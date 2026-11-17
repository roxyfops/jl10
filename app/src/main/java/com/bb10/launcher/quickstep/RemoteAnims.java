package com.bb10.launcher.quickstep;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import com.bb10.launcher.ui.ActiveFramesPage;
import com.bb10.launcher.ui.Ui;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class RemoteAnims {
    private static final String TAG = "BB10RemoteAnims";
    public static final String PERM = "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS";
    private static final int MODE_OPENING = 0, MODE_CLOSING = 1;
    private static final long DURATION = 340;
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static volatile RectF pendingCloseFrom;
    public static volatile Runnable onCloseStarted;
    public static volatile Runnable onCloseFinished;
    public static volatile long closeStartedAt;
    public static volatile long closeEndedAt;

    public static volatile Runnable onCloseBegun;
    public static volatile boolean openQueued;

    public static volatile long zoomStartAt;

    public static volatile Runnable onOpenAnimStart;

    public static volatile String openPkg;
    public static volatile String neverShown;
    public static volatile long neverShownAt;
    public static volatile long openRequestedAt;
    public static volatile String shownPkg;
    public static volatile long shownAt;
    public static volatile boolean shownReported;

    public static void appShown(String pkg) {
        shownAt = android.os.SystemClock.uptimeMillis();
        shownPkg = pkg;
    }

    public static volatile int openTaskId = -1;
    public static volatile boolean openSettled = true;

    private static final java.util.HashMap<Integer, long[]> sSplashes = new java.util.HashMap<>();
    private static final java.util.HashMap<Integer, String> sSplashPkgs = new java.util.HashMap<>();
    public static final int SPLASH_OTHER = 0, SPLASH_SCREEN = 1, SPLASH_SNAPSHOT = 2;

    public static volatile long bouncerGoneAt;

    public static android.os.Bundle splash(android.os.Bundle ex) {
        android.os.Bundle r = new android.os.Bundle();
        if (ex == null) return r;
        long now = android.os.SystemClock.uptimeMillis();
        String ev = ex.getString("event");
        int task = ex.getInt("task", -1);
        synchronized (sSplashes) {
            sSplashes.values().removeIf(s -> now - Math.max(s[0], s[1]) > 30_000);
            sSplashPkgs.keySet().retainAll(sSplashes.keySet());
            long[] s = sSplashes.get(task);
            if ("asked".equals(ev)) {
                sSplashes.put(task, new long[]{now, 0, SPLASH_OTHER, 0, 0, now, ex.getInt("type", -1), 0});
                sSplashPkgs.put(task, ex.getString("pkg"));
                String pkg = ex.getString("pkg");
                if (pkg != null) sAsked.put(pkg, new long[]{now, ex.getInt("type", -1), task});
            } else if ("added".equals(ev)) {
                String kind = ex.getString("kind");
                boolean same = s != null && s[1] == 0 && s[4] == 0 && now - s[5] < 10_000;
                sSplashes.put(task, new long[]{now, 0, "splash".equals(kind) ? SPLASH_SCREEN : "snapshot".equals(kind) ? SPLASH_SNAPSHOT : SPLASH_OTHER, 0, 0,
                        same ? s[5] : 0, same ? s[6] : -1, 0});
                sSplashPkgs.put(task, ex.getString("pkg"));
            } else if (s == null) {
                if ("removing".equals(ev)) r.putBoolean("quiet", isOpening(task, ex.getString("pkg"), 0, now));
            } else if ("removing".equals(ev)) {
                s[4] = now;
                String pkg = sSplashPkgs.get(task);
                boolean quiet = isOpening(task, pkg != null ? pkg : ex.getString("pkg"), s[0], now);
                r.putBoolean("quiet", quiet);
                if (quiet && ownSplashOn() && OwnSplash.covering(pkg != null ? pkg : ex.getString("pkg"))) r.putBoolean("hideNow", true);
                s[7] = quiet || s[2] == SPLASH_SNAPSHOT || s[6] == 2 ? 0 : 1;
            } else if ("removed".equals(ev)) {
                if (s[3] == 0 && s[1] == 0) s[1] = now;
            } else if ("copied".equals(ev)) {
                s[3] = 1;
                String pkg = sSplashPkgs.get(task);
                if (pkg != null && ownSplashOn() && OwnSplash.covering(pkg)
                        && com.bb10.launcher.gesture.GestureService.watchAppSplash(pkg, task)) sWatched.add(task);
            } else if ("appRemoved".equals(ev)) {
                if (s[1] == 0) s[1] = now;
            }
        }
        android.util.Log.i("BB10Open", "splash " + ev + " task " + task + (ex.containsKey("kind") ? " " + ex.getString("kind") : "")
                + (ex.containsKey("type") ? " type=" + ex.getInt("type") + (ex.containsKey("why") ? " (" + ex.getString("why") + ")" : "") : "")
                + (r.containsKey("quiet") ? " quiet=" + r.getBoolean("quiet") : "")
                + (ex.containsKey("reveal") ? " reveal=" + ex.getBoolean("reveal") + " leash=" + ex.getBoolean("leash") : ""));
        return r;
    }

    private static boolean isOpening(int task, String pkg, long up, long now) {
        String op = openPkg;
        long req = openRequestedAt;
        if (op == null || openSettled || now - req > 10_000) return false;
        int ot = openTaskId;
        boolean it = task == ot || (ot == -1 && (op.equals(pkg) || (up >= req && up - req < 2_000)));
        if (it && ownSplashOn()) return (ownHandedOff || OwnSplash.quickSkipOn()) && OwnSplash.covering(op) && splashComing(op, task, req) == 1;
        return it;
    }

    static volatile boolean ownHandedOff;

    private static boolean ownSplashOn() {
        com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
        return app != null && app.prefs != null && app.prefs.bool(com.bb10.launcher.Prefs.OWN_SPLASH, false);
    }

    public static long[] splashOf(int task, long since) {
        synchronized (sSplashes) {
            long[] s = sSplashes.get(task);
            return s != null && s[0] >= since ? s.clone() : null;
        }
    }

    private static final java.util.Set<Integer> sWatched = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void appSplashGone(int task, String how) {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (sSplashes) {
            long[] s = sSplashes.get(task);
            if (s != null && s[1] == 0) s[1] = now;
        }
        sWatched.remove(task);
        android.util.Log.i("BB10Open", "splash animated away by the app, task " + task + ": " + how);
    }

    private static final java.util.Map<String, long[]> sAsked = new java.util.concurrent.ConcurrentHashMap<>();

    public static int splashComing(String pkg, int task, long requested) {
        long[] a = null;
        if (task != -1) {
            synchronized (sSplashes) {
                long[] s = sSplashes.get(task);
                if (s != null && s[5] >= requested) a = new long[]{s[5], s[6], task};
            }
        }
        if (a == null && pkg != null) {
            long[] p = sAsked.get(pkg);
            if (p != null && p[0] >= requested) a = p;
        }
        if (a == null) return -1;
        int type = (int) a[1];
        return type == 1 || type == 4 || type == -1 ? 1 : 0;
    }

    public static final long OWN_WAIT_MAX = 6000;
    private static final long GONE_LAG = 32;
    private static final long SPLASH_EXIT_MS = 83 + 266 + 50;
    private static final long APP_EXIT_MAX = 600;

    static long ownReady(int task, long requested, long animStart, long now) {
        if (now - animStart > OWN_WAIT_MAX) return now;
        long[] s;
        synchronized (sSplashes) {
            s = task == -1 ? null : sSplashes.get(task);
            if (s != null) s = s.clone();
        }
        if (s == null || Math.max(s[0], s[5]) < requested) return animStart;
        if (s[3] != 0) {
            if (s[1] > 0) return s[1] + GONE_LAG;
            if (sWatched.contains(task)) return 0;
            long guess = s[4] > 0 ? s[4] + APP_EXIT_MAX : 0;
            return guess != 0 && now >= guess ? guess : 0;
        }
        long gone = s[4] > 0 ? s[4] : s[1];
        if (gone <= 0) return 0;
        if (s[7] != 0) return Math.max(s[4] + SPLASH_EXIT_MS, s[1]) + GONE_LAG;
        return gone + GONE_LAG;
    }

    public static final long ZOOM_PENDING = Long.MAX_VALUE;

    public static volatile float zoomFromDx;

    public static float openInterp(float x) {
        return OPEN_EASE.getInterpolation(Math.max(0f, Math.min(1f, x)));
    }

    static final PathInterpolator OPEN_EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static Boolean sHidden;
    private static boolean sCloseRegistered;
    private static ValueAnimator sCurrent;

    private RemoteAnims() {}

    public static synchronized boolean hiddenApisOk() {
        if (sHidden == null) {
            try {
                if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("");
            } catch (Throwable t) {
                Log.w(TAG, "hidden api bypass failed", t);
            }
            try {
                Class.forName("android.view.RemoteAnimationDefinition").getConstructor();
                ActivityOptions.class.getMethod("makeRemoteAnimation", Class.forName("android.view.RemoteAnimationAdapter"));
                sHidden = true;
            } catch (Throwable t) {
                Log.w(TAG, "remote animation APIs not reachable", t);
                sHidden = false;
            }
        }
        return sHidden;
    }

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean available(Context c) {
        return hasPermission(c) && hiddenApisOk();
    }

    public static boolean closeRegistered() {
        return sCloseRegistered;
    }

    public static Bundle launchOptions(Context c, Rect from) {
        ActivityOptions o = launchActivityOptions(c, from);
        if (o == null) return null;
        if (android.os.Build.VERSION.SDK_INT >= 34)
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return o.toBundle();
    }

    public static ActivityOptions launchActivityOptions(Context c, Rect from) {
        if (!available(c)) return null;
        try {
            Object adapter = newAdapter(new Runner(false, new RectF(from)), DURATION, DURATION / 3);
            Method m = ActivityOptions.class.getMethod("makeRemoteAnimation", Class.forName("android.view.RemoteAnimationAdapter"));
            return (ActivityOptions) m.invoke(null, adapter);
        } catch (Throwable t) {
            Log.w(TAG, "launchOptions", t);
            return null;
        }
    }

    public static ActivityOptions growFrom(Context c, android.view.View from) {
        Rect r = null;
        if (from != null && from.isAttachedToWindow() && from.getWidth() > 0) {
            int[] l = new int[2];
            from.getLocationOnScreen(l);
            r = new Rect(l[0], l[1], l[0] + from.getWidth(), l[1] + from.getHeight());
        }
        return growFrom(c, r);
    }

    public static ActivityOptions growFrom(Context c, Rect r) {
        ActivityOptions o = null;
        if (r != null && !r.isEmpty()) {
            o = ShellTransitions.canLaunch(c) ? ShellTransitions.launchActivityOptions(c, r) : launchActivityOptions(c, r);
        }
        if (o == null) o = ActivityOptions.makeBasic();
        if (android.os.Build.VERSION.SDK_INT >= 34)
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return o;
    }

    public static void registerClose(Activity a) {
        if (!available(a)) return;
        try {
            Class<?> defCls = Class.forName("android.view.RemoteAnimationDefinition");
            Class<?> adCls = Class.forName("android.view.RemoteAnimationAdapter");
            Object def = defCls.getConstructor().newInstance();
            Method add = defCls.getMethod("addRemoteAnimation", int.class, int.class, adCls);
            add.invoke(def, transitWallpaperOpen(), 1 , newAdapter(new Runner(true, null), DURATION, 0));
            Activity.class.getMethod("registerRemoteAnimations", defCls).invoke(a, def);
            sCloseRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "registerClose", t);
            sCloseRegistered = false;
        }
    }

    public static void unregister(Activity a) {
        if (!sCloseRegistered) return;
        try {
            Activity.class.getMethod("unregisterRemoteAnimations").invoke(a);
        } catch (Throwable ignored) {
        }
        sCloseRegistered = false;
    }

    private static int transitWallpaperOpen() {
        for (String n : new String[]{"TRANSIT_OLD_WALLPAPER_OPEN", "TRANSIT_WALLPAPER_OPEN"}) {
            try {
                return WindowManager.class.getField(n).getInt(null);
            } catch (Throwable ignored) {
            }
        }
        return 13;
    }

    private static Object newAdapter(Runner r, long duration, long statusBarDelay) throws Exception {
        Class<?> itf = Class.forName("android.view.IRemoteAnimationRunner");
        Object runner = Class.forName("android.view.IRemoteAnimationRunner$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, r);
        return Class.forName("android.view.RemoteAnimationAdapter")
                .getConstructor(itf, long.class, long.class)
                .newInstance(runner, duration, statusBarDelay);
    }

    private static final class Runner extends Binder {
        static final String DESC = "android.view.IRemoteAnimationRunner";
        final boolean closing;
        final RectF from;

        Runner(boolean closing, RectF from) {
            this.closing = closing;
            this.from = from;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESC);
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION) {
                data.enforceInterface(DESC);
                Object[] apps = null;
                IBinder finished = null;
                try {
                    if (Build.VERSION.SDK_INT >= 31) data.readInt();
                    Parcelable.Creator cr = creator();
                    apps = data.createTypedArray(cr);
                    data.createTypedArray(cr);
                    if (Build.VERSION.SDK_INT >= 31) data.createTypedArray(cr);
                    finished = data.readStrongBinder();
                } catch (Throwable t) {
                    Log.w(TAG, "parse", t);
                }
                Object[] fApps = apps;
                IBinder fFinished = finished;
                MAIN.post(() -> start(closing, from, fApps, fFinished));
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 1) {
                MAIN.post(RemoteAnims::cancelCurrent);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static Parcelable.Creator<?> sCreator;

    private static Parcelable.Creator<?> creator() throws Exception {
        if (sCreator == null) {
            sCreator = (Parcelable.Creator<?>) Class.forName("android.view.RemoteAnimationTarget").getField("CREATOR").get(null);
        }
        return sCreator;
    }

    private static Object f(Object o, String name) {
        try {
            Field fl = o.getClass().getField(name);
            return fl.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static void cancelCurrent() {
        if (sCurrent != null) sCurrent.end();
    }

    private static void finish(IBinder cb) {
        if (cb == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken("android.view.IRemoteAnimationFinishedCallback");
            cb.transact(IBinder.FIRST_CALL_TRANSACTION, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static void start(boolean closing, RectF launchFrom, Object[] apps, IBinder finished) {
        if (apps == null) {
            finish(finished);
            return;
        }
        Object opening = null, closingT = null;
        List<SurfaceControl> leashes = new ArrayList<>();
        for (Object t : apps) {
            Object m = f(t, "mode");
            Object l = f(t, "leash");
            if (l instanceof SurfaceControl) leashes.add((SurfaceControl) l);
            if (m instanceof Integer && (Integer) m == MODE_OPENING) opening = t;
            else if (m instanceof Integer && (Integer) m == MODE_CLOSING) closingT = t;
        }
        Object moving = closing ? closingT : opening;
        Object other = closing ? opening : closingT;
        if (moving == null) {
            Tx tx = new Tx();
            if (other != null) tx.show((SurfaceControl) f(other, "leash"));
            tx.apply();
            finish(finished);
            return;
        }
        SurfaceControl leash = (SurfaceControl) f(moving, "leash");
        Rect bounds = (Rect) f(moving, "screenSpaceBounds");
        if (bounds == null) bounds = (Rect) f(moving, "sourceContainerBounds");
        Point pos = (Point) f(moving, "position");
        if (pos == null) pos = new Point();
        final Rect b = bounds != null ? new Rect(bounds) : new Rect(0, 0, 720, 720);
        final Point p0 = pos;
        RectF full = new RectF(b);

        RectF startR, endR;
        if (closing) {
            com.bb10.launcher.LauncherApp.get().frames.snapshotNow(com.bb10.launcher.LauncherApp.get().frames.lastForeground());
            RectF pending = pendingCloseFrom;
            pendingCloseFrom = null;
            startR = pending != null ? pending : full;
            Rect slot = ActiveFramesPage.slotFor(com.bb10.launcher.LauncherApp.get().frames.lastForeground());
            if (slot.width() <= 0) {
                int gap = Ui.dp(5), top = Ui.dp(26) + gap;
                int fw = (b.width() - 3 * gap) / 2, fh = (b.height() - Ui.dp(26) - Ui.dp(44) - 3 * gap) / 2;
                slot.set(gap, top, gap + fw, top + fh);
            }
            endR = new RectF(slot);
        } else {
            startR = launchFrom != null ? launchFrom : full;
            endR = full;
        }
        final boolean fromFrame = !closing && launchFrom != null && pendingFrameLaunch;
        final boolean live = fromFrame && pendingFrameLive;
        if (!closing) {
            pendingFrameLaunch = false;
            pendingFrameLive = false;
        }

        SurfaceControl otherLeash = other == null ? null : (SurfaceControl) f(other, "leash");
        Tx first = new Tx();
        if (otherLeash != null) {
            first.show(otherLeash);
            first.alpha(otherLeash, 1f);
            first.layer(otherLeash, 0);
        }
        first.show(leash);
        first.layer(leash, 100);
        applyFrame(first, leash, b, p0, startR, live ? 0f : 1f, fromFrame ? openTrimTop(0f) : 0f, fromFrame ? openTrim(0f) : 0f);
        first.apply();
        dropLiveInfo();

        if (closing) {
            Runnable r = onCloseStarted;
            onCloseStarted = null;
            if (r != null) MAIN.post(r);
        }

        cancelCurrent();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(DURATION);
        va.setInterpolator(closing ? new PathInterpolator(0.3f, 0f, 0.1f, 1f) : new DecelerateInterpolator(1.8f));
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            RectF r = lerp(startR, endR, t);
            Tx tx = new Tx();
            float top = closing ? closeTrimTop(t) : fromFrame ? openTrimTop(t) : 0f;
            float bottom = closing ? closeTrim(t) : fromFrame ? openTrim(t) : 0f;
            applyFrame(tx, leash, b, p0, r, live ? liveOpenAlpha(Math.min(1f, a.getCurrentPlayTime() / (float) DURATION)) : 1f, top, bottom);
            tx.apply();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean done;

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (done) return;
                done = true;
                if (sCurrent == va) sCurrent = null;
                if (!closing) {
                    Tx tx = new Tx();
                    applyFrame(tx, leash, b, p0, full, 1f);
                    tx.apply();
                }
                finish(finished);
                for (SurfaceControl s : leashes) {
                    try {
                        s.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        sCurrent = va;
        va.start();
    }

    static RectF lerp(RectF a, RectF b, float t) {
        return new RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    static void applyFrame(Tx tx, SurfaceControl leash, Rect b, Point pos, RectF r, float alpha) {
        applyFrame(tx, leash, b, pos, r, alpha, 0f, 0f);
    }

    static void applyFrame(Tx tx, SurfaceControl leash, Rect b, Point pos, RectF r, float alpha, float trimTop, float trimBottom) {
        int W = b.width(), H = b.height();
        float s = r.width() / Math.max(1, W);
        int top = Math.round(Math.max(0f, Math.min(trimTop, H / 4f)));
        float cropH = Math.min(H - top, Math.max(1f, r.height() - trimBottom) / Math.max(0.0001f, s));
        tx.crop(leash, new Rect(0, top, W, top + Math.round(cropH)));
        tx.scale(leash, s);
        tx.position(leash, pos.x + (r.left - b.left), pos.y + (r.top - b.top) - top * s);
        tx.alpha(leash, alpha);
    }

    static void applyCover(Tx tx, SurfaceControl sc, Rect b, Point pos, RectF r, float alpha) {
        int W = Math.max(1, b.width()), H = Math.max(1, b.height());
        float s = Math.max(r.width() / W, r.height() / H);
        float vw = Math.min(W, r.width() / s), vh = Math.min(H, r.height() / s);
        float left = (W - vw) / 2f, top = (H - vh) / 2f;
        tx.crop(sc, new Rect(Math.round(left), Math.round(top), Math.round(left + vw), Math.round(top + vh)));
        tx.scale(sc, s);
        tx.position(sc, pos.x + (r.left - b.left) - left * s, pos.y + (r.top - b.top) - top * s);
        tx.alpha(sc, alpha);
    }

    static int statusInset() {
        if (sStatusInset < 0) {
            android.content.res.Resources res = android.content.res.Resources.getSystem();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            sStatusInset = id > 0 ? res.getDimensionPixelSize(id) : 0;
        }
        return sStatusInset;
    }

    private static int sStatusInset = -1;

    static float closeTrim(float t) {
        return Ui.dp(ActiveFramesPage.BAR_DP) * ease(t);
    }

    static float closeTrimTop(float t, float inset) {
        return inset * ease(t);
    }

    static float openTrimTop(float t, float inset) {
        return inset * (1f - openEase(t));
    }

    static float closeTrimTop(float t) {
        return statusInset() * ease(t);
    }

    private static float ease(float t) {
        float k = Math.max(0f, Math.min(1f, (t - 0.5f) / 0.5f));
        return k * k * (3f - 2f * k);
    }

    public static volatile boolean pendingFrameLaunch;
    public static volatile boolean pendingFrameLive;

    public static volatile SurfaceControl pendingLiveInfo;
    public static volatile Rect pendingLiveSize;

    public static SurfaceControl liveInfoSurface(android.hardware.HardwareBuffer buf) {
        if (android.os.Build.VERSION.SDK_INT < 33 || buf == null) return null;
        try {
            SurfaceControl sc = new SurfaceControl.Builder().setName("BB10 live frame")
                    .setBufferSize(buf.getWidth(), buf.getHeight()).setHidden(true).build();
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setBuffer(sc, buf);
            t.apply();
            return sc;
        } catch (Throwable e) {
            Log.w(TAG, "live frame surface", e);
            return null;
        }
    }

    public static void dropLiveInfo() {
        SurfaceControl sc = pendingLiveInfo;
        pendingLiveInfo = null;
        pendingLiveSize = null;
        if (sc == null) return;
        try {
            new SurfaceControl.Transaction().reparent(sc, null).apply();
            OwnSplash.forget(sc);
            sc.release();
        } catch (Throwable ignored) {
        }
    }

    static float liveOpenAlpha(float elapsed) {
        float k = Math.max(0f, Math.min(1f, elapsed));
        return k * k * (3f - 2f * k);
    }

    static float openTrim(float t) {
        return Ui.dp(ActiveFramesPage.BAR_DP) * (1f - openEase(t));
    }

    static float openTrimTop(float t) {
        return statusInset() * (1f - openEase(t));
    }

    private static float openEase(float t) {
        float k = Math.max(0f, Math.min(1f, t / 0.5f));
        return k * k * (3f - 2f * k);
    }

    static final class Tx {
        private static Method sMatrix, sScale, sPos, sCrop, sAlpha, sLayer, sVis;

        static {
            Class<?> c = SurfaceControl.Transaction.class;
            sMatrix = m(c, "setMatrix", SurfaceControl.class, float.class, float.class, float.class, float.class);
            sScale = m(c, "setScale", SurfaceControl.class, float.class, float.class);
            sPos = m(c, "setPosition", SurfaceControl.class, float.class, float.class);
            sCrop = m(c, "setWindowCrop", SurfaceControl.class, Rect.class);
            if (sCrop == null) sCrop = m(c, "setCrop", SurfaceControl.class, Rect.class);
            sAlpha = m(c, "setAlpha", SurfaceControl.class, float.class);
            sLayer = m(c, "setLayer", SurfaceControl.class, int.class);
            sVis = m(c, "setVisibility", SurfaceControl.class, boolean.class);
        }

        private static Method m(Class<?> c, String n, Class<?>... args) {
            try {
                return c.getMethod(n, args);
            } catch (Throwable t) {
                return null;
            }
        }

        final SurfaceControl.Transaction t;

        Tx() { this(new SurfaceControl.Transaction()); }

        Tx(SurfaceControl.Transaction existing) { t = existing; }

        private void call(Method m, Object... a) {
            if (m == null) return;
            try {
                m.invoke(t, a);
            } catch (Throwable ignored) {
            }
        }

        void scale(SurfaceControl s, float k) {
            scale(s, k, k);
        }

        private static final boolean DIRECT = Build.VERSION.SDK_INT >= 33;

        void scale(SurfaceControl s, float sx, float sy) {
            if (sMatrix != null) call(sMatrix, s, sx, 0f, 0f, sy);
            else call(sScale, s, sx, sy);
        }

        void position(SurfaceControl s, float x, float y) {
            if (DIRECT) {
                try {
                    t.setPosition(s, x, y);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sPos, s, x, y);
        }

        void crop(SurfaceControl s, Rect r) {
            if (DIRECT) {
                try {
                    t.setCrop(s, r);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sCrop, s, r);
        }

        void alpha(SurfaceControl s, float a) {
            if (DIRECT) {
                try {
                    t.setAlpha(s, a);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sAlpha, s, a);
        }

        void layer(SurfaceControl s, int l) {
            if (DIRECT) {
                try {
                    t.setLayer(s, l);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sLayer, s, l);
        }

        void show(SurfaceControl s) {
            if (s == null) return;
            if (DIRECT) {
                try {
                    t.setVisibility(s, true);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sVis, s, true);
        }

        void hide(SurfaceControl s) {
            if (s == null) return;
            if (DIRECT) {
                try {
                    t.setVisibility(s, false);
                    return;
                } catch (Throwable ignored) {
                }
            }
            call(sVis, s, false);
        }

        void apply() {
            try {
                t.apply();
            } catch (Throwable ignored) {
            }
        }

        private static Method sVsyncId, sSetVsync;
        private static boolean sPacingLooked;

        void applyPaced() {
            try {
                if (!sPacingLooked) {
                    sPacingLooked = true;
                    try {
                        sVsyncId = android.view.Choreographer.class.getDeclaredMethod("getVsyncId");
                        sVsyncId.setAccessible(true);
                        sSetVsync = SurfaceControl.Transaction.class.getDeclaredMethod("setFrameTimelineVsync", long.class);
                        sSetVsync.setAccessible(true);
                    } catch (Throwable e) {
                        sVsyncId = sSetVsync = null;
                    }
                }
                if (sVsyncId != null && sSetVsync != null && Looper.myLooper() != null) {
                    Object id = sVsyncId.invoke(android.view.Choreographer.getInstance());
                    if (id instanceof Long && (Long) id > 0) sSetVsync.invoke(t, id);
                }
            } catch (Throwable ignored) {
            }
            apply();
        }
    }
}
