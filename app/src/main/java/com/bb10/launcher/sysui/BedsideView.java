package com.bb10.launcher.sysui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import com.bb10.launcher.R;
import com.bb10.launcher.ui.Ui;

import java.util.Calendar;

final class BedsideView extends View {
    private static final long DIM_AFTER_MS = 7000;
    private static final int ORANGE = 0xFFB93D18, ORANGE_DIM = 0xFF963214, RIM = 0xFF1C1C1C;
    private static final int AM_RED = 0xFFE8383A, PM_BLUE = 0xFF1E90D8;
    static final String K_ALARM = "bb10_bedside_alarm";
    static final String CLOCK_PKG = "com.bb10.clock";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), drop = new Path();
    private final RectF r = new RectF();
    private final android.graphics.Rect numBounds = new android.graphics.Rect();
    private final int slop;
    private Drawable alarmGlyph;

    private float down;
    private float content;
    private float chrome = 1f;
    private ValueAnimator slide, fadeIn, chromeAnim;
    private boolean open;

    private int alarmMin = 6 * 60;
    private boolean alarmOn;
    private boolean setting;
    private String toast;
    private long toastUntil;

    BedsideView(Context c) {
        super(c);
        setVisibility(GONE);
        setClickable(true);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void dragOpen(float dy) {
        if (slide != null) slide.cancel();
        if (getVisibility() != VISIBLE) loadAlarm();
        setVisibility(VISIBLE);
        content = 0f;
        setDown(Math.max(0f, Math.min(1f, dy / Math.max(1, getRootHeight()))));
    }

    void release(boolean stay) {
        animateDown(stay ? 1f : 0f);
    }

    private float getRootHeight() {
        View p = (View) getParent();
        return p != null ? p.getHeight() : getHeight();
    }

    private void setDown(float d) {
        down = d;
        setTranslationY(-(1f - d) * getRootHeight());
        invalidate();
    }

    private void animateDown(float to) {
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(down, to);
        slide.setDuration(Math.round(260 * Math.abs(to - down)) + 60);
        slide.setInterpolator(new DecelerateInterpolator(1.4f));
        slide.addUpdateListener(a -> setDown((float) a.getAnimatedValue()));
        slide.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                if (to >= 1f) opened();
                else closed();
            }
        });
        slide.start();
    }

    private void opened() {
        open = true;
        setKeepScreenOn(true);
        if (fadeIn != null) fadeIn.cancel();
        fadeIn = ValueAnimator.ofFloat(content, 1f);
        fadeIn.setDuration(450);
        fadeIn.addUpdateListener(a -> {
            content = (float) a.getAnimatedValue();
            invalidate();
        });
        fadeIn.start();
        wake();
        removeCallbacks(tick);
        post(tick);
    }

    private void closed() {
        open = false;
        setting = false;
        toast = null;
        setKeepScreenOn(false);
        dimScreen(false);
        removeCallbacks(tick);
        removeCallbacks(dim);
        if (fadeIn != null) fadeIn.cancel();
        if (chromeAnim != null) chromeAnim.cancel();
        chrome = 1f;
        content = 0f;
        setVisibility(GONE);
    }

    void dismissNow() {
        if (slide != null) slide.cancel();
        down = 0f;
        closed();
    }

    private final Runnable dim = () -> {
        animateChrome(0f, 700);
        dimScreen(true);
    };

    private void wake() {
        removeCallbacks(dim);
        dimScreen(false);
        if (chrome < 1f) animateChrome(1f, 250);
        postDelayed(dim, DIM_AFTER_MS);
    }

    private void animateChrome(float to, long ms) {
        if (chromeAnim != null) chromeAnim.cancel();
        chromeAnim = ValueAnimator.ofFloat(chrome, to);
        chromeAnim.setDuration(ms);
        chromeAnim.addUpdateListener(a -> {
            chrome = (float) a.getAnimatedValue();
            invalidate();
        });
        chromeAnim.start();
    }

    private boolean dimmed;

    private void dimScreen(boolean on) {
        if (on == dimmed) return;
        dimmed = on;
        try {
            android.hardware.display.DisplayManager dm = SysUi.sys.getSystemService(android.hardware.display.DisplayManager.class);
            java.lang.reflect.Method m = dm.getClass().getMethod("setTemporaryBrightness", int.class, float.class);
            m.invoke(dm, android.view.Display.DEFAULT_DISPLAY, on ? 0f : Float.NaN);
        } catch (Throwable t) {
            KeyguardHook.log("bedside brightness: " + t);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!open) return;
            invalidate();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5);
        }
    };

    private static final android.net.Uri CLOCK_MAIN = android.net.Uri.parse("content://com.bb10.clock.alarms/main");

    private static boolean bb10Clock() {
        try {
            return SysUi.sys.getPackageManager().resolveContentProvider("com.bb10.clock.alarms", 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private int globalMin = -1;

    private void loadAlarm() {
        if (!bb10Clock()) {
            loadGlobalAlarm();
            return;
        }
        try (android.database.Cursor c = SysUi.sys.getContentResolver().query(CLOCK_MAIN, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                alarmMin = c.getInt(0);
                alarmOn = c.getInt(1) != 0;
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            String v = Settings.Global.getString(SysUi.sys.getContentResolver(), K_ALARM);
            if (v == null) return;
            String[] a = v.split(",");
            alarmMin = Math.max(0, Math.min(24 * 60 - 1, Integer.parseInt(a[0].trim())));
            alarmOn = a.length > 1 && "on".equals(a[1].trim());
        } catch (Throwable ignored) {
        }
    }

    private void loadGlobalAlarm() {
        globalMin = -1;
        try {
            android.app.AlarmManager am = SysUi.sys.getSystemService(android.app.AlarmManager.class);
            android.app.AlarmManager.AlarmClockInfo next = am != null ? am.getNextAlarmClock() : null;
            if (next != null) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(next.getTriggerTime());
                alarmMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
                alarmOn = true;
                globalMin = alarmMin;
                return;
            }
        } catch (Throwable t) {
            KeyguardHook.log("bedside next alarm: " + t);
        }
        alarmOn = false;
        try {
            String v = Settings.Global.getString(SysUi.sys.getContentResolver(), K_ALARM);
            if (v != null) alarmMin = Math.max(0, Math.min(24 * 60 - 1, Integer.parseInt(v.split(",")[0].trim())));
        } catch (Throwable ignored) {
        }
    }

    private void saveGlobalAlarm() {
        int want = alarmOn ? alarmMin : -1;
        if (globalMin >= 0 && globalMin != want) {
            clockRequest(new Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, globalMin / 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, globalMin % 60));
        }
        if (want >= 0 && want != globalMin) {
            clockRequest(new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, want / 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, want % 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true));
        }
        globalMin = want;
        try {
            Settings.Global.putString(SysUi.sys.getContentResolver(), K_ALARM, alarmMin + "," + (alarmOn ? "on" : "off"));
        } catch (Throwable ignored) {
        }
    }

    private static void clockRequest(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (SysUi.sys.getPackageManager().resolveActivity(i, 0) == null) {
                KeyguardHook.log("bedside: no clock app takes " + i.getAction());
                return;
            }
            SysUi.sys.startActivity(i);
        } catch (Throwable t) {
            KeyguardHook.log("bedside " + i.getAction() + ": " + t);
        }
    }

    private void saveAlarm() {
        if (!bb10Clock()) {
            saveGlobalAlarm();
            return;
        }
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("minutes", alarmMin);
            v.put("on", alarmOn ? 1 : 0);
            if (SysUi.sys.getContentResolver().update(CLOCK_MAIN, v, null, null) > 0) return;
        } catch (Throwable ignored) {
        }
        try {
            Settings.Global.putString(SysUi.sys.getContentResolver(), K_ALARM, alarmMin + "," + (alarmOn ? "on" : "off"));
        } catch (Throwable t) {
            KeyguardHook.log("bedside alarm: " + t);
        }
    }

    private void showWhen() {
        Calendar now = Calendar.getInstance();
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int in = (alarmMin - cur + 24 * 60) % (24 * 60);
        if (in == 0) in = 24 * 60;
        toast = "Alarm will go off in " + (in / 60) + " hour(s) and " + (in % 60) + " minute(s).";
        toastUntil = android.os.SystemClock.uptimeMillis() + 2800;
        invalidate();
        postDelayed(this::invalidate, 2850);
    }

    private float downY, downX;
    private boolean dragging;
    private VelocityTracker vt;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!open) return true;
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getRawY();
                dragging = false;
                boolean wasDim = chrome < 0.5f;
                wake();
                setting = !wasDim && alarmOn && onHandle(e.getX(), e.getY());
                if (setting) invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (setting) {
                    followHandle(e.getX(), e.getY());
                    return true;
                }
                float dy = e.getRawY() - downY;
                if (!dragging && dy < -slop) dragging = true;
                if (dragging) setDown(1f + Math.min(0f, dy) / Math.max(1, getRootHeight()));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vy = vt.getYVelocity();
                vt.recycle();
                vt = null;
                if (setting) {
                    setting = false;
                    saveAlarm();
                    showWhen();
                    invalidate();
                } else if (dragging) {
                    boolean leave = down < 0.75f || vy < -900;
                    if (leave) {
                        open = false;
                        removeCallbacks(dim);
                        dimScreen(false);
                    }
                    animateDown(leave ? 0f : 1f);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && chrome > 0.5f) {
                    tap(downX, e.getY());
                }
                dragging = false;
                return true;
            }
        }
        return true;
    }

    private float cx() { return getWidth() / 2f; }
    private float cy() { return 0.424f * getHeight(); }
    private float S() { return Math.min(getWidth(), getHeight()); }
    private float inner() { return 0.286f * S(); }

    private double alarmAngle() {
        return Math.toRadians((alarmMin % 720) / 2.0 - 90);
    }

    private boolean onHandle(float x, float y) {
        double t = alarmAngle();
        float rr = edge(inner() + 0.011f * S(), t);
        float hx = cx() + (float) Math.cos(t) * rr, hy = cy() + (float) Math.sin(t) * rr;
        float d = (float) Math.hypot(x - hx, y - hy);
        return d < 0.085f * S();
    }

    private void followHandle(float x, float y) {
        double t = Math.atan2(y - cy(), x - cx());
        double deg = (Math.toDegrees(t) + 90 + 360) % 360;
        int m12 = (int) Math.round(deg * 2) % 720;
        int cur12 = alarmMin % 720;
        int delta = m12 - cur12;
        if (delta > 360) delta -= 720;
        if (delta < -360) delta += 720;
        int raw = ((alarmMin + delta) % 1440 + 1440) % 1440;
        int snapped = (Math.round(raw / 5f) * 5) % 1440;
        alarmRaw = raw;
        if (snapped != alarmMin) {
            alarmMin = snapped;
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    private int alarmRaw = -1;

    private void tap(float x, float y) {
        float W = getWidth(), H = getHeight(), S = S();
        float top = H - 0.137f * S;
        if (y > top && x > W - 0.2f * S) {
            alarmOn = !alarmOn;
            saveAlarm();
            if (alarmOn) showWhen();
            invalidate();
            return;
        }
        boolean plus = x > W - 0.11f * S && y < 0.11f * S;
        boolean row = y > top;
        if (!plus && !row) return;
        openClock(plus ? android.provider.AlarmClock.ACTION_SET_ALARM : android.provider.AlarmClock.ACTION_SHOW_ALARMS);
    }

    private void openClock(String action) {
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            i.setPackage(CLOCK_PKG);
            if (SysUi.sys.getPackageManager().resolveActivity(i, 0) != null) {
                SysUi.sys.startActivity(new Intent(i).putExtra("over_lock", true));
                return;
            }
        } catch (Throwable ignored) {
        }
        i.setPackage(null);
        dismissNow();
        KeyguardHook.openAfterUnlock(() -> {
            try {
                SysUi.sys.startActivity(i);
            } catch (Throwable t) {
                KeyguardHook.log("bedside alarms: " + t);
            }
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), S = S();
        c.drawColor(0xFF000000);
        if (content <= 0f) return;
        int a = Math.round(255 * content);
        int save = c.saveLayerAlpha(0, 0, W, H, a);
        float cx = cx(), cy = cy();
        drawFace(c, cx, cy, S);
        if (chrome > 0f) drawChrome(c, W, H, S);
        drawAlarmMark(c, cx, cy, S);
        if (toast != null && android.os.SystemClock.uptimeMillis() < toastUntil) drawToast(c, W, H, S);
        c.restoreToCount(save);
    }

    private static float edge(float a, double t) {
        double cs = Math.abs(Math.cos(t)), sn = Math.abs(Math.sin(t)), n = 4.2;
        return (float) (a / Math.pow(Math.pow(cs, n) + Math.pow(sn, n), 1.0 / n));
    }

    private void drawFace(Canvas c, float cx, float cy, float S) {
        float a = inner();
        float rim = setting ? 1f : chrome;
        if (rim > 0f) {
            path.rewind();
            for (int i = 0; i <= 120; i++) {
                double t = i * Math.PI * 2 / 120;
                float rr = edge(a + 0.011f * S, t);
                float x = cx + (float) Math.cos(t) * rr, y = cy + (float) Math.sin(t) * rr;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.02f * S);
            p.setColor(RIM);
            p.setAlpha(Math.round(255 * rim));
            c.drawPath(path, p);
            p.setAlpha(255);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(ORANGE);
        p.setTypeface(Ui.bold());
        p.setTextAlign(Paint.Align.CENTER);
        float in = 0.012f * S;
        for (int m = 0; m < 60; m++) {
            double t = Math.toRadians(m * 6 - 90);
            float rr = edge(a, t);
            boolean hour = m % 5 == 0;
            if (hour && m % 15 == 0) {
                String n = String.valueOf(m == 0 ? 12 : m / 5);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(0.072f * S);
                p.getTextBounds(n, 0, n.length(), numBounds);
                float nr = rr - in - numBounds.height() / 2f;
                float x = cx + (float) Math.cos(t) * nr, y = cy + (float) Math.sin(t) * nr;
                c.drawText(n, x, y - (numBounds.top + numBounds.bottom) / 2f, p);
                p.setStyle(Paint.Style.STROKE);
                continue;
            }
            int d = Math.min(m % 15, 15 - m % 15);
            if (!hour && (d == 1 || (d == 2 && (m < 3 || m > 57)))) continue;
            float len = (hour ? 0.034f : 0.018f) * S;
            p.setStrokeWidth((hour ? 0.009f : 0.004f) * S);
            float x0 = cx + (float) Math.cos(t) * (rr - in), y0 = cy + (float) Math.sin(t) * (rr - in);
            float x1 = cx + (float) Math.cos(t) * (rr - in - len), y1 = cy + (float) Math.sin(t) * (rr - in - len);
            c.drawLine(x0, y0, x1, y1, p);
        }
        if (setting) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(alarmMin < 720 ? AM_RED : PM_BLUE);
            p.setTypeface(Ui.regular());
            p.setTextSize(0.12f * S);
            String t = String.format(java.util.Locale.US, "%02d:%02d", alarmMin / 60, alarmMin % 60);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, p);
            return;
        }
        Calendar now = Calendar.getInstance();
        p.setStyle(Paint.Style.FILL);
        p.setColor(ORANGE_DIM);
        p.setTypeface(Ui.regular());
        p.setTextSize(0.036f * S);
        String date = now.get(Calendar.DAY_OF_MONTH) + " "
                + new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(now.getTime()).toUpperCase(java.util.Locale.getDefault());
        c.drawText(date, cx + 0.125f * S, cy + 0.012f * S, p);
        c.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", cx, cy + 0.15f * S, p);
        int h = now.get(Calendar.HOUR), mi = now.get(Calendar.MINUTE), se = now.get(Calendar.SECOND);
        double ts = Math.toRadians(se * 6 - 90);
        double tm = Math.toRadians((mi + se / 60.0) * 6 - 90);
        double th = Math.toRadians((h + mi / 60.0) * 30 - 90);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        float ml = 0.215f * S;
        p.setStrokeWidth(0.007f * S);
        p.setColor(0xFF4A4A4A);
        line(c, cx, cy, tm, 0f, 0.06f * S);
        p.setColor(0xFFE8461A);
        line(c, cx, cy, tm, 0.06f * S, ml);
        float hl = 0.13f * S;
        p.setStrokeWidth(0.016f * S);
        p.setColor(0xFFFF6A1E);
        line(c, cx, cy, th, 0.015f * S, hl);
        p.setStrokeWidth(0.004f * S);
        p.setColor(0xFFFFE0C8);
        line(c, cx, cy, th, hl - 0.035f * S, hl - 0.004f * S);
        p.setStrokeWidth(0.003f * S);
        p.setColor(0xFF505050);
        line(c, cx, cy, ts + Math.PI, 0f, 0.04f * S);
        line(c, cx, cy, ts, 0f, 0.27f * S);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF3A3A3A);
        c.drawCircle(cx, cy, 0.017f * S, p);
        p.setColor(0xFF222222);
        c.drawCircle(cx, cy, 0.007f * S, p);
    }

    private void line(Canvas c, float cx, float cy, double t, float from, float to) {
        float cs = (float) Math.cos(t), sn = (float) Math.sin(t);
        c.drawLine(cx + cs * from, cy + sn * from, cx + cs * to, cy + sn * to, p);
    }

    private void drawAlarmMark(Canvas c, float cx, float cy, float S) {
        if (!alarmOn) return;
        double t = alarmAngle();
        float rr = edge(inner() + 0.011f * S, t);
        float hx = cx + (float) Math.cos(t) * rr, hy = cy + (float) Math.sin(t) * rr;
        int col = alarmMin < 720 ? AM_RED : PM_BLUE;
        float show = setting ? 1f : chrome;
        p.setStyle(Paint.Style.FILL);
        if (show < 1f) {
            p.setColor(ORANGE);
            p.setAlpha(Math.round(255 * (1f - show)));
            c.drawCircle(hx, hy, 0.009f * S, p);
            p.setAlpha(255);
        }
        if (show <= 0f) return;
        int save = c.saveLayerAlpha(hx - 0.1f * S, hy - 0.1f * S, hx + 0.1f * S, hy + 0.1f * S, Math.round(255 * show));
        float br = 0.032f * S;
        if (setting) {
            p.setColor(0x66808080);
            c.drawCircle(hx, hy, br * 1.75f, p);
        }
        float ix = (float) -Math.cos(t), iy = (float) -Math.sin(t);
        float tipD = br * 1.55f;
        float tx = hx + ix * tipD, ty = hy + iy * tipD;
        double back = Math.atan2(iy, ix);
        double spread = Math.acos(br / tipD);
        drop.rewind();
        drop.addCircle(hx, hy, br, Path.Direction.CW);
        path.rewind();
        path.moveTo(tx, ty);
        path.lineTo(hx + br * (float) Math.cos(back + spread), hy + br * (float) Math.sin(back + spread));
        path.lineTo(hx + br * (float) Math.cos(back - spread), hy + br * (float) Math.sin(back - spread));
        path.close();
        drop.op(path, Path.Op.UNION);
        p.setColor(col);
        c.drawPath(drop, p);
        c.restoreToCount(save);
    }

    private void drawChrome(Canvas c, float W, float H, float S) {
        int a = Math.round(255 * chrome);
        int save = c.saveLayerAlpha(0, 0, W, H, a);
        float bs = 0.09f * S, bx = W - 0.015f * S - bs, by = 0.015f * S;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2F2F2F);
        c.drawRect(bx, by, bx + bs, by + bs, p);
        if (alarmGlyph == null) {
            alarmGlyph = SysUi.res.getDrawable(R.drawable.ic_alarm_solid, null).mutate();
            alarmGlyph.setTint(Ui.TEXT);
        }
        float gs = bs * 0.62f, gx = bx + (bs - gs) / 2f - bs * 0.06f, gy = by + (bs - gs) / 2f - bs * 0.04f;
        alarmGlyph.setBounds(Math.round(gx), Math.round(gy), Math.round(gx + gs), Math.round(gy + gs));
        alarmGlyph.draw(c);
        p.setStrokeWidth(0.0045f * S);
        float px = bx + bs * 0.74f, py = by + bs * 0.74f, pl = bs * 0.13f;
        p.setColor(0xFF2F2F2F);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(px, py, pl * 1.35f, p);
        p.setColor(Ui.TEXT);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(px - pl, py, px + pl, py, p);
        c.drawLine(px, py - pl, px, py + pl, p);
        float top = H - 0.137f * S;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF3C3C3C);
        c.drawRect(0, top, W, top + Math.max(1f, 0.0015f * S), p);
        String time = String.format(java.util.Locale.US, "%02d:%02d", alarmMin / 60, alarmMin % 60);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Ui.TEXT);
        p.setTypeface(Ui.bold());
        p.setTextSize(0.047f * S);
        float tx = 0.025f * S, base = top + 0.052f * S;
        c.drawText(time, tx, base, p);
        float tw = p.measureText(time);
        p.setTypeface(Ui.regular());
        c.drawText(alarmOn ? "Main Alarm" : "Alarm Off", tx + tw + 0.03f * S, base, p);
        p.setTypeface(Ui.bold());
        p.setTextSize(0.03f * S);
        p.setColor(PM_BLUE);
        c.drawText("No Recurrence", tx, top + 0.095f * S, p);
        float sw = 0.15f * S, sh = 0.086f * S, sx = W - 0.025f * S - sw, sy = top + (0.137f * S - sh) / 2f;
        r.set(sx, sy, sx + sw, sy + sh);
        p.setColor(alarmOn ? PM_BLUE : 0xFF3C3C3C);
        c.drawRoundRect(r, sh / 2f, sh / 2f, p);
        float kr = sh * 0.45f, kx = alarmOn ? sx + sw - sh / 2f : sx + sh / 2f, ky = sy + sh / 2f;
        p.setColor(alarmOn ? 0xFFF4F4F4 : 0xFFDADADA);
        c.drawCircle(kx, ky, kr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.003f * S);
        p.setColor(0xFF777777);
        if (alarmOn) c.drawLine(kx, ky - kr * 0.32f, kx, ky + kr * 0.32f, p);
        else c.drawCircle(kx, ky, kr * 0.36f, p);
        p.setStyle(Paint.Style.FILL);
        c.restoreToCount(save);
    }

    private void drawToast(Canvas c, float W, float H, float S) {
        float l = 0.036f * S, rgt = W - 0.036f * S, t = 0.386f * H, b = 0.597f * H;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF0303030);
        c.drawRect(l, t, rgt, b, p);
        p.setColor(Ui.TEXT);
        p.setTypeface(Ui.regular());
        p.setTextSize(0.04f * S);
        android.text.TextPaint tp = new android.text.TextPaint(p);
        tp.setTextAlign(Paint.Align.LEFT);
        int w = Math.round(rgt - l - 0.08f * S);
        android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(toast, 0, toast.length(), tp, w)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build();
        c.save();
        c.translate(l + 0.04f * S, (t + b) / 2f - sl.getHeight() / 2f);
        sl.draw(c);
        c.restore();
    }
}
