package com.bb10.launcher.sysui;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

public final class BbTiles {
    public static final int UNAVAILABLE = 0, INACTIVE = 1, ACTIVE = 2;

    public static final String[] ALL = {"settings", "wifi", "bt", "cell", "airplane", "hotspot", "flashlight", "rotation",
            "dnd", "bedside", "location", "nfc", "battery", "mute", "alarm", "devicemonitor", "screenrecord"};
    public static final String DEFAULT = "settings,cell,wifi,flashlight,alarm,dnd,bt,rotation,airplane,hotspot,location,battery,devicemonitor,screenrecord";

    private BbTiles() {}

    public static List<String> parse(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) csv = DEFAULT;
        List<String> known = Arrays.asList(ALL);
        for (String s : csv.split(",")) {
            s = s.trim();
            if (known.contains(s) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public static String label(String spec) {
        switch (spec) {
            case "settings": return "Settings";
            case "wifi": return "Wi-Fi";
            case "bt": return "Bluetooth";
            case "cell": return "Mobile Network";
            case "airplane": return "Airplane Mode";
            case "hotspot": return "Mobile Hotspot";
            case "flashlight": return "Flashlight";
            case "rotation": return "Rotation Lock";
            case "dnd": return "Notifications";
            case "bedside": return "Bedside Mode";
            case "location": return "Location";
            case "nfc": return "NFC";
            case "battery": return "Battery Saver";
            case "mute": return "Mute";
            case "alarm": return "Alarm";
            case "devicemonitor": return "Device Monitor";
            case "screenrecord": return "Screen Recorder";
        }
        return spec;
    }

    public static String icon(String spec) {
        switch (spec) {
            case "wifi": return "wifi";
            case "bt": return "bluetooth";
            case "cell": return "mobilenetwork";
            case "airplane": return "airplanemode";
            case "hotspot": return "mobilehotspot";
            case "flashlight": return "flashlight";
            case "rotation": return "rotationlock";
            case "dnd": return "notifications";
            case "bedside": return "bedsidemode";
            case "location": return "locationservices";
            case "nfc": return "nfc";
            case "battery": return "powersave";
            case "mute": return "mute";
            case "alarm": return "alarm";
            case "devicemonitor": return "devicemonitor";
            case "screenrecord": return "screenrecord";
        }
        return "settings";
    }

    private static volatile boolean torchOn, torchAvailable;
    private static String torchId;
    private static boolean torchInit;

    private static void initTorch(Context c) {
        if (torchInit) return;
        torchInit = true;
        try {
            CameraManager cm = c.getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) {
                    torchId = id;
                    break;
                }
            }
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) {
                        torchOn = enabled;
                        torchAvailable = true;
                    }
                }

                @Override
                public void onTorchModeUnavailable(String id) {
                    if (id.equals(torchId)) torchAvailable = false;
                }
            }, SysUi.main);
            torchAvailable = torchId != null;
        } catch (Throwable t) {
            SysUi.note("flashlight: " + t);
        }
    }

    private static int zen(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "zen_mode", 0);
    }

    static int state(Context c, String spec) {
        try {
            switch (spec) {
                case "wifi": {
                    WifiManager wm = c.getSystemService(WifiManager.class);
                    return wm != null && wm.isWifiEnabled() ? ACTIVE : INACTIVE;
                }
                case "bt": {
                    BluetoothAdapter a = adapter(c);
                    return a == null ? UNAVAILABLE : a.isEnabled() ? ACTIVE : INACTIVE;
                }
                case "cell": {
                    TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                    return tm == null ? UNAVAILABLE : tm.isDataEnabled() ? ACTIVE : INACTIVE;
                }
                case "airplane":
                    return Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1 ? ACTIVE : INACTIVE;
                case "hotspot": {
                    WifiManager wm = c.getSystemService(WifiManager.class);
                    Object st = WifiManager.class.getMethod("getWifiApState").invoke(wm);
                    int s = st instanceof Integer ? (Integer) st : 11;
                    return s == 13 || s == 12 ? ACTIVE : INACTIVE;
                }
                case "flashlight":
                    initTorch(c);
                    return !torchAvailable ? UNAVAILABLE : torchOn ? ACTIVE : INACTIVE;
                case "rotation":
                    return Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0 ? ACTIVE : INACTIVE;
                case "settings":
                    return INACTIVE;
                case "dnd": {
                    int z = zen(c);
                    if (z != 0 && z != 3) return ACTIVE;
                    return c.getSystemService(AudioManager.class).getRingerMode() != AudioManager.RINGER_MODE_NORMAL ? ACTIVE : INACTIVE;
                }
                case "bedside":
                    return zen(c) == 3 ? ACTIVE : INACTIVE;
                case "location": {
                    LocationManager lm = c.getSystemService(LocationManager.class);
                    return lm != null && lm.isLocationEnabled() ? ACTIVE : INACTIVE;
                }
                case "nfc": {
                    NfcAdapter n = NfcAdapter.getDefaultAdapter(c);
                    return n == null ? UNAVAILABLE : n.isEnabled() ? ACTIVE : INACTIVE;
                }
                case "battery": {
                    PowerManager pm = c.getSystemService(PowerManager.class);
                    return pm.isPowerSaveMode() ? ACTIVE : INACTIVE;
                }
                case "mute": {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    return am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL ? ACTIVE : INACTIVE;
                }
                case "alarm": {
                    AlarmManager am = c.getSystemService(AlarmManager.class);
                    return am.getNextAlarmClock() != null ? ACTIVE : INACTIVE;
                }
                case "devicemonitor":
                    return INACTIVE;
                case "screenrecord": {
                    Boolean sys = systemRecording();
                    if (sys != null) return sys ? ACTIVE : INACTIVE;
                    Bundle r = bridge(c, "recording");
                    return r == null ? UNAVAILABLE : r.getBoolean("on") ? ACTIVE : INACTIVE;
                }
            }
        } catch (Throwable ignored) {
        }
        return INACTIVE;
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    static String secondary(Context c, String spec) {
        try {
            switch (spec) {
                case "wifi": {
                    WifiManager wm = c.getSystemService(WifiManager.class);
                    if (!wm.isWifiEnabled()) return null;
                    String ssid = wm.getConnectionInfo().getSSID();
                    if (ssid == null || ssid.contains("unknown ssid")) return null;
                    return ssid.replace("\"", "");
                }
                case "cell": {
                    TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                    CharSequence op = tm.getNetworkOperatorName();
                    return op == null || op.length() == 0 ? null : op.toString();
                }
                case "dnd":
                    return modeName(notificationMode(c));
                case "mute": {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    int m = am.getRingerMode();
                    return m == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : m == AudioManager.RINGER_MODE_SILENT ? "Silent" : null;
                }
                case "alarm": {
                    AlarmManager.AlarmClockInfo a = c.getSystemService(AlarmManager.class).getNextAlarmClock();
                    if (a == null) return "Off";
                    boolean h24 = SysUi.clock24 || DateFormat.is24HourFormat(c);
                    return DateFormat.format(h24 ? "EEE HH:mm" : "EEE h:mm a", a.getTriggerTime()).toString();
                }
                case "devicemonitor": {
                    android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
                    int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (pct < 0) return null;
                    return pct + "%" + (bm.isCharging() ? " · Charging" : "");
                }
                case "screenrecord":
                    return state(c, spec) == ACTIVE ? "Recording" : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean opens(String spec) {
        return spec.equals("alarm") || spec.equals("settings") || spec.equals("devicemonitor") || spec.equals("screenrecord");
    }

    static volatile Object recordUx, recordCtl;

    private static Boolean systemRecording() {
        Object ux = ux();
        if (ux == null) return null;
        try {
            return (Boolean) ux.getClass().getMethod("isRecording").invoke(ux);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean systemRecord() {
        Object ux = ux();
        Boolean on = systemRecording();
        if (on == null) return false;
        try {
            if (on) {
                for (Object o : new Object[]{ux, recordCtl, field(ux, "recordingController")}) {
                    if (o == null) continue;
                    try {
                        o.getClass().getMethod("stopRecording", int.class).invoke(o, 5);
                        return true;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                return false;
            }
            SysUi.main.post(() -> {
                try {
                    Runnable started = () -> { };
                    android.app.Dialog d = (android.app.Dialog) ux.getClass()
                            .getMethod("createScreenRecordDialog", Runnable.class).invoke(ux, started);
                    if (d != null) d.show();
                } catch (Throwable t) {
                    SysUi.note("Android's screen record dialog failed: " + t);
                }
            });
            return true;
        } catch (Throwable t) {
            SysUi.note("Android's screen recorder failed: " + t);
            return false;
        }
    }

    private static Object ux() {
        if (recordUx == null && recordCtl != null) {
            recordUx = field(recordCtl, "mScreenRecordUxController");
            if (recordUx == null) {
                try {
                    recordCtl.getClass().getMethod("createScreenRecordDialog", Runnable.class);
                    recordUx = recordCtl;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return recordUx;
    }

    private static Object field(Object o, String name) {
        if (o == null) return null;
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bundle bridge(Context c, String method) {
        try (android.content.ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient("com.bb10.launcher.hubbridge")) {
            return cl == null ? null : cl.call(method, null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    static void toggle(Context c, String spec) {
        boolean on = state(c, spec) == ACTIVE;
        try {
            switch (spec) {
                case "wifi":
                    c.getSystemService(WifiManager.class).setWifiEnabled(!on);
                    return;
                case "bt": {
                    BluetoothAdapter a = adapter(c);
                    if (a != null) {
                        if (on) a.disable();
                        else a.enable();
                    }
                    return;
                }
                case "cell":
                    c.getSystemService(TelephonyManager.class).setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, !on);
                    return;
                case "airplane":
                    ConnectivityManager.class.getMethod("setAirplaneMode", boolean.class)
                            .invoke(c.getSystemService(ConnectivityManager.class), !on);
                    return;
                case "hotspot":
                    hotspot(c, !on);
                    return;
                case "flashlight":
                    initTorch(c);
                    if (torchId != null) c.getSystemService(CameraManager.class).setTorchMode(torchId, !on);
                    return;
                case "rotation":
                    Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                    return;
                case "settings":
                    c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case "dnd": {
                    int mode = notificationMode(c);
                    AudioManager am = c.getSystemService(AudioManager.class);
                    switch (mode) {
                        case MODE_NORMAL:
                            ringer(am, AudioManager.RINGER_MODE_VIBRATE);
                            break;
                        case MODE_VIBRATE:
                            ringer(am, AudioManager.RINGER_MODE_SILENT);
                            break;
                        case MODE_SILENT:
                            setZen(c, 2);
                            break;
                        default:
                            setZen(c, 0);
                            ringer(am, AudioManager.RINGER_MODE_NORMAL);
                    }
                    return;
                }
                case "bedside":
                    setZen(c, on ? 0 : 3);
                    return;
                case "location":
                    LocationManager.class.getMethod("setLocationEnabledForUser", boolean.class, android.os.UserHandle.class)
                            .invoke(c.getSystemService(LocationManager.class), !on, android.os.Process.myUserHandle());
                    return;
                case "nfc": {
                    NfcAdapter n = NfcAdapter.getDefaultAdapter(c);
                    if (n != null) NfcAdapter.class.getMethod(on ? "disable" : "enable").invoke(n);
                    return;
                }
                case "battery":
                    PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class)
                            .invoke(c.getSystemService(PowerManager.class), !on);
                    return;
                case "mute": {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    int target = on ? AudioManager.RINGER_MODE_NORMAL : AudioManager.RINGER_MODE_SILENT;
                    try {
                        AudioManager.class.getMethod("setRingerModeInternal", int.class).invoke(am, target);
                    } catch (Throwable e) {
                        am.setRingerMode(target);
                    }
                    return;
                }
                case "alarm":
                    c.startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case "devicemonitor":
                    c.startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case "screenrecord":
                    if (systemRecord()) return;
                    SysUi.note("Android's screen recorder not found (recording controller " + (recordCtl != null)
                            + ", its UI controller " + (ux() != null) + "); using JL10's");
                    if (bridge(c, "record") == null) SysUi.note("screen recording: JL10 not reachable");
                    return;
            }
        } catch (Throwable t) {
            SysUi.note("toggle " + spec + " failed: " + t);
        }
    }

    static final int MODE_NORMAL = 0, MODE_VIBRATE = 1, MODE_SILENT = 2, MODE_OFF = 3;

    static int notificationMode(Context c) {
        int z = zen(c);
        if (z != 0 && z != 3) return MODE_OFF;
        int m = c.getSystemService(AudioManager.class).getRingerMode();
        return m == AudioManager.RINGER_MODE_VIBRATE ? MODE_VIBRATE : m == AudioManager.RINGER_MODE_SILENT ? MODE_SILENT : MODE_NORMAL;
    }

    static String modeName(int mode) {
        switch (mode) {
            case MODE_VIBRATE: return "Vibrate";
            case MODE_SILENT: return "Silent";
            case MODE_OFF: return "All Alerts Off";
            default: return "Normal";
        }
    }

    private static void ringer(AudioManager am, int mode) {
        try {
            AudioManager.class.getMethod("setRingerModeInternal", int.class).invoke(am, mode);
        } catch (Throwable e) {
            am.setRingerMode(mode);
        }
    }

    private static void setZen(Context c, int mode) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (Method m : NotificationManager.class.getMethods()) {
            if (!m.getName().equals("setZenMode")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args = new Object[p.length];
            args[0] = mode;
            for (int i = 1; i < p.length; i++) {
                if (p[i] == Uri.class) args[i] = null;
                else if (p[i] == String.class) args[i] = "BB10 Quick Settings";
                else if (p[i] == boolean.class) args[i] = true;
                else if (p[i] == int.class) args[i] = 0;
            }
            try {
                m.invoke(nm, args);
                return;
            } catch (Throwable ignored) {
            }
        }
        nm.setInterruptionFilter(mode == 0 ? NotificationManager.INTERRUPTION_FILTER_ALL
                : mode == 3 ? NotificationManager.INTERRUPTION_FILTER_ALARMS : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    private static void hotspot(Context c, boolean on) throws Exception {
        Object tm = c.getSystemService("tethering");
        Class<?> tmc = Class.forName("android.net.TetheringManager");
        if (!on) {
            tmc.getMethod("stopTethering", int.class).invoke(tm, 0);
            return;
        }
        Class<?> cbc = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        Object cb = Proxy.newProxyInstance(cbc.getClassLoader(), new Class<?>[]{cbc}, (p, m, a) -> {
            switch (m.getName()) {
                case "equals": return p == a[0];
                case "hashCode": return System.identityHashCode(p);
                case "toString": return "BB10Hotspot";
                case "onTetheringFailed": SysUi.note("hotspot failed: " + (a != null && a.length > 0 ? a[0] : "?"));
            }
            return null;
        });
        Executor ex = Runnable::run;
        tmc.getMethod("startTethering", int.class, Executor.class, cbc).invoke(tm, 0, ex, cb);
    }

    static Intent settings(String spec) {
        String a;
        switch (spec) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bt": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "cell": a = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "airplane": a = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            case "hotspot": a = "android.settings.TETHER_SETTINGS"; break;
            case "rotation": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "dnd":
            case "bedside": a = "android.settings.ZEN_MODE_SETTINGS"; break;
            case "location": a = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "nfc": a = Settings.ACTION_NFC_SETTINGS; break;
            case "battery": a = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
            case "mute": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "settings": a = Settings.ACTION_SETTINGS; break;
            case "alarm": a = AlarmClock.ACTION_SHOW_ALARMS; break;
            default: return null;
        }
        return new Intent(a);
    }

    private static BluetoothAdapter adapter(Context c) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }
}
