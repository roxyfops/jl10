package com.bb10.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bb10.launcher.frames.FrameTracker;
import com.bb10.launcher.hub.HubListenerService;
import com.bb10.launcher.hub.HubStore;
import com.bb10.launcher.icons.IconPackManager;
import com.bb10.launcher.icons.IconProvider;
import com.bb10.launcher.model.AppInfo;
import com.bb10.launcher.model.AppRepository;
import com.bb10.launcher.model.GridItem;
import com.bb10.launcher.quickstep.RemoteAnims;
import com.bb10.launcher.settings.IconPickerActivity;
import com.bb10.launcher.settings.SettingsActivity;
import com.bb10.launcher.ui.ActionMenu;
import com.bb10.launcher.ui.ActiveFramesPage;
import com.bb10.launcher.ui.AppGridPage;
import com.bb10.launcher.ui.Closable;
import com.bb10.launcher.ui.Dialogs;
import com.bb10.launcher.ui.FolderOverlay;
import com.bb10.launcher.ui.HubPage;
import com.bb10.launcher.ui.IconView;
import com.bb10.launcher.ui.PageIndicator;
import com.bb10.launcher.ui.PagerView;
import com.bb10.launcher.ui.SearchOverlay;
import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LauncherActivity extends Activity implements PagerView.Listener, AppRepository.Listener,
        HubStore.Listener, FrameTracker.Listener, SharedPreferences.OnSharedPreferenceChangeListener, HubPage.Host, SearchOverlay.Host {
    public static final String ACTION_SHOW = "com.bb10.launcher.SHOW";
    public static final String EXTRA_PAGE = "page";
    public static final String EXTRA_RETURN = "return_pkg";
    private String returnPkg;
    private static final int REQ_IMAGE = 11, REQ_PICK = 12;

    private LauncherApp app;
    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());

    private FrameLayout root, dragLayer, overlayLayer;
    private LinearLayout content, bottomBar;
    private android.view.SurfaceView dockHost;
    private android.view.SurfaceControlViewHost dockScvh;
    private android.view.SurfaceControlViewHost.SurfacePackage dockPkg;
    private boolean dockOwned;

    private void publishLiveDock() {
        android.graphics.Rect at = dockHost != null ? rectOf(dockHost) : null;
        android.view.SurfaceControl home = dockHost != null ? dockHost.getSurfaceControl() : null;
        com.bb10.launcher.quickstep.LauncherChrome.liveDock = dockPkg != null && at != null && home != null
                ? new com.bb10.launcher.quickstep.LauncherChrome.LiveDock(dockPkg.getSurfaceControl(), at, home) : null;
    }

    private void embedDock(LinearLayout.LayoutParams lp) {
        bottomBar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        dockHost = new android.view.SurfaceView(this);
        dockHost.setZOrderOnTop(true);
        dockHost.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        if (android.os.Build.VERSION.SDK_INT >= 34) dockHost.setSurfaceLifecycle(android.view.SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        content.addView(dockHost, lp);
        dockHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> publishLiveDock());
        dockHost.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(android.view.SurfaceHolder holder) {
                android.util.Log.i("BB10Dock", "dock surface created " + dockHost.getWidth() + "x" + dockHost.getHeight());
                if (dockScvh == null) {
                    dockScvh = new android.view.SurfaceControlViewHost(LauncherActivity.this, getDisplay(), dockHost.getHostToken());
                    try {
                        org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(android.view.SurfaceControlViewHost.class, dockScvh, "setView",
                                bottomBar, dockAttrs(dockHost.getWidth(), dockHost.getHeight()));
                    } catch (Throwable t) {
                        android.util.Log.w("BB10Dock", "setView with attrs: " + t);
                        dockScvh.setView(bottomBar, dockHost.getWidth(), dockHost.getHeight());
                    }
                    dockUnderOverlays();
                    dockPkg = dockScvh.getSurfacePackage();
                    dockHost.setChildSurfacePackage(dockPkg);
                    try {
                        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
                        java.lang.reflect.Field f = android.view.SurfaceView.class.getDeclaredField("mSurfacePackage");
                        f.setAccessible(true);
                        f.set(dockHost, null);
                        dockOwned = true;
                    } catch (Throwable t) {
                        android.util.Log.w("BB10Dock", "dock stays the holder's: " + t);
                    }
                }
                publishLiveDock();
                if (dockOwned && !com.bb10.launcher.quickstep.LauncherChrome.dockBusy) {
                    android.view.SurfaceControl home = dockHost.getSurfaceControl();
                    android.view.SurfaceControl d = dockPkg.getSurfaceControl();
                    if (home != null && home.isValid() && d.isValid()) {
                        new android.view.SurfaceControl.Transaction().reparent(d, home).setPosition(d, 0, 0)
                                .setLayer(d, 1).setAlpha(d, 1f).setVisibility(d, true).apply();
                    }
                }
            }

            @Override
            public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
                dockUnderOverlays();
            }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                android.util.Log.i("BB10Dock", "dock surface destroyed");
            }
        });
    }

    private android.view.WindowManager.LayoutParams dockAttrs(int w, int h) {
        int flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (overlayLayer != null && overlayLayer.getChildCount() > 0) flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return new android.view.WindowManager.LayoutParams(w, h, android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                flags, android.graphics.PixelFormat.TRANSLUCENT);
    }

    private void dockUnderOverlays() {
        if (dockScvh == null || dockHost == null || dockHost.getWidth() == 0) return;
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(android.view.SurfaceControlViewHost.class, dockScvh, "relayout",
                    dockAttrs(dockHost.getWidth(), dockHost.getHeight()));
        } catch (Throwable t) {
            android.util.Log.w("BB10Dock", "relayout: " + t);
        }
    }

    private android.graphics.Rect dockRect() {
        return rectOf(dockHost != null ? dockHost : bottomBar);
    }
    private StatusBarView statusBar;
    private PagerView pager;
    private PageIndicator indicator;
    private HubPage hubPage;
    private ActiveFramesPage framesPage;
    private final List<AppGridPage> gridPages = new ArrayList<>();
    private int framesIndex = -1, gridStart = 1;
    private boolean editMode, needsRebuild, resumed;

    private boolean dragging;
    private int dragIndex = -1;
    private boolean dragIsApp;
    private ImageView dragView;
    private int flipDir;
    private final Runnable flipRun = this::doFlip;

    private int trackedKey = -1;
    private char trackedChar;
    private boolean longPressed;

    private String pendingIconKey;
    private int imeInset;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = LauncherApp.get();
        app.ensureInit();
        current = this;
        if (app.prefs.bool(Prefs.OWN_SPLASH, false)) app.bg.execute(com.bb10.launcher.quickstep.OwnSplash::warm);
        prefs = app.prefs;
        if (state != null) pendingIconKey = state.getString("pik");
        getWindow().setDecorFitsSystemWindows(false);
        buildUi();
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null && (now.onCheckIsTextEditor() || now.getClass().getName().contains("AndroidComposeView"))) imeTarget(true);
        });

        app.apps.ensureLoaded();
        app.apps.addListener(this);
        app.hub.addListener(this);
        app.frames.addListener(this);
        prefs.sp.registerOnSharedPreferenceChangeListener(this);
        RemoteAnims.registerClose(this);
        app.live.addListener(liveChanged);
        app.hub.addOngoingListener(liveChanged);

        rebuildPages();
        applyHomeLook();
        goDefaultPage(false);
        handleShowIntent(getIntent());
        firstLaunch();
    }

    private static final String DISCLAIMER = "/*\n"
            + " * Your warranty is now void. The launcher is under development and has some bugs. "
            + "We are not responsible for bricked devices, dead SD cards, thermonuclear war, or you "
            + "getting fired because the alarm app failed. Please do some research if you have any "
            + "concerns about features included in this launcher before installing it! YOU are "
            + "choosing to make these modifications, and if you point the finger at us for messing "
            + "up your device, we will laugh at you. By installing this launcher, you accept this "
            + "disclaimer.\n"
            + " */";

    private void firstLaunch() {
        if (prefs.sp.getBoolean("safe_mode_notice", false)) {
            new android.app.AlertDialog.Builder(this).setTitle("JL10 kept crashing")
                    .setMessage("To keep your phone usable, these are now off: the System UI replacements (status bar, "
                            + "Quick Settings, volume, lock screen), Instant Previews and gestures.\n\n"
                            + "Turn them back on one at a time in Settings. If it crashes again, please send the "
                            + "report from Settings → Diagnostics.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> {
                        prefs.sp.edit().remove("safe_mode_notice").apply();
                        firstLaunch();
                    }).show();
            return;
        }
        if (!prefs.sp.getBoolean("disclaimer_seen", false)) {
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText(DISCLAIMER);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextSize(11);
            int pad = Ui.dp(16);
            t.setPadding(pad, pad, pad, 0);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(t);
            new android.app.AlertDialog.Builder(this).setTitle("Before you start")
                    .setView(sv)
                    .setCancelable(false)
                    .setPositiveButton("I understand", (d, w) -> {
                        prefs.sp.edit().putBoolean("disclaimer_seen", true).apply();
                        firstLaunch();
                    }).show();
            return;
        }
        if (!prefs.sp.getBoolean("setup_opened", false)
                || prefs.sp.getBoolean(com.bb10.launcher.settings.SetupWizardActivity.PREF_AFTER_REBOOT, false)) {
            h.postDelayed(() -> com.bb10.launcher.settings.SetupWizardActivity.open(getApplicationContext()), 600);
        }
    }

    @Override
    protected void onDestroy() {
        if (wallColors != null) {
            try {
                android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallColors);
            } catch (Exception ignored) {
            }
            wallColors = null;
        }
        app.apps.removeListener(this);
        app.hub.removeListener(this);
        app.frames.removeListener(this);
        app.live.removeListener(liveChanged);
        app.hub.removeOngoingListener(liveChanged);
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this);
        statusBar.stop();
        RemoteAnims.unregister(this);
        if (unlockListener != null && android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(android.app.KeyguardManager.class).removeKeyguardLockedStateListener(unlockListener);
            } catch (Throwable ignored) {
            }
        }
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingIconKey != null) out.putString("pik", pendingIconKey);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        com.bb10.launcher.gesture.GestureService.refreshEdge();
        com.bb10.launcher.hub.HubListenerService.reconnect(this);
        if (framesDirty) {
            framesDirty = false;
            framesPage.refresh();
        }
        checkDayChanged();
        if (needsRebuild) {
            needsRebuild = false;
            rebuildPages();
        }
        applySystemBars();
        statusBar.start();
        h.removeCallbacks(afterReturn);
        h.postDelayed(afterReturn, 450);
        com.bb10.launcher.quickstep.LauncherChrome.refresher = chromeRefresh;
        com.bb10.launcher.quickstep.LauncherChrome.underNow = underNow;
        if (bottomBar.getVisibility() != View.VISIBLE) {
            h.removeCallbacks(dockBack);
            h.postDelayed(dockBack, 800);
        }
        if (!scrollWatch) {
            scrollWatch = true;
            root.getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (resumed) refreshChromeSoon(500);
            });
        }
        refreshChromeSoon(700);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        clearHubReturn();
        framesPage.clearOpening();
        if (openJob != null) openJob.settle();
        RemoteAnims.zoomStartAt = 0;
        hideZoom();
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        stoppedByScreenOff = pm != null && !pm.isInteractive();
        app.live.stop();
        root.removeCallbacks(idleGc);
        root.postDelayed(idleGc, 8000);
        if (framesDirty) {
            framesDirty = false;
            framesPage.refresh();
        }
        if (awaitingResult) {
            framesPage.scrollToTop();
            return;
        }
        closeAllOverlays();
        if (editMode) exitEditMode();
        hubPage.closeMail();
        hubPage.onBack();
        returnPkg = null;
        if (pager.getCurrentPage() != framesIndex) {
            pager.setCurrentPage(framesIndex, false);
            updateBottomBar();
        }
        framesPage.scrollToTop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        com.bb10.launcher.gesture.GestureService.refreshEdge();
        startedAt = android.os.SystemClock.uptimeMillis();
        if (lockJob != null && !lockJob.started) {
            OpenJob lj = lockJob;
            root.postOnAnimation(() -> startLockOpenWhenUnlocked(lj, android.os.SystemClock.uptimeMillis()));
        }
        if (!stoppedByScreenOff) framesPage.restartLiveReveal();
        stoppedByScreenOff = false;
        root.removeCallbacks(idleGc);
        app.live.start();
        h.removeCallbacks(refreshTasks);
        h.postDelayed(refreshTasks, 450);
        h.postDelayed(refreshTasks, 1300);
    }

    private final Runnable refreshTasks = () -> app.frames.refreshFromTasks();

    private final Runnable afterReturn = () -> {
        app.frames.refreshFromUsage();
        hubPage.refresh();
    };

    private final Runnable liveChanged = () -> {
        if (framesPage != null) framesPage.invalidateFrames();
    };

    private void hideDockForApp() {
        if (!RemoteAnims.available(this)) return;
        com.bb10.launcher.quickstep.LauncherChrome.endWithDock = endWithDock;
        com.bb10.launcher.quickstep.LauncherChrome.dockHidden = true;
        bottomBar.setVisibility(View.INVISIBLE);
    }

    private final Runnable showDock = () -> {
        h.removeCallbacks(this.dockBack);
        bottomBar.setVisibility(View.VISIBLE);
        com.bb10.launcher.quickstep.LauncherChrome.dockHidden = false;
    };

    private final java.util.function.BiConsumer<android.view.SurfaceControl.Transaction, Runnable> endWithDock = (t, after) -> {
        showDock.run();
        try {
            getWindow().getRootSurfaceControl().applyTransactionOnDraw(t);
            root.getViewTreeObserver().registerFrameCommitCallback(after);
            root.invalidate();
        } catch (Throwable e) {
            t.apply();
            after.run();
        }
    };

    private final Runnable dockBack = () -> {
        if (resumed) showDock.run();
    };

    private boolean visible;

    private boolean awaitingResult;

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        awaitingResult = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onPause() {
        resumed = false;
        com.bb10.launcher.gesture.GestureService.refreshEdge();
        statusBar.stop();
        if (dragging) endDrag(false);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
        if (root != null) root.requestApplyInsets();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_SHOW.equals(intent.getAction())) {
            handleShowIntent(intent);
            return;
        }
        if (ACTION_OPEN.equals(intent.getAction())) {
            handleOpen(intent);
            return;
        }
        boolean opening = openJob != null && openJob.started && android.os.SystemClock.uptimeMillis() - openJob.startedAt < 3000;
        if (opening) {
            openJob.slid = true;
            RemoteAnims.onOpenAnimStart = null;
            RemoteAnims.zoomStartAt = 0;
            hideZoom();
            framesPage.clearOpening();
        }
        returnPkg = null;
        framesPage.scrollToTop();
        boolean animate = hasWindowFocus() && !opening;
        closeAllOverlays();
        if (editMode) exitEditMode();
        goDefaultPage(animate);
    }

    public static final String ACTION_OPEN = "com.bb10.launcher.OPEN_THROUGH_FRAME";
    public static final String EXTRA_PKG = "pkg", EXTRA_ITEM = "hub_item", EXTRA_PI = "open_pi", EXTRA_KEY = "notif_key";

    public static final String OPEN_TOKEN = java.util.UUID.randomUUID().toString();
    public static final String EXTRA_TOKEN = "open_token";

    public static void openThroughFrame(android.content.Context c, String pkg, String hubItemId) {
        openThroughFrame(c, pkg, hubItemId, null);
    }

    public static void openThroughFrame(android.content.Context c, String pkg, String hubItemId, android.app.PendingIntent target) {
        LauncherActivity la = current;
        LauncherApp app = LauncherApp.get();
        if (target != null && pkg != null && (la == null || !la.visible) && app != null && app.frames != null
                && pkg.equals(app.frames.lastForeground())) {
            try {
                Bundle b = null;
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    b = o.toBundle();
                }
                target.send(c, 0, null, null, null, null, b);
                android.util.Log.i("BB10Open", "in " + pkg + " already: opened in place");
                com.bb10.launcher.hub.HubItem it = hubItemId == null || app.hub == null ? null : app.hub.byId(hubItemId);
                if (it != null) app.hub.setRead(it, true);
                return;
            } catch (Exception ignored) {
            }
        }
        Intent open = new Intent(ACTION_OPEN).setClassName("com.bb10.launcher", LauncherActivity.class.getName())
                .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_ITEM, hubItemId).putExtra(EXTRA_PI, target)
                .putExtra(EXTRA_TOKEN, OPEN_TOKEN);
        if (la != null && la.resumed && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            la.handleOpen(open);
            return;
        }
        try {
            c.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private Runnable pendingOpen;

    private static volatile LauncherActivity current;

    private long startedAt;

    private void handleOpen(Intent i) {
        clearHubReturn();
        if (!OPEN_TOKEN.equals(i.getStringExtra(EXTRA_TOKEN))) {
            i.removeExtra(EXTRA_PI);
            i.removeExtra(EXTRA_KEY);
            i.removeExtra(EXTRA_ITEM);
            android.util.Log.i("BB10Open", "open request without JL10's code: just the app");
        }
        i.removeExtra(EXTRA_TOKEN);
        String pkg = i.getStringExtra(EXTRA_PKG);
        String item = i.getStringExtra(EXTRA_ITEM);
        String key = i.getStringExtra(EXTRA_KEY);
        @SuppressWarnings("deprecation") android.app.PendingIntent target = i.getParcelableExtra(EXTRA_PI);
        i.removeExtra(EXTRA_PKG);
        i.removeExtra(EXTRA_PI);
        i.removeExtra(EXTRA_KEY);
        if (pkg == null) return;
        if (com.bb10.launcher.hub.HubStore.isMailPkg(pkg)) {
            i.removeExtra(EXTRA_ITEM);
            com.bb10.launcher.hub.HubItem it = item == null ? null : app.hub.byId(item);
            android.util.Log.i("BB10Open", "open an email" + (it == null ? ": gone, the Hub instead" : ""));
            closeAllOverlays();
            if (editMode) exitEditMode();
            if (it == null || !com.bb10.launcher.hub.HubActions.openMail(this, it)) pager.setCurrentPage(0, hasWindowFocus());
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (pkg.equals(lockStartedPkg) && now - lockStartedAt < 4000) {
            lockStartedPkg = null;
            android.util.Log.i("BB10Open", "open " + pkg + ": already opening (from the lock screen)");
            return;
        }
        boolean fromApp = !visible || now - startedAt < 150;
        boolean fromLock = i.hasExtra("over_app");
        if (fromLock) fromApp = i.getBooleanExtra("over_app", fromApp);
        boolean unlockPlaying = i.getBooleanExtra("unlock_playing", false);
        boolean fromBouncer = i.getBooleanExtra("bouncer", false);
        i.removeExtra("over_app");
        i.removeExtra("unlock_playing");
        i.removeExtra("bouncer");
        android.util.Log.i("BB10Open", "open " + pkg + " item=" + item + " key=" + (key != null) + " fromApp=" + fromApp + " page=" + pager.getCurrentPage());
        closeAllOverlays();
        if (editMode) exitEditMode();
        returnPkg = null;
        app.frames.bringFirst(pkg);
        framesPage.refresh();
        boolean fresh = target != null || item != null || key != null;
        if (fresh) framesPage.showOpening(pkg);
        framesPage.scrollToTop();
        if (pendingOpen != null) h.removeCallbacks(pendingOpen);
        RemoteAnims.onCloseBegun = null;
        hideZoom();
        if (openJob != null && !openJob.started) openJob.discard();
        OpenJob job;
        if (lockJob != null && lockJob.pkg.equals(pkg) && !lockJob.started
                && android.os.SystemClock.uptimeMillis() - lockJobAt < 60_000) {
            job = lockJob;
            job.adopt(item, target, key);
        } else {
            if (lockJob != null && !lockJob.started) lockJob.discard();
            job = new OpenJob(pkg, item, target, key, fresh);
        }
        lockJob = null;
        openJob = job;
        if (fromLock && !fromApp) {
            job.zoomDelay = unlockPlaying ? 0 : LOCK_FADE_MS;
            job.fromLock = true;
            job.unlockPlaying = unlockPlaying;
            job.zoomAfterUnlock = fromBouncer;
        }
        job.attachIfReady();
        RemoteAnims.zoomStartAt = fresh ? RemoteAnims.ZOOM_PENDING : 0;
        RemoteAnims.zoomFromDx = 0;
        if (!fromApp) {
            job.prepare(true);
            RemoteAnims.onOpenAnimStart = null;
            if (pager.getCurrentPage() == framesIndex || !fresh) {
                if (pager.getCurrentPage() != framesIndex) {
                    pager.setCurrentPage(framesIndex, true);
                    updateBottomBar();
                }
                waitForFrames(job, android.os.SystemClock.uptimeMillis());
                return;
            }
            job.start();
            Runnable slide = () -> {
                if (openJob != job || job.slid) return;
                job.slid = true;
                pager.setCurrentPage(framesIndex, true);
                updateBottomBar();
                waitForFrames(job, android.os.SystemClock.uptimeMillis());
            };
            RemoteAnims.onOpenAnimStart = slide;
            h.postDelayed(slide, 900);
            return;
        }
        if (pager.getCurrentPage() != framesIndex) {
            pager.setCurrentPage(framesIndex, false);
            updateBottomBar();
        }
        job.prepare();
        RemoteAnims.onCloseBegun = () -> {
            if (openJob != job || job.started) return;
            job.queued = true;
            job.start();
        };
        pendingOpen = () -> {
            if (openJob != job || job.started) return;
            RemoteAnims.onCloseBegun = null;
            if (RemoteAnims.zoomStartAt == RemoteAnims.ZOOM_PENDING) RemoteAnims.zoomStartAt = android.os.SystemClock.uptimeMillis();
            job.prepare();
            job.start();
        };
        h.postDelayed(pendingOpen, 700);
    }

    private OpenJob openJob;

    private OpenJob lockJob;
    private static final long LOCK_FADE_MS = 220;
    private long lockJobAt;

    public static void prepareOpenFromLockScreen(String pkg, android.app.PendingIntent target, String key, String item) {
        LauncherActivity la = current;
        if (la != null) la.h.post(() -> la.prepareLockOpen(pkg, target, key, item));
    }

    private String lockStartedPkg;
    private long lockStartedAt;

    public static void undoOpenFromLockScreen(String pkg) {
        LauncherActivity la = current;
        if (la != null) la.h.post(() -> {
            if (la.lockJob == null || !la.lockJob.pkg.equals(pkg) || la.lockJob.started) return;
            la.lockJob.discard();
            la.lockJob = null;
            la.framesPage.clearOpening();
        });
    }

    private void prepareLockOpen(String pkg, android.app.PendingIntent target, String key, String item) {
        if (pkg == null) return;
        if (openJob != null && openJob.pkg.equals(pkg) && openJob.started
                && android.os.SystemClock.uptimeMillis() - openJob.startedAt < 4000) return;
        if (pkg.equals(lockStartedPkg) && android.os.SystemClock.uptimeMillis() - lockStartedAt < 4000) return;
        if (lockJob != null && !lockJob.started) lockJob.discard();
        lockJob = null;
        framesPage.refresh();
        framesPage.showOpening(pkg);
        framesPage.scrollToTop();
        if (pager.getCurrentPage() != framesIndex) {
            pager.setCurrentPage(framesIndex, false);
            updateBottomBar();
        }
        OpenJob j = new OpenJob(pkg, item, target, key, true);
        j.deferAttach = true;
        j.prepare(false);
        lockJob = j;
        lockJobAt = android.os.SystemClock.uptimeMillis();
        watchUnlock();
    }

    private android.app.KeyguardManager.KeyguardLockedStateListener unlockListener;

    private void watchUnlock() {
        if (unlockListener != null || android.os.Build.VERSION.SDK_INT < 33) return;
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km == null) return;
        unlockListener = locked -> {
            if (locked) return;
            OpenJob lj = lockJob;
            android.util.Log.i("BB10Open", "phone unlocked; lock-screen open waiting: " + (lj != null ? lj.pkg : "none") + ", launcher " + (visible ? "on screen" : "hidden"));
            if (lj != null && !lj.started && visible) startLockOpenWhenUnlocked(lj, android.os.SystemClock.uptimeMillis());
        };
        try {
            km.addKeyguardLockedStateListener(getMainExecutor(), unlockListener);
        } catch (Throwable t) {
            unlockListener = null;
        }
    }

    private void startLockOpenWhenUnlocked(OpenJob job, long since) {
        if (lockJob != job || job.started || !visible) {
            android.util.Log.i("BB10Open", "unlock watch ends: " + (lockJob != job ? "replaced" : job.started ? "started" : "hidden"));
            return;
        }
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            if (android.os.SystemClock.uptimeMillis() - since < 3000) root.postOnAnimation(() -> startLockOpenWhenUnlocked(job, since));
            return;
        }
        android.util.Log.i("BB10Open", "unlocked: starting " + job.pkg + " (made ready on the lock screen)");
        lockJob = null;
        if (openJob != null && openJob != job && !openJob.started) openJob.discard();
        openJob = job;
        lockStartedPkg = job.pkg;
        lockStartedAt = android.os.SystemClock.uptimeMillis();
        job.adopt(job.itemId, job.target, job.key);
        job.zoomDelay = LOCK_FADE_MS;
        job.fromLock = true;
        job.attachIfReady();
        RemoteAnims.zoomStartAt = RemoteAnims.ZOOM_PENDING;
        RemoteAnims.zoomFromDx = 0;
        waitForFrames(job, android.os.SystemClock.uptimeMillis());
    }

    private boolean keyguardLocked() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private void waitForFrames(OpenJob job, long since) {
        if (openJob != job || (job.started && RemoteAnims.zoomStartAt != RemoteAnims.ZOOM_PENDING)) return;
        int left = pager.getWidth() > 0 ? framesIndex * pager.getWidth() - pager.getScrollX() : Integer.MAX_VALUE;
        boolean inPlace = Math.abs(left) <= (job.fresh ? Ui.dp(12) : Math.max(2, Ui.dp(1)));
        boolean timeUp = android.os.SystemClock.uptimeMillis() - since > 1200;
        if (job.fresh && !job.zoomReady) job.attachIfReady();
        boolean ready = job.lookDone && (job.zoomReady || !job.fresh || job.lookSc == null);
        if (job.fromLock && !job.unlockPlaying && ready && keyguardLocked()) ready = false;
        if ((inPlace && ready) || timeUp) {
            boolean holdZoom = job.zoomAfterUnlock && RemoteAnims.bouncerGoneAt < since - 300
                    && android.os.SystemClock.uptimeMillis() - since < 600;
            if (!holdZoom && RemoteAnims.zoomStartAt == RemoteAnims.ZOOM_PENDING) {
                if (job.fromLock) android.util.Log.i("BB10Open", "lock open: zoom starts" + (job.zoomAfterUnlock ? " (password/PIN screen gone)" : ""));
                RemoteAnims.zoomFromDx = timeUp ? 0 : left;
                long waited = android.os.SystemClock.uptimeMillis() - since;
                RemoteAnims.zoomStartAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis() + Math.max(0, job.zoomDelay - waited);
            }
            if (!job.started) job.start();
            if (!holdZoom) return;
        }
        root.postOnAnimation(() -> waitForFrames(job, since));
    }

    private final class OpenJob {
        final String pkg;
        String itemId, key;
        android.app.PendingIntent target;
        final boolean fresh;
        boolean deferAttach;
        boolean slid;

        long zoomDelay;
        boolean fromLock;
        boolean unlockPlaying;

        boolean zoomAfterUnlock;

        void adopt(String itemId, android.app.PendingIntent target, String key) {
            this.itemId = itemId;
            this.target = target;
            this.key = key;
            deferAttach = false;
            if (key != null) app.hub.markReadByKey(key);
            it = itemId == null ? null : app.hub.byId(itemId);
            n = it == null ? null : app.hub.live(it);
            pi = target != null ? target : it != null ? app.hub.target(it) : null;
        }

        void attachIfReady() {
            if (!prepared || deferAttach || zoomReady || discarded || !fresh || !lookDone || lookSc == null) return;
            zoomReady = showZoom(lookSc, lookSize, lookAt, false);
        }
        boolean prepared, started, queued;
        View fv;
        android.graphics.Rect r;
        android.graphics.RectF lookAt;
        android.app.PendingIntent pi;
        Intent li;
        com.bb10.launcher.hub.HubItem it;
        android.app.Notification n;
        Bundle opts;
        volatile boolean lookDone;

        OpenJob(String pkg, String itemId, android.app.PendingIntent target, String key, boolean fresh) {
            this.pkg = pkg;
            this.itemId = itemId;
            this.target = target;
            this.key = key;
            this.fresh = fresh;
        }

        void prepare() {
            prepare(false);
        }

        void prepare(boolean renderNow) {
            if (prepared) return;
            prepared = true;
            layoutNow();
            fv = framesPage.frameViewFor(pkg);
            r = rectOf(fv);
            if (r != null && pager.getWidth() > 0) r.offset(pager.getScrollX() - framesIndex * pager.getWidth(), 0);
            if (key != null) app.hub.markReadByKey(key);
            it = itemId == null ? null : app.hub.byId(itemId);
            n = it == null ? null : app.hub.live(it);
            pi = target != null ? target : it != null ? app.hub.target(it) : null;
            li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                li.setPackage(null);
            }
            android.util.Log.i("BB10Open", "prepare " + pkg + " rect=" + r + " item=" + (it != null) + " notif=" + (n != null) + " target=" + (pi != null));
            android.graphics.Picture pic = null;
            if (r != null && RemoteAnims.available(LauncherActivity.this)) {
                frameW = fv.getWidth();
                frameH = fv.getHeight();
                iconOnly = com.bb10.launcher.ui.ActiveFramesPage.showsIconOnly(fv);
                boolean blend = com.bb10.launcher.ui.ActiveFramesPage.showsLiveInfo(fv) || iconOnly;
                if (!blend && ownSplash(pkg) && startsAfresh(pkg)) blend = true;
                ownLook = blend && ownSplash(pkg);
                pic = blend ? recordLook(fv) : null;
                opts = com.bb10.launcher.quickstep.ShellTransitions.canLaunch(LauncherActivity.this)
                        ? com.bb10.launcher.quickstep.ShellTransitions.launchOptions(LauncherActivity.this, r)
                        : RemoteAnims.launchOptions(LauncherActivity.this, r);
                int bar = Ui.dp(com.bb10.launcher.ui.ActiveFramesPage.BAR_DP);
                lookAt = new android.graphics.RectF(r.left, r.top, r.right, r.bottom - bar);
            }
            android.graphics.Picture p = pic;
            if (p == null) {
                lookDone = true;
                return;
            }
            if (renderNow) {
                makeLook(p);
                if (fresh && lookSc != null && !discarded) zoomReady = showZoom(lookSc, lookSize, lookAt, false);
                return;
            }
            openHandler().post(() -> {
                makeLook(p);
                if (fresh && lookSc != null) h.post(() -> {
                    if (openJob != this || discarded || deferAttach) return;
                    attachIfReady();
                });
            });
        }

        private void makeLook(android.graphics.Picture p) {
            android.graphics.Bitmap lk = renderLook(p);
            android.hardware.HardwareBuffer buf = lk != null ? lk.getHardwareBuffer() : null;
            if (buf != null) {
                lookSize = new android.graphics.Rect(0, 0, buf.getWidth(), buf.getHeight());
                android.view.SurfaceControl sc = ownLook ? ownSplashLook(buf, frameW, frameH, iconOnly, pkg) : null;
                lookSc = sc != null ? sc : RemoteAnims.liveInfoSurface(buf);
            }
            lookDone = true;
        }

        boolean ownLook, iconOnly;
        int frameW, frameH;
        volatile android.view.SurfaceControl lookSc;
        volatile android.graphics.Rect lookSize;
        boolean zoomReady, discarded;

        void discard() {
            discarded = true;
            openHandler().post(() -> com.bb10.launcher.quickstep.SurfaceZoom.discard(lookSc));
        }

        long startedAt;

        void start() {
            if (started) return;
            started = true;
            startedAt = android.os.SystemClock.uptimeMillis();
            h.removeCallbacks(pendingOpen);
            RemoteAnims.onCloseBegun = null;
            RemoteAnims.openQueued = queued;
            if (queued) h.postDelayed(() -> RemoteAnims.openQueued = false, 1500);
            android.util.Log.i("BB10Open", "start " + pkg + (queued ? " (behind the close)" : ""));
            RemoteAnims.openPkg = pkg;
            RemoteAnims.openRequestedAt = android.os.SystemClock.uptimeMillis();
            RemoteAnims.openTaskId = -1;
            RemoteAnims.openSettled = !fresh;
            if (pi == null && li == null) {
                RemoteAnims.zoomStartAt = 0;
                hideZoom();
                framesPage.clearOpening();
                return;
            }
            h.postDelayed(() -> {
                if (!resumed || openJob != this) return;
                framesPage.clearOpening();
                clearFrameLaunch();
            }, 2500);
            android.content.Context ctx = getApplicationContext();
            openHandler().post(() -> {
                if (RemoteAnims.pendingLiveInfo != lookSc) RemoteAnims.dropLiveInfo();
                RemoteAnims.pendingFrameLaunch = opts != null;
                boolean live = opts != null && lookSc != null;
                RemoteAnims.pendingLiveSize = live ? lookSize : null;
                RemoteAnims.pendingLiveInfo = live ? lookSc : null;
                RemoteAnims.pendingFrameLive = live;
                if (fresh && !live) RemoteAnims.zoomStartAt = 0;
                Bundle b = opts;
                if (b == null && android.os.Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    b = o.toBundle();
                }
                boolean ok = false;
                if (pi != null) {
                    try {
                        pi.send(ctx, 0, null, null, null, null, b);
                        ok = true;
                    } catch (Exception e) {
                        android.util.Log.i("BB10Open", "target gone (" + e + "): the app instead");
                    }
                }
                if (!ok && li != null) {
                    try {
                        ctx.startActivity(li, b);
                        ok = true;
                    } catch (Exception ignored) {
                    }
                }
                boolean sent = ok;
                h.post(() -> {
                    if (sent) {
                        opened = true;
                        h.postDelayed(this::settle, 2500);
                    } else {
                        clearFrameLaunch();
                        framesPage.clearOpening();
                        hideZoom();
                        Toast.makeText(LauncherActivity.this, "Can't open app", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }

        private boolean settled, opened;

        void settle() {
            if (settled || !opened) return;
            settled = true;
            if (it != null && !it.read) app.hub.setRead(it, true);
            if (n != null && (n.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) com.bb10.launcher.hub.HubListenerService.cancel(it.key);
            android.app.Notification kn = app.hub.liveByKey(key);
            if (kn != null && (kn.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) com.bb10.launcher.hub.HubListenerService.cancel(key);
        }
    }

    private boolean showZoom(android.view.SurfaceControl look, android.graphics.Rect lookSize, android.graphics.RectF at, boolean startNow) {
        if (look == null || at == null || android.os.Build.VERSION.SDK_INT < 33) return false;
        android.view.AttachedSurfaceControl win = getWindow().getRootSurfaceControl();
        android.view.SurfaceControl.Transaction attach = win != null ? win.buildReparentTransaction(look) : null;
        android.view.SurfaceControl.Transaction reattach = win != null ? win.buildReparentTransaction(look) : null;
        View d = getWindow().getDecorView();
        return com.bb10.launcher.quickstep.SurfaceZoom.start(attach, reattach, look, lookSize, at, d.getWidth(), d.getHeight(), startNow);
    }

    private void hideZoom() {
        com.bb10.launcher.quickstep.SurfaceZoom.cancel();
        if (openJob != null) com.bb10.launcher.quickstep.SurfaceZoom.discard(openJob.lookSc);
    }

    private void layoutNow() {
        View d = framesPage;
        if (d.getWidth() == 0 || d.getHeight() == 0) return;
        d.measure(View.MeasureSpec.makeMeasureSpec(d.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(d.getHeight(), View.MeasureSpec.EXACTLY));
        d.layout(d.getLeft(), d.getTop(), d.getRight(), d.getBottom());
    }

    private void handleShowIntent(Intent i) {
        if (i == null || !ACTION_SHOW.equals(i.getAction())) return;
        String page = i.getStringExtra(EXTRA_PAGE);
        closeAllOverlays();
        returnPkg = i.getStringExtra(EXTRA_RETURN);
        i.removeExtra(EXTRA_RETURN);
        if ("hub".equals(page)) pager.setCurrentPage(0, hasWindowFocus());
        else if ("search".equals(page)) openSearch("");
        else goDefaultPage(hasWindowFocus());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        content = new LinearLayout(this) {
            private boolean below;

            private boolean belowPages(MotionEvent e) {
                if (pager == null || pager.tallPage == null || editMode || e.getY() < pager.getBottom()) return false;
                View dock = dockHost != null ? dockHost : bottomBar;
                return dock != null && e.getX() < dock.getX();
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) below = belowPages(e);
                if (!below) return super.dispatchTouchEvent(e);
                MotionEvent m = MotionEvent.obtain(e);
                m.offsetLocation(getScrollX() - pager.getLeft(), getScrollY() - pager.getTop());
                boolean r = pager.dispatchTouchEvent(m);
                m.recycle();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) below = false;
                return r;
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);

        statusBar = new StatusBarView(this);
        statusBar.setOnTick(() -> {
            framesPage.updateDate();
            checkDayChanged();
        });
        content.addView(statusBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, StatusBarView.height()));

        pager = new PagerView(this);
        pager.setListener(this);
        content.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pager.setClipChildren(false);
        pager.setClipToPadding(false);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        statusBar.setOutlineProvider(null);
        statusBar.setTranslationZ(1f);

        bottomBar = new LinearLayout(this) {
            private float dx0, dy0;
            private boolean up, onDots;
            private float sx, sy;

            @Override
            public void setTranslationX(float x) {
                if (dockHost == null) {
                    super.setTranslationX(x);
                } else if (sx != x) {
                    sx = x;
                    dockHost.setTranslationX(x);
                    publishLiveDock();
                }
            }

            @Override
            public float getTranslationX() {
                return dockHost == null ? super.getTranslationX() : sx;
            }

            @Override
            public void setTranslationY(float y) {
                if (dockHost == null) {
                    super.setTranslationY(y);
                } else if (sy != y) {
                    sy = y;
                    dockHost.setTranslationY(y);
                    publishLiveDock();
                }
            }

            @Override
            public float getTranslationY() {
                return dockHost == null ? super.getTranslationY() : sy;
            }

            private boolean upNow(MotionEvent e) {
                int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                return !editMode && dy0 - e.getRawY() > slop * 2 && dy0 - e.getRawY() > Math.abs(e.getRawX() - dx0) * 1.5f;
            }

            private void begin(MotionEvent e) {
                up = true;
                homeSwipe(MotionEvent.ACTION_DOWN, dx0, dy0);
                homeSwipe(MotionEvent.ACTION_MOVE, e.getRawX(), e.getRawY());
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dx0 = e.getRawX();
                    dy0 = e.getRawY();
                    up = false;
                    onDots = indicator != null && e.getX() >= indicator.getLeft() && e.getX() < indicator.getRight();
                }
                if (onDots) return false;
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !up && upNow(e)) begin(e);
                return up;
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (!up) {
                    if (a == MotionEvent.ACTION_DOWN) {
                        dx0 = e.getRawX();
                        dy0 = e.getRawY();
                        return true;
                    }
                    if (a == MotionEvent.ACTION_MOVE && upNow(e)) begin(e);
                    if (!up) return true;
                    return true;
                }
                homeSwipe(a, e.getRawX(), e.getRawY());
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) up = false;
                return true;
            }
        };
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackground(barBg);
        bottomBar.setClipChildren(false);
        indicator = new PageIndicator(this, pager);
        indicator.scrubListener = new PageIndicator.ScrubListener() {
            @Override
            public void onScrub(boolean on) {
                LauncherActivity.this.onScrub(on);
            }

            @Override
            public void onPeekTouch(int action, float rawX, float rawY) {
                homeSwipe(action, rawX, rawY);
            }
        };
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(Ui.s(BOTTOM_H)));
        if ("1".equals(android.provider.Settings.Global.getString(getContentResolver(), "bb10_live_dock"))) {
            try {
                embedDock(dockLp);
            } catch (Throwable t) {
                android.util.Log.w("BB10Dock", "own surface failed, inline instead", t);
                if (dockHost != null) content.removeView(dockHost);
                dockHost = null;
                content.addView(bottomBar, dockLp);
            }
        } else {
            content.addView(bottomBar, dockLp);
        }

        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dragLayer = new FrameLayout(this);
        root.addView(dragLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayLayer = new FrameLayout(this);
        overlayLayer.setClipToPadding(false);
        overlayLayer.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                dockUnderOverlays();
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                dockUnderOverlays();
            }
        });
        root.addView(overlayLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dockHost != null) {
            final int[] at = new int[2], in = new int[2], me = new int[2];
            View bubble = new View(this) {
                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    if (indicator == null || dockHost == null) return;
                    dockHost.getLocationInWindow(at);
                    indicator.getLocationInWindow(in);
                    getLocationInWindow(me);
                    c.save();
                    c.translate(at[0] + in[0] - me[0], at[1] + in[1] - me[1]);
                    indicator.drawBubbleOut(c);
                    c.restore();
                }
            };
            bubble.setWillNotDraw(false);
            bubble.setClickable(false);
            bubble.setFocusable(false);
            bubble.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(bubble, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            indicator.bubbleOut = bubble;
        }

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            boolean bb = ownBar();
            int top = bb ? 0 : ins.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
            Insets nav = ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
            int imeH = ins.getInsets(WindowInsets.Type.ime()).bottom;
            if (imeH < Ui.dp(24)) imeH = 0;
            if (!ownKeyboard()) imeH = 0;
            int bottom = Math.max(bb ? 0 : nav.bottom, imeH);
            content.setPadding(0, top, 0, bottom);
            overlayLayer.setPadding(0, top, 0, 0);
            imeInset = imeH;
            for (int i = 0; i < overlayLayer.getChildCount(); i++) {
                View c = overlayLayer.getChildAt(i);
                if (c instanceof SearchOverlay) ((SearchOverlay) c).setImeInset(imeInset);
            }
            return WindowInsets.CONSUMED;
        });
        android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wlp.flags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        getWindow().setAttributes(wlp);

        hubPage = new HubPage(this, this, false);
        pager.tallPage = hubPage;
        pager.tallExtra = Math.round(Ui.s(BOTTOM_H));
        hubBar = hubPage.actionBar();
        hubBar.setVisibility(View.GONE);
        root.addView(hubBar, root.indexOfChild(dragLayer), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        hubCompose = hubPage.composeButton();
        int hcs = Math.round(Ui.s(0.135f));
        FrameLayout.LayoutParams hcl = new FrameLayout.LayoutParams(hcs, hcs, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hcl.bottomMargin = Math.round(Ui.s(BOTTOM_H) * 0.42f);
        hubCompose.setVisibility(View.GONE);
        root.addView(hubCompose, root.indexOfChild(dragLayer), hcl);
        framesPage = new ActiveFramesPage(this);
        framesPage.onHalfChanged = () -> {
            if (indicator != null) indicator.invalidate();
        };

        pager.setOnScrollChangeListener((v, sx, sy, osx, osy) -> hubSlide(sx));
    }

    private boolean ownBar() {
        return prefs.bool(Prefs.BB_STATUS, true) || prefs.bool(Prefs.HIDE_STATUS_APPS, false);
    }

    private void applySystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        statusBar.pullOpensNotifications = prefs.bool(Prefs.PULL_ANDROID, false);
        lightBars = null;
        if (pager != null && pager.getWidth() > 0) hubSlide(pager.getScrollX());
        else c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        boolean bb = ownBar();
        if (bb) {
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.systemBars());
            statusBar.setVisibility(View.VISIBLE);
        } else {
            c.show(WindowInsets.Type.systemBars());
            statusBar.setVisibility(View.GONE);
        }
        if (searchUp) c.hide(WindowInsets.Type.statusBars());
        if (appliedBb == null || appliedBb != bb) {
            appliedBb = bb;
            root.requestApplyInsets();
        }
    }

    private Boolean appliedBb;

    public FrameLayout overlays() { return overlayLayer; }

    private static final int PT_HUB = 0, PT_FRAMES = 1, PT_GRID = 2;

    private int pageType(int p) {
        if (p == 0) return PT_HUB;
        if (p == framesIndex) return PT_FRAMES;
        return PT_GRID;
    }

    private void rebuildPages() {
        int cur = pager.getCurrentPage();
        int keepType = pager.getChildCount() == 0 ? PT_GRID : pageType(cur);
        int keepGrid = Math.max(0, cur - gridStart);

        pager.removeAllViews();
        gridPages.clear();
        pager.addView(hubPage);
        framesIndex = 1;
        pager.addView(framesPage);
        framesPage.rebuild();
        gridStart = pager.getChildCount();

        int cols = prefs.cols(), rows = prefs.rows(), per = cols * rows;
        List<GridItem> items = app.apps.layout();
        int pages = Math.max(1, (items.size() + per - 1) / per);
        if (editMode) pages++;
        for (int p = 0; p < pages; p++) {
            AppGridPage g = new AppGridPage(this, p * per, cols, rows);
            g.setOnClickListener(v -> {
                if (editMode) exitEditMode();
            });
            g.setOnLongClickListener(v -> {
                if (!editMode) enterEditMode();
                return true;
            });
            for (int i = p * per; i < Math.min(items.size(), (p + 1) * per); i++) g.addView(makeIcon(items.get(i), i));
            gridPages.add(g);
            pager.addView(g);
        }
        indicator.setup(pages);

        int target;
        if (keepType == PT_HUB) target = 0;
        else if (keepType == PT_FRAMES) target = framesIndex;
        else target = gridStart + Math.min(keepGrid, pages - 1);
        pager.setCurrentPage(target, false);
        updateBottomBar();
    }

    private IconView makeIcon(GridItem g, int index) {
        IconView v = new IconView(this);
        boolean labels = prefs.bool(Prefs.SHOW_LABELS, true);
        if (g.type == GridItem.APP) {
            AppInfo a = app.apps.app(g.app);
            v.bindApp(g, index, a == null ? "" : a.label(), a == null ? null : app.icons.get(a), a == null ? 0 : app.hub.unread(a.pkg));
        } else {
            Drawable[] minis = new Drawable[4];
            int badge = 0, k = 0;
            for (String key : g.apps) {
                AppInfo a = app.apps.app(key);
                if (a == null) continue;
                if (k < 4) minis[k++] = app.icons.get(a);
                badge += app.hub.unread(a.pkg);
            }
            v.bindFolder(g, index, minis, badge);
        }
        v.setShowLabel(labels);
        v.setEdit(editMode);
        v.setDragStarter(this::startDrag);
        v.setOnClickListener(x -> onItemClick(v));
        v.setOnLongClickListener(x -> {
            if (!editMode) enterEditMode();
            v.armDrag();
            return true;
        });
        return v;
    }

    private void refreshBadges() {
        for (AppGridPage g : gridPages) {
            for (int i = 0; i < g.getChildCount(); i++) {
                IconView v = (IconView) g.getChildAt(i);
                if (v.item == null) continue;
                if (v.item.type == GridItem.APP) {
                    AppInfo a = app.apps.app(v.item.app);
                    v.setBadge(a == null ? 0 : app.hub.unread(a.pkg));
                } else {
                    int b = 0;
                    for (String key : v.item.apps) {
                        AppInfo a = app.apps.app(key);
                        if (a != null) b += app.hub.unread(a.pkg);
                    }
                    v.setBadge(b);
                }
            }
        }
    }

    static final float BOTTOM_H = 0.116f;
    static final float HUB_ICON = 0.094f, CAMERA_ICON = 0.082f;
    static final float CORNER_W = 0.17f;
    private BarButton hubButton;
    private View hubCompose, hubBar;

    private boolean stoppedByScreenOff;

    private Boolean lightBars;

    private boolean hubWasGone = true;

    private void hubSlide(int sx) {
        int w = Math.max(1, pager.getWidth());
        float f = Math.max(0f, Math.min(1f, 1f - sx / (float) w));
        boolean hubBright = !"dark".equals(prefs.sp.getString(Prefs.HUB_THEME, "bright"));
        int band = hubBright ? 0xEDEDED : 0x262626;
        statusBar.setTopShade(0x90);
        boolean bandDown = peekDateWrap != null && peekDateWrap.getParent() != null;
        if (statusBar.solidBg != 0 && !bandDown) {
            statusBar.solidBg = 0;
            statusBar.invalidate();
        }
        android.graphics.Bitmap wall = com.bb10.launcher.gesture.GestureService.homeWall();
        if (wall == null) com.bb10.launcher.gesture.GestureService.loadWall(this, () -> {
            if (pager != null && hubBar != null) hubSlide(pager.getScrollX());
        });
        boolean reveal = !editMode && !hubFromPeek;
        if (hubFromPeek) {
            pushHome(Math.max(0, Math.min(w, w - sx)));
            if (sx >= w) {
                h.removeCallbacks(returnCheck);
                h.post(returnCheck);
            }
        }
        pager.setUnderFirst(reveal);
        int edge = Math.max(0, Math.min(w, w - sx));
        if (reveal) {
            if (edge > 0) {
                revealBg.edge = edge;
                revealBg.band = 0xFF000000 | band;
                revealBg.wall = wall;
                revealBg.dim = homeDim;
                revealBg.topH = statusBar.getVisibility() == View.VISIBLE ? StatusBarView.height() : content.getPaddingTop();
                content.getLocationOnScreen(revealBg.loc);
                android.graphics.Rect real = getWindowManager().getMaximumWindowMetrics().getBounds();
                revealBg.screenW = real.width();
                revealBg.screenH = real.height();
                if (content.getBackground() != revealBg) content.setBackground(revealBg);
                else revealBg.invalidateSelf();
            } else if (content.getBackground() != null) {
                content.setBackground(null);
            }
        } else if (hubFromPeek) {
            if (content.getBackground() != null) content.setBackground(null);
        } else {
            content.setBackgroundColor(Color.argb(Math.round(f * 255), (band >> 16) & 0xFF, (band >> 8) & 0xFF, band & 0xFF));
        }
        boolean darkBar = f < 0.5f && Ui.homeDark;
        if ((statusBar.darkIntensity > 0.5f) != darkBar) {
            statusBar.darkIntensity = darkBar ? 1f : 0f;
            statusBar.invalidate();
        }
        if (lightBars == null || lightBars != darkBar) {
            lightBars = darkBar;
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) ic.setSystemBarsAppearance(darkBar ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        boolean hub = !editMode && sx < w;
        boolean hubGone = sx >= w;
        if (hubGone && !hubWasGone) hubPage.closeMail();
        hubWasGone = hubGone;
        int vis = hub ? View.VISIBLE : View.GONE;
        hubBar.setVisibility(vis);
        hubCompose.setVisibility(vis);
        if (reveal) {
            hubBar.setTranslationX(0);
            hubCompose.setTranslationX(0);
            if (hub) {
                hubBarClip.set(0, 0, edge, Math.max(1, hubBar.getHeight()));
                hubBar.setClipBounds(hubBarClip);
                hubComposeClip.set(0, 0, Math.max(0, edge - hubCompose.getLeft()), Math.max(1, hubCompose.getHeight()));
                hubCompose.setClipBounds(hubComposeClip);
            }
        } else {
            hubBar.setClipBounds(null);
            hubCompose.setClipBounds(null);
            hubBar.setTranslationX(-sx);
            hubCompose.setTranslationX(-sx);
        }
        bottomBar.setTranslationX(editMode ? 0 : Math.max(0, w - sx));
        if (scrubbing) {
            bottomBar.setTranslationX(0);
            hubBar.setVisibility(View.GONE);
            hubCompose.setVisibility(View.GONE);
        }
    }

    public int[] framesInsets() {
        float S = Ui.S();
        int left = Math.round(Ui.s(0.057f)), right = left;
        int top = statusBar.getVisibility() == View.VISIBLE ? StatusBarView.height() : content.getPaddingTop();
        int bottom = Math.round(Ui.s(BOTTOM_H) / 2 - 0.045f * S / 2);
        return new int[]{left, right, top, bottom};
    }

    private final android.graphics.Rect hubBarClip = new android.graphics.Rect(), hubComposeClip = new android.graphics.Rect();
    private final RevealBg revealBg = new RevealBg();
    private int homeDim;

    public static float wallpaperZoom() {
        return RevealBg.wallpaperScale();
    }

    private static final class RevealBg extends android.graphics.drawable.Drawable {
        int edge, band, dim;
        int topH;
        android.graphics.Bitmap wall;
        final int[] loc = new int[2];
        int screenW, screenH;
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private static float sWallScale;

        static float wallpaperScale() {
            if (sWallScale == 0) {
                float v = 1.10f;
                try {
                    android.content.res.Resources r = android.content.res.Resources.getSystem();
                    int id = r.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
                    if (id != 0) v = r.getFloat(id);
                } catch (Throwable ignored) {
                }
                sWallScale = v < 1f ? 1f : v;
                android.util.Log.d("JL10", "wallpaper scale " + sWallScale);
            }
            return sWallScale;
        }
        private final android.graphics.Paint fill = new android.graphics.Paint();
        private final android.graphics.Paint pic = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (edge > b.left) {
                fill.setColor(band);
                c.drawRect(b.left, b.top, edge, b.bottom, fill);
            }
            if (edge < b.right && wall != null) {
                c.save();
                c.clipRect(edge, b.top, b.right, b.bottom);
                float z = wallpaperScale();
                float ox = -(z - 1) * screenW / 2, oy = -(z - 1) * screenH / 2;
                dst.set(edge - loc[0] + ox, -loc[1] + oy, edge - loc[0] + ox + screenW * z, -loc[1] + oy + screenH * z);
                c.drawBitmap(wall, null, dst, pic);
                c.restore();
            }
            if (edge < b.right && dim > 0) {
                fill.setColor(dim << 24);
                c.drawRect(edge, b.top, b.right, b.bottom, fill);
            }
            if (topH > 0 && edge > b.left) {
                fill.setColor(0xFF000000);
                c.drawRect(b.left, b.top, edge, b.top + topH, fill);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private Boolean barBuiltForEdit;

    private void updateBottomBar() {
        if (hubBar != null) hubSlide(pager.getScrollX());
        if (barBuiltForEdit != null && barBuiltForEdit == editMode && hubButton != null) {
            boolean splat = app.hub.unread(null) > 0;
            if (hubButton.splat != splat) {
                hubButton.splat = splat;
                hubButton.invalidate();
            }
            indicator.invalidate();
            return;
        }
        barBuiltForEdit = editMode;
        bottomBar.removeAllViews();
        hubButton = null;

        {
            hubButton = addBarButton(R.drawable.ic_hub_bar, "BlackBerry Hub", v -> pager.setCurrentPage(0, true), HUB_ICON);
            hubButton.splat = app.hub.unread(null) > 0;
        }
        bottomBar.addView(indicator, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (editMode) {
            addBarButton(R.drawable.ic_done, "Done", v -> exitEditMode(), 0.072f);
        } else {
            addBarButton(R.drawable.ic_camera, "Camera", v -> startSafe(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), CAMERA_ICON);
        }
        indicator.invalidate();
    }

    private BarButton addBarButton(int icon, String label, View.OnClickListener l, float sizeFraction) {
        BarButton b = new BarButton(Ui.glyph(this, icon, Ui.homeFg()), Ui.glyph(this, icon, Ui.homeOutline()), Ui.s(sizeFraction));
        b.lift = icon == R.drawable.ic_hub_bar ? Ui.s(0.004f) : 0;
        b.setContentDescription(label);
        b.setOnClickListener(l);
        bottomBar.addView(b, new LinearLayout.LayoutParams(Math.round(Ui.s(CORNER_W)), ViewGroup.LayoutParams.MATCH_PARENT));
        return b;
    }

    private class BarButton extends View {
        final Drawable glyph, shadow;
        final float size;
        boolean splat;
        float lift;
        private final android.graphics.Paint sp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        BarButton(Drawable glyph, Drawable shadow, float size) {
            super(LauncherActivity.this);
            this.glyph = glyph;
            this.shadow = shadow;
            this.size = size;
            setFocusable(true);
            setClickable(true);
            setBackground(Ui.selectable(0));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f - lift;
            int l = Math.round(cx - size / 2), t = Math.round(cy - size / 2), s = Math.round(size);
            Ui.outline(c, shadow, l, t, s);
            glyph.setBounds(l, t, l + s, t + s);
            glyph.draw(c);
            if (splat) Ui.splat(c, cx + Ui.s(0.029f), cy - Ui.s(0.024f), Ui.s(0.0125f), sp);
        }
    }

    private final Runnable idleGc = () -> {
        if (visible) return;
        Runtime.getRuntime().gc();
    };

    private void goDefaultPage(boolean animate) {
        pager.setCurrentPage(framesIndex, animate);
        updateBottomBar();
    }

    @Override
    public void onPageChanged(int page) {
        if (page != 0) returnPkg = null;
        updateBottomBar();
        indicator.invalidate();
        if (page == framesIndex) framesPage.refresh();
        if (resumed) refreshChromeSoon(600);
    }

    @Override
    public void onOverscrollLeft() {
        hubPage.openAccounts();
    }

    @Override
    public boolean pullsLeft() { return !editMode; }

    @Override
    public void onPullLeft(float px) { hubPage.dragAccounts(px); }

    @Override
    public void onPullLeftEnd(float px, float velocity) { hubPage.releaseAccounts(px, velocity); }

    @Override
    public float pulled() { return hubPage.accountsPulled(); }

    @Override
    public void onAppsChanged() {
        if (dragging) return;
        if (resumed) rebuildPages();
        else needsRebuild = true;
        for (int i = 0; i < overlayLayer.getChildCount(); i++) {
            View v = overlayLayer.getChildAt(i);
            if (v instanceof FolderOverlay) ((FolderOverlay) v).refresh();
        }
    }

    @Override
    public void onHubChanged() {
        statusBar.invalidate();
        hubPage.refresh();
        refreshBadges();
        framesPage.invalidateFrames();
        if (hubButton != null) {
            hubButton.splat = app.hub.unread(null) > 0;
            hubButton.invalidate();
        }
    }

    @Override
    public void onFramesChanged() {
        indicator.invalidate();
        if (!resumed && visible) {
            framesDirty = true;
            return;
        }
        framesPage.refresh();
        if (!visible) framesPage.scrollToTop();
        refreshChromeSoon(300);
    }

    private boolean framesDirty;

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        switch (key) {
            case Prefs.ICON_PACK:
            case Prefs.ICON_SHAPE:
            case Prefs.BB10_ICONS:
                IconPackManager.clear();
                app.icons.invalidateAll();
                needsRebuild = true;
                break;
            case Prefs.HIDDEN:
                app.apps.reload();
                break;
            case Prefs.FONT_STAMP:
                Ui.loadFont(this);
                recreate();
                return;
            case Prefs.HUB_THEME:
                recreate();
                return;
            case Prefs.GRID_COLS:
            case Prefs.GRID_ROWS:
            case Prefs.SHOW_LABELS:
                needsRebuild = true;
                break;
            case Prefs.BB_STATUS:
            case Prefs.HIDE_STATUS_APPS:
                applySystemBars();
                break;
            case Prefs.CLOCK_24:
            case Prefs.BB_LOGO:
                statusBar.invalidate();
                break;
            case Prefs.STATUS_SCALE: {
                StatusBarView.scale = prefs.scale(Prefs.STATUS_SCALE);
                ViewGroup.LayoutParams lp = statusBar.getLayoutParams();
                if (lp != null && lp.height != StatusBarView.height()) {
                    lp.height = StatusBarView.height();
                    statusBar.setLayoutParams(lp);
                }
                statusBar.invalidate();
                break;
            }
            case Prefs.HOME_TEXT:
            case Prefs.WALL_DIM:
                applyHomeLook();
                break;
            case Prefs.FRAMES_ARRANGE:
                framesPage.refresh();
                break;
        }
        if (needsRebuild && resumed) {
            needsRebuild = false;
            rebuildPages();
        }
    }

    private android.app.WallpaperManager.OnColorsChangedListener wallColors;

    private void applyHomeLook() {
        int dim = 0;
        try {
            dim = Math.max(0, Math.min(90, Integer.parseInt(prefs.sp.getString(Prefs.WALL_DIM, "0"))));
        } catch (Exception ignored) {
        }
        root.setBackgroundColor(Color.argb(Math.round(dim * 2.55f), 0, 0, 0));
        homeDim = Math.round(dim * 2.55f);
        String mode = prefs.sp.getString(Prefs.HOME_TEXT, "light");
        boolean dark;
        if ("dark".equals(mode)) dark = true;
        else if ("auto".equals(mode)) dark = dim < 30 && wallpaperSuitsDarkText();
        else dark = false;
        if (wallColors == null && "auto".equals(mode)) {
            wallColors = (colors, which) -> {
                if ((which & android.app.WallpaperManager.FLAG_SYSTEM) != 0) applyHomeLook();
            };
            try {
                android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallColors, new android.os.Handler(android.os.Looper.getMainLooper()));
            } catch (Exception ignored) {
            }
        }
        if (dark == Ui.homeDark && homeLookApplied) return;
        homeLookApplied = true;
        Ui.homeDark = dark;
        Ui.homeGen++;
        statusBar.darkIntensity = dark && pager.getCurrentPage() != 0 ? 1f : 0f;
        statusBar.invalidate();
        if (pager.getWidth() > 0) hubSlide(pager.getScrollX());
        framesPage.applyHomeStyle();
        barBuiltForEdit = null;
        updateBottomBar();
        invalidateTree(pager);
        indicator.invalidate();
    }

    private boolean homeLookApplied;

    private boolean wallpaperSuitsDarkText() {
        try {
            android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(this).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            return wc != null && (wc.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    private void onItemClick(IconView v) {
        if (v.item == null) return;
        if (editMode) {
            if (v.downOnClose()) onItemClose(v.item);
            else showItemOptions(v.item, null);
            return;
        }
        if (v.item.type == GridItem.APP) launchApp(v.item.app, v);
        else openFolder(v.item, v);
    }

    private void onItemClose(GridItem g) {
        if (g.type == GridItem.FOLDER) {
            Dialogs.confirm(this, "Remove folder", "Move the apps in “" + g.name + "” back to the home screen?", "Remove", () -> {
                app.apps.ungroup(g);
                rebuildPages();
            });
        } else {
            AppInfo a = app.apps.app(g.app);
            if (a != null) uninstall(a);
        }
    }

    private void openFolder(GridItem g, IconView from) {
        overlayLayer.addView(new FolderOverlay(this, g, from), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static final class BarBg extends android.graphics.drawable.Drawable {
        final GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x85000000, 0x38000000, 0x00000000});
        final android.graphics.Paint black = new android.graphics.Paint();
        float k;

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (k < 1f) {
                grad.setBounds(b);
                grad.setAlpha(Math.round(255 * (1f - k)));
                grad.draw(c);
            }
            if (k > 0f) {
                black.setColor(Math.round(255 * k) << 24);
                c.drawRect(b.left, b.bottom - Ui.s(0.15f), b.right, b.bottom, black);
            }
        }

        @Override
        public void setAlpha(int a) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {}

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private final BarBg barBg = new BarBg();
    private android.animation.ValueAnimator scrubAnim;
    private float scrubK;

    private boolean scrubbing;

    private void onScrub(boolean on) {
        scrubbing = on;
        hubSlide(pager.getScrollX());
        if (scrubAnim != null) scrubAnim.cancel();
        scrubAnim = android.animation.ValueAnimator.ofFloat(scrubK, on ? 1f : 0f);
        scrubAnim.setDuration(160);
        scrubAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        scrubAnim.addUpdateListener(va -> {
            scrubK = (float) va.getAnimatedValue();
            float s = 1f - 0.1f * scrubK;
            for (int i = 0; i < pager.getChildCount(); i++) {
                View pg = pager.getChildAt(i);
                float ps = pg == hubPage ? 1f : s;
                pg.setPivotX(pg.getWidth() / 2f);
                pg.setPivotY(pg.getHeight() / 2f);
                pg.setScaleX(ps);
                pg.setScaleY(ps);
            }
            barBg.k = scrubK;
            bottomBar.invalidate();
            for (int i = 0; i < bottomBar.getChildCount(); i++) {
                View v = bottomBar.getChildAt(i);
                if (v != indicator) v.setAlpha(1f - scrubK);
            }
        });
        scrubAnim.start();
    }

    public static LauncherActivity peekTarget() {
        LauncherActivity a = current;
        return a != null && a.visible && a.resumed && !a.editMode ? a : null;
    }

    private float peekK;
    private LinearLayout peekCol;
    private android.animation.ValueAnimator peekAnim;
    private final List<View> peekHidden = new ArrayList<>();
    public static final int PEEK_BACK = 0, PEEK_FRAMES = 1, PEEK_HUB = 2;
    private FrameLayout peekDateWrap;
    private View peekDate;
    private final Runnable peekDateIn = this::showPeekDate;
    private View peekShot;
    private int peekFrom = -1;
    private boolean peekSide;

    public void homePeek(float dy) {
        if (peekSide) return;
        if (peekAnim != null) peekAnim.cancel();
        if (peekCol == null) startPeek();
        float L = Math.max(1, root.getHeight()) * 0.21f;
        applyPeek(Math.max(0f, Math.min(1f, dy / L)));
    }

    private void startPeek() {
        statusBar.popPercent(true);
        com.bb10.launcher.gesture.GestureService.peekBar(this, true);
        closeAllOverlays();
        if (scrubbing) onScrub(false);
        pager.setCurrentPage(pager.getCurrentPage(), false);
        View curPage = pager.getCurrentView();
        if (curPage != null) {
            curPage.setAlpha(1f);
            curPage.setTranslationX(0f);
            if (curPage instanceof com.bb10.launcher.ui.ActiveFramesPage) ((com.bb10.launcher.ui.ActiveFramesPage) curPage).setPageFade(1f);
        }
        peekFrom = pager.getCurrentPage();
        boolean top = "top".equals(prefs.sp.getString("lock_column", "center"));
        peekCol = com.bb10.launcher.ui.NotifyColumn.build(this, com.bb10.launcher.ui.NotifyColumn.entries(), top,
                StatusBarView.height() + Math.round(0.1f * Ui.S()));
        peekCol.setAlpha(0f);
        root.addView(peekCol, new FrameLayout.LayoutParams(Math.round(0.19f * Ui.S()), ViewGroup.LayoutParams.MATCH_PARENT));
        int cur = pager.getCurrentPage();
        peekHidden.clear();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View p = pager.getChildAt(i);
            if (i != cur && p.getVisibility() == View.VISIBLE) {
                p.setVisibility(View.INVISIBLE);
                peekHidden.add(p);
            }
        }
        h.removeCallbacks(peekDateIn);
        h.postDelayed(peekDateIn, 1000);
    }

    private void applyPeek(float f) {
        peekK = f;
        float e = f * f * (3f - 2f * f);
        float sc = 1f - 0.35f * e;
        pager.setPivotX(pager.getWidth() / 2f);
        pager.setPivotY(pager.getHeight() / 2f);
        pager.setScaleX(sc);
        pager.setScaleY(sc);
        framesPage.setPeekScale(pager.getCurrentPage() == framesIndex ? sc : 1f);
        bottomBar.setTranslationY(bottomBar.getHeight() * e);
        bottomBar.setAlpha(1f - e);
        if (peekCol != null) {
            float u = Math.max(0f, Math.min(1f, (f - 0.08f) / 0.62f));
            u = u * u * (3f - 2f * u);
            peekCol.setAlpha(u);
            peekCol.setTranslationX(-peekCol.getWidth() * 0.5f * (1f - u));
        }
    }

    private void showPeekDate() {
        if (peekCol == null || peekSide) return;
        float S = Ui.S();
        boolean own = statusBar.getVisibility() == View.VISIBLE;
        int sbH = own ? StatusBarView.height() : 0, behind = own ? 0 : content.getPaddingTop();
        int rowH = Math.round(0.062f * S);
        if (peekDateWrap != null && peekDateWrap.getParent() == null && !Integer.valueOf(behind).equals(peekDate.getTag())) {
            peekDateWrap = null;
        }
        if (peekDateWrap == null) {
            peekDateWrap = new FrameLayout(this);
            peekDateWrap.setClipChildren(true);
            LinearLayout band = new LinearLayout(this);
            band.setOrientation(LinearLayout.HORIZONTAL);
            band.setGravity(Gravity.CENTER_VERTICAL);
            band.setBackgroundColor(0xFF000000);
            int pad = Math.round(0.012f * S);
            band.setPadding(pad, 0, pad, 0);
            TextView date = new TextView(this);
            date.setTag("date");
            TextView carrier = new TextView(this);
            carrier.setTag("carrier");
            for (TextView t : new TextView[]{date, carrier}) {
                t.setTextColor(Ui.TEXT);
                t.setTypeface(Ui.regular());
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 0.042f * S);
                t.setIncludeFontPadding(false);
                t.setSingleLine(true);
            }
            band.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            band.addView(carrier, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout holder = new FrameLayout(this);
            holder.setBackgroundColor(0xFF000000);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH, Gravity.BOTTOM);
            holder.addView(band, bl);
            View rule = new View(this);
            rule.setBackgroundColor(0xFF4A4A4A);
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.003f * S)), Gravity.BOTTOM);
            holder.addView(rule, rl);
            holder.setTag(behind);
            peekDate = holder;
            peekDateWrap.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, behind + rowH));
        }
        ((TextView) peekDate.findViewWithTag("date")).setText(android.text.format.DateFormat.format("EEEE, MMMM d", System.currentTimeMillis()));
        ((TextView) peekDate.findViewWithTag("carrier")).setText(carrierText());
        if (peekDateWrap.getParent() == null) {
            FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, behind + rowH);
            wl.topMargin = sbH;
            root.addView(peekDateWrap, wl);
        }
        if (own) {
            statusBar.solidBg = 0xFF000000;
            statusBar.invalidate();
        }
        peekDate.setTranslationY(-(behind + rowH));
        peekDate.animate().translationY(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
    }

    private String carrierText() {
        try {
            android.telephony.TelephonyManager tm = getSystemService(android.telephony.TelephonyManager.class);
            if (tm == null) return "";
            if (tm.getSimState() == android.telephony.TelephonyManager.SIM_STATE_ABSENT) return "SIM not detected";
            CharSequence op = tm.getNetworkOperatorName();
            if (op == null || op.length() == 0) op = tm.getSimOperatorName();
            return op == null || op.length() == 0 ? "No Service" : op.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void hidePeekDate() {
        h.removeCallbacks(peekDateIn);
        if (statusBar.solidBg != 0) {
            statusBar.solidBg = 0;
            statusBar.invalidate();
        }
        if (peekDateWrap == null || peekDateWrap.getParent() == null) return;
        FrameLayout wrap = peekDateWrap;
        peekDate.animate().cancel();
        peekDate.animate().translationY(-wrap.getHeight()).setDuration(160)
                .withEndAction(() -> {
                    if (peekCol == null && wrap.getParent() != null) root.removeView(wrap);
                }).start();
    }

    public static float sideResist(float x, float screenW) {
        if (x <= 0) return 0;
        float span = 140f * screenW / 720f, k = 0.1f;
        if (x >= span) return x;
        float u = x / span, s = u * u * (3f - 2f * u);
        return x * (k + (1f - k) * s);
    }

    private View shotOfPage() {
        View page = pager.getCurrentView();
        if (page == null) return null;
        int ext = page.getHeight() / 2;
        android.graphics.Picture pic = new android.graphics.Picture();
        android.graphics.Canvas c = pic.beginRecording(page.getWidth(), page.getHeight() + 2 * ext);
        c.translate(0, ext);
        if (page instanceof com.bb10.launcher.ui.ActiveFramesPage) ((com.bb10.launcher.ui.ActiveFramesPage) page).forPicture(true);
        page.draw(c);
        if (page instanceof com.bb10.launcher.ui.ActiveFramesPage) ((com.bb10.launcher.ui.ActiveFramesPage) page).forPicture(false);
        pic.endRecording();
        View v = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                canvas.drawPicture(pic);
            }
        };
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(page.getWidth(), page.getHeight() + 2 * ext);
        lp.leftMargin = content.getLeft() + pager.getLeft();
        lp.topMargin = content.getTop() + pager.getTop() - ext;
        v.setPivotX(pager.getPivotX());
        v.setPivotY(pager.getPivotY() + ext);
        v.setScaleX(pager.getScaleX());
        v.setScaleY(pager.getScaleY());
        root.addView(v, 0, lp);
        return v;
    }

    private View peekWall;

    private View wallCopy() {
        android.graphics.Bitmap wall = com.bb10.launcher.gesture.GestureService.homeWall();
        if (wall == null) return null;
        android.graphics.Rect real = getWindowManager().getMaximumWindowMetrics().getBounds();
        int dim = homeDim;
        View v = new View(this) {
            final android.graphics.Paint pic = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            final android.graphics.RectF dst = new android.graphics.RectF();
            final int[] loc = new int[2];

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                getLocationOnScreen(loc);
                float z = RevealBg.wallpaperScale();
                float sw = real.width(), sh = real.height();
                float ox = -(z - 1) * sw / 2, oy = -(z - 1) * sh / 2;
                float lx = loc[0] - getTranslationX();
                dst.set(-lx + ox, -loc[1] + oy, -lx + ox + sw * z, -loc[1] + oy + sh * z);
                c.drawBitmap(wall, null, dst, pic);
                if (dim > 0) c.drawColor(dim << 24);
            }
        };
        root.addView(v, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private final HomeSwipe homeSwipe = new HomeSwipe();

    public void homeSwipe(int action, float rawX, float rawY) {
        homeSwipe.on(action, rawX, rawY);
    }

    private final class HomeSwipe {
        private float x0, y0, sideFrom, lastRaw = -1, lastShown, unlockGap;
        private int mode;
        private boolean unlocked;
        private long unlockAt, t0;
        private android.view.VelocityTracker vt;
        private static final long GLIDE = 170;

        private final Runnable glide = new Runnable() {
            @Override
            public void run() {
                if (mode != 2 || lastRaw < 0) return;
                send(shown(lastRaw));
                if (android.os.SystemClock.uptimeMillis() - unlockAt < GLIDE) root.postOnAnimation(this);
            }
        };

        private float shown(float x) {
            x = Math.max(0f, x);
            float sc = Math.max(1, root.getWidth()) / 720f;
            float t = com.bb10.launcher.gesture.SwipeUp.LOCK_FINGER * sc, held = com.bb10.launcher.gesture.SwipeUp.LOCK_HELD * sc;
            if (!unlocked) {
                float v = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getXVelocity();
                }
                boolean flick = v > Ui.dp(com.bb10.launcher.gesture.SwipeUp.LOCK_FLICK_DP);
                if (x < t && !flick) return com.bb10.launcher.gesture.SwipeUp.heldAt(x, t, held);
                unlocked = true;
                unlockAt = android.os.SystemClock.uptimeMillis();
                unlockGap = x - com.bb10.launcher.gesture.SwipeUp.heldAt(Math.min(x, t), t, held);
                root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                root.postOnAnimation(glide);
            }
            float g = Math.min(1f, (android.os.SystemClock.uptimeMillis() - unlockAt) / (float) GLIDE);
            float e = 1f - (1f - g) * (1f - g) * (1f - g);
            return x - unlockGap * (1f - e);
        }

        private void send(float x) {
            lastShown = x;
            homePeekSide(x);
        }

        void on(int action, float x, float y) {
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            MotionEvent ev = MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    t0 = android.os.SystemClock.uptimeMillis();
                    vt.clear();
                    x0 = x;
                    y0 = y;
                    mode = 0;
                    unlocked = false;
                    lastRaw = -1;
                    vt.addMovement(ev);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    vt.addMovement(ev);
                    float dy = y0 - y, dx = x - x0;
                    float pdx = dx, pdy = dy;
                    if (prefs.bool(Prefs.GEST_PREDICT, true)) {
                        vt.computeCurrentVelocity(1000);
                        pdx = dx + com.bb10.launcher.gesture.SwipeUp.ahead(vt.getXVelocity());
                        pdy = dy + com.bb10.launcher.gesture.SwipeUp.ahead(-vt.getYVelocity());
                    }
                    if (mode == 0 && dy > Ui.dp(8)) {
                        mode = 1;
                        root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (mode == 1) {
                        if (dx > Ui.dp(48)) {
                            mode = 2;
                            sideFrom = dx;
                        } else homePeek(Math.max(0f, pdy));
                    }
                    if (mode == 2) {
                        lastRaw = pdx - sideFrom;
                        send(shown(lastRaw));
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.addMovement(ev);
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    boolean up = action == MotionEvent.ACTION_UP;
                    float dy = y0 - y;
                    if (mode == 1) {
                        float L = root.getHeight() * 0.27f, vUp = -vy;
                        boolean go = up && (vUp > Ui.dp(500) || vUp > -Ui.dp(300) && dy > L * 0.35f);
                        homePeekEnd(go ? PEEK_FRAMES : PEEK_BACK);
                    } else if (mode == 2) {
                        int W = Math.max(1, root.getWidth());
                        boolean open = up && (vx > Ui.dp(450) || lastShown > W * 0.2f && vx > -Ui.dp(400));
                        homePeekEnd(open ? PEEK_HUB : PEEK_BACK, vx);
                    }
                    mode = 0;
                    lastRaw = -1;
                    break;
                }
            }
            ev.recycle();
        }
    }

    private View peekHubTop;

    private void hubTopAt(float hubLeft) {
        if (peekHubTop == null) {
            int topH = statusBar.getVisibility() == View.VISIBLE ? StatusBarView.height() : content.getPaddingTop();
            if (topH <= 0) return;
            peekHubTop = new View(this);
            peekHubTop.setBackgroundColor(0xFF000000);
            root.addView(peekHubTop, root.indexOfChild(content), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topH));
        }
        peekHubTop.setTranslationX(hubLeft);
    }

    private void dropHubTop() {
        if (peekHubTop != null) root.removeView(peekHubTop);
        peekHubTop = null;
    }

    private void pushHome(float x) {
        hubTopAt(x - Math.max(1, pager.getWidth()));
        if (peekShot != null) peekShot.setTranslationX(x);
        if (peekWall != null) peekWall.setTranslationX(x);
        if (peekCol != null) peekCol.setTranslationX(x);
    }

    public void homePeekSide(float x) {
        if (peekAnim != null) peekAnim.cancel();
        int w = Math.max(1, pager.getWidth());
        if (!peekSide) {
            peekSide = true;
            if (peekFrom < 0) peekFrom = pager.getCurrentPage();
            h.removeCallbacks(peekDateIn);
            peekShot = shotOfPage();
            peekWall = wallCopy();
            for (View p : peekHidden) p.setVisibility(View.VISIBLE);
            peekHidden.clear();
            framesPage.setPeekScale(1f);
            pager.setScaleX(1f);
            pager.setScaleY(1f);
            pager.setCurrentPage(0, false);
            for (int i = 1; i < pager.getChildCount(); i++) {
                View p = pager.getChildAt(i);
                if (p.getVisibility() == View.VISIBLE) {
                    p.setVisibility(View.INVISIBLE);
                    peekHidden.add(p);
                }
            }
            content.setBackground(null);
            hubBar.setClipBounds(null);
            hubCompose.setClipBounds(null);
        }
        float tx = -w + Math.min(w, Math.max(0f, x));
        pager.setTranslationX(tx);
        hubBar.setTranslationX(tx);
        hubCompose.setTranslationX(tx);
        pushHome(tx + w);
    }

    public void homePeekSideEnd(boolean open) {
        homePeekSideEnd(open, 0f);
    }

    public void homePeekSideEnd(boolean open, float vx) {
        if (!peekSide) return;
        int w = Math.max(1, pager.getWidth());
        float from = pager.getTranslationX(), to = open ? 0 : -w;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
        peekAnim = a;
        long ms = open ? 220 : 200;
        float v = Math.abs(vx), dist = Math.abs(to - from);
        if ((to > from) == (vx > 0) && v > Ui.dp(150) && dist > 1f) ms = Math.max(140, Math.min(380, Math.round(3f * dist / v * 1000f)));
        a.setDuration(ms);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        a.addUpdateListener(va -> {
            float tx = (float) va.getAnimatedValue();
            pager.setTranslationX(tx);
            hubBar.setTranslationX(tx);
            hubCompose.setTranslationX(tx);
            pushHome(tx + w);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                peekAnim = null;
                peekSide = false;
                if (peekCol == null) {
                    pager.setTranslationX(0);
                    if (!open && peekFrom >= 0) pager.setCurrentPage(peekFrom, false);
                    endPeek();
                    hubSlide(pager.getScrollX());
                    return;
                }
                if (open) {
                    pager.setTranslationX(0);
                    hubFromPeek = true;
                    hidePeekDate();
                    statusBar.popPercent(false);
                    com.bb10.launcher.gesture.GestureService.peekBar(LauncherActivity.this, false);
                    bottomBar.setAlpha(0f);
                    hubSlide(pager.getScrollX());
                } else zoomBackIn();
            }
        });
        a.start();
    }

    private boolean hubFromPeek;

    private final Runnable returnCheck = new Runnable() {
        @Override
        public void run() {
            if (!hubFromPeek) return;
            if (pager.getScrollX() < pager.getWidth()) return;
            if (pager.isSettled()) zoomBackIn();
            else pager.postOnAnimation(this);
        }
    };

    private void zoomBackIn() {
        hubFromPeek = false;
        pager.setTranslationX(0);
        if (peekFrom >= 0 && pager.getCurrentPage() != peekFrom) pager.setCurrentPage(peekFrom, false);
        for (View p : peekHidden) p.setVisibility(View.VISIBLE);
        peekHidden.clear();
        int cur = pager.getCurrentPage();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View p = pager.getChildAt(i);
            if (i != cur && p.getVisibility() == View.VISIBLE) {
                p.setVisibility(View.INVISIBLE);
                peekHidden.add(p);
            }
        }
        applyPeek(1f);
        if (peekCol != null) peekCol.setTranslationX(0);
        if (peekShot != null) root.removeView(peekShot);
        peekShot = null;
        if (peekWall != null) root.removeView(peekWall);
        peekWall = null;
        dropHubTop();
        hubSlide(pager.getScrollX());
        if (peekAnim != null) peekAnim.cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
        peekAnim = a;
        a.setDuration(300);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.addUpdateListener(va -> applyPeek((float) va.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (cancelled) return;
                peekAnim = null;
                endPeek();
                hubSlide(pager.getScrollX());
            }
        });
        a.start();
    }

    private void clearHubReturn() {
        if (!hubFromPeek) return;
        hubFromPeek = false;
        endPeek();
        if (pager != null) hubSlide(pager.getScrollX());
    }

    public void homePeekEnd(int to) {
        homePeekEnd(to, 0f);
    }

    public void homePeekEnd(int to, float vx) {
        if (peekSide) {
            homePeekSideEnd(to == PEEK_HUB, vx);
            return;
        }
        if (peekCol == null && peekK == 0f) {
            if (to == PEEK_FRAMES && pager.getCurrentPage() != framesIndex) {
                pager.setCurrentPage(framesIndex, false);
                updateBottomBar();
            }
            return;
        }
        if (peekAnim != null) peekAnim.cancel();
        int w = Math.max(1, pager.getWidth());
        boolean slide = to == PEEK_FRAMES && pager.getCurrentPage() != framesIndex;
        if (slide) {
            peekShot = shotOfPage();
            for (View p : peekHidden) p.setVisibility(View.VISIBLE);
            peekHidden.clear();
            pager.setCurrentPage(framesIndex, false);
            updateBottomBar();
            for (int i = 0; i < pager.getChildCount(); i++) {
                View p = pager.getChildAt(i);
                if (i != framesIndex && p.getVisibility() == View.VISIBLE) {
                    p.setVisibility(View.INVISIBLE);
                    peekHidden.add(p);
                }
            }
            pager.setTranslationX(-w);
        }
        float from = peekK;
        View shot = peekShot;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        peekAnim = a;
        a.setDuration(to == PEEK_BACK ? 200 : 260);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        a.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            applyPeek(from * (1f - t));
            if (slide) {
                pager.setTranslationX(-w * (1f - t));
                if (shot != null) shot.setTranslationX(w * t);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (cancelled) return;
                peekAnim = null;
                pager.setTranslationX(0);
                endPeek();
            }
        });
        a.start();
    }

    private void endPeek() {
        applyPeek(0f);
        pager.setScaleX(1f);
        pager.setScaleY(1f);
        bottomBar.setTranslationY(0f);
        bottomBar.setAlpha(1f);
        for (View p : peekHidden) p.setVisibility(View.VISIBLE);
        peekHidden.clear();
        framesPage.setPeekScale(1f);
        if (peekCol != null) root.removeView(peekCol);
        peekCol = null;
        if (peekShot != null) root.removeView(peekShot);
        peekShot = null;
        if (peekWall != null) root.removeView(peekWall);
        peekWall = null;
        dropHubTop();
        peekFrom = -1;
        statusBar.popPercent(false);
        com.bb10.launcher.gesture.GestureService.peekBar(this, false);
        hidePeekDate();
    }

    public void fadeHome(float a) {
        pager.setAlpha(a);
        bottomBar.setAlpha(a);
    }

    public int gridCols() { return prefs.cols(); }

    public int gridRows() { return prefs.rows(); }

    public void gridArea(View in, android.graphics.Rect out) {
        int[] a = new int[2], b = new int[2];
        pager.getLocationInWindow(a);
        in.getLocationInWindow(b);
        out.set(a[0] - b[0], a[1] - b[1], a[0] - b[0] + pager.getWidth(), a[1] - b[1] + pager.getHeight());
    }

    public void launchApp(String key, View src) {
        AppInfo a = app.apps.app(key);
        if (a == null) return;
        if (a.pkg.equals(getPackageName()) && a.cn.getClassName().endsWith(".HubShortcutActivity")) {
            closeAllOverlays();
            pager.setCurrentPage(0, true);
            return;
        }
        Intent main = Intent.makeMainActivity(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!prefs.bool(Prefs.FRAMES_LAUNCH, true) || !app.frames.trackable(a.pkg)) {
            if (!remoteLaunch(main, src)) app.apps.launch(a, src, null);
            return;
        }
        closeAllOverlays();
        if (editMode) exitEditMode();
        app.frames.onForeground(a.pkg);
        framesPage.refresh();
        framesPage.scrollToTop();
        boolean already = pager.getCurrentPage() == framesIndex;
        pager.setCurrentPage(framesIndex, true);
        h.postDelayed(() -> {
            View fv = framesPage.frameViewFor(a.pkg);
            boolean ok = fv != null && fv.isAttachedToWindow() && fv.getWidth() > 0;
            if (ok && remoteFrameLaunch(main, fv, rectOf(fv))) return;
            if (ok && overlayLaunch(main, fv, a.pkg)) return;
            if (ok) app.apps.launch(a, fv, snapshot(fv));
            else app.apps.launch(a, src, null);
        }, already ? 80 : 330);
    }

    private boolean overlayLaunch(Intent i, View frame, String pkg) {
        return overlayLaunch(i, rectOf(frame), pkg);
    }

    private boolean overlayLaunch(Intent i, android.graphics.Rect r, String pkg) {
        if (r == null) return false;
        Bitmap thumb = null;
        for (FrameTracker.Frame f : app.frames.list()) if (f.pkg.equals(pkg)) thumb = f.thumb;
        Intent quiet = new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return com.bb10.launcher.gesture.GestureService.playLaunch(r, thumb, app.iconForPackage(pkg), pkg, () -> {
            try {
                startActivity(quiet, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            } catch (Exception e) {
                Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean remoteLaunch(Intent i, View from) {
        return remoteLaunch(i, rectOf(from));
    }

    private static android.graphics.Bitmap liveInfoBitmap(View frame) {
        return renderLook(recordLook(frame));
    }

    private static android.graphics.Picture recordLook(View frame) {
        int w = frame.getWidth();
        int h = frame.getHeight() - com.bb10.launcher.ui.Ui.dp(com.bb10.launcher.ui.ActiveFramesPage.BAR_DP);
        if (android.os.Build.VERSION.SDK_INT < 31 || w <= 0 || h <= 0) return null;
        try {
            android.util.DisplayMetrics dm = frame.getResources().getDisplayMetrics();
            float k = Math.max(1f, Math.max(dm.widthPixels / (float) w, dm.heightPixels / (float) h));
            int bw = Math.round(w * k), bh = Math.round(h * k);
            android.graphics.Picture pic = new android.graphics.Picture();
            Canvas pc = pic.beginRecording(bw, bh);
            pc.scale(bw / (float) w, bh / (float) h);
            pc.clipRect(0, 0, w, h);
            com.bb10.launcher.ui.ActiveFramesPage.drawPlain(frame, pc);
            pic.endRecording();
            return pic;
        } catch (Throwable t) {
            com.bb10.launcher.quickstep.ShellTransitions.note("live frame picture unavailable: " + t);
            return null;
        }
    }

    private static android.graphics.Bitmap renderLook(android.graphics.Picture pic) {
        if (pic == null) return null;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(pic);
            return b.getConfig() == android.graphics.Bitmap.Config.HARDWARE ? b : null;
        } catch (Throwable t) {
            com.bb10.launcher.quickstep.ShellTransitions.note("live frame picture unavailable: " + t);
            return null;
        }
    }

    private final Runnable chromeRefresh = this::refreshChrome;
    private boolean scrollWatch;

    private final java.util.function.Consumer<java.util.function.Consumer<com.bb10.launcher.quickstep.LauncherChrome.Piece>> underNow = cb -> h.post(() -> {
        android.graphics.Picture up = null;
        android.graphics.Rect da = null;
        try {
            da = dockRect();
            boolean onHub = bottomBar.getTranslationX() != 0f && !editMode;
            if (da != null && onHub) da.offset(-Math.round(bottomBar.getTranslationX()), 0);
            if (da != null && bottomBar.getTranslationY() == 0f) up = recordUnderDock(da, onHub);
        } catch (Throwable ignored) {
        }
        android.graphics.Picture fp = up;
        android.graphics.Rect fa = da;
        openHandler().post(() -> cb.accept(fp != null ? chromePiece(fp, fa) : null));
    });

    private void refreshChrome() {
        h.removeCallbacks(chromeRefresh);
        h.postDelayed(chromeRefresh, 60_000 - System.currentTimeMillis() % 60_000 + 1000);
        if (android.os.Build.VERSION.SDK_INT < 33 || !RemoteAnims.available(this)) return;
        if (!resumed && visible) {
            h.removeCallbacks(chromeRefresh);
            h.postDelayed(chromeRefresh, 500);
            return;
        }
        if (bottomBar.getTranslationY() != 0f || peekCol != null || peekSide || peekAnim != null
                || pager.getScaleX() != 1f || pager.getTranslationX() != 0f) {
            h.removeCallbacks(chromeRefresh);
            h.postDelayed(chromeRefresh, 500);
            return;
        }
        android.graphics.Picture dp = recordView(bottomBar), bp = statusBar.getVisibility() == View.VISIBLE ? recordView(statusBar) : null;
        android.graphics.Rect da = dp != null ? dockRect() : null, ba = bp != null ? rectOf(statusBar) : null;
        boolean onHub = bottomBar.getTranslationX() != 0f && !editMode;
        if (da != null && onHub) da.offset(-Math.round(bottomBar.getTranslationX()), 0);
        android.graphics.Picture up = da != null ? recordUnderDock(da, onHub) : null;
        openHandler().post(() -> com.bb10.launcher.quickstep.LauncherChrome.set(chromePiece(dp, da), chromePiece(bp, ba), chromePiece(up, da)));
    }

    private android.graphics.Picture recordUnderDock(android.graphics.Rect da, boolean asFrames) {
        if (pager == null || !pager.isAttachedToWindow() || !root.isAttachedToWindow()) return null;
        int[] rl = new int[2], pl = new int[2];
        root.getLocationOnScreen(rl);
        pager.getLocationOnScreen(pl);
        android.graphics.Picture p = new android.graphics.Picture();
        android.graphics.Canvas c = p.beginRecording(da.width(), da.height());
        c.clipRect(0, 0, da.width(), da.height());
        c.translate(-da.left, -da.top);
        android.graphics.drawable.Drawable bg = root.getBackground();
        if (bg != null) {
            c.save();
            c.translate(rl[0], rl[1]);
            bg.draw(c);
            c.restore();
        }
        View fp = asFrames ? pager.getChildAt(framesIndex) : null;
        int sx = fp != null ? fp.getLeft() : pager.getScrollX();
        c.translate(pl[0] - sx, pl[1] - pager.getScrollY());
        if (framesPage != null) framesPage.forPicture(true);
        pager.draw(c);
        if (framesPage != null) framesPage.forPicture(false);
        p.endRecording();
        return p;
    }

    private void refreshChromeSoon(long ms) {
        h.removeCallbacks(chromeRefresh);
        h.postDelayed(chromeRefresh, ms);
    }

    private static android.graphics.Picture recordView(View v) {
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0 || v.getHeight() == 0) return null;
        android.graphics.Picture p = new android.graphics.Picture();
        v.draw(p.beginRecording(v.getWidth(), v.getHeight()));
        p.endRecording();
        return p;
    }

    private static com.bb10.launcher.quickstep.LauncherChrome.Piece chromePiece(android.graphics.Picture p, android.graphics.Rect at) {
        if (p == null || at == null) return null;
        android.graphics.Bitmap b = renderLook(p);
        android.hardware.HardwareBuffer buf = b != null ? b.getHardwareBuffer() : null;
        android.view.SurfaceControl sc = buf != null ? RemoteAnims.liveInfoSurface(buf) : null;
        return sc != null ? new com.bb10.launcher.quickstep.LauncherChrome.Piece(sc, at) : null;
    }

    private static android.graphics.Rect rectOf(View v) {
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0) return null;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new android.graphics.Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    private boolean remoteLaunch(Intent i, android.graphics.Rect r) {
        if (r == null || !RemoteAnims.available(this)) return false;
        Bundle b = com.bb10.launcher.quickstep.ShellTransitions.canLaunch(this)
                ? com.bb10.launcher.quickstep.ShellTransitions.launchOptions(this, r)
                : RemoteAnims.launchOptions(this, r);
        if (b == null) return false;
        try {
            startActivity(i, b);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap snapshot(View v) {
        if (v == null || v.getWidth() == 0) return null;
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    @Override
    public void onHubItemOpened() {}

    @Override
    public void requestContactsPermission() {
        requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 5);
    }

    @Override
    public void closeSearch(SearchOverlay s) {
        overlayLayer.removeView(s);
    }

    @Override
    public boolean fullScreen() { return true; }

    private boolean ownKeyboard() {
        if (!hasWindowFocus() || root == null) return false;
        View f = root.findFocus();
        return f != null && f.onCheckIsTextEditor();
    }

    private void imeTarget(boolean on) {
        int f = android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        boolean off = (getWindow().getAttributes().flags & f) != 0;
        if (on == !off) return;
        getWindow().setFlags(on ? 0 : f, f);
    }

    private void hideStatusNow(WindowInsetsController c) {
        try {
            c.controlWindowInsetsAnimation(WindowInsets.Type.statusBars(), 0, new android.view.animation.LinearInterpolator(), null,
                    new android.view.WindowInsetsAnimationControlListener() {
                        @Override
                        public void onReady(android.view.WindowInsetsAnimationController ctl, int types) {
                            ctl.finish(false);
                        }

                        @Override
                        public void onFinished(android.view.WindowInsetsAnimationController ctl) {}

                        @Override
                        public void onCancelled(android.view.WindowInsetsAnimationController ctl) {
                            if (searchUp) c.hide(WindowInsets.Type.statusBars());
                        }
                    });
        } catch (Throwable t) {
            c.hide(WindowInsets.Type.statusBars());
        }
    }

    private boolean searchUp;

    @Override
    public void searchShown(boolean shown) {
        searchUp = shown;
        imeTarget(shown);
        fadeHome(shown ? 0f : 1f);
        statusBar.setAlpha(shown ? 0f : 1f);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (shown) hideStatusNow(c);
        else applySystemBars();
    }

    private int lastDay = -1;

    private void checkDayChanged() {
        int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        if (lastDay != -1 && day != lastDay) {
            app.icons.invalidateAll();
            if (resumed && !dragging) rebuildPages();
            else needsRebuild = true;
        }
        lastDay = day;
    }

    public void launchPackage(String pkg, View src) {
        AppInfo a = app.apps.firstForPackage(pkg);
        if (a != null) {
            app.apps.launch(a, src, null);
            return;
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li != null) startSafe(li);
    }

    public void openFrame(String pkg, View src) {
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) {
            if (!app.frames.isLocked(pkg)) app.frames.close(pkg);
            return;
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        li.setPackage(null);

        launchFromFrame(li, src, rectOf(src), pkg);
        h.postDelayed(() -> {
            if (!resumed) app.frames.onForeground(pkg);
        }, 900);
    }

    private boolean remoteFrameLaunch(Intent li, View src, android.graphics.Rect from) {
        String pkg = li.getComponent() != null ? li.getComponent().getPackageName() : li.getPackage();
        Bundle b = frameLaunchOptions(src, from, pkg);
        if (b == null) return false;
        if (RemoteAnims.pendingFrameLive && pkg != null) {
            RemoteAnims.openPkg = pkg;
            RemoteAnims.openRequestedAt = android.os.SystemClock.uptimeMillis();
            RemoteAnims.openTaskId = -1;
            RemoteAnims.openSettled = false;
        }
        try {
            startActivity(li, b);
            return true;
        } catch (Throwable t) {
            clearFrameLaunch();
            return false;
        }
    }

    private void clearFrameLaunch() {
        RemoteAnims.pendingFrameLaunch = false;
        RemoteAnims.pendingFrameLive = false;
        RemoteAnims.zoomStartAt = 0;
        RemoteAnims.openSettled = true;
        hideZoom();
        RemoteAnims.dropLiveInfo();
    }

    private Bundle frameLaunchOptions(View src, android.graphics.Rect from, String pkg) {
        if (from == null || !RemoteAnims.available(this)) return null;
        RemoteAnims.pendingFrameLaunch = true;
        boolean blend = com.bb10.launcher.ui.ActiveFramesPage.showsLiveInfo(src)
                || com.bb10.launcher.ui.ActiveFramesPage.showsIconOnly(src);
        if (!blend && src != null && ownSplash(pkg) && startsAfresh(pkg)) blend = true;
        boolean own = blend && ownSplash(pkg) && src != null;
        RemoteAnims.dropLiveInfo();
        android.graphics.Bitmap look = blend ? liveInfoBitmap(src) : null;
        android.hardware.HardwareBuffer buf = look != null ? look.getHardwareBuffer() : null;
        if (buf != null) {
            RemoteAnims.pendingLiveSize = new android.graphics.Rect(0, 0, buf.getWidth(), buf.getHeight());
            android.view.SurfaceControl sc = own ? ownSplashLook(buf, src.getWidth(), src.getHeight(),
                    com.bb10.launcher.ui.ActiveFramesPage.showsIconOnly(src), pkg) : null;
            RemoteAnims.pendingLiveInfo = sc != null ? sc : RemoteAnims.liveInfoSurface(buf);
        }
        RemoteAnims.pendingFrameLive = RemoteAnims.pendingLiveInfo != null;
        Bundle b = com.bb10.launcher.quickstep.ShellTransitions.canLaunch(this)
                ? com.bb10.launcher.quickstep.ShellTransitions.launchOptions(this, from)
                : RemoteAnims.launchOptions(this, from);
        if (b == null) clearFrameLaunch();
        return b;
    }

    private boolean ownSplash(String pkg) {
        return pkg != null && android.os.Build.VERSION.SDK_INT >= 33 && prefs.bool(Prefs.OWN_SPLASH, false)
                && com.bb10.launcher.quickstep.OwnSplash.hasSplash(this, pkg);
    }

    private boolean startsAfresh(String pkg) {
        java.util.Map<String, Integer> ids = com.bb10.launcher.quickstep.Tasks.taskIds(this);
        boolean fresh = !ids.isEmpty() && !ids.containsKey(pkg);
        android.util.Log.i("BB10Open", "open: " + pkg + (fresh ? " has no task: starts afresh (JL10's splash ready)" : " has a task"));
        return fresh;
    }

    private static android.view.SurfaceControl ownSplashLook(android.hardware.HardwareBuffer buf, int frameW, int frameH,
                                                             boolean iconOnly, String pkg) {
        return com.bb10.launcher.quickstep.OwnSplash.make(buf, frameW,
                com.bb10.launcher.ui.ActiveFramesPage.iconRect(frameW, frameH), iconOnly, pkg,
                () -> LauncherApp.get().hiResIconForPackage(pkg, com.bb10.launcher.quickstep.OwnSplash.iconPx()));
    }

    private static android.os.Handler openHandler;

    private static android.os.Handler openHandler() {
        if (openHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("bb10-open", android.os.Process.THREAD_PRIORITY_FOREGROUND);
            t.start();
            openHandler = new android.os.Handler(t.getLooper());
        }
        return openHandler;
    }

    private void launchFromFrame(Intent li, View src, android.graphics.Rect from, String pkg) {
        if (remoteFrameLaunch(li, src, from)) return;
        if (prefs.bool(Prefs.FRAMES_LAUNCH, true) && overlayLaunch(li, from, pkg)) return;
        Bitmap snap = snapshot(src);
        Bundle opts = src == null ? null : snap != null
                ? ActivityOptions.makeThumbnailScaleUpAnimation(src, snap, 0, 0).toBundle()
                : ActivityOptions.makeScaleUpAnimation(src, 0, 0, src.getWidth(), src.getHeight()).toBundle();
        try {
            startActivity(li, opts);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public Drawable iconForPackage(String pkg) {
        return app.iconForPackage(pkg);
    }

    private void startSafe(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app available", Toast.LENGTH_SHORT).show();
        }
    }

    public void openSettings() {
        startSafe(new Intent(this, SettingsActivity.class));
    }

    public void openCompose() {
        List<ActionMenu.Item> m = new ArrayList<>();
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_text, Ui.TEXT), "Text Message",
                () -> startSafe(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_unread, Ui.TEXT), "Email",
                () -> startSafe(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")))));
        ActionMenu.show(overlayLayer, "Compose", Ui.glyph(this, R.drawable.ic_compose, Ui.BLUE), m);
    }

    public void requestNotificationAccess() {
        String cn = getPackageName() + "/" + HubListenerService.class.getName();
        RootShell.execAsync(r -> {
            if (!r.ok) startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            else h.postDelayed(() -> hubPage.refresh(), 800);
        }, "cmd notification allow_listener " + cn);
    }

    public void renameFolder(GridItem folder, Runnable after) {
        Dialogs.input(this, "Folder name", folder.name, "Folder", false, s -> {
            if (s == null || s.isEmpty()) return;
            folder.name = s;
            app.apps.save();
            rebuildPages();
            if (after != null) after.run();
        });
    }

    public void showItemOptions(GridItem g, GridItem parentFolder) {
        List<ActionMenu.Item> m = new ArrayList<>();
        if (g.type == GridItem.FOLDER) {
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_compose, Ui.TEXT), "Rename Folder", () -> renameFolder(g, null)));
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_ungroup, Ui.TEXT), "Remove Folder", () -> {
                app.apps.ungroup(g);
                rebuildPages();
            }));
            ActionMenu.show(overlayLayer, g.name, Ui.glyph(this, R.drawable.ic_folder, Ui.BLUE), m);
            return;
        }
        AppInfo a = app.apps.app(g.app);
        if (a == null) return;
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_compose, Ui.TEXT), "Rename", () ->
                Dialogs.input(this, "Rename", a.label(), a.originalLabel, true, s -> {
                    prefs.setLabel(a.key, s);
                    afterCustomise(a.key);
                })));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_image, Ui.TEXT), "Icon from Image", () -> pickImage(a.key)));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_palette, Ui.TEXT), "Icon from Icon Pack", () -> pickFromPack(a)));
        if (prefs.iconOverride(a.key) != null) {
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_reset, Ui.TEXT), "Reset Icon", () -> {
                prefs.setIconOverride(a.key, null);
                IconProvider.customFile(this, a.key).delete();
                afterCustomise(a.key);
            }));
        }
        char sc = prefs.shortcutFor(a.key);
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_keyboard, Ui.TEXT),
                sc == 0 ? "Keyboard Shortcut" : "Keyboard Shortcut (" + Character.toUpperCase(sc) + ")", () -> shortcutDialog(a)));
        if (parentFolder != null) {
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_ungroup, Ui.TEXT), "Remove from Folder", () -> {
                app.apps.removeFromFolder(parentFolder, a.key);
                closeAllOverlays();
                rebuildPages();
            }));
        }
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_hide, Ui.TEXT), "Hide App", () -> {
            closeAllOverlays();
            app.apps.hide(a.key);
            Toast.makeText(this, "Hidden. Unhide it in Settings › Hidden apps.", Toast.LENGTH_LONG).show();
        }));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_info, Ui.TEXT), "App Info", () ->
                startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null)))));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_delete, Ui.TEXT), "Uninstall", () -> uninstall(a)));
        ActionMenu.show(overlayLayer, a.label(), app.icons.get(a), m);
    }

    private void afterCustomise(String key) {
        app.icons.invalidate(key);
        rebuildPages();
        for (int i = 0; i < overlayLayer.getChildCount(); i++) {
            View v = overlayLayer.getChildAt(i);
            if (v instanceof FolderOverlay) ((FolderOverlay) v).refresh();
        }
    }

    private void uninstall(AppInfo a) {
        startSafe(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.pkg, null)));
    }

    private void shortcutDialog(AppInfo a) {
        char cur = prefs.shortcutFor(a.key);
        String msg = "Press a letter key. Holding that key on the home screen will open " + a.label() + "."
                + (cur != 0 ? "\n\nCurrent: " + Character.toUpperCase(cur) : "");
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("Keyboard shortcut")
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Remove", (x, y) -> prefs.setShortcut((char) 0, a.key))
                .create();
        d.setOnKeyListener((di, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int uc = ev.getUnicodeChar(0);
            if (uc > 0 && Character.isLetter(uc)) {
                char ch = Character.toLowerCase((char) uc);
                String prev = prefs.shortcutApp(ch);
                prefs.setShortcut(ch, a.key);
                String extra = "";
                if (prev != null && !prev.equals(a.key)) {
                    AppInfo pa = app.apps.app(prev);
                    if (pa != null) extra = " (replaces " + pa.label() + ")";
                }
                Toast.makeText(this, "Hold " + Character.toUpperCase(ch) + " to open " + a.label() + extra, Toast.LENGTH_LONG).show();
                di.dismiss();
                return true;
            }
            return false;
        });
        d.show();
    }

    private void pickImage(String key) {
        pendingIconKey = key;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_IMAGE);
        } catch (ActivityNotFoundException e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_IMAGE);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void pickFromPack(AppInfo a) {
        List<String[]> packs = new ArrayList<>();
        packs.add(new String[]{com.bb10.launcher.icons.BB10Icons.PACK_ID, com.bb10.launcher.icons.BB10Icons.PACK_LABEL});
        packs.addAll(IconPackManager.installed(this));
        if (packs.size() == 1) {
            openPicker(a, packs.get(0)[0]);
            return;
        }
        List<ActionMenu.Item> m = new ArrayList<>();
        for (String[] p : packs) {
            Drawable ic;
            try {
                ic = com.bb10.launcher.icons.BB10Icons.PACK_ID.equals(p[0])
                        ? com.bb10.launcher.icons.BB10Icons.drawable(this, "blackberry_world")
                        : getPackageManager().getApplicationIcon(p[0]);
            } catch (Exception e) {
                ic = null;
            }
            m.add(new ActionMenu.Item(ic, p[1], () -> openPicker(a, p[0])));
        }
        ActionMenu.show(overlayLayer, "Choose icon pack", null, m);
    }

    private void openPicker(AppInfo a, String pack) {
        Intent i = new Intent(this, IconPickerActivity.class)
                .putExtra(IconPickerActivity.EXTRA_KEY, a.key)
                .putExtra(IconPickerActivity.EXTRA_PACK, pack)
                .putExtra(IconPickerActivity.EXTRA_LABEL, a.label());
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        awaitingResult = false;
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            String key = data.getStringExtra(IconPickerActivity.EXTRA_KEY);
            if (key != null) afterCustomise(key);
        } else if (req == REQ_IMAGE && res == RESULT_OK && data != null && data.getData() != null && pendingIconKey != null) {
            String key = pendingIconKey;
            Uri uri = data.getData();
            pendingIconKey = null;
            app.bg.execute(() -> {
                boolean ok = saveCustomIcon(key, uri);
                h.post(() -> {
                    if (ok) {
                        prefs.setIconOverride(key, "file");
                        afterCustomise(key);
                    } else {
                        Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    private boolean saveCustomIcon(String key, Uri uri) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap b = ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (max > 1024) dec.setTargetSampleSize(max / 512);
            });
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
            Bitmap out = Bitmap.createScaledBitmap(sq, 192, 192, true);
            try (FileOutputStream os = new FileOutputStream(IconProvider.customFile(this, key))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void enterEditMode() {
        h.post(com.bb10.launcher.gesture.GestureService::refreshEdge);
        if (editMode) return;
        editMode = true;
        for (AppGridPage g : gridPages) {
            for (int i = 0; i < g.getChildCount(); i++) ((IconView) g.getChildAt(i)).setEdit(true);
        }
        int per = prefs.cols() * prefs.rows();
        AppGridPage extra = new AppGridPage(this, gridPages.size() * per, prefs.cols(), prefs.rows());
        extra.setOnClickListener(v -> exitEditMode());
        gridPages.add(extra);
        pager.addView(extra);
        indicator.setup(gridPages.size());
        updateBottomBar();
        Ui.tick(this);
    }

    private void exitEditMode() {
        h.post(com.bb10.launcher.gesture.GestureService::refreshEdge);
        if (!editMode) return;
        editMode = false;
        rebuildPages();
    }

    private void startDrag(IconView v, float rawX, float rawY) {
        if (dragging || v.item == null || v.index < 0) return;
        if (!editMode) enterEditMode();
        dragging = true;
        dragIndex = v.index;
        dragIsApp = v.item.type == GridItem.APP;
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        boolean wasEdit = true;
        v.setEdit(false);
        v.draw(new Canvas(b));
        v.setEdit(wasEdit);
        dragView = new ImageView(this);
        dragView.setImageBitmap(b);
        dragView.setScaleX(1.15f);
        dragView.setScaleY(1.15f);
        dragView.setAlpha(0.9f);
        dragLayer.addView(dragView, new FrameLayout.LayoutParams(v.getWidth(), v.getHeight()));
        v.setVisibility(View.INVISIBLE);
        moveDragView(rawX, rawY);
        Ui.tick(this);
    }

    private void moveDragView(float rawX, float rawY) {
        int[] loc = new int[2];
        dragLayer.getLocationOnScreen(loc);
        ViewGroup.LayoutParams lp = dragView.getLayoutParams();
        dragView.setX(rawX - loc[0] - lp.width / 2f);
        dragView.setY(rawY - loc[1] - lp.height / 2f - Ui.dp(16));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!dragging) return super.dispatchTouchEvent(ev);
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                moveDragView(x, y);
                AppGridPage p = currentGridPage();
                if (p != null) {
                    int[] pl = new int[2];
                    p.getLocationOnScreen(pl);
                    p.hover(x - pl[0], y - pl[1], dragIndex, dragIsApp);
                }
                int[] rl = new int[2];
                root.getLocationOnScreen(rl);
                int dir = x < rl[0] + Ui.dp(22) ? -1 : x > rl[0] + root.getWidth() - Ui.dp(22) ? 1 : 0;
                if (dir != flipDir) {
                    flipDir = dir;
                    h.removeCallbacks(flipRun);
                    if (dir != 0) h.postDelayed(flipRun, 550);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                endDrag(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                endDrag(false);
                break;
        }
        return true;
    }

    private AppGridPage currentGridPage() {
        View v = pager.getCurrentView();
        return v instanceof AppGridPage ? (AppGridPage) v : null;
    }

    private void doFlip() {
        if (!dragging || flipDir == 0) return;
        int next = pager.getCurrentPage() + flipDir;
        if (next >= gridStart && next < pager.getChildCount()) {
            AppGridPage old = currentGridPage();
            if (old != null) old.clearHover();
            pager.setCurrentPage(next, true);
        }
        h.postDelayed(flipRun, 900);
    }

    private void endDrag(boolean drop) {
        h.removeCallbacks(flipRun);
        flipDir = 0;
        AppGridPage p = currentGridPage();
        if (drop && p != null) {
            int mode = p.dropMode(), target = p.dropGlobal();
            if (mode == AppGridPage.MERGE) app.apps.merge(dragIndex, target);
            else if (mode == AppGridPage.INSERT) app.apps.move(dragIndex, target);
        }
        for (AppGridPage g : gridPages) g.clearHover();
        if (dragView != null) dragLayer.removeView(dragView);
        dragView = null;
        dragging = false;
        dragIndex = -1;
        rebuildPages();
    }

    private boolean closeTopOverlay() {
        int n = overlayLayer.getChildCount();
        if (n == 0) return false;
        View v = overlayLayer.getChildAt(n - 1);
        if (v instanceof Closable) ((Closable) v).close();
        else overlayLayer.removeView(v);
        return true;
    }

    private void closeAllOverlays() {
        if (overlayLayer.getChildCount() > 0) {
            for (int i = 0; i < overlayLayer.getChildCount(); i++)
                if (overlayLayer.getChildAt(i) instanceof SearchOverlay) ((SearchOverlay) overlayLayer.getChildAt(i)).release();
            overlayLayer.removeAllViews();
        }
    }

    private void openSearch(String initial) {
        SearchOverlay s = null;
        for (int i = 0; i < overlayLayer.getChildCount(); i++) {
            if (overlayLayer.getChildAt(i) instanceof SearchOverlay) s = (SearchOverlay) overlayLayer.getChildAt(i);
        }
        if (s == null) {
            s = new SearchOverlay(this, this);
            overlayLayer.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            s.setImeInset(imeInset);
        }
        s.setQuery(initial);
    }

    private void showMainMenu() {
        int t = pageType(pager.getCurrentPage());
        if (t == PT_HUB) {
            hubPage.showMenu();
            return;
        }
        List<ActionMenu.Item> m = new ArrayList<>();
        if (t == PT_FRAMES) {
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_close, Ui.TEXT), "Close All", () -> app.frames.closeAll()));
        } else {
            m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_compose, Ui.TEXT), "Edit Home Screen", this::enterEditMode));
        }
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_search, Ui.TEXT), "Search", () -> openSearch("")));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_image, Ui.TEXT), "Wallpaper", () ->
                startSafe(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper"))));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_settings, Ui.TEXT), "Launcher Settings", this::openSettings));
        m.add(new ActionMenu.Item(Ui.glyph(this, R.drawable.ic_settings, Ui.TEXT), "System Settings", () ->
                startSafe(new Intent(Settings.ACTION_SETTINGS))));
        ActionMenu.show(overlayLayer, null, null, m);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (dragging) return;
        if (closeTopOverlay()) return;
        if (editMode) {
            exitEditMode();
            return;
        }
        if (pager.getCurrentPage() == 0 && hubPage.onBack()) return;
        if (pager.getCurrentPage() == 0 && returnPkg != null) {
            String p = returnPkg;
            returnPkg = null;
            openFrame(p, null);
            return;
        }
        goDefaultPage(true);
    }

    private int lastPadKey;
    private long lastPadAt;

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.getRepeatCount() == 0) {
                if (!closeTopOverlay()) showMainMenu();
            }
            return true;
        }
        if (overlayLayer.getChildCount() > 0 || dragging) return super.onKeyDown(code, e);

        int page = pager.getCurrentPage();
        if (page == 0 && !editMode) {
            if (hubPage.onKey(code, e)) return true;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                pager.setCurrentPage(framesIndex, true);
                focusPage();
                return true;
            }
        }

        if (page > 0 && !editMode && padDirection(code) != 0) {
            int dir = padDirection(code);
            View cur = pager.getCurrentView();
            View f = getCurrentFocus();
            if (cur == null) return true;
            if (f != null && isInside(bottomBar, f)) {
                dockKey(dir, f, cur);
                return true;
            }
            if (f == null || !isInside(cur, f)) {
                focusPage();
                return true;
            }
            View next = f.focusSearch(dir);
            if (next != null && isInside(cur, next)) {
                next.requestFocus(dir);
                return true;
            }
            if (dir == View.FOCUS_DOWN) {
                focusDock(f);
                return true;
            }
            boolean left = dir == View.FOCUS_LEFT, right = dir == View.FOCUS_RIGHT;
            if (left || (right && page < pager.getChildCount() - 1)) {
                int to = left ? page - 1 : page + 1;
                if (to == 0) {
                    pager.setCurrentPage(0, true);
                    return true;
                }
                enterPage(to, dir, f, cur);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            openSearch("");
            return true;
        }
        if (page == 0 || editMode) return super.onKeyDown(code, e);

        if (code == KeyEvent.KEYCODE_SPACE) {
            if (e.getRepeatCount() == 0) {
                e.startTracking();
                trackedKey = code;
                trackedChar = ' ';
                longPressed = false;
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (e.getRepeatCount() == 0) altLatched = !altLatched;
            return true;
        }
        boolean alt = e.isAltPressed() || altLatched;
        int uc = e.getUnicodeChar(alt ? e.getMetaState() | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON : e.getMetaState());
        if (alt && e.getRepeatCount() == 0) {
            altLatched = false;
            if (uc == 0 || Character.isLetter(uc)) {
                char m = bbAltChar(code);
                if (m != 0) uc = m;
            }
        }
        if (uc > 0 && !Character.isISOControl(uc) && !Character.isWhitespace(uc) && prefs.bool(Prefs.KB_SEARCH, true)) {
            if (e.getRepeatCount() > 0) return true;
            if (trackedKey != -1 && trackedKey != code && !longPressed && trackedChar != ' ') {
                String first = String.valueOf(trackedChar);
                trackedKey = -1;
                openSearch(first + (char) uc);
                return true;
            }
            if (prefs.bool(Prefs.KB_SHORTCUTS, true) && Character.isLetter(uc) && !alt) {
                e.startTracking();
                trackedKey = code;
                trackedChar = (char) uc;
                longPressed = false;
                return true;
            }
            openSearch(String.valueOf((char) uc));
            return true;
        }
        if (uc > 0 && Character.isLetter(uc) && prefs.bool(Prefs.KB_SHORTCUTS, true) && e.getRepeatCount() == 0) {
            e.startTracking();
            trackedKey = code;
            trackedChar = (char) uc;
            longPressed = false;
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private boolean altLatched;

    private static char bbAltChar(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_Q: return '#';
            case KeyEvent.KEYCODE_W: return '1';
            case KeyEvent.KEYCODE_E: return '2';
            case KeyEvent.KEYCODE_R: return '3';
            case KeyEvent.KEYCODE_T: return '(';
            case KeyEvent.KEYCODE_Y: return ')';
            case KeyEvent.KEYCODE_U: return '_';
            case KeyEvent.KEYCODE_I: return '-';
            case KeyEvent.KEYCODE_O: return '+';
            case KeyEvent.KEYCODE_P: return '@';
            case KeyEvent.KEYCODE_A: return '*';
            case KeyEvent.KEYCODE_S: return '4';
            case KeyEvent.KEYCODE_D: return '5';
            case KeyEvent.KEYCODE_F: return '6';
            case KeyEvent.KEYCODE_G: return '/';
            case KeyEvent.KEYCODE_H: return ':';
            case KeyEvent.KEYCODE_J: return ';';
            case KeyEvent.KEYCODE_K: return '\'';
            case KeyEvent.KEYCODE_L: return '"';
            case KeyEvent.KEYCODE_Z: return '7';
            case KeyEvent.KEYCODE_X: return '8';
            case KeyEvent.KEYCODE_C: return '9';
            case KeyEvent.KEYCODE_V: return '?';
            case KeyEvent.KEYCODE_B: return '!';
            case KeyEvent.KEYCODE_N: return ',';
            case KeyEvent.KEYCODE_M: return '.';
            case KeyEvent.KEYCODE_0: return '0';
            default: return 0;
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == trackedKey) {
            longPressed = true;
            if (trackedChar == ' ') {
                Ui.tick(this);
                startVoiceAssistant();
                return true;
            }
            String key = prefs.shortcutApp(trackedChar);
            if (key != null && app.apps.app(key) != null) {
                Ui.tick(this);
                launchApp(key, null);
            } else {
                Toast.makeText(this, "No app assigned to " + String.valueOf(trackedChar).toUpperCase(Locale.ROOT)
                        + ". Assign one in edit mode (tap an icon › Keyboard Shortcut).", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == trackedKey) {
            trackedKey = -1;
            if (!longPressed && !e.isCanceled() && trackedChar != ' ' && prefs.bool(Prefs.KB_SEARCH, true)) openSearch(String.valueOf(trackedChar));
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void startVoiceAssistant() {
        Intent[] tries = {
                new Intent(Intent.ACTION_VOICE_COMMAND),
                new Intent(Intent.ACTION_ASSIST),
                new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "No voice assistant installed", Toast.LENGTH_SHORT).show();
    }

    private static int padDirection(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
        }
        return 0;
    }

    private static boolean isInside(View parent, View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return v == parent;
    }

    private void enterPage(int to, int dir, View from, View fromPage) {
        android.graphics.Rect r = new android.graphics.Rect();
        from.getDrawingRect(r);
        ((ViewGroup) fromPage).offsetDescendantRectToMyCoords(from, r);
        pager.setCurrentPage(to, true);
        View page = pager.getChildAt(to);
        h.post(() -> {
            View next = null;
            if (page instanceof ViewGroup) {
                if (dir == View.FOCUS_RIGHT) r.offsetTo(-r.width(), r.top);
                else r.offsetTo(page.getWidth(), r.top);
                next = android.view.FocusFinder.getInstance().findNextFocusFromRect((ViewGroup) page, r, dir);
            }
            if (next != null) next.requestFocus(dir);
            else focusPage();
        });
    }
    private List<View> dockButtons() {
        List<View> l = new ArrayList<>();
        for (int i = 0; i < bottomBar.getChildCount(); i++) {
            View v = bottomBar.getChildAt(i);
            if (v instanceof BarButton && v.getVisibility() == View.VISIBLE) l.add(v);
        }
        return l;
    }

    private static float centerX(View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return xy[0] + v.getWidth() / 2f;
    }

    private void focusDock(View from) {
        float x = centerX(from);
        View best = null;
        for (View b : dockButtons()) if (best == null || Math.abs(centerX(b) - x) < Math.abs(centerX(best) - x)) best = b;
        if (best != null) best.requestFocus();
    }

    private void dockKey(int dir, View f, View page) {
        List<View> l = dockButtons();
        int i = l.indexOf(f);
        if (dir == View.FOCUS_LEFT && i > 0) l.get(i - 1).requestFocus();
        else if (dir == View.FOCUS_RIGHT && i >= 0 && i < l.size() - 1) l.get(i + 1).requestFocus();
        else if (dir == View.FOCUS_UP && page instanceof ViewGroup) {
            int[] pxy = new int[2], fxy = new int[2];
            page.getLocationOnScreen(pxy);
            f.getLocationOnScreen(fxy);
            int x = fxy[0] - pxy[0];
            android.graphics.Rect r = new android.graphics.Rect(x, page.getHeight(), x + f.getWidth(), page.getHeight() + 1);
            View next = android.view.FocusFinder.getInstance().findNextFocusFromRect((ViewGroup) page, r, View.FOCUS_UP);
            if (next != null) next.requestFocus(View.FOCUS_UP);
            else focusPage();
        }
    }

    private void focusPage() {
        h.post(() -> {
            View v = pager.getCurrentView();
            if (v == framesPage) framesPage.focusFirst();
            else if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) ((ViewGroup) v).getChildAt(0).requestFocus();
            else if (v != null) v.requestFocus();
        });
    }
}