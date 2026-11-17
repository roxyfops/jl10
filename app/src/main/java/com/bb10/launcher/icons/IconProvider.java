package com.bb10.launcher.icons;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.bb10.launcher.Prefs;
import com.bb10.launcher.model.AppInfo;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class IconProvider {
    private static final int SIZE = 192;
    private final Context ctx;
    private final Prefs prefs;
    private final Map<String, Drawable> cache = new HashMap<>();

    public IconProvider(Context c, Prefs p) {
        ctx = c;
        prefs = p;
    }

    public static File customFile(Context c, String key) {
        File dir = new File(c.getFilesDir(), "icons");
        dir.mkdirs();
        return new File(dir, key.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
    }

    public Drawable get(AppInfo a) {
        Drawable d = cache.get(a.key);
        if (d == null) {
            d = load(a, 0);
            cache.put(a.key, d);
        }
        return d;
    }

    public Drawable hiRes(AppInfo a, int px) {
        return load(a, Math.max(1, px));
    }

    public void invalidate(String key) {
        cache.remove(key);
        com.bb10.launcher.quickstep.OwnSplash.forgetIcons();
    }

    public void invalidateAll() {
        cache.clear();
        com.bb10.launcher.quickstep.OwnSplash.forgetIcons();
    }

    private Drawable load(AppInfo a, int px) {
        int density = px > 0 ? android.util.DisplayMetrics.DENSITY_XXXHIGH : 0;
        String ov = prefs.iconOverride(a.key);
        if ("file".equals(ov)) {
            File f = customFile(ctx, a.key);
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) return new BitmapDrawable(ctx.getResources(), b);
            }
        } else if (ov != null && ov.startsWith("bb10:")) {
            Drawable x = BB10Icons.drawable(ctx, ov.substring(5));
            if (x != null) return x;
        } else if (ov != null && ov.startsWith("pack:")) {
            String[] parts = ov.split(":", 3);
            if (parts.length == 3) {
                IconPack ip = IconPackManager.get(ctx, parts[1]);
                Drawable x = ip == null ? null : ip.drawable(parts[2], density);
                if (x != null) return x;
            }
        }
        if (prefs.bool(Prefs.BB10_ICONS, true)) {
            Drawable x = BB10Icons.iconFor(ctx, a);
            if (x != null) return x;
        }
        IconPack pack = IconPackManager.get(ctx, prefs.str(Prefs.ICON_PACK, ""));
        if (pack != null) {
            Drawable x = pack.iconFor(a.cn, density);
            if (x != null) return x;
        }
        return original(a, px > 0 ? px : SIZE, density);
    }

    public Drawable original(AppInfo a) {
        return original(a, SIZE, 0);
    }

    private Drawable original(AppInfo a, int size, int density) {
        Drawable d = a.info.getIcon(density > 0 ? density : ctx.getResources().getDisplayMetrics().densityDpi);
        String shape = prefs.str(Prefs.ICON_SHAPE, "rounded");
        if (!(d instanceof AdaptiveIconDrawable) || "system".equals(shape)) return d;
        AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float radius = "square".equals(shape) ? size * 0.04f : size * 0.2f;
        float inset = size * 0.04f;
        Path clip = new Path();
        clip.addRoundRect(new RectF(inset, inset, size - inset, size - inset), radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        int full = Math.round(size * 1.5f), off = -Math.round(size * 0.25f);
        if (ad.getBackground() != null) {
            ad.getBackground().setBounds(off, off, off + full, off + full);
            ad.getBackground().draw(c);
        }
        if (ad.getForeground() != null) {
            ad.getForeground().setBounds(off, off, off + full, off + full);
            ad.getForeground().draw(c);
        }
        return new BitmapDrawable(ctx.getResources(), b);
    }
}
