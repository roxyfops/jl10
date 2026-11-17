package com.bb10.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Prefs {
    public static final String GRID_COLS = "grid_cols";
    public static final String GRID_ROWS = "grid_rows";
    public static final String SHOW_LABELS = "show_labels";
    public static final String ICON_PACK = "icon_pack";
    public static final String ICON_SHAPE = "icon_shape";
    public static final String BB10_ICONS = "bb10_icons";
    public static final String BB_LOGO = "bb_status_logo";
    public static final String HOME_KEY_ANIM = "home_key_anim";
    public static final String HIDDEN = "hidden_apps";
    public static final String FONT_STAMP = "font_stamp";
    public static final String BB_STATUS = "bb_status_bar";
    public static final String CLOCK_24 = "clock_24h";
    public static final String HUB_HISTORY = "hub_keep_history";
    public static final String HUB_ONGOING = "hub_ongoing";
    public static final String HUB_EXCLUDED = "hub_excluded";
    public static final String FRAMES_THUMBS = "frames_thumbnails";
    public static final String FRAMES_MAX = "frames_max";
    public static final String FRAMES_KILL = "frames_kill";
    public static final String FRAMES_LAUNCH = "frames_launch";
    public static final String OWN_SPLASH = "own_splash";
    public static final String OWN_SPLASH_SKIP_QUICK = "own_splash_skip_quick";
    public static final String FRAMES_MESSAGES = "frames_messages";
    public static final String FRAMES_LIVE = "frames_live";
    public static final String INSTANT_PREVIEWS = "instant_previews";
    public static final String LOCKSCREEN = "bb10_lockscreen";
    public static final String SYSUI_STATUSBAR = "sysui_statusbar";
    public static final String BB_CARRIER = "bb_status_carrier";
    public static final String QS_TILES = "qs_tiles";
    public static final String HUB_ALL = "hub_all";
    public static final String HUB_SPLIT = "hub_split_messages";
    public static final String HUB_THEME = "hub_theme";
    public static final String SYSUI_VOLUME = "sysui_volume";
    public static final String SYSUI_KEYGUARD = "sysui_keyguard";
    public static final String PW_AUTO = "pw_auto_length";
    public static final String LOCK_MUSIC_TINT = "lock_music_tint";
    public static final String LOCK_CAMERA = "lock_camera";
    public static final String LOCK_COLUMN = "lock_column";
    public static final String LOCK_TIMEOUT = "lock_timeout";
    public static final String LOCK_SCALE = "lock_scale";
    public static final String STATUS_SCALE = "status_scale";
    public static final String HOME_TEXT = "home_text";
    public static final String WALL_DIM = "wall_dim";
    public static final String FRAMES_ARRANGE = "frames_arrange";
    public static final String SYSUI_SHADE = "sysui_shade";
    public static final String PULL_ANDROID = "pull_android_panel";
    public static final String NET_SPEED = "net_speed";
    public static final String ALARM_ICON = "alarm_icon";
    public static final String CLOCK_SECONDS = "clock_seconds";
    public static final String QS_BRIGHTNESS = "qs_brightness";
    public static final String BATTERY_PERCENT = "battery_percent";
    public static final String HIDE_STATUS_APPS = "hide_status_apps";
    public static final String NOTIF_ICONS = "sb_notif_icons";
    public static final String DEBUG = "debug_mode";
    public static final String RECENTS_ACTION = "recents_action";
    public static final String VIP_SENDERS = "vip_senders";
    public static final String VIP_APPS = "vip_apps";
    public static final String FRAMES_MINIMIZE = "frames_minimize_anim";
    public static final String GEST_ON = "gestures_enabled";
    public static final String GEST_HOME = "gesture_home";
    public static final String GEST_PEEK = "gesture_peek";
    public static final String GEST_EDGE = "gesture_edge";
    public static final String GEST_PREDICT = "gesture_predict";
    public static final String GEST_HAPTIC = "gesture_haptic";
    public static final String GEST_HANDLE = "gesture_handle";
    public static final String KB_SEARCH = "kb_type_search";
    public static final String KB_SHORTCUTS = "kb_shortcuts";

    public final SharedPreferences sp;
    private final SharedPreferences labels, shortcuts, icons;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(c.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        labels = c.getSharedPreferences("labels", Context.MODE_PRIVATE);
        shortcuts = c.getSharedPreferences("kb_shortcuts", Context.MODE_PRIVATE);
        icons = c.getSharedPreferences("icon_overrides", Context.MODE_PRIVATE);
    }

    public int cols() { return clamp(getInt(GRID_COLS, 5), 3, 6); }

    public int rows() { return clamp(getInt(GRID_ROWS, 3), 3, 6); }

    public boolean bool(String key, boolean def) { return sp.getBoolean(key, def); }

    public String str(String key, String def) { return sp.getString(key, def); }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(sp.getString(key, Integer.toString(def)));
        } catch (Exception e) {
            return def;
        }
    }

    public float scale(String key) {
        try {
            float v = Float.parseFloat(sp.getString(key, "1"));
            return Float.isNaN(v) ? 1f : Math.max(0.7f, Math.min(1.6f, v));
        } catch (Exception e) {
            return 1f;
        }
    }

    public Set<String> set(String key) {
        return new HashSet<>(sp.getStringSet(key, Collections.emptySet()));
    }

    public void putSet(String key, Set<String> v) { sp.edit().putStringSet(key, v).apply(); }

    public String label(String appKey) { return labels.getString(appKey, null); }

    public void setLabel(String appKey, String v) {
        if (v == null || v.trim().isEmpty()) labels.edit().remove(appKey).apply();
        else labels.edit().putString(appKey, v.trim()).apply();
    }

    public String shortcutApp(char ch) {
        return shortcuts.getString(String.valueOf(Character.toLowerCase(ch)), null);
    }

    public char shortcutFor(String appKey) {
        for (Map.Entry<String, ?> e : shortcuts.getAll().entrySet()) {
            if (appKey.equals(e.getValue())) return e.getKey().charAt(0);
        }
        return 0;
    }

    public void setShortcut(char ch, String appKey) {
        SharedPreferences.Editor ed = shortcuts.edit();
        for (Map.Entry<String, ?> e : shortcuts.getAll().entrySet()) {
            if (appKey.equals(e.getValue())) ed.remove(e.getKey());
        }
        if (ch != 0) ed.putString(String.valueOf(Character.toLowerCase(ch)), appKey);
        ed.apply();
    }

    public String iconOverride(String appKey) { return icons.getString(appKey, null); }

    public void setIconOverride(String appKey, String v) {
        if (v == null) icons.edit().remove(appKey).apply();
        else icons.edit().putString(appKey, v).apply();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
