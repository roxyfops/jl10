package com.bb10.launcher.quickstep;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.SurfaceControl;

public final class SurfaceZoom {
    private SurfaceZoom() {}

    private static SurfaceControl sc;
    private static Rect size;
    private static final RectF from = new RectF(), to = new RectF(), r = new RectF();
    private static boolean startOnFirstFrame;
    private static int run;
    private static SurfaceControl handedOver;
    private static volatile long lastFrameAt;
    private static SurfaceControl.Transaction home;
    private static boolean lifted;

    static long lastFrameAt() {
        return lastFrameAt;
    }

    public static boolean start(SurfaceControl.Transaction attach, SurfaceControl.Transaction reattach, SurfaceControl look,
                                Rect lookSize, RectF at, int w, int h, boolean startNow) {
        if (attach == null || look == null || lookSize == null || at == null) return false;
        RectF a = new RectF(at);
        ShellTransitions.ANIM.post(() -> {
            if (!look.isValid() || look == handedOver) return;
            int me = ++run;
            lastFrameAt = 0;
            sc = look;
            size = lookSize;
            home = reattach;
            lifted = false;
            from.set(a);
            to.set(0, 0, w, h);
            startOnFirstFrame = startNow;
            final OwnSplash own = OwnSplash.of(look);
            final Point origin = new Point(0, 0);
            RemoteAnims.Tx t = new RemoteAnims.Tx(attach);
            t.layer(look, Integer.MAX_VALUE);
            t.alpha(look, 0f);
            t.show(look);
            t.apply();
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                long prev;
                final RemoteAnims.Tx tx = new RemoteAnims.Tx();

                @Override
                public void doFrame(long frameTimeNanos) {
                    if (me != run || sc != look || !look.isValid()) return;
                    long now = frameTimeNanos / 1_000_000L;
                    long frame = prev > 0 && now > prev ? Math.min(34, now - prev) : 16;
                    prev = now;
                    long st = RemoteAnims.zoomStartAt;
                    if (st == RemoteAnims.ZOOM_PENDING && startOnFirstFrame) {
                        RemoteAnims.zoomStartAt = st = now;
                        startOnFirstFrame = false;
                    }
                    if (st == 0) return;
                    if (st == RemoteAnims.ZOOM_PENDING || now < st) {
                        Choreographer.getInstance().postFrameCallback(this);
                        return;
                    }
                    if (lastFrameAt == 0 && now - st > frame) {
                        long nst = now - frame;
                        if (RemoteAnims.zoomStartAt == st) RemoteAnims.zoomStartAt = nst;
                        st = nst;
                    }
                    float e = (now - st) / (float) ShellTransitions.DURATION;
                    float k = RemoteAnims.openInterp(e);
                    float dx = RemoteAnims.zoomFromDx;
                    r.set(from.left + dx + (to.left - from.left - dx) * k, from.top + (to.top - from.top) * k,
                            from.right + dx + (to.right - from.right - dx) * k, from.bottom + (to.bottom - from.bottom) * k);
                    float alpha = own != null ? own.step(tx, origin, r, e, k, -1) : 1f;
                    RemoteAnims.applyCover(tx, look, size, origin, r, alpha);
                    tx.applyPaced();
                    lastFrameAt = now;
                    if (e < 1f) Choreographer.getInstance().postFrameCallback(this);
                }
            });
        });
        return true;
    }

    static void liftAbove(RemoteAnims.Tx tx, SurfaceControl root, int layer) {
        if (lifted || sc == null || !sc.isValid() || home == null || root == null) return;
        tx.t.reparent(sc, root);
        tx.layer(sc, layer);
        lifted = true;
        ShellTransitions.note("zoom: lifted over the closing app");
    }

    static boolean lower(RemoteAnims.Tx tx) {
        if (!lifted) return false;
        lifted = false;
        if (sc == null || !sc.isValid() || home == null) return false;
        tx.t.merge(home);
        home = null;
        tx.layer(sc, Integer.MAX_VALUE);
        ShellTransitions.note("zoom: back in the launcher's window, as the close ends");
        return true;
    }

    static void handOver() {
        run++;
        handedOver = RemoteAnims.pendingLiveInfo;
        sc = null;
        home = null;
        lifted = false;
    }

    public static void cancel() {
        ShellTransitions.ANIM.post(() -> {
            run++;
            sc = null;
            lastFrameAt = 0;
            home = null;
            lifted = false;
        });
    }

    public static void discard(SurfaceControl look) {
        if (look == null) return;
        ShellTransitions.ANIM.post(() -> {
            if (look == handedOver || look == RemoteAnims.pendingLiveInfo) return;
            if (sc == look) {
                run++;
                sc = null;
            }
            try {
                new SurfaceControl.Transaction().reparent(look, null).apply();
                OwnSplash.forget(look);
                look.release();
            } catch (Throwable ignored) {
            }
        });
    }
}
