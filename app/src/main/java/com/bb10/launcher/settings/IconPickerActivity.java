package com.bb10.launcher.settings;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.icons.BB10Icons;
import com.bb10.launcher.icons.IconPack;
import com.bb10.launcher.icons.IconPackManager;
import com.bb10.launcher.model.AppInfo;
import com.bb10.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IconPickerActivity extends Activity {
    public static final String EXTRA_KEY = "key", EXTRA_PACK = "pack", EXTRA_LABEL = "label";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> cache = new LruCache<>(240);
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private IconPack pack;
    private boolean builtin;
    private String key;
    private BaseAdapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LauncherApp.get().ensureInit();
        key = getIntent().getStringExtra(EXTRA_KEY);
        String pkg = getIntent().getStringExtra(EXTRA_PACK);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        setTitle("Icon for " + label);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        EditText filter = new EditText(this);
        filter.setHint("Filter icons");
        filter.setSingleLine();
        filter.setText(guess(label));
        col.addView(filter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = Ui.text(this, "Loading…", 13, Ui.TEXT2, false);
        status.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        col.addView(status);

        GridView grid = new GridView(this);
        grid.setColumnWidth(Ui.dp(64));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(6));
        grid.setSelector(Ui.selectable(0));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }

            @Override public Object getItem(int p) { return shown.get(p); }

            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                ImageView iv = cv instanceof ImageView ? (ImageView) cv : new ImageView(IconPickerActivity.this);
                iv.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(60)));
                iv.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
                String name = shown.get(p);
                Drawable d = cache.get(name);
                if (d == null && builtin) {
                    d = BB10Icons.drawable(IconPickerActivity.this, name);
                    if (d != null) cache.put(name, d);
                } else if (d == null && pack != null) {
                    d = pack.drawable(name);
                    if (d != null) cache.put(name, d);
                }
                iv.setImageDrawable(d);
                return iv;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, v, pos, id) -> choose(shown.get(pos)));
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });

        LauncherApp app = LauncherApp.get();
        AppInfo ai = app.apps.app(key);
        builtin = BB10Icons.PACK_ID.equals(pkg);
        if (builtin) filter.setText("");
        app.bg.execute(() -> {
            IconPack p = builtin ? null : IconPackManager.get(this, pkg);
            List<String> names = builtin ? BB10Icons.list(this) : p == null ? new ArrayList<>() : p.allDrawables();
            String mapped = builtin ? (ai != null ? BB10Icons.assetFor(this, ai) : null)
                    : (p != null && ai != null) ? p.drawableNameFor(ai.cn) : null;
            if (mapped != null) {
                names.remove(mapped);
                names.add(0, mapped);
            }
            h.post(() -> {
                pack = p;
                all.clear();
                all.addAll(names);
                applyFilter(filter.getText().toString());
            });
        });
    }

    private static String guess(String label) {
        if (label == null) return "";
        String[] w = label.toLowerCase(Locale.ROOT).split("\\s+");
        return w.length > 0 ? w[0] : "";
    }

    private void applyFilter(String f) {
        String q = f.toLowerCase(Locale.ROOT).replace(" ", "_").trim();
        shown.clear();
        for (String n : all) if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
        status.setText(shown.size() + " of " + all.size() + " icons" + (builtin ? " (BlackBerry 10)" : pack != null ? " in " + pack.label : ""));
        adapter.notifyDataSetChanged();
    }

    private void choose(String name) {
        LauncherApp app = LauncherApp.get();
        if (builtin) app.prefs.setIconOverride(key, "bb10:" + name);
        else if (pack != null) app.prefs.setIconOverride(key, "pack:" + pack.pkg + ":" + name);
        else return;
        app.icons.invalidate(key);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_KEY, key));
        finish();
    }
}
