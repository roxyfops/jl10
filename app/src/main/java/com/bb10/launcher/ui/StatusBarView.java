package com.bb10.launcher.ui;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.View;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;
import com.bb10.launcher.R;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatusBarView extends View {
    public static final float HEIGHT = 0.055f;
    private static final int SHADOW = 0xE0000000;
    private static final float EDGE_DX = 0.9f, EDGE_DY = 1.4f;

    public static volatile float scale = 1f;

    public static int height() {
        return Math.round(Ui.s(HEIGHT) * scale);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF(), arc = new RectF();
    private LinearGradient fillShade, boltShade;
    private float fillShadeH = -1, boltShadeH = -1;
    private boolean fillShadeDark;
    private final android.graphics.Matrix fillMatrix = new android.graphics.Matrix();
    private final Path bolt = new Path(), seg = new Path();
    private final android.graphics.Rect tb = new android.graphics.Rect();
    private final Handler h = new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
    private final Map<String, Drawable> glyphCache = new HashMap<>();
    private final Drawable alarm, logo;
    private int battery = -1;
    private boolean charging;
    private int wifiLevel = -1, cellLevel = -1;
    private String carrier = "", dataType = "";
    private boolean alarmSet;
    private int ringer = android.media.AudioManager.RINGER_MODE_NORMAL;
    private boolean started;
    private float downY;
    private Runnable onTick;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) readBattery(i);
            else if (android.media.AudioManager.RINGER_MODE_CHANGED_ACTION.equals(i.getAction())) readRinger();
            else {
                readRadios();
                if (onTick != null) onTick.run();
            }
            invalidate();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            readRadios();
            readRinger();
            invalidate();
            h.postDelayed(this, 10000);
        }
    };

    public interface Env {
        boolean clock24();

        boolean logo();

        boolean carrier();

        default boolean hideClock() { return false; }

        default String imsLabel() { return ""; }

        List<String> unread();

        Icon smallIcon(String pkg);

        default boolean netSpeed() { return false; }

        default boolean alarmIcon() { return true; }

        default boolean seconds() { return false; }

        default boolean batteryPercent() { return false; }

        default boolean notificationIcons() { return true; }
    }

    private final Runnable secondTick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!env().seconds()) {
                if (secondsShown) invalidate();
                h.postDelayed(this, 3000);
                return;
            }
            invalidate();
            h.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5);
        }
    };
    private boolean secondsShown;

    private long lastRx = -1, lastTx = -1, lastNetAt;
    private String upText = "", downText = "";
    private final Runnable netPoll = new Runnable() {
        @Override
        public void run() {
            if (!started || !env().netSpeed()) {
                lastRx = -1;
                if (!upText.isEmpty()) {
                    upText = downText = "";
                    invalidate();
                }
                if (started) h.postDelayed(this, 5000);
                return;
            }
            long rx = android.net.TrafficStats.getTotalRxBytes(), tx = android.net.TrafficStats.getTotalTxBytes();
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastRx >= 0 && rx >= lastRx && tx >= lastTx && now > lastNetAt) {
                float secs = (now - lastNetAt) / 1000f;
                String up = "↑" + rate((tx - lastTx) / secs), down = "↓" + rate((rx - lastRx) / secs);
                if (!up.equals(upText) || !down.equals(downText)) {
                    upText = up;
                    downText = down;
                    invalidate();
                }
            }
            lastRx = rx;
            lastTx = tx;
            lastNetAt = now;
            h.postDelayed(this, 1500);
        }
    };

    private static String rate(float bps) {
        float k = bps / 1024f;
        if (k < 1000) return Math.round(k) + "K";
        float m = k / 1024f;
        return m < 10 ? String.format(java.util.Locale.US, "%.1fM", m) : Math.round(m) + "M";
    }

    public static Env env;

    private static final Env LAUNCHER = new Env() {
        @Override
        public boolean clock24() { return LauncherApp.get().prefs.bool(Prefs.CLOCK_24, false); }

        @Override
        public boolean logo() { return LauncherApp.get().prefs.bool(Prefs.BB_LOGO, true); }

        @Override
        public boolean carrier() { return LauncherApp.get().prefs.bool(Prefs.BB_CARRIER, true); }

        @Override
        public String imsLabel() {
            String s = android.provider.Settings.Global.getString(LauncherApp.get().getContentResolver(), "bb10_volte");
            return s == null ? "" : s;
        }

        @Override
        public List<String> unread() { return LauncherApp.get().hub.unreadPackages(); }

        @Override
        public Icon smallIcon(String pkg) { return LauncherApp.get().hub.smallIcon(pkg); }

        @Override
        public boolean netSpeed() { return LauncherApp.get().prefs.bool(Prefs.NET_SPEED, false); }

        @Override
        public boolean alarmIcon() { return LauncherApp.get().prefs.bool(Prefs.ALARM_ICON, true); }

        @Override
        public boolean seconds() { return LauncherApp.get().prefs.bool(Prefs.CLOCK_SECONDS, false); }

        @Override
        public boolean batteryPercent() { return LauncherApp.get().prefs.bool(Prefs.BATTERY_PERCENT, false); }

        @Override
        public boolean notificationIcons() { return LauncherApp.get().prefs.bool(Prefs.NOTIF_ICONS, true); }
    };

    private static Env env() { return env != null ? env : LAUNCHER; }

    public int solidBg;
    public float darkIntensity;
    private static final int DARK_FG = 0xFF1A1A1A;
    private boolean dark;
    private int fg = Ui.TEXT;
    public boolean batteryPercent;

    public boolean peekPercent;

    private float popScale = 1f;
    private android.animation.Animator popAnim;
    private boolean popTarget;

    public void popPercent(boolean on) {
        if (popAnim != null ? popTarget == on : peekPercent == on) return;
        popTarget = on;
        if (popAnim != null) popAnim.cancel();
        if (!isAttachedToWindow()) {
            peekPercent = on;
            popScale = 1f;
            invalidate();
            return;
        }
        android.animation.ValueAnimator out = android.animation.ValueAnimator.ofFloat(popScale, 0.7f);
        out.setDuration(55);
        out.setInterpolator(new android.view.animation.AccelerateInterpolator());
        out.addUpdateListener(a -> {
            popScale = (float) a.getAnimatedValue();
            invalidate();
        });
        android.animation.ValueAnimator in = android.animation.ValueAnimator.ofFloat(0.7f, 1f);
        in.setDuration(170);
        in.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
        in.addUpdateListener(a -> {
            popScale = (float) a.getAnimatedValue();
            invalidate();
        });
        in.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator a) {
                peekPercent = on;
            }
        });
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playSequentially(out, in);
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (popAnim == set) popAnim = null;
                if (!cancelled) {
                    popScale = 1f;
                    invalidate();
                }
            }
        });
        popAnim = set;
        set.start();
    }

    public boolean groundOnly;

    public boolean lockExtras;
    public int rowHeight;
    private Boolean clockWant;
    private float clockA, clockFrom;
    private long clockT0;
    public boolean transparent;
    public boolean passTouches;
    public boolean pullOpensNotifications;

    public StatusBarView(Context c) {
        super(c);
        alarm = Ui.glyph(c, R.drawable.ic_alarm_solid, Ui.TEXT);
        logo = Ui.glyph(c, R.drawable.bb_logo, Ui.TEXT);
    }

    public void setOnTick(Runnable r) { onTick = r; }

    public boolean showClock = true;

    public void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
        Intent sticky = getContext().registerReceiver(rx, f, null, h);
        if (sticky != null) readBattery(sticky);
        readRinger();
        h.post(poll);
        h.post(netPoll);
        h.post(secondTick);
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            getContext().unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        h.removeCallbacks(poll);
        h.removeCallbacks(netPoll);
        h.removeCallbacks(secondTick);
        lastRx = -1;
    }

    private void readBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0) battery = Math.round(level * 100f / Math.max(1, scale));
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void readRinger() {
        try {
            android.media.AudioManager am = getContext().getSystemService(android.media.AudioManager.class);
            ringer = am == null ? android.media.AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        } catch (Exception e) {
            ringer = android.media.AudioManager.RINGER_MODE_NORMAL;
        }
    }

    @SuppressWarnings("deprecation")
    private void readRadios() {
        Context c = getContext();
        wifiLevel = -1;
        boolean cellularData = false;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = c.getSystemService(WifiManager.class);
                int rssi = wm.getConnectionInfo().getRssi();
                int max = wm.getMaxSignalLevel();
                wifiLevel = Math.round(wm.calculateSignalLevel(rssi) * 3f / Math.max(1, max));
            }
            cellularData = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception ignored) {
        }
        cellLevel = -1;
        carrier = "";
        dataType = "";
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            if (tm != null) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) cellLevel = ss.getLevel();
                CharSequence op = tm.getNetworkOperatorName();
                if (op == null || op.length() == 0) op = tm.getSimOperatorName();
                carrier = op == null ? "" : op.toString();
                if (cellularData && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    dataType = typeName(tm.getDataNetworkType());
                }
            }
        } catch (Exception ignored) {
        }
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            alarmSet = am != null && am.getNextAlarmClock() != null;
        } catch (Exception e) {
            alarmSet = false;
        }
    }

    private static String typeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN: return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "H+";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA: return "H";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "E";
            case TelephonyManager.NETWORK_TYPE_GPRS: return "G";
            default: return "";
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        shadeH = -1;
        setTopShade(topShade);
    }

    private int topShade = 0x90, shadeH = -1;

    public float groundAlpha = 1f;

    public float groundLeft;

    public void setTopShade(int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        int h = getHeight();
        if (alpha == topShade && h == shadeH) return;
        topShade = alpha;
        shadeH = h;
        if (h > 0) {
            int mid = Math.round(alpha * 0.45f);
            bg.setShader(new LinearGradient(0, 0, 0, h, new int[]{alpha << 24, mid << 24, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            invalidate();
        }
    }

    public boolean carrierTitle;

    public float extrasAlpha = 1f;

    public float bedsideSlide;

    private int extrasA() {
        return Math.round(255 * Math.max(0f, Math.min(1f, extrasAlpha)));
    }

    public boolean bedsideShown;
    private final Path moon = new Path(), moonCut = new Path();

    private static boolean isLate(Calendar now) {
        int h = now.get(Calendar.HOUR_OF_DAY);
        return h >= 21 || h < 6;
    }

    private void drawBedside(Canvas c, float cx, float cy, float S) {
        float my = cy + 0.017f * S, r = 0.02f * S;
        moon.rewind();
        moon.addCircle(cx - r * 0.25f, my, r, Path.Direction.CW);
        moonCut.rewind();
        moonCut.addCircle(cx + r * 0.3f, my - r * 0.3f, r * 0.9f, Path.Direction.CW);
        moon.op(moonCut, Path.Op.DIFFERENCE);
        moonCut.rewind();
        float kx = cx + r * 0.75f, ky = my + r * 0.35f, kr = r * 0.26f;
        moonCut.addCircle(kx - kr * 1.1f, ky + kr * 0.35f, kr * 0.85f, Path.Direction.CW);
        moonCut.addCircle(kx, ky, kr * 1.15f, Path.Direction.CW);
        moonCut.addCircle(kx + kr * 1.2f, ky + kr * 0.4f, kr * 0.8f, Path.Direction.CW);
        moonCut.addRect(kx - kr * 1.1f, ky + kr * 0.35f, kx + kr * 1.2f, ky + kr * 1.2f, Path.Direction.CW);
        moon.op(moonCut, Path.Op.UNION);
        int col = dark ? 0x80000000 : 0x8CFFFFFF;
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        shadow(p);
        c.drawPath(moon, p);
        float vy = cy + 0.06f * S, vw = 0.015f * S, vh = 0.011f * S;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.0035f * S);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        seg.rewind();
        seg.moveTo(cx - vw, vy - vh / 2);
        seg.lineTo(cx, vy + vh / 2);
        seg.lineTo(cx + vw, vy - vh / 2);
        c.drawPath(seg, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void shadow(Paint paint) {
        paint.setShadowLayer(0.9f, EDGE_DX, EDGE_DY, dark ? 0xCCFFFFFF : SHADOW);
    }

    private static void place(Drawable d, float cx, float cy, float size) {
        d.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(cx + size / 2), Math.round(cy + size / 2));
    }

    private final Map<String, Shadows.Mask> glyphShadows = new HashMap<>();
    private final Paint glyphShadow = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void drawGlyph(Canvas c, Drawable d, String key) {
        android.graphics.Rect b = d.getBounds();
        String k = key + '@' + b.width() + 'x' + b.height();
        Shadows.Mask m = glyphShadows.get(k);
        if (m == null && !glyphShadows.containsKey(k)) {
            if (glyphShadows.size() > 48) glyphShadows.clear();
            try {
                m = Shadows.make(d, b.width(), b.height(), 0.9f);
            } catch (Throwable t) {
                m = null;
            }
            glyphShadows.put(k, m);
        }
        if (m != null) {
            glyphShadow.setColor(dark ? 0xCCFFFFFF : SHADOW);
            c.drawBitmap(m.bmp, b.left + m.dx + EDGE_DX, b.top + m.dy + EDGE_DY, glyphShadow);
        }
        d.draw(c);
    }

    private static float barS(float rowH) {
        float s = Ui.S() * scale;
        return scale <= 1f ? s : Math.min(s, Math.max(Ui.S(), rowH / HEIGHT));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), hgt = getHeight();
        if (!transparent && (solidBg >>> 24) < 255 && groundAlpha > 0f && groundLeft < w) {
            bg.setAlpha(Math.round(255 * Math.min(1f, groundAlpha)));
            c.drawRect(Math.max(0f, groundLeft), 0, w, hgt, bg);
        }
        if (solidBg != 0) {
            int was = p.getColor();
            android.graphics.Shader sh = p.getShader();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(solidBg);
            c.drawRect(0, 0, w, hgt, p);
            p.setColor(was);
            p.setShader(sh);
        }
        if (groundOnly) return;
        dark = darkIntensity > 0.5f;
        fg = dark ? DARK_FG : Ui.TEXT;
        float rowH = rowHeight > 0 ? Math.min(rowHeight, hgt) : hgt;
        float S = barS(rowH);
        float cy = rowH * (0.026f / HEIGHT);
        float base = rowH * (0.0417f / HEIGHT);

        boolean h24 = env().clock24();
        Calendar now = Calendar.getInstance();
        secondsShown = env().seconds();
        String time = DateFormat.format(h24 ? (secondsShown ? "HH:mm:ss" : "HH:mm") : (secondsShown ? "h:mm:ss" : "h:mm"), now).toString();
        String ampm = h24 ? "" : (now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(fg);
        shadow(tp);
        tp.setTypeface(Ui.light());
        tp.setTextSize(0.049f * S);
        float tw = tp.measureText(time);
        float layoutW = secondsShown ? tp.measureText(time.replaceAll("[0-9]", "0")) : tw;
        ap.set(tp);
        ap.setTextSize(0.028f * S);
        float aw = ampm.isEmpty() ? 0 : ap.measureText(ampm);
        float tx = (w - layoutW - aw) / 2f;
        float clockL = tx, clockR = tx + Math.max(tw, layoutW) + aw;
        boolean want = showClock && !env().hideClock();
        long upNow = android.os.SystemClock.uptimeMillis();
        if (clockWant == null) {
            clockWant = want;
            clockA = want ? 1f : 0f;
            clockT0 = 0;
        } else if (clockWant != want) {
            clockWant = want;
            clockFrom = clockA;
            clockT0 = upNow;
        }
        if (clockT0 != 0) {
            float k = Math.min(1f, (upNow - clockT0) / 280f);
            float e = 1f - (1f - k) * (1f - k);
            clockA = clockFrom + ((want ? 1f : 0f) - clockFrom) * e;
            if (k < 1f) postInvalidateOnAnimation();
            else clockT0 = 0;
        }
        if (clockA > 0.01f) {
            boolean partial = clockA < 0.99f;
            int save = partial ? c.saveLayerAlpha(0, 0, w, hgt, Math.round(clockA * 255)) : -1;
            if (partial) c.translate(0, -(1f - clockA) * hgt * 0.8f);
            c.drawText(time, tx, base, tp);
            if (!ampm.isEmpty()) c.drawText(ampm, tx + tw, base, ap);
            if (partial) c.restoreToCount(save);
        }
        if (!want) clockL = clockR = w / 2f;
        if (carrierTitle && !want && !carrier.isEmpty()) {
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setColor(fg);
            shadow(tp);
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.042f * S);
            float cw = tp.measureText(carrier);
            c.drawText(carrier, w / 2f, cy + 0.015f * S, tp);
            clockL = (w - cw) / 2f;
            clockR = (w + cw) / 2f;
        }
        bedsideShown = lockExtras && !want && charging && isLate(now);
        if (bedsideShown && bedsideSlide < 1f) {
            float dy = -bedsideSlide * (cy + 0.075f * S);
            int sl = c.save();
            c.translate(0, dy);
            drawBedside(c, w / 2f, cy, S);
            c.restoreToCount(sl);
        }

        float bl = 0.014f * S, bw = 0.072f * S, bh = 0.033f * S, sw = 0.0032f * S, nub = 0.0055f * S;
        float rr = 0.0045f * S;
        float batteryEnd;
        int popSave = -1;
        if (popScale != 1f) {
            popSave = c.save();
            c.scale(popScale, popScale, bl + bw / 2f, cy);
        }
        if (peekPercent && battery >= 0 && !lockExtras) {
            String pct = battery + "%";
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(fg);
            shadow(tp);
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.041f * S);
            tp.getTextBounds("0", 0, 1, tb);
            c.drawText(pct, bl, cy + tb.height() / 2f, tp);
            batteryEnd = bl + tp.measureText(pct);
        } else {
        body.set(bl + sw / 2, cy - bh / 2 + sw / 2, bl + bw - nub - sw / 2, cy + bh / 2 - sw / 2);
        boolean low = battery >= 0 && battery <= 15 && !charging;
        int edge = low ? Ui.SPLAT : dark ? 0xFF3A3A3A : 0xFFE4E4E4;
        p.setStyle(Paint.Style.FILL);
        p.setColor(edge);
        shadow(p);
        arc.set(body.right, cy - bh * 0.24f, bl + bw, cy + bh * 0.24f);
        c.drawRoundRect(arc, nub * 0.4f, nub * 0.4f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        c.drawRoundRect(body, rr, rr, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        float in = sw / 2;
        if (battery >= 0) {
            float inset = in + sw * 0.5f;
            float inner = (body.width() - inset * 2) * battery / 100f;
            float ft = body.top + inset, fb = body.bottom - inset;
            if (low) {
                p.setColor(Ui.SPLAT);
            } else {
                if (fillShadeH != fb - ft || fillShadeDark != dark) {
                    fillShadeH = fb - ft;
                    fillShadeDark = dark;
                    fillShade = dark ? new LinearGradient(0, 0, 0, fillShadeH, 0xFF2A2A2A, 0xFF101010, Shader.TileMode.CLAMP)
                            : new LinearGradient(0, 0, 0, fillShadeH, 0xFFF6F6F6, 0xFFC9C9C9, Shader.TileMode.CLAMP);
                }
                fillMatrix.setTranslate(0, ft);
                fillShade.setLocalMatrix(fillMatrix);
                p.setColor(0xFFFFFFFF);
                p.setShader(fillShade);
            }
            c.drawRect(body.left + inset, ft, body.left + inset + inner, fb, p);
            p.setShader(null);
        }
        if (low && battery <= 5) {
            float ex = body.centerX(), t = body.top + sw * 1.6f, b = body.bottom - sw * 1.6f, ew = sw * 1.1f;
            p.setColor(0xFFFFFFFF);
            c.drawRect(ex - ew / 2, t, ex + ew / 2, b - (b - t) * 0.32f, p);
            c.drawRect(ex - ew / 2, b - (b - t) * 0.16f, ex + ew / 2, b, p);
        }
        batteryEnd = bl + bw;
        if (charging) {
            float bx0 = bl + bw + 0.002f * S, bwd = 0.017f * S, bt = cy - bh / 2, bb = cy + bh / 2;
            bolt.rewind();
            bolt.moveTo(bx0 + bwd * 0.78f, bt);
            bolt.lineTo(bx0, cy + bh * 0.08f);
            bolt.lineTo(bx0 + bwd * 0.46f, cy + bh * 0.08f);
            bolt.lineTo(bx0 + bwd * 0.22f, bb);
            bolt.lineTo(bx0 + bwd, cy - bh * 0.1f);
            bolt.lineTo(bx0 + bwd * 0.54f, cy - bh * 0.1f);
            bolt.close();
            long ms = android.os.SystemClock.uptimeMillis() % 1000;
            float k = ms < 500 ? ms / 500f : (1000 - ms) / 500f;
            int a = Math.round(255 * k * k * (3 - 2 * k));
            if (a > 0) {
                int sl = c.saveLayerAlpha(bx0 - sw * 2, bt - sw * 2, bx0 + bwd + sw * 3, bb + sw * 3, a);
                p.setColor(0xFFF0B400);
                shadow(p);
                c.drawPath(bolt, p);
                p.clearShadowLayer();
                if (boltShade == null || boltShadeH != bh) {
                    boltShadeH = bh;
                    boltShade = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xFFFFE04A, 0xFFF0A000, Shader.TileMode.CLAMP);
                }
                fillMatrix.setTranslate(0, cy);
                boltShade.setLocalMatrix(fillMatrix);
                p.setColor(0xFFFFFFFF);
                p.setShader(boltShade);
                c.drawPath(bolt, p);
                p.setShader(null);
                c.restoreToCount(sl);
            }
            batteryEnd = bx0 + bwd;
            if (started) postInvalidateDelayed(66);
        }
        }
        if (popSave >= 0) c.restoreToCount(popSave);

        float pitch = 0.044f * S, gx = batteryEnd + 0.025f * S;
        if (lockExtras && battery >= 0) {
            String num = String.valueOf(battery);
            float pctBase = cy + 0.057f * S;
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(fg);
            shadow(tp);
            tp.setTypeface(Ui.light());
            tp.setTextSize(0.04f * S);
            int sl = extrasAlpha < 1f ? c.saveLayerAlpha(0, 0, w, hgt, extrasA()) : -1;
            float nx = bl - 0.002f * S + tp.measureText(num);
            if (extrasA() > 0) {
                c.drawText(num, bl - 0.002f * S, pctBase, tp);
                tp.setTextSize(0.026f * S);
                c.drawText("%", nx + 0.002f * S, pctBase, tp);
            }
            if (sl >= 0) c.restoreToCount(sl);
        } else if (!peekPercent && (batteryPercent || env().batteryPercent()) && battery >= 0) {
            String pct = battery + "%";
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(fg);
            shadow(tp);
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.03f * S);
            tp.getTextBounds(pct, 0, pct.length(), tb);
            float px = bl + bw + 0.01f * S;
            c.drawText(pct, px, cy + tb.height() / 2f, tp);
            gx = px + tp.measureText(pct) + 0.012f * S + pitch / 2f;
        }
        float maxX = clockL - 0.015f * S;
        if (alarmSet && env().alarmIcon() && gx + pitch / 2 < maxX) {
            alarm.setTint(fg);
            place(alarm, gx, cy, 0.036f * S);
            drawGlyph(c, alarm, "alarm");
            gx += pitch;
        }
        if (ringer != android.media.AudioManager.RINGER_MODE_NORMAL && gx + pitch / 2 < maxX) {
            p.setColor(fg);
            shadow(p);
            RingerGlyph.draw(c, p, seg, arc, ringer, gx, cy, 0.036f * S);
            p.clearShadowLayer();
            gx += pitch;
        }
        List<String> pk = env().notificationIcons() ? env().unread() : java.util.Collections.emptyList();
        for (int i = 0; i < pk.size() && gx + pitch / 2 < maxX; i++) {
            Drawable d = glyph(pk.get(i));
            if (d == null) continue;
            d.setTint(fg);
            place(d, gx, cy, 0.034f * S);
            drawGlyph(c, d, pk.get(i));
            gx += pitch;
        }

        float r = w - 0.018f * S;
        float barW = 0.0053f * S, gap = 0.0027f * S, maxH = 0.0306f * S, barBottom = cy + maxH / 2;
        int filled = cellLevel < 0 ? 0 : Math.max(1, Math.round(cellLevel * 5f / 4f));
        p.setStyle(Paint.Style.FILL);
        for (int i = 4; i >= 0; i--) {
            float bhh = maxH * (i + 1f) / 5f;
            p.setColor(i < filled ? fg : (dark ? 0x40000000 : 0x59FFFFFF));
            if (i < filled) shadow(p);
            else p.clearShadowLayer();
            c.drawRect(r - barW, barBottom - bhh, r, barBottom, p);
            r -= barW + gap;
        }
        p.clearShadowLayer();

        boolean showLogo = env().logo();
        if (showLogo) {
            logo.setTint(fg);
            place(logo, w - 0.08f * S, cy, 0.036f * S);
            drawGlyph(c, logo, "logo");
        }

        float minX = clockR + 0.015f * S;
        float tr = showLogo ? w - 0.11f * S : r - 0.012f * S;
        tp.setTextAlign(Paint.Align.RIGHT);
        if (!dataType.isEmpty()) {
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.034f * S);
            tp.getTextBounds(dataType, 0, dataType.length(), tb);
            c.drawText(dataType, tr, cy + tb.height() / 2f, tp);
            tr -= tp.measureText(dataType) + 0.015f * S;
        }
        String ims = env().imsLabel();
        if (!ims.isEmpty()) {
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.02f * S);
            tp.getTextBounds(ims, 0, ims.length(), tb);
            c.drawText(ims, tr, cy + tb.height() / 2f, tp);
            tr -= tp.measureText(ims) + 0.014f * S;
        }
        if (carrierTitle) {
        } else if (lockExtras && !carrier.isEmpty()) {
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.033f * S);
            int sl = extrasAlpha < 1f ? c.saveLayerAlpha(0, 0, w, hgt, extrasA()) : -1;
            if (extrasA() > 0) c.drawText(carrier, w - 0.018f * S, cy + maxH / 2f + 0.041f * S, tp);
            if (sl >= 0) c.restoreToCount(sl);
        } else if (!carrier.isEmpty() && env().carrier()) {
            tp.setTypeface(Ui.bold());
            tp.setTextSize(0.029f * S);
            String cr = carrier;
            while (cr.length() > 1 && tr - tp.measureText(cr) < minX) cr = cr.substring(0, cr.length() - 1);
            if (tr - tp.measureText(cr) >= minX) {
                c.drawText(cr, tr, base, tp);
                tr -= tp.measureText(cr) + 0.015f * S;
            }
        }

        boolean wifiShown = wifiLevel >= 0 && tr - 0.04f * S > clockR;
        if (wifiShown) {
            float wr = 0.021f * S;
            float bx = tr - wr, by = cy + wr * 0.6f;
            float band = wr / 3f;
            for (int i = 0; i < 3; i++) {
                float outer = band * (i + 1), inner = i == 0 ? 0 : band * i + 0.0025f * S;
                seg.rewind();
                arc.set(bx - outer, by - outer, bx + outer, by + outer);
                seg.arcTo(arc, 225, 90);
                if (inner > 0) {
                    arc.set(bx - inner, by - inner, bx + inner, by + inner);
                    seg.arcTo(arc, 315, -90);
                }
                else seg.lineTo(bx, by);
                seg.close();
                p.setColor(i <= Math.max(0, wifiLevel - 1) ? fg : (dark ? 0x4D000000 : 0x66FFFFFF));
                shadow(p);
                c.drawPath(seg, p);
            }
            p.clearShadowLayer();
        }

        if (!upText.isEmpty()) {
            float nr = wifiShown ? tr - 0.052f * S : tr;
            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.019f * S);
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(fg);
            shadow(tp);
            float nw = Math.max(tp.measureText(upText), tp.measureText(downText));
            if (nr - nw > clockR + 0.01f * S) {
                c.drawText(upText, nr, cy - 0.003f * S, tp);
                c.drawText(downText, nr, cy + 0.003f * S + (tp.descent() - tp.ascent()) * 0.78f, tp);
            }
        }
        tp.clearShadowLayer();
    }

    private Drawable glyph(String pkg) {
        Drawable d = glyphCache.get(pkg);
        if (d == null) {
            Icon ic = env().smallIcon(pkg);
            if (ic == null) return null;
            try {
                d = ic.loadDrawable(getContext());
            } catch (Exception e) {
                d = null;
            }
            if (d == null) return null;
            d = d.mutate();
            d.setTint(Ui.TEXT);
            glyphCache.put(pkg, d);
        }
        return d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (passTouches) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getY() - downY > Ui.dp(10)) {
                    downY = Float.MAX_VALUE;
                    if (pullOpensNotifications) Ui.expandNotifications(getContext());
                    else Ui.expandQuickSettings(getContext());
                }
                return true;
        }
        return true;
    }
}
