package com.bb10.launcher.sysui;

import android.content.ContentProviderClient;
import android.os.Bundle;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class SplashHook {
    private SplashHook() {}

    private static volatile ContentProviderClient client;
    private static final java.util.Map<Integer, String> pkgs = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private static final java.util.Map<Integer, android.view.SurfaceControl> snapshotSurfaces = java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static volatile android.view.SurfaceControl drawnSnapshot;
    private static volatile long drawnAt;

    static void install(ClassLoader cl) throws Throwable {
        int n = 0;
        Class<?> sto = Class.forName("com.android.wm.shell.ShellTaskOrganizer", false, cl);
        String base = "com.android.wm.shell.startingsurface.";
        Class<?> mgr = Class.forName(base + "StartingSurfaceDrawer$StartingWindowRecordManager", false, cl);

        n += XposedBridge.hookAllMethods(sto, "addStartingWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object ti = p.args.length > 0 && p.args[0] != null ? field(p.args[0], "taskInfo") : null;
                    if (!(ti instanceof android.app.TaskInfo)) return;
                    android.app.TaskInfo t = (android.app.TaskInfo) ti;
                    String pkg = t.topActivity != null ? t.topActivity.getPackageName()
                            : t.baseActivity != null ? t.baseActivity.getPackageName() : null;
                    if (pkgs.size() > 50) pkgs.clear();
                    pkgs.put(t.taskId, pkg);
                    Bundle ex = event("asked", t.taskId);
                    ex.putString("pkg", pkg);
                    ex.putInt("type", suggestedType(p.thisObject, p.args[0], ex));
                    call(ex);
                } catch (Throwable t) {
                    SysUi.note("splash asked for: " + t);
                }
            }
        }).size();
        try {
            XposedBridge.hookAllMethods(Class.forName("android.window.SnapshotDrawerUtils", false, cl), "drawSnapshotOnSurface", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (!(a instanceof android.view.SurfaceControl)) continue;
                        try {
                            android.view.SurfaceControl copy = (android.view.SurfaceControl) android.view.SurfaceControl.class
                                    .getConstructor(android.view.SurfaceControl.class, String.class).newInstance(a, "BB10 splash hook");
                            release(drawnSnapshot);
                            drawnSnapshot = copy;
                            drawnAt = android.os.SystemClock.uptimeMillis();
                        } catch (Throwable t) {
                            SysUi.note("snapshot surface: " + t);
                        }
                        return;
                    }
                }
            });
        } catch (Throwable t) {
            SysUi.note("snapshot drawing not hooked: " + t);
        }
        n += XposedBridge.hookAllMethods(mgr, "addRecord", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[0] instanceof Integer) || p.args[1] == null) return;
                int task = (Integer) p.args[0];
                String cls = p.args[1].getClass().getName();
                android.view.SurfaceControl drawn = drawnSnapshot;
                if (cls.contains("Snapshot") && drawn != null && android.os.SystemClock.uptimeMillis() - drawnAt < 2000) {
                    drawnSnapshot = null;
                    release(snapshotSurfaces.put(task, drawn));
                }
                Bundle ex = event("added", task);
                ex.putString("pkg", pkgs.get(task));
                ex.putString("kind", cls.contains("Snapshot") ? "snapshot" : cls.contains("Splash") ? "splash" : cls);
                call(ex);
            }
        }).size();
        n += XposedBridge.hookAllMethods(sto, "removeStartingWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object info = p.args.length > 0 ? p.args[0] : null;
                if (info == null) return;
                try {
                    int task = info.getClass().getField("taskId").getInt(info);
                    Bundle ex = event("removing", task);
                    ex.putString("pkg", taskPkg(p.thisObject, task));
                    ex.putBoolean("reveal", info.getClass().getField("playRevealAnimation").getBoolean(info));
                    ex.putBoolean("leash", info.getClass().getField("windowAnimationLeash").get(info) != null);
                    Bundle r = call(ex);
                    if (r == null || !r.getBoolean("quiet")) return;
                    info.getClass().getField("playRevealAnimation").setBoolean(info, false);
                    synchronized (quietTasks) {
                        quietTasks.put(info.getClass().getField("taskId").getInt(info), android.os.SystemClock.uptimeMillis());
                    }
                    if (r.getBoolean("hideNow")) {
                        Object recs = recordManager(p.thisObject);
                        if (recs != null) hidePicture(recs, task);
                    }
                } catch (Throwable t) {
                    SysUi.note("splash removing: " + t);
                }
            }
        }).size();
        XposedBridge.hookAllMethods(mgr, "removeWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object info = p.args.length > 0 ? p.args[0] : null;
                if (info == null) return;
                try {
                    int task = info.getClass().getField("taskId").getInt(info);
                    if (quiet(task)) hidePicture(p.thisObject, task);
                } catch (Throwable t) {
                    SysUi.note("splash: couldn't hide it first: " + t);
                }
            }
        });
        n += XposedBridge.hookAllMethods(Class.forName(base + "SplashscreenWindowCreator$SplashWindowRecord", false, cl), "removeIfPossible", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object info = p.args.length > 0 ? p.args[0] : null;
                if (info == null || !Boolean.TRUE.equals(p.getResult())) return;
                try {
                    call(event("removed", info.getClass().getField("taskId").getInt(info)));
                } catch (Throwable t) {
                    SysUi.note("splash removed: " + t);
                }
            }
        }).size();
        n += XposedBridge.hookAllMethods(Class.forName(base + "SnapshotWindowCreator$SnapshotWindowRecord", false, cl), "removeImmediately", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object task = field(p.thisObject, "mTaskId");
                if (task instanceof Integer && quiet((Integer) task)) hideSnapshot(p.thisObject, (Integer) task);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object task = field(p.thisObject, "mTaskId");
                if (!(task instanceof Integer)) return;
                release(snapshotSurfaces.remove(task));
                synchronized (quietTasks) {
                    quietTasks.remove(task);
                }
                call(event("removed", (Integer) task));
            }
        }).size();
        XposedBridge.hookAllMethods(sto, "copySplashScreenView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Integer) call(event("copied", (Integer) p.args[0]));
            }
        });
        XposedBridge.hookAllMethods(sto, "onAppSplashScreenViewRemoved", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Integer) call(event("appRemoved", (Integer) p.args[0]));
            }
        });
        if (n < 5) throw new NoSuchMethodException("starting window hooks: " + n + " of 5");
        SysUi.note("splash screen hooks: " + n);
    }

    private static final java.util.Map<Integer, Long> quietTasks = new java.util.HashMap<>();

    private static boolean quiet(int task) {
        synchronized (quietTasks) {
            Long at = quietTasks.get(task);
            return at != null && android.os.SystemClock.uptimeMillis() - at < 10_000;
        }
    }

    private static void hidePicture(Object records, int task) throws Throwable {
        Object all = field(records, "mStartingWindowRecords");
        Object rec = all instanceof android.util.SparseArray ? ((android.util.SparseArray<?>) all).get(task) : null;
        if (rec == null) return;
        hideRecord(rec, task);
    }

    private static void hideSnapshot(Object rec, int task) {
        try {
            hideRecord(rec, task);
        } catch (Throwable t) {
            SysUi.note("splash: couldn't hide it first: " + t);
        }
    }

    private static void hideRecord(Object rec, int task) throws Throwable {
        android.view.SurfaceControl sc = null;
        Object snapshot = field(rec, "mTaskSnapshotWindow");
        if (snapshot != null) {
            Object s = field(snapshot, "mRootSurface");
            if (s instanceof android.view.SurfaceControl) sc = (android.view.SurfaceControl) s;
            if (sc == null || !sc.isValid()) sc = snapshotSurfaces.get(task);
        } else {
            Object root = field(rec, "mRootView");
            if (root instanceof android.view.View) {
                Object vri = android.view.View.class.getMethod("getViewRootImpl").invoke(root);
                Object s = vri == null ? null : vri.getClass().getMethod("getSurfaceControl").invoke(vri);
                if (s instanceof android.view.SurfaceControl) sc = (android.view.SurfaceControl) s;
            }
        }
        if (sc == null || !sc.isValid()) {
            SysUi.note("splash: nothing to hide for task " + task + " (" + rec.getClass().getSimpleName() + ")");
            return;
        }
        new android.view.SurfaceControl.Transaction().setVisibility(sc, false).apply();
    }

    private static void release(android.view.SurfaceControl sc) {
        if (sc == null) return;
        try {
            sc.release();
        } catch (Throwable ignored) {
        }
    }

    private static int suggestedType(Object organizer, Object info, Bundle why) {
        int fromFlags = typeFromFlags(info, why);
        if (fromFlags >= 0) return fromFlags;
        try {
            Object ctl = fieldOfType(organizer, "StartingWindowController");
            if (ctl == null) {
                why.putString("why", "no controller in " + organizer.getClass().getName());
                return -1;
            }
            Object alg = fieldOfType(ctl, "StartingWindowTypeAlgorithm");
            if (alg == null) {
                why.putString("why", "no algorithm in " + ctl.getClass().getName());
                return -1;
            }
            for (boolean byName : new boolean[]{true, false}) {
                for (Class<?> c = alg.getClass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 1 || m.getReturnType() != int.class
                                || !m.getParameterTypes()[0].isInstance(info)) continue;
                        if (byName && !m.getName().equals("getSuggestedWindowType")) continue;
                        m.setAccessible(true);
                        Object r = m.invoke(alg, info);
                        if (r instanceof Integer && (Integer) r >= 0 && (Integer) r <= 6) return (Integer) r;
                    }
                }
            }
            why.putString("why", "no method in " + alg.getClass().getName());
        } catch (Throwable t) {
            why.putString("why", String.valueOf(t));
        }
        return -1;
    }

    private static int typeFromFlags(Object info, Bundle why) {
        try {
            Class<?> c = info.getClass();
            int p = c.getField("startingWindowTypeParameter").getInt(info);
            int NEW_TASK = flag(c, "TYPE_PARAMETER_NEW_TASK", 0x1), TASK_SWITCH = flag(c, "TYPE_PARAMETER_TASK_SWITCH", 0x2),
                    PROCESS_RUNNING = flag(c, "TYPE_PARAMETER_PROCESS_RUNNING", 0x4), ALLOW_SNAPSHOT = flag(c, "TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT", 0x8),
                    CREATED = flag(c, "TYPE_PARAMETER_ACTIVITY_CREATED", 0x10), SOLID = flag(c, "TYPE_PARAMETER_USE_SOLID_COLOR_SPLASH_SCREEN", 0x20),
                    DRAWN = flag(c, "TYPE_PARAMETER_ACTIVITY_DRAWN", 0x40), WINDOWLESS = flag(c, "TYPE_PARAMETER_WINDOWLESS", 0x100),
                    LEGACY = flag(c, "TYPE_PARAMETER_LEGACY_SPLASH_SCREEN", 0x80000000);
            Object ti = c.getField("taskInfo").get(info);
            boolean home = ti instanceof android.app.TaskInfo && ti.getClass().getField("topActivityType").getInt(ti) == 2;
            Object snap = null;
            try {
                snap = c.getField("taskSnapshot").get(info);
            } catch (NoSuchFieldException ignored) {
            }
            int splash = (p & SOLID) != 0 ? 3 : (p & LEGACY) != 0 ? 4 : 1;
            why.putString("why", "flags 0x" + Integer.toHexString(p));
            if ((p & WINDOWLESS) != 0) return 5;
            if (!home && ((p & PROCESS_RUNNING) == 0 || (p & NEW_TASK) != 0 || ((p & TASK_SWITCH) != 0 && (p & CREATED) == 0))) return splash;
            if ((p & TASK_SWITCH) != 0) {
                if ((p & ALLOW_SNAPSHOT) != 0) {
                    if (snap != null) return 2;
                    if (!home) return 1;
                }
                if ((p & DRAWN) == 0 && !home) return splash;
            }
            return 0;
        } catch (Throwable t) {
            why.putString("why", "flags: " + t);
            return -1;
        }
    }

    private static int flag(Class<?> c, String name, int fallback) {
        try {
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Object recordManager(Object organizer) {
        try {
            Object ctl = fieldOfType(organizer, "StartingWindowController");
            Object drawer = ctl == null ? null : fieldOfType(ctl, "StartingSurfaceDrawer");
            return drawer == null ? null : fieldOfType(drawer, "StartingWindowRecordManager");
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.Map<String, Field> typedFields = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object fieldOfType(Object o, String type) throws IllegalAccessException {
        String key = o.getClass().getName() + "/" + type;
        Field f = typedFields.get(key);
        if (f == null) {
            outer:
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                for (Field x : c.getDeclaredFields()) {
                    if (x.getType().getName().endsWith(type)) {
                        f = x;
                        break outer;
                    }
                }
            }
            if (f == null) return null;
            f.setAccessible(true);
            typedFields.put(key, f);
        }
        return f.get(o);
    }

    private static String taskPkg(Object organizer, int task) {
        String pkg = pkgs.get(task);
        if (pkg != null) return pkg;
        try {
            Object ti = organizer.getClass().getMethod("getRunningTaskInfo", int.class).invoke(organizer, task);
            if (ti instanceof android.app.TaskInfo) {
                android.app.TaskInfo t = (android.app.TaskInfo) ti;
                return t.topActivity != null ? t.topActivity.getPackageName()
                        : t.baseActivity != null ? t.baseActivity.getPackageName() : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bundle event(String what, int task) {
        Bundle ex = new Bundle();
        ex.putString("event", what);
        ex.putInt("task", task);
        return ex;
    }

    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Bundle call(Bundle ex) {
        return callLauncher("splash", ex);
    }

    static Bundle callLauncher(String method, Bundle ex) {
        Bundle r = callLauncherOnce(method, ex);
        return r != null || client != null ? r : callLauncherOnce(method, ex);
    }

    private static Bundle callLauncherOnce(String method, Bundle ex) {
        try {
            ContentProviderClient c = client;
            if (c == null) {
                synchronized (SplashHook.class) {
                    if (client == null)
                        client = SysUi.sys.getContentResolver().acquireUnstableContentProviderClient("com.bb10.launcher.hubbridge");
                    c = client;
                }
            }
            return c == null ? null : c.call(method, null, ex);
        } catch (Throwable t) {
            ContentProviderClient c = client;
            client = null;
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
