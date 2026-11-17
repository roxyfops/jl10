package com.bb10.launcher.model;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import com.bb10.launcher.Prefs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DefaultLayout {
    private DefaultLayout() {}

    private static final class Slot {
        final String name;
        final String[] pkgs;
        final String category;

        Slot(String name, String category, String... pkgs) {
            this.name = name;
            this.category = category;
            this.pkgs = pkgs;
        }
    }

    private static final String SMS = "#sms", DIALER = "#dialer", BROWSER = "#browser";

    private static final Slot[] SLOTS = {
            new Slot(null, null, "#hub"),
            new Slot("Contacts", Intent.CATEGORY_APP_CONTACTS, "com.bb10.contacts", "com.google.android.contacts",
                    "com.android.contacts", "com.samsung.android.app.contacts", "org.fossify.contacts"),
            new Slot("Calendar", Intent.CATEGORY_APP_CALENDAR, "com.bb10.calendar", "com.google.android.calendar",
                    "org.lineageos.etar", "ws.xsoh.etar", "com.android.calendar", "org.fossify.calendar", "com.samsung.android.calendar"),
            new Slot("BBM", null, "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
                    "org.telegram.plus", "com.whatsapp", "org.thoughtcrime.securesms", "com.facebook.orca", "com.discord", "com.bbm"),
            new Slot("Messages", Intent.CATEGORY_APP_MESSAGING, "com.google.android.apps.messaging", SMS,
                    "com.android.messaging", "com.android.mms", "org.fossify.messages", "com.samsung.android.messaging"),
            new Slot("BlackBerry World", Intent.CATEGORY_APP_MARKET, "com.android.vending", "com.aurora.store",
                    "org.fdroid.fdroid", "com.looker.droidify", "cm.aptoide.pt", "com.amazon.venezia"),
            new Slot("Browser", Intent.CATEGORY_APP_BROWSER, "org.lineageos.jelly", "com.android.browser", BROWSER,
                    "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "app.vanadium.browser", "com.sec.android.app.sbrowser"),
            new Slot("Docs To Go", null, "com.onlyoffice.documents", "com.google.android.apps.docs.editors.docs",
                    "com.microsoft.office.officehubrow", "com.microsoft.office.word", "cn.wps.moffice_eng",
                    "org.documentfoundation.libreoffice", "com.collabora.libreoffice", "com.google.android.apps.docs"),
            new Slot("Maps", Intent.CATEGORY_APP_MAPS, "com.google.android.apps.maps", "app.organicmaps", "net.osmand.plus",
                    "net.osmand", "com.here.app.maps", "com.waze", "com.google.android.apps.mapslite"),
            new Slot("Tasks", null, "com.bb10.tasks", "com.google.android.apps.tasks", "com.microsoft.todos",
                    "com.todoist", "com.ticktick.task", "org.tasks"),
            new Slot("Pictures", Intent.CATEGORY_APP_GALLERY, "com.google.android.apps.photos", "org.lineageos.glimpse",
                    "com.android.gallery3d", "org.fossify.gallery", "com.sec.android.gallery3d", "com.google.android.apps.photosgo"),
            new Slot("Music", Intent.CATEGORY_APP_MUSIC, "com.google.android.apps.youtube.music",
                    "app.revanced.android.apps.youtube.music", "app.rvx.android.apps.youtube.music", "com.spotify.music",
                    "org.lineageos.eleven", "in.krosbits.musicolet", "org.fossify.musicplayer", "com.android.music"),
            new Slot("Videos", null, "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
                    "is.xyz.mpv", "com.google.android.videos"),
            new Slot("Notes", null, "com.bb10.notes", "com.google.android.keep", "org.fossify.notes",
                    "com.samsung.android.app.notes", "com.microsoft.office.onenote"),
            new Slot("Phone", null, "com.bb10.phone", DIALER, "com.google.android.dialer", "com.android.dialer",
                    "com.samsung.android.dialer"),
    };

    static List<String> firstPage(Context c, Collection<AppInfo> apps, Prefs prefs) {
        Map<String, AppInfo> byPkg = new LinkedHashMap<>();
        AppInfo hub = null;
        for (AppInfo a : apps) {
            if (a.pkg.equals(c.getPackageName())) {
                if (a.cn.getClassName().endsWith(".HubShortcutActivity")) hub = a;
                continue;
            }
            byPkg.putIfAbsent(a.pkg, a);
        }
        List<String> out = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Slot s : SLOTS) {
            AppInfo found = null;
            for (String p : s.pkgs) {
                String pkg = p.equals("#hub") ? null : resolve(c, p);
                if (p.equals("#hub")) found = hub;
                else if (pkg != null && !used.contains(pkg)) found = byPkg.get(pkg);
                if (found != null) break;
            }
            if (found == null && s.category != null) {
                PackageManager pm = c.getPackageManager();
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(s.category), 0)) {
                    AppInfo a = byPkg.get(ri.activityInfo.packageName);
                    if (a != null && !used.contains(a.pkg)) {
                        found = a;
                        break;
                    }
                }
            }
            if (found == null) continue;
            used.add(found.pkg);
            out.add(found.key);
            if (s.name != null && prefs.label(found.key) == null && !s.name.equals(found.originalLabel)) prefs.setLabel(found.key, s.name);
        }
        return out;
    }

    private static String resolve(Context c, String p) {
        try {
            switch (p) {
                case SMS:
                    return Telephony.Sms.getDefaultSmsPackage(c);
                case DIALER: {
                    TelecomManager tm = c.getSystemService(TelecomManager.class);
                    return tm == null ? null : tm.getDefaultDialerPackage();
                }
                case BROWSER: {
                    RoleManager rm = c.getSystemService(RoleManager.class);
                    ResolveInfo ri = c.getPackageManager().resolveActivity(
                            new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY);
                    String pkg = ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
                    return "android".equals(pkg) ? null : pkg;
                }
                default:
                    return p;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
