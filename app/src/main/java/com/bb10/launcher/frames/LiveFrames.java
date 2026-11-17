package com.bb10.launcher.frames;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.hub.HubListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LiveFrames {
    public static class Event {
        public long begin, end;
        public String title = "", location = "";
        public boolean allDay;
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private List<Event> events = new ArrayList<>();
    private long eventsAt;
    private Set<String> calendarPkgs;
    private boolean started;

    public LiveFrames(Context c) {
        ctx = c;
        try {
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    setOpenContact(i.getLongExtra("id", -1));
                }
            }, new android.content.IntentFilter("com.bb10.contacts.OPEN"), Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {
        }
    }

    public static class OpenContact {
        public long id;
        public String name = "", job = "";
        public android.graphics.Bitmap photo;
    }

    private volatile OpenContact openContact;
    private long openContactId = -1;

    private android.graphics.Bitmap hiPhoto(long contactId, int max) {
        Uri uri = android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, contactId);
        try (java.io.InputStream in = android.provider.ContactsContract.Contacts.openContactPhotoInputStream(ctx.getContentResolver(), uri, true)) {
            if (in == null) return null;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b == null || Math.max(b.getWidth(), b.getHeight()) <= max) return b;
            float s = max / (float) Math.max(b.getWidth(), b.getHeight());
            return android.graphics.Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
        } catch (Exception e) {
            return null;
        }
    }

    public OpenContact openContact() {
        return openContact;
    }

    private void setOpenContact(long id) {
        if (id == openContactId) return;
        openContactId = id;
        if (id < 0 || !canReadContacts()) {
            openContact = null;
            fire();
            return;
        }
        LauncherApp.get().bg.execute(() -> {
            OpenContact oc = new OpenContact();
            oc.id = id;
            Uri uri = android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, id);
            boolean found = false;
            try (Cursor c = ctx.getContentResolver().query(uri, new String[]{android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    android.provider.ContactsContract.Contacts.PHOTO_URI}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    found = true;
                    oc.name = c.getString(0) == null ? "" : c.getString(0);
                    if (c.getString(1) != null) oc.photo = hiPhoto(id, 720);
                }
            } catch (Exception ignored) {
            }
            try (Cursor c = ctx.getContentResolver().query(android.provider.ContactsContract.Data.CONTENT_URI,
                    new String[]{android.provider.ContactsContract.CommonDataKinds.Organization.COMPANY, android.provider.ContactsContract.CommonDataKinds.Organization.TITLE},
                    android.provider.ContactsContract.Data.CONTACT_ID + "=? AND " + android.provider.ContactsContract.Data.MIMETYPE + "=?",
                    new String[]{String.valueOf(id), android.provider.ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE}, null)) {
                if (c != null && c.moveToFirst()) {
                    String co = c.getString(0) == null ? "" : c.getString(0), ti = c.getString(1) == null ? "" : c.getString(1);
                    oc.job = ti.isEmpty() ? co : co.isEmpty() ? ti : ti + ", " + co;
                }
            } catch (Exception ignored) {
            }
            boolean ok = found;
            h.post(() -> {
                if (openContactId != id) return;
                openContact = ok ? oc : null;
                fire();
            });
        });
    }

    public void addListener(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private int users;

    public void start() {
        users++;
        if (started) return;
        started = true;
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            ComponentName nl = new ComponentName(ctx, HubListenerService.class);
            sessionsListener = list -> setControllers(list);
            msm.addOnActiveSessionsChangedListener(sessionsListener, nl, h);
            setControllers(msm.getActiveSessions(nl));
        } catch (Exception ignored) {
        }
        refreshEvents(true);
    }

    public void stop() {
        if (users > 0) users--;
        if (users > 0 || !started) return;
        started = false;
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            if (sessionsListener != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
        }
        setControllers(new ArrayList<>());
    }

    private void setControllers(List<MediaController> list) {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try {
                e.getKey().unregisterCallback(e.getValue());
            } catch (Exception ignored) {
            }
        }
        callbacks.clear();
        stateCache.clear();
        metaCache.clear();
        controllers = list == null ? new ArrayList<>() : new ArrayList<>(list);
        for (MediaController mc : controllers) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    stateCache.put(mc, state == null ? NO_STATE : state);
                    fire();
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    metaCache.put(mc, metadata == null ? NO_META : metadata);
                    remember(mc.getPackageName(), metadata);
                    fire();
                }

                @Override
                public void onSessionDestroyed() { fire(); }
            };
            try {
                mc.registerCallback(cb, h);
                callbacks.put(mc, cb);
            } catch (Exception ignored) {
            }
        }
        fire();
    }

    private static final PlaybackState NO_STATE = new PlaybackState.Builder().build();
    private static final MediaMetadata NO_META = new MediaMetadata.Builder().build();
    private final Map<MediaController, PlaybackState> stateCache = new HashMap<>();
    private final Map<MediaController, MediaMetadata> metaCache = new HashMap<>();

    public PlaybackState state(MediaController mc) {
        PlaybackState s = stateCache.get(mc);
        if (s == null) {
            s = mc.getPlaybackState();
            stateCache.put(mc, s == null ? NO_STATE : s);
        }
        return s == NO_STATE ? null : s;
    }

    public MediaMetadata metadata(MediaController mc) {
        MediaMetadata m = metaCache.get(mc);
        if (m == null) {
            m = mc.getMetadata();
            metaCache.put(mc, m == null ? NO_META : m);
        }
        return m == NO_META ? null : m;
    }

    public boolean playing(MediaController mc) {
        PlaybackState st = state(mc);
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    public MediaController mediaFor(String pkg) {
        for (MediaController mc : controllers) {
            if (!pkg.equals(mc.getPackageName())) continue;
            PlaybackState st = state(mc);
            MediaMetadata md = metadata(mc);
            if (st == null || md == null) continue;
            int s = st.getState();
            if (s == PlaybackState.STATE_NONE || s == PlaybackState.STATE_STOPPED || s == PlaybackState.STATE_ERROR) continue;
            remember(pkg, md);
            return mc;
        }
        return null;
    }

    private final Map<String, MediaMetadata> lastSong = new HashMap<>();

    private void remember(String pkg, MediaMetadata md) {
        if (md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null) lastSong.put(pkg, md);
    }

    public MediaMetadata lastPlayed(String pkg) {
        return mediaFor(pkg) != null ? null : lastSong.get(pkg);
    }

    public void forget(String pkg) {
        lastSong.remove(pkg);
    }

    public void mediaKey(int keyCode) {
        android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
    }

    public MediaController current() {
        MediaController paused = null;
        for (MediaController mc : controllers) {
            MediaController m = mediaFor(mc.getPackageName());
            if (m == null || closedPlayer(m)) continue;
            if (isPlaying(m)) return m;
            if (paused == null) paused = m;
        }
        return paused;
    }

    private final Map<String, Long> closedPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    public static final String ACTION_MEDIA_CLOSED = "com.bb10.launcher.MEDIA_CLOSED";

    public void appClosed(String pkg) {
        lastSong.remove(pkg);
        boolean player = false;
        for (MediaController mc : sessionsNow()) {
            if (!pkg.equals(mc.getPackageName())) continue;
            player = true;
            if (isPlaying(mc)) {
                try {
                    mc.getTransportControls().pause();
                } catch (Exception ignored) {
                }
            }
        }
        h.postDelayed(() -> HubListenerService.cancelMedia(pkg), 1200);
        if (!player && !HubListenerService.cancelMedia(pkg)) return;
        closedPlayers.put(pkg, android.os.SystemClock.uptimeMillis());
        sendClosed();
        fire();
    }

    public void appOpened(String pkg) {
        if (closedPlayers.remove(pkg) == null) return;
        sendClosed();
        fire();
    }

    public boolean closedPlayer(MediaController mc) {
        Long at = closedPlayers.get(mc.getPackageName());
        if (at == null) return false;
        if (isPlaying(mc) && android.os.SystemClock.uptimeMillis() - at > 3000) {
            appOpened(mc.getPackageName());
            return false;
        }
        return true;
    }

    public String[] closedPlayers() {
        return closedPlayers.keySet().toArray(new String[0]);
    }

    public void playerBack(String pkg) {
        if (pkg != null && closedPlayers.remove(pkg) != null) fire();
    }

    private void sendClosed() {
        try {
            ctx.sendBroadcast(new Intent(ACTION_MEDIA_CLOSED).setPackage("com.android.systemui")
                    .putExtra("pkgs", closedPlayers()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (Exception ignored) {
        }
    }

    private List<MediaController> sessionsNow() {
        if (started) return controllers;
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(ctx, HubListenerService.class));
            return l != null ? l : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean isPlaying(MediaController mc) {
        PlaybackState st = mc.getPlaybackState();
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    public boolean isCalendarApp(String pkg) {
        if (calendarPkgs == null) {
            calendarPkgs = new HashSet<>();
            PackageManager pm = ctx.getPackageManager();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR), 0)) {
                calendarPkgs.add(ri.activityInfo.packageName);
            }
            calendarPkgs.add("com.google.android.calendar");
            calendarPkgs.add("com.android.calendar");
            calendarPkgs.add("org.fossify.calendar");
            calendarPkgs.add("ws.xsoh.etar");
            calendarPkgs.add("org.lineageos.etar");
            calendarPkgs.add("com.samsung.android.calendar");
            calendarPkgs.add("com.blackberry.calendar");
            calendarPkgs.add("com.bb10.calendar");
        }
        return calendarPkgs.contains(pkg);
    }

    public boolean canReadCalendar() {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public List<Event> upcoming() {
        refreshEvents(false);
        return events;
    }

    public void refreshEvents(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - eventsAt < 60_000) return;
        eventsAt = now;
        if (!canReadCalendar()) return;
        LauncherApp.get().bg.execute(() -> {
            List<Event> out = query(ctx, now, now + 7L * 24 * 3600_000L, 12);
            h.post(() -> {
                events = out;
                fire();
            });
        });
    }

    public static List<Event> query(Context ctx, long from, long to, int max) {
        List<Event> out = new ArrayList<>();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from - 24 * 3600_000L);
        ContentUris.appendId(b, to);
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION};
        try (Cursor c = ctx.getContentResolver().query(b.build(), proj,
                CalendarContract.Instances.END + " > ?", new String[]{String.valueOf(from)},
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < max) {
                Event e = new Event();
                e.title = c.getString(0) == null ? "(No title)" : c.getString(0);
                e.begin = c.getLong(1);
                e.end = c.getLong(2);
                e.allDay = c.getInt(3) != 0;
                e.location = c.getString(4) == null ? "" : c.getString(4);
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public AlarmManager.AlarmClockInfo nextAlarm() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!alarmKnown || now - alarmAt > 60_000) {
            try {
                alarm = ctx.getSystemService(AlarmManager.class).getNextAlarmClock();
            } catch (Exception e) {
                alarm = null;
            }
            alarmKnown = true;
            alarmAt = now;
            watchCaches();
        }
        return alarm;
    }

    private AlarmManager.AlarmClockInfo alarm;
    private boolean alarmKnown, cachesWatched;
    private long alarmAt, dialerAt;
    private String dialer;

    private String defaultDialer() {
        long now = android.os.SystemClock.uptimeMillis();
        if (dialer == null || now - dialerAt > 60_000) {
            try {
                android.telecom.TelecomManager tm = ctx.getSystemService(android.telecom.TelecomManager.class);
                dialer = tm == null ? "" : String.valueOf(tm.getDefaultDialerPackage());
            } catch (Exception e) {
                dialer = "";
            }
            dialerAt = now;
            watchCaches();
        }
        return dialer;
    }

    private void watchCaches() {
        if (cachesWatched) return;
        cachesWatched = true;
        IntentFilter f = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(android.telecom.TelecomManager.ACTION_DEFAULT_DIALER_CHANGED);
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    alarmKnown = false;
                    dialer = null;
                }
            }, f, Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {
        }
    }

    private static String formatted(String number, String iso) {
        String f = android.telephony.PhoneNumberUtils.formatNumber(number, iso);
        return f != null ? f : number;
    }

    public boolean isClockApp(String pkg) {
        AlarmManager.AlarmClockInfo a = nextAlarm();
        if (a != null && a.getShowIntent() != null && pkg.equals(a.getShowIntent().getCreatorPackage())) return true;
        return pkg.equals("com.google.android.deskclock") || pkg.equals("com.android.deskclock")
                || pkg.equals("org.fossify.clock") || pkg.equals("com.sec.android.app.clockpackage")
                || pkg.equals("com.blackberry.clock");
    }

    public static class Person {
        public String name = "";
        public android.graphics.Bitmap photo;
    }

    private List<Person> people = new ArrayList<>();
    private long peopleAt;
    private boolean peopleLoading;
    private android.database.ContentObserver peopleObserver;
    private String contactsPkg;

    public boolean isContactsApp(String pkg) {
        if ("com.bb10.contacts".equals(pkg)) return true;
        if (contactsPkg == null) {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS), PackageManager.MATCH_DEFAULT_ONLY);
            contactsPkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
        }
        return pkg.equals(contactsPkg);
    }

    public boolean canReadContacts() {
        return ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    public List<Person> framePeople() {
        if (System.currentTimeMillis() - peopleAt > 300_000 && !peopleLoading) refreshPeople();
        return people;
    }

    private void refreshPeople() {
        peopleLoading = true;
        if (peopleObserver == null) {
            peopleObserver = new android.database.ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    peopleAt = 0;
                }
            };
            try {
                ctx.getContentResolver().registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, peopleObserver);
            } catch (Exception ignored) {
            }
        }
        LauncherApp.get().bg.execute(() -> {
            List<Person> out = new ArrayList<>();
            String[] cols = {android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                    android.provider.ContactsContract.Contacts._ID};
            String[] where = {
                    android.provider.ContactsContract.Contacts.STARRED + "=1 AND " + android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI + " IS NOT NULL",
                    android.provider.ContactsContract.Contacts.STARRED + "=1 AND " + android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI + " IS NULL",
                    android.provider.ContactsContract.Contacts.STARRED + "=0 AND " + android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI + " IS NOT NULL"};
            for (String w : where) {
                if (out.size() >= 4) break;
                try (Cursor c = ctx.getContentResolver().query(android.provider.ContactsContract.Contacts.CONTENT_URI, cols, w, null,
                        android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
                    while (c != null && c.moveToNext() && out.size() < 4) {
                        Person p = new Person();
                        p.name = c.getString(0) == null ? "" : c.getString(0);
                        if (c.getString(1) != null) p.photo = hiPhoto(c.getLong(2), 400);

                        out.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
            h.post(() -> {
                people = out;
                peopleAt = System.currentTimeMillis();
                peopleLoading = false;
                fire();
            });
        });
    }

    public static class CallEntry {
        public String name = "", number = "";
        public long time;
        public int type;
        public boolean isNew;
        public String who = "", when = "";
    }

    private List<CallEntry> calls = new ArrayList<>();
    private long callsAt;
    private boolean callsLoading;
    private android.database.ContentObserver callObserver;

    public boolean isPhoneApp(String pkg) {
        if ("com.bb10.phone".equals(pkg)) return true;
        try {
            return pkg.equals(defaultDialer());
        } catch (Exception e) {
            return false;
        }
    }

    public static class NoteItem {
        public String heading = "", text = "";
        public long modified;
    }

    public static class TaskItem {
        public String title = "";
        public long due;
        public boolean hasTime, flagged;

        public boolean overdue() {
            if (due <= 0) return false;
            long now = System.currentTimeMillis();
            if (hasTime) return due < now;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(due);
            c.set(java.util.Calendar.HOUR_OF_DAY, 23);
            c.set(java.util.Calendar.MINUTE, 59);
            return c.getTimeInMillis() < now;
        }

        public boolean dueToday() {
            return due > 0 && android.text.format.DateUtils.isToday(due);
        }
    }

    private static final Uri NOTES = Uri.parse("content://com.bb10.notes.provider/notes");
    private static final Uri TASKS = Uri.parse("content://com.bb10.tasks.provider/tasks");
    private volatile List<NoteItem> notes = new ArrayList<>();
    private volatile List<TaskItem> tasks = new ArrayList<>();
    private boolean notesWatched, tasksWatched;

    public boolean isNotesApp(String pkg) { return "com.bb10.notes".equals(pkg); }

    public boolean isTasksApp(String pkg) { return "com.bb10.tasks".equals(pkg); }

    public List<NoteItem> notes() {
        if (!notesWatched) {
            notesWatched = true;
            watch(NOTES, this::loadNotes);
            loadNotes();
        }
        return notes;
    }

    public List<TaskItem> tasks() {
        if (!tasksWatched) {
            tasksWatched = true;
            watch(TASKS, this::loadTasks);
            loadTasks();
        }
        return tasks;
    }

    private void watch(Uri uri, Runnable reload) {
        try {
            ctx.getContentResolver().registerContentObserver(uri, true, new android.database.ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    reload.run();
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void loadNotes() {
        LauncherApp.get().bg.execute(() -> {
            List<NoteItem> out = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(NOTES, null, null, null, null)) {
                while (c != null && c.moveToNext() && out.size() < 8) {
                    NoteItem n = new NoteItem();
                    String title = c.getString(c.getColumnIndexOrThrow("title")).trim();
                    String body = c.getString(c.getColumnIndexOrThrow("body")).trim();
                    if (title.isEmpty()) {
                        int nl = body.indexOf('\n');
                        title = nl < 0 ? body : body.substring(0, nl).trim();
                        body = nl < 0 ? "" : body.substring(nl + 1).trim();
                    }
                    n.heading = title.isEmpty() ? "Untitled" : title;
                    n.text = body.replace('\n', ' ');
                    n.modified = c.getLong(c.getColumnIndexOrThrow("modified"));
                    out.add(n);
                }
            } catch (Exception ignored) {
            }
            h.post(() -> {
                notes = out;
                fire();
            });
        });
    }

    private void loadTasks() {
        LauncherApp.get().bg.execute(() -> {
            List<TaskItem> out = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(TASKS, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    TaskItem t = new TaskItem();
                    t.title = c.getString(c.getColumnIndexOrThrow("title"));
                    t.due = c.getLong(c.getColumnIndexOrThrow("due"));
                    t.hasTime = c.getInt(c.getColumnIndexOrThrow("has_time")) == 1;
                    t.flagged = c.getInt(c.getColumnIndexOrThrow("priority")) > 0;
                    out.add(t);
                }
            } catch (Exception ignored) {
            }
            h.post(() -> {
                tasks = out;
                fire();
            });
        });
    }

    public static class DeviceStats {
        public int battery = -1;
        public boolean charging;
        public float batteryTemp;
        public long memUsed, memTotal, diskUsed, diskTotal;
    }

    private volatile DeviceStats stats;
    private long statsAsked;
    private boolean statsLoading;
    private String settingsPkg;

    public boolean isSettingsApp(String pkg) {
        if ("com.android.settings".equals(pkg)) return true;
        if (settingsPkg == null) {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(new Intent(android.provider.Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY);
            settingsPkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
        }
        return pkg.equals(settingsPkg);
    }

    public DeviceStats deviceStats() {
        long now = android.os.SystemClock.uptimeMillis();
        boolean idle = now - statsAsked > 6000;
        statsAsked = now;
        if (stats == null || idle) loadStats();
        return stats;
    }

    private final Runnable statsAgain = this::loadStats;

    private void loadStats() {
        if (statsLoading) return;
        statsLoading = true;
        h.removeCallbacks(statsAgain);
        LauncherApp.get().bg.execute(() -> {
            DeviceStats s = new DeviceStats();
            try {
                Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                    s.battery = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, scale));
                    int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
                    s.charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
                    s.batteryTemp = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                }
            } catch (Exception ignored) {
            }
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                ctx.getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi);
                s.memTotal = mi.totalMem;
                s.memUsed = mi.totalMem - mi.availMem;
            } catch (Exception ignored) {
            }
            try {
                android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                s.diskTotal = fs.getTotalBytes();
                s.diskUsed = fs.getTotalBytes() - fs.getAvailableBytes();
            } catch (Exception ignored) {
            }
            h.post(() -> {
                stats = s;
                statsLoading = false;
                fire();
                if (android.os.SystemClock.uptimeMillis() - statsAsked < 6000) h.postDelayed(statsAgain, 4000);
            });
        });
    }

    public static class WeatherNow {
        public String place = "", unit = "", symbol = "", desc = "";
        public int temp;
        public Integer hi, lo;
        public long updated;
        public final List<WeatherHour> hours = new ArrayList<>();
    }

    public static class WeatherHour {
        public long time;
        public int temp;
        public String symbol = "";
        public String label = "";
    }

    private static final Uri WEATHER = Uri.parse("content://com.bb10.weather.provider");
    private volatile WeatherNow weather;
    private long weatherAt;
    private boolean weatherWatched, weatherLoading;

    public boolean isWeatherApp(String pkg) { return "com.bb10.weather".equals(pkg); }

    public WeatherNow weather() {
        if (!weatherWatched) {
            weatherWatched = true;
            watch(WEATHER, this::loadWeather);
        }
        if (!weatherLoading && System.currentTimeMillis() - weatherAt > 10 * 60_000L) loadWeather();
        return weather;
    }

    private void loadWeather() {
        weatherLoading = true;
        weatherAt = System.currentTimeMillis();
        LauncherApp.get().bg.execute(() -> {
            WeatherNow w = null;
            try (Cursor c = ctx.getContentResolver().query(Uri.withAppendedPath(WEATHER, "current"), null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    w = new WeatherNow();
                    w.place = c.getString(c.getColumnIndexOrThrow("place"));
                    w.temp = c.getInt(c.getColumnIndexOrThrow("temp"));
                    w.unit = c.getString(c.getColumnIndexOrThrow("unit"));
                    w.symbol = c.getString(c.getColumnIndexOrThrow("symbol"));
                    w.desc = c.getString(c.getColumnIndexOrThrow("desc"));
                    int hi = c.getColumnIndexOrThrow("hi"), lo = c.getColumnIndexOrThrow("lo");
                    w.hi = c.isNull(hi) ? null : c.getInt(hi);
                    w.lo = c.isNull(lo) ? null : c.getInt(lo);
                    w.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
                }
            } catch (Exception ignored) {
            }
            if (w != null) {
                boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
                try (Cursor c = ctx.getContentResolver().query(Uri.withAppendedPath(WEATHER, "hours"), null, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        WeatherHour h = new WeatherHour();
                        h.time = c.getLong(0);
                        h.temp = c.getInt(1);
                        h.symbol = c.getString(2);
                        h.label = android.text.format.DateFormat.format(h24 ? "HH:mm" : "h a", h.time).toString();
                        w.hours.add(h);
                    }
                } catch (Exception ignored) {
                }
            }
            WeatherNow out = w;
            h.post(() -> {
                weather = out;
                weatherLoading = false;
                fire();
            });
        });
    }

    public boolean canReadCalls() {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    public List<CallEntry> recentCalls() {
        if (System.currentTimeMillis() - callsAt > 60_000 && !callsLoading) refreshCalls();
        return calls;
    }

    private void refreshCalls() {
        callsLoading = true;
        if (callObserver == null) {
            callObserver = new android.database.ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    callsAt = 0;
                    if (!callsLoading) refreshCalls();
                }
            };
            try {
                ctx.getContentResolver().registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callObserver);
            } catch (Exception ignored) {
            }
        }
        LauncherApp.get().bg.execute(() -> {
            List<CallEntry> out = new ArrayList<>();
            boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
            String iso = java.util.Locale.getDefault().getCountry();
            String[] cols = {android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.NEW};
            try (Cursor c = ctx.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, cols, null, null,
                    android.provider.CallLog.Calls.DATE + " DESC")) {
                while (c != null && c.moveToNext() && out.size() < 8) {
                    CallEntry e = new CallEntry();
                    e.name = c.getString(0) == null ? "" : c.getString(0);
                    e.number = c.getString(1) == null ? "" : c.getString(1);
                    e.time = c.getLong(2);
                    e.type = c.getInt(3);
                    e.isNew = c.getInt(4) == 1;
                    e.who = !e.name.isEmpty() ? e.name : e.number.isEmpty() ? "Private Number" : formatted(e.number, iso);
                    e.when = android.text.format.DateUtils.isToday(e.time)
                            ? android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", e.time).toString()
                            : android.text.format.DateFormat.format("MMM d", e.time).toString();
                    out.add(e);
                }
            } catch (Exception ignored) {
            }
            h.post(() -> {
                calls = out;
                callsAt = System.currentTimeMillis();
                callsLoading = false;
                fire();
            });
        });
    }
}
