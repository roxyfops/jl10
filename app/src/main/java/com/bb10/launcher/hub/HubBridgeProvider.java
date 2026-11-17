package com.bb10.launcher.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.bb10.launcher.gesture.GestureService;

public class HubBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "com.bb10.launcher.hubbridge";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext().checkCallingPermission("android.permission.STATUS_BAR_SERVICE") != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("System UI only");
        if ("lockinfo".equals(method)) return lockInfo();
        if ("splash".equals(method)) return com.bb10.launcher.quickstep.RemoteAnims.splash(extras);
        if ("bouncer_gone".equals(method)) {
            com.bb10.launcher.quickstep.RemoteAnims.bouncerGoneAt = android.os.SystemClock.uptimeMillis();
            return null;
        }
        if ("media_closed".equals(method)) {
            Bundle r = new Bundle();
            com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
            r.putStringArray("pkgs", app.live != null ? app.live.closedPlayers() : new String[0]);
            return r;
        }
        if ("media_playing".equals(method)) {
            String pkg = extras == null ? null : extras.getString("pkg");
            com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (app.live != null) app.live.playerBack(pkg);
            });
            return null;
        }
        if ("recording".equals(method) || "record".equals(method)) {
            if ("record".equals(method)) com.bb10.launcher.record.ScreenRecorder.toggle(getContext());
            Bundle r = new Bundle();
            r.putBoolean("on", com.bb10.launcher.record.ScreenRecorder.isRecording());
            return r;
        }
        float x = extras == null ? 0 : extras.getFloat("x");
        long t = extras == null ? 0 : extras.getLong("t");
        if ("open_token".equals(method)) {
            Bundle r = new Bundle();
            r.putString("token", com.bb10.launcher.LauncherActivity.OPEN_TOKEN);
            return r;
        }
        return GestureService.hubBridge(method, x, t);
    }

    private static final int LOCK_APPS = 5, LOCK_MESSAGES = 20;

    private Bundle lockInfo() {
        Bundle b = new Bundle();
        com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
        try {
            app.ensureInit();
            Bundle hub = onMain(() -> hubForLock(app.hub));
            if (hub != null) b.putAll(hub);
        } catch (Throwable ignored) {
        }
        try {
            if (getContext().checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                long now = System.currentTimeMillis();
                java.util.List<com.bb10.launcher.frames.LiveFrames.Event> ev =
                        com.bb10.launcher.frames.LiveFrames.query(getContext(), now, now + 24 * 3600_000L, 6);
                int n = ev.size();
                String[] titles = new String[n];
                long[] begins = new long[n], ends = new long[n];
                boolean[] allDay = new boolean[n];
                for (int i = 0; i < n; i++) {
                    titles[i] = ev.get(i).title;
                    begins[i] = ev.get(i).begin;
                    ends[i] = ev.get(i).end;
                    allDay[i] = ev.get(i).allDay;
                }
                b.putStringArray("titles", titles);
                b.putLongArray("begins", begins);
                b.putLongArray("ends", ends);
                b.putBooleanArray("allday", allDay);
            }
        } catch (Throwable ignored) {
        }
        return b;
    }

    private static Bundle hubForLock(HubStore hub) {
        java.util.List<String> pkgs = hub.unreadGroups();
        int[] counts = new int[pkgs.size()];
        Bundle items = new Bundle();
        for (int i = 0; i < counts.length; i++) {
            counts[i] = hub.unread(pkgs.get(i));
            if (i < LOCK_APPS) items.putBundle(pkgs.get(i), newestUnread(hub, pkgs.get(i)));
        }
        Bundle b = new Bundle();
        b.putStringArray("pkgs", pkgs.toArray(new String[0]));
        b.putIntArray("counts", counts);
        b.putBundle("items", items);
        b.putStringArray("comm", hub.learnedComm());
        return b;
    }

    private static Bundle newestUnread(HubStore hub, String pkg) {
        java.util.List<HubItem> l = new java.util.ArrayList<>();
        for (HubItem it : hub.items(pkg, null)) if (!it.read) l.add(it);
        l.sort((x, y) -> Long.compare(y.time, x.time));
        int n = Math.min(LOCK_MESSAGES, l.size());
        String[] ids = new String[n], keys = new String[n], titles = new String[n], texts = new String[n], apps = new String[n];
        long[] times = new long[n];
        android.content.Context ctx = com.bb10.launcher.LauncherApp.get();
        for (int i = 0; i < n; i++) {
            HubItem it = l.get(i);
            boolean mail = HubStore.isMail(it);
            ids[i] = it.id;
            apps[i] = HubStore.isCall(it) ? CallLogHub.dialer(ctx) : it.pkg;
            keys[i] = it.active && !mail ? HubStore.baseKey(it.key) : null;
            titles[i] = it.title;
            String body = it.text == null ? "" : it.text;
            if (mail && it.sub != null && !it.sub.isEmpty() && !body.startsWith(it.sub))
                body = body.isEmpty() ? it.sub : it.sub + " • " + body;
            texts[i] = body;
            times[i] = it.time;
        }
        Bundle b = new Bundle();
        b.putStringArray("ids", ids);
        b.putStringArray("keys", keys);
        b.putStringArray("titles", titles);
        b.putStringArray("texts", texts);
        b.putLongArray("times", times);
        b.putStringArray("apps", apps);
        return b;
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> f) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try {
                return f.call();
            } catch (Exception e) {
                return null;
            }
        }
        java.util.concurrent.FutureTask<T> t = new java.util.concurrent.FutureTask<>(f);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(t);
        try {
            return t.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            t.cancel(false);
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override
    public int delete(Uri uri, String s, String[] a) { return 0; }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
