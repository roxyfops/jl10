package com.bb10.launcher.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import com.bb10.launcher.LauncherActivity;
import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.RootShell;
import com.bb10.launcher.gesture.GestureService;
import com.bb10.launcher.hub.HubListenerService;
import com.bb10.launcher.quickstep.RemoteAnims;
import com.bb10.launcher.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class SettingsTools {
    private SettingsTools() {}

    static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static AlertDialog.Builder dialog(Activity a) {
        boolean dark = "dark".equals(LauncherApp.get().prefs.sp.getString(Prefs.HUB_THEME, "bright"));
        return new AlertDialog.Builder(a, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    static void open(Activity a, Intent i) {
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "Not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    public static String status(Activity a, String key) {
        switch (key) {
            case "perm_notif": return HubListenerService.isGranted(a) ? "On" : "Off — tap to turn on";
            case "perm_a11y": return GestureService.isRunning() ? "On" : "Off — tap to turn on";
            case "perm_usage": return usageGranted(a) ? "On" : "Off — tap to turn on";
            case "set_default": return isDefaultHome(a) ? "JL10" : "Not JL10 — tap to change";
            case "nav_buttons": return navButtons(a) ? "Done — Android's gestures are off" : "Android's gestures are on — tap to use buttons";
            case "setup_screen": {
                int missing = (HubListenerService.isGranted(a) ? 0 : 1) + (GestureService.isRunning() ? 0 : 1)
                        + (usageGranted(a) ? 0 : 1) + (isDefaultHome(a) ? 0 : 1) + (navButtons(a) ? 0 : 1);
                return missing == 0 ? "All set" : missing + (missing == 1 ? " thing needs" : " things need") + " attention";
            }
            case "qs_status": {
                boolean perm = RemoteAnims.hasPermission(a), apis = RemoteAnims.hiddenApisOk();
                if (com.bb10.launcher.quickstep.ShellTransitions.homeRegistered()) return "Active";
                if (perm && apis) return "Installed; waiting for System UI (reboot if this stays)";
                if (GestureService.isRunning()) return "Not installed; using simpler fallback animations";
                return "Not installed";
            }
            case "sysui_status": {
                String st = Settings.Global.getString(a.getContentResolver(), "bb10_sysui_status");
                return st == null || st.isEmpty()
                        ? "Not active. Turn on JL10 in LSPosed Manager, then Restart System UI."
                        : st.startsWith(com.bb10.launcher.sysui.SysUiHook.SAFE_STATUS)
                        ? "Switched off: System UI kept crashing with it. Restart System UI to try again"
                        : st.contains("not available") || st.contains("failed")
                        ? "Active, with problems (see Diagnostics)"
                        : "Active";
            }
            case "font_pick": return Ui.fontFile(a).exists() ? "In use" : "Pick a .ttf or .otf";
        }
        return null;
    }

    public static boolean click(Activity a, String k, Runnable refresh) {
        switch (k) {
            case "setup_wizard":
                a.startActivity(new Intent(a, SetupWizardActivity.class));
                return true;
            case "root_setup":
                rootSetup(a, refresh);
                return true;
            case "nav_buttons":
                if (!navButtons(a)) setNavButtons(a, true, refresh);
                else dialog(a).setTitle("Turn Android's gestures back on?")
                        .setMessage("They use the same bottom edge as the BB10 gestures, so the two can get in each other's way.")
                        .setPositiveButton("Turn on", (d, w) -> setNavButtons(a, false, refresh))
                        .setNegativeButton("Keep buttons", null).show();
                return true;
            case "qs_install":
                dialog(a).setTitle("Make JL10 the QuickStep provider")
                        .setMessage("This installs a small Magisk/KernelSU module that tells Android to use JL10 for recents, so it can animate apps into and out of Active Frames.\n\n"
                                + "Other QuickStep-switching modules (QuickSwitch) are disabled so they don't conflict.\n\n"
                                + "Undo any time with “Remove QuickStep module” (or remove it in your root manager) and reboot.")
                        .setPositiveButton("Install", (d, w) -> installQuickstepModule(a))
                        .setNegativeButton("Cancel", null).show();
                return true;
            case "qs_diag":
                runDiagnostics(a);
                return true;
            case "sysui_restart":
                LauncherApp.get().syncSysUi();
                RootShell.execAsync(r -> {
                    if (!alive(a)) return;
                    if (!r.ok) Toast.makeText(a, "Couldn't restart System UI: " + r.out, Toast.LENGTH_LONG).show();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (alive(a)) refresh.run();
                    }, 5000);
                },
                        "rm -f /data/user_de/0/com.android.systemui/" + com.bb10.launcher.sysui.SysUiHook.SAFE_FILE
                                + " /data/user_de/0/com.android.systemui/" + com.bb10.launcher.sysui.SysUiHook.STARTS_FILE
                                + " /data/user_de/0/com.android.systemui/" + com.bb10.launcher.sysui.SysUiHook.OLD_STARTS_FILE,
                        "settings delete global bb10_sysui_status; sleep 0.3; pkill -f com.android.systemui || killall com.android.systemui");
                return true;
            case "qs_remove":
                RootShell.execAsync(r -> {
                    if (!alive(a)) return;
                    dialog(a).setTitle(r.ok ? "Module will be removed" : "Couldn't remove")
                            .setMessage(r.ok ? "Reboot to give recents back to the system launcher." : r.out)
                            .setPositiveButton("OK", null).show();
                }, "[ -f " + MODULE + "/old_launcher ] && pm enable \"$(cat " + MODULE + "/old_launcher)\" >/dev/null 2>&1",
                        "[ -d " + MODULE + " ] && touch " + MODULE + "/remove; [ -d " + OLD_MODULE + " ] && touch " + OLD_MODULE + "/remove",
                        "fab=$(cmd overlay list android 2>/dev/null | grep -o '[A-Za-z0-9_.]*:" + FAB_NAME + "' | head -1); [ -n \"$fab\" ] && cmd overlay disable --user 0 \"$fab\"",
                        UNDO_HIDDEN_API,
                        "echo ok");
                return true;
            case "perm_notif":
                open(a, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return true;
            case "perm_a11y":
                open(a, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return true;
            case "perm_usage":
                open(a, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                return true;
            case "set_default":
                open(a, new Intent(Settings.ACTION_HOME_SETTINGS));
                return true;
            case "wallpaper":
                open(a, new Intent(a, WallpaperActivity.class));
                return true;
            case "qs_edit":
                open(a, new Intent(a, QsEditActivity.class));
                return true;
            case "font_reset":
                Ui.fontFile(a).delete();
                fontChanged(a, refresh);
                return true;
            case "reset_layout":
                dialog(a).setTitle("Reset layout")
                        .setMessage("Remove all folders and sort apps alphabetically?")
                        .setPositiveButton("Reset", (d, w) -> LauncherApp.get().apps.resetLayout())
                        .setNegativeButton("Cancel", null).show();
                return true;
            case "hub_clear":
                dialog(a).setTitle("Clear Hub")
                        .setMessage("Delete all Hub items whose notifications are already gone?")
                        .setPositiveButton("Clear", (d, w) -> LauncherApp.get().hub.clearHistory())
                        .setNegativeButton("Cancel", null).show();
                return true;
        }
        return false;
    }

    private static final String NAV_OVERLAY = "com.android.internal.systemui.navbar.";

    static boolean navButtons(Context a) {
        try {
            return !a.getPackageManager().getApplicationInfo(NAV_OVERLAY + "gestural",
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (Exception e) {
            return Settings.Secure.getInt(a.getContentResolver(), "navigation_mode", 0) != 2;
        }
    }

    private static void setNavButtons(Activity a, boolean buttons, Runnable refresh) {
        String grep = " | sed 's/^package://' | grep '^" + NAV_OVERLAY + "gestural'";
        String cmd = buttons
                ? "for p in $(pm list packages -e" + grep + "); do pm disable $p >/dev/null || exit 1; done"
                : "for p in $(pm list packages -d" + grep + "); do pm enable $p >/dev/null || exit 1; done";
        RootShell.execAsync(r -> {
            if (!alive(a)) return;
            if (r.ok) {
                Toast.makeText(a, buttons ? "Android's gestures are off" : "Android's gestures are back on", Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (alive(a)) refresh.run();
                }, 800);
                return;
            }
            dialog(a).setTitle("Needs root")
                    .setMessage("Android's gestures can only be " + (buttons ? "disabled" : "turned back on")
                            + " with root. Grant root to JL10 in your root manager (Magisk, KernelSU, APatch), then try again."
                            + (r.out.trim().isEmpty() ? "" : "\n\n" + r.out.trim()))
                    .setPositiveButton("OK", null).show();
        }, cmd);
    }

    private static String installedAt(Activity a) {
        try {
            long t = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).lastUpdateTime;
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(t));
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean usageGranted(Context a) {
        android.app.AppOpsManager ops = a.getSystemService(android.app.AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), a.getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    static boolean isDefaultHome(Context a) {
        android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null && a.getPackageName().equals(ri.activityInfo.packageName);
    }

    private static void rootSetup(Activity a, Runnable refresh) {
        Toast.makeText(a, "Requesting root…", Toast.LENGTH_SHORT).show();
        List<String> cmds = rootSetupCmds(a);
        RootShell.execAsync(r -> {
            if (!alive(a)) return;
            if (!RootShell.knownAvailable()) {
                dialog(a).setTitle("Root not available")
                        .setMessage("Grant root to JL10 in your root manager (e.g. Magisk), then try again.\n\n" + r.out)
                        .setPositiveButton("OK", null).show();
                return;
            }
            verifySetup(a, r.out, 0, refresh);
        }, cmds.toArray(new String[0]));
    }

    static List<String> rootSetupCmds(Context a) {
        String me = a.getPackageName();
        String nl = new ComponentName(a, HubListenerService.class).flattenToString();
        String acc = new ComponentName(a, GestureService.class).flattenToString();
        String home = new ComponentName(a, LauncherActivity.class).flattenToString();
        List<String> cmds = new ArrayList<>();
        cmds.add("appops set " + me + " ACCESS_RESTRICTED_SETTINGS allow");
        cmds.add("cmd notification allow_listener " + nl);
        for (String p : requestedPermissions(a)) {
            try {
                android.content.pm.PermissionInfo pi = a.getPackageManager().getPermissionInfo(p, 0);
                int base = pi.getProtection(), flags = pi.getProtectionFlags();
                String op = android.app.AppOpsManager.permissionToOp(p);
                if (base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                        || (flags & android.content.pm.PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
                    cmds.add("pm grant " + me + " " + p + " 2>/dev/null");
                } else if ((flags & android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP) != 0 && op != null) {
                    cmds.add("appops set " + me + " " + op + " allow");
                }
            } catch (Exception ignored) {
            }
        }
        cmds.add("appops set " + me + " GET_USAGE_STATS allow");
        cmds.add("appops set com.bb10.clock SYSTEM_ALERT_WINDOW allow 2>/dev/null");
        cmds.add("cur=$(settings get secure enabled_accessibility_services); [ \"$cur\" = null ] && cur=''");
        cmds.add("rest=$(echo \"$cur\" | tr ':' '\\n' | grep -vxF '" + acc + "' | grep -v '^$' | tr '\\n' ':' | sed 's/:$//')");
        cmds.add("settings put secure enabled_accessibility_services \"${rest:-null}\"");
        cmds.add("sleep 1");
        cmds.add("settings put secure enabled_accessibility_services \"${rest:+$rest:}" + acc + "\"");
        cmds.add("settings put secure accessibility_enabled 1");
        cmds.add("cmd role add-role-holder android.app.role.HOME " + me + " 0 2>/dev/null");
        cmds.add("cmd package set-home-activity " + home);
        return cmds;
    }

    private static String[] requestedPermissions(Context a) {
        try {
            String[] p = a.getPackageManager().getPackageInfo(a.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            return p == null ? new String[0] : p;
        } catch (Exception e) {
            return new String[0];
        }
    }

    static List<String> setupMissing(Context a) {
        List<String> m = new ArrayList<>();
        if (!HubListenerService.isGranted(a)) m.add("Notification access");
        if (!GestureService.isRunning()) m.add("Accessibility service");
        if (!usageGranted(a)) m.add("Usage access");
        if (!isDefaultHome(a)) m.add("Default home screen");
        for (String p : requestedPermissions(a)) {
            try {
                if (a.getPackageManager().getPermissionInfo(p, 0).getProtection() == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                        && a.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    m.add(p.substring(p.lastIndexOf('.') + 1));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void verifySetup(Activity a, String out, int tries, Runnable refresh) {
        if (!alive(a)) return;
        List<String> missing = setupMissing(a);
        if (!missing.isEmpty() && tries < 8) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> verifySetup(a, out, tries + 1, refresh), 750);
            return;
        }
        refresh.run();
        String log = TextUtils.isEmpty(out.trim()) ? "" : "\n\nOutput:\n" + out.trim();
        if (missing.isEmpty()) {
            dialog(a).setTitle("Done")
                    .setMessage("Every permission is granted, notification access, the accessibility service and usage access are on, and JL10 is your home screen.")
                    .setPositiveButton("OK", null).show();
        } else {
            dialog(a).setTitle("Some things didn't take")
                    .setMessage("Still off:\n• " + TextUtils.join("\n• ", missing) + log)
                    .setPositiveButton("Try again", (d, w) -> rootSetup(a, refresh))
                    .setNegativeButton("OK", null).show();
        }
    }

    static final String MODULE = "/data/adb/modules/bb10launcher_quickstep";
    static final String OLD_MODULE = "/data/adb/modules/bb10launcher_system";
    static final String FAB_NAME = "BB10Recents";

    private static void copyAsset(Context a, String asset, File out) throws java.io.IOException {
        try (InputStream is = a.getAssets().open(asset); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    static List<String> moduleFileCmds(Context a) throws Exception {
        String me = a.getPackageName();
        File overlay = new File(a.getCacheDir(), "BB10QuickstepOverlay.apk");
        File script = new File(a.getCacheDir(), "post-fs-data.sh");
        File service = new File(a.getCacheDir(), "service.sh");
        copyAsset(a, "quickstep/BB10QuickstepOverlay.apk", overlay);
        copyAsset(a, "quickstep/post-fs-data.sh", script);
        copyAsset(a, "quickstep/service.sh", service);
        String[] perms = a.getPackageManager().getPackageInfo(me, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        if (perms == null) perms = new String[0];
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<permissions>\n    <privapp-permissions package=\"" + me + "\">\n");
        for (String p : perms) xml.append("        <permission name=\"").append(p).append("\"/>\n");
        xml.append("    </privapp-permissions>\n</permissions>\n");
        String mod = MODULE;
        String file = "privapp-permissions-" + me + ".xml";
        List<String> cmds = new ArrayList<>();
        cmds.add("printf 'id=bb10launcher_quickstep\\nname=JL10 QuickStep\\nversion=2\\nversionCode=2\\nauthor=SixFops\\ndescription=Makes JL10 the recents (QuickStep) provider for its window animations\\n' > " + mod + "/module.prop");
        cmds.add("printf 'allow system_file system_file filesystem associate\\n' > " + mod + "/sepolicy.rule");
        cmds.add("if command -v magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then for d in priv_app_36 priv_app_37; do "
                + "printf 'allow %s magisk unix_stream_socket connectto\\n' $d >> " + mod + "/sepolicy.rule; done; fi");
        cmds.add("cp '" + overlay.getAbsolutePath() + "' " + mod + "/BB10QuickstepOverlay.apk || { echo 'copy failed'; exit 1; }");
        cmds.add("cp '" + script.getAbsolutePath() + "' " + mod + "/post-fs-data.sh || { echo 'copy failed'; exit 1; }");
        cmds.add("cp '" + service.getAbsolutePath() + "' " + mod + "/service.sh || { echo 'copy failed'; exit 1; }");
        cmds.add("cp '" + a.getApplicationInfo().sourceDir + "' " + mod + "/BB10Launcher.apk || { echo 'copy failed'; exit 1; }");
        cmds.add("cat > " + mod + "/" + file + " <<'BB10EOF'\n" + xml + "BB10EOF");
        cmds.add("chmod 755 " + mod + "/post-fs-data.sh " + mod + "/service.sh; chmod 644 " + mod + "/*.apk " + mod + "/*.xml");
        return cmds;
    }

    public static void refreshModuleIfUpdated(Context c, SharedPreferences sp) {
        if (!sp.getBoolean("qs_module_installed", false)) return;
        long stamp;
        List<String> cmds = new ArrayList<>();
        try {
            stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            if (sp.getLong("qs_module_stamp", 0) == stamp) return;
            cmds.add("[ -d " + MODULE + " ] || { echo NO_MODULE; exit 0; }");
            cmds.addAll(moduleFileCmds(c));
            cmds.add(UNDO_HIDDEN_API);
            cmds.add("echo BB10_REFRESH_OK");
        } catch (Exception e) {
            return;
        }
        RootShell.execAsync(r -> {
            if (r.out.contains("BB10_REFRESH_OK")) sp.edit().putLong("qs_module_stamp", stamp).apply();
            else if (r.out.contains("NO_MODULE")) sp.edit().putBoolean("qs_module_installed", false).apply();
        }, cmds.toArray(new String[0]));
    }

    private static void installQuickstepModule(Activity a) {
        List<String> marked;
        try {
            marked = moduleInstallCmds(a);
        } catch (Exception e) {
            Toast.makeText(a, "Module files missing from this build: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog progress = dialog(a).setTitle("Installing…")
                .setMessage("Waiting for root. If KernelSU/APatch asks, allow JL10.")
                .setCancelable(false).show();
        RootShell.execAsync(90_000, r -> {
            progress.dismiss();
            if (!alive(a)) return;
            boolean ok = moduleInstalled(a, r.out);
            String log = r.out.replaceAll("(?m)^step \\d+/\\d+\\s*$\\n?", "").replace("BB10_INSTALL_OK", "").trim();
            String title = ok ? "Installed — reboot now" : r.timedOut ? "Install stalled" : "Couldn't install";
            String msg = ok ? "Reboot whenever you like. Afterwards Status here should say Active."
                    + (log.isEmpty() ? "" : "\n\n" + log)
                    : (r.timedOut ? "It stopped responding. Last output (send this to me):\n\n" : "Output:\n\n") + r.out;
            dialog(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
        }, marked.toArray(new String[0]));
    }

    static boolean moduleInstalled(Context a, String out) {
        boolean ok = out.contains("BB10_INSTALL_OK");
        if (ok) {
            try {
                long stamp = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).lastUpdateTime;
                PreferenceManager.getDefaultSharedPreferences(a).edit()
                        .putBoolean("qs_module_installed", true).putLong("qs_module_stamp", stamp).apply();
            } catch (Exception ignored) {
            }
        }
        return ok;
    }

    static String stockRecentsPackage(Context a) {
        try {
            int id = android.content.res.Resources.getSystem().getIdentifier("config_recentsComponentName", "string", "android");
            String cn = id == 0 ? null : android.content.res.Resources.getSystem().getString(id);
            ComponentName c = cn == null ? null : ComponentName.unflattenFromString(cn);
            if (c == null || c.getPackageName().equals(a.getPackageName())) return null;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName());
            return a.getPackageManager().queryIntentActivities(home, 0).isEmpty() ? null : c.getPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    static final String UNDO_HIDDEN_API = "for k in hidden_api_policy hidden_api_policy_p_apps hidden_api_policy_pre_p_apps; do "
            + "[ \"$(settings get global $k)\" = 1 ] && settings delete global $k >/dev/null; done; true";

    static List<String> moduleInstallCmds(Context a) throws Exception {
        List<String> files = moduleFileCmds(a);
        String mod = MODULE;
        List<String> cmds = new ArrayList<>();
        cmds.add("[ -d /data/adb/modules ] || { echo 'Magisk/KernelSU modules folder not found'; exit 1; }");
        cmds.add("[ -d " + OLD_MODULE + " ] && touch " + OLD_MODULE + "/remove");
        cmds.add("mkdir -p " + mod + " && rm -rf " + mod + "/system " + mod + "/remove " + mod + "/disable " + mod + "/boot_count");
        cmds.addAll(files);
        cmds.add("for d in /data/adb/modules/*; do [ \"$d\" = \"" + mod + "\" ] && continue; "
                + "if grep -qiE 'quickswitch|quickstepswitcher|quickstep switcher' \"$d/module.prop\" 2>/dev/null; then touch \"$d/disable\"; echo \"disabled $(basename $d)\"; fi; done");
        cmds.add(UNDO_HIDDEN_API);
        String old = stockRecentsPackage(a);
        if (old != null) {
            cmds.add("echo '" + old + "' > " + mod + "/old_launcher");
            cmds.add("if cmd role get-role-holders android.app.role.HOME 2>/dev/null | grep -qx '" + a.getPackageName() + "'; then "
                    + "pm disable-user --user 0 '" + old + "' >/dev/null 2>&1 && echo 'switched off " + old + " (it stops working without recents)'; fi");
        }
        cmds.add("echo BB10_INSTALL_OK");
        List<String> marked = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            marked.add("echo \"step " + (i + 1) + "/" + cmds.size() + "\" >&2");
            marked.add(cmds.get(i));
        }
        return marked;
    }

    private static String sysUiStatus(Context c) {
        String s = Settings.Global.getString(c.getContentResolver(), "bb10_sysui_status");
        return s == null || s.isEmpty()
                ? "Not running. Turn on JL10 in LSPosed Manager (scope: System UI), then tap Restart System UI."
                : s;
    }

    static void runDiagnostics(Activity a) {
        String me = a.getPackageName();
        String M = MODULE;
        AlertDialog wait = dialog(a).setTitle("Diagnostics")
                .setMessage("Generating the report, please wait…")
                .setCancelable(false).show();
        RootShell.execAsync(r -> {
            wait.dismiss();
            if (!alive(a)) return;
            String crashes = com.bb10.launcher.CrashGuard.log(a);
            String report = "JL10 diagnostics\n"
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE
                    + " (" + android.os.Build.DISPLAY + ")\n"
                    + "JL10 build installed: " + installedAt(a) + "\n"
                    + "permission CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS: " + RemoteAnims.hasPermission(a) + "\n"
                    + "hidden APIs reachable: " + RemoteAnims.hiddenApisOk() + "\n"
                    + "shell home transition registered: " + com.bb10.launcher.quickstep.ShellTransitions.homeRegistered() + "\n"
                    + "system has asked for a close animation: " + com.bb10.launcher.quickstep.ShellTransitions.closeConfirmed() + "\n"
                    + "frames use system task snapshots: " + LauncherApp.get().frames.usesTaskSnapshots() + "\n"
                    + "READ_FRAME_BUFFER: " + (a.checkSelfPermission("android.permission.READ_FRAME_BUFFER") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    + ", REMOVE_TASKS: " + (a.checkSelfPermission("android.permission.REMOVE_TASKS") == android.content.pm.PackageManager.PERMISSION_GRANTED) + "\n"
                    + "accessibility service: " + GestureService.isRunning() + "\n"
                    + "Hub listener connected: " + HubListenerService.isConnected() + "\n\n"
                    + "== QuickStep trail (this boot)\n" + com.bb10.launcher.quickstep.ShellTransitions.trail() + "\n"
                    + "== System UI module (LSPosed)\n" + sysUiStatus(a) + "\n\n"
                    + "== JL10 crashes\n" + (crashes.isEmpty() ? "none\n" : crashes) + "\n"
                    + (RootShell.knownAvailable() ? r.out : "Root not available:\n" + r.out);
            android.widget.TextView tv = new android.widget.TextView(a);
            tv.setText(report);
            tv.setTextIsSelectable(true);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(10);
            int pad = Ui.dp(12);
            tv.setPadding(pad, pad, pad, pad);
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(tv);
            File tmp = new File(a.getCacheDir(), "jl10-diagnostics.txt");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                RootShell.execAsync(null, "cp '" + tmp.getAbsolutePath() + "' /sdcard/Download/jl10-diagnostics.txt && chmod 660 /sdcard/Download/jl10-diagnostics.txt");
                Toast.makeText(a, "Saved to Download/jl10-diagnostics.txt", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            dialog(a).setTitle("Diagnostics")
                    .setView(sv)
                    .setPositiveButton("Copy", (d, w) -> {
                        android.content.ClipboardManager cm = a.getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("BB10 diagnostics", report));
                        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null).show();
        },
                "echo \"== recents provider now: $(cmd overlay lookup android android:string/config_recentsComponentName 2>&1 | tail -1)\"",
                "echo \"== android $(getprop ro.build.version.release) (sdk $(getprop ro.build.version.sdk)), $(getprop ro.product.model)\"",
                "echo \"== root: magisk=$(magisk -v 2>/dev/null) ksu=$(ksud -V 2>/dev/null) apatch=$([ -d /data/adb/ap ] && echo yes)\"",
                "echo '== recents component now'",
                "cmd overlay lookup --verbose android android:string/config_recentsComponentName 2>&1 | head -25",
                "echo '== framework overlays mentioning launcher/recents'",
                "cmd overlay list android 2>&1 | grep -iE 'bb10|recent|quickstep|launcher|BB10Recents' | head -20",
                "echo '== our overlay'",
                "cmd overlay dump com.bb10.launcher.overlay.quickstep 2>&1 | head -25",
                "echo '== module files'",
                "ls -R " + M + " 2>&1 | head -30",
                "echo '== Android crash log'",
                "logcat -b crash -d -t 300 2>&1 | tail -300",
                "echo '== boot script log'",
                "cat " + M + "/boot.log 2>&1",
                "echo '== overlay mounts'",
                "grep -E ' /(system|system_ext|product)/(overlay|priv-app|etc/permissions) ' /proc/mounts 2>&1 | head -8",
                "echo '== mounted overlay files'",
                "ls -lZd /product/overlay/BB10QuickstepOverlay /system_ext/overlay/BB10QuickstepOverlay /*/priv-app/BB10Launcher /system/priv-app/BB10Launcher /*/etc/permissions/privapp-permissions-" + me + ".xml /system/etc/permissions/privapp-permissions-" + me + ".xml 2>/dev/null",
                "echo '== modules'",
                "for d in /data/adb/modules/*; do echo \"$(basename $d) $( [ -f $d/disable ] && echo '[disabled]') $( [ -f $d/remove ] && echo '[remove]')\"; done",
                "echo '== Xposed framework (LSPosed / Vector)'",
                "if [ -x /data/adb/lspd/cli ]; then /data/adb/lspd/cli status 2>&1 | head -8; /data/adb/lspd/cli modules ls 2>&1; "
                        + "echo 'scope:'; /data/adb/lspd/cli scope ls " + me + " 2>&1; else echo 'no command-line tool (LSPosed)'; fi",
                "echo '== System UI module crash guard'",
                "ls -la /data/user_de/0/com.android.systemui/ 2>/dev/null | grep jl10 || echo 'no guard files (hooks allowed)'",
                "echo '== System UI module log'",
                "grep -h -E 'JL10:|BB10:' /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -30",
                "echo '== framework log about JL10 / System UI'",
                "grep -h -iE 'JL10|BB10|" + me + "|systemui' /data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log 2>/dev/null | tail -40",
                "echo '== last System UI crash'",
                "logcat -b crash -d 2>/dev/null | grep -A 40 'Process: com.android.systemui' | tail -60",
                "echo '== last BB10 app crash'",
                "logcat -b crash -d 2>/dev/null | grep -A 40 -E 'Process: com.bb10' | tail -60",
                "echo '== launcher permission'",
                "dumpsys package " + me + " | grep -E 'codePath|PRIVILEGED|(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS|START_TASKS_FROM_RECENTS).*granted' | head -8",
                "echo '== SystemUI connection'",
                "dumpsys activity services " + me + "/.quickstep.QuickstepService 2>&1 | grep -E 'ServiceRecord|ConnectionRecord|app=' | head -6",
                "echo '== log'",
                "logcat -d -s BB10Shell:* BB10RemoteAnims:* BB10Hub:* 2>/dev/null | tail -25",
                "echo '== SystemUI registered transition handlers'",
                "dumpsys activity service com.android.systemui/.SystemUIService WMShell 2>&1 | grep -iE -A2 'remote|BB10|filter' | head -40",
                "echo '== SystemUI about the recents provider'",
                "logcat -d 2>/dev/null | grep -iE 'OverviewProxy|LauncherProxy|QUICKSTEP|TouchInteraction' | tail -20",
                "logcat -d 2>/dev/null | grep -iE 'BB10QuickstepOverlay|idmap.*bb10|OverlayManager.*bb10' | tail -15");
    }

    public static void importFont(Activity a, Uri uri, Runnable refresh) {
        File tmp = new File(a.getFilesDir(), "font.tmp");
        try (InputStream is = a.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't read that file", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Typeface.createFromFile(tmp);
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(a, "That isn't a font file", Toast.LENGTH_SHORT).show();
            return;
        }
        tmp.renameTo(Ui.fontFile(a));
        fontChanged(a, refresh);
    }

    private static void fontChanged(Activity a, Runnable refresh) {
        LauncherApp app = LauncherApp.get();
        Ui.loadFont(app);
        app.prefs.sp.edit().putLong(Prefs.FONT_STAMP, System.currentTimeMillis()).apply();
        refresh.run();
        String st = Settings.Global.getString(a.getContentResolver(), "bb10_sysui_status");
        boolean sysui = st != null && !st.isEmpty();
        String put = "settings put global " + Ui.FONT_KEY + " '" + app.fontStamp() + "'";
        if (sysui) {
            RootShell.execAsync(null, put,
                    "rm -f /data/user_de/0/com.android.systemui/" + com.bb10.launcher.sysui.SysUiHook.STARTS_FILE
                            + " /data/user_de/0/com.android.systemui/" + com.bb10.launcher.sysui.SysUiHook.OLD_STARTS_FILE,
                    "sleep 0.3; pkill -f com.android.systemui || killall com.android.systemui");
            Toast.makeText(a, "Font updated everywhere (the screen blinks while System UI restarts)", Toast.LENGTH_LONG).show();
        } else {
            RootShell.execAsync(null, put);
            Toast.makeText(a, "Font updated", Toast.LENGTH_SHORT).show();
        }
    }
}
