package com.bb10.launcher.quickstep;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Tasks {
    private static final String TAG = "BB10Tasks";
    private static Object sAtm;

    private Tasks() {}

    @SuppressWarnings("unchecked")
    private static List<ActivityManager.RunningTaskInfo> allTasks() {
        try {
            Object atm = atm();
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) continue;
                Class<?>[] p = m.getParameterTypes();
                Object[] a = new Object[p.length];
                if (p.length == 0 || p[0] != int.class) continue;
                a[0] = 200;
                for (int i = 1; i < p.length; i++) a[i] = p[i] == boolean.class ? Boolean.FALSE : p[i] == int.class ? -1 : null;
                return (List<ActivityManager.RunningTaskInfo>) m.invoke(atm, a);
            }
        } catch (Throwable t) {
            Log.w(TAG, "allTasks", t);
        }
        return null;
    }

    private static Object atm() throws Exception {
        if (sAtm == null) {
            RemoteAnims.hiddenApisOk();
            sAtm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        }
        return sAtm;
    }

    @SuppressWarnings("deprecation")
    public static Map<String, Integer> taskIds(Context c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ActivityManager.RecentTaskInfo> list = am.getRecentTasks(200, 0);
            if (list == null) return out;
            for (ActivityManager.RecentTaskInfo t : list) {
                ComponentName cn = t.baseActivity != null ? t.baseActivity
                        : t.baseIntent != null ? t.baseIntent.getComponent() : null;
                if (cn == null) continue;
                if (!out.containsKey(cn.getPackageName())) out.put(cn.getPackageName(), t.taskId);
            }
            List<ActivityManager.RunningTaskInfo> running = am.getRunningTasks(200);
            if (running != null) {
                for (ActivityManager.RunningTaskInfo t : running) {
                    ComponentName cn = t.baseActivity != null ? t.baseActivity
                            : t.topActivity != null ? t.topActivity
                            : t.baseIntent != null ? t.baseIntent.getComponent() : null;
                    if (cn == null) continue;
                    if (!out.containsKey(cn.getPackageName())) out.put(cn.getPackageName(), t.taskId);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "taskIds", t);
        }
        return out;
    }

    public static Bitmap snapshot(int taskId, int maxSide) {
        return snapshot(taskId, maxSide, false);
    }

    private static final java.util.Map<Integer, Long> sSeen = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean anySeen() {
        return !sSeen.isEmpty();
    }

    public static Bitmap snapshot(int taskId, int maxSide, boolean onlyNew) {
        return snapshot(taskId, maxSide, onlyNew, false);
    }

    private static java.util.List<Object> snapshotServices() throws Exception {
        java.util.List<Object> l = new java.util.ArrayList<>();
        Object atm = atm();
        l.add(atm);
        try {
            Object tsm = atm.getClass().getMethod("getTaskSnapshotManager").invoke(atm);
            if (tsm != null) l.add(tsm);
        } catch (NoSuchMethodException ignored) {
        }
        return l;
    }

    private static int anyResolution() {
        try {
            java.lang.reflect.Field f = Class.forName("android.window.TaskSnapshotManager").getField("RESOLUTION_ANY");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return 1;
        }
    }

    private static Object callSnapshot(Object svc, String name, int taskId, boolean flags) {
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 0 || p[0] != int.class) continue;
            Object[] args = new Object[p.length];
            args[0] = taskId;
            int bools = 0;
            for (int i = 1; i < p.length; i++) {
                if (p[i] == boolean.class) args[i] = bools++ == 0 ? flags : false;
                else if (p[i] == long.class) args[i] = -1L;
                else if (p[i] == int.class) args[i] = anyResolution();
                else args[i] = null;
            }
            if (name.equals("getTaskSnapshot") && bools > 0) for (int i = 1; i < p.length; i++) if (p[i] == boolean.class) args[i] = false;
            try {
                Object s = m.invoke(svc, args);
                if (s != null) return s;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                ShellTransitions.note(name + " failed: " + c);
            }
        }
        return null;
    }

    private static final Map<Integer, Float> sTopCut = new java.util.concurrent.ConcurrentHashMap<>();

    public static float topCut(int taskId, int height, float fallback) {
        Float f = taskId == -1 ? null : sTopCut.get(taskId);
        return f != null ? f * height : fallback;
    }

    public static Bitmap freshSnapshot(int taskId, int maxSide) {
        return snapshot(taskId, maxSide, false, true);
    }

    private static Bitmap snapshot(int taskId, int maxSide, boolean onlyNew, boolean fresh) {
        try {
            Object snap = null;
            for (Object svc : snapshotServices()) {
                if (fresh && snap == null) snap = callSnapshot(svc, "takeTaskSnapshot", taskId, true);
                if (snap == null) snap = callSnapshot(svc, "getTaskSnapshot", taskId, false);
                if (snap != null) break;
            }
            if (snap == null) return null;
            Long sid = null;
            try {
                sid = (Long) snap.getClass().getMethod("getId").invoke(snap);
            } catch (Throwable ignored) {
            }
            if (onlyNew && sid != null && sid.equals(sSeen.get(taskId))) return null;
            if (sid != null) sSeen.put(taskId, sid);
            Rect insets = null;
            try {
                insets = (Rect) snap.getClass().getMethod("getContentInsets").invoke(snap);
            } catch (Throwable ignored) {
            }
            Bitmap hw = snapshotBitmap(snap);
            if (hw == null) return null;
            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            try {
                snap.getClass().getMethod("closeBuffer").invoke(snap);
            } catch (Throwable ignored) {
            }
            if (soft == null) return null;
            int top = insets != null ? Math.max(0, Math.min(insets.top, soft.getHeight() / 4)) : 0;
            sTopCut.put(taskId, top / (float) soft.getHeight());
            int w = soft.getWidth(), h = soft.getHeight() - top;
            Bitmap crop = top > 0 ? Bitmap.createBitmap(soft, 0, top, w, h) : soft;
            float s = Math.min(1f, maxSide / (float) Math.max(w, h));
            Bitmap out = s < 1f ? Bitmap.createScaledBitmap(crop, Math.round(w * s), Math.round(h * s), true) : crop;
            if (crop != out && crop != soft) crop.recycle();
            if (soft != out && soft != crop) soft.recycle();
            out.prepareToDraw();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "snapshot", t);
            return null;
        }
    }

    private static Bitmap snapshotBitmap(Object snap) throws Exception {
        try {
            Bitmap b = (Bitmap) snap.getClass().getMethod("wrapToBitmap").invoke(snap);
            if (b != null) return b;
        } catch (NoSuchMethodException ignored) {
        }
        HardwareBuffer hb = (HardwareBuffer) snap.getClass().getMethod("getHardwareBuffer").invoke(snap);
        if (hb == null) return null;
        ColorSpace cs = null;
        try {
            cs = (ColorSpace) snap.getClass().getMethod("getColorSpace").invoke(snap);
        } catch (Throwable ignored) {
        }
        return Bitmap.wrapHardwareBuffer(hb, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
    }

    public static boolean remove(int taskId) {
        try {
            Object atm = atm();
            Object r = atm.getClass().getMethod("removeTask", int.class).invoke(atm, taskId);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            Log.w(TAG, "removeTask", t);
            return false;
        }
    }
}
