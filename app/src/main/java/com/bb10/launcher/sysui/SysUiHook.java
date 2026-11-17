package com.bb10.launcher.sysui;

import android.graphics.Canvas;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.bb10.launcher.ui.StatusBarView;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SysUiHook implements IXposedHookLoadPackage {
    private static final String SYSUI = "com.android.systemui";
    private static boolean loaded;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSUI.equals(lp.packageName)) return;
        if (lp.processName != null && !SYSUI.equals(lp.processName)) return;
        if (loaded) return;
        if (!seesSystemUi(lp.classLoader)) {
            XposedBridge.log("JL10: skipping a System UI load without System UI's classes");
            return;
        }
        loaded = true;
        if (crashLooping(lp)) return;
        ClassLoader cl = lp.classLoader;
        SysUi.cl = cl;
        step("status bar", () -> hookStatusBar(cl));
        step("shade commands", () -> hookCommandQueue(cl));
        step("shade collapse", () -> hookCollapse(cl));
        step("notifications", () -> hookNotifications(cl));
        step("dark icons", () -> hookDarkIcons(cl));
        step("volume", () -> hookVolume(cl));
        step("screen recorder", () -> hookScreenRecord(cl));
        step("keyguard", () -> KeyguardHook.install(cl));
        step("splash screens", () -> SplashHook.install(cl));
        step("hide status bar", () -> NoStatusBar.install(cl));
        step("instant unlock", () -> InstantUnlock.install(cl));
    }

    private static void hookScreenRecord(ClassLoader cl) {
        int n = 0, m = 0;
        Class<?> ux = find(cl, "com.android.systemui.screenrecord.ScreenRecordLegacyUxControllerImpl");
        if (ux != null) m = XposedBridge.hookAllConstructors(ux, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                BbTiles.recordUx = p.thisObject;
            }
        }).size();
        Class<?> ctl = find(cl, "com.android.systemui.screenrecord.RecordingController");
        if (ctl != null) n += XposedBridge.hookAllConstructors(ctl, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                BbTiles.recordCtl = p.thisObject;
            }
        }).size();
        SysUi.note("screen recorder hooked (controller " + m + ", recording " + n + ")");
    }

    private static final String[] SYSUI_MARKERS = {
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
            "com.android.systemui.SystemUIInitializer",
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
    };

    private static boolean seesSystemUi(ClassLoader cl) {
        for (String c : SYSUI_MARKERS) {
            try {
                Class.forName(c, false, cl);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static final String SAFE_STATUS = "SAFE: ";
    public static final String SAFE_FILE = "jl10_safe", STARTS_FILE = "jl10_crashes";
    public static final String OLD_STARTS_FILE = "jl10_starts";

    private static boolean crashLooping(XC_LoadPackage.LoadPackageParam lp) {
        try {
            java.io.File dir = new java.io.File("/data/user_de/" + android.os.Process.myUid() / 100000 + "/" + SYSUI);
            java.io.File safe = new java.io.File(dir, SAFE_FILE), crashes = new java.io.File(dir, STARTS_FILE);
            new java.io.File(dir, OLD_STARTS_FILE).delete();
            if (safe.exists() && !new String(java.nio.file.Files.readAllBytes(safe.toPath())).startsWith("crashes ")) {
                safe.delete();
                XposedBridge.log("JL10: hooks were off from an older build's restart count; trying again");
            }
            if (safe.exists()) {
                XposedBridge.log("JL10: hooks off: System UI crashed 3 times in 5 minutes with them; Restart System UI in JL10 tries again");
                reportSafe(SAFE_STATUS + "switched off after System UI kept crashing");
                return true;
            }
            long now = System.currentTimeMillis();
            int recent = 0;
            if (crashes.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(crashes.toPath())).split("\n")) {
                    try {
                        if (now - Long.parseLong(line.trim()) < 5 * 60_000L) recent++;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (recent >= 3) {
                crashes.delete();
                java.nio.file.Files.write(safe.toPath(), ("crashes " + now).getBytes());
                XposedBridge.log("JL10: System UI crashed " + recent + " times in 5 minutes with JL10's hooks; they're off until Restart System UI in JL10");
                reportSafe(SAFE_STATUS + "switched off after System UI kept crashing");
                return true;
            }
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
                try {
                    java.nio.file.Files.write(crashes.toPath(), (System.currentTimeMillis() + "\n").getBytes(),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(th, e);
            });
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                crashes.delete();
            }, 90_000);
        } catch (Throwable t) {
            XposedBridge.log("JL10: crash guard unavailable: " + t);
        }
        return false;
    }

    private static void reportSafe(String status) {
        try {
            XposedBridge.hookAllMethods(android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    writeStatus((android.content.Context) p.thisObject, status);
                }
            });
        } catch (Throwable ignored) {
        }
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (long delay : new long[]{3_000, 15_000}) {
            h.postDelayed(() -> {
                try {
                    Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                    if (app != null) writeStatus((android.content.Context) app, status);
                } catch (Throwable ignored) {
                }
            }, delay);
        }
    }

    private static void writeStatus(android.content.Context c, String status) {
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(), SysUi.K_STATUS, status);
        } catch (Throwable t) {
            XposedBridge.log("JL10: couldn't report the status: " + t);
        }
    }

    private static void hookVolume(ClassLoader cl) throws Throwable {
        Class<?> ctl = find(cl, "com.android.systemui.volume.VolumeDialogControllerImpl");
        if (ctl != null) {
            XposedBridge.hookAllMethods(ctl, "onVolumeChangedW", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Integer && VolumeHud.shown((Integer) p.args[0]))
                        VolumeHud.lastStream = (Integer) p.args[0];
                    boolean showUi = p.args.length > 1 && p.args[1] instanceof Integer
                            && ((Integer) p.args[1] & android.media.AudioManager.FLAG_SHOW_UI) != 0;
                    if (showUi && SysUi.volumeOn && SysUi.res != null) SysUi.main.post(VolumeHud::onShowRequested);
                    else VolumeHud.onVolumeChanged();
                }
            });
        }
        Class<?> legacy = find(cl, "com.android.systemui.volume.VolumeDialogImpl");
        if (legacy != null) {
            XposedBridge.hookAllMethods(legacy, "showH", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!SysUi.volumeOn || SysUi.res == null) return;
                    p.setResult(null);
                    SysUi.main.post(VolumeHud::onShowRequested);
                }
            });
        }
        Method show = android.app.Dialog.class.getDeclaredMethod("show");
        XposedBridge.hookMethod(show, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                android.app.Dialog d = (android.app.Dialog) p.thisObject;
                android.view.Window w = d.getWindow();
                if (w == null || w.getAttributes().type != 2020) return;
                String name = d.getClass().getName();
                if (name.contains("Csd") || name.contains("Safety") || name.contains("Warning")) return;
                if (SysUi.shuttingDown()) {
                    android.view.WindowManager.LayoutParams a = w.getAttributes();
                    a.alpha = 0f;
                    a.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    a.flags &= ~android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                    w.setAttributes(a);
                    VolumeHud.dismissNow();
                    return;
                }
                if (!SysUi.volumeOn || SysUi.res == null) {
                    android.view.WindowManager.LayoutParams a = w.getAttributes();
                    if (a.alpha == 0f) {
                        a.alpha = 1f;
                        a.flags &= ~(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        w.setAttributes(a);
                        View decor = w.peekDecorView();
                        if (decor != null) decor.setAlpha(1f);
                    }
                    return;
                }
                if (!loggedVolumeDialog) {
                    loggedVolumeDialog = true;
                    SysUi.note("hiding volume dialog " + name);
                }
                android.view.WindowManager.LayoutParams a = w.getAttributes();
                a.alpha = 0f;
                a.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                a.flags &= ~android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                w.setAttributes(a);
                SysUi.main.post(VolumeHud::onShowRequested);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!SysUi.volumeOn && !SysUi.shuttingDown()) return;
                android.view.Window w = ((android.app.Dialog) p.thisObject).getWindow();
                if (w == null || w.getAttributes().type != 2020) return;
                String name = p.thisObject.getClass().getName();
                if (name.contains("Csd") || name.contains("Safety") || name.contains("Warning")) return;
                View decor = w.peekDecorView();
                if (decor != null) decor.setAlpha(0f);
            }
        });
        Class<?> cq = find(cl, "com.android.systemui.statusbar.CommandQueue");
        int sd = cq == null ? 0 : XposedBridge.hookAllMethods(cq, "showShutdownUi", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                SysUi.markShutdown();
            }
        }).size();
        SysUi.note("BB10 volume hooked (controller " + (ctl != null) + ", legacy dialog " + (legacy != null) + ", shutdown " + sd + ")");
    }

    private static boolean loggedVolumeDialog;

    private static void hookDarkIcons(ClassLoader cl) throws Throwable {
        int n = 0;
        for (String cn : new String[]{"com.android.systemui.statusbar.phone.DarkIconDispatcherImpl",
                "com.android.systemui.statusbar.phone.ui.DarkIconDispatcherImpl"}) {
            Class<?> c = find(cl, cn);
            if (c == null) continue;
            n += XposedBridge.hookAllMethods(c, "applyDarkIntensity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Float)) return;
                    if (!forcing) {
                        darkDispatcher = p.thisObject;
                        realDark = (Float) p.args[0];
                    }
                    if (splashWhite) p.args[0] = 0f;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Float) SysUi.setDark((Float) p.args[0], 2);
                }
            }).size();
        }
        Class<?> clock = find(cl, "com.android.systemui.statusbar.policy.Clock");
        if (clock != null) {
            XC_MethodHook fromClock = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Float f = null;
                    Integer tint = null;
                    for (Object a : p.args) {
                        if (a instanceof Float && f == null) f = (Float) a;
                        else if (a instanceof Integer && tint == null) tint = (Integer) a;
                    }
                    if (f != null) SysUi.setDark(f, 1);
                    else if (tint != null) SysUi.setDark(android.graphics.Color.luminance(tint) < 0.5f ? 1f : 0f, 1);
                }
            };
            n += XposedBridge.hookAllMethods(clock, "onDarkChanged", fromClock).size();
            n += XposedBridge.hookAllMethods(clock, "onDarkChangedWithContrast", fromClock).size();
        }
        if (n == 0) throw new ClassNotFoundException("DarkIconDispatcherImpl / Clock");
        SysUi.note("dark icon hooks: " + n);
    }

    private static volatile boolean splashWhite;
    private static volatile boolean forcing;
    private static volatile Object darkDispatcher;
    private static volatile float realDark;

    static void setSplashWhite(boolean on) {
        if (splashWhite == on) return;
        splashWhite = on;
        Object d = darkDispatcher;
        if (d == null) return;
        try {
            for (Class<?> c = d.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("applyDarkIntensity") || m.getParameterTypes().length != 1) continue;
                    m.setAccessible(true);
                    forcing = true;
                    try {
                        m.invoke(d, on ? 0f : realDark);
                    } finally {
                        forcing = false;
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            SysUi.note("splash status bar: " + t);
        }
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void step(String what, Step s) {
        try {
            s.run();
        } catch (Throwable t) {
            SysUi.note(what + ": not available (" + t + ")");
        }
    }

    private static Class<?> find(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookStatusBar(ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName("com.android.systemui.statusbar.phone.PhoneStatusBarView", false, cl);
        XposedBridge.hookAllMethods(c, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    attach((ViewGroup) p.thisObject);
                } catch (Throwable t) {
                    SysUi.note("status bar attach failed: " + t);
                }
            }
        });
        hookStatusBarTouches(c);
    }

    private static void hookStatusBarTouches(Class<?> c) {
        if (hasDeclared(c, "dispatchTouchEvent")) {
            XposedBridge.hookAllMethods(c, "dispatchTouchEvent", touchHook);
            SysUi.note("status bar pull-down: dispatchTouchEvent");
            return;
        }
        if (hasDeclared(c, "onTouchEvent")) {
            if (hasDeclared(c, "onInterceptTouchEvent")) XposedBridge.hookAllMethods(c, "onInterceptTouchEvent", interceptHook);
            XposedBridge.hookAllMethods(c, "onTouchEvent", touchHook);
            SysUi.note("status bar pull-down: onInterceptTouchEvent/onTouchEvent");
            return;
        }
        XposedBridge.hookAllMethods(ViewGroup.class, "dispatchTouchEvent", touchHook);
        SysUi.note("status bar pull-down: ViewGroup.dispatchTouchEvent");
    }

    private static final XC_MethodHook interceptHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (SysUi.res == null || !SysUi.hosts.contains(p.thisObject)) return;
            if (SysUi.lockShowing || SysUi.shadeOn) p.setResult(true);
        }
    };

    private static boolean inStatusBar(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent())
            if (SysUi.hosts.contains(p)) return true;
        return false;
    }

    private static boolean hasDeclared(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return true;
        return false;
    }

    private static void attach(ViewGroup host) {
        SysUi.init(host.getContext());
        if (SysUi.res == null) return;
        SysUi.hosts.add(host);
        StatusBarView v = new StatusBarView(SysUi.wrap(host.getContext()));
        v.passTouches = true;
        v.darkIntensity = SysUi.dark;
        v.transparent = true;
        v.setVisibility(SysUi.sbOn ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = host instanceof FrameLayout
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        host.addView(v, lp);
        SysUi.ours.add(v);
        host.setClipToPadding(false);
        host.addOnLayoutChangeListener((h, l, t, r, b, ol, ot, or, ob) -> {
            if (h instanceof ViewGroup) ((ViewGroup) h).setClipToPadding(false);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            if (!(p instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
            int nl = -h.getPaddingLeft(), nr = -h.getPaddingRight();
            if (m.leftMargin != nl || m.rightMargin != nr) {
                m.leftMargin = nl;
                m.rightMargin = nr;
                v.post(v::requestLayout);
            }
        });
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View x, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                SysUi.note("status bar size " + (r - l) + "x" + (b - t) + ", host " + host.getWidth() + " wide, padding "
                        + host.getPaddingLeft() + "/" + host.getPaddingRight() + ", screen " + Math.round(com.bb10.launcher.ui.Ui.S()));
            }
        });
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View x) { v.start(); }

            @Override
            public void onViewDetachedFromWindow(View x) { v.stop(); }
        });
        if (v.isAttachedToWindow()) v.start();
        watchHost(host);
        SysUi.note("BB10 status bar attached (" + host.getClass().getSimpleName() + ")");
    }

    private static final android.graphics.Rect EMPTY = new android.graphics.Rect();
    private static final android.graphics.Rect tmp = new android.graphics.Rect();

    private static void watchHost(ViewGroup host) {
        java.util.List<android.widget.TextView> clocks = new java.util.ArrayList<>();
        Class<?> clockCls = find(SysUi.cl, "com.android.systemui.statusbar.policy.Clock");
        int[] lastColor = {0};
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                boolean hide = SysUi.sbOn;
                for (int i = 0; i < host.getChildCount(); i++) {
                    View ch = host.getChildAt(i);
                    if (SysUi.ours.contains(ch)) continue;
                    boolean clipped = ch.getClipBounds(tmp) && tmp.isEmpty();
                    if (hide && !clipped) ch.setClipBounds(EMPTY);
                    else if (!hide && clipped) ch.setClipBounds(null);
                }
                if (clockCls != null) {
                    if (clocks.isEmpty()) collect(host, clockCls, clocks);
                    for (android.widget.TextView c : clocks) {
                        int col = c.getCurrentTextColor();
                        if (col != lastColor[0]) {
                            lastColor[0] = col;
                            SysUi.setDark(android.graphics.Color.luminance(col) < 0.5f ? 1f : 0f, 3);
                        }
                        break;
                    }
                }
            } catch (Throwable t) {
                SysUi.note("status bar check failed: " + t);
            }
            return true;
        });
        host.addOnLayoutChangeListener((h, l, t, r, b, ol, ot, or, ob) -> clocks.clear());
    }

    private static void collect(View v, Class<?> cls, java.util.List<android.widget.TextView> out) {
        if (cls.isInstance(v)) out.add((android.widget.TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), cls, out);
        }
    }

    private static final XC_MethodHook touchHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (SysUi.res == null || !SysUi.hosts.contains(p.thisObject)) return;
            if (SysUi.lockShowing) {
                p.setResult(true);
                return;
            }
            if (!SysUi.shadeOn) return;
            try {
                ShadePanel.get().statusBarTouch((MotionEvent) p.args[0]);
            } catch (Throwable t) {
                SysUi.note("pull-down failed: " + t);
                return;
            }
            p.setResult(true);
        }
    };

    private static void hookCommandQueue(ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName("com.android.systemui.statusbar.CommandQueue", false, cl);
        XC_MethodHook open = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (SysUi.lockShowing) {
                    p.setResult(null);
                    return;
                }
                if (!SysUi.shadeOn || SysUi.res == null) return;
                String n = p.method.getName();
                SysUi.main.post(n.equals("togglePanel") ? ShadePanel::toggle : ShadePanel::expand);
                p.setResult(null);
            }
        };
        int n = 0;
        for (String name : new String[]{"animateExpandNotificationsPanel", "animateExpandSettingsPanel", "togglePanel"})
            n += XposedBridge.hookAllMethods(c, name, open).size();
        XposedBridge.hookAllMethods(c, "animateCollapsePanels", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                SysUi.main.post(() -> ShadePanel.closeIfOpen(true));
            }
        });
        if (n == 0) throw new NoSuchMethodException("no expand methods");
    }

    private static void hookCollapse(ClassLoader cl) {
        XC_MethodHook close = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                SysUi.main.post(() -> ShadePanel.closeIfOpen(true));
            }
        };
        int n = 0;
        for (String cn : new String[]{"com.android.systemui.shade.ShadeControllerImpl",
                "com.android.systemui.shade.ShadeControllerSceneImpl",
                "com.android.systemui.shade.BaseShadeControllerImpl"}) {
            Class<?> c = find(cl, cn);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (name.startsWith("animateCollapse") || name.startsWith("collapseShade")
                        || name.startsWith("instantCollapse") || name.equals("collapseOnMainThread")) {
                    try {
                        XposedBridge.hookMethod(m, close);
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        SysUi.note("collapse hooks: " + n);
    }

    private static void hookNotifications(ClassLoader cl) throws Throwable {
        Class<?> c = Class.forName("com.android.systemui.statusbar.NotificationListener", false, cl);
        XposedBridge.hookAllMethods(c, "onNotificationPosted", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof StatusBarNotification) SysUi.posted((StatusBarNotification) p.args[0]);
            }
        });
        XposedBridge.hookAllMethods(c, "onNotificationRemoved", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof StatusBarNotification) SysUi.removed((StatusBarNotification) p.args[0]);
            }
        });
        Set<?> s = XposedBridge.hookAllMethods(c, "onListenerConnected", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    SysUi.sync(((NotificationListenerService) p.thisObject).getActiveNotifications());
                } catch (Throwable t) {
                    SysUi.note("initial notifications: " + t);
                }
            }
        });
        if (s.isEmpty()) SysUi.note("notifications: no onListenerConnected, starting empty");
    }
}
