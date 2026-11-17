package com.bb10.launcher.settings;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.sysui.BbTiles;
import com.bb10.launcher.ui.Ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class QsEditActivity extends Activity {
    private final List<String> order = new ArrayList<>();
    private final List<String> enabled = new ArrayList<>();
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LauncherApp app = LauncherApp.get();
        app.ensureInit();
        enabled.addAll(BbTiles.parse(app.prefs.sp.getString(Prefs.QS_TILES, BbTiles.DEFAULT)));
        order.addAll(enabled);
        for (String s : BbTiles.ALL) if (!order.contains(s)) order.add(s);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        TextView title = Ui.text(this, "Quick Settings", 20, Ui.TEXT, false);
        int p = Ui.dp(16);
        title.setPadding(p, p, p, Ui.dp(4));
        root.addView(title);
        TextView hint = Ui.text(this, "Turn toggles on or off and use the arrows to order them. Four fit in a row.", 13, Ui.TEXT2, false);
        hint.setPadding(p, 0, p, Ui.dp(10));
        root.addView(hint);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        render();
    }

    private void render() {
        list.removeAllViews();
        for (int i = 0; i < order.size(); i++) list.addView(row(order.get(i), i));
    }

    private View row(String spec, int index) {
        boolean on = enabled.contains(spec);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        r.setMinimumHeight(Ui.dp(56));

        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon(BbTiles.icon(spec) + (on ? "_active" : "")));
        ic.setAlpha(on ? 1f : 0.5f);
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));

        TextView t = Ui.text(this, BbTiles.label(spec), 16, on ? Ui.TEXT : Ui.TEXT2, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(14);
        r.addView(t, tlp);

        r.addView(arrow("▲", index > 0, v -> move(index, -1)));
        r.addView(arrow("▼", index < order.size() - 1, v -> move(index, 1)));

        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                if (!enabled.contains(spec)) enabled.add(spec);
            } else {
                enabled.remove(spec);
            }
            save();
            render();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(6);
        r.addView(sw, slp);
        return r;
    }

    private View arrow(String glyph, boolean enabledArrow, View.OnClickListener l) {
        TextView a = Ui.text(this, glyph, 15, enabledArrow ? Ui.TEXT : 0x33FFFFFF, false);
        a.setGravity(Gravity.CENTER);
        a.setBackground(enabledArrow ? Ui.selectable(0) : null);
        a.setEnabled(enabledArrow);
        a.setOnClickListener(l);
        a.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return a;
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= order.size()) return;
        String s = order.remove(i);
        order.add(j, s);
        save();
        render();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (String s : order) {
            if (!enabled.contains(s)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        LauncherApp.get().prefs.sp.edit().putString(Prefs.QS_TILES, sb.toString()).apply();
    }

    private android.graphics.drawable.Drawable icon(String name) {
        try (InputStream in = getAssets().open("bb10/qs/" + name + ".png")) {
            return new BitmapDrawable(getResources(), BitmapFactory.decodeStream(in));
        } catch (Exception e) {
            if (name.endsWith("_active")) return icon(name.substring(0, name.length() - 7));
            return null;
        }
    }
}
