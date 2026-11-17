package com.bb10.launcher.model;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.view.View;
import android.widget.Toast;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AppRepository {
    public interface Listener {
        void onAppsChanged();
    }

    private final Context ctx;
    private final Prefs prefs;
    private final LauncherApps la;
    private final File file;
    private final Map<String, AppInfo> apps = new HashMap<>();
    private final List<GridItem> layout = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean loaded;
    private boolean seedPending;

    public AppRepository(Context c, Prefs p) {
        ctx = c;
        prefs = p;
        la = c.getSystemService(LauncherApps.class);
        file = new File(c.getFilesDir(), "layout.json");
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String pkg, UserHandle u) { reload(); }
            @Override public void onPackageAdded(String pkg, UserHandle u) { reload(); }
            @Override public void onPackageChanged(String pkg, UserHandle u) { reload(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reload(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reload(); }
        }, new Handler(Looper.getMainLooper()));
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public void ensureLoaded() { if (!loaded) reload(); }

    public void reload() {
        apps.clear();
        for (LauncherActivityInfo i : la.getActivityList(null, Process.myUserHandle())) {
            AppInfo a = new AppInfo(i);
            apps.put(a.key, a);
        }
        if (!loaded) readLayout();
        else com.bb10.launcher.icons.IconPackManager.rescanAsync(ctx);
        com.bb10.launcher.icons.BB10Icons.invalidate();
        loaded = true;
        sync();
        LauncherApp app = LauncherApp.get();
        app.icons.invalidateAll();
        if (app.frames != null) app.frames.onPackagesChanged();
        for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
    }

    public void resetLayout() {
        layout.clear();
        sync();
        for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
    }

    private void sync() {
        Set<String> hidden = prefs.set(Prefs.HIDDEN);
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        for (Iterator<GridItem> it = layout.iterator(); it.hasNext(); ) {
            GridItem g = it.next();
            if (g.type == GridItem.APP) {
                if (!apps.containsKey(g.app) || hidden.contains(g.app) || !seen.add(g.app)) {
                    it.remove();
                    changed = true;
                }
            } else {
                for (Iterator<String> fi = g.apps.iterator(); fi.hasNext(); ) {
                    String k = fi.next();
                    if (!apps.containsKey(k) || hidden.contains(k) || !seen.add(k)) {
                        fi.remove();
                        changed = true;
                    }
                }
                if (g.apps.isEmpty()) {
                    it.remove();
                    changed = true;
                }
            }
        }
        for (int i = 0; i < layout.size(); i++) {
            GridItem g = layout.get(i);
            if (g.type == GridItem.FOLDER && g.apps.size() == 1) {
                layout.set(i, GridItem.app(g.apps.get(0)));
                changed = true;
            }
        }
        if (seedPending) {
            seedPending = false;
            for (String k : DefaultLayout.firstPage(ctx, apps.values(), prefs)) {
                if (hidden.contains(k) || !seen.add(k)) continue;
                layout.add(GridItem.app(k));
                changed = true;
            }
        }
        List<AppInfo> missing = new ArrayList<>();
        for (AppInfo a : apps.values()) if (!seen.contains(a.key) && !hidden.contains(a.key)) missing.add(a);
        missing.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        for (AppInfo a : missing) {
            if (a.cn.getClassName().endsWith(".HubShortcutActivity")) layout.add(0, GridItem.app(a.key));
            else layout.add(GridItem.app(a.key));
            changed = true;
        }
        if (changed) save();
    }

    public List<GridItem> layout() { return layout; }

    public AppInfo app(String key) { return key == null ? null : apps.get(key); }

    public Collection<AppInfo> all() { return apps.values(); }

    public AppInfo firstForPackage(String pkg) {
        AppInfo best = null;
        for (AppInfo a : apps.values()) {
            if (a.pkg.equals(pkg) && (best == null || a.key.compareTo(best.key) < 0)) best = a;
        }
        return best;
    }

    public String labelForPackage(String pkg) {
        String mail = com.bb10.launcher.mail.Mail.label(ctx, pkg);
        if (mail != null) return mail;
        if (com.bb10.launcher.hub.HubKinds.NOTIF.equals(pkg)) return "Notifications";
        if (com.bb10.launcher.hub.HubKinds.CALLS.equals(pkg)) return "Call Logs";
        AppInfo a = firstForPackage(pkg);
        if (a != null) return a.label();
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    public List<AppInfo> search(String q) {
        String lq = q.toLowerCase(Locale.ROOT).trim();
        List<AppInfo> starts = new ArrayList<>(), words = new ArrayList<>(), contains = new ArrayList<>();
        if (lq.isEmpty()) return starts;
        Set<String> hidden = prefs.set(Prefs.HIDDEN);
        for (AppInfo a : apps.values()) {
            if (hidden.contains(a.key)) continue;
            String l = a.label().toLowerCase(Locale.ROOT);
            if (l.startsWith(lq)) starts.add(a);
            else if (l.contains(" " + lq)) words.add(a);
            else if (l.contains(lq) || a.originalLabel.toLowerCase(Locale.ROOT).contains(lq)) contains.add(a);
        }
        starts.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        words.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        contains.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        starts.addAll(words);
        starts.addAll(contains);
        return starts;
    }

    public void launch(AppInfo a, View src, android.graphics.Bitmap thumb) {
        Rect r = null;
        Bundle opts = null;
        if (src != null && src.isAttachedToWindow()) {
            int[] loc = new int[2];
            src.getLocationOnScreen(loc);
            r = new Rect(loc[0], loc[1], loc[0] + src.getWidth(), loc[1] + src.getHeight());
            opts = thumb != null
                    ? ActivityOptions.makeThumbnailScaleUpAnimation(src, thumb, 0, 0).toBundle()
                    : ActivityOptions.makeScaleUpAnimation(src, 0, 0, src.getWidth(), src.getHeight()).toBundle();
        }
        try {
            la.startMainActivity(a.cn, a.info.getUser(), r, opts);
        } catch (Exception e) {
            Toast.makeText(ctx, "Can't open " + a.label(), Toast.LENGTH_SHORT).show();
        }
    }

    public void move(int from, int to) {
        if (from < 0 || from >= layout.size()) return;
        GridItem g = layout.remove(from);
        if (to > from) to--;
        to = Math.max(0, Math.min(to, layout.size()));
        layout.add(to, g);
        save();
    }

    public void merge(int from, int target) {
        if (from == target || from < 0 || target < 0 || from >= layout.size() || target >= layout.size()) return;
        GridItem src = layout.get(from), dst = layout.get(target);
        if (src.type != GridItem.APP) return;
        if (dst.type == GridItem.FOLDER) {
            dst.apps.add(src.app);
        } else {
            GridItem f = GridItem.folder("Folder");
            f.apps.add(dst.app);
            f.apps.add(src.app);
            layout.set(target, f);
        }
        layout.remove(from);
        save();
    }

    public void removeFromFolder(GridItem folder, String key) {
        int idx = layout.indexOf(folder);
        if (idx < 0 || !folder.apps.remove(key)) return;
        layout.add(idx + 1, GridItem.app(key));
        if (folder.apps.size() == 1) layout.set(idx, GridItem.app(folder.apps.get(0)));
        else if (folder.apps.isEmpty()) layout.remove(idx);
        save();
    }

    public void ungroup(GridItem folder) {
        int idx = layout.indexOf(folder);
        if (idx < 0) return;
        layout.remove(idx);
        for (int i = 0; i < folder.apps.size(); i++) layout.add(idx + i, GridItem.app(folder.apps.get(i)));
        save();
    }

    public void hide(String key) {
        Set<String> s = prefs.set(Prefs.HIDDEN);
        s.add(key);
        prefs.putSet(Prefs.HIDDEN, s);
        sync();
        for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
    }

    public void save() {
        try {
            JSONArray a = new JSONArray();
            for (GridItem g : layout) a.put(g.toJson());
            JSONObject o = new JSONObject();
            o.put("items", a);
            byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(data);
            }
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }

    private void readLayout() {
        layout.clear();
        if (!file.exists()) {
            seedPending = true;
            return;
        }
        try {
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray a = new JSONObject(s).optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) layout.add(GridItem.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }
}
