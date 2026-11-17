package com.bb10.launcher.sysui;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.LayoutInflater;
import android.view.View;

import com.bb10.launcher.R;
import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

final class SysUi {
    static final String PKG = "com.bb10.launcher";
    static final String K_SB = "bb10_sb", K_SHADE = "bb10_shade", K_CLOCK24 = "bb10_clock24", K_LOGO = "bb10_logo", K_CARRIER = "bb10_carrier", K_VOLUME = "bb10_volume";
    static final String K_STATUS = "bb10_sysui_status";
    static final String K_TILES = "bb10_qs_tiles";

    static final Handler main = new Handler(Looper.getMainLooper());
    static ClassLoader cl;
    static Context sys;
    static Resources res;

    static volatile boolean lockShowing;

    static boolean securelyLocked() {
        try {
            android.app.KeyguardManager km = sys.getSystemService(android.app.KeyguardManager.class);
            return km != null && km.isKeyguardLocked() && km.isKeyguardSecure();
        } catch (Throwable t) {
            return false;
        }
    }

    static final String SENDER_PERM = "com.bb10.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
    static volatile boolean sbOn, shadeOn, clock24, logo = true, carrier = true, volumeOn = true;

    static final Set<Object> hosts = Collections.newSetFromMap(new WeakHashMap<>());
    static final Set<Object> ours = Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile int darkLevel;
    static float dark;

    static void setDark(float intensity, int level) {
        if (level < darkLevel) return;
        darkLevel = level;
        main.post(() -> {
            if (dark == intensity) return;
            boolean flip = (dark > 0.5f) != (intensity > 0.5f);
            dark = intensity;
            if (!flip) return;
            for (Object o : new ArrayList<>(ours)) {
                if (o instanceof StatusBarView) {
                    ((StatusBarView) o).darkIntensity = intensity;
                    ((StatusBarView) o).invalidate();
                }
            }
        });
    }

    private static final Map<String, StatusBarNotification> notifs = new LinkedHashMap<>();
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final List<String> log = new ArrayList<>();

    private SysUi() {}

    static synchronized void init(Context any) {
        if (sys != null) return;
        Context app = any.getApplicationContext();
        sys = app != null ? app : any;
        try {
            res = sys.getPackageManager().getResourcesForApplication(PKG);
            Ui.init(wrap(sys));
            note("BB10 font: " + Ui.fontStatus);
        } catch (Throwable t) {
            note("can't load JL10 resources: " + t);
        }
        StatusBarView.env = ENV;
        readSettings();
        ContentObserver obs = new ContentObserver(main) {
            @Override
            public void onChange(boolean self) {
                readSettings();
                changed();
                KeyguardHook.update();
            }
        };
        for (String k : new String[]{K_SB, K_SHADE, K_CLOCK24, K_LOGO, K_CARRIER, K_VOLUME, KeyguardHook.K_KEYGUARD, KeyguardHook.K_AUTO, K_DEBUG, K_NET_SPEED, K_ALARM_ICON, K_SECONDS, K_BRIGHTNESS, K_BATTERY_PCT, K_NOTIF_ICONS, KeyguardView.K_CAMERA,
                K_LOCK_TIMEOUT, K_LOCK_SCALE, K_STATUS_SCALE})
            sys.getContentResolver().registerContentObserver(Settings.Global.getUriFor(k), false, obs);
        IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                ShadePanel.closeIfOpen(false);
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) KeyguardHook.onScreenOff();
            }
        }, f, Context.RECEIVER_EXPORTED);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                boolean showing = i.getBooleanExtra("showing", false);
                barAlpha(showing ? 0f : 1f, showing ? 120 : 200);
                main.removeCallbacks(restoreBar);
                if (showing) main.postDelayed(restoreBar, 60000);
            }
        }, new IntentFilter("com.bb10.launcher.PREVIEW"), SENDER_PERM, null, Context.RECEIVER_EXPORTED);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                lockShowing = i.getBooleanExtra("showing", false);
                if (lockShowing) ShadePanel.closeIfOpen(false);
            }
        }, new IntentFilter("com.bb10.launcher.LOCK"), SENDER_PERM, null, Context.RECEIVER_EXPORTED);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                setPeekPercent(i.getBooleanExtra("on", false));
                main.removeCallbacks(peekOver);
                if (i.getBooleanExtra("on", false)) main.postDelayed(peekOver, 30_000);
            }
        }, new IntentFilter("com.bb10.launcher.PEEK"), SENDER_PERM, null, Context.RECEIVER_EXPORTED);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                boolean showing = i.getBooleanExtra("showing", false);
                SysUiHook.setSplashWhite(showing);
                main.removeCallbacks(splashOver);
                if (showing) main.postDelayed(splashOver, 10_000);
            }
        }, new IntentFilter("com.bb10.launcher.SPLASH"), SENDER_PERM, null, Context.RECEIVER_EXPORTED);
        IntentFilter lf = new IntentFilter(Intent.ACTION_USER_PRESENT);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(android.telephony.SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        lf.addAction(android.telephony.TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                ImsWatch.watch();
                changed();
            }
        }, lf);
        ImsWatch.watch();
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                setClosedMedia(i.getStringArrayExtra("pkgs"));
            }
        }, new IntentFilter("com.bb10.launcher.MEDIA_CLOSED"), SENDER_PERM, null, Context.RECEIVER_EXPORTED);
        new Thread(() -> {
            Bundle b = SplashHook.callLauncher("media_closed", null);
            if (b != null) setClosedMedia(b.getStringArray("pkgs"));
        }, "bb10-media-closed").start();
        IntentFilter sf = new IntentFilter(Intent.ACTION_SHUTDOWN);
        sf.addAction(Intent.ACTION_REBOOT);
        sys.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                shutdownSeen = true;
                VolumeHud.dismissNow();
            }
        }, sf, Context.RECEIVER_EXPORTED);
        note("System UI hooked; status bar " + (sbOn ? "on" : "off") + ", shade " + (shadeOn ? "on" : "off"));
    }

    private static final Map<String, Long> closedMedia = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean closedMedia(String pkg) {
        return pkg != null && closedMedia.containsKey(pkg);
    }

    static void setClosedMedia(String[] pkgs) {
        if (pkgs == null) return;
        java.util.Set<String> now = new java.util.HashSet<>(java.util.Arrays.asList(pkgs));
        closedMedia.keySet().retainAll(now);
        for (String p : now) closedMedia.putIfAbsent(p, android.os.SystemClock.uptimeMillis());
        changed();
    }

    static boolean hiddenPlayer(String pkg, boolean playing) {
        Long at = pkg == null ? null : closedMedia.get(pkg);
        if (at == null) return false;
        if (!playing || android.os.SystemClock.uptimeMillis() - at < 3000) return true;
        closedMedia.remove(pkg);
        new Thread(() -> {
            Bundle b = new Bundle();
            b.putString("pkg", pkg);
            SplashHook.callLauncher("media_playing", b);
        }, "bb10-media-back").start();
        return false;
    }

    private static volatile boolean shutdownSeen;

    static void markShutdown() {
        shutdownSeen = true;
        VolumeHud.dismissNow();
    }

    static boolean shuttingDown() {
        if (shutdownSeen) return true;
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "sys.shutdown.requested");
            if (v instanceof String && !((String) v).isEmpty()) {
                shutdownSeen = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final Runnable restoreBar = () -> barAlpha(1f, 200);
    private static final Runnable splashOver = () -> SysUiHook.setSplashWhite(false);

    private static final Runnable peekOver = () -> setPeekPercent(false);

    private static void setPeekPercent(boolean on) {
        for (Object o : new ArrayList<>(ours)) {
            if (o instanceof com.bb10.launcher.ui.StatusBarView) {
                com.bb10.launcher.ui.StatusBarView b = (com.bb10.launcher.ui.StatusBarView) o;
                b.popPercent(on);
            }
        }
    }

    private static void barAlpha(float a, long ms) {
        for (Object o : new ArrayList<>(ours)) {
            if (o instanceof View) ((View) o).animate().alpha(a).setDuration(ms).start();
        }
    }

    private static void readSettings() {
        android.content.ContentResolver cr = sys.getContentResolver();
        sbOn = Settings.Global.getInt(cr, K_SB, 0) == 1;
        shadeOn = Settings.Global.getInt(cr, K_SHADE, 0) == 1;
        clock24 = Settings.Global.getInt(cr, K_CLOCK24, 0) == 1;
        logo = Settings.Global.getInt(cr, K_LOGO, 1) == 1;
        carrier = Settings.Global.getInt(cr, K_CARRIER, 1) == 1;
        volumeOn = Settings.Global.getInt(cr, K_VOLUME, 0) == 1;
        debug = Settings.Global.getInt(cr, K_DEBUG, 0) == 1;
        netSpeed = Settings.Global.getInt(cr, K_NET_SPEED, 0) == 1;
        alarmIcon = Settings.Global.getInt(cr, K_ALARM_ICON, 1) == 1;
        seconds = Settings.Global.getInt(cr, K_SECONDS, 0) == 1;
        brightnessSlider = Settings.Global.getInt(cr, K_BRIGHTNESS, 1) == 1;
        batteryPct = Settings.Global.getInt(cr, K_BATTERY_PCT, 0) == 1;
        notifIcons = Settings.Global.getInt(cr, K_NOTIF_ICONS, 1) == 1;
        lockTimeout = Settings.Global.getInt(cr, K_LOCK_TIMEOUT, 0);
        lockScale = size(Settings.Global.getString(cr, K_LOCK_SCALE));
        StatusBarView.scale = size(Settings.Global.getString(cr, K_STATUS_SCALE));
    }

    private static float size(String s) {
        try {
            float v = s == null ? 1f : Float.parseFloat(s);
            return Float.isNaN(v) ? 1f : Math.max(0.7f, Math.min(1.6f, v));
        } catch (Exception e) {
            return 1f;
        }
    }

    static final String K_LOCK_TIMEOUT = "bb10_lock_timeout";
    static volatile int lockTimeout;
    static final String K_LOCK_SCALE = "bb10_lock_scale";
    static volatile float lockScale = 1f;
    static final String K_STATUS_SCALE = "bb10_status_scale";

    static final String K_NOTIF_ICONS = "bb10_notif_icons";
    static volatile boolean notifIcons = true;

    static final String K_NET_SPEED = "bb10_net_speed";
    static volatile boolean netSpeed;
    static final String K_ALARM_ICON = "bb10_alarm_icon";
    static volatile boolean alarmIcon = true;
    static final String K_SECONDS = "bb10_clock_seconds";
    static volatile boolean seconds;
    static final String K_BRIGHTNESS = "bb10_qs_brightness";
    static volatile boolean brightnessSlider = true;
    static final String K_BATTERY_PCT = "bb10_battery_percent";
    static volatile boolean batteryPct;
    static final String K_DEBUG = "bb10_debug";
    static volatile boolean debug;
    private static final long STARTED_AT = android.os.SystemClock.uptimeMillis();

    static boolean logging(String s) {
        if (debug || android.os.SystemClock.uptimeMillis() - STARTED_AT < 20_000) return true;
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("fail") || l.contains("error") || l.contains("unavailable") || l.contains("not found");
    }

    static Context wrap(Context base) {
        return new ResCtx(base, res);
    }

    private static final class ResCtx extends ContextWrapper {
        private final Resources r;
        private Resources.Theme theme;
        private LayoutInflater inflater;

        ResCtx(Context base, Resources r) {
            super(base);
            this.r = r;
        }

        @Override
        public Resources getResources() { return r; }

        @Override
        public AssetManager getAssets() { return r.getAssets(); }

        @Override
        public Resources.Theme getTheme() {
            if (theme == null) {
                theme = r.newTheme();
                theme.applyStyle(R.style.Theme_BB10, true);
            }
            return theme;
        }

        @Override
        public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                return inflater;
            }
            return super.getSystemService(name);
        }
    }

    static void posted(StatusBarNotification sbn) {
        if (sbn == null) return;
        synchronized (notifs) {
            notifs.remove(sbn.getKey());
            notifs.put(sbn.getKey(), sbn);
        }
        openIncomingCall(sbn);
        changed();
    }

    static void removed(StatusBarNotification sbn) {
        if (sbn == null) return;
        synchronized (notifs) {
            notifs.remove(sbn.getKey());
        }
        synchronized (callsOpened) {
            callsOpened.remove(sbn.getKey());
        }
        changed();
    }

    private static final java.util.Set<String> callsOpened = new java.util.HashSet<>();

    private static void openIncomingCall(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n.fullScreenIntent == null || sys == null) return;
            boolean call = Notification.CATEGORY_CALL.equals(n.category)
                    || "android.app.Notification$CallStyle".equals(n.extras.getString(Notification.EXTRA_TEMPLATE));
            if (!call || "com.bb10.phone".equals(sbn.getPackageName())) return;
            if (android.provider.Settings.Global.getInt(sys.getContentResolver(), "heads_up_notifications_enabled", 1) != 0) return;
            android.os.PowerManager pm = sys.getSystemService(android.os.PowerManager.class);
            android.app.KeyguardManager km = sys.getSystemService(android.app.KeyguardManager.class);
            if (pm == null || !pm.isInteractive() || km == null || km.isKeyguardLocked()) return;
            synchronized (callsOpened) {
                if (!callsOpened.add(sbn.getKey())) return;
            }
            android.app.PendingIntent pi = n.fullScreenIntent;
            String pkg = sbn.getPackageName();
            main.post(() -> {
                try {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    if (android.os.Build.VERSION.SDK_INT >= 34)
                        o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(sys, 0, null, null, null, null, o.toBundle());
                    note("incoming call: opened " + pkg + "'s call screen");
                } catch (Throwable t) {
                    note("incoming call: couldn't open " + pkg + "'s call screen: " + t);
                }
            });
        } catch (Throwable t) {
            note("incoming call: " + t);
        }
    }

    static void sync(StatusBarNotification[] all) {
        if (all == null) return;
        synchronized (notifs) {
            notifs.clear();
            for (StatusBarNotification s : all) notifs.put(s.getKey(), s);
        }
        changed();
    }

    private static boolean ongoing(StatusBarNotification s) {
        int fl = s.getNotification().flags;
        return (fl & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0
                || s.getNotification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    private static boolean summary(StatusBarNotification s) {
        return (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    static List<String> unreadPackages() {
        List<String> out = new ArrayList<>();
        synchronized (notifs) {
            List<StatusBarNotification> l = new ArrayList<>(notifs.values());
            for (int i = l.size() - 1; i >= 0; i--) {
                StatusBarNotification s = l.get(i);
                if (ongoing(s) || !s.isClearable()) continue;
                if (!out.contains(s.getPackageName())) out.add(s.getPackageName());
            }
        }
        return out;
    }

    static StatusBarNotification notification(String key) {
        if (key == null) return null;
        synchronized (notifs) {
            return notifs.get(key);
        }
    }

    static List<StatusBarNotification> notificationsFor(String pkg) {
        List<StatusBarNotification> out = new ArrayList<>();
        synchronized (notifs) {
            for (StatusBarNotification s : notifs.values())
                if (s.getPackageName().equals(pkg) && !ongoing(s) && s.isClearable() && !summary(s)) out.add(s);
        }
        out.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out;
    }
    static int count(String pkg) {
        int n = 0;
        synchronized (notifs) {
            for (StatusBarNotification s : notifs.values())
                if (s.getPackageName().equals(pkg) && !ongoing(s) && s.isClearable() && !summary(s)) n++;
        }
        return Math.max(1, n);
    }

    static Icon smallIcon(String pkg) {
        synchronized (notifs) {
            List<StatusBarNotification> l = new ArrayList<>(notifs.values());
            for (int i = l.size() - 1; i >= 0; i--) {
                StatusBarNotification s = l.get(i);
                if (s.getPackageName().equals(pkg) && !ongoing(s)) return s.getNotification().getSmallIcon();
            }
        }
        return null;
    }

    static List<StatusBarNotification> ongoingList() {
        List<StatusBarNotification> out = new ArrayList<>();
        synchronized (notifs) {
            List<StatusBarNotification> l = new ArrayList<>(notifs.values());
            for (int i = l.size() - 1; i >= 0; i--) {
                StatusBarNotification s = l.get(i);
                if (ongoing(s) && !summary(s)) out.add(s);
            }
        }
        List<StatusBarNotification> media = new ArrayList<>();
        for (StatusBarNotification s : out)
            if (s.getNotification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) media.add(s);
        out.removeAll(media);
        media.addAll(out);
        return media;
    }

    static void addListener(Runnable r) {
        main.post(() -> {
            if (!listeners.contains(r)) listeners.add(r);
        });
    }

    static void removeListener(Runnable r) {
        main.post(() -> listeners.remove(r));
    }

    private static boolean changePending;

    static void changed() {
        main.post(() -> {
            if (changePending) return;
            changePending = true;
            main.postDelayed(() -> {
                changePending = false;
                for (Object o : new ArrayList<>(ours)) {
                    if (o instanceof View) {
                        ((View) o).setVisibility(sbOn ? View.VISIBLE : View.GONE);
                        ((View) o).invalidate();
                    }
                }
                for (Object h : new ArrayList<>(hosts)) if (h instanceof View) ((View) h).invalidate();
                for (Runnable r : new ArrayList<>(listeners)) r.run();
            }, 50);
        });
    }

    private static final StatusBarView.Env ENV = new StatusBarView.Env() {
        @Override
        public boolean clock24() { return clock24 || android.text.format.DateFormat.is24HourFormat(sys); }

        @Override
        public boolean logo() { return logo; }

        @Override
        public boolean carrier() { return carrier; }

        @Override
        public boolean netSpeed() { return netSpeed; }

        @Override
        public boolean alarmIcon() { return alarmIcon; }

        @Override
        public boolean seconds() { return seconds; }

        @Override
        public boolean batteryPercent() { return batteryPct; }

        @Override
        public boolean notificationIcons() { return notifIcons; }

        @Override
        public String imsLabel() { return ImsWatch.label; }

        @Override
        public boolean hideClock() {
            android.app.KeyguardManager km = sys.getSystemService(android.app.KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        }

        @Override
        public List<String> unread() { return unreadPackages(); }

        @Override
        public Icon smallIcon(String pkg) { return SysUi.smallIcon(pkg); }
    };

    private static boolean writePending;

    static void note(String s) {
        if (!logging(s)) return;
        try {
            XposedBridge.log("BB10: " + s);
        } catch (Throwable ignored) {
        }
        synchronized (log) {
            log.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s);
            while (log.size() > 40) log.remove(0);
        }
        if (sys == null) return;
        main.post(() -> {
            if (writePending) return;
            writePending = true;
            main.postDelayed(() -> {
                writePending = false;
                String text;
                synchronized (log) {
                    text = String.join("\n", log);
                }
                try {
                    Settings.Global.putString(sys.getContentResolver(), K_STATUS, text);
                } catch (Throwable ignored) {
                }
            }, 1000);
        });
    }
}
