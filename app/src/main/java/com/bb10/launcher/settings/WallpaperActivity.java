package com.bb10.launcher.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.R;
import com.bb10.launcher.ui.Ui;

import java.io.InputStream;

public class WallpaperActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final String DIR = "bb10/walls";
    private static final int COLS = 3;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        int p = Ui.dp(16);
        TextView title = Ui.text(this, "Wallpaper", 20, Ui.TEXT, false);
        title.setPadding(p, p, p, Ui.dp(4));
        root.addView(title);
        TextView hint = Ui.text(this, "Pick a BlackBerry 10 wallpaper or one of your pictures", 13, Ui.TEXT2, false);
        hint.setPadding(p, 0, p, Ui.dp(10));
        root.addView(hint);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(Ui.dp(6), 0, Ui.dp(6), Ui.dp(6));
        ScrollView sv = new ScrollView(this);
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String[] names;
        try {
            names = getAssets().list(DIR);
        } catch (Exception e) {
            names = new String[0];
        }
        int total = names.length + 1;
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View tile = i == 0 ? galleryTile() : assetTile(names[i - 1]);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMargins(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            row.addView(tile, tl);
        }
        if (row != null) for (int k = row.getChildCount(); k < COLS; k++) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, 1, 1f);
            tl.setMargins(Ui.dp(4), 0, Ui.dp(4), 0);
            row.addView(new View(this), tl);
        }
    }

    private FrameLayout squareTile() {
        FrameLayout f = new FrameLayout(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, w);
            }
        };
        f.setBackgroundColor(0xFF262626);
        f.setForeground(Ui.selectable(0));
        return f;
    }

    private View galleryTile() {
        FrameLayout f = squareTile();
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(Ui.glyph(this, R.drawable.ic_image, Ui.TEXT));
        in.addView(ic, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        TextView t = Ui.text(this, "Gallery", 13, Ui.TEXT, false);
        t.setPadding(0, Ui.dp(6), 0, 0);
        in.addView(t);
        f.addView(in, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No gallery app available", Toast.LENGTH_SHORT).show();
            }
        });
        return f;
    }

    private View assetTile(String name) {
        FrameLayout f = squareTile();
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String path = DIR + "/" + name;
        LauncherApp.get().bg.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            try (InputStream in = getAssets().open(path)) {
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                runOnUiThread(() -> iv.setImageBitmap(b));
            } catch (Exception ignored) {
            }
        });
        f.setOnClickListener(v -> chooseTarget(() -> getAssets().open(path)));
        return f;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        chooseTarget(() -> getContentResolver().openInputStream(uri));
    }

    private interface Source {
        InputStream open() throws Exception;
    }

    private void chooseTarget(Source src) {
        String[] items = {"Home screen", "Lock screen", "Home and lock screens"};
        new AlertDialog.Builder(this)
                .setTitle("Set wallpaper on")
                .setItems(items, (d, which) -> {
                    int flags = which == 0 ? WallpaperManager.FLAG_SYSTEM
                            : which == 1 ? WallpaperManager.FLAG_LOCK
                            : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                    apply(src, flags);
                })
                .show();
    }

    private void apply(Source src, int flags) {
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show();
        LauncherApp.get().bg.execute(() -> {
            String msg;
            try (InputStream in = src.open()) {
                Bitmap full = BitmapFactory.decodeStream(in);
                if (full == null) throw new IllegalArgumentException("not a picture");
                Bitmap b = fitScreen(full);
                WallpaperManager.getInstance(this).setBitmap(b, null, true, flags);
                msg = "Wallpaper set";
            } catch (Throwable t) {
                msg = "Couldn't set the wallpaper: " + t.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
        });
    }

    private Bitmap fitScreen(Bitmap src) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int tw = dm.widthPixels, th = dm.heightPixels;
        float s = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
        int cw = Math.min(src.getWidth(), Math.round(tw / s)), ch = Math.min(src.getHeight(), Math.round(th / s));
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
