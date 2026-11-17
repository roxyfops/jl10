package com.bb10.launcher.sysui;

import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class KeyguardHook {
    static final String K_KEYGUARD = "bb10_keyguard";
    static final String K_AUTO = "bb10_pw_auto";
    private static volatile int autoLength;
    private static int autoTried = -1;
    private static final String TAG = "BB10Keyguard";

    static volatile boolean showing, occluded, bouncer;
    private static volatile boolean on;
    private static volatile boolean covering;

    private static Object viewManager;
    private static Object stateController;
    private static Object unlockAnimation;
    private static PasswordView password;
    private static Object passwordController;
    private static ViewGroup shadeWindow;
    private static KeyguardView view;
    private static final List<View> stock = new ArrayList<>();
    private static final String[] STOCK_IDS = {"keyguard_root_view", "shared_notification_container", "notification_panel"};
    private static final List<View> stockBouncer = new ArrayList<>();
    private static final String[] BOUNCER_IDS = {"keyguard_security_container", "keyguard_message_area_container"};
    private static volatile boolean pwCovering;

    private KeyguardHook() {}

    static void install(ClassLoader cl) throws Throwable {
        int n = 0;

        Class<?> ksc = Class.forName("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl", false, cl);
        n += XposedBridge.hookAllMethods(ksc, "notifyKeyguardState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[0] instanceof Boolean)) return;
                stateController = p.thisObject;
                boolean s = (Boolean) p.args[0], o = (Boolean) p.args[1];
                if (s != showing || o != occluded) log("keyguard showing=" + s + " occluded=" + o);
                showing = s;
                occluded = o;
                if (!s) {
                    resetTyped();
                    runAfterUnlock();
                    SysUi.main.postDelayed(() -> {
                        if (!showing) setGoingAway(false);
                    }, 400);
                } else {
                    SysUi.main.post(() -> setGoingAway(false));
                }
                update();
            }
        }).size();

        Class<?> vm = Class.forName("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager", false, cl);
        n += XposedBridge.hookAllConstructors(vm, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                viewManager = p.thisObject;
            }
        }).size();
        n += XposedBridge.hookAllMethods(vm, "updateStates", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                viewManager = p.thisObject;
                boolean b = bool(p.thisObject, "mLastPrimaryBouncerShowing");
                if (b != bouncer) {
                    log("password screen " + (b ? "shown" : "hidden"));
                    bouncer = b;
                    if (!b && goingAway && android.os.SystemClock.uptimeMillis() - lockOpenAt < 3000) {
                        PasswordView pv = password;
                        long wait = 0;
                        if (pwCovering && pv != null && pv.slideStartedAt > 0)
                            wait = Math.max(0, pv.slideStartedAt + PasswordView.SLIDE_MS * 6 / 10 - android.os.SystemClock.uptimeMillis());
                        SysUi.main.postDelayed(() -> SplashHook.callLauncher("bouncer_gone", null), wait);
                    }
                    if (b) {
                        seen = "";
                        SysUi.main.postDelayed(KeyguardHook::flushPending, 700);
                    } else {
                        resetTyped();
                        SysUi.main.postDelayed(() -> {
                            if (showing && !bouncer) cancelAfterUnlock();
                        }, 500);
                    }
                }
                update();
            }
        }).size();

        Class<?> kuac = Class.forName("com.android.systemui.keyguard.KeyguardUnlockAnimationController", false, cl);
        n += XposedBridge.hookAllConstructors(kuac, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                unlockAnimation = p.thisObject;
            }
        }).size();
        n += XposedBridge.hookAllConstructors(ksc, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                stateController = p.thisObject;
            }
        }).size();

        Class<?> input = Class.forName("com.android.keyguard.KeyguardAbsKeyInputViewController", false, cl);
        n += XposedBridge.hookAllMethods(input, "startErrorAnimation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                PasswordView pv = password;
                if (pv != null) SysUi.main.post(pv::shake);
            }
        }).size();

        Class<?> pwc = Class.forName("com.android.keyguard.KeyguardPasswordViewController", false, cl);
        XC_MethodHook grab = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                passwordController = p.thisObject;
            }
        };
        n += XposedBridge.hookAllMethods(pwc, "onViewAttached", grab).size();
        n += XposedBridge.hookAllMethods(pwc, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                passwordController = p.thisObject;
                SysUi.main.postDelayed(KeyguardHook::flushPending, 150);
            }
        }).size();

        try {
            Class<?> kth = Class.forName("com.android.wm.shell.keyguard.KeyguardTransitionHandler", false, cl);
            for (Method m : kth.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!m.getName().equals("startAnimation") || pt.length != 5 || pt[0] != android.os.IBinder.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object f = p.args[1].getClass().getMethod("getFlags").invoke(p.args[1]);
                            if (!(f instanceof Integer) || (((Integer) f) & 0x100) == 0) return;
                            unlockPlayingAt = android.os.SystemClock.uptimeMillis();
                            runAfterUnlock();
                        } catch (Throwable t) {
                            log("unlock transition: " + t);
                        }
                    }
                });
                unlockTransitionHooked = true;
            }
            for (Method m : kth.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!m.getName().equals("mergeAnimation") || pt.length < 2 || pt[0] != android.os.IBinder.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (android.os.SystemClock.uptimeMillis() - lockOpenAt > 1500 || !unlockPlaying()) return;
                        try {
                            Object next = p.args[1];
                            int type = (Integer) next.getClass().getMethod("getType").invoke(next);
                            int flags = (Integer) next.getClass().getMethod("getFlags").invoke(next);
                            if ((flags & (0x100 | 0x800 | 0x1000 | 0x2000)) != 0) return;
                            if (type != 1 && type != 3) return;
                            p.setResult(null);
                        } catch (Throwable t) {
                            log("unlock merge: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log("unlock transition not hooked: " + t);
        }

        n += XposedBridge.hookAllMethods(ksc, "notifyKeyguardGoingAway", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                boolean away = p.args.length > 0 && Boolean.TRUE.equals(p.args[0]);
                PasswordView pv = password;
                if (away) SysUi.main.post(() -> {
                    setGoingAway(true);
                    if (pv != null) SysUi.main.postDelayed(() -> {
                        if (goingAway && pv.getVisibility() == View.VISIBLE) pv.goingAway(true);
                    }, 500);
                    if (!unlockTransitionHooked) runAfterUnlock();
                });
                else SysUi.main.postDelayed(() -> {
                    if (!showing) return;
                    setGoingAway(false);
                    if (pv != null) pv.goingAway(false);
                }, 400);
            }
        }).size();

        n += XposedBridge.hookAllMethods(kuac, "setSurfaceBehindAppearAmount", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!on || !goingAway || p.args.length == 0 || !(p.args[0] instanceof Float)) return;
                p.args[0] = 1f;
                try {
                    setField(p.thisObject, "surfaceBehindAlpha", 1f);
                } catch (Throwable ignored) {
                }
            }
        }).size();
        for (String step : new String[]{"notifyStartSurfaceBehindRemoteAnimation", "playCannedUnlockAnimation",
                "unlockToLauncherWithInWindowAnimations", "notifyFinishedKeyguardExitAnimation"}) {
            n += XposedBridge.hookAllMethods(kuac, step, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (on) log("unlock step: " + step);
                }
            }).size();
        }
        n += XposedBridge.hookAllMethods(kuac, "notifyStartSurfaceBehindRemoteAnimation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                PasswordView pv = password;
                if (on && pv != null) SysUi.main.post(() -> {
                    if (pv.getVisibility() == View.VISIBLE) pv.goingAway(true);
                });
            }
        }).size();
        n += XposedBridge.hookAllMethods(kuac, "playCannedUnlockAnimation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!on || !goingAway) return;
                Object ctl = p.thisObject;
                if (pwCovering) SysUi.main.postDelayed(() -> endAnimators(ctl), PasswordView.FADE_MS + 20);
                else if (android.os.SystemClock.uptimeMillis() - lockOpenAt < 1500) SysUi.main.post(() -> endAnimators(ctl));
            }
        }).size();
        n += XposedBridge.hookAllMethods(kuac, "setWallpaperAppearAmount", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (on && goingAway && p.args.length > 0 && p.args[0] instanceof Float) p.args[0] = 1f;
            }
        }).size();
        try {
            Class<?> applier = Class.forName("com.android.systemui.keyguard.ui.binder.KeyguardSurfaceBehindParamsApplier", false, cl);
            n += XposedBridge.hookAllMethods(applier, "applyToSurfaceBehind", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!on) return;
                    try {
                        Object params = getField(p.thisObject, "viewParams");
                        Object target = params == null ? null : getField(params, "alpha");
                        if (!(target instanceof Float) || (Float) target < 1f) return;
                        setField(p.thisObject, "animatedAlpha", 1f);
                        Object ty = getField(p.thisObject, "animatedTranslationY");
                        if (ty != null) ty.getClass().getMethod("setValue", float.class).invoke(ty, 0f);
                    } catch (Throwable ignored) {
                    }
                }
            }).size();
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> nswc = Class.forName("com.android.systemui.shade.NotificationShadeWindowControllerImpl", false, cl);
            XC_MethodHook timeout = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        applyLockTimeout(p.thisObject);
                    } catch (Throwable e) {
                        if (!timeoutFailed) log("lock screen timeout: " + e);
                        timeoutFailed = true;
                    }
                }
            };
            int t = 0;
            for (Method m : nswc.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1 || !(m.getName().equals("apply") || m.getName().equals("applyUserActivityTimeout"))) continue;
                XposedBridge.hookMethod(m, timeout);
                t++;
            }
            n += t;
            log("lock screen timeout hooks: " + t);
        } catch (Throwable e) {
            log("lock screen timeout not hooked: " + e);
        }

        Class<?> win = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, cl);
        n += XposedBridge.hookAllMethods(win, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    attach((ViewGroup) p.thisObject);
                } catch (Throwable t) {
                    log("lock screen attach failed: " + t);
                }
            }
        }).size();

        XC_MethodHook touch = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject != shadeWindow) return;
                View v = view != null && view.getVisibility() == View.VISIBLE ? view
                        : password != null && password.getVisibility() == View.VISIBLE ? password : null;
                if (v == null) return;
                try {
                    p.setResult(v.dispatchTouchEvent((MotionEvent) p.args[0]));
                } catch (Throwable t) {
                    log("lock screen touch failed: " + t);
                }
            }
        };
        n += hookOn(win, "dispatchTouchEvent", touch, MotionEvent.class);
        XC_MethodHook keys = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject != shadeWindow) return;
                PasswordView pv = password;
                if (pv != null && pv.getVisibility() == View.VISIBLE) {
                    try {
                        if (passwordKey((KeyEvent) p.args[0])) p.setResult(true);
                    } catch (Throwable t) {
                        log("password key failed: " + t);
                    }
                    return;
                }
                KeyguardView v = view;
                if (v == null || v.getVisibility() != View.VISIBLE) {
                    if (covering && showing && passwordLock() && typeAhead((KeyEvent) p.args[0])) p.setResult(true);
                    return;
                }
                try {
                    if (v.handleKey((KeyEvent) p.args[0])) p.setResult(true);
                } catch (Throwable t) {
                    log("lock screen key failed: " + t);
                }
            }
        };
        n += hookOn(win, "dispatchKeyEventPreIme", keys, KeyEvent.class);
        n += hookOn(win, "dispatchKeyEvent", keys, KeyEvent.class);
        XC_MethodHook generic = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                KeyguardView v = view;
                if (v == null || p.thisObject != shadeWindow || v.getVisibility() != View.VISIBLE) return;
                try {
                    if (v.handleGeneric((MotionEvent) p.args[0])) p.setResult(true);
                } catch (Throwable t) {
                    log("lock screen scroll failed: " + t);
                }
            }
        };
        n += hookOn(win, "dispatchGenericMotionEvent", generic, MotionEvent.class);

        SysUi.note("keyguard hooks: " + n);
    }

    private static Field lockTimeoutField;
    private static long stockLockTimeout = Long.MIN_VALUE;
    private static boolean timeoutFailed;

    private static void applyLockTimeout(Object ctl) throws Exception {
        if (lockTimeoutField == null) {
            for (Class<?> c = ctl.getClass(); c != null && lockTimeoutField == null; c = c.getSuperclass()) {
                try {
                    lockTimeoutField = c.getDeclaredField("mLockScreenDisplayTimeout");
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (lockTimeoutField == null) throw new NoSuchFieldException("mLockScreenDisplayTimeout");
            lockTimeoutField.setAccessible(true);
        }
        long cur = lockTimeoutField.getLong(ctl);
        if (stockLockTimeout == Long.MIN_VALUE) stockLockTimeout = cur;
        int want = SysUi.lockTimeout;
        long v = want == 0 ? stockLockTimeout : want < 0 ? -1L : want;
        if (cur != v) lockTimeoutField.setLong(ctl, v);
    }

    private static int hookOn(Class<?> c, String name, XC_MethodHook hook, Class<?> arg) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, arg);
                XposedBridge.hookMethod(m, hook);
                return 1;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                log("hook " + name + " failed: " + t);
                return 0;
            }
        }
        return 0;
    }

    private static final android.graphics.Rect EMPTY = new android.graphics.Rect();
    private static final android.graphics.Rect tmp = new android.graphics.Rect();
    private static final java.util.Map<View, android.graphics.Rect> savedClip = new java.util.WeakHashMap<>();

    private static void hide(View v) {
        if (!savedClip.containsKey(v)) {
            android.graphics.Rect r = new android.graphics.Rect();
            savedClip.put(v, v.getClipBounds(r) && !r.isEmpty() ? r : null);
        }
        if (!(v.getClipBounds(tmp) && tmp.isEmpty())) v.setClipBounds(EMPTY);
    }

    private static void unhide(View v) {
        if (!savedClip.containsKey(v)) return;
        v.setClipBounds(savedClip.remove(v));
    }

    private static boolean enforce() {
        try {
            if (covering) {
                for (View v : stock) if (!v.isAttachedToWindow()) {
                    findStock();
                    break;
                }
                for (View v : stock) hide(v);
            }
            if (pwCovering) for (View v : stockBouncer) hide(v);
            if (goingAway) for (View v : exitHidden) hide(v);
        } catch (Throwable t) {
            log("hiding the stock lock screen failed: " + t);
        }
        return true;
    }

    private static void attach(ViewGroup root) {
        SysUi.init(root.getContext());
        if (SysUi.res == null) return;
        if (view != null && view.getParent() == root) return;
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        shadeWindow = root;
        view = new KeyguardView(SysUi.wrap(root.getContext()));
        view.setVisibility(View.GONE);
        root.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (password != null && password.getParent() instanceof ViewGroup) ((ViewGroup) password.getParent()).removeView(password);
        password = new PasswordView(SysUi.wrap(root.getContext()));
        password.setVisibility(View.GONE);
        root.addView(password, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.getViewTreeObserver().addOnPreDrawListener(KeyguardHook::enforce);
        log("lock screen attached to " + root.getClass().getSimpleName());
        update();
    }

    private static void findStock() {
        stock.clear();
        ViewGroup root = shadeWindow;
        if (root == null) return;
        for (String name : STOCK_IDS) {
            int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
            View v = id == 0 ? null : root.findViewById(id);
            if (v != null) stock.add(v);
        }
    }

    static void update() {
        SysUi.main.post(() -> {
            try {
                apply();
            } catch (Throwable t) {
                log("lock screen update failed: " + t);
            }
        });
    }

    private static void rebuildIfResized() {
        ViewGroup root = shadeWindow;
        if (root == null) return;
        KeyguardView v = view;
        if (v != null && (v.builtScale != SysUi.lockScale || v.builtBarScale != com.bb10.launcher.ui.StatusBarView.scale)) {
            if (v.getVisibility() == View.VISIBLE) {
                v.setVisibility(View.GONE);
                v.onHidden();
            }
            int at = root.indexOfChild(v);
            root.removeView(v);
            KeyguardView nv = new KeyguardView(SysUi.wrap(root.getContext()));
            nv.setVisibility(View.GONE);
            root.addView(nv, at >= 0 ? at : root.getChildCount(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view = nv;
            log("lock screen built again, size " + SysUi.lockScale + ", status bar " + com.bb10.launcher.ui.StatusBarView.scale);
        }
        PasswordView pv = password;
        if (pv != null && pv.getVisibility() != View.VISIBLE
                && (pv.builtScale != SysUi.lockScale || pv.builtBarScale != com.bb10.launcher.ui.StatusBarView.scale)) {
            int at = root.indexOfChild(pv);
            root.removeView(pv);
            PasswordView np = new PasswordView(SysUi.wrap(root.getContext()));
            np.setVisibility(View.GONE);
            root.addView(np, at >= 0 ? at : root.getChildCount(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            password = np;
        }
    }

    private static void apply() {
        if (SysUi.sys == null) return;
        on = Settings.Global.getInt(SysUi.sys.getContentResolver(), K_KEYGUARD, 0) == 1;
        autoLength = Settings.Global.getInt(SysUi.sys.getContentResolver(), K_AUTO, 0);
        rebuildIfResized();
        boolean cover = on && showing && !occluded && view != null;
        if (cover != covering) {
            covering = cover;
            if (cover) {
                findStock();
                for (View v : stock) hide(v);
            } else {
                for (View v : stock) unhide(v);
            }
        }
        if (on) SysUi.lockShowing = showing && !occluded;
        applyPassword(cover && bouncer);
        KeyguardView v = view;
        if (v == null) return;
        boolean visible = cover && (hold || (!bouncer && !(goingAway && pwCovering)));
        if (visible && v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.VISIBLE);
            v.bringToFront();
            v.onShown();
        } else if (!visible && v.getVisibility() == View.VISIBLE) {
            v.setVisibility(View.GONE);
            v.onHidden();
        } else if (visible) {
            v.refresh();
        }
        if (visible && password != null && password.getVisibility() == View.VISIBLE) v.bringToFront();
    }

    private static void applyPassword(boolean up) {
        PasswordView pv = password;
        if (pv == null) return;
        if (goingAway && pwCovering && !up) return;
        EditText field = up ? stockField() : null;
        boolean show = field != null;
        if (show != pwCovering) {
            pwCovering = show;
            for (View s : stockBouncer) unhide(s);
            stockBouncer.clear();
            if (show) {
                for (String name : BOUNCER_IDS) {
                    int id = shadeWindow.getResources().getIdentifier(name, "id", "com.android.systemui");
                    View s = id == 0 ? null : shadeWindow.findViewById(id);
                    if (s != null) {
                        stockBouncer.add(s);
                        hide(s);
                    }
                }
            }
        }
        if (show != (pv.getVisibility() == View.VISIBLE))
            log("BB10 password screen " + (show ? "up" : "down") + " (was at y " + Math.round(pv.getTranslationY()) + ", going away " + goingAway + ")");
        if (show && pv.getVisibility() != View.VISIBLE) {
            pwShownAt = android.os.SystemClock.uptimeMillis();
            pv.goingAway(false);
            pv.setVisibility(View.VISIBLE);
            pv.bringToFront();
            pv.onShown(field, messageViews());
        } else if (!show && pv.getVisibility() == View.VISIBLE) {
            pv.setVisibility(View.GONE);
            pv.onHidden();
        }
        if (up && !show) SysUi.main.postDelayed(KeyguardHook::update, 150);
    }

    static EditText stockField() {
        if (shadeWindow == null) return null;
        int id = shadeWindow.getResources().getIdentifier("passwordEntry", "id", "com.android.systemui");
        View f = id == 0 ? null : shadeWindow.findViewById(id);
        return f instanceof EditText && f.isAttachedToWindow() && isPasswordMode(f) ? (EditText) f : null;
    }

    private static boolean isPasswordMode(View field) {
        int id = shadeWindow.getResources().getIdentifier("keyguard_password_view", "id", "com.android.systemui");
        for (android.view.ViewParent p = field.getParent(); p instanceof View; p = p.getParent())
            if (((View) p).getId() == id) return ((View) p).getVisibility() == View.VISIBLE;
        return false;
    }

    private static android.widget.TextView[] messageViews() {
        String[] names = {"bouncer_primary_message_area", "bouncer_secondary_message_area"};
        android.widget.TextView[] out = new android.widget.TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            int id = shadeWindow.getResources().getIdentifier(names[i], "id", "com.android.systemui");
            View v = id == 0 ? null : shadeWindow.findViewById(id);
            out[i] = v instanceof android.widget.TextView ? (android.widget.TextView) v : null;
        }
        return out;
    }

    private static boolean passwordKey(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) SysUi.main.post(KeyguardHook::cancelPassword);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return false;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) verifyPassword();
                return true;
        }
        if (typingAhead() && typeAhead(e)) return true;
        EditText f = stockField();
        if (f == null) return typeAhead(e);
        if (!f.hasFocus()) f.requestFocus();
        f.dispatchKeyEvent(e);
        PasswordView pv = password;
        if (pv != null) pv.keyed();
        return true;
    }

    private static void autoCheck(EditText f) {
        int len = f.length();
        if (autoLength <= 0 || len < autoLength) {
            autoTried = -1;
            return;
        }
        if (len == autoLength && autoTried != len) {
            autoTried = len;
            SysUi.main.postDelayed(KeyguardHook::verifyPassword, 60);
        }
    }

    private static volatile boolean lockIsPassword = true;
    private static volatile long lockTypeAt;

    private static boolean passwordLock() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lockTypeAt < 5000) return lockIsPassword;
        lockTypeAt = now;
        try {
            Class<?> lpu = Class.forName("com.android.internal.widget.LockPatternUtils");
            Object u = lpu.getConstructor(android.content.Context.class).newInstance(SysUi.sys);
            int user = (Integer) android.os.UserHandle.class.getMethod("myUserId").invoke(null);
            int type = (Integer) lpu.getMethod("getCredentialTypeForUser", int.class).invoke(u, user);
            lockIsPassword = type == 4;
        } catch (Throwable t) {
            lockIsPassword = true;
        }
        return lockIsPassword;
    }

    private static boolean typeAhead(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_DEL) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && ops.length() < 64) ops.append('\b');
            return true;
        }
        int ch = e.getUnicodeChar(e.getMetaState());
        if (ch <= 0 || Character.isISOControl(ch)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) typeIntoPassword((char) ch);
        return true;
    }

    private static void verifyPassword() {
        Object c = passwordController;
        EditText f = stockField();
        if (f != null) {
            pollPassword(f);
            resetTyped();
            pwShownAt = 0;
        }
        log("password check (" + (f == null ? "no field" : f.length() == 0 ? "empty" : "typed") + ")");
        if (c == null) {
            log("no password controller");
            return;
        }
        try {
            for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Method m = k.getDeclaredMethod("verifyPasswordAndUnlock");
                    m.setAccessible(true);
                    m.invoke(c);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
            log("verifyPasswordAndUnlock not found");
        } catch (Throwable t) {
            log("password check failed: " + t);
        }
    }

    private static volatile Runnable afterUnlock;
    private static volatile long unlockPlayingAt;
    private static volatile boolean unlockTransitionHooked;

    static boolean unlockPlaying() {
        return android.os.SystemClock.uptimeMillis() - unlockPlayingAt < 2000;
    }

    static boolean bouncerUp() {
        return bouncer || pwCovering;
    }

    static void openAfterUnlock(Runnable r) {
        openAfterUnlock(r, null);
    }

    private static volatile Runnable afterUnlockCancelled;

    static void openAfterUnlock(Runnable r, Runnable cancelled) {
        cancelAfterUnlock();
        afterUnlock = r;
        afterUnlockCancelled = cancelled;
        KeyguardView v = view;
        if (v != null) v.unlock();
    }

    private static void cancelAfterUnlock() {
        Runnable c = afterUnlockCancelled;
        afterUnlockCancelled = null;
        afterUnlock = null;
        if (c != null) {
            try {
                c.run();
            } catch (Throwable t) {
                log("undoing an open: " + t);
            }
        }
    }

    private static volatile long lockOpenAt;

    private static void runAfterUnlock() {
        Runnable r = afterUnlock;
        afterUnlock = null;
        afterUnlockCancelled = null;
        if (r == null) return;
        lockOpenAt = android.os.SystemClock.uptimeMillis();
        SysUi.main.post(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                log("opening after unlock failed: " + t);
            }
        });
    }

    static void send(android.app.PendingIntent pi) {
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(SysUi.sys, 0, null, null, null, null, o.toBundle());
        } catch (Throwable t) {
            log("open failed: " + t);
        }
    }

    static void emergency() {
        if (shadeWindow == null) return;
        int id = shadeWindow.getResources().getIdentifier("emergency_call_button", "id", "com.android.systemui");
        View b = id == 0 ? null : shadeWindow.findViewById(id);
        if (b != null) b.performClick();
    }

    static void cancelPassword() {
        Object vm = viewManager;
        if (vm == null) return;
        try {
            Method m = vm.getClass().getDeclaredMethod("reset", boolean.class, boolean.class);
            m.setAccessible(true);
            m.invoke(vm, true, false);
            return;
        } catch (Throwable t) {
            log("leaving the password screen (reset) failed: " + t);
        }
        try {
            Method h = vm.getClass().getDeclaredMethod("hideBouncer", boolean.class);
            h.setAccessible(true);
            h.invoke(vm, false);
            Method u = vm.getClass().getDeclaredMethod("updateStates");
            u.setAccessible(true);
            u.invoke(vm);
        } catch (Throwable t) {
            log("leaving the password screen failed: " + t);
        }
    }

    static boolean canDismiss() {
        Object k = stateController;
        return k != null && bool(k, "mCanDismissLockScreen");
    }

    static void dismissAmount(float amount, boolean tracking) {
        Object k = stateController, u = unlockAnimation;
        if (k == null || u == null) return;
        if (!canDismiss() && amount > 0f) return;
        try {
            setField(k, "mDismissAmount", amount);
            setField(k, "mDismissingFromTouch", tracking);
            Method m = u.getClass().getDeclaredMethod("onKeyguardDismissAmountChanged");
            m.setAccessible(true);
            m.invoke(u);
        } catch (Throwable t) {
            log("swipe unlock failed: " + t);
        }
    }

    private static void endAnimators(Object ctl) {
        int ended = 0;
        for (Class<?> c = ctl.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!android.animation.Animator.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    android.animation.Animator a = (android.animation.Animator) f.get(ctl);
                    if (a != null && a.isStarted()) {
                        a.end();
                        ended++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (ended > 0) log("unlock animation ended early (" + ended + ")");
    }

    private static Object getField(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static volatile boolean goingAway;
    private static final String[] EXIT_IDS = {"scrim_behind", "scrim_notifications", "scrim_in_front"};
    private static final List<View> exitHidden = new ArrayList<>();

    private static void setGoingAway(boolean away) {
        if (goingAway == away) return;
        goingAway = away && on;
        if (goingAway && shadeWindow != null) {
            for (String name : EXIT_IDS) {
                int id = shadeWindow.getResources().getIdentifier(name, "id", "com.android.systemui");
                View s = id == 0 ? null : shadeWindow.findViewById(id);
                if (s != null && !exitHidden.contains(s)) {
                    exitHidden.add(s);
                    hide(s);
                }
            }
            log("unlocking: screen underneath shown at once, " + exitHidden.size() + " backdrops kept out");
        } else {
            for (View s : exitHidden) unhide(s);
            exitHidden.clear();
        }
        update();
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static boolean requestUnlock() {
        Object vm = viewManager;
        if (vm == null) return false;
        try {
            for (Method m : vm.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("showPrimaryBouncer")) continue;
                Class<?>[] t = m.getParameterTypes();
                Object[] a = new Object[t.length];
                for (int i = 0; i < t.length; i++) a[i] = t[i] == boolean.class ? Boolean.TRUE : t[i] == String.class ? "BB10" : null;
                m.setAccessible(true);
                m.invoke(vm, a);
                log("unlock requested");
                return true;
            }
        } catch (Throwable t) {
            log("unlock request failed: " + t);
        }
        return false;
    }

    private static final StringBuilder ops = new StringBuilder();
    private static String seen = "";

    static void onScreenOff() {
        KeyguardView v = view;
        if (v != null) v.onScreenOff();
    }

    static void typeIntoPassword(char c) {
        if (ops.length() < 64) ops.append(c);
    }

    static void resetTyped() {
        ops.setLength(0);
        seen = "";
    }

    static boolean typingAhead() {
        return ops.length() > 0 || android.os.SystemClock.uptimeMillis() - pwShownAt < SETTLE_MS;
    }

    private static String apply(String base, CharSequence keys) {
        StringBuilder s = new StringBuilder(base);
        for (int i = 0; i < keys.length(); i++) {
            char k = keys.charAt(i);
            if (k == '\b') {
                if (s.length() > 0) s.setLength(s.length() - 1);
            } else if (s.length() < 64) {
                s.append(k);
            }
        }
        return s.toString();
    }

    static int pendingLength() {
        return apply(seen, ops).length();
    }

    private static void flushPending() {
        EditText f = stockField();
        if (f != null) pollPassword(f);
    }

    static volatile long pwShownAt;
    private static final long SETTLE_MS = 1500;

    static void pollPassword(EditText f) {
        boolean settling = android.os.SystemClock.uptimeMillis() - pwShownAt < SETTLE_MS;
        String have = f.getText().toString(), base;
        if (have.startsWith(seen)) base = have;
        else if (settling) base = seen + have;
        else base = have;
        String target = apply(base, ops);
        ops.setLength(0);
        if (!target.equals(have)) {
            log("password field put right");
            f.setText(target);
            f.setSelection(f.length());
        }
        seen = target;
        autoCheck(f);
    }

    private static volatile boolean hold;

    static void setHold(boolean h) {
        if (hold == h) return;
        hold = h;
        update();
    }

    static boolean userUnlocked() {
        try {
            android.os.UserManager um = SysUi.sys.getSystemService(android.os.UserManager.class);
            return um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean bool(Object o, String field) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.getBoolean(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    static void log(String s) {
        if (!SysUi.logging(s)) return;
        Log.i(TAG, s);
        SysUi.note(s);
    }
}
