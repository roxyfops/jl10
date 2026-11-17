package com.bb10.launcher.sysui;

import android.animation.ValueAnimator;
import android.provider.Settings;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class InstantUnlock {
    private InstantUnlock() {}

    static final String KEY = "bb10_instant_unlock";

    static void install(ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName("com.android.systemui.keyguard.KeyguardUnlockAnimationController", false, cl);
        XC_MethodHook instant = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!on()) return;
                for (Class<?> k = p.thisObject.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (!ValueAnimator.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            ValueAnimator a = (ValueAnimator) f.get(p.thisObject);
                            if (a != null) {
                                a.setDuration(0);
                                a.setStartDelay(0);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        };
        int n = 0;
        for (String m : new String[]{"notifyStartSurfaceBehindRemoteAnimation", "playCannedUnlockAnimation", "onKeyguardGoingAwayChanged"})
            n += XposedBridge.hookAllMethods(c, m, instant).size();
        SysUi.note("instant unlock hooks: " + n);
    }

    private static boolean on() {
        try {
            return SysUi.sys != null && "1".equals(Settings.Global.getString(SysUi.sys.getContentResolver(), KEY));
        } catch (Throwable t) {
            return false;
        }
    }
}
