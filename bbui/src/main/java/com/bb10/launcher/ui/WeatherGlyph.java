package com.bb10.launcher.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;

public final class WeatherGlyph {
    private WeatherGlyph() {}

    private static final int SUN = 0xFFFFC233, RAY = 0xFFFFB300, MOON = 0xFFEDE7CF,
            CLOUD = 0xFFF4F6F8, CLOUD_GREY = 0xFFC9D0D8, CLOUD_DARK = 0xFF98A2AE,
            RAIN = 0xFF4FC3F7, SNOW = 0xFFFFFFFF, BOLT = 0xFFFFD54F, FOG = 0xFFD3D9DF;
    private static final int CLOUD_L = 0xFFC3CCD6, CLOUD_GREY_L = 0xFFA4AFBB, CLOUD_DARK_L = 0xFF7D8896,
            SNOW_L = 0xFF8FB8D8, FOG_L = 0xFF9AA5B1;

    public static final class Kind {
        public boolean night, thunder, showers, fog;
        public int precip;
        public int intensity = 2;
        public String sky = "";
    }

    public static Kind parse(String code) {
        Kind k = new Kind();
        String c = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        k.night = c.endsWith("_night");
        c = c.replaceAll("_(day|night|polartwilight)$", "").replace("lightss", "lights");
        k.thunder = c.contains("thunder");
        c = c.replace("andthunder", "");
        if (c.endsWith("showers")) {
            k.showers = true;
            c = c.substring(0, c.length() - "showers".length());
        }
        if (c.startsWith("light")) {
            k.intensity = 1;
            c = c.substring(5);
        } else if (c.startsWith("heavy")) {
            k.intensity = 3;
            c = c.substring(5);
        }
        switch (c) {
            case "rain": k.precip = 1; break;
            case "sleet": k.precip = 2; break;
            case "snow": k.precip = 3; break;
            case "fog": k.fog = true; break;
            default: break;
        }
        k.sky = c;
        return k;
    }

    public static String describe(String code) {
        if (code == null || code.isEmpty()) return "";
        Kind k = parse(code);
        String s;
        if (k.precip > 0) {
            s = (k.intensity == 1 ? "light " : k.intensity == 3 ? "heavy " : "")
                    + (k.precip == 1 ? "rain" : k.precip == 2 ? "sleet" : "snow")
                    + (k.showers ? " showers" : "") + (k.thunder ? " and thunder" : "");
        } else if (k.fog) {
            s = "fog";
        } else {
            switch (k.sky) {
                case "clearsky": s = k.night ? "clear" : "sunny"; break;
                case "fair": s = k.night ? "mostly clear" : "mostly sunny"; break;
                case "partlycloudy": s = "partly cloudy"; break;
                case "cloudy": s = "cloudy"; break;
                default: s = k.thunder ? "thunder" : k.sky;
            }
        }
        return s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static int[] sky(String code) {
        Kind k = parse(code);
        if (k.thunder) return new int[]{0xFF25273A, 0xFF4A4C68};
        if (k.precip == 3) return k.night ? new int[]{0xFF2C3B4E, 0xFF4F6379} : new int[]{0xFF5B7894, 0xFF9AB1C6};
        if (k.precip > 0) return k.night ? new int[]{0xFF19283A, 0xFF33475C} : new int[]{0xFF34506A, 0xFF5E7B95};
        if (k.fog || "cloudy".equals(k.sky)) return k.night ? new int[]{0xFF272D35, 0xFF454F5A} : new int[]{0xFF56626E, 0xFF86929E};
        if (k.night) return new int[]{0xFF0B1A3A, 0xFF24426F};
        if ("partlycloudy".equals(k.sky)) return new int[]{0xFF2F7DBE, 0xFF79ACD4};
        return new int[]{0xFF1C84D3, 0xFF5AB6EE};
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<>(64);

    public static Bitmap bitmap(String code, int px, boolean onLight) {
        String key = code + "|" + px + "|" + onLight;
        Bitmap b = cache.get(key);
        if (b == null) {
            b = Bitmap.createBitmap(Math.max(1, px), Math.max(1, px), Bitmap.Config.ARGB_8888);
            draw(new Canvas(b), code, 0, 0, px, onLight);
            cache.put(key, b);
        }
        return b;
    }

    public static void draw(Canvas c, String code, float x, float y, float size, boolean onLight) {
        Kind k = parse(code);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.translate(x, y);
        c.scale(size / 100f, size / 100f);
        int cloud = onLight ? CLOUD_L : CLOUD, grey = onLight ? CLOUD_GREY_L : CLOUD_GREY, dark = onLight ? CLOUD_DARK_L : CLOUD_DARK;

        if (k.precip > 0 || k.thunder) {
            if (k.showers) {
                body(c, p, k.night, 30, 28, 15);
                cloud(c, p, 56, 40, 74, k.intensity == 3 || k.thunder ? dark : grey);
            } else {
                cloud(c, p, 50, 40, 82, k.intensity == 3 || k.thunder ? dark : grey);
            }
            int n = Math.max(1, k.intensity);
            float[] xs;
            if (k.thunder) {
                bolt(c, p);
                xs = n == 1 ? new float[]{28} : new float[]{26, 76};
            } else {
                xs = n == 1 ? new float[]{50} : n == 2 ? new float[]{40, 62} : new float[]{30, 50, 70};
            }
            if (k.precip == 0) xs = new float[0];
            for (int i = 0; i < xs.length; i++) {
                boolean flake = k.precip == 3 || (k.precip == 2 && i % 2 == 1);
                if (flake) flake(c, p, xs[i], 78, onLight ? SNOW_L : SNOW);
                else drop(c, p, xs[i], 70);
            }
        } else if (k.fog) {
            cloud(c, p, 50, 40, 74, grey);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(5.5f);
            p.setColor(onLight ? FOG_L : FOG);
            c.drawLine(18, 72, 80, 72, p);
            c.drawLine(26, 82, 88, 82, p);
            c.drawLine(20, 92, 72, 92, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            switch (k.sky) {
                case "fair":
                    body(c, p, k.night, 44, 42, k.night ? 26 : 21);
                    cloud(c, p, 70, 70, 44, cloud);
                    break;
                case "partlycloudy":
                    body(c, p, k.night, 36, 34, k.night ? 21 : 17);
                    cloud(c, p, 56, 60, 76, cloud);
                    break;
                case "cloudy":
                    cloud(c, p, 38, 44, 54, grey);
                    cloud(c, p, 58, 62, 76, cloud);
                    break;
                default:
                    body(c, p, k.night, 50, 50, k.night ? 32 : 24);
            }
        }
        c.restore();
    }

    private static void body(Canvas c, Paint p, boolean night, float cx, float cy, float r) {
        if (night) {
            Path a = new Path(), b = new Path();
            a.addCircle(cx, cy, r, Path.Direction.CW);
            b.addCircle(cx + r * 0.55f, cy - r * 0.38f, r * 0.84f, Path.Direction.CW);
            a.op(b, Path.Op.DIFFERENCE);
            p.setColor(MOON);
            c.drawPath(a, p);
            return;
        }
        p.setColor(RAY);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.2f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 1.38f, cy + sin * r * 1.38f, cx + cos * r * 1.78f, cy + sin * r * 1.78f, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(SUN);
        c.drawCircle(cx, cy, r, p);
    }

    private static final RectF rect = new RectF();

    private static void cloud(Canvas c, Paint p, float cx, float top, float w, int color) {
        Path path = new Path();
        synchronized (rect) {
            rect.set(cx - w / 2f, top, cx + w / 2f, top + w * 0.28f);
            path.addRoundRect(rect, w * 0.14f, w * 0.14f, Path.Direction.CW);
        }
        path.addCircle(cx - w * 0.2f, top + w * 0.04f, w * 0.2f, Path.Direction.CW);
        path.addCircle(cx + w * 0.06f, top - w * 0.04f, w * 0.26f, Path.Direction.CW);
        path.addCircle(cx + w * 0.3f, top + w * 0.1f, w * 0.16f, Path.Direction.CW);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private static void drop(Canvas c, Paint p, float x, float y) {
        p.setColor(RAIN);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(5.5f);
        c.drawLine(x + 4, y, x - 3, y + 16, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void flake(Canvas c, Paint p, float x, float y, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3.6f);
        float r = 7.5f;
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 + i * Math.PI / 3;
            float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
            c.drawLine(x - dx, y - dy, x + dx, y + dy, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void bolt(Canvas c, Paint p) {
        Path b = new Path();
        b.moveTo(56, 58);
        b.lineTo(41, 80);
        b.lineTo(51, 80);
        b.lineTo(45, 98);
        b.lineTo(64, 72);
        b.lineTo(54, 72);
        b.lineTo(60, 58);
        b.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(BOLT);
        c.drawPath(b, p);
    }
}
