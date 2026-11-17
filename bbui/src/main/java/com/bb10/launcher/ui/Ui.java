package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

import com.bb10.launcher.RootShell;

import java.io.File;

public final class Ui {
    public static final int BLUE = 0xFF00A8DF;
    public static final int BLUE_DIM = 0x5500A8DF;
    public static final int BG = 0xFF121212;
    public static final int PANEL = 0xFF262626;
    public static final int PANEL_DARK = 0xFF1A1A1A;
    public static final int DIVIDER = 0xFF323232;
    public static final int TEXT = 0xFFF5F5F5;
    public static final int TEXT2 = 0xFF9A9A9A;
    public static final int SPLAT = 0xFFE3262D;

    private static float sDensity = 2f, sScaled = 2f;
    private static float sS = 720f;

    public static float S() { return sS; }

    public static float s(float fraction) { return fraction * sS; }
    private static Typeface sRegular = Typeface.DEFAULT, sBold = Typeface.DEFAULT_BOLD, sLight = Typeface.DEFAULT;

    private Ui() {}

    @SuppressWarnings("deprecation")
    public static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        sDensity = dm.density;
        sScaled = dm.scaledDensity;
        try {
            android.graphics.Rect b = c.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            sS = Math.min(b.width(), b.height());
        } catch (Exception e) {
            sS = Math.min(dm.widthPixels, dm.heightPixels);
        }
        loadFont(c);
    }

    public static File fontFile(Context c) {
        return new File(c.getFilesDir(), "font.ttf");
    }

    private static Typeface sMedium = Typeface.DEFAULT;

    public static volatile String fontStatus = "";

    private static Typeface asset(Context c, String name) {
        String path = "fonts/" + name + ".ttf";
        try {
            Typeface t = Typeface.createFromAsset(c.getAssets(), path);
            if (t != null && t != Typeface.DEFAULT) {
                fontStatus = "asset";
                return t;
            }
        } catch (Throwable e) {
            fontStatus = "asset failed: " + e;
        }
        try {
            File out = new File(c.getCacheDir(), "jl10-" + name + ".ttf");
            if (!out.exists() || out.length() == 0) {
                try (java.io.InputStream is = c.getAssets().open(path); java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            Typeface t = Typeface.createFromFile(out);
            fontStatus = "file" + (fontStatus.isEmpty() ? "" : " (" + fontStatus + ")");
            return t;
        } catch (Throwable e) {
            fontStatus = "no BB10 font: " + e + (fontStatus.isEmpty() ? "" : "; " + fontStatus);
            return null;
        }
    }

    public static final String FONT_KEY = "bb10_font";
    private static final android.net.Uri FONT_URI = android.net.Uri.parse("content://com.bb10.launcher.font/font");
    private static final String LAUNCHER = "com.bb10.launcher";
    private static volatile String loadedStamp = "";
    private static boolean watching;

    private static File customFont(Context c) {
        if (LAUNCHER.equals(c.getPackageName())) {
            File f = fontFile(c);
            return f.exists() ? f : null;
        }
        String stamp = null;
        try {
            stamp = android.provider.Settings.Global.getString(c.getContentResolver(), FONT_KEY);
        } catch (Throwable ignored) {
        }
        loadedStamp = stamp == null ? "" : stamp;
        if (loadedStamp.isEmpty()) return null;
        File dir = c.getCacheDir();
        File out = new File(dir, "jl10-font-" + loadedStamp + ".ttf");
        if (out.exists() && out.length() > 0) return out;
        File tmp = new File(dir, "jl10-font.tmp");
        try (java.io.InputStream is = c.getContentResolver().openInputStream(FONT_URI);
             java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
            if (is == null) return null;
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Throwable e) {
            fontStatus = "custom font unavailable: " + e;
            tmp.delete();
            return null;
        }
        File[] old = dir.listFiles((d, name) -> name.startsWith("jl10-font-") && name.endsWith(".ttf"));
        if (old != null) for (File o : old)
            o.delete();
        return tmp.renameTo(out) ? out : null;
    }

    public static boolean fontChanged(Context c) {
        if (LAUNCHER.equals(c.getPackageName())) return false;
        try {
            String s = android.provider.Settings.Global.getString(c.getContentResolver(), FONT_KEY);
            return !(s == null ? "" : s).equals(loadedStamp);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void watchFont(Context c) {
        if (watching || LAUNCHER.equals(c.getPackageName())) return;
        Context app = c.getApplicationContext();
        if (!(app instanceof android.app.Application)) return;
        watching = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity a) {
                if (!fontChanged(a)) return;
                loadFont(a);
                a.recreate();
            }

            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}

            @Override
            public void onActivityStarted(android.app.Activity a) {}

            @Override
            public void onActivityPaused(android.app.Activity a) {}

            @Override
            public void onActivityStopped(android.app.Activity a) {}

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}

            @Override
            public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    public static volatile File customFontFile;

    public static void loadFont(Context c) {
        watchFont(c);
        Typeface base = null;
        File f = customFont(c);
        customFontFile = null;
        if (f != null && f.exists()) {
            try {
                base = Typeface.createFromFile(f);
            } catch (Exception ignored) {
            }
        }
        if (base != null) {
            customFontFile = f;
            sRegular = base;
            sMedium = base;
            sBold = Typeface.create(base, Typeface.BOLD);
            sLight = base;
            return;
        }
        Typeface reg = asset(c, "slatepro"), med = asset(c, "slatepro-medium"),
                bold = asset(c, "slatepro-bold"), light = asset(c, "slatepro-light");
        sRegular = reg != null ? reg : Typeface.create("sans-serif", Typeface.NORMAL);
        sMedium = med != null ? med : Typeface.create("sans-serif-medium", Typeface.NORMAL);
        sBold = bold != null ? bold : Typeface.create("sans-serif", Typeface.BOLD);
        sLight = light != null ? light : Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    public static Typeface medium() { return sMedium; }

    public static Typeface regular() { return sRegular; }

    public static Typeface bold() { return sBold; }

    public static Typeface light() { return sLight; }

    public static int dp(float v) { return Math.round(v * sDensity); }

    public static float sp(float v) { return v * sScaled; }

    public static TextView text(Context c, CharSequence s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(Tr.t(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(bold ? sBold : sRegular);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static Drawable glyph(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static Drawable selectable(int base) {
        StateListDrawable s = new StateListDrawable() {
            private boolean keys() {
                Drawable.Callback cb = getCallback();
                return !(cb instanceof android.view.View) || !((android.view.View) cb).isInTouchMode();
            }

            @Override
            public void draw(Canvas c) {
                if (keys() || getCurrent() == null) super.draw(c);
                else if (base != 0) {
                    c.save();
                    c.clipRect(getBounds());
                    c.drawColor(base);
                    c.restore();
                }
            }
        };
        s.addState(new int[]{android.R.attr.state_focused}, focus());
        s.addState(new int[]{android.R.attr.state_selected}, focus());
        s.addState(new int[]{}, new ColorDrawable(base));
        return s;
    }

    public static Drawable focus() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x4000A8DF);
        g.setStroke(dp(2), BLUE);
        return g;
    }

    public static void splat(Canvas c, float cx, float cy, float r, Paint p) {
        Paint.Style st = p.getStyle();
        int col = p.getColor();
        Paint.Cap cap = p.getStrokeCap();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(r * 0.15f, 0, r * 0.06f, 0x80000000);
        c.drawCircle(cx, cy, r, p);
        p.clearShadowLayer();
        p.setColor(0xFFD7262B);
        c.drawCircle(cx, cy, r * 0.84f, p);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.2f);
        float arm = r * 0.48f;
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 + i * Math.PI / 3;
            float dx = (float) Math.cos(a) * arm, dy = (float) Math.sin(a) * arm;
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p);
        }
        p.setStrokeCap(cap);
        p.setStyle(st);
        p.setColor(col);
    }

    public static void tick(Context c) {
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        }
    }

    @SuppressWarnings({"JavaReflectionMemberAccess"})
    public static void expandQuickSettings(Context c) {
        try {
            Object sbm = c.getSystemService("statusbar");
            sbm.getClass().getMethod("expandSettingsPanel").invoke(sbm);
        } catch (Throwable t) {
            RootShell.execAsync(null, "cmd statusbar expand-settings");
        }
    }

    @SuppressWarnings({"JavaReflectionMemberAccess"})
    public static void expandNotifications(Context c) {
        try {
            Object sbm = c.getSystemService("statusbar");
            sbm.getClass().getMethod("expandNotificationsPanel").invoke(sbm);
        } catch (Throwable t) {
            RootShell.execAsync(null, "cmd statusbar expand-notifications");
        }
    }

    public static volatile boolean homeDark;
    public static volatile int homeGen;

    public static int homeFg() {
        return homeDark ? 0xFF141414 : TEXT;
    }

    public static int homeFgSoft() {
        return homeDark ? 0xD9141414 : 0xD9FFFFFF;
    }

    public static int homeShadow(int dark) {
        return homeDark ? 0x8CFFFFFF : dark;
    }

    public static int homeOutline() {
        return homeDark ? 0xB3FFFFFF : 0xA6000000;
    }

    public static float outlineW() {
        return Math.max(1f, s(0.0022f));
    }

    public static void outline(android.graphics.Canvas c, android.graphics.drawable.Drawable shape, float l, float t, int s) {
        float o = outlineW(), d = o * 0.7071f;
        float[] xy = {-o, 0, o, 0, 0, -o, 0, o, -d, -d, d, -d, -d, d, d, d, 0, o * 1.6f};
        for (int i = 0; i < xy.length; i += 2) {
            int x = Math.round(l + xy[i]), y = Math.round(t + xy[i + 1]);
            shape.setBounds(x, y, x + s, y + s);
            shape.draw(c);
        }
    }
}