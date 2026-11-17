package com.bb10.launcher.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.RootShell;
import com.bb10.launcher.quickstep.RemoteAnims;
import com.bb10.launcher.quickstep.ShellTransitions;
import com.bb10.launcher.ui.Ui;

import java.util.List;

public class SetupWizardActivity extends Activity {
    public static final String PREF_AFTER_REBOOT = "setup_after_reboot";
    public static final String PREF_DONE = "setup_done";

    private static final int TODO = 0, RUNNING = 1, DONE = 2, FAIL = 3, SKIP = 4;
    private static final String[] TITLES = {
            "Allow root access",
            "Permissions and home screen",
            "Recents animations",
            "BB10 status bar and lock screen",
            "Restart your phone",
    };
    private static final String[] HINTS = {
            "When your root app (Magisk, KernelSU or APatch) asks, tap Allow or Grant.",
            "Notifications, gestures and app usage. Done for you.",
            "Installs JL10's small system module.",
            "Turns JL10 on in Vector / LSPosed for System UI.",
            "Needed once, to finish.",
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextView[] marks = new TextView[TITLES.length], details = new TextView[TITLES.length];
    private TextView title, intro, primary, secondary, warning;
    private android.widget.ProgressBar progress;
    private ScrollView scroll;
    public static final String PREF_NO_XPOSED = "setup_no_xposed";
    private boolean running, needReboot, needSysUiRestart;
    private SharedPreferences sp;

    public static void open(Context c) {
        c.startActivity(new Intent(c, SetupWizardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit().putBoolean("setup_opened", true).apply();
        RootShell.held = false;
        int pad = Ui.dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);

        ScrollView sv = new ScrollView(this);
        scroll = sv;
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, Ui.dp(16), pad, Ui.dp(12));

        title = Ui.text(this, "Welcome to JL10", 21, Ui.TEXT, true);
        page.addView(title);
        intro = Ui.text(this, "One tap sets everything up. You'll only need to tap Allow once, and restart at the end.", 13, Ui.TEXT2, false);
        intro.setPadding(0, Ui.dp(4), 0, Ui.dp(10));
        page.addView(intro);
        progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(TITLES.length);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A2A2A));
        page.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4)));

        warning = Ui.text(this, "", 13, 0xFFFFE0A3, false);
        GradientDrawable wd = new GradientDrawable();
        wd.setColor(0xFF3A2C10);
        wd.setStroke(Ui.dp(1), 0xFF8A6A20);
        wd.setCornerRadius(Ui.dp(8));
        warning.setBackground(wd);
        warning.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        warning.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(12);
        page.addView(warning, wlp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cd = new GradientDrawable();
        cd.setColor(0xFF1C1C1C);
        cd.setCornerRadius(Ui.dp(10));
        card.setBackground(cd);
        card.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(4));
        for (int i = 0; i < TITLES.length; i++) {
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(0xFF2A2A2A);
                card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(1))));
            }
            card.addView(stepRow(i));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(12);
        page.addView(card, clp);
        sv.addView(page);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View topLine = new View(this);
        topLine.setBackgroundColor(0xFF262626);
        root.addView(topLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(1))));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, Ui.dp(10), pad, Ui.dp(10));
        secondary = button(false);
        bar.addView(secondary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44)));
        primary = button(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(44), 1f);
        plp.leftMargin = Ui.dp(10);
        bar.addView(primary, plp);
        root.addView(bar);
        setContentView(root);

        for (int i = 0; i < TITLES.length; i++) set(i, TODO, HINTS[i]);
        if (sp.getBoolean(PREF_AFTER_REBOOT, false)) {
            verifyAfterReboot();
        } else {
            showPrimary("Set up everything", this::runAll);
            showSecondary("Not now", this::finish);
        }
    }

    private void runAll() {
        if (running) return;
        running = true;
        needReboot = needSysUiRestart = false;
        hideButtons();
        title.setText("Setting up…");
        intro.setText("Keep this screen open. It takes about a minute.");
        for (int i = 0; i < TITLES.length; i++) set(i, TODO, HINTS[i]);
        stepRoot();
    }

    private void stepRoot() {
        set(0, RUNNING, "Waiting for you to tap Allow…");
        bg(() -> {
            boolean ok = RootShell.isAvailable();
            ui(() -> {
                if (ok) {
                    set(0, DONE, "Root access granted.");
                    stepPermissions();
                    return;
                }
                running = false;
                String app = rootAppLabel();
                set(0, FAIL, "JL10 didn't get root. Open " + (app != null ? app : "your root app")
                        + ", find JL10 in its Superuser list, switch it on, then come back and tap Try again.");
                title.setText("One thing to do");
                intro.setText("Your root app has to allow JL10 first.");
                showPrimary("Try again", this::runAll);
                Intent li = rootAppIntent();
                if (li != null) showSecondary("Open " + app, () -> startActivity(li));
                else showSecondary("Not now", this::finish);
            });
        });
    }

    private void stepPermissions() {
        set(1, RUNNING, "Granting…");
        List<String> cmds = SettingsTools.rootSetupCmds(this);
        RootShell.execAsync(r -> waitForPermissions(0), cmds.toArray(new String[0]));
    }

    private void waitForPermissions(int tries) {
        List<String> missing = SettingsTools.setupMissing(this);
        if (!missing.isEmpty() && tries < 10) {
            h.postDelayed(() -> waitForPermissions(tries + 1), 700);
            return;
        }
        if (missing.isEmpty()) set(1, DONE, "All granted, and JL10 is your home screen.");
        else set(1, FAIL, "Still off: " + TextUtils.join(", ", missing) + ". You can fix these later in Settings › Setup.");
        stepModule();
    }

    private void stepModule() {
        if (quickstepActive()) {
            set(2, DONE, "Already active.");
            stepSystemUi();
            return;
        }
        set(2, RUNNING, "Installing the system module…");
        List<String> cmds;
        try {
            cmds = SettingsTools.moduleInstallCmds(this);
        } catch (Exception e) {
            set(2, FAIL, "This copy of JL10 is missing the module's files. Download JL10 again.");
            stepSystemUi();
            return;
        }
        RootShell.execAsync(90_000, r -> {
            if (SettingsTools.moduleInstalled(this, r.out)) {
                needReboot = true;
                set(2, DONE, "Installed. Active after the restart.");
            } else {
                set(2, FAIL, "Couldn't install it" + (r.timedOut ? " (it stopped responding)" : "")
                        + ". JL10 still works, with simpler animations. Settings › System › Diagnostics shows why.");
            }
            stepSystemUi();
        }, cmds.toArray(new String[0]));
    }

    private void stepSystemUi() {
        if (sysUiRunning()) {
            set(3, DONE, "Already active.");
            switchBb10On();
            stepRestart();
            return;
        }
        set(3, RUNNING, "Looking for Vector / LSPosed…");
        String me = getPackageName();
        RootShell.execAsync(r -> {
            String out = r.out;
            boolean found = out.contains("HAS_CLI") || out.contains("HAS_LSPOSED");
            if (found) {
                sp.edit().remove(PREF_NO_XPOSED).apply();
                warning.setVisibility(View.GONE);
            }
            if (out.contains("HAS_CLI")) {
                RootShell.execAsync(r2 -> {
                    boolean ok = r2.out.contains("SCOPE_OK");
                    if (ok) {
                        switchBb10On();
                        if (!needReboot) needSysUiRestart = true;
                        set(3, DONE, "Turned on for System UI.");
                    } else {
                        set(3, FAIL, manualXposedSteps());
                    }
                    stepRestart();
                }, "/data/adb/lspd/cli modules enable " + me + " 2>&1",
                        "/data/adb/lspd/cli scope set " + me + " com.android.systemui/0 2>&1 && echo SCOPE_OK");
            } else if (out.contains("HAS_LSPOSED")) {
                switchBb10On();
                set(3, FAIL, manualXposedSteps());
                if (getPackageManager().getLaunchIntentForPackage("org.lsposed.manager") != null) needReboot = true;
                stepRestart();
            } else {
                sp.edit().putBoolean(PREF_NO_XPOSED, true).apply();
                showNoXposedWarning();
                set(3, SKIP, "Skipped: Vector / LSPosed isn't installed.");
                stepRestart();
            }
        }, "[ -x /data/adb/lspd/cli ] && echo HAS_CLI",
                "ls /data/adb/modules 2>/dev/null | grep -qiE 'lsposed|vector' && echo HAS_LSPOSED", "true");
    }

    private void showNoXposedWarning() {
        warning.setText("⚠  Vector or LSPosed wasn't found\n\nJL10 will still work, but without the BB10 status bar, "
                + "lock screen, Quick Settings and volume. To get them: install Vector (a Zygisk module) in "
                + rootAppLabelOr("your root app") + ", restart, then run this setup again (Settings › Setup › Guided setup).");
        warning.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    private String rootAppLabelOr(String fallback) {
        String s = rootAppLabel();
        return s != null ? s : fallback;
    }

    private String manualXposedSteps() {
        return "One step by hand: open LSPosed (or Vector), go to Modules, switch JL10 on and tick System UI. Then restart.";
    }

    private void switchBb10On() {
        sp.edit().putBoolean(Prefs.SYSUI_STATUSBAR, true).putBoolean(Prefs.SYSUI_SHADE, true)
                .putBoolean(Prefs.SYSUI_KEYGUARD, true).putBoolean(Prefs.SYSUI_VOLUME, true)
                .putBoolean(Prefs.PULL_ANDROID, false).apply();
        LauncherApp.get().syncSysUi();
    }

    private void stepRestart() {
        running = false;
        if (needReboot) {
            set(4, TODO, "Tap Restart. When the phone is back on, this screen opens again to check everything.");
            title.setText("Almost done");
            intro.setText("Restart your phone to finish.");
            showPrimary("Restart phone now", () -> {
                sp.edit().putBoolean(PREF_AFTER_REBOOT, true).commit();
                hideButtons();
                set(4, RUNNING, "Restarting…");
                RootShell.execAsync(r -> set(4, FAIL, "Couldn't restart. Restart the phone yourself (hold the power key)."),
                        "svc power reboot || reboot");
            });
            showSecondary("Later", () -> {
                sp.edit().putBoolean(PREF_AFTER_REBOOT, true).apply();
                finish();
            });
            return;
        }
        if (needSysUiRestart) {
            set(4, RUNNING, "No restart needed: reloading the status bar instead…");
            RootShell.execAsync(r -> h.postDelayed(() -> verify(0), 6000),
                    "settings delete global bb10_sysui_status; sleep 0.3; pkill -f com.android.systemui || killall com.android.systemui");
            return;
        }
        set(4, SKIP, "Not needed.");
        verify(0);
    }

    private void verifyAfterReboot() {
        title.setText("Checking…");
        intro.setText("Making sure everything took.");
        hideButtons();
        set(4, DONE, "Restarted.");
        bg(() -> {
            boolean root = RootShell.isAvailable();
            ui(() -> {
                set(0, root ? DONE : FAIL, root ? "Root access granted." : "Root access is off. Allow JL10 in your root app.");
                verify(0);
            });
        });
    }

    private void verify(int tries) {
        List<String> missing = SettingsTools.setupMissing(this);
        boolean qs = quickstepActive(), sys = sysUiRunning();
        boolean xposedWanted = !sp.getBoolean(PREF_NO_XPOSED, false);
        if (!xposedWanted) {
            showNoXposedWarning();
            set(3, SKIP, "Skipped: Vector / LSPosed isn't installed.");
        }
        if ((!missing.isEmpty() || !qs || (xposedWanted && !sys)) && tries < 12) {
            h.postDelayed(() -> verify(tries + 1), 1000);
            return;
        }
        set(1, missing.isEmpty() ? DONE : FAIL, missing.isEmpty() ? "All granted, and JL10 is your home screen."
                : "Still off: " + TextUtils.join(", ", missing) + ". Tap Try again.");
        set(2, qs ? DONE : FAIL, qs ? "Active." : "Not active yet. Tap Try again, then restart once more.");
        if (xposedWanted) set(3, sys ? DONE : FAIL, sys ? "Active." : statusHint());
        boolean all = missing.isEmpty() && qs && (sys || !xposedWanted);
        sp.edit().remove(PREF_AFTER_REBOOT).apply();
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
        if (all) {
            sp.edit().putBoolean(PREF_DONE, true).apply();
            title.setText(xposedWanted ? "You're all set!" : "Set up, with one thing missing");
            intro.setText(xposedWanted ? "Enjoy JL10. Everything can be changed later in Settings."
                    : "JL10 works. The note below says how to get the BB10 status bar and lock screen too.");
            showPrimary("Start using JL10", this::finish);
            hideSecondary();
        } else {
            title.setText("Nearly there");
            intro.setText("Something below didn't take. Try again usually fixes it.");
            showPrimary("Try again", this::runAll);
            showSecondary("Close", this::finish);
        }
    }

    private String statusHint() {
        String s = Settings.Global.getString(getContentResolver(), "bb10_sysui_status");
        if (s != null && s.startsWith(com.bb10.launcher.sysui.SysUiHook.SAFE_STATUS))
            return "Switched itself off after System UI crashed. Tap Try again.";
        return manualXposedSteps();
    }

    private boolean quickstepActive() {
        return ShellTransitions.homeRegistered() || RemoteAnims.hasPermission(this);
    }

    private boolean sysUiRunning() {
        String s = Settings.Global.getString(getContentResolver(), "bb10_sysui_status");
        return s != null && !s.isEmpty() && !s.startsWith(com.bb10.launcher.sysui.SysUiHook.SAFE_STATUS);
    }

    private static final String[] ROOT_APPS = {"com.topjohnwu.magisk", "io.github.vvb2060.magisk", "me.weishu.kernelsu",
            "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch"};

    private Intent rootAppIntent() {
        for (String p : ROOT_APPS) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private String rootAppLabel() {
        for (String p : ROOT_APPS) {
            try {
                if (getPackageManager().getLaunchIntentForPackage(p) == null) continue;
                return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p, 0)).toString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private View stepRow(int i) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(11), 0, Ui.dp(11));
        TextView m = Ui.text(this, String.valueOf(i + 1), 13, Ui.TEXT, true);
        m.setGravity(Gravity.CENTER);
        marks[i] = m;
        row.addView(m, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, TITLES[i], 15, Ui.TEXT, true));
        TextView d = Ui.text(this, "", 13, Ui.TEXT2, false);
        d.setPadding(0, Ui.dp(2), 0, 0);
        details[i] = d;
        col.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(12);
        row.addView(col, lp);
        return row;
    }

    private void set(int i, int state, String detail) {
        TextView m = marks[i];
        m.setTag(state);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setShape(GradientDrawable.OVAL);
        switch (state) {
            case DONE:
                m.setText("✓");
                bgd.setColor(0xFF2E9E4F);
                break;
            case FAIL:
                m.setText("!");
                bgd.setColor(0xFFD7262B);
                break;
            case SKIP:
                m.setText(i == 3 ? "!" : "–");
                bgd.setColor(i == 3 ? 0xFFC88A12 : 0xFF555555);
                break;
            case RUNNING:
                m.setText("…");
                bgd.setColor(Ui.BLUE);
                break;
            default:
                m.setText(String.valueOf(i + 1));
                bgd.setColor(0xFF333333);
        }
        m.setBackground(bgd);
        details[i].setText(detail);
        boolean show = state == RUNNING || state == FAIL || state == SKIP || (state == TODO && (i == 0 || i == 4));
        details[i].setVisibility(show ? View.VISIBLE : View.GONE);
        details[i].setTextColor(state == FAIL ? 0xFFFF8A80 : state == SKIP && i == 3 ? 0xFFFFC966 : Ui.TEXT2);
        if (scroll != null && (state == RUNNING || state == FAIL || (state == TODO && i == 4 && needReboot))) {
            View row = (View) marks[i].getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, ((View) row.getParent()).getTop() + row.getBottom() - scroll.getHeight() + Ui.dp(12))));
        }
        if (progress != null) {
            int n = 0;
            for (TextView t : marks) if (t != null && t.getTag() != null && (int) t.getTag() >= DONE) n++;
            progress.setProgress(n);
        }
    }

    private TextView button(boolean main) {
        TextView t = Ui.text(this, "", main ? 16 : 15, main ? 0xFFFFFFFF : Ui.TEXT2, main);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.dp(8));
        if (main) d.setColor(Ui.BLUE);
        else d.setStroke(Ui.dp(1), 0xFF3A3A3A);
        t.setBackground(d);
        return t;
    }

    private void showPrimary(String s, Runnable r) {
        primary.setText(s);
        primary.setVisibility(View.VISIBLE);
        primary.setOnClickListener(v -> r.run());
    }

    private void showSecondary(String s, Runnable r) {
        secondary.setText(s);
        secondary.setVisibility(View.VISIBLE);
        secondary.setOnClickListener(v -> r.run());
    }

    private void hideSecondary() {
        secondary.setVisibility(View.GONE);
    }

    private void hideButtons() {
        primary.setVisibility(View.INVISIBLE);
        hideSecondary();
    }

    private void bg(Runnable r) {
        LauncherApp.get().bg.execute(r);
    }

    private void ui(Runnable r) {
        h.post(r);
    }

    @Override
    public void onBackPressed() {
        if (!running) super.onBackPressed();
    }
}
