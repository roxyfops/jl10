package com.bb10.launcher.quickstep;

import android.graphics.Rect;
import android.view.SurfaceControl;

public final class LauncherChrome {
    private LauncherChrome() {}

    public static final class Piece {
        public final SurfaceControl sc;
        public final Rect at;

        public Piece(SurfaceControl sc, Rect at) {
            this.sc = sc;
            this.at = at;
        }

        void release() {
            try {
                new SurfaceControl.Transaction().reparent(sc, null).apply();
                sc.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Piece dock, bar, under;
    public static volatile Runnable refresher;

    public static volatile java.util.function.Consumer<java.util.function.Consumer<Piece>> underNow;

    public static final class LiveDock {
        public final android.view.SurfaceControl sc, home;
        public final Rect at;

        public LiveDock(android.view.SurfaceControl sc, Rect at, android.view.SurfaceControl home) {
            this.sc = sc;
            this.at = at;
            this.home = home;
        }

        boolean ok() {
            return sc != null && sc.isValid() && home != null && home.isValid() && at != null && at.width() > 0;
        }
    }

    public static volatile LiveDock liveDock;
    public static volatile boolean dockBusy;

    public static volatile boolean dockHidden;

    public static volatile java.util.function.BiConsumer<android.view.SurfaceControl.Transaction, Runnable> endWithDock;

    static volatile long setAt, takeAt, giveAt;
    static volatile boolean setBar, gaveBar;

    static void missingBar(android.content.Context c, boolean closing) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            String line = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())
                    + (closing ? " close" : " open") + ": no status bar picture; made " + (setAt > 0 ? (now - setAt) + " ms ago" : "never")
                    + (setBar ? " with" : " without") + " the bar, last taken " + (takeAt > 0 ? (now - takeAt) + " ms ago" : "never")
                    + ", given back " + (giveAt > 0 ? (now - giveAt) + " ms ago" + (gaveBar ? " with" : " without") + " the bar" : "never")
                    + ", refresher " + (refresher != null ? "set" : "none") + "\n";
            android.util.Log.w("BB10Open", line.trim());
            java.io.File f = new java.io.File(c.getFilesDir(), "chrome-missing.txt");
            if (f.length() > 64 * 1024) f.delete();
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(line);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void set(Piece d, Piece b, Piece u) {
        setAt = android.os.SystemClock.uptimeMillis();
        setBar = b != null;
        Piece od, ob, ou;
        synchronized (LauncherChrome.class) {
            od = dock;
            ob = bar;
            ou = under;
            dock = d;
            bar = b;
            under = u;
        }
        if (od != null) od.release();
        if (ob != null) ob.release();
        if (ou != null) ou.release();
    }

    static Piece[] take() {
        takeAt = android.os.SystemClock.uptimeMillis();
        Piece[] r;
        synchronized (LauncherChrome.class) {
            r = new Piece[]{dock, bar, under};
            dock = bar = under = null;
        }
        Runnable f = refresher;
        if (f != null) {
            RemoteAnims.MAIN.removeCallbacks(f);
            RemoteAnims.MAIN.postDelayed(f, 700);
        }
        return r;
    }

    static void drop(Piece[] p) {
        if (p == null) return;
        for (Piece x : p) if (x != null) x.release();
    }

    static void giveBack(Piece[] p) {
        if (p == null) return;
        giveAt = android.os.SystemClock.uptimeMillis();
        gaveBar = p.length > 1 && p[1] != null;
        Piece d = p.length > 0 ? p[0] : null, b = p.length > 1 ? p[1] : null, u = p.length > 2 ? p[2] : null;
        synchronized (LauncherChrome.class) {
            if (d != null && dock == null && d.sc.isValid()) {
                dock = d;
                d = null;
            }
            if (b != null && bar == null && b.sc.isValid()) {
                bar = b;
                b = null;
            }
            if (u != null && under == null && u.sc.isValid()) {
                under = u;
                u = null;
            }
        }
        if (d != null) d.release();
        if (b != null) b.release();
        if (u != null) u.release();
    }
}
