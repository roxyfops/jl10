package com.bb10.launcher.sysui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;

import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class NoStatusBar {
    private NoStatusBar() {}

    public static final String KEY = "bb10_no_status_bar";
    private static final int STRIP_DP = 8;

    private static volatile boolean on;
    private static WeakReference<Object> controller = new WeakReference<>(null);
    private static boolean watching;
    private static boolean shrunkOnce;

    static void install(ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName("com.android.systemui.statusbar.window.StatusBarWindowControllerImpl", false, cl);
        int n = XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                controller = new WeakReference<>(p.thisObject);
                watch(p.thisObject);
            }
        }).size();
        Class<?> wmi = Class.forName("android.view.WindowManagerGlobal", false, cl);
        XC_MethodHook out = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!on || p.args.length < 2 || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
                Object ctl = controller.get();
                try {
                    if (ctl == null || p.args[0] != field(ctl, "mStatusBarWindowView")) return;
                    shrink((WindowManager.LayoutParams) p.args[1]);
                    if (!shrunkOnce) {
                        shrunkOnce = true;
                        XposedBridge.log("JL10: status bar window: no room taken (" + p.method.getName() + ")");
                    }
                } catch (Throwable t) {
                    SysUi.note("hide status bar: " + t);
                }
            }
        };
        n += XposedBridge.hookAllMethods(wmi, "addView", out).size();
        n += XposedBridge.hookAllMethods(wmi, "updateViewLayout", out).size();
        int a = XposedBridge.hookAllMethods(c, "apply", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (controller.get() != p.thisObject) {
                    controller = new WeakReference<>(p.thisObject);
                    watch(p.thisObject);
                }
            }
        }).size();
        SysUi.note("hide status bar hooks: " + n + " + apply " + a);
    }

    private static void shrink(WindowManager.LayoutParams lp) throws Exception {
        int strip = Math.round(STRIP_DP * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        shrinkOne(lp, strip);
        Field f = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation");
        f.setAccessible(true);
        Object[] rot = (Object[]) f.get(lp);
        if (rot != null) for (Object r : rot) if (r instanceof WindowManager.LayoutParams) shrinkOne((WindowManager.LayoutParams) r, strip);
    }

    private static Method getType, setSize;

    private static void shrinkOne(WindowManager.LayoutParams lp, int strip) throws Exception {
        lp.height = strip;
        Field f = WindowManager.LayoutParams.class.getDeclaredField("providedInsets");
        f.setAccessible(true);
        Object[] ps = (Object[]) f.get(lp);
        if (ps == null) return;
        for (Object ip : ps) {
            if (ip == null) continue;
            if (getType == null) {
                getType = ip.getClass().getMethod("getType");
                setSize = ip.getClass().getMethod("setInsetsSize", Insets.class);
            }
            int t = (int) getType.invoke(ip);
            if (t == WindowInsets.Type.statusBars() || t == WindowInsets.Type.tappableElement()) setSize.invoke(ip, Insets.NONE);
        }
    }

    private static void watch(Object ctl) {
        if (watching) return;
        try {
            Context c = (Context) field(ctl, "mContext");
            if (c == null) return;
            watching = true;
            Handler h = new Handler(Looper.getMainLooper());
            ContentObserver obs = new ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    read(c);
                }
            };
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY), false, obs);
            on = "1".equals(Settings.Global.getString(c.getContentResolver(), KEY));
            h.post(() -> read(c));
        } catch (Throwable t) {
            SysUi.note("hide status bar: no settings: " + t);
        }
    }

    private static boolean applied;

    private static void read(Context c) {
        on = "1".equals(Settings.Global.getString(c.getContentResolver(), KEY));
        Object ctl = controller.get();
        XposedBridge.log("JL10: hide status bar setting " + on + ", controller " + (ctl != null) + ", applied " + applied);
        if (ctl == null) return;
        try {
            View v = (View) field(ctl, "mStatusBarWindowView");
            if (v != null) v.setAlpha(on ? 0f : 1f);
            if (on == applied) return;
            applied = on;
            Method m = ctl.getClass().getDeclaredMethod("refreshStatusBarHeight");
            m.setAccessible(true);
            m.invoke(ctl);
            Object wm = field(ctl, "mWindowManager"), lp = field(ctl, "mLp");
            if (wm instanceof WindowManager && v != null && v.isAttachedToWindow() && lp instanceof WindowManager.LayoutParams) {
                if (!on) {
                    Field pf = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation");
                    pf.setAccessible(true);
                    Object[] rot = (Object[]) pf.get(lp);
                    Method get = ctl.getClass().getDeclaredMethod("getBarLayoutParamsForRotation", int.class);
                    get.setAccessible(true);
                    if (rot != null) for (int i = 0; i < rot.length; i++) rot[i] = get.invoke(ctl, i);
                }
                ((WindowManager) wm).updateViewLayout(v, (WindowManager.LayoutParams) lp);
            }
            SysUi.note("status bar " + (on ? "hidden in apps" : "shown"));
        } catch (Throwable t) {
            SysUi.note("hide status bar: " + t);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
