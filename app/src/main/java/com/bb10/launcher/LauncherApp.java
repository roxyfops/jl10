package com.bb10.launcher;

import android.app.Application;
import android.preference.PreferenceManager;

import com.bb10.launcher.frames.FrameTracker;
import com.bb10.launcher.hub.HubStore;
import com.bb10.launcher.icons.IconPackManager;
import com.bb10.launcher.icons.IconProvider;
import com.bb10.launcher.model.AppRepository;
import com.bb10.launcher.ui.Ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LauncherApp extends Application {
    private static LauncherApp sApp;

    public final ExecutorService bg = Executors.newFixedThreadPool(2, r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "jl10-bg"));
    public Prefs prefs;
    public IconProvider icons;
    public AppRepository apps;
    public HubStore hub;
    public FrameTracker frames;
    public com.bb10.launcher.frames.LiveFrames live;

    public static LauncherApp get() {
        return sApp;
    }

    public android.graphics.drawable.Drawable iconForPackage(String pkg) {
        android.graphics.drawable.Drawable mail = com.bb10.launcher.mail.Mail.icon(this, pkg);
        if (mail != null) return mail;
        if (com.bb10.launcher.hub.HubKinds.NOTIF.equals(pkg)) return bell();
        if (com.bb10.launcher.hub.HubKinds.CALLS.equals(pkg)) pkg = com.bb10.launcher.hub.CallLogHub.dialer(this);
        com.bb10.launcher.model.AppInfo a = apps.firstForPackage(pkg);
        if (a != null) {
            android.graphics.drawable.Drawable d = icons.get(a);
            android.graphics.drawable.Drawable.ConstantState cs = d == null ? null : d.getConstantState();
            return cs != null ? cs.newDrawable(getResources()).mutate() : d;
        }
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return bell();
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return Ui.glyph(this, R.drawable.ic_hub, Ui.TEXT2);
        }
    }

    private android.graphics.drawable.Drawable bell() {
        return Ui.glyph(this, R.drawable.ic_bell, BELL_NEW);
    }

    public static final int BELL_NEW = 0xFFF6C61C, BELL_READ = 0xFF8C8C8C;

    public boolean isSystemNotifier(String pkg) {
        if (pkg == null || pkg.contains(":") || apps.firstForPackage(pkg) != null) return false;
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public android.graphics.drawable.Drawable hiResIconForPackage(String pkg, int px) {
        try {
            com.bb10.launcher.model.AppInfo a = apps.firstForPackage(pkg);
            if (a != null) return icons.hiRes(a, px);
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                android.graphics.drawable.Drawable d = pm.getResourcesForApplication(ai)
                        .getDrawableForDensity(ai.icon, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            }
            return pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) Runtime.getRuntime().gc();
    }

    private boolean initialized;

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        CrashGuard.install(this);
        com.bb10.launcher.quickstep.RemoteAnims.hiddenApisOk();
        android.os.UserManager um = getSystemService(android.os.UserManager.class);
        if (um == null || um.isUserUnlocked()) {
            ensureInit();
        } else {
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    try {
                        unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    ensureInit();
                }
            }, new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED));
        }
    }

    @SuppressWarnings("deprecation")
    public static final String ACTION_PREPARE_FRAME = "com.bb10.launcher.PREPARE_FRAME";
    public static final String ACTION_UNPREPARE_FRAME = "com.bb10.launcher.UNPREPARE_FRAME";

    public synchronized void ensureInit() {
        if (initialized) return;
        initialized = true;
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false);
        Ui.init(this);
        prefs = new Prefs(this);
        boolean earlierInstall = prefs.sp.contains("layout_version");
        if (earlierInstall && !prefs.sp.contains("setup_opened")) prefs.sp.edit().putBoolean("setup_opened", true).apply();
        RootShell.held = !prefs.sp.getBoolean("setup_opened", false);
        if (CrashGuard.crashLooping(this)) {
            prefs.sp.edit().putBoolean(Prefs.SYSUI_STATUSBAR, false).putBoolean(Prefs.SYSUI_SHADE, false)
                    .putBoolean(Prefs.SYSUI_VOLUME, false).putBoolean("sysui_keyguard", false)
                    .putBoolean(Prefs.INSTANT_PREVIEWS, false).putBoolean("gestures_enabled", false)
                    .putBoolean("safe_mode_notice", true).commit();        }
        if (prefs.sp.getInt("layout_version", 1) < 3) {
            prefs.sp.edit().putString(Prefs.GRID_COLS, "5").putString(Prefs.GRID_ROWS, "3").putInt("layout_version", 3).apply();
        }
        if (prefs.sp.getInt("layout_version", 1) < 4) {
            prefs.sp.edit().putString(Prefs.FRAMES_MAX, "0").putInt("layout_version", 4).apply();
        }
        if (prefs.sp.getInt("layout_version", 1) < 5) {
            prefs.sp.edit().putBoolean(Prefs.FRAMES_KILL, false).putInt("layout_version", 5).apply();
        }
        boolean undoOldLock = false;
        if (prefs.sp.getInt("layout_version", 1) < 6) {
            android.content.SharedPreferences.Editor e = prefs.sp.edit();
            android.content.ContentResolver cr = getContentResolver();
            String[][] keep = {{Prefs.SYSUI_STATUSBAR, "bb10_sb"}, {Prefs.SYSUI_SHADE, "bb10_shade"}, {Prefs.SYSUI_VOLUME, "bb10_volume"}};
            for (String[] k : keep)
                if (!prefs.sp.contains(k[0])) e.putBoolean(k[0], "1".equals(android.provider.Settings.Global.getString(cr, k[1])));
            if (!prefs.sp.contains(Prefs.INSTANT_PREVIEWS))
                e.putBoolean(Prefs.INSTANT_PREVIEWS, android.provider.Settings.Global.getInt(cr, "heads_up_notifications_enabled", 1) == 0);
            undoOldLock = prefs.sp.getBoolean(Prefs.LOCKSCREEN, false);
            e.remove(Prefs.LOCKSCREEN);
            e.putInt("layout_version", 6).apply();
        }
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && !prefs.sp.contains("qs_module_installed"))
            prefs.sp.edit().putBoolean("qs_module_installed", true).putLong("qs_module_stamp", 0).apply();
        com.bb10.launcher.settings.SettingsTools.refreshModuleIfUpdated(this, prefs.sp);
        com.bb10.launcher.quickstep.ShellTransitions.debug = prefs.bool(Prefs.DEBUG, false);
        com.bb10.launcher.ui.StatusBarView.scale = prefs.scale(Prefs.STATUS_SCALE);
        icons = new IconProvider(this, prefs);
        apps = new AppRepository(this, prefs);
        hub = new HubStore(this, prefs);
        com.bb10.launcher.mail.Mail.INSTANCE.init(this);
        com.bb10.launcher.hub.CallLogHub.start(this);
        com.bb10.launcher.caldav.CalDav.INSTANCE.init(this);
        frames = new FrameTracker(this, prefs);
        live = new com.bb10.launcher.frames.LiveFrames(this);
        android.content.IntentFilter pf = new android.content.IntentFilter(ACTION_PREPARE_FRAME);
        pf.addAction(ACTION_UNPREPARE_FRAME);
        android.content.BroadcastReceiver prepare = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent i) {
                String pkg = i.getStringExtra("pkg");
                if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return;
                if (com.bb10.launcher.hub.HubStore.isMailPkg(pkg)) return;
                boolean undo = ACTION_UNPREPARE_FRAME.equals(i.getAction());
                if (undo) frames.unprepare(pkg);
                else frames.prepareFirst(pkg);
                @SuppressWarnings("deprecation") android.app.PendingIntent target = i.getParcelableExtra("open_pi");
                if (undo) LauncherActivity.undoOpenFromLockScreen(pkg);
                else LauncherActivity.prepareOpenFromLockScreen(pkg, target, i.getStringExtra("notif_key"), i.getStringExtra("hub_item"));
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(prepare, pf, "android.permission.STATUS_BAR_SERVICE", null, RECEIVER_EXPORTED);
        else registerReceiver(prepare, pf, "android.permission.STATUS_BAR_SERVICE", null);
        IconPackManager.rescanAsync(this);
        prefs.sp.registerOnSharedPreferenceChangeListener(headsUpPref);
        getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor(HEADS_UP), false,
                new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean self) {
                        applyHeadsUp();
                    }
                });
        applyHeadsUp();
        syncSysUi();
        applyLockSettings(undoOldLock);
    }

    public void applyLockSettings(boolean changed) {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km == null || km.isDeviceSecure()) return;
        boolean bb10 = false;
        if (!bb10 && !changed) return;
        RootShell.execAsync(null, "locksettings set-disabled " + (bb10 ? "true" : "false"));
    }

    private static final String HEADS_UP = "heads_up_notifications_enabled";

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener headsUpPref = (sp, key) -> {
        if (Prefs.INSTANT_PREVIEWS.equals(key)) applyHeadsUp();
        if (Prefs.DEBUG.equals(key)) {
            com.bb10.launcher.quickstep.ShellTransitions.debug = prefs.bool(Prefs.DEBUG, false);
            syncSysUi();
        }
        if (Prefs.SYSUI_KEYGUARD.equals(key) || Prefs.PW_AUTO.equals(key) || Prefs.HUB_THEME.equals(key) || Prefs.LOCK_MUSIC_TINT.equals(key) || Prefs.LOCK_COLUMN.equals(key)) applyLockSettings(true);
        if (Prefs.STATUS_SCALE.equals(key)) com.bb10.launcher.ui.StatusBarView.scale = prefs.scale(Prefs.STATUS_SCALE);
        if (Prefs.SYSUI_STATUSBAR.equals(key) || Prefs.SYSUI_SHADE.equals(key) || Prefs.PULL_ANDROID.equals(key) || Prefs.NET_SPEED.equals(key) || Prefs.ALARM_ICON.equals(key) || Prefs.CLOCK_SECONDS.equals(key) || Prefs.QS_BRIGHTNESS.equals(key) || Prefs.BATTERY_PERCENT.equals(key) || Prefs.NOTIF_ICONS.equals(key) || Prefs.CLOCK_24.equals(key) || Prefs.BB_LOGO.equals(key) || Prefs.BB_CARRIER.equals(key) || Prefs.QS_TILES.equals(key) || Prefs.SYSUI_VOLUME.equals(key) || Prefs.SYSUI_KEYGUARD.equals(key) || Prefs.PW_AUTO.equals(key) || Prefs.HUB_THEME.equals(key) || Prefs.LOCK_MUSIC_TINT.equals(key) || Prefs.LOCK_COLUMN.equals(key) || Prefs.LOCK_CAMERA.equals(key)
                || Prefs.LOCK_TIMEOUT.equals(key) || Prefs.LOCK_SCALE.equals(key) || Prefs.STATUS_SCALE.equals(key) || Prefs.HIDE_STATUS_APPS.equals(key))
            syncSysUi();
    };

    private String lockTimeout() {
        String v = prefs.sp.getString(Prefs.LOCK_TIMEOUT, "default");
        if ("screen".equals(v)) return "-1";
        try {
            return String.valueOf(Math.max(0, Math.min(600_000, Integer.parseInt(v.trim()))));
        } catch (Exception e) {
            return "0";
        }
    }

    private static String num(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    public void syncSysUi() {
        addNewTiles();
        String[][] keys = {
                {"bb10_sb", prefs.bool(Prefs.SYSUI_STATUSBAR, false) ? "1" : "0"},
                {"bb10_shade", prefs.bool(Prefs.SYSUI_SHADE, false) && !prefs.bool(Prefs.PULL_ANDROID, false) ? "1" : "0"},
                {"bb10_clock24", prefs.bool(Prefs.CLOCK_24, false) ? "1" : "0"},
                {"bb10_logo", prefs.bool(Prefs.BB_LOGO, true) ? "1" : "0"},
                {"bb10_carrier", prefs.bool(Prefs.BB_CARRIER, true) ? "1" : "0"},
                {"bb10_volume", prefs.bool(Prefs.SYSUI_VOLUME, false) ? "1" : "0"},
                {"bb10_keyguard", prefs.bool(Prefs.SYSUI_KEYGUARD, false) ? "1" : "0"},
                {"bb10_pw_auto", pwAuto()},
                {"bb10_lock_music_tint", prefs.bool(Prefs.LOCK_MUSIC_TINT, true) ? "1" : "0"},
                {"bb10_lock_column", prefs.sp.getString(Prefs.LOCK_COLUMN, "center")},
                {"bb10_lock_camera", prefs.bool(Prefs.LOCK_CAMERA, true) ? "1" : "0"},
                {Ui.FONT_KEY, fontStamp()},
                {"bb10_hub_theme", prefs.sp.getString(Prefs.HUB_THEME, "bright")},
                {"bb10_qs_tiles", prefs.sp.getString(Prefs.QS_TILES, com.bb10.launcher.sysui.BbTiles.DEFAULT)},
                {"bb10_debug", prefs.bool(Prefs.DEBUG, false) ? "1" : "0"},
                {"bb10_net_speed", prefs.bool(Prefs.NET_SPEED, false) ? "1" : "0"},
                {"bb10_alarm_icon", prefs.bool(Prefs.ALARM_ICON, true) ? "1" : "0"},
                {"bb10_clock_seconds", prefs.bool(Prefs.CLOCK_SECONDS, false) ? "1" : "0"},
                {"bb10_qs_brightness", prefs.bool(Prefs.QS_BRIGHTNESS, true) ? "1" : "0"},
                {"bb10_battery_percent", prefs.bool(Prefs.BATTERY_PERCENT, false) ? "1" : "0"},
                {"bb10_notif_icons", prefs.bool(Prefs.NOTIF_ICONS, true) ? "1" : "0"},
                {"bb10_lock_timeout", lockTimeout()},
                {"bb10_lock_scale", num(prefs.scale(Prefs.LOCK_SCALE))},
                {"bb10_status_scale", num(prefs.scale(Prefs.STATUS_SCALE))},
                {com.bb10.launcher.sysui.NoStatusBar.KEY, prefs.bool(Prefs.HIDE_STATUS_APPS, false) ? "1" : "0"},
        };
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (String[] k : keys) {
            if (!k[1].equals(android.provider.Settings.Global.getString(getContentResolver(), k[0]))) {
                cmds.add("settings put global " + k[0] + " '" + k[1] + "'");
            }
        }
        if (!cmds.isEmpty()) RootShell.execAsync(null, cmds.toArray(new String[0]));
    }

    private void addNewTiles() {
        String saved = prefs.sp.getString(Prefs.QS_TILES, null);
        if (saved == null || prefs.sp.getBoolean("qs_tiles_v3", false)) return;
        java.util.List<String> tiles = com.bb10.launcher.sysui.BbTiles.parse(saved);
        for (String t : new String[]{"devicemonitor", "screenrecord"}) if (!tiles.contains(t)) tiles.add(t);
        prefs.sp.edit().putString(Prefs.QS_TILES, android.text.TextUtils.join(",", tiles)).putBoolean("qs_tiles_v3", true).apply();
    }

    public String fontStamp() {
        java.io.File f = Ui.fontFile(this);
        return f.exists() ? String.valueOf(f.lastModified()) : "";
    }

    private String pwAuto() {
        try {
            int n = Integer.parseInt(prefs.sp.getString(Prefs.PW_AUTO, "0").trim());
            return String.valueOf(Math.max(0, Math.min(64, n)));
        } catch (Exception e) {
            return "0";
        }
    }

    public void applyHeadsUp() {
        int want = prefs.bool(Prefs.INSTANT_PREVIEWS, false) ? 0 : 1;
        int cur = android.provider.Settings.Global.getInt(getContentResolver(), HEADS_UP, 1);
        if (cur == want) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - headsUpWindow > 60_000) {
            headsUpWindow = now;
            headsUpTries = 0;
        }
        if (++headsUpTries > 3) {
            if (headsUpTries == 4) android.util.Log.w("JL10", "heads-up keeps being turned back on; leaving it for now");
            return;
        }
        RootShell.execAsync(null, "settings put global " + HEADS_UP + " " + want);
    }

    private long headsUpWindow;
    private int headsUpTries;
}
