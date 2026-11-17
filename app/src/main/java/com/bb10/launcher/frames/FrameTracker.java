package com.bb10.launcher.frames;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.RootShell;
import com.bb10.launcher.gesture.GestureService;
import com.bb10.launcher.quickstep.Tasks;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrameTracker {
    public interface Listener {
        void onFramesChanged();
    }

    public static class Frame {
        public final String pkg;
        public Bitmap thumb;
        public long time;
        public boolean locked;

        public boolean left = true;

        Frame(String p) { pkg = p; }
    }

    public boolean toggleLock(String pkg) {
        Frame f = find(pkg);
        if (f == null) return false;
        f.locked = !f.locked;
        persistLocks();
        fire();
        return f.locked;
    }

    private void persistLocks() {
        Set<String> s = new HashSet<>();
        for (Frame f : frames) if (f.locked) s.add(f.pkg);
        store.edit().putStringSet("locked", s).apply();
    }

    private final Context ctx;
    private final Prefs prefs;
    private final PackageManager pm;
    private final SharedPreferences store;
    private final File dir;
    private final List<Frame> frames = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Boolean> trackCache = new HashMap<>();
    private final Map<String, Long> closedAt = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private Set<String> excluded;

    private final long createdAt = android.os.SystemClock.uptimeMillis();

    public FrameTracker(Context c, Prefs p) {
        ctx = c;
        prefs = p;
        pm = c.getPackageManager();
        store = c.getSharedPreferences("frames", Context.MODE_PRIVATE);
        dir = new File(c.getCacheDir(), "frames");
        dir.mkdirs();
        Set<String> locked = store.getStringSet("locked", java.util.Collections.emptySet());
        for (String s : store.getString("list", "").split(",")) {
            if (s.isEmpty()) continue;
            Frame f = new Frame(s);
            f.locked = locked.contains(s);
            frames.add(f);
        }
        List<Frame> snapshot = new ArrayList<>(frames);
        LauncherApp.get().bg.execute(() -> {
            for (Frame f : snapshot) {
                File fl = file(f.pkg);
                if (!fl.exists()) continue;
                Bitmap b = BitmapFactory.decodeFile(fl.getPath());
                if (b != null) h.post(() -> {
                    if (f.thumb == null) {
                        f.thumb = b;
                        fire();
                    }
                });
            }
        });
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public List<Frame> list() { return frames; }

    public boolean trackable(String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || pkg.equals("android")
                || pkg.equals("com.android.systemui")) return false;
        Boolean c = trackCache.get(pkg);
        if (c != null) return c;
        boolean ok = pm.getLaunchIntentForPackage(pkg) != null && !excluded().contains(pkg) && !hidden(pkg);
        trackCache.put(pkg, ok);
        return ok;
    }

    private boolean hidden(String pkg) {
        for (String k : prefs.set(Prefs.HIDDEN)) if (k.startsWith(pkg + "/")) return true;
        return false;
    }

    private Set<String> excluded() {
        if (excluded == null) {
            excluded = new HashSet<>();
            InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
            if (imm != null) for (InputMethodInfo i : imm.getInputMethodList()) excluded.add(i.getPackageName());
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (!ri.activityInfo.packageName.equals("com.android.settings")) excluded.add(ri.activityInfo.packageName);
            }
        }
        return excluded;
    }

    public void onPackagesChanged() {
        trackCache.clear();
        excluded = null;
        h.removeCallbacks(dropGone);
        h.postDelayed(dropGone, 3000);
    }

    private final Runnable dropGone = () -> {
        trackCache.clear();
        excluded = null;
        boolean changed = frames.removeIf(f -> {
            boolean drop = f.locked ? !installed(f.pkg) : !trackable(f.pkg);
            if (drop) android.util.Log.i("BB10Frames", "drop (" + (f.locked ? "locked, uninstalled" : "not trackable") + ") " + f.pkg);
            return drop;
        });
        if (changed) {
            persist();
            persistLocks();
            fire();
        }
    };

    private boolean installed(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public boolean isLocked(String pkg) {
        Frame f = find(pkg);
        return f != null && f.locked;
    }

    public void onForeground(String pkg) {
        if (!trackable(pkg)) return;
        beforePrepare = null;
        preparedPkg = null;
        closedAt.remove(pkg);
        if (LauncherApp.get().live != null) LauncherApp.get().live.appOpened(pkg);
        lastForeground = pkg;
        Frame f = find(pkg);
        if (f != null && frames.indexOf(f) == 0) return;
        if (f != null && arranged()) {
            f.time = System.currentTimeMillis();
            return;
        }
        if (f != null) frames.remove(f);
        else {
            f = new Frame(pkg);
            f.left = false;
        }
        f.time = System.currentTimeMillis();
        frames.add(0, f);
        trim();
        persist();
        fire();
    }

    public void bringFirst(String pkg) {
        String keep = lastForeground;
        onForeground(pkg);
        lastForeground = keep;
    }

    private volatile String lastForeground;

    private List<Frame> beforePrepare;
    private String preparedPkg;

    public void prepareFirst(String pkg) {
        List<Frame> before = new ArrayList<>(frames);
        bringFirst(pkg);
        beforePrepare = before;
        preparedPkg = pkg;
    }

    public void unprepare(String pkg) {
        if (pkg == null || !pkg.equals(preparedPkg) || beforePrepare == null) return;
        frames.clear();
        frames.addAll(beforePrepare);
        beforePrepare = null;
        preparedPkg = null;
        persist();
        fire();
    }

    public String lastForeground() {
        return lastForeground;
    }

    public boolean arranged() {
        return prefs.bool(Prefs.FRAMES_ARRANGE, false) && store.getBoolean("manual", false);
    }

    public void move(int from, int to) {
        if (from < 0 || from >= frames.size() || to < 0 || to >= frames.size() || from == to) return;
        frames.add(to, frames.remove(from));
        store.edit().putBoolean("manual", true).apply();
        persist();
        fire();
    }

    public void markLeft(String pkg) {
        Frame f = find(pkg);
        if (f != null) f.left = true;
    }

    public void setThumb(String pkg, Bitmap b) {
        Frame f = find(pkg);
        if (f == null || b == null) return;
        f.thumb = b;
        fire();
        LauncherApp.get().bg.execute(() -> {
            try (FileOutputStream os = new FileOutputStream(file(pkg))) {
                b.compress(Bitmap.CompressFormat.JPEG, 82, os);
            } catch (Exception ignored) {
            }
        });
    }

    public void close(String pkg) {
        android.util.Log.i("BB10Frames", "close " + pkg);
        Frame f = find(pkg);
        if (f == null) return;
        frames.remove(f);
        closedAt.put(pkg, System.currentTimeMillis());
        if (LauncherApp.get().live != null) LauncherApp.get().live.appClosed(pkg);
        file(pkg).delete();
        persist();
        if (f.locked) persistLocks();
        fire();
        stopApp(pkg);
    }

    public void closeAll() {
        boolean anyLocked = false;
        for (Frame f : new ArrayList<>(frames)) {
            if (f.locked) {
                anyLocked = true;
                continue;
            }
            frames.remove(f);
            closedAt.put(f.pkg, System.currentTimeMillis());
            if (LauncherApp.get().live != null) LauncherApp.get().live.appClosed(f.pkg);
            file(f.pkg).delete();
            stopApp(f.pkg);
        }
        if (!anyLocked) store.edit().putBoolean("manual", false).apply();
        persist();
        fire();
    }

    private void stopApp(String pkg) {
        if (prefs.bool(Prefs.FRAMES_KILL, false)) {
            RootShell.execAsync(r -> {
                if (!r.ok) killBackground(pkg);
            }, "am force-stop " + pkg);
            return;
        }
        LauncherApp.get().bg.execute(() -> {
            Integer id = Tasks.taskIds(ctx).get(pkg);
            if (id != null) Tasks.remove(id);
        });
    }

    private static final long NEW_FRAME_GRACE_MS = 8000;

    private volatile boolean snapshotsWork;

    public boolean usesTaskSnapshots() {
        return snapshotsWork;
    }

    public void refreshFromTasks() {
        List<String> pkgs = new ArrayList<>();
        for (Frame f : frames) pkgs.add(f.pkg);
        int max = Math.round(com.bb10.launcher.ui.Ui.S() / 2f);
        LauncherApp.get().bg.execute(() -> {
            Map<String, Integer> ids = Tasks.taskIds(ctx);
            if (ids.isEmpty()) return;
            Map<String, Bitmap> shots = new HashMap<>();
            for (String p : pkgs) {
                Integer id = ids.get(p);
                if (id == null) continue;
                Bitmap b = Tasks.snapshot(id, Math.max(360, max), true);
                if (b != null) shots.put(p, b);
            }
            if (!shots.isEmpty() || Tasks.anySeen()) snapshotsWork = true;
            h.post(() -> {
                for (Map.Entry<String, Bitmap> e : shots.entrySet()) {
                    Frame f = find(e.getKey());
                    if (f != null) f.thumb = e.getValue();
                }
                if (!shots.isEmpty()) fire();
            });
        });
    }

    public void snapshotNow(String pkg) {
        if (pkg == null || find(pkg) == null) return;
        int max = Math.round(com.bb10.launcher.ui.Ui.S() / 2f);
        LauncherApp.get().bg.execute(() -> {
            Integer id = Tasks.taskIds(ctx).get(pkg);
            if (id == null) return;
            Bitmap b = Tasks.snapshot(id, Math.max(360, max), true);
            if (b == null) b = Tasks.freshSnapshot(id, Math.max(360, max));
            com.bb10.launcher.quickstep.ShellTransitions.note("closing app's frame: " + (b != null ? "fresh snapshot" : "no fresh snapshot, using Android's stored one"));
            for (int i = 0; i < 3 && b == null; i++) {
                b = Tasks.snapshot(id, Math.max(360, max));
                if (b == null) android.os.SystemClock.sleep(60);
            }
            if (b == null) return;
            Bitmap fb = b;
            h.post(() -> {
                Frame f = find(pkg);
                if (f == null) return;
                f.thumb = fb;
                fire();
            });
        });
    }

    public void freshen(String pkg) {
        if (pkg == null || find(pkg) == null) return;
        int max = Math.round(com.bb10.launcher.ui.Ui.S() / 2f);
        LauncherApp.get().bg.execute(() -> {
            Integer id = Tasks.taskIds(ctx).get(pkg);
            if (id == null) return;
            Bitmap b = Tasks.freshSnapshot(id, Math.max(360, max));
            if (b == null) return;
            h.post(() -> {
                Frame f = find(pkg);
                if (f == null) return;
                f.thumb = b;
                fire();
            });
        });
    }

    public void afterLeft(String pkg) {
        if (pkg == null || find(pkg) == null) return;
        markLeft(pkg);
        int max = Math.round(com.bb10.launcher.ui.Ui.S() / 2f);
        for (long delay : new long[]{700, 2000}) {
            h.postDelayed(() -> LauncherApp.get().bg.execute(() -> {
                Integer id = Tasks.taskIds(ctx).get(pkg);
                if (id == null) return;
                Bitmap b = Tasks.snapshot(id, Math.max(360, max), true);
                if (b == null) return;
                com.bb10.launcher.quickstep.ShellTransitions.note("left for another app: " + pkg + "'s frame updated");
                h.post(() -> {
                    Frame f = find(pkg);
                    if (f == null) return;
                    f.thumb = b;
                    fire();
                });
            }), delay);
        }
    }

    private void killBackground(String pkg) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);
    }

    public void refreshFromUsage() {
        if (GestureService.isRunning()) return;
        if (android.os.SystemClock.uptimeMillis() - createdAt < 60_000) return;
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        UsageEvents ev;
        try {
            ev = usm.queryEvents(now - 6 * 3600_000L, now);
        } catch (Exception e) {
            return;
        }
        if (ev == null) return;
        Map<String, Long> last = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
        }
        if (last.isEmpty()) return;
        List<Map.Entry<String, Long>> es = new ArrayList<>(last.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int max = prefs.getInt(Prefs.FRAMES_MAX, 0);
        if (max <= 0) max = 40;
        Map<String, Integer> open = Tasks.taskIds(ctx);
        List<Frame> nf = new ArrayList<>();
        for (Map.Entry<String, Long> en : es) {
            String p = en.getKey();
            if (!trackable(p)) continue;
            if (!open.isEmpty() && !open.containsKey(p)) continue;
            Long c = closedAt.get(p);
            if (c != null && c >= en.getValue()) continue;
            Frame f = find(p);
            if (f == null) f = new Frame(p);
            f.time = en.getValue();
            nf.add(f);
            if (nf.size() >= max) break;
        }
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.locked && !nf.contains(f)) nf.add(Math.min(i, nf.size()), f);
        }
        boolean same = nf.size() == frames.size();
        for (int i = 0; same && i < nf.size(); i++) same = nf.get(i).pkg.equals(frames.get(i).pkg);
        if (!same) {
            frames.clear();
            frames.addAll(nf);
            persist();
            fire();
        }
    }

    private void trim() {
        int max = prefs.getInt(Prefs.FRAMES_MAX, 0);
        if (max <= 0) return;
        for (int i = frames.size() - 1; i >= 0 && frames.size() > max; i--) {
            Frame r = frames.get(i);
            if (r.locked) continue;
            frames.remove(i);
            android.util.Log.i("BB10Frames", "drop (too many) " + r.pkg);
            file(r.pkg).delete();
        }
    }

    private Frame find(String pkg) {
        for (Frame f : frames) if (f.pkg.equals(pkg)) return f;
        return null;
    }

    private File file(String pkg) { return new File(dir, pkg + ".jpg"); }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (Frame f : frames) {
            if (sb.length() > 0) sb.append(',');
            sb.append(f.pkg);
        }
        store.edit().putString("list", sb.toString()).apply();
    }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) l.onFramesChanged();
    }
}
