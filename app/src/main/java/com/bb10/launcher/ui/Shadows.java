package com.bb10.launcher.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

public final class Shadows {
    private Shadows() {}

    public static final class Mask {
        public final Bitmap bmp;
        public final int dx, dy;

        Mask(Bitmap b, int x, int y) {
            bmp = b;
            dx = x;
            dy = y;
        }
    }

    public static Mask make(Drawable d, int w, int h, float radius) {
        if (w <= 0 || h <= 0) return null;
        int pad = (int) Math.ceil(radius) + 2;
        Bitmap src = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(pad, pad, pad + w, pad + h);
        d.draw(new Canvas(src));
        d.setBounds(old);
        Paint blur = new Paint();
        if (radius > 0) blur.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap m = src.extractAlpha(blur, off);
        src.recycle();
        return new Mask(m, off[0] - pad, off[1] - pad);
    }

    public static final int ICON_REF = 112;
    public static final float ICON_BLUR = 0.05f, ICON_DROP = 0.03f;

    private static final Mask NONE = new Mask(null, 0, 0);
    private static final Map<Drawable, Mask> icons = new WeakHashMap<>();
    private static final Map<Drawable, Boolean> making = new WeakHashMap<>();
    private static final List<WeakReference<View>> waiting = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static Mask icon(Drawable d, View v, Executor bg) {
        Mask m = icons.get(d);
        if (m != null) return m.bmp != null ? m : null;
        if (v != null) waiting.add(new WeakReference<>(v));
        if (making.containsKey(d)) return null;
        making.put(d, Boolean.TRUE);
        Drawable.ConstantState cs = d.getConstantState();
        Drawable copy = null;
        try {
            if (cs != null) copy = cs.newDrawable(v != null ? v.getResources() : null).mutate();
        } catch (Throwable ignored) {
        }
        if (copy == null || bg == null) {
            Mask now;
            try {
                now = make(d, ICON_REF, ICON_REF, ICON_REF * ICON_BLUR);
            } catch (Throwable t) {
                now = null;
            }
            making.remove(d);
            icons.put(d, now != null ? now : NONE);
            return now;
        }
        WeakReference<Drawable> key = new WeakReference<>(d);
        Drawable c = copy;
        bg.execute(() -> {
            Mask made;
            try {
                made = make(c, ICON_REF, ICON_REF, ICON_REF * ICON_BLUR);
            } catch (Throwable t) {
                made = null;
            }
            Mask fm = made;
            main.post(() -> {
                Drawable k = key.get();
                if (k != null) {
                    making.remove(k);
                    icons.put(k, fm != null ? fm : NONE);
                }
                for (WeakReference<View> w : waiting) {
                    View x = w.get();
                    if (x != null) x.invalidate();
                }
                waiting.clear();
            });
        });
        return null;
    }
}
