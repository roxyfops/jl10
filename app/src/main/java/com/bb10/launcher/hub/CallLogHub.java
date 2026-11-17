package com.bb10.launcher.hub;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;

import com.bb10.launcher.LauncherApp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class CallLogHub {
    private CallLogHub() {
    }

    private static final int MAX = 150;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean started;

    public static boolean readable(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean writable(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    public static void start(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext();
        try {
            app.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, new ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    h.removeCallbacks(syncRun);
                    h.postDelayed(syncRun, 400);
                }
            });
        } catch (Exception ignored) {
        }
        sync(app);
    }

    private static final Runnable syncRun = () -> sync(LauncherApp.get());

    public static void sync(Context c) {
        if (!readable(c)) return;
        LauncherApp.get().bg.execute(() -> {
            List<HubItem> out = load(c);
            if (out != null) h.post(() -> LauncherApp.get().hub.putMail(HubKinds.CALLS, out, false));
        });
    }

    private static List<HubItem> load(Context c) {
        String[] cols = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
                CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.IS_READ, CallLog.Calls.NEW};
        List<HubItem> out = new ArrayList<>();
        String iso = java.util.Locale.getDefault().getCountry();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")) {
            if (cur == null) return null;
            while (cur.moveToNext() && out.size() < MAX) {
                long id = cur.getLong(0);
                String number = cur.getString(1) == null ? "" : cur.getString(1);
                String name = cur.getString(2);
                int type = cur.getInt(4);
                long dur = cur.getLong(5);
                boolean missed = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE;
                HubItem it = new HubItem();
                it.id = HubKinds.CALLS + id;
                it.key = it.id;
                it.pkg = HubKinds.CALLS;
                it.time = cur.getLong(3);
                String shown = number;
                try {
                    String f = android.telephony.PhoneNumberUtils.formatNumber(number, iso);
                    if (f != null) shown = f;
                } catch (Exception ignored) {
                }
                if (shown.isEmpty()) shown = "Unknown number";
                it.title = name != null && !name.isEmpty() ? name : shown;
                String what;
                switch (type) {
                    case CallLog.Calls.MISSED_TYPE: what = "Missed call"; break;
                    case CallLog.Calls.REJECTED_TYPE: what = "Declined call"; break;
                    case CallLog.Calls.BLOCKED_TYPE: what = "Blocked call"; break;
                    case CallLog.Calls.VOICEMAIL_TYPE: what = "Voicemail"; break;
                    case CallLog.Calls.OUTGOING_TYPE: what = "Outgoing call"; break;
                    default: what = "Incoming call";
                }
                if (!missed && dur > 0) what += " · " + length(dur);
                it.text = name != null && !name.isEmpty() && !number.isEmpty() ? what + " · " + shown : what;
                it.sub = "";
                boolean isRead = cur.isNull(6) ? cur.getInt(7) == 0 : cur.getInt(6) == 1;
                it.read = !missed || isRead;
                out.add(it);
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    private static String length(long s) {
        if (s < 60) return s + " sec";
        long m = s / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " hr " + (m % 60) + " min";
    }

    static long rowId(HubItem it) {
        if (it.key == null || !it.key.startsWith(HubKinds.CALLS)) return -1;
        try {
            return Long.parseLong(it.key.substring(HubKinds.CALLS.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    public static String number(Context c, HubItem it) {
        long id = rowId(it);
        if (id < 0 || !readable(c)) return null;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER},
                CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)}, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    static void read(Context c, Collection<HubItem> l, boolean read) {
        List<Long> ids = new ArrayList<>();
        for (HubItem it : l) {
            long id = rowId(it);
            if (id >= 0) ids.add(id);
        }
        if (ids.isEmpty() || !writable(c)) return;
        LauncherApp.get().bg.execute(() -> {
            ContentValues v = new ContentValues();
            v.put(CallLog.Calls.IS_READ, read ? 1 : 0);
            if (read) v.put(CallLog.Calls.NEW, 0);
            for (long id : ids) {
                try {
                    c.getContentResolver().update(CallLog.Calls.CONTENT_URI, v, CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)});
                } catch (Exception ignored) {
                }
            }
        });
    }

    static void deleted(Context c, Collection<HubItem> l) {
        List<Long> ids = new ArrayList<>();
        for (HubItem it : l) {
            long id = rowId(it);
            if (id >= 0) ids.add(id);
        }
        if (ids.isEmpty() || !writable(c)) return;
        LauncherApp.get().bg.execute(() -> {
            for (long id : ids) {
                try {
                    c.getContentResolver().delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)});
                } catch (Exception ignored) {
                }
            }
        });
    }

    public static String dialer(Context c) {
        try {
            android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class);
            String p = tm == null ? null : tm.getDefaultDialerPackage();
            if (p != null) return p;
        } catch (Exception ignored) {
        }
        return "com.android.dialer";
    }
}
