package com.bb10.launcher.quickstep;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;

import com.bb10.launcher.ui.ActiveFramesPage;

import java.util.ArrayList;
import java.util.List;

public final class OwnSplash {
    private static final String TAG = "BB10Open";
    private static final float ICON_DP = 160f;

    private static final float GREY_IN = 0.4f, ICON_IN_FROM = 0.4f, ICON_IN_TO = 0.8f;

    static final long ICON_OUT = 133, OUT_DELAY = 83, OUT = 360;
    static final long OUT_TOTAL = Math.max(ICON_OUT, OUT_DELAY + OUT);
    private static final PathInterpolator ICON_EASE = new PathInterpolator(0.15f, 0f, 1f, 1f);
    private static final PathInterpolator OUT_EASE = new PathInterpolator(0f, 0f, 0.8f, 1f);

    private static final int GROUND_PX = 8;
    private static HardwareBuffer sGround;
    private static final List<OwnSplash> sAll = new ArrayList<>();

    private final SurfaceControl look, picture, ground, icon;
    private final int w, h;
    private final RectF from;
    private final boolean iconOnly;
    private final int size;
    private final float midX, midY;
    private float in, iconIn;
    private boolean pictureGone;
    private String pkg;
    private int task = -1;
    private final long made = android.os.SystemClock.uptimeMillis();

    private int want = -1;
    private float wantE, wantK;

    private OwnSplash(SurfaceControl look, SurfaceControl picture, SurfaceControl ground, SurfaceControl icon,
                      int w, int h, RectF from, boolean iconOnly, Icon ic) {
        this.look = look;
        this.picture = picture;
        this.ground = ground;
        this.icon = icon;
        this.w = w;
        this.h = h;
        this.from = from;
        this.iconOnly = iconOnly;
        size = ic != null ? ic.size : 1;
        midX = ic != null ? ic.midX : 0;
        midY = ic != null ? ic.midY : 0;
    }

    void setTask(int task) {
        this.task = task;
    }

    boolean fullyIn() {
        return want == 1 && in >= 1f && iconIn >= 1f;
    }

    boolean wanted() {
        return want == 1;
    }

    boolean settle(float e, float k) {
        if (want == -1) {
            int w = RemoteAnims.splashComing(pkg, task, made);
            decide(w == 1, e, k);
        }
        return want == 1;
    }

    private static boolean quickSkip() {
        com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
        return app != null && app.prefs != null && app.prefs.bool(com.bb10.launcher.Prefs.OWN_SPLASH_SKIP_QUICK, false);
    }

    private volatile boolean quick;

    void goQuickly() {
        quick = true;
    }

    long outTotal() {
        return OUT_TOTAL;
    }

    static boolean quickSkipOn() {
        return quickSkip();
    }

    private void decide(boolean yes, float e, float k) {
        want = yes ? 1 : 0;
        wantE = Math.max(0f, e);
        wantK = Math.max(0f, Math.min(0.999f, k));
        if (yes && iconOnly && k < 0.4f) in = iconIn = 1f;
        Log.i(TAG, "open: " + pkg + (yes ? " gets JL10's splash screen" : " has no splash screen: it just fades in")
                + " (known " + Math.round(e * DURATION_MS) + " ms into the zoom)");
    }

    private static final float DURATION_MS = ShellTransitions.DURATION;

    private static final java.util.Map<String, Boolean> sHasSplash = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean hasSplash(android.content.Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li == null || li.getComponent() == null) return false;
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            String key = pkg + "@" + pi.lastUpdateTime;
            Boolean b = sHasSplash.get(key);
            if (b != null) return b;
            android.content.pm.ActivityInfo ai = pm.getActivityInfo(li.getComponent(), 0);
            android.content.Context pc = c.createPackageContext(pkg, 0);
            android.content.res.Resources.Theme th = pc.getResources().newTheme();
            int theme = ai.getThemeResource();
            if (theme != 0) th.applyStyle(theme, true);
            boolean has = !bool(th, android.R.attr.windowIsTranslucent) && !bool(th, android.R.attr.windowIsFloating)
                    && !bool(th, android.R.attr.windowDisablePreview);
            if (has && android.os.Build.VERSION.SDK_INT >= 31) {
                android.util.TypedValue v = new android.util.TypedValue();
                if (th.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, v, true)
                        && (v.type == android.util.TypedValue.TYPE_NULL
                        || (v.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && v.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
                        && (v.data >>> 24) == 0))) has = false;
            }
            sHasSplash.put(key, has);
            Log.i(TAG, "splash screen of its own: " + pkg + " " + (has ? "yes" : "no"));
            return has;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean bool(android.content.res.Resources.Theme th, int attr) {
        android.content.res.TypedArray a = th.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getBoolean(0, false);
        } finally {
            a.recycle();
        }
    }

    public static int iconPx() {
        return Math.round(ICON_DP * density() * 1.25f);
    }

    private static float density() {
        return android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    public static SurfaceControl make(HardwareBuffer pic, int frameW, Rect frameIcon, boolean iconOnly, String pkg,
                                      java.util.function.Supplier<Drawable> appIcon) {
        if (android.os.Build.VERSION.SDK_INT < 33 || pic == null || frameW <= 0 || frameIcon == null) return null;
        SurfaceControl look = null;
        try {
            int w = pic.getWidth(), h = pic.getHeight();
            float k = w / (float) frameW;
            RectF from = new RectF(frameIcon.left * k, frameIcon.top * k, frameIcon.right * k, frameIcon.bottom * k);
            Icon ic = sIcons.get(pkg);
            if (ic == null || ic.buf.isClosed()) {
                Drawable d = appIcon.get();
                ic = d == null ? null : icon(d, Math.round(ICON_DP * density()));
                if (ic != null) sIcons.put(pkg, ic);
            }
            HardwareBuffer g = groundBuffer();
            if (g == null) return null;
            look = new SurfaceControl.Builder().setName("BB10 live frame").setHidden(true).build();
            SurfaceControl p = new SurfaceControl.Builder().setName("BB10 frame look").setParent(look)
                    .setBufferSize(w, h).setHidden(false).build();
            SurfaceControl gr = new SurfaceControl.Builder().setName("BB10 splash").setParent(look)
                    .setBufferSize(GROUND_PX, GROUND_PX).setHidden(false).build();
            SurfaceControl ics = ic == null ? null : new SurfaceControl.Builder().setName("BB10 splash icon").setParent(look)
                    .setBufferSize(ic.size, ic.size).setHidden(false).build();
            HardwareBuffer hole = holeBuffer();
            SurfaceControl rv = hole == null ? null : new SurfaceControl.Builder().setName("BB10 splash reveal").setParent(look)
                    .setBufferSize(HOLE_PX, HOLE_PX).setHidden(true).build();
            OwnSplash s = new OwnSplash(look, p, gr, ics, w, h, from, iconOnly, ic);
            s.reveal = rv;
            s.pkg = pkg;
            RemoteAnims.Tx t = new RemoteAnims.Tx();
            t.t.setBuffer(p, pic);
            t.layer(p, 0);
            t.t.setBuffer(gr, g);
            t.layer(gr, 1);
            t.scale(gr, w / (float) GROUND_PX, h / (float) GROUND_PX);
            t.alpha(gr, 0f);
            if (rv != null) {
                t.t.setBuffer(rv, hole);
                t.layer(rv, 2);
            }
            if (ics != null) {
                t.t.setBuffer(ics, ic.buf);
                t.layer(ics, 3);
                t.position(ics, from.left, from.top);
                t.scale(ics, from.width() / ic.size);
                t.alpha(ics, 0f);
            }
            t.apply();
            synchronized (sAll) {
                sAll.removeIf(o -> {
                    if (o.look.isValid()) return false;
                    o.release();
                    return true;
                });
                sAll.add(s);
            }
            return look;
        } catch (Throwable e) {
            Log.w(TAG, "JL10 splash screen unavailable", e);
            if (look != null) {
                try {
                    look.release();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    static boolean covering(String pkg) {
        if (pkg == null) return false;
        synchronized (sAll) {
            for (OwnSplash s : sAll) if (pkg.equals(s.pkg) && s.want != 0 && s.look.isValid()) return true;
        }
        return false;
    }

    static OwnSplash of(SurfaceControl look) {
        if (look == null) return null;
        synchronized (sAll) {
            for (OwnSplash s : sAll) if (s.look == look) return s;
        }
        return null;
    }

    static void forget(SurfaceControl look) {
        if (look == null) return;
        OwnSplash s = null;
        synchronized (sAll) {
            for (OwnSplash o : sAll) {
                if (o.look == look) {
                    s = o;
                    break;
                }
            }
            if (s != null) sAll.remove(s);
        }
        if (s != null) {
            s.release();
            boolean none = true;
            synchronized (sAll) {
                for (OwnSplash o : sAll) if (o.look.isValid()) none = false;
            }
            if (none) statusWhite(false);
        }
    }

    private void release() {
        for (SurfaceControl c : new SurfaceControl[]{picture, ground, icon, reveal}) {
            if (c == null) continue;
            try {
                c.release();
            } catch (Throwable ignored) {
            }
        }
    }

    float step(RemoteAnims.Tx tx, Point pos, RectF r, float e, float k, long outMs) {
        float s = Math.max(r.width() / w, r.height() / h);
        float left = (w - r.width() / s) / 2f, top = (h - r.height() / s) / 2f;
        reach = (float) Math.hypot(r.width() / s, r.height() / s) / 2f;
        float ox = pos.x + r.left - left * s, oy = pos.y + r.top - top * s;
        if (want == -1) {
            int c = RemoteAnims.splashComing(pkg, task, made);
            if (c != -1) decide(c == 1, e, k);
        }
        if (want != 1) return 1f;
        if (icon != null) {
            float fx = ox + from.left * s, fy = oy + from.top * s, fs = from.width() * s;
            float ex = Math.round(pos.x + r.centerX() - midX), ey = Math.round(pos.y + r.centerY() - midY);
            float m = clamp((k - wantK) / (1f - wantK));
            float gx = fx + (ex - fx) * m, gy = fy + (ey - fy) * m, gs = fs + (size - fs) * m;
            iconX = (gx - ox) / s;
            iconY = (gy - oy) / s;
            iconScale = gs / size / s;
            tx.position(icon, iconX, iconY);
            tx.scale(icon, iconScale);
        }
        if (in < 1f || iconIn < 1f) {
            float x = e - wantE;
            in = Math.max(in, smooth(x / GREY_IN));
            iconIn = Math.max(iconIn, smooth((x - ICON_IN_FROM) / (ICON_IN_TO - ICON_IN_FROM)));
        }
        tx.alpha(ground, in);
        if (in >= 1f && !pictureGone) {
            tx.hide(picture);
            pictureGone = true;
        }
        return out(tx, outMs);
    }

    private float iconX, iconY, iconScale = 1f;

    static final long PULSE_AFTER = 500;
    private static final long PULSE_PERIOD = 1600, PULSE_RAMP = 800;
    private static final float PULSE = 0.045f;

    void pulse(RemoteAnims.Tx tx, long ms) {
        if (icon == null || ms <= 0) return;
        float ramp = smooth(ms / (float) PULSE_RAMP);
        float p = 1f + PULSE * ramp * (1f - (float) Math.cos(2 * Math.PI * ms / PULSE_PERIOD)) / 2f;
        float half = size * iconScale / 2f;
        float cx = iconX + half, cy = iconY + half;
        tx.position(icon, cx - half * p, cy - half * p);
        tx.scale(icon, iconScale * p);
    }

    private static volatile boolean sWhite;
    private static final java.util.concurrent.ExecutorService SEND = java.util.concurrent.Executors.newSingleThreadExecutor();

    static void statusWhite(boolean on) {
        if (sWhite == on) return;
        sWhite = on;
        com.bb10.launcher.LauncherApp app = com.bb10.launcher.LauncherApp.get();
        if (app == null) return;
        SEND.execute(() -> {
            try {
                app.sendBroadcast(new android.content.Intent("com.bb10.launcher.SPLASH")
                        .setPackage("com.android.systemui").putExtra("showing", on));
            } catch (Throwable ignored) {
            }
        });
    }

    float out(RemoteAnims.Tx tx, long ms) {
        statusWhite(ms < outTotal());
        if (icon != null) tx.alpha(icon, ms < 0 ? iconIn : iconIn * (1f - ICON_EASE.getInterpolation(clamp(ms / (float) ICON_OUT))));
        if (ms < 0) return 1f;
        return reveal(tx, clamp((ms - OUT_DELAY) / (float) OUT));
    }

    private float reveal(RemoteAnims.Tx tx, float p) {
        if (reveal == null || reach <= 0f) return 1f - OUT_EASE.getInterpolation(p);
        if (p <= 0f) return 1f;
        if (!revealing) {
            revealing = true;
            tx.show(reveal);
        }
        float q = REVEAL_EASE.getInterpolation(p);
        float edge = reach * (EDGE_FROM + (EDGE_TO - EDGE_FROM) * q);
        float sc = Math.max(edge / HOLE_R, reach / (HOLE_PX / 2f));
        tx.scale(reveal, sc);
        tx.position(reveal, w / 2f - HOLE_PX * sc / 2f, h / 2f - HOLE_PX * sc / 2f);
        tx.alpha(ground, 0f);
        return 1f - q;
    }

    private static final float EDGE_FROM = 0.05f, EDGE_TO = 1.05f / (1f - 0.5f);
    private static final float BAND = 0.5f;

    private static final int HOLE_PX = 1024;
    private static final float HOLE_R = 24f;
    private static final PathInterpolator REVEAL_EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static HardwareBuffer sHole;
    private SurfaceControl reveal;
    private boolean revealing;
    private float reach;

    public static void warm() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            holeBuffer();
            groundBuffer();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized HardwareBuffer holeBuffer() {
        if (sHole == null || sHole.isClosed()) {
            int n = HOLE_PX;
            int[] px = new int[n * n];
            int rgb = ActiveFramesPage.GROUND & 0xFFFFFF;
            for (int y = 0; y < n; y++) {
                float dy = y + 0.5f - n / 2f;
                for (int x = 0; x < n; x++) {
                    float dx = x + 0.5f - n / 2f;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    int a = Math.round(255 * smooth((d - HOLE_R * (1f - BAND)) / (HOLE_R * BAND)));
                    px[y * n + x] = (a << 24) | rgb;
                }
            }
            Bitmap b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
            b.recycle();
            sHole = hw != null ? hw.getHardwareBuffer() : null;
        }
        return sHole;
    }

    private static float clamp(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    private static float smooth(float x) {
        float k = clamp(x);
        return k * k * (3f - 2f * k);
    }

    private static final java.util.Map<String, Icon> sIcons = new java.util.concurrent.ConcurrentHashMap<>();

    public static void forgetIcons() {
        sIcons.clear();
    }

    private static final class Icon {
        HardwareBuffer buf;
        int size;
        float midX, midY;
    }

    private static Icon icon(Drawable d, int want) {
        Bitmap b = draw(d, want);
        Rect vis = visible(b);
        if (vis == null) return null;
        int size = want;
        int longer = Math.max(vis.width(), vis.height());
        if (Math.abs(longer - want) > 1) {
            size = Math.max(want / 2, Math.min(want * 3, Math.round(want * want / (float) longer)));
            b.recycle();
            b = draw(d, size);
            vis = visible(b);
            if (vis == null) return null;
        }
        Icon ic = new Icon();
        ic.size = size;
        ic.midX = (vis.left + vis.right) / 2f;
        ic.midY = (vis.top + vis.bottom) / 2f;
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        b.recycle();
        ic.buf = hw != null ? hw.getHardwareBuffer() : null;
        return ic.buf != null ? ic : null;
    }

    private static Bitmap draw(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Rect keep = d.copyBounds();
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        d.setBounds(keep);
        return b;
    }

    private static Rect visible(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bo = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) < 48) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > bo) bo = y;
            }
        }
        return r < 0 ? null : new Rect(l, t, r + 1, bo + 1);
    }

    private static synchronized HardwareBuffer groundBuffer() {
        if (sGround == null || sGround.isClosed()) {
            Bitmap b = Bitmap.createBitmap(GROUND_PX, GROUND_PX, Bitmap.Config.ARGB_8888);
            b.eraseColor(ActiveFramesPage.GROUND);
            Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
            b.recycle();
            sGround = hw != null ? hw.getHardwareBuffer() : null;
        }
        return sGround;
    }
}
