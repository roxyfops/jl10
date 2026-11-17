package com.bb10.launcher.gesture;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.bb10.launcher.LauncherActivity;
import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.R;
import com.bb10.launcher.RootShell;
import com.bb10.launcher.hub.HubStore;
import com.bb10.launcher.settings.SettingsActivity;
import com.bb10.launcher.ui.ActionMenu;
import com.bb10.launcher.ui.ActiveFramesPage;
import com.bb10.launcher.ui.Closable;
import com.bb10.launcher.ui.HubPage;
import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GestureService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static GestureService sInstance;

    LauncherApp app;
    Prefs prefs;
    Context themed;
    private WindowManager wm;
    private KeyguardManager km;
    private PowerManager pm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SwipeUp swipe;
    static final boolean GDEBUG = true;
    private HubOverlay hubOverlay;
    volatile String fgPkg;
    int statusBarH;

    private final Runnable periodic = this::capture;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateEdgeTouchable();
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                h.removeCallbacks(periodic);
                if (hubOverlay != null) hubOverlay.dismissNow();
                if (searchPanel != null) searchPanel.removeNow();
                if (preview != null) preview.removeNow();
                h.removeCallbacks(spareRun);
                if (spare != null) spare.dismissNow();
            }
            boolean unlocked = Intent.ACTION_USER_PRESENT.equals(i.getAction())
                    || (Intent.ACTION_SCREEN_ON.equals(i.getAction()) && !km.isKeyguardLocked());
            if (unlocked && prefs.bool(Prefs.GEST_PEEK, true)) {
                if (!hubWarm) {
                    h.removeCallbacks(warmHubRun);
                    h.postDelayed(warmHubRun, 1500);
                } else {
                    spareLater();
                }
            }
        }
    };

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        com.bb10.launcher.quickstep.RemoteAnims.shownReported = true;
        app = LauncherApp.get();
        app.ensureInit();
        prefs = app.prefs;
        themed = new ContextThemeWrapper(this, R.style.Theme_BB10);
        wm = getSystemService(WindowManager.class);
        km = getSystemService(KeyguardManager.class);
        pm = getSystemService(PowerManager.class);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusBarH = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        prefs.sp.registerOnSharedPreferenceChangeListener(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenRx, f);
        getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor(com.bb10.launcher.sysui.NoStatusBar.KEY), false,
                new android.database.ContentObserver(h) {
                    @Override
                    public void onChange(boolean self) {
                        setupEdge();
                    }
                });
        setupEdge();
        app.hub.addNewItemListener(newItem);
        applyHeadsUp();
        wallRx = (colors, which) -> {
            if ((which & android.app.WallpaperManager.FLAG_SYSTEM) != 0) loadWall();
        };
        try {
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallRx, h);
        } catch (Exception ignored) {
        }
        loadWall();
        if (prefs.bool(Prefs.GEST_PEEK, true)) h.postDelayed(warmHubRun, 3000);
    }

    static volatile Bitmap wall;
    private static volatile boolean wallLoading;
    private android.app.WallpaperManager.OnColorsChangedListener wallRx;

    public static Bitmap homeWall() {
        return wall;
    }

    private void loadWall() {
        loadWall(this, null);
    }

    public static void loadWall(Context c, Runnable done) {
        if (wallLoading) return;
        wallLoading = true;
        Context ctx = c.getApplicationContext();
        LauncherApp app = LauncherApp.get();
        app.bg.execute(() -> {
            Bitmap b = null;
            try {
                Drawable d = android.app.WallpaperManager.getInstance(ctx).getDrawable();
                if (d instanceof android.graphics.drawable.BitmapDrawable) b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            } catch (Throwable ignored) {
            }
            if (b == null && (app.prefs.sp.getBoolean("qs_module_installed", false) || com.bb10.launcher.RootShell.knownAvailable())) {
                java.io.File out = new java.io.File(ctx.getCacheDir(), "homewall");
                String o = out.getAbsolutePath(), dir = ctx.getCacheDir().getAbsolutePath();
                com.bb10.launcher.RootShell.Result r = com.bb10.launcher.RootShell.exec(4000,
                        "cp /data/system/users/0/wallpaper '" + o + "' && chmod 644 '" + o + "'",
                        "chown $(stat -c %u:%g '" + dir + "') '" + o + "' 2>/dev/null; chcon $(stat -c %C '" + dir + "') '" + o + "' 2>/dev/null; true");
                if (r.ok && out.exists()) b = android.graphics.BitmapFactory.decodeFile(o);
                out.delete();
            }
            Bitmap fit = b == null ? null : fitScreen(ctx, b);
            if (GDEBUG) android.util.Log.d("JL10Gest", "home wallpaper: " + (fit == null ? "none" : fit.getWidth() + "x" + fit.getHeight()));
            new Handler(Looper.getMainLooper()).post(() -> {
                if (fit != null) wall = fit;
                wallLoading = false;
                if (done != null) done.run();
            });
        });
    }

    private static Bitmap fitScreen(Context c, Bitmap b) {
        Rect real = c.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        int W = real.width(), H = real.height();
        if (b.getWidth() == W && b.getHeight() == H) return b;
        float s = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight());
        float sw = W / s, sh = H / s;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(b, new Rect(Math.round((b.getWidth() - sw) / 2), Math.round((b.getHeight() - sh) / 2),
                Math.round((b.getWidth() + sw) / 2), Math.round((b.getHeight() + sh) / 2)), new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    public static android.os.Bundle hubBridge(String op, float x, long t) {
        android.os.Bundle r = new android.os.Bundle();
        GestureService s = sInstance;
        if (s == null || s.app == null) {
            r.putString("reason", "service");
            return r;
        }
        if (s.km.isKeyguardLocked()) {
            r.putString("reason", "locked");
            return r;
        }
        r.putBoolean("ok", true);
        s.h.post(() -> s.hubFollow(op, x, t));
        return r;
    }

    public static void refreshEdge() {
        GestureService s = sInstance;
        if (s != null) s.h.post(s::updateEdgeTouchable);
    }

    private void hubFollow(String op, float x, long t) {
        switch (op) {
            case "begin":
                if (searchPanel != null) searchPanel.removeNow();
                if (hubOverlay == null) {
                    HubOverlay o = newHub();
                    if (o == null) return;
                    hubOverlay = o;
                    freshenFront();
                    o.startFollow();
                }
                break;
            case "move":
                if (hubOverlay != null) hubOverlay.followTo(x);
                break;
            case "commit":
                if (hubOverlay != null) {
                    hubOverlay.commitX = x;
                    hubOverlay.commitT = t;
                    hubOverlay.openFully();
                }
                break;
            case "cancel":
                if (hubOverlay != null && !hubOverlay.open) hubOverlay.dismiss();
                break;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key != null && key.startsWith("gesture")) setupEdge();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (app == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (app.frames.usesTaskSnapshots()) {
                onlyAppSwitches();
                return;
            }
            CharSequence p = e.getPackageName();
            if (p != null && p.toString().equals(fgPkg) && app.frames.trackable(fgPkg)) scheduleSettledCapture();
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), cls = e.getClassName();
        if (p == null || cls == null) return;
        String pkg = p.toString();
        if (!isActivity(pkg, cls.toString())) return;
        com.bb10.launcher.quickstep.RemoteAnims.appShown(pkg);
        if (hubOverlay != null && hubOverlay.open && !pkg.equals(fgPkg)) hubOverlay.dismissNow();
        if (searchPanel != null && !pkg.equals(fgPkg)) searchPanel.removeNow();
        if (fgPkg != null && !pkg.equals(fgPkg) && !fgPkg.equals(getPackageName()) && app.frames.usesTaskSnapshots())
            app.frames.afterLeft(fgPkg);
        fgPkg = pkg;
        updateEdgeTouchable();
        if (pkg.equals(waitingPkg) && launchDone != null) h.postDelayed(launchDone, 60);
        app.frames.onForeground(pkg);
        h.removeCallbacks(periodic);
        if (app.frames.trackable(pkg)) h.postDelayed(periodic, 1200);
    }

    private static final String SPLASH_ICON_ID = "android:id/splashscreen_icon_view";
    private static android.os.Handler sSplashWatch;
    private static volatile int sWatchRun;

    public static boolean watchAppSplash(String pkg, int task) {
        GestureService s = sInstance;
        if (s == null || pkg == null) return false;
        synchronized (GestureService.class) {
            if (sSplashWatch == null) {
                android.os.HandlerThread t = new android.os.HandlerThread("bb10-splash-watch", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                t.start();
                sSplashWatch = new android.os.Handler(t.getLooper());
            }
        }
        int run = ++sWatchRun;
        long start = android.os.SystemClock.uptimeMillis();
        sSplashWatch.post(() -> s.detail(true));
        sSplashWatch.post(new Runnable() {
            boolean seen;
            int absent;

            @Override
            public void run() {
                if (run != sWatchRun) return;
                long now = android.os.SystemClock.uptimeMillis();
                int there = s.splashIconShown(pkg);
                if (there == 1) {
                    seen = true;
                    absent = 0;
                } else if (there == 0) {
                    absent++;
                }
                if ((seen && absent >= 2) || (!seen && absent >= 6) || now - start > 8000) {
                    s.detail(false);
                    com.bb10.launcher.quickstep.RemoteAnims.appSplashGone(task, seen ? "watched" : now - start > 8000 ? "watch timed out" : "never seen");
                    return;
                }
                sSplashWatch.postDelayed(this, 100);
            }
        });
        return true;
    }

    private int splashIconShown(String pkg) {
        android.view.accessibility.AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) return -1;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(SPLASH_ICON_ID);
            boolean there = false;
            if (l != null) {
                for (android.view.accessibility.AccessibilityNodeInfo n : l) {
                    if (n.isVisibleToUser()) there = true;
                    n.recycle();
                }
            }
            return there ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private int detailFlags = -1;

    private void detail(boolean on) {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int extra = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            if (on) {
                if (detailFlags == -1) detailFlags = info.flags;
                info.flags |= extra;
            } else {
                if (detailFlags == -1) return;
                info.flags = detailFlags;
                detailFlags = -1;
            }
            setServiceInfo(info);
        } catch (Throwable ignored) {
        }
    }

    private boolean appSwitchesOnly;

    private void onlyAppSwitches() {
        if (appSwitchesOnly) return;
        appSwitchesOnly = true;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.notificationTimeout = 0;
            setServiceInfo(info);
            com.bb10.launcher.quickstep.ShellTransitions.note("accessibility: app switches only (task snapshots in use)");
        } catch (Throwable ignored) {
        }
    }

    private final java.util.HashMap<String, Boolean> activityCache = new java.util.HashMap<>();
    private long activityCacheAt;

    private boolean isActivity(String pkg, String cls) {
        String k = pkg + "/" + cls;
        Boolean known = activityCache.get(k);
        if (known != null) return known;
        boolean is;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            is = true;
        } catch (Exception ex) {
            is = false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (activityCache.size() > 400 || now - activityCacheAt > 10 * 60_000) {
            activityCache.clear();
            activityCacheAt = now;
        }
        activityCache.put(k, is);
        return is;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (sInstance == this) sInstance = null;
        com.bb10.launcher.quickstep.RemoteAnims.shownReported = false;
        h.removeCallbacksAndMessages(null);
        removeEdge();
        if (swipe != null) {
            swipe.quit();
            swipe = null;
        }
        if (hubOverlay != null) hubOverlay.dismissNow();
        if (preview != null) preview.removeNow();
        if (searchPanel != null) searchPanel.removeNow();
        if (app != null) {
            app.hub.removeNewItemListener(newItem);
        }
        try {
            unregisterReceiver(screenRx);
        } catch (Exception ignored) {
        }
        if (prefs != null) prefs.sp.unregisterOnSharedPreferenceChangeListener(this);
        if (wallRx != null) {
            try {
                android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallRx);
            } catch (Exception ignored) {
            }
        }
    }

    boolean canShoot() {
        return !km.isKeyguardLocked() && pm.isInteractive();
    }

    private void takeShot(Consumer<Bitmap> cb) {
        takeShot(cb, false);
    }

    private void takeShot(Consumer<Bitmap> cb, boolean gpu) {
        if (!canShoot()) {
            cb.accept(null);
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap soft = null;
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null && gpu) {
                            soft = hw;
                        } else if (hw != null) {
                            soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        hb.close();
                    }
                    cb.accept(soft);
                }

                @Override
                public void onFailure(int code) {
                    cb.accept(null);
                }
            });
        } catch (Throwable t) {
            cb.accept(null);
        }
    }

    Bitmap thumbOf(Bitmap full) {
        boolean noBar = "1".equals(android.provider.Settings.Global.getString(getContentResolver(), com.bb10.launcher.sysui.NoStatusBar.KEY));
        int top = noBar ? 0 : Math.min(statusBarH, full.getHeight() / 4);
        int w = full.getWidth(), hh = full.getHeight() - top;
        float s = 360f / Math.max(w, hh);
        Bitmap crop = Bitmap.createBitmap(full, 0, top, w, hh);
        Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(hh * s)), true);
        if (crop != small && crop != full) crop.recycle();
        return small;
    }

    private long lastCaptureAt, firstPendingChangeAt;
    private final Runnable settledCapture = () -> {
        firstPendingChangeAt = 0;
        capture();
    };

    private void scheduleSettledCapture() {
        long now = android.os.SystemClock.uptimeMillis();
        if (firstPendingChangeAt == 0) firstPendingChangeAt = now;
        h.removeCallbacks(settledCapture);
        long delay = 600;
        if (now - firstPendingChangeAt > 5000) delay = 0;
        delay = Math.max(delay, 3000 - (now - lastCaptureAt));
        h.postDelayed(settledCapture, Math.max(0, delay));
    }

    private void capture() {
        final String pkg = fgPkg;
        if (pkg == null || !app.frames.trackable(pkg) || !prefs.bool(Prefs.FRAMES_THUMBS, true)) return;
        if (hubOverlay != null || (swipe != null && swipe.active)) return;
        if (app.frames.usesTaskSnapshots()) {
            if (!canShoot()) return;
            app.frames.freshen(pkg);
            return;
        }
        lastCaptureAt = android.os.SystemClock.uptimeMillis();
        takeShot(full -> {
            if (full == null || !pkg.equals(fgPkg)) return;
            app.frames.setThumb(pkg, thumbOf(full));
            full.recycle();
        });
    }

    private void setupEdge() {
        removeEdge();
        setupTopEdge();
        if (!prefs.bool(Prefs.GEST_ON, true)) return;
        if (!prefs.bool(Prefs.GEST_HOME, true) && !prefs.bool(Prefs.GEST_PEEK, true)) return;
        if (swipe == null) swipe = new SwipeUp(this);
        swipe.start();
        setupLeftEdge();
        updateEdgeTouchable();
    }

    String frontPkg() {
        String p = fgPkg;
        if (p != null) return p;
        try {
            android.view.accessibility.AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) p = r.getPackageName().toString();
        } catch (Exception ignored) {
        }
        if (p != null && !p.equals("com.android.systemui")) fgPkg = p;
        return p;
    }

    boolean edgeLocked() {
        return km.isKeyguardLocked() || !pm.isInteractive() || com.bb10.launcher.lock.LockActivity.isShowing();
    }

    private View leftEdge;
    private WindowManager.LayoutParams leftLp;

    private void setupLeftEdge() {
        if (!prefs.bool(Prefs.GEST_PEEK, true)) return;
        int h = getResources().getDisplayMetrics().heightPixels;
        leftEdge = new LeftEdgeView(this);
        leftLp = new WindowManager.LayoutParams(
                Ui.dp(prefs.getInt(Prefs.GEST_EDGE, 20)), h / 3,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        leftLp.gravity = Gravity.TOP | Gravity.LEFT;
        leftLp.setFitInsetsTypes(0);
        leftLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            wm.addView(leftEdge, leftLp);
        } catch (Exception e) {
            leftEdge = null;
        }
    }

    private void removeEdge() {
        if (topEdge != null) {
            try {
                wm.removeView(topEdge);
            } catch (Exception ignored) {
            }
            topEdge = null;
        }
        if (leftEdge != null) {
            try {
                wm.removeView(leftEdge);
            } catch (Exception ignored) {
            }
            leftEdge = null;
        }
        if (swipe != null) swipe.stop();
    }

    private void updateEdgeTouchable() {
        boolean locked = edgeLocked();
        boolean home = LauncherActivity.peekTarget() != null;
        if (swipe != null) swipe.setTouchable(!locked && !home);
        if (topEdge != null && topLp != null) {
            int tf = topLp.flags;
            if (locked || home) tf |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else tf &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (tf != topLp.flags) {
                topLp.flags = tf;
                try {
                    wm.updateViewLayout(topEdge, topLp);
                } catch (Exception ignored) {
                }
            }
        }
        if (leftEdge != null && leftLp != null) {
            int lf = leftLp.flags;
            if (locked || getPackageName().equals(fgPkg)) lf |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else lf &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (lf != leftLp.flags) {
                leftLp.flags = lf;
                try {
                    wm.updateViewLayout(leftEdge, leftLp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private View topEdge;
    private WindowManager.LayoutParams topLp;

    private void setupTopEdge() {
        if (!"1".equals(android.provider.Settings.Global.getString(getContentResolver(), com.bb10.launcher.sysui.NoStatusBar.KEY))) return;
        topEdge = new TopEdgeView(this);
        topLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Ui.dp(10),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        topLp.gravity = Gravity.TOP | Gravity.LEFT;
        topLp.setFitInsetsTypes(0);
        topLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            wm.addView(topEdge, topLp);
        } catch (Exception e) {
            topEdge = null;
        }
    }

    private class TopEdgeView extends View {
        private float sx, sy;
        private boolean opened;

        TopEdgeView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = x;
                    sy = y;
                    opened = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!opened && y - sy > Ui.dp(12) && y - sy > Math.abs(x - sx)) {
                        opened = true;
                        if (prefs.bool(Prefs.PULL_ANDROID, false)) Ui.expandNotifications(GestureService.this);
                        else Ui.expandQuickSettings(GestureService.this);
                    }
                    return true;
            }
            return true;
        }
    }

    private class LeftEdgeView extends View {
        private float sx, sy;
        private boolean pulling;
        private VelocityTracker vt;

        LeftEdgeView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            if (vt == null) vt = VelocityTracker.obtain();
            MotionEvent copy = MotionEvent.obtain(e);
            copy.setLocation(x, y);
            vt.addMovement(copy);
            copy.recycle();
            float dx = x - sx, dy = y - sy;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = x;
                    sy = y;
                    pulling = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!pulling && dx > Ui.dp(14) && dx > Math.abs(dy)) {
                        pulling = true;
                        haptic();
                        showHubOverlay(null);
                    }
                    if (pulling && hubOverlay != null) hubOverlay.setProgress(Math.max(0, dx));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    int sw = getResources().getDisplayMetrics().widthPixels;
                    if (pulling && hubOverlay != null) {
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        if (up && (dx > sw * 0.22f || vx > Ui.dp(700))) hubOverlay.openFully();
                        else hubOverlay.dismiss();
                    }
                    pulling = false;
                    vt.recycle();
                    vt = null;
                    return true;
                }
            }
            return true;
        }
    }

    void haptic() {
        if (prefs.bool(Prefs.GEST_HAPTIC, true)) Ui.tick(this);
    }

    static WindowManager.LayoutParams fullScreenLp(boolean interactive) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!interactive) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return lp;
    }

    void goHome() {
        homeSentAt = android.os.SystemClock.uptimeMillis();
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        if (com.bb10.launcher.quickstep.ShellTransitions.debug
                && (kc == KeyEvent.KEYCODE_HOME || kc == KeyEvent.KEYCODE_APP_SWITCH) && e.getAction() == KeyEvent.ACTION_DOWN) {
            long late = android.os.SystemClock.uptimeMillis() - e.getEventTime();
            if (late > 60) com.bb10.launcher.quickstep.ShellTransitions.note("key " + KeyEvent.keyCodeToString(kc) + " reached us " + late + " ms late (launcher busy)");
        }
        if (com.bb10.launcher.lock.LockActivity.isShowing()
                && (kc == KeyEvent.KEYCODE_HOME || kc == KeyEvent.KEYCODE_APP_SWITCH)) {
            if (hubOverlay != null && e.getAction() == KeyEvent.ACTION_UP) h.post(hubOverlay::dismiss);
            return true;
        }
        if (kc == KeyEvent.KEYCODE_VOLUME_MUTE) return muteKey(e);
        if (app == null || km.isKeyguardLocked()) return false;
        if (com.bb10.launcher.quickstep.ShellTransitions.debug && e.getAction() == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(kc)
                && (kc < KeyEvent.KEYCODE_A || kc > KeyEvent.KEYCODE_Z) && (kc < KeyEvent.KEYCODE_0 || kc > KeyEvent.KEYCODE_9))
            com.bb10.launcher.quickstep.ShellTransitions.note("key " + KeyEvent.keyCodeToString(kc) + " (scan " + e.getScanCode() + ")");
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) return homeKey(e);
        if (e.getKeyCode() != KeyEvent.KEYCODE_APP_SWITCH) return false;
        if (recentsUnused()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) h.post(() -> {
            if (hubOverlay != null) hubOverlay.dismissNow();
            recentsPressed(this);
        });
        return true;
    }

    private boolean muteKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_UP) return true;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        try {
            android.media.session.MediaController mc = app == null ? null : app.live.current();
            if (am.isMusicActive() || mc != null) {
                long now = android.os.SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            } else {
                boolean normal = am.getRingerMode() == android.media.AudioManager.RINGER_MODE_NORMAL;
                am.setRingerMode(normal ? android.media.AudioManager.RINGER_MODE_VIBRATE : android.media.AudioManager.RINGER_MODE_NORMAL);
            }
            haptic();
        } catch (Exception ex) {
            com.bb10.launcher.quickstep.ShellTransitions.note("mute key: " + ex);
        }
        return true;
    }

    private boolean homePending;

    private long homeSentAt;

    private boolean homeKey(KeyEvent e) {
        if (android.os.SystemClock.uptimeMillis() - homeSentAt < 1000) return false;
        String pkg = fgPkg;
        boolean snap = prefs.bool(Prefs.FRAMES_THUMBS, true) && app.frames.trackable(pkg) && (swipe == null || !swipe.active) && hubOverlay == null;
        if (snap && app.frames.usesTaskSnapshots() && com.bb10.launcher.quickstep.ShellTransitions.homeRegistered()) return false;
        if (!snap) return false;
        if (com.bb10.launcher.quickstep.ShellTransitions.homeRegistered()) return false;
        if (prefs.bool(Prefs.FRAMES_MINIMIZE, true)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && (swipe == null || !swipe.minimiseNow())) goHome();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !homePending) {
            homePending = true;
            h.removeCallbacks(settledCapture);
            Runnable go = () -> {
                if (!homePending) return;
                homePending = false;
                homeSentAt = android.os.SystemClock.uptimeMillis();
                goHome();
            };
            h.post(() -> takeShot(full -> {
                if (full != null) {
                    app.frames.setThumb(pkg, thumbOf(full));
                    full.recycle();
                }
                go.run();
            }));
            h.postDelayed(go, 350);
        }
        return true;
    }

    private PreviewBanner preview;
    private final com.bb10.launcher.hub.HubStore.NewItemListener newItem = this::showPreview;

    private void applyHeadsUp() {
        app.applyHeadsUp();
    }

    private boolean previewShown;

    private void previewState(boolean showing) {
        if (previewShown == showing) return;
        previewShown = showing;
        try {
            sendBroadcast(new Intent("com.bb10.launcher.PREVIEW").setPackage("com.android.systemui").putExtra("showing", showing));
        } catch (Exception ignored) {
        }
    }

    private void freshenFront() {
        String pkg = fgPkg;
        if (pkg == null || pkg.equals(getPackageName()) || !app.frames.usesTaskSnapshots()) return;
        if (!app.frames.trackable(pkg) || !canShoot()) return;
        app.frames.freshen(pkg);
    }

    private void showPreview(com.bb10.launcher.hub.HubItem it) {
        if (!prefs.bool(Prefs.INSTANT_PREVIEWS, false)) return;
        if (km.isKeyguardLocked() || !pm.isInteractive()) return;
        if (searchPanel != null || hubOverlay != null) return;
        freshenFront();
        if (preview != null && preview.replying) return;
        if (preview != null && preview.closing) preview.removeNow();
        if (preview == null) {
            PreviewBanner b = new PreviewBanner(themed);
            try {
                wm.addView(b, b.lp);
            } catch (Exception e) {
                return;
            }
            preview = b;
            previewState(true);
            b.show(it, true);
        } else {
            preview.show(it, false);
        }
    }

    private class PreviewBanner extends FrameLayout {
        final WindowManager.LayoutParams lp;
        private final LinearLayout card, replyRow;
        private final ImageView avatar, appIcon;
        private final android.widget.TextView title, time, body;
        private final android.widget.EditText field;
        private final LinearLayout actions;
        private final View replyBtn;
        private int extra;
        private com.bb10.launcher.hub.HubItem item;
        boolean replying;
        boolean closing;
        private float downX, downY;
        private final Runnable autoHide = this::dismiss;

        PreviewBanner(Context c) {
            super(c);
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.setFitInsetsTypes(0);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xF51A1A1A);
            card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(12), Ui.dp(12));

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new ImageView(c);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            top.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout l1 = new LinearLayout(c);
            l1.setGravity(Gravity.CENTER_VERTICAL);
            title = Ui.text(c, "", 17, Ui.TEXT, true);
            title.setSingleLine();
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l1.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            time = Ui.text(c, "", 12.5f, Ui.TEXT2, false);
            l1.addView(time);
            col.addView(l1);
            body = Ui.text(c, "", 15, 0xDDFFFFFF, false);
            body.setMaxLines(2);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setPadding(0, Ui.dp(3), 0, 0);
            col.addView(body);
            actions = new LinearLayout(c);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(0, Ui.dp(4), 0, 0);
            replyBtn = action(c, R.drawable.ic_reply, "Reply", v -> startReply());
            actions.addView(replyBtn);
            actions.addView(action(c, R.drawable.ic_read, "Mark Read", v -> markRead()));
            actions.addView(action(c, R.drawable.ic_delete, "Delete", v -> deleteItem()));
            actions.addView(action(c, R.drawable.ic_open, "Open", v -> openItem()));
            col.addView(actions);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = Ui.dp(12);
            clp.rightMargin = Ui.dp(10);
            top.addView(col, clp);
            appIcon = new ImageView(c);
            top.addView(appIcon, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
            card.addView(top);

            replyRow = new LinearLayout(c);
            replyRow.setOrientation(LinearLayout.HORIZONTAL);
            replyRow.setGravity(Gravity.CENTER_VERTICAL);
            replyRow.setPadding(0, Ui.dp(6), 0, 0);
            field = new android.widget.EditText(c);
            field.setBackground(Ui.round(0xFF333333, 3));
            field.setTextColor(Ui.TEXT);
            field.setHintTextColor(Ui.TEXT2);
            field.setHint("Reply");
            field.setTextSize(14);
            field.setTypeface(Ui.regular());
            field.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
            field.setMaxLines(3);
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            field.setOnEditorActionListener((v, id, ev) -> {
                sendReply();
                return true;
            });
            field.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN && !ev.isShiftPressed()) {
                    sendReply();
                    return true;
                }
                return false;
            });
            replyRow.addView(button(c, R.drawable.ic_close, v -> dismiss()));
            replyRow.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            replyRow.addView(button(c, R.drawable.ic_open, v -> openItem()));
            replyRow.addView(button(c, R.drawable.ic_send, v -> sendReply()));
            replyRow.setVisibility(GONE);
            card.addView(replyRow);

            card.setOnClickListener(v -> {
                if (replying) return;
                android.app.Notification n = app.hub.live(item);
                if (com.bb10.launcher.hub.HubActions.replyAction(n) != null) startReply();
                else openItem();
            });
            addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        private View action(Context c, int icon, String label, OnClickListener l) {
            LinearLayout b = new LinearLayout(c);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(0, Ui.dp(4), Ui.dp(14), Ui.dp(4));
            b.setBackground(Ui.selectable(0));
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(Ui.glyph(c, icon, Ui.BLUE));
            b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
            android.widget.TextView t = Ui.text(c, label, 12.5f, Ui.BLUE, false);
            t.setPadding(Ui.dp(4), 0, 0, 0);
            b.addView(t);
            b.setOnClickListener(l);
            return b;
        }

        private void markRead() {
            if (item != null) app.hub.setRead(item, true);
            dismiss();
        }

        private void deleteItem() {
            if (item != null) app.hub.delete(item);
            dismiss();
        }

        private View button(Context c, int icon, OnClickListener l) {
            ImageView b = new ImageView(c);
            b.setImageDrawable(Ui.glyph(c, icon, Ui.TEXT));
            b.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            b.setBackground(Ui.selectable(0));
            b.setOnClickListener(l);
            b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
            return b;
        }

        private CharSequence smallEmoji(String s) {
            if (s == null) return "";
            android.text.SpannableString out = new android.text.SpannableString(s);
            int i = 0;
            while (i < s.length()) {
                int cp = s.codePointAt(i);
                if (emoji(cp) && cp != 0x200D && cp != 0xFE0F) {
                    int j = i + Character.charCount(cp);
                    while (j < s.length() && emoji(s.codePointAt(j))) j += Character.charCount(s.codePointAt(j));
                    out.setSpan(new android.text.style.RelativeSizeSpan(0.72f), i, j, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = j;
                } else i += Character.charCount(cp);
            }
            return out;
        }

        private boolean emoji(int cp) {
            return cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B05 && cp <= 0x2B55) || (cp >= 0x231A && cp <= 0x23FF)
                    || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x200D || cp == 0xFE0F || cp == 0x20E3;
        }

        private void showAvatar(com.bb10.launcher.hub.HubItem it) {
            LauncherApp app = LauncherApp.get();
            Drawable av = null;
            android.graphics.drawable.Icon ic = app.hub.avatar(it);
            if (ic != null) {
                try {
                    av = ic.loadDrawable(getContext());
                } catch (Exception ignored) {
                }
            }
            avatar.setImageDrawable(av != null ? av : app.iconForPackage(it.pkg));
        }

        void show(com.bb10.launcher.hub.HubItem it, boolean animateIn) {
            if (!animateIn && item != null && item != it) extra++;
            item = it;
            title.setText(smallEmoji(it.title));
            body.setText(smallEmoji(it.text));
            CharSequence when = android.text.format.DateFormat.format(
                    android.text.format.DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm a", it.time);
            time.setText(extra > 0 ? when + "  +" + extra : when);
            replyBtn.setVisibility(com.bb10.launcher.hub.HubActions.replyAction(app.hub.live(it)) != null ? VISIBLE : GONE);
            appIcon.setImageDrawable(app.iconForPackage(it.pkg));
            showAvatar(it);
            if (!app.hub.avatarSaved(it)) {
                com.bb10.launcher.hub.HubItem was = it;
                for (long t : new long[]{300, 900, 2000}) h.postDelayed(() -> {
                    if (item == was && app.hub.avatarSaved(was)) showAvatar(was);
                }, t);
            }
            if (animateIn) {
                card.setTranslationY(-Ui.dp(120));
                card.animate().translationY(0).setDuration(220).start();
            }
            h.removeCallbacks(autoHide);
            h.postDelayed(autoHide, 5000);
            if (animateIn && prefs.bool(Prefs.GEST_HAPTIC, true)) Ui.tick(GestureService.this);
        }

        private void startReply() {
            replying = true;
            h.removeCallbacks(autoHide);
            actions.setVisibility(GONE);
            replyRow.setVisibility(VISIBLE);
            body.setMaxLines(4);
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            try {
                wm.updateViewLayout(this, lp);
            } catch (Exception ignored) {
            }
            field.requestFocus();
            android.view.inputmethod.InputMethodManager imm = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) field.post(() -> imm.showSoftInput(field, 0));
        }

        private void sendReply() {
            String text = field.getText().toString();
            if (text.trim().isEmpty() || item == null) return;
            boolean ok = com.bb10.launcher.hub.HubActions.reply(getContext(), app.hub.live(item), text);
            if (ok) app.hub.setRead(item, true);
            android.widget.Toast.makeText(getContext(), ok ? "Sent" : "Couldn't send", android.widget.Toast.LENGTH_SHORT).show();
            if (ok) dismiss();
        }

        private void openItem() {
            if (item != null) com.bb10.launcher.hub.HubActions.open(GestureService.this, app.hub, item, card);
            dismiss();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY, min = Ui.dp(replying ? 24 : 12);
                if (Math.abs(dx) > min || dy < -min) return true;
            }
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                if (replying) dismiss();
                return true;
            }
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(dx) > Math.abs(dy)) card.setTranslationX(dx);
                    else card.setTranslationY(Math.min(0, dy));
                    h.removeCallbacks(autoHide);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(dx) > getWidth() * 0.3f || dy < -Ui.dp(30)) dismiss();
                    else {
                        card.animate().translationX(0).translationY(0).setDuration(150).start();
                        if (!replying) h.postDelayed(autoHide, 3000);
                    }
                    return true;
            }
            return true;
        }

        void dismiss() {
            if (closing) return;
            closing = true;
            previewState(false);
            h.removeCallbacks(autoHide);
            android.view.inputmethod.InputMethodManager imm = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            card.animate().translationY(-card.getHeight() - Ui.dp(40)).alpha(0f).setDuration(180).withEndAction(this::removeNow).start();
        }

        void removeNow() {
            closing = true;
            h.removeCallbacks(autoHide);
            card.animate().cancel();
            if (preview == this) {
                preview = null;
                previewState(false);
            }
            try {
                wm.removeView(this);
            } catch (Exception ignored) {
            }
        }
    }

    private SearchPanel searchPanel;

    public static void recentsPressed(Context c) {
        LauncherApp app = LauncherApp.get();
        if (recentsUnused()) return;
        if (!"last_app".equals(app.prefs.sp.getString(Prefs.RECENTS_ACTION, "search"))) {
            if (!showSearchOverlay()) openLauncherPage(c, "search");
            return;
        }
        GestureService s = sInstance;
        if (s != null) s.h.post(() -> {
            if (s.hubOverlay != null) s.hubOverlay.dismissNow();
            if (s.searchPanel != null) s.searchPanel.removeNow();
        });
        String cur = s != null && s.fgPkg != null ? s.fgPkg : app.frames.lastForeground();
        for (com.bb10.launcher.frames.FrameTracker.Frame f : new ArrayList<>(app.frames.list())) {
            if (f.pkg.equals(cur) || f.pkg.equals(c.getPackageName())) continue;
            Intent li = c.getPackageManager().getLaunchIntentForPackage(f.pkg);
            if (li == null) continue;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            li.setPackage(null);
            try {
                c.startActivity(li);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    static boolean recentsUnused() {
        LauncherApp app = LauncherApp.get();
        return app != null && app.prefs != null && "none".equals(app.prefs.sp.getString(Prefs.RECENTS_ACTION, "search"));
    }

    public static boolean showSearchOverlay() {
        GestureService s = sInstance;
        if (s == null || s.app == null) return false;
        s.h.post(s::toggleSearchPanel);
        return true;
    }

    private long lastToggle;

    private void toggleSearchPanel() {
        if (km.isKeyguardLocked()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastToggle < 500) return;
        lastToggle = now;
        com.bb10.launcher.quickstep.ShellTransitions.note("search panel toggle (" + (searchPanel != null ? "close" : "open") + ")");
        if (searchPanel != null) {
            searchPanel.dismiss();
            return;
        }
        if (hubOverlay != null) hubOverlay.dismissNow();
        SearchPanel p = new SearchPanel(themed);
        try {
            wm.addView(p, p.lp);
        } catch (Exception e) {
            openLauncherPage(this, "search");
            return;
        }
        searchPanel = p;
        freshenFront();
        p.slideIn();
    }

    private class SearchPanel extends FrameLayout implements com.bb10.launcher.ui.SearchOverlay.Host {
        final WindowManager.LayoutParams lp = fullScreenLp(true);
        private final View dim;
        private final LinearLayout panel;
        private final com.bb10.launcher.ui.SearchOverlay search;
        private final StatusBarView status;
        private final int W, slop;
        private boolean closing, swiping;
        private float downX, downY;

        SearchPanel(Context c) {
            super(c);
            W = c.getResources().getDisplayMetrics().widthPixels;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            dim = new View(c);
            dim.setBackgroundColor(0x99000000);
            dim.setAlpha(0f);
            dim.setOnClickListener(v -> dismiss());
            addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            panel = new LinearLayout(c);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xFF121212);
            panel.setClickable(true);
            status = new StatusBarView(c);
            panel.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(statusBarH, Math.round(Ui.s(StatusBarView.HEIGHT)))));
            search = new com.bb10.launcher.ui.SearchOverlay(c, this);
            panel.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            panel.setTranslationX(W);
            setFocusableInTouchMode(true);
        }

        void slideIn() {
            panel.animate().translationX(0).setDuration(200).withEndAction(() -> search.setQuery("")).start();
            dim.animate().alpha(1f).setDuration(200).start();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            status.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            status.stop();
            super.onDetachedFromWindow();
        }

        void dismiss() {
            if (closing) return;
            closing = true;
            android.view.inputmethod.InputMethodManager imm = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            dim.animate().alpha(0f).setDuration(160).start();
            panel.animate().translationX(W).setDuration(160).withEndAction(this::removeNow).start();
        }

        void leave() {
            if (closing) return;
            closing = true;
            animate().alpha(0f).setDuration(120).withEndAction(this::removeNow).start();
        }

        void removeNow() {
            closing = true;
            if (searchPanel == this) searchPanel = null;
            try {
                wm.removeView(this);
            } catch (Exception ignored) {
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (dx > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        swiping = true;
                        return true;
                    }
                    break;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!swiping) return super.onTouchEvent(e);
            float dx = Math.max(0, e.getRawX() - downX);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    panel.setTranslationX(dx);
                    dim.setAlpha(1f - dx / W);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dx > W * 0.25f) dismiss();
                    else {
                        panel.animate().translationX(0).setDuration(150).start();
                        dim.animate().alpha(1f).setDuration(150).start();
                    }
                    swiping = false;
                    break;
            }
            return true;
        }

        @Override
        public void launchApp(String key, View src) {
            com.bb10.launcher.model.AppInfo a = app.apps.app(key);
            if (a == null) return;
            if (a.pkg.equals(getPackageName()) && a.cn.getClassName().endsWith(".HubShortcutActivity")) {
                leave();
                openLauncherPage(GestureService.this, "hub");
                return;
            }
            Intent i = Intent.makeMainActivity(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                GestureService.this.startActivity(i);
            } catch (Exception ignored) {
            }
            leave();
        }

        @Override
        public void startActivity(Intent i) {
            GestureService.this.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            leave();
        }

        @Override
        public void requestContactsPermission() {
            leave();
            openLauncherPage(GestureService.this, "search");
        }

        @Override
        public void closeSearch(com.bb10.launcher.ui.SearchOverlay s) {
            if (!closing) leave();
        }
    }

    public static void openLauncherPage(Context c, String page) {
        Intent i = new Intent(c, LauncherActivity.class)
                .setAction(LauncherActivity.ACTION_SHOW)
                .putExtra(LauncherActivity.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    RectF slotRect(int W, int H) {
        Rect slot = ActiveFramesPage.slotFor(com.bb10.launcher.LauncherApp.get().frames.lastForeground());
        if (slot.width() <= 0) {
            float S = Math.min(W, H);
            int gap = Ui.dp(5), top = Math.round(S * com.bb10.launcher.ui.StatusBarView.HEIGHT) + gap;
            int fw = (W - 3 * gap) / 2, fh = Math.round((H - S * com.bb10.launcher.ui.StatusBarView.HEIGHT - S * 0.208f - 3 * gap) / 2);
            slot.set(gap, top, gap + fw, top + fh);
        }
        return new RectF(slot);
    }

    static RectF lerp(RectF a, RectF b, float t) {
        return new RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    static class ClipCard extends View {
        Bitmap bmp;
        Drawable icon;
        final RectF r = new RectF();
        float cropTop;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

        ClipCard(Context c) {
            super(c);
            shadow.setColor(0x66000000);
        }

        float trimBottom;
        float shadowAlpha = 1f;

        void set(RectF x) {
            r.set(x);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (r.width() < 1 || r.height() < 1) return;
            float sh = Ui.s(0.008f);
            float shown = Math.max(r.top + 1, r.bottom - trimBottom);
            if (r.width() < getWidth() - 1 && shadowAlpha > 0f) {
                shadow.setAlpha(Math.round(0x66 * shadowAlpha));
                c.drawRect(r.left - sh / 2, r.top, r.right + sh / 2, shown + sh, shadow);
            }
            c.save();
            c.clipRect(r.left, r.top, r.right, shown);
            c.drawColor(0xFF1C1C1C);
            if (bmp != null && !bmp.isRecycled()) {
                float bw = bmp.getWidth(), bh = bmp.getHeight() - cropTop;
                float s = Math.max(r.width() / bw, r.height() / bh);
                float sw = r.width() / s, shh = r.height() / s;
                float sx = (bw - sw) / 2f;
                Rect src = new Rect(Math.round(sx), Math.round(cropTop), Math.round(sx + sw), Math.round(cropTop + shh));
                c.drawBitmap(bmp, src, r, p);
            } else if (icon != null) {
                float s = Math.min(r.width(), r.height()) * 0.36f;
                icon.setBounds(Math.round(r.centerX() - s / 2), Math.round(r.centerY() - s / 2), Math.round(r.centerX() + s / 2), Math.round(r.centerY() + s / 2));
                icon.draw(c);
            }
            c.restore();
        }
    }

    private String waitingPkg;
    private Runnable launchDone;

    public static boolean playLaunch(Rect from, Bitmap pic, Drawable icon, String pkg, Runnable startApp) {
        GestureService s = sInstance;
        if (s == null || s.app == null || !s.canShoot()) return false;
        s.launchAnim(new RectF(from), pic, icon, pkg, startApp);
        return true;
    }

    private void launchAnim(RectF from, Bitmap pic, Drawable icon, String pkg, Runnable startApp) {
        takeShot(bg -> {
            if (bg == null) {
                startApp.run();
                return;
            }
            FrameLayout o = new FrameLayout(themed);
            ImageView back = new ImageView(themed);
            back.setScaleType(ImageView.ScaleType.FIT_XY);
            back.setImageBitmap(bg);
            o.addView(back, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            ClipCard cc = new ClipCard(themed);
            cc.bmp = pic;
            cc.icon = icon;
            cc.set(from);
            o.addView(cc, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            try {
                wm.addView(o, fullScreenLp(false));
            } catch (Exception e) {
                startApp.run();
                return;
            }
            Runnable remove = () -> {
                waitingPkg = null;
                launchDone = null;
                o.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    try {
                        wm.removeView(o);
                    } catch (Exception ignored) {
                    }
                }).start();
            };
            o.post(() -> {
                startApp.run();
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(300);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
                RectF to = new RectF(0, 0, o.getWidth(), o.getHeight());
                va.addUpdateListener(a -> cc.set(lerp(from, to, (float) a.getAnimatedValue())));
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (pkg.equals(fgPkg)) {
                            h.postDelayed(remove, 60);
                        } else {
                            waitingPkg = pkg;
                            launchDone = remove;
                            h.postDelayed(() -> {
                                if (launchDone == remove) remove.run();
                            }, 700);
                        }
                    }
                });
                va.start();
            });
        });
    }

    private HubOverlay spare;
    private final Runnable spareRun = this::makeSpare;

    private void makeSpare() {
        if (sInstance != this || spare != null || hubOverlay != null) return;
        if (!pm.isInteractive() || km.isKeyguardLocked()) return;
        if (swipe != null && swipe.active) {
            h.removeCallbacks(spareRun);
            h.postDelayed(spareRun, 3000);
            return;
        }
        HubOverlay o = new HubOverlay(themed);
        o.panel.setAlpha(1f);
        o.panel.setTranslationX(0);
        o.lp.alpha = 0f;
        try {
            wm.addView(o, o.lp);
        } catch (Exception e) {
            return;
        }
        spare = o;
        h.postDelayed(() -> {
            if (spare != o) return;
            o.resetForReuse();
            setSpareShown(o, false);
        }, 900);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && spare != null) {
            h.removeCallbacks(spareRun);
            HubOverlay o = spare;
            o.dismissNow();
            h.removeCallbacks(spareRun);
            h.postDelayed(spareRun, 60_000);
        }
    }

    private void setSpareShown(HubOverlay o, boolean shown) {
        float a = shown ? 1f : 0f;
        if (o.lp.alpha == a) return;
        o.lp.alpha = a;
        try {
            wm.updateViewLayout(o, o.lp);
        } catch (Exception ignored) {
        }
    }

    private HubOverlay newHub() {
        HubOverlay o = spare;
        spare = null;
        if (o != null && o.isAttachedToWindow()) {
            setSpareShown(o, true);
            return o;
        }
        o = new HubOverlay(themed);
        try {
            wm.addView(o, o.lp);
        } catch (Exception e) {
            return null;
        }
        return o;
    }

    private void spareLater() {
        h.removeCallbacks(spareRun);
        h.postDelayed(spareRun, 1500);
    }

    private void showHubOverlay(Bitmap shot) {
        if (hubOverlay != null) return;
        HubOverlay o = newHub();
        if (o == null) return;
        hubOverlay = o;
        freshenFront();
        if (shot != null) {
            o.setAppShot(shot);
        } else {
            o.waitingForShot = true;
            takeShot(full -> {
                if (hubOverlay != o) return;
                o.waitingForShot = false;
                if (full != null) o.setAppShot(full);
                else o.useSlideIn();
            }, true);
            h.postDelayed(() -> {
                if (hubOverlay == o && o.waitingForShot) {
                    o.waitingForShot = false;
                    o.useSlideIn();
                }
            }, 250);
        }
    }

    private void prepareHubOverCard() {
        if (hubOverlay != null) return;
        HubOverlay o = newHub();
        if (o == null) return;
        o.overCard = true;
        o.useSlideIn();
        hubOverlay = o;
    }

    private boolean hubWarm;
    private final Runnable warmHubRun = this::warmHub;

    private void warmHub() {
        if (sInstance != this || hubWarm) return;
        if (hubOverlay != null || (swipe != null && swipe.active) || !pm.isInteractive() || km.isKeyguardLocked()) return;
        hubWarm = true;
        makeSpare();
    }

    private void dropPreparedHub() {
        HubOverlay o = hubOverlay;
        if (o == null || !o.overCard || o.open || o.closing) return;
        if (spare == null && o.reusable()) {
            hubOverlay = null;
            o.resetForReuse();
            spare = o;
            setSpareShown(o, false);
            return;
        }
        o.dismissNow();
    }

    interface Below {
        void cancel();

        void removeNow();

        default void follow(float edge) {}
    }

    private volatile float hubX;
    private final java.util.concurrent.atomic.AtomicBoolean hubXPosted = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable hubXRun = () -> {
        hubXPosted.set(false);
        if (hubOverlay != null) hubOverlay.setProgress(hubX);
    };

    private void hubTo(float x) {
        hubX = x;
        if (hubXPosted.compareAndSet(false, true)) h.post(hubXRun);
    }

    boolean homeFront() {
        return LauncherActivity.peekTarget() != null;
    }

    void homePeek(float dy) {
        h.post(() -> {
            LauncherActivity a = LauncherActivity.peekTarget();
            if (a != null) a.homePeek(dy);
        });
    }

    public static void peekBar(android.content.Context c, boolean on) {
        try {
            c.sendBroadcast(new Intent("com.bb10.launcher.PEEK").setPackage("com.android.systemui").putExtra("on", on));
        } catch (Exception ignored) {
        }
    }

    void homePeekSide(float x) {
        h.post(() -> {
            LauncherActivity a = LauncherActivity.peekTarget();
            if (a != null) a.homePeekSide(x);
        });
    }

    void homePeekEnd(int to) {
        homePeekEnd(to, 0f);
    }

    void homePeekEnd(int to, float vx) {
        h.post(() -> {
            LauncherActivity a = LauncherActivity.peekTarget();
            if (a != null) a.homePeekEnd(to, vx);
        });
    }

    void hubPeekBegin(Bitmap shot) {
        h.post(() -> {
            dropPreparedHub();
            showHubOverlay(shot);
        });
    }

    void hubPeekProgress(float x) {
        hubTo(x);
    }

    void hubPeekRelease(boolean open, float vx) {
        h.post(() -> {
            if (hubOverlay == null) return;
            hubOverlay.releaseV = vx;
            if (open) hubOverlay.openFully();
            else hubOverlay.dismiss();
        });
    }

    void hubOverCardPrepare() {
        h.post(this::prepareHubOverCard);
    }

    void hubOverCardProgress(float x) {
        hubTo(x);
    }

    void hubOverCardTake(Bitmap app, RectF from, RectF to, float crop, boolean band) {
        h.post(() -> {
            HubOverlay o = hubOverlay;
            if (o != null && o.overCard && !o.closing) {
                o.takeCard(app, from, to, crop);
                if (band) o.showBand();
            }
        });
    }

    void hubOverCardRelease(boolean open, Below below, float vx) {
        h.post(() -> {
            HubOverlay o = hubOverlay;
            if (o == null || !o.overCard || o.closing) {
                below.cancel();
                return;
            }
            o.cardBelow = below;
            o.releaseV = vx;
            if (open) o.openFully();
            else o.dismiss();
        });
    }

    void hubDropPrepared() {
        h.post(this::dropPreparedHub);
    }

    void thumbLater(String pkg, Bitmap b) {
        h.postDelayed(() -> {
            try {
                app.frames.setThumb(pkg, thumbOf(b));
            } catch (Exception ignored) {
            }
        }, 400);
    }

    List<Object[]> notifyingApps() {
        java.util.LinkedHashMap<String, Object[]> out = new java.util.LinkedHashMap<>();
        try {
            for (String pkg : new ArrayList<>(app.hub.unreadPackages())) {
                out.put(pkg, new Object[]{pkg, app.hub.smallIcon(pkg), app.hub.unread(pkg)});
            }
        } catch (Exception ignored) {
        }
        for (android.service.notification.StatusBarNotification n : com.bb10.launcher.hub.HubListenerService.newsNow()) {
            String pkg = n.getPackageName();
            Object[] e = out.get(pkg);
            if (e == null) out.put(pkg, new Object[]{pkg, n.getNotification().getSmallIcon(), 1});
            else if (!app.hub.unreadPackages().contains(pkg)) e[2] = (Integer) e[2] + 1;
        }
        return new ArrayList<>(out.values());
    }

    private static final long FOLLOW_MS = 200;

    private class HubOverlay extends FrameLayout implements HubPage.Host, HubStore.Listener {
        final WindowManager.LayoutParams lp = fullScreenLp(false);
        private final View dim;
        private final FrameLayout panel, menus;
        private final HubPage hub;
        private final StatusBarView status;
        private final ImageView appCard;
        private final int W;
        private final int slop;
        boolean open, waitingForShot;
        boolean overCard;
        Below cardBelow;
        private boolean reveal;
        private float lastDx;
        private boolean closing, swiping;
        private float downX, downY;
        private VelocityTracker vt;

        void setAppShot(Bitmap b) {
            reveal = true;
            appCard.setImageBitmap(b);
            appCard.setVisibility(VISIBLE);
            panel.setTranslationX(-W);
            panel.setAlpha(1f);
            dim.setAlpha(0f);
            setProgress(lastDx);
            if (open) openFully();
        }

        private boolean follow;
        float commitX;
        long commitT;

        void startFollow() {
            follow = true;
            reveal = false;
            appCard.setVisibility(GONE);
            panel.setAlpha(1f);
            panel.setTranslationX(-W);
            dim.setAlpha(0f);
        }

        void followTo(float x) {
            if (open || closing) return;
            panel.setTranslationX(Math.min(0, x - W));
        }

        void useSlideIn() {
            reveal = false;
            appCard.setVisibility(GONE);
            panel.setAlpha(1f);
            panel.setTranslationX(-W);
            setProgress(lastDx);
            if (open) openFully();
        }

        HubOverlay(Context c) {
            super(c);
            W = c.getResources().getDisplayMetrics().widthPixels;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            dim = new View(c);
            dim.setBackgroundColor(0xCC000000);
            dim.setAlpha(0f);
            addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            panel = new FrameLayout(c);
            boolean hubBright = !"dark".equals(LauncherApp.get().prefs.sp.getString(com.bb10.launcher.Prefs.HUB_THEME, "bright"));
            panel.setBackgroundColor(hubBright ? 0xFFEDEDED : 0xFF262626);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            status = new StatusBarView(c);
            status.solidBg = 0xFF000000;
            status.darkIntensity = 0f;
            status.pullOpensNotifications = LauncherApp.get().prefs.bool(com.bb10.launcher.Prefs.PULL_ANDROID, false);
            col.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(statusBarH, Ui.dp(26))));
            hub = new HubPage(c, this);
            col.addView(hub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            panel.addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            menus = new FrameLayout(c);
            panel.addView(menus, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            appCard = new ImageView(c);
            appCard.setScaleType(ImageView.ScaleType.FIT_XY);
            appCard.setVisibility(GONE);
            appCard.setElevation(Ui.dp(12));
            addView(appCard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            panel.setAlpha(0f);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);
        }

        private View barButton(Context c, int icon, Runnable r) {
            FrameLayout b = new FrameLayout(c);
            b.setBackground(Ui.selectable(0));
            b.setFocusable(true);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(Ui.glyph(c, icon, Ui.TEXT));
            b.addView(iv, new LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
            b.setOnClickListener(v -> r.run());
            b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(64), ViewGroup.LayoutParams.MATCH_PARENT));
            return b;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            app.hub.addListener(this);
            status.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            app.hub.removeListener(this);
            status.stop();
            if (underBar != null) underBar.stop();
            super.onDetachedFromWindow();
        }

        @Override
        public void onHubChanged() {
            hub.refresh();
            status.invalidate();
        }

        private FrameLayout under;
        private ClipCard underCard;
        private View underCol;
        private StatusBarView underBar;

        void takeCard(Bitmap app, RectF from, RectF to, float crop) {
            Context c = getContext();
            if (under == null) {
                under = new FrameLayout(c);
                int pct = 0;
                try {
                    pct = Math.max(0, Math.min(90, Integer.parseInt(prefs.sp.getString(Prefs.WALL_DIM, "0"))));
                } catch (Exception ignored) {
                }
                SwipeUp.WallPic wp = new SwipeUp.WallPic(c, Math.round(pct * 2.55f));
                wp.bmp = wall;
                under.addView(wp, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                if (wall == null) loadWall(c, () -> {
                    wp.bmp = wall;
                    wp.invalidate();
                });
                underCard = new ClipCard(c);
                under.addView(underCard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                addView(under, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                underBar = new StatusBarView(c) {
                    @Override
                    public boolean dispatchTouchEvent(MotionEvent e) {
                        return false;
                    }
                };
                underBar.peekPercent = true;
                underBar.groundLeft = 0f;
                addView(underBar, new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(statusBarH, Ui.dp(26))));
                underBar.start();
            }
            underBar.setVisibility(VISIBLE);
            underBar.peekPercent = true;
            underBar.invalidate();
            status.groundOnly = true;
            status.invalidate();
            if (underCol != null) under.removeView(underCol);
            boolean top = "top".equals(prefs.sp.getString("lock_column", "center"));
            underCol = com.bb10.launcher.ui.NotifyColumn.build(c, com.bb10.launcher.ui.NotifyColumn.entries(), top,
                    statusBarH + Math.round(0.1f * Ui.S()));
            under.addView(underCol, under.indexOfChild(underCard), new LayoutParams(Math.round(0.19f * Ui.S()), LayoutParams.MATCH_PARENT));
            underCard.bmp = app;
            underCard.cropTop = crop;
            underCard.set(from);
            under.setVisibility(VISIBLE);
            under.setTranslationX(Math.max(0f, Math.min(W, lastDx)));
            dim.setAlpha(0f);
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(180);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            a.addUpdateListener(va -> underCard.set(lerp(from, to, (float) va.getAnimatedValue())));
            a.start();
        }

        private View band;

        void showBand() {
            if (band != null) return;
            int top = Math.max(statusBarH, Ui.dp(26));
            band = SwipeUp.makeBand(getContext(), GestureService.this, top);
            int hgt = top + Math.round(0.062f * Ui.S());
            int at = underBar != null && underBar.getParent() == this ? indexOfChild(underBar) : getChildCount();
            addView(band, at, new LayoutParams(LayoutParams.MATCH_PARENT, hgt));
        }

        void hideBand() {
            View b = band;
            if (b == null) return;
            band = null;
            b.animate().translationY(-b.getHeight()).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> removeView(b)).start();
        }

        private void fullyIn() {
            hideBand();
            if (underBar != null) underBar.popPercent(false);
        }

        private void edgeAt(float edge) {
            if (underBar != null && underBar.groundLeft != edge) {
                underBar.groundLeft = edge;
                underBar.invalidate();
            }
            panel.setTranslationX(-W + edge);
            if (under != null && under.getVisibility() == VISIBLE) under.setTranslationX(edge);
            if (reveal) appCard.setTranslationX(edge);
        }

        void setProgress(float dx) {
            lastDx = dx;
            if (open || closing || waitingForShot) return;
            if (reveal) {
                edgeAt(Math.max(0, Math.min(W, dx)));
            } else {
                float f = Math.min(1f, Math.max(0f, dx) / W);
                edgeAt(W * f);
                if (under == null || under.getVisibility() != VISIBLE) dim.setAlpha(overCard ? 0.5f * f : f);
            }
        }

        void openFully() {
            if (closing) return;
            open = true;
            if (waitingForShot) return;
            if (reveal || (under != null && under.getVisibility() == VISIBLE)) {
                stripTo(W, 170, () -> {
                    if (reveal) appCard.setVisibility(INVISIBLE);
                    fullyIn();
                });
            } else if (follow) {
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(commitX - W, 0f);
                va.setDuration(FOLLOW_MS);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
                va.addUpdateListener(a -> panel.setTranslationX((float) a.getAnimatedValue()));
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator a) {
                        dim.setAlpha(1f);
                    }
                });
                va.start();
                if (commitT > 0) va.setCurrentPlayTime(Math.max(0, Math.min(FOLLOW_MS, android.os.SystemClock.uptimeMillis() - commitT)));
            } else {
                panel.animate().translationX(0).setDuration(160).start();
                dim.animate().alpha(1f).setDuration(160).start();
            }
            lp.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            try {
                wm.updateViewLayout(this, lp);
            } catch (Exception ignored) {
            }
            h.postDelayed(() -> {
                if (!closing) hub.refresh();
            }, 220);
            requestFocus();
        }

        boolean reusable() {
            return !open && !closing && !swiping && (under == null || under.getVisibility() != VISIBLE) && edgeNow() <= 0.5f;
        }

        void resetForReuse() {
            overCard = false;
            follow = false;
            reveal = false;
            waitingForShot = false;
            lastDx = 0;
            cardBelow = null;
            panel.animate().cancel();
            panel.setTranslationX(-W);
            panel.setAlpha(0f);
            dim.setAlpha(0f);
            appCard.setVisibility(GONE);
            if (under != null) under.setVisibility(GONE);
            if (underBar != null) underBar.setVisibility(GONE);
            if (band != null) {
                removeView(band);
                band = null;
            }
            status.groundOnly = false;
            status.invalidate();
        }

        private float edgeNow() {
            return panel.getTranslationX() + W;
        }

        float releaseV;

        private void stripTo(float to, long ms, Runnable end) {
            panel.animate().cancel();
            float from = edgeNow(), v = Math.abs(releaseV), dist = Math.abs(to - from);
            boolean along = releaseV != 0 && (to > from) == (releaseV > 0);
            if (along && v > Ui.dp(150) && dist > 1f) ms = Math.max(140, Math.min(380, Math.round(3f * dist / v * 1000f)));
            releaseV = 0;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
            a.setDuration(ms);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            a.addUpdateListener(va -> edgeAt((float) va.getAnimatedValue()));
            if (end != null) a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    end.run();
                }
            });
            a.start();
        }

        void dismiss() {
            if (closing) return;
            closing = true;
            hideBand();
            if (reveal) {
                appCard.setVisibility(VISIBLE);
                stripTo(0, 170, this::dismissNow);
            } else if (under != null && under.getVisibility() == VISIBLE) {
                Below b = cardBelow;
                cardBelow = null;
                stripTo(0, 180, () -> {
                    if (b != null) b.cancel();
                    h.postDelayed(this::dismissNow, 50);
                });
            } else {
                dim.animate().alpha(0f).setDuration(160).start();
                if (cardBelow != null) {
                    cardBelow.cancel();
                    cardBelow = null;
                }
                panel.animate().translationX(-W).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .setUpdateListener(null).withEndAction(this::dismissNow).start();
            }
        }

        void leave() {
            if (closing) return;
            closing = true;
            animate().alpha(0f).setDuration(140).withEndAction(this::dismissNow).start();
        }

        void dismissNow() {
            closing = true;
            if (hubOverlay == this) hubOverlay = null;
            if (spare == this) spare = null;
            spareLater();
            if (cardBelow != null) {
                cardBelow.removeNow();
                cardBelow = null;
            }
            try {
                wm.removeView(this);
            } catch (Exception ignored) {
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (!open || menus.getChildCount() > 0) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) {
                MotionEvent copy = MotionEvent.obtain(e);
                copy.setLocation(e.getRawX(), e.getRawY());
                vt.addMovement(copy);
                copy.recycle();
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = false;
                    menuDrag = false;
                    menuFrom = hub.accountsPulled();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        if (dx > 0 || menuFrom > 0f) {
                            menuDrag = true;
                            downX = e.getRawX();
                            return true;
                        }
                        swiping = true;
                        return true;
                    }
                    break;
                }
            }
            return false;
        }

        private boolean menuDrag;
        private float menuFrom;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (menuDrag) {
                if (vt == null) vt = VelocityTracker.obtain();
                MotionEvent copy = MotionEvent.obtain(e);
                copy.setLocation(e.getRawX(), e.getRawY());
                vt.addMovement(copy);
                copy.recycle();
                float px = Math.max(0f, menuFrom + e.getRawX() - downX);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        hub.dragAccounts(px);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        vt.computeCurrentVelocity(1000);
                        hub.releaseAccounts(px, vt.getXVelocity());
                        vt.recycle();
                        vt = null;
                        menuDrag = false;
                        break;
                }
                return true;
            }
            if (!swiping) return super.onTouchEvent(e);
            if (vt == null) vt = VelocityTracker.obtain();
            MotionEvent copy = MotionEvent.obtain(e);
            copy.setLocation(e.getRawX(), e.getRawY());
            vt.addMovement(copy);
            copy.recycle();
            float dx = Math.min(0, e.getRawX() - downX);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (reveal) {
                        appCard.setVisibility(VISIBLE);
                        edgeAt(W + dx);
                    } else if (under != null && under.getVisibility() == VISIBLE) {
                        edgeAt(W + dx);
                    } else {
                        panel.setTranslationX(dx);
                        dim.setAlpha(1f + dx / W);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    vt.computeCurrentVelocity(1000);
                    releaseV = vt.getXVelocity();
                    if (dx < -W * 0.25f || vt.getXVelocity() < -Ui.dp(700)) dismiss();
                    else if (reveal) {
                        stripTo(W, 150, () -> appCard.setVisibility(INVISIBLE));
                    } else if (under != null && under.getVisibility() == VISIBLE) {
                        stripTo(W, 150, null);
                    } else {
                        panel.animate().translationX(0).setDuration(150).start();
                        dim.animate().alpha(1f).setDuration(150).start();
                    }
                    vt.recycle();
                    vt = null;
                    swiping = false;
                    break;
            }
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    int n = menus.getChildCount();
                    if (n > 0) {
                        View top = menus.getChildAt(n - 1);
                        if (top instanceof Closable) ((Closable) top).close();
                        else menus.removeView(top);
                    } else if (!hub.onBack()) {
                        dismiss();
                    }
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) hub.showMenu();
                return true;
            }
            if (super.dispatchKeyEvent(e)) return true;
            return e.getAction() == KeyEvent.ACTION_DOWN && menus.getChildCount() == 0 && hub.onKey(code, e);
        }

        @Override
        public ViewGroup overlays() { return menus; }

        @Override
        public Drawable iconForPackage(String pkg) { return app.iconForPackage(pkg); }

        private void start(Intent i, boolean animate) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        animate ? null : ActivityOptions.makeCustomAnimation(GestureService.this, 0, 0).toBundle());
            } catch (Exception ignored) {
            }
        }

        @Override
        public void launchPackage(String pkg, View src) {
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) start(li, true);
            leave();
        }

        @Override
        public void openCompose() {
            Context c = getContext();
            List<ActionMenu.Item> m = new ArrayList<>();
            m.add(new ActionMenu.Item(Ui.glyph(c, R.drawable.ic_text, Ui.TEXT), "Text Message", () -> {
                start(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), true);
                leave();
            }));
            m.add(new ActionMenu.Item(Ui.glyph(c, R.drawable.ic_unread, Ui.TEXT), "Email", () -> {
                start(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), true);
                leave();
            }));
            ActionMenu.show(menus, "Compose", Ui.glyph(c, R.drawable.ic_compose, Ui.BLUE), m);
        }

        @Override
        public void openSettings() {
            start(new Intent(GestureService.this, SettingsActivity.class), true);
            leave();
        }

        @Override
        public void requestNotificationAccess() {
            start(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), true);
            leave();
        }

        @Override
        public void onHubItemOpened() {
            leave();
        }
    }
}
