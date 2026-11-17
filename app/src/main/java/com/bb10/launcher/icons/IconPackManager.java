package com.bb10.launcher.icons;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;

import com.bb10.launcher.LauncherApp;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IconPackManager {
    private static final String[] TAGS = {
            "org.adw.launcher.THEMES",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.gau.go.launcherex.theme",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
            "com.dlto.atom.launcher.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "app.lawnchair.icons.THEMED_ICON",
            "com.lge.launcher2.THEME",
            "net.oneplus.launcher.icons.ACTION_PICK_ICON",
            "com.sec.android.app.launcher.THEMES",
    };
    private static final Map<String, IconPack> sCache = new HashMap<>();
    private static Set<String> sScanned;

    private IconPackManager() {}

    public static void rescanAsync(Context c) {
        Context app = c.getApplicationContext();
        LauncherApp.get().bg.execute(() -> {
            Set<String> found = scan(app);
            synchronized (IconPackManager.class) {
                sScanned = found;
            }
        });
    }

    private static Set<String> scan(Context c) {
        Set<String> out = new LinkedHashSet<>();
        PackageManager pm = c.getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(c.getPackageName())) continue;
            try {
                Resources r = pm.getResourcesForApplication(ai);
                if (r.getIdentifier("appfilter", "xml", ai.packageName) != 0) {
                    out.add(ai.packageName);
                    continue;
                }
                try (InputStream is = r.getAssets().open("appfilter.xml")) {
                    if (is != null) out.add(ai.packageName);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static List<String[]> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> pkgs = new LinkedHashSet<>();
        for (String tag : TAGS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(tag), PackageManager.GET_RESOLVED_FILTER)) {
                pkgs.add(ri.activityInfo.packageName);
            }
            Intent cat = new Intent(Intent.ACTION_MAIN).addCategory(tag);
            for (ResolveInfo ri : pm.queryIntentActivities(cat, 0)) pkgs.add(ri.activityInfo.packageName);
        }
        Set<String> scanned;
        synchronized (IconPackManager.class) {
            scanned = sScanned;
        }
        if (scanned == null) {
            scanned = scan(c);
            synchronized (IconPackManager.class) {
                sScanned = scanned;
            }
        }
        pkgs.addAll(scanned);
        pkgs.remove(c.getPackageName());

        LinkedHashMap<String, String> found = new LinkedHashMap<>();
        for (String p : pkgs) {
            try {
                found.put(p, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))));
            } catch (Exception ignored) {
            }
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : found.entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
        out.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    public static synchronized IconPack get(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        IconPack p = sCache.get(pkg);
        if (p == null) {
            String label;
            try {
                PackageManager pm = c.getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Exception e) {
                return null;
            }
            p = new IconPack(pkg, label);
            p.load(c);
            sCache.put(pkg, p);
        }
        return p;
    }

    public static synchronized void clear() {
        sCache.clear();
    }
}
