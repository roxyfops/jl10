package com.bb10.launcher.quickstep;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import com.bb10.launcher.LauncherActivity;
import com.bb10.launcher.ui.ActiveFramesPage;
import com.bb10.launcher.ui.Ui;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class ShellTransitions {
    private static final String TAG = "BB10Shell";
    private static final int TRANSIT_OPEN = 1, TRANSIT_CLOSE = 2, TRANSIT_TO_FRONT = 3, TRANSIT_TO_BACK = 4;
    private static final int ACTIVITY_TYPE_STANDARD = 1, ACTIVITY_TYPE_HOME = 2;
    private static final int FLAG_IS_WALLPAPER = 1 << 1;
    public static final long DURATION = 340;

    private static final long FADE = 200;
    private static final long LATE_FADE = 200;
    private static final long ZOOM_WAIT_MAX = 700;
    private static final long SHOW_WAIT_MAX = 3000;

    private static long appUp(String pkg, String taskPkg, int task, long requested, long animStart, long now) {
        if (requested == 0) return animStart;
        long[] s = task == -1 ? null : RemoteAnims.splashOf(task, requested);
        if (s != null && s[2] != RemoteAnims.SPLASH_OTHER) {
            if (s[2] == RemoteAnims.SPLASH_SNAPSHOT) return Math.max(s[0] + 20, animStart);
            if (s[1] > 0) {
                long gone = s[1] + 20;
                return now >= gone ? Math.max(gone, animStart) : 0;
            }
            return now - animStart > SHOW_WAIT_MAX ? now : 0;
        }
        if (!RemoteAnims.shownReported) return animStart;
        String sp = RemoteAnims.shownPkg;
        long sa = RemoteAnims.shownAt;
        if (sp != null && sa >= requested && (sp.equals(pkg) || sp.equals(taskPkg))) return Math.max(sa, animStart);
        if (now - animStart > SHOW_WAIT_MAX) return now;
        return 0;
    }

    private static String splashState(int task, long since) {
        long[] s = task == -1 ? null : RemoteAnims.splashOf(task, since);
        if (s == null) return "no splash: window came up";
        String kind = s[2] == RemoteAnims.SPLASH_SNAPSHOT ? "last picture" : s[2] == RemoteAnims.SPLASH_SCREEN ? "splash" : "other starting window";
        return kind + (s[3] != 0 ? " (animated away by the app)" : "") + (s[1] > 0 ? " gone " + (s[1] - s[0]) + " ms after it went up" : " still up");
    }
    private static int taskId(Object change) {
        Object ti = call(change, "getTaskInfo");
        return ti instanceof android.app.TaskInfo ? ((android.app.TaskInfo) ti).taskId : -1;
    }

    private static String taskPkg(Object change) {
        try {
            Object ti = call(change, "getTaskInfo");
            if (ti == null) return null;
            for (String f : new String[]{"topActivity", "baseActivity"}) {
                Object cn = ti.getClass().getField(f).get(ti);
                if (cn instanceof ComponentName) return ((ComponentName) cn).getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    private static final int REGISTER_REMOTE = IBinder.FIRST_CALL_TRANSACTION + 1;

    static final android.os.Handler ANIM;

    static {
        android.os.HandlerThread t = new android.os.HandlerThread("bb10-window-anim", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        t.start();
        ANIM = new android.os.Handler(t.getLooper());
    }

    private static volatile IBinder sShell;
    private static volatile boolean sHomeRegistered;
    private static Object sHomeRemote;
    private static ValueAnimator sCurrent;
    private static volatile int sAnimOpenTask = -1;
    private static volatile String sAnimOpenPkg;
    private static SurfaceControl.Transaction sMerged;

    private static void takeMerged(RemoteAnims.Tx tx) {
        if (sMerged == null) return;
        tx.t.merge(sMerged);
        sMerged = null;
    }

    private static boolean sameTaskOnward(Object info, int task) {
        Object list = call(info, "getChanges");
        if (!(list instanceof java.util.List)) return false;
        int occluded = 0;
        try {
            occluded = Class.forName("android.window.TransitionInfo").getField("FLAG_IS_OCCLUDED").getInt(null);
        } catch (Throwable ignored) {
        }
        boolean saw = false;
        for (Object c : (java.util.List<?>) list) {
            int tid = taskId(c);
            if (tid == -1) {
                int at = -1;
                Object ati = call(c, "getActivityTransitionInfo");
                Object v = ati != null ? call(ati, "getTaskId") : null;
                if (v instanceof Integer) at = (Integer) v;
                if (at != -1) {
                    if (at != task) return false;
                    saw = true;
                    continue;
                }
                Object cn = call(c, "getActivityComponent");
                if (cn instanceof ComponentName) {
                    if (!((ComponentName) cn).getPackageName().equals(sAnimOpenPkg)) return false;
                    saw = true;
                }
                continue;
            }
            Object m = call(c, "getMode");
            int mode = m instanceof Integer ? (Integer) m : 0;
            if (tid == task) {
                if (mode == 2 || mode == 4) return false;
                saw = true;
                continue;
            }
            if (activityType(c) == 2) {
                Object f = call(c, "getFlags");
                int flags = f instanceof Integer ? (Integer) f : 0;
                if (mode == 3 && (occluded == 0 || (flags & occluded) == 0)) return false;
                continue;
            }
            return false;
        }
        return saw;
    }

    private static String describe(Object info) {
        StringBuilder sb = new StringBuilder();
        Object list = info == null ? null : call(info, "getChanges");
        if (list instanceof java.util.List) {
            for (Object c : (java.util.List<?>) list) {
                Object ati = call(c, "getActivityTransitionInfo");
                sb.append("[task=").append(taskId(c)).append(" mode=").append(call(c, "getMode"))
                        .append(" activity=").append(call(c, "getActivityComponent"))
                        .append(ati != null ? " in " + call(ati, "getTaskId") : "")
                        .append(" type=").append(activityType(c)).append(" flags=0x")
                        .append(Integer.toHexString(call(c, "getFlags") instanceof Integer ? (Integer) call(c, "getFlags") : 0)).append("] ");
            }
        }
        return sb.toString();
    }

    private ShellTransitions() {}

    public static boolean homeRegistered() {
        return sHomeRegistered;
    }

    private static volatile boolean sCloseSeen;

    public static boolean closeConfirmed() {
        return sCloseSeen;
    }

    public static boolean canLaunch(Context c) {
        return Build.VERSION.SDK_INT >= 34 && RemoteAnims.hasPermission(c) && RemoteAnims.hiddenApisOk();
    }

    public static void onSystemUiInit(Context c, Bundle params) {
        IBinder shell = null;
        try {
            shell = params.getBinder("extra_shell_shell_transitions");
            if (shell == null) {
                for (String k : params.keySet()) {
                    String lk = k.toLowerCase(java.util.Locale.ROOT);
                    if (lk.endsWith("ishelltransitions") || lk.contains("shell_transitions") || lk.contains("shelltransitions")) {
                        shell = params.getBinder(k);
                        if (shell != null) {
                            note("shell transitions binder found under " + k);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            note("init bundle unreadable: " + t);
        }
        try {
            note("SystemUI init, keys: " + params.keySet());
        } catch (Throwable ignored) {
        }
        if (shell == null) {
            note("no shell-transitions binder in SystemUI init");
            return;
        }
        if (shell == sShell) return;
        sShell = shell;
        registerHome(c);
    }

    private static void registerHome(Context c) {
        if (!RemoteAnims.hiddenApisOk()) {
            note("hidden APIs unavailable; cannot register");
            return;
        }
        Parcel d = Parcel.obtain();
        try {
            Runner runner = new Runner(true, null);
            String desc = sShell.getInterfaceDescriptor();
            java.lang.reflect.Method setFilter = null;
            try {
                setFilter = Class.forName("android.window.RemoteTransition")
                        .getMethod("setFilter", Class.forName("android.window.TransitionFilter"));
            } catch (Throwable ignored) {
            }
            java.util.List<Object> remotes = new java.util.ArrayList<>();
            for (boolean strict : new boolean[]{true, false}) {
                Parcel p = Parcel.obtain();
                try {
                    Object filter = homeFilter(c, strict);
                    Object remote = newRemoteTransition(runner, "BB10Home");
                    p.writeInterfaceToken(desc);
                    if (setFilter != null) {
                        setFilter.invoke(remote, filter);
                    } else {
                        p.writeInt(1);
                        ((Parcelable) filter).writeToParcel(p, 0);
                    }
                    p.writeInt(1);
                    ((Parcelable) remote).writeToParcel(p, 0);
                    sShell.transact(REGISTER_REMOTE, p, null, IBinder.FLAG_ONEWAY);
                    remotes.add(remote);
                    note("registered " + (strict ? "strict" : "loose") + " home filter"
                            + (setFilter != null ? " (inside the remote)" : "") + ": " + filter);
                } finally {
                    p.recycle();
                }
            }
            sHomeRemote = remotes;
            sHomeRegistered = true;
            note("home transition registered via " + sShell.getInterfaceDescriptor());
        } catch (Throwable t) {
            note("registering home transition failed: " + t);
            sHomeRegistered = false;
        } finally {
            d.recycle();
        }
    }

    private static void set(Object o, String field, Object value) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, value);
        } catch (Throwable t) {
            note("couldn't set " + o.getClass().getSimpleName() + "." + field + ": " + t);
        }
    }

    private static Object homeFilter(Context c, boolean strict) throws Exception {
        Class<?> fc = Class.forName("android.window.TransitionFilter");
        Class<?> rc = Class.forName("android.window.TransitionFilter$Requirement");
        Object f = fc.getConstructor().newInstance();
        set(f, "mTypeSet", null);
        set(f, "mNotFlags", 0x100 | 0x800 | 0x1000 | 0x2000);
        Object home = rc.getConstructor().newInstance();
        set(home, "mActivityType", ACTIVITY_TYPE_HOME);
        if (strict) {
            set(home, "mTopActivity", new ComponentName(c, LauncherActivity.class));
            set(home, "mOrder", 1 );
        }
        set(home, "mModes", new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT});
        Object app = rc.getConstructor().newInstance();
        set(app, "mActivityType", ACTIVITY_TYPE_STANDARD);
        set(app, "mModes", new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK});
        Object reqs = Array.newInstance(rc, 2);
        Array.set(reqs, 0, home);
        Array.set(reqs, 1, app);
        set(f, "mRequirements", reqs);
        return f;
    }

    private static Object newRemoteTransition(Runner r, String name) throws Exception {
        Class<?> itf = Class.forName("android.window.IRemoteTransition");
        Object proxy = Class.forName("android.window.IRemoteTransition$Stub").getMethod("asInterface", IBinder.class).invoke(null, r);
        Class<?> rt = Class.forName("android.window.RemoteTransition");
        try {
            Class<?> at = Class.forName("android.app.IApplicationThread");
            Object thread = null;
            try {
                Class<?> atc = Class.forName("android.app.ActivityThread");
                Object cur = atc.getMethod("currentActivityThread").invoke(null);
                thread = atc.getMethod("getApplicationThread").invoke(cur);
            } catch (Throwable ignored) {
            }
            return rt.getConstructor(itf, at, String.class).newInstance(proxy, thread, name);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return rt.getConstructor(itf, String.class).newInstance(proxy, name);
        } catch (NoSuchMethodException ignored) {
        }
        return rt.getConstructor(itf).newInstance(proxy);
    }

    private static final StringBuffer sTrail = new StringBuffer();

    public static volatile boolean debug;

    public static void note(String s) {
        if (!debug) return;
        String line = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + " " + s;
        Log.i(TAG, s);
        if (sTrail.length() > 6000) sTrail.delete(0, 2000);
        sTrail.append(line).append('\n');
    }

    public static String trail() {
        if (!debug) return "(debug mode is off: turn it on in Settings > System to record this)\n";
        return sTrail.length() == 0 ? "(nothing yet)\n" : sTrail.toString();
    }

    public static Bundle launchOptions(Context c, Rect from) {
        ActivityOptions o = launchActivityOptions(c, from);
        if (o == null) return null;
        if (android.os.Build.VERSION.SDK_INT >= 34)
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return o.toBundle();
    }

    public static ActivityOptions launchActivityOptions(Context c, Rect from) {
        if (!canLaunch(c)) return null;
        try {
            Object remote = newRemoteTransition(new Runner(false, new RectF(from)), "BB10Launch");
            Method m = ActivityOptions.class.getMethod("makeRemoteTransition", Class.forName("android.window.RemoteTransition"));
            ActivityOptions o = (ActivityOptions) m.invoke(null, remote);
            if (Build.VERSION.SDK_INT >= 33) o.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_ICON);
            return o;
        } catch (Throwable t) {
            Log.w(TAG, "launchOptions", t);
            return null;
        }
    }

    private static final class Runner extends Binder {
        static final String DESC = "android.window.IRemoteTransition";
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
                if (reply != null) reply.writeString(DESC);
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION) {
                Object info = null;
                SurfaceControl.Transaction t = null;
                IBinder finished = null;
                try {
                    data.enforceInterface(DESC);
                    data.readStrongBinder();
                    Parcelable.Creator infoCreator = (Parcelable.Creator) Class.forName("android.window.TransitionInfo").getField("CREATOR").get(null);
                    info = data.readTypedObject(infoCreator);
                    t = data.readTypedObject(SurfaceControl.Transaction.CREATOR);
                    finished = data.readStrongBinder();
                } catch (Throwable e) {
                    note("could not read startAnimation: " + e);
                }
                if (closing) {
                    sCloseSeen = true;
                    RemoteAnims.closeStartedAt = android.os.SystemClock.uptimeMillis();
                }
                note((closing ? "close" : "open") + " animation requested by the system");
                Object fi = info;
                SurfaceControl.Transaction ft = t;
                IBinder ff = finished;
                ANIM.post(() -> start(closing, from, fi, ft, ff));
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 1) {
                if (closing && RemoteAnims.openQueued) {
                    note("close animation: the next app waits for it");
                    return true;
                }
                if (!closing && sAnimOpenTask != -1) {
                    try {
                        data.enforceInterface(DESC);
                        data.readStrongBinder();
                        Parcelable.Creator infoCreator = (Parcelable.Creator) Class.forName("android.window.TransitionInfo").getField("CREATOR").get(null);
                        Object info = data.readTypedObject(infoCreator);
                        SurfaceControl.Transaction t = data.readTypedObject(SurfaceControl.Transaction.CREATOR);
                        data.readStrongBinder();
                        IBinder cb = data.readStrongBinder();
                        int task = sAnimOpenTask;
                        if (info != null && sameTaskOnward(info, task)) {
                            note("open: the app's next screen (same task " + task + ") joins this animation");
                            Log.i("BB10Open", "open: next screen in task " + task + " taken into the animation");
                            ANIM.post(() -> {
                                if (t != null) {
                                    if (sCurrent != null && sAnimOpenTask == task) {
                                        if (sMerged == null) sMerged = t;
                                        else sMerged.merge(t);
                                    } else {
                                        t.apply();
                                    }
                                }
                                finish(cb);
                            });
                            return true;
                        }
                        note("open: merge not taken (opening task " + task + "): " + describe(info));
                    } catch (Throwable e) {
                        note("could not read mergeAnimation: " + e);
                    }
                }
                note((closing ? "close" : "open") + " animation cut short: something new is starting (opening task " + sAnimOpenTask + ")");
                ANIM.post(() -> {
                    if (sCurrent != null) sCurrent.end();
                });
                return true;
            }
            note((closing ? "close" : "open") + " runner got call #" + (code - FIRST_CALL_TRANSACTION) + " (consumed)");
            return true;
        }
    }

    private static void finish(IBinder cb) {
        finish(cb, null);
    }

    private static void finish(IBinder cb, SurfaceControl.Transaction extra) {
        if (cb == null) {
            if (extra != null) extra.apply();
            return;
        }
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken("android.window.IRemoteTransitionFinishedCallback");
            d.writeInt(0);
            if (extra != null) {
                d.writeInt(1);
                extra.writeToParcel(d, 0);
            } else {
                d.writeInt(0);
            }
            cb.transact(IBinder.FIRST_CALL_TRANSACTION, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.w(TAG, "finish", t);
        } finally {
            d.recycle();
        }
    }

    private static Object call(Object o, String m) {
        try {
            return o.getClass().getMethod(m).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLockScreen(Object change) {
        Object ti = call(change, "getTaskInfo");
        if (ti == null) return false;
        for (String f : new String[]{"topActivity", "baseActivity", "realActivity"}) {
            try {
                Object cn = ti.getClass().getField(f).get(ti);
                if (cn instanceof android.content.ComponentName
                        && ((android.content.ComponentName) cn).getClassName().endsWith(".lock.LockActivity")) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int activityType(Object change) {
        Object ti = call(change, "getTaskInfo");
        if (ti == null) return 0;
        Object v = call(ti, "getActivityType");
        if (v instanceof Integer) return (Integer) v;
        try {
            Field f = ti.getClass().getField("topActivityType");
            return f.getInt(ti);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static boolean placeLiveInfo(Object info, RemoteAnims.Tx t, SurfaceControl sc, int layer) {
        try {
            SurfaceControl root = rootLeash(info);
            if (root == null) return false;
            t.t.reparent(sc, root);
            t.layer(sc, layer);
            t.show(sc);
            return true;
        } catch (Throwable e) {
            note("live frame surface unavailable: " + e);
            return false;
        }
    }

    private static RectF insideSplash(RectF r, Rect screen) {
        float l = r.left + Math.min(2f, Math.max(0f, r.left - screen.left));
        float t = r.top + Math.min(2f, Math.max(0f, r.top - screen.top));
        float rr = r.right - Math.min(2f, Math.max(0f, screen.right - r.right));
        float bb = r.bottom - Math.min(2f, Math.max(0f, screen.bottom - r.bottom));
        return rr > l && bb > t ? new RectF(l, t, rr, bb) : r;
    }

    private static SurfaceControl rootLeash(Object info) {
        SurfaceControl root = null;
        try {
            Object r = info.getClass().getMethod("getRoot", int.class).invoke(info, 0);
            root = (SurfaceControl) r.getClass().getMethod("getLeash").invoke(r);
        } catch (Throwable ignored) {
        }
        if (root == null) root = (SurfaceControl) call(info, "getRootLeash");
        return root;
    }

    private static void closeDone() {
        RemoteAnims.closeEndedAt = android.os.SystemClock.uptimeMillis();
        if (RemoteAnims.zoomStartAt == RemoteAnims.ZOOM_PENDING) RemoteAnims.zoomStartAt = RemoteAnims.closeEndedAt;
        Runnable begun = RemoteAnims.onCloseBegun;
        RemoteAnims.onCloseBegun = null;
        if (begun != null) RemoteAnims.MAIN.post(begun);
        Runnable after = RemoteAnims.onCloseFinished;
        RemoteAnims.onCloseFinished = null;
        if (after != null) RemoteAnims.MAIN.post(after);
    }

    private static final class TaskCover {
        private static ValueAnimator sAnim;

        static SurfaceControl placeOver(RemoteAnims.Tx tx, SurfaceControl cover, SurfaceControl task) {
            return placeOver(tx, cover, task, null);
        }

        static SurfaceControl placeOver(RemoteAnims.Tx tx, SurfaceControl cover, SurfaceControl task, SurfaceControl made) {
            SurfaceControl holder = made;
            if (holder == null) {
                try {
                    holder = new SurfaceControl.Builder().setName("BB10 open cover").setParent(task).setHidden(false).build();
                } catch (Throwable e) {
                    Log.w(TAG, "cover holder", e);
                }
            }
            tx.t.reparent(cover, holder != null ? holder : task);
            if (holder != null) {
                tx.layer(holder, Integer.MAX_VALUE);
                tx.show(holder);
            }
            tx.layer(cover, Integer.MAX_VALUE);
            return holder;
        }

        static void start(SurfaceControl cover, SurfaceControl holder, OwnSplash own, String pkg, String taskPkg, int task, long requested, long animStart) {
            dismiss();
            final long coverAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            long[] shown = {0};
            boolean[] ending = {false};
            long[] timing = {0, 0, 0};
            ValueAnimator v = ValueAnimator.ofFloat(0f, 1f);
            v.setDuration((own != null ? RemoteAnims.OWN_WAIT_MAX + OwnSplash.OUT_TOTAL : SHOW_WAIT_MAX + LATE_FADE) + 500);
            v.setInterpolator(new android.view.animation.LinearInterpolator());
            final RemoteAnims.Tx ftx = new RemoteAnims.Tx();
            v.addUpdateListener(a -> {
                if (ending[0]) return;
                long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
                if (shown[0] == 0) {
                    shown[0] = own != null ? RemoteAnims.ownReady(task, requested, animStart, now) : appUp(pkg, taskPkg, task, requested, animStart, now);
                    if (shown[0] != 0) Log.i("BB10Open", "open: " + pkg + "'s screen up (" + splashState(task, requested) + ") "
                            + (shown[0] - animStart) + " ms into the animation: its " + (own != null ? "splash goes" : "look fades away"));
                }
                if (!cover.isValid()) return;
                if (own != null && shown[0] == 0) {
                    own.pulse(ftx, now - coverAt - OwnSplash.PULSE_AFTER);
                    ftx.applyPaced();
                    return;
                }
                if (shown[0] == 0) return;
                if (timing[0] != 0) timing[1] = Math.max(timing[1], now - timing[0]);
                timing[0] = now;
                timing[2]++;
                RemoteAnims.Tx tx = ftx;
                boolean gone;
                if (own != null) {
                    long ms = now - shown[0];
                    own.pulse(tx, now - coverAt - OwnSplash.PULSE_AFTER);
                    tx.alpha(cover, own.out(tx, ms));
                    gone = ms >= own.outTotal();
                } else {
                    float k = (now - shown[0]) / (float) LATE_FADE;
                    tx.alpha(cover, 1f - RemoteAnims.liveOpenAlpha(k));
                    gone = k >= 1f;
                }
                tx.applyPaced();
                if (gone) {
                    ending[0] = true;
                    a.end();
                }
            });
            v.addListener(new AnimatorListenerAdapter() {
                boolean done;

                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    if (done) return;
                    done = true;
                    if (sAnim == v) sAnim = null;
                    if (own != null) RemoteAnims.ownHandedOff = false;
                    if (timing[2] > 0) note("late fade: " + timing[2] + " frames, worst gap " + timing[1] + " ms");
                    if (timing[2] > 0 && own != null && !debug) Log.i("BB10Open", "splash late fade: " + timing[2] + " frames, worst gap " + timing[1] + " ms");
                    try {
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        if (holder != null && holder.isValid()) t.reparent(holder, null);
                        else if (cover.isValid()) t.reparent(cover, null);
                        t.apply();
                        OwnSplash.forget(cover);
                        cover.release();
                        if (holder != null) holder.release();
                    } catch (Throwable ignored) {
                    }
                    RemoteAnims.openSettled = true;
                }
            });
            sAnim = v;
            v.start();
        }

        static void dismiss() {
            ValueAnimator v = sAnim;
            sAnim = null;
            if (v != null) v.end();
        }
    }

    private static final float HOME_ZOOM = 0.7f;

    private static float homeScale(boolean closing, float t, float from) {
        t = Math.max(0f, Math.min(1f, t));
        float out = 1f - (1f - HOME_ZOOM) * from;
        return closing ? out + (1f - out) * t : 1f - (1f - HOME_ZOOM) * t;
    }

    private static volatile long sOpenCutAt;
    private static volatile float sOpenCutT = 1f;
    private static volatile RectF sOpenCutRect;

    private static void zoomHome(RemoteAnims.Tx tx, List<SurfaceControl> ls, List<Point> ps, float s, float px, float py) {
        for (int i = 0; i < ls.size(); i++) {
            Point p = ps.get(i);
            tx.scale(ls.get(i), s);
            tx.position(ls.get(i), px + (p.x - px) * s, py + (p.y - py) * s);
        }
    }

    private static void zoomUnder(RemoteAnims.Tx tx, LauncherChrome.Piece[] p, float s, float px, float py) {
        if (p == null || p.length < 3 || p[2] == null || !p[2].sc.isValid()) return;
        Rect at = p[2].at;
        tx.scale(p[2].sc, s);
        tx.position(p[2].sc, px + (at.left - px) * s, py + (at.top - py) * s);
    }

    private static volatile boolean sSwipeClose;

    private static void dockBack(RemoteAnims.Tx tx, LauncherChrome.LiveDock d) {
        if (d == null || !d.sc.isValid()) return;
        LauncherChrome.dockBusy = false;
        LauncherChrome.LiveDock now = LauncherChrome.liveDock;
        SurfaceControl home = now != null && now.home != null && now.home.isValid() ? now.home : d.home;
        if (home == null || !home.isValid()) return;
        tx.t.reparent(d.sc, home);
        tx.t.setPosition(d.sc, 0, 0);
        tx.t.setLayer(d.sc, 1);
        tx.t.setAlpha(d.sc, 1f);
        tx.show(d.sc);
    }

    private static void slideChrome(RemoteAnims.Tx tx, LauncherChrome.Piece[] p, float k) {
        if (p == null) return;
        k = Math.max(0f, Math.min(1f, k));
        if (p[0] != null && p[0].sc.isValid()) tx.position(p[0].sc, p[0].at.left, p[0].at.top + p[0].at.height() * k);
        float bk = sSwipeClose ? 0f : k;
        if (p[1] != null && p[1].sc.isValid()) tx.position(p[1].sc, p[1].at.left, p[1].at.top - p[1].at.bottom * bk);
    }

    private static void start(boolean closing, RectF launchFrom, Object info, SurfaceControl.Transaction startT, IBinder finished) {
        sSwipeClose = false;
        TaskCover.dismiss();
        RemoteAnims.ownHandedOff = false;
        if (info == null || startT == null) {
            if (startT != null) startT.apply();
            finish(finished);
            return;
        }
        Object moving = null, other = null;
        Object tType = call(info, "getType"), tFlags = call(info, "getFlags");
        note("transition type " + tType + " flags 0x" + (tFlags instanceof Integer ? Integer.toHexString((Integer) tFlags) : "?"));
        if (tFlags instanceof Integer && (((Integer) tFlags) & (0x100 | 0x800 | 0x1000 | 0x2000)) != 0) {
            startT.apply();
            finish(finished);
            return;
        }
        List<?> changes = (List<?>) call(info, "getChanges");
        if (changes != null) {
            StringBuilder sb = new StringBuilder();
            for (Object ch : changes) {
                sb.append(" [mode=").append(call(ch, "getMode")).append(" type=").append(activityType(ch))
                        .append(" task=").append(call(ch, "getTaskInfo") != null).append("]");
            }
            note("changes:" + sb);
        }
        if (changes != null) {
            for (Object ch : changes) {
                Object fl = call(ch, "getFlags");
                if (fl instanceof Integer && (((Integer) fl) & FLAG_IS_WALLPAPER) != 0) continue;
                Object mo = call(ch, "getMode");
                int mode = mo instanceof Integer ? (Integer) mo : 0;
                boolean opening = mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT;
                boolean home = activityType(ch) == ACTIVITY_TYPE_HOME;
                if (closing) {
                    if (!opening && !home && call(ch, "getTaskInfo") != null && moving == null) moving = ch;
                    else if (opening && other == null) other = ch;
                } else {
                    if (opening && !home && call(ch, "getTaskInfo") != null && moving == null) moving = ch;
                    else if (!opening && other == null) other = ch;
                }
            }
        }
        RemoteAnims.Tx first = new RemoteAnims.Tx(startT);
        final java.util.ArrayList<SurfaceControl> homeLeashes = new java.util.ArrayList<>();
        final java.util.ArrayList<Point> homePos = new java.util.ArrayList<>();
        if (changes != null) {
            for (Object ch : changes) {
                if (ch == moving) continue;
                SurfaceControl l = (SurfaceControl) call(ch, "getLeash");
                if (l == null) continue;
                Object fl = call(ch, "getFlags");
                boolean wallpaper = fl instanceof Integer && (((Integer) fl) & FLAG_IS_WALLPAPER) != 0;
                first.show(l);
                first.alpha(l, 1f);
                first.layer(l, wallpaper ? -100 : 0);
                if (!wallpaper && activityType(ch) == ACTIVITY_TYPE_HOME) {
                    Point hp = (Point) call(ch, closing ? "getEndRelOffset" : "getStartRelOffset");
                    homeLeashes.add(l);
                    homePos.add(hp != null ? hp : new Point());
                }
            }
        }
        if (moving == null) {
            note("no app window to animate in this transition (" + (changes == null ? 0 : changes.size()) + " changes)");
            first.apply();
            finish(finished);
            if (closing) closeDone();
            return;
        }
        SurfaceControl leash = (SurfaceControl) call(moving, "getLeash");
        if (closing && isLockScreen(moving)) {
            note("lock screen closed: no animation");
            if (leash != null) first.alpha(leash, 0f);
            first.apply();
            finish(finished);
            closeDone();
            return;
        }
        String unseen = RemoteAnims.neverShown;
        if (closing && unseen != null && android.os.SystemClock.uptimeMillis() - RemoteAnims.neverShownAt < 3000
                && unseen.equals(taskPkg(moving))) {
            RemoteAnims.neverShown = null;
            Log.i("BB10Open", "going home from " + unseen + ", which never showed: no animation");
            if (leash != null) first.alpha(leash, 0f);
            first.apply();
            finish(finished);
            closeDone();
            return;
        }
        Rect bounds = (Rect) call(moving, closing ? "getStartAbsBounds" : "getEndAbsBounds");
        if (bounds == null || bounds.isEmpty()) bounds = (Rect) call(moving, "getEndAbsBounds");
        Point pos = (Point) call(moving, closing ? "getStartRelOffset" : "getEndRelOffset");
        if (pos == null) pos = new Point();
        final Rect b = bounds != null && !bounds.isEmpty() ? new Rect(bounds) : new Rect(0, 0, Math.round(Ui.S()), Math.round(Ui.S()));
        final Point p0 = pos;
        RectF full = new RectF(b);
        final float topCut = Tasks.topCut(taskId(moving), b.height(), RemoteAnims.statusInset());

        RectF startR, endR;
        boolean intoFrame = closing;
        if (closing && !intoFrame) {
            startR = full;
            float cx = full.centerX(), cy = full.centerY();
            endR = new RectF(cx - full.width() * 0.3f, cy - full.height() * 0.3f, cx + full.width() * 0.3f, cy + full.height() * 0.3f);
        } else if (closing) {
            String closingPkg = taskPkg(moving);
            if (closingPkg == null) closingPkg = com.bb10.launcher.LauncherApp.get().frames.lastForeground();
            com.bb10.launcher.LauncherApp.get().frames.snapshotNow(closingPkg);
            RectF pending = RemoteAnims.pendingCloseFrom;
            RemoteAnims.pendingCloseFrom = null;
            sSwipeClose = pending != null;
            RectF cut = android.os.SystemClock.uptimeMillis() - sOpenCutAt < 800 ? sOpenCutRect : null;
            startR = pending != null ? pending : cut != null ? new RectF(cut) : full;
            Rect slot = ActiveFramesPage.slotFor(closingPkg);
            if (slot.width() <= 0) {
                float S = Ui.S();
                int gap = Ui.dp(5), top = Math.round(S * 0.055f) + gap;
                slot.set(gap, top, gap + (b.width() - 3 * gap) / 2, top + Math.round((b.height() - S * 0.055f - S * 0.116f - 3 * gap) / 2));
            }
            endR = new RectF(slot);
        } else {
            startR = launchFrom != null ? new RectF(launchFrom) : full;
            endR = full;
            if (launchFrom != null && RemoteAnims.zoomStartAt != 0) startR.offset(RemoteAnims.zoomFromDx, 0);
        }
        final boolean fromFrame = !closing && launchFrom != null && RemoteAnims.pendingFrameLaunch;
        RectF pr = closing ? endR : launchFrom != null ? startR : full;
        final float pivX = pr.centerX(), pivY = pr.centerY();
        final float closeFrom = closing && android.os.SystemClock.uptimeMillis() - sOpenCutAt < 800 ? Math.max(0f, sOpenCutT) : 1f;
        if (closing) sOpenCutAt = 0;
        final float[] openReached = {0f};
        if (sCurrent != null) sCurrent.end();
        final LauncherChrome.Piece[] chrome = homeLeashes.isEmpty() ? null : LauncherChrome.take();
        final boolean[] closeOver = {false};
        final float[] underScale = {homeScale(closing, 0f, closeFrom)};
        final boolean liveDock = LauncherChrome.dockHidden;
        LauncherChrome.LiveDock ld = LauncherChrome.liveDock;
        final LauncherChrome.LiveDock dockLive = ld != null && ld.ok() && chrome != null ? ld : null;
        final LauncherChrome.Piece[] slide = dockLive != null
                ? new LauncherChrome.Piece[]{new LauncherChrome.Piece(dockLive.sc, dockLive.at), chrome[1], null} : chrome;
        final SurfaceControl[] dockRoot = {null};
        if (chrome != null) Log.i("BB10Open", (closing ? "close" : "open") + ": dock " + (chrome[0] != null ? "slides" : "missing")
                + ", status bar " + (chrome[1] != null ? "slides" : "missing"));
        if (chrome != null && chrome[1] == null) LauncherChrome.missingBar(com.bb10.launcher.LauncherApp.get(), closing);
        if (chrome != null) {
            SurfaceControl root = rootLeash(info);
            boolean any = false;
            for (int ci = 0; ci < chrome.length; ci++) {
                LauncherChrome.Piece c = chrome[ci];
                if (c == null || root == null || !c.sc.isValid() || (ci == 2 && liveDock) || (ci != 1 && dockLive != null)) continue;
                first.t.reparent(c.sc, root);
                first.layer(c.sc, ci == 2 ? 1 : 20000);
                first.show(c.sc);
                first.alpha(c.sc, 1f);
                any = true;
            }
            if (dockLive != null && root != null) {
                LauncherChrome.dockBusy = true;
                first.t.reparent(dockLive.sc, root);
                dockRoot[0] = root;
                first.layer(dockLive.sc, 20000);
                first.show(dockLive.sc);
                first.alpha(dockLive.sc, 1f);
                any = true;
            }
            if (closing && !liveDock && dockLive == null && root != null && LauncherChrome.underNow != null) {
                final SurfaceControl rootF = root;
                LauncherChrome.underNow.accept(fresh -> ANIM.post(() -> {
                    if (fresh == null) return;
                    if (closeOver[0] || !fresh.sc.isValid()) {
                        fresh.release();
                        return;
                    }
                    RemoteAnims.Tx sw = new RemoteAnims.Tx();
                    sw.t.reparent(fresh.sc, rootF);
                    sw.layer(fresh.sc, 1);
                    sw.show(fresh.sc);
                    sw.alpha(fresh.sc, 1f);
                    LauncherChrome.Piece old = chrome[2];
                    chrome[2] = fresh;
                    zoomUnder(sw, chrome, underScale[0], pivX, pivY);
                    if (old != null && old.sc.isValid()) sw.t.reparent(old.sc, null);
                    sw.apply();
                    if (old != null) old.release();
                }));
            }
            if (any) {
                int top = chrome[1] != null ? chrome[1].at.bottom : -100000;
                int bottom = chrome[0] != null && !liveDock && dockLive == null ? chrome[0].at.top : 100000;
                for (int i = 0; i < homeLeashes.size(); i++) {
                    Point hp = homePos.get(i);
                    first.crop(homeLeashes.get(i), new Rect(-100000, top - hp.y, 100000, bottom - hp.y));
                }
            }
        }
        if (!closing) SurfaceZoom.handOver();
        SurfaceControl liveSc = fromFrame && RemoteAnims.pendingFrameLive ? RemoteAnims.pendingLiveInfo : null;
        final Rect liveB = RemoteAnims.pendingLiveSize;
        OwnSplash splash = !closing ? OwnSplash.of(liveSc) : null;
        if (splash != null) {
            splash.setTask(taskId(moving));
            float es = 0f;
            long zst = RemoteAnims.zoomStartAt;
            if (zst > 0 && zst != RemoteAnims.ZOOM_PENDING) {
                long tn = SurfaceZoom.lastFrameAt() > 0 ? SurfaceZoom.lastFrameAt() : android.os.SystemClock.uptimeMillis();
                es = Math.max(0f, (tn - zst) / (float) DURATION);
            }
            if (!splash.settle(es, RemoteAnims.openInterp(es))) splash = null;
        }
        final SurfaceControl liveInfo = liveSc != null && liveB != null && placeLiveInfo(info, first, liveSc, splash != null ? 10002 : 10000) ? liveSc : null;
        final boolean live = liveInfo != null;
        final OwnSplash own = live ? splash : null;
        if (liveSc != null && !live) RemoteAnims.dropLiveInfo();
        final Point liveP = new Point(p0.x - b.left, p0.y - b.top);
        final RectF liveFrom = launchFrom == null ? null : new RectF(startR.left, startR.top, startR.right,
                startR.bottom - com.bb10.launcher.ui.Ui.dp(com.bb10.launcher.ui.ActiveFramesPage.BAR_DP));
        if (!closing) {
            RemoteAnims.pendingFrameLaunch = false;
            RemoteAnims.pendingFrameLive = false;
            RemoteAnims.pendingLiveInfo = null;
            RemoteAnims.pendingLiveSize = null;
            RemoteAnims.openQueued = false;
        }
        final long now0 = android.os.SystemClock.uptimeMillis();
        final SurfaceControl closeRoot = closing && intoFrame ? rootLeash(info) : null;
        final boolean zoomed = !closing && live && RemoteAnims.zoomStartAt != 0;
        final boolean waits = zoomed || (!closing && live && (own != null || RemoteAnims.openPkg != null && !RemoteAnims.openSettled
                && now0 - RemoteAnims.openRequestedAt < 5000));
        final long[] zoom = {0};
        long zs = zoomed ? RemoteAnims.zoomStartAt : 0;
        if (zoomed && zs != RemoteAnims.ZOOM_PENDING) zoom[0] = zs;
        if (waits && !zoomed) zoom[0] = now0;
        final long[] shown = {0};
        final boolean[] faded = {false};
        final int[] handOff = {0};
        final String openPkg = waits ? RemoteAnims.openPkg : null, openTask = waits ? taskPkg(moving) : null;
        final int openTaskId = waits ? taskId(moving) : -1;
        final long requested = RemoteAnims.openRequestedAt;
        if (waits) {
            RemoteAnims.openTaskId = openTaskId;
            RemoteAnims.openSettled = false;
        }
        long zt = zoomed ? SurfaceZoom.lastFrameAt() : 0;
        long tNow = zt > 0 ? zt : now0;
        final float e0 = zoom[0] > 0 && tNow > zoom[0] ? (tNow - zoom[0]) / (float) DURATION : 0f;
        final float t0 = RemoteAnims.openInterp(e0);
        final boolean hidden = zoomed && (zoom[0] == 0 || tNow < zoom[0]);

        first.show(leash);
        first.layer(leash, 10001);
        RemoteAnims.applyFrame(first, leash, b, p0, RemoteAnims.lerp(startR, endR, t0), live || hidden || (closing && sSwipeClose) ? 0f : 1f,
                fromFrame ? RemoteAnims.openTrimTop(t0, topCut) : 0f, fromFrame ? RemoteAnims.openTrim(t0) : 0f);
        zoomHome(first, homeLeashes, homePos, homeScale(closing, t0, closeFrom), pivX, pivY);
        zoomUnder(first, chrome, homeScale(closing, t0, closeFrom), pivX, pivY);
        slideChrome(first, slide, closing ? closeFrom * (1f - t0) : t0);
        if (live) {
            RectF lr = RemoteAnims.lerp(liveFrom, endR, t0);
            float la = hidden ? 0f : 1f;
            if (own != null) la *= own.step(first, liveP, lr, e0, t0, -1);
            RemoteAnims.applyCover(first, liveInfo, liveB, liveP, lr, la);
        }
        first.apply();
        if (own != null) note("open: into JL10's splash screen, the app hidden under it until its own screen is up");
        else if (live) note("open from a live frame: its info grows with the app");
        if (!closing) {
            Runnable started = RemoteAnims.onOpenAnimStart;
            RemoteAnims.onOpenAnimStart = null;
            if (started != null) RemoteAnims.MAIN.post(started);
        }
        if (zoomed) note("open: the launcher's zoom " + (zoom[0] == 0 ? "starts once the frames are in place"
                : now0 >= zoom[0] ? "started " + (now0 - zoom[0]) + " ms ago" : "starts in " + (zoom[0] - now0) + " ms"));
        else if (waits && own == null) note("open: out of the frame's look, the app fading in once its screen is up");

        if (closing) {
            Runnable r = RemoteAnims.onCloseStarted;
            RemoteAnims.onCloseStarted = null;
            if (r != null) RemoteAnims.MAIN.post(r);
            Runnable begun = RemoteAnims.onCloseBegun;
            RemoteAnims.onCloseBegun = null;
            if (begun != null) RemoteAnims.MAIN.post(begun);
        }

        if (sCurrent != null) sCurrent.end();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(waits ? ZOOM_WAIT_MAX + SHOW_WAIT_MAX + DURATION + 500 : DURATION);
        va.setInterpolator(closing ? new PathInterpolator(0.3f, 0f, 0.1f, 1f)
                : waits ? new android.view.animation.LinearInterpolator() : RemoteAnims.OPEN_EASE);
        final long[] timing = {0, 0, 0, 0};
        final boolean timed = true;
        final StringBuilder trace = new StringBuilder();
        final boolean[] begun = {false};
        final long[] prevFrame = {0};
        final boolean[] stepped = {zt > 0};
        final float[] ze = {e0};
        final long[] ready = {0};
        final long[] outMs = {-1};
        final long[] fullAt = {0};
        final long[] quick = {0};
        final SurfaceControl[] preHolder = {null};
        if (own != null) {
            try {
                preHolder[0] = new SurfaceControl.Builder().setName("BB10 open cover").setParent(leash).setHidden(false).build();
            } catch (Throwable e) {
                Log.w(TAG, "cover holder", e);
            }
        }
        final boolean[] over = {false};
        final RemoteAnims.Tx frameTx = new RemoteAnims.Tx();
        va.addUpdateListener(a -> {
            if (waits && (!begun[0] || over[0])) return;
            if (timed) {
                long now = System.nanoTime();
                if (timing[0] != 0 && now - timing[0] > timing[1]) {
                    timing[1] = now - timing[0];
                    timing[3] = timing[2];
                }
                timing[0] = now;
                timing[2]++;
            }
            float t = (float) a.getAnimatedValue();
            if (closing && t >= 0.9f && RemoteAnims.zoomStartAt == RemoteAnims.ZOOM_PENDING)
                RemoteAnims.zoomStartAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            float elapsed = Math.min(1f, a.getCurrentPlayTime() / (float) FADE);
            float alpha = closing && !intoFrame ? 1f - t : live ? RemoteAnims.liveOpenAlpha(elapsed) : 1f;
            float coverAlpha = 1f;
            if (waits) {
                long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
                long frame = prevFrame[0] > 0 && now > prevFrame[0] ? Math.min(34, now - prevFrame[0]) : 16;
                prevFrame[0] = now;
                if (zoom[0] == 0) {
                    long z = RemoteAnims.zoomStartAt;
                    if (z == RemoteAnims.ZOOM_PENDING || z == 0) z = now - now0 > ZOOM_WAIT_MAX ? now : 0;
                    if (z > 0) zoom[0] = z;
                }
                if (shown[0] == 0 && own == null) {
                    shown[0] = appUp(openPkg, openTask, openTaskId, requested, now0, now);
                    if (shown[0] != 0) Log.i("BB10Open", "open: " + openPkg + "'s screen up (" + splashState(openTaskId, requested) + ") " + (shown[0] - now0)
                            + " ms into the animation" + (zoom[0] > 0 ? ", " + (shown[0] - zoom[0]) + " ms into the zoom" : ""));
                }
                if (zoom[0] == 0 || now < zoom[0]) {
                    t = 0f;
                    alpha = 0f;
                    coverAlpha = 0f;
                } else {
                    if (!stepped[0]) {
                        stepped[0] = true;
                        if (now - zoom[0] > frame) zoom[0] = now - frame;
                    }
                    float e = (now - zoom[0]) / (float) DURATION;
                    t = RemoteAnims.openInterp(e);
                    ze[0] = e;
                    if (own != null) {
                        if (ready[0] == 0) {
                            ready[0] = RemoteAnims.ownReady(openTaskId, requested, now0, now);
                            if (ready[0] != 0) Log.i("BB10Open", "open: " + openPkg + "'s screen up (" + splashState(openTaskId, requested) + ") "
                                    + (ready[0] - now0) + " ms into the animation, " + (ready[0] - zoom[0]) + " ms into the zoom");
                        }
                        if (fullAt[0] == 0 && own.fullyIn()) fullAt[0] = now;
                        long zoomEnd = zoom[0] + DURATION;
                        long outAt = ready[0] == 0 || fullAt[0] == 0 ? 0 : Math.max(Math.max(ready[0], fullAt[0]), zoomEnd);
                        if (ready[0] != 0 && fullAt[0] != 0 && quick[0] == 0) {
                            quick[0] = 1;
                            if (OwnSplash.quickSkipOn() && ready[0] <= zoomEnd) {
                                long from = Math.max(ready[0], fullAt[0]);
                                own.goQuickly();
                                quick[0] = from;
                                Log.i("BB10Open", "open: " + openPkg + " was quick: JL10's splash fades into it as it zooms");
                            }
                        }
                        if (quick[0] > 1) outAt = quick[0];
                        if (ready[0] == 0 && fullAt[0] != 0 && e >= 1f) {
                            handOff[0] = 1;
                            over[0] = true;
                            a.end();
                            return;
                        }
                        outMs[0] = outAt == 0 || now < outAt ? -1 : now - outAt;
                        alpha = outMs[0] < 0 ? 0f : 1f;
                        if (alpha > 0f) faded[0] = true;
                        if (debug && trace.length() < 1500)
                            trace.append(now - now0).append(':').append(Math.round(t * 100)).append("/out ").append(outMs[0]).append(' ');
                        if (outMs[0] >= own.outTotal()) {
                            zoom[0] = -1;
                            over[0] = true;
                            a.end();
                            return;
                        }
                    } else {
                        long from = Math.max(zoom[0], now0);
                        alpha = now < from ? 0f : RemoteAnims.liveOpenAlpha((now - from) / (float) FADE);
                        if (alpha > 0f) faded[0] = true;
                        if (debug && trace.length() < 1500)
                            trace.append(now - now0).append(':').append(Math.round(t * 100)).append('/').append(Math.round(alpha * 100)).append(' ');
                        if (e >= 1f && alpha >= 1f) {
                            if (zoom[0] != -1) {
                                zoom[0] = -1;
                                a.end();
                            }
                            return;
                        }
                    }
                }
            }
            boolean frame = closing && intoFrame;
            float top = frame ? RemoteAnims.closeTrimTop(t, topCut) : fromFrame ? RemoteAnims.openTrimTop(t, topCut) : 0f;
            float bottom = frame ? RemoteAnims.closeTrim(t) : fromFrame ? RemoteAnims.openTrim(t) : 0f;
            RemoteAnims.Tx tx = frameTx;
            takeMerged(tx);
            if (own != null && live) RemoteAnims.applyFrame(tx, leash, b, p0, insideSplash(RemoteAnims.lerp(liveFrom, endR, t), b), alpha, top, 0f);
            else RemoteAnims.applyFrame(tx, leash, b, p0, RemoteAnims.lerp(startR, endR, t), (closing && sSwipeClose ? 0f : alpha), top, bottom);
            if (!closing) openReached[0] = t;
            zoomHome(tx, homeLeashes, homePos, homeScale(closing, t, closeFrom), pivX, pivY);
            zoomUnder(tx, chrome, homeScale(closing, t, closeFrom), pivX, pivY);
            underScale[0] = homeScale(closing, t, closeFrom);
            slideChrome(tx, slide, closing ? closeFrom * (1f - t) : t);
            if (dockLive != null && dockRoot[0] != null && dockLive.sc.isValid()) tx.t.reparent(dockLive.sc, dockRoot[0]);
            long zst = RemoteAnims.zoomStartAt;
            if (closeRoot != null && zst != 0 && zst != RemoteAnims.ZOOM_PENDING) SurfaceZoom.liftAbove(tx, closeRoot, 10002);
            if (live) {
                RectF lr = RemoteAnims.lerp(liveFrom, endR, t);
                float la = coverAlpha;
                if (own != null && la > 0f) la *= own.step(tx, liveP, lr, ze[0], t, outMs[0]);
                RemoteAnims.applyCover(tx, liveInfo, liveB, liveP, lr, la);
            }
            tx.applyPaced();
        });
        va.addListener(new AnimatorListenerAdapter() {
            boolean done;
            boolean dockWaited;
            boolean dockPending;
            Runnable afterDock;
            boolean endSent;

            void endChanges(RemoteAnims.Tx zt) {
                zoomHome(zt, homeLeashes, homePos, 1f, pivX, pivY);
                for (SurfaceControl l : homeLeashes) {
                    try {
                        zt.t.setCrop(l, null);
                    } catch (Throwable ignored) {
                    }
                }
                if (chrome != null) {
                    for (LauncherChrome.Piece c : chrome) {
                        if (c != null && c.sc.isValid()) zt.t.reparent(c.sc, null);
                    }
                }
                dockBack(zt, dockLive);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (closing && liveDock && !dockWaited && !done && !homeLeashes.isEmpty()) {
                    java.util.function.BiConsumer<SurfaceControl.Transaction, Runnable> end = LauncherChrome.endWithDock;
                    if (end != null) {
                        dockWaited = true;
                        RemoteAnims.Tx et = new RemoteAnims.Tx();
                        endChanges(et);
                        endSent = true;
                        final long t0 = android.os.SystemClock.uptimeMillis();
                        Runnable go = () -> ANIM.post(() -> {
                            onAnimationEnd(animation);
                        });
                        RemoteAnims.MAIN.post(() -> end.accept(et.t, go));
                        ANIM.postDelayed(() -> {
                            et.t.apply();
                            go.run();
                        }, 250);
                        return;
                    }
                }
                if (done) return;
                done = true;
                closeOver[0] = true;
                if (endSent) {
                    LauncherChrome.giveBack(chrome);
                } else if (!homeLeashes.isEmpty()) {
                    RemoteAnims.Tx zt = new RemoteAnims.Tx();
                    zoomHome(zt, homeLeashes, homePos, 1f, pivX, pivY);
                    for (SurfaceControl l : homeLeashes) {
                        try {
                            zt.t.setCrop(l, null);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (chrome != null) {
                        for (LauncherChrome.Piece c : chrome) {
                            if (c != null && c.sc.isValid()) zt.t.reparent(c.sc, null);
                        }
                    }
                    dockBack(zt, dockLive);
                    if (closing && dockLive != null) {
                        dockPending = true;
                        zt.t.addTransactionCommittedListener(ANIM::post, () -> {
                            dockPending = false;
                            Runnable r = afterDock;
                            afterDock = null;
                            if (r != null) r.run();
                        });
                    }
                    zt.applyPaced();
                    LauncherChrome.giveBack(chrome);
                }
                if (!closing && openReached[0] < 0.98f) {
                    sOpenCutAt = android.os.SystemClock.uptimeMillis();
                    sOpenCutT = openReached[0];
                    sOpenCutRect = RemoteAnims.lerp(startR, endR, openReached[0]);
                }
                if (sCurrent == va) {
                    sCurrent = null;
                    sAnimOpenTask = -1;
                }
                if (sMerged != null) {
                    RemoteAnims.Tx mt = new RemoteAnims.Tx();
                    takeMerged(mt);
                    mt.apply();
                }
                note((closing ? "close" : "open") + " animation: " + timing[2] + " frames, worst gap " + (timing[1] / 1_000_000) + " ms (frame " + timing[3] + ")");
                if (!debug) Log.i("BB10Open", (own != null ? "splash open" : closing ? "close" : "open") + " animation: " + timing[2]
                        + " frames, worst gap " + (timing[1] / 1_000_000) + " ms (frame " + timing[3] + ")");
                if (trace.length() > 0) note("open frames (ms:zoom%/app%): " + trace);
                if (!closing && handOff[0] == 1) {
                    RemoteAnims.Tx tx = new RemoteAnims.Tx();
                    RemoteAnims.applyFrame(tx, leash, b, p0, full, 1f);
                    SurfaceControl holder = TaskCover.placeOver(tx, liveInfo, leash, preHolder[0]);
                    preHolder[0] = null;
                    RectF whole = new RectF(0, 0, b.width(), b.height());
                    if (own != null) own.step(tx, new Point(0, 0), whole, 1f, 1f, -1);
                    RemoteAnims.applyCover(tx, liveInfo, liveB, new Point(0, 0), whole, 1f);
                    tx.apply();
                    Log.i("BB10Open", "open: " + openPkg + " not up when the zoom ended: its " + (own != null ? "splash" : "look")
                            + " stays over it, the open finishes");
                    if (own != null) RemoteAnims.ownHandedOff = true;
                    TaskCover.start(liveInfo, holder, own, openPkg, openTask, openTaskId, requested, now0);
                    finish(finished);
                    return;
                }
                if (!closing) {
                    boolean unseen = waits && zoom[0] != -1 && !faded[0];
                    RemoteAnims.Tx tx = new RemoteAnims.Tx();
                    RemoteAnims.applyFrame(tx, leash, b, p0, full, unseen ? 0f : 1f);
                    if (live) tx.t.reparent(liveInfo, null);
                    tx.apply();
                    if (live) {
                        OwnSplash.forget(liveInfo);
                        liveInfo.release();
                    }
                    if (preHolder[0] != null) {
                        try {
                            new SurfaceControl.Transaction().reparent(preHolder[0], null).apply();
                            preHolder[0].release();
                        } catch (Throwable ignored) {
                        }
                        preHolder[0] = null;
                    }
                    RemoteAnims.neverShown = unseen ? (openTask != null ? openTask : openPkg) : null;
                    RemoteAnims.neverShownAt = android.os.SystemClock.uptimeMillis();
                    if (unseen) Log.i("BB10Open", "open of " + openPkg + " cut short before its screen was up");
                }
                if (closing) {
                    RemoteAnims.Tx tx = new RemoteAnims.Tx();
                    if (SurfaceZoom.lower(tx)) {
                        tx.hide(leash);
                        tx.apply();
                    }
                }
                if (dockPending) {
                    Runnable fin = () -> finish(finished);
                    afterDock = fin;
                    ANIM.postDelayed(() -> {
                        if (afterDock == fin) {
                            afterDock = null;
                            fin.run();
                        }
                    }, 100);
                } else {
                    finish(finished);
                }
                if (closing) {
                    closeDone();
                    RemoteAnims.MAIN.postDelayed(() -> {
                        com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
                        if (app != null && app.frames != null) app.frames.refreshFromTasks();
                    }, 150);
                }
            }
        });
        sCurrent = va;
        sMerged = null;
        sAnimOpenPkg = closing ? null : taskPkg(moving);
        sAnimOpenTask = closing ? -1 : taskId(moving);
        va.start();
        begun[0] = true;
    }
}
