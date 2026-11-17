package com.bb10.launcher.gesture;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.Prefs;
import com.bb10.launcher.quickstep.RemoteAnims;
import com.bb10.launcher.quickstep.ShellTransitions;
import com.bb10.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SwipeUp {
    private static final String TAG = "JL10Gest";
    private static final float LOCK_FRAC = 0.27f;
    private static final float LOCK_SCALE = 0.6f;
    private static final long FLY_MS = 300, BACK_MS = 220, CATCH_UP_MS = 120;
    private static final float ACT_FRAC = 0.15f;
    private static final long SLOW_MS = 450;
    private static final int MIN_EDGE_DP = 20;

    private final GestureService s;
    private final Handler gh;
    private final WindowManager wm;

    private Strip strip;
    private WindowManager.LayoutParams stripLp;
    private Card card;
    private Bitmap shot;
    private boolean shooting;
    private Consumer<Bitmap> shotWaiter;
    private long downAt;
    private final Map<String, Drawable> iconCache = new HashMap<>();

    volatile boolean active;

    SwipeUp(GestureService s) {
        this.s = s;
        HandlerThread t = new HandlerThread("JL10-swipe", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        gh = new Handler(t.getLooper());
        wm = s.getSystemService(WindowManager.class);
    }

    void start() {
        gh.post(this::addStrip);
    }

    void stop() {
        gh.post(() -> {
            removeStrip();
            if (card != null) card.removeNow();
        });
    }

    void quit() {
        stop();
        gh.post(() -> gh.getLooper().quitSafely());
    }

    void setTouchable(boolean on) {
        gh.post(() -> {
            if (strip == null) return;
            int f = stripLp.flags;
            if (on) f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else f |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (f == stripLp.flags) return;
            stripLp.flags = f;
            try {
                wm.updateViewLayout(strip, stripLp);
            } catch (Exception ignored) {
            }
        });
    }

    boolean minimiseNow() {
        if (!s.prefs.bool(Prefs.FRAMES_MINIMIZE, true) || !s.app.frames.trackable(s.frontPkg())) return false;
        gh.post(() -> {
            if (card != null) return;
            prepareCard();
            if (card == null) {
                s.goHome();
                return;
            }
            begin(true);
        });
        return true;
    }

    private void addStrip() {
        removeStrip();
        Context c = s.themed;
        strip = new Strip(c);
        stripLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Ui.dp(Math.max(MIN_EDGE_DP, s.prefs.getInt(Prefs.GEST_EDGE, 20))),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        stripLp.gravity = Gravity.BOTTOM;
        stripLp.setFitInsetsTypes(0);
        stripLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (s.edgeLocked()) stripLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.addView(strip, stripLp);
        } catch (Exception e) {
            strip = null;
        }
    }

    private void removeStrip() {
        if (strip == null) return;
        try {
            wm.removeView(strip);
        } catch (Exception ignored) {
        }
        strip = null;
    }

    private final class Strip extends View {
        private static final int NONE = 0, MIN = 1, SIDE = 2, PEEK = 3, HOME = 4, HOME_HUB = 5;
        private int mode;
        private boolean homeFront;
        private float maxMove;
        private float sx, sy, sideFrom;
        private VelocityTracker vt;
        private boolean hubAsked;
        private boolean ignore;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Strip(Context c) {
            super(c);
        }

        private float lead(float dx) {
            return dx;
        }

        private boolean unlocked;
        private long unlockAt;
        private float unlockGap, lastSideRaw = -1;
        private int sideMode;
        private final Runnable sideTick = new Runnable() {
            @Override
            public void run() {
                if (lastSideRaw < 0 || mode != sideMode) return;
                sendSide(shown(lastSideRaw));
                if (SystemClock.uptimeMillis() - unlockAt < GLIDE_MS) postOnAnimation(this);
            }
        };
        private static final long GLIDE_MS = 170;

        private float detent() {
            return LOCK_FINGER * getResources().getDisplayMetrics().widthPixels / 720f;
        }

        private float holdMax() {
            return LOCK_HELD * getResources().getDisplayMetrics().widthPixels / 720f;
        }

        private float sideSpeed() {
            if (vt == null) return 0f;
            vt.computeCurrentVelocity(1000);
            float v = vt.getXVelocity();
            return sideMode == SIDE ? Math.abs(v) : v;
        }

        private void resetSide() {
            unlocked = false;
            lastSideRaw = -1;
            removeCallbacks(sideTick);
        }

        private float shown(float x) {
            x = Math.max(0f, x);
            float t = detent(), held = holdMax();
            if (!unlocked) {
                boolean flick = sideSpeed() > Ui.dp(LOCK_FLICK_DP);
                if (x < t && !flick) return heldAt(x, t, held);
                unlocked = true;
                unlockAt = SystemClock.uptimeMillis();
                unlockGap = x - heldAt(Math.min(x, t), t, held);
                s.haptic();
                postOnAnimation(sideTick);
            }
            float g = Math.min(1f, (SystemClock.uptimeMillis() - unlockAt) / (float) GLIDE_MS);
            float e = 1f - (1f - g) * (1f - g) * (1f - g);
            return x - unlockGap * (1f - e);
        }

        private float lastShown;

        private void side(int m, float raw) {
            sideMode = m;
            lastSideRaw = raw;
            sendSide(shown(raw));
        }

        private void sendSide(float x) {
            lastShown = x;
            if (sideMode == PEEK) s.hubPeekProgress(x);
            else if (sideMode == SIDE) s.hubOverCardProgress(x);
            else if (sideMode == HOME_HUB) s.homePeekSide(x);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!s.prefs.bool(Prefs.GEST_HANDLE, false)) return;
            float w = Ui.dp(44), hh = Ui.dp(3);
            p.setColor(0x88FFFFFF);
            c.drawRoundRect(new RectF((getWidth() - w) / 2f, getHeight() - hh - Ui.dp(3), (getWidth() + w) / 2f, getHeight() - Ui.dp(3)), hh, hh, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            if (vt == null) vt = VelocityTracker.obtain();
            MotionEvent copy = MotionEvent.obtain(e);
            copy.setLocation(x, y);
            vt.addMovement(copy);
            copy.recycle();
            float dy = sy - y, dx = x - sx;
            boolean homeOn = s.prefs.bool(Prefs.GEST_HOME, true), peekOn = s.prefs.bool(Prefs.GEST_PEEK, true);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (passing) {
                        if (GestureService.GDEBUG) Log.d(TAG, "tap came back to the strip; flags " + Integer.toHexString(stripLp.flags) + " src " + e.getSource() + " dev " + e.getDeviceId());
                        return false;
                    }
                    sx = x;
                    sy = y;
                    mode = NONE;
                    hubAsked = false;
                    ignore = false;
                    resetSide();
                    maxMove = 0;
                    downAt = SystemClock.uptimeMillis();
                    homeFront = s.homeFront();
                    if (card == null && s.app.frames.trackable(s.frontPkg())) {
                        shoot();
                        if (homeOn) prepareCard();
                        if (GestureService.GDEBUG) watchFrames();
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    maxMove = Math.max(maxMove, (float) Math.hypot(dx, dy));
                    if (ignore) return true;
                    float pdx = dx, pdy = dy;
                    if (s.prefs.bool(Prefs.GEST_PREDICT, true)) {
                        vt.computeCurrentVelocity(1000);
                        pdx = dx + ahead(vt.getXVelocity());
                        pdy = dy + ahead(-vt.getYVelocity());
                    }
                    if (mode == NONE && homeFront) {
                        if (homeOn && dy > Ui.dp(8) && dy > Math.abs(dx)) {
                            mode = HOME;
                            s.haptic();
                        }
                    }
                    if (mode == HOME) {
                        if (peekOn && dx > Ui.dp(48)) {
                            mode = HOME_HUB;
                            sideFrom = dx;
                            s.haptic();
                        } else s.homePeek(Math.max(0f, pdy));
                        if (mode == HOME) return true;
                    }
                    if (mode == HOME_HUB) {
                        side(HOME_HUB, pdx - sideFrom);
                        return true;
                    }
                    if (mode == NONE && !homeFront) {
                        float act = getResources().getDisplayMetrics().heightPixels * ACT_FRAC;
                        if (homeOn && card != null && dy > act && dy > Math.abs(dx) * 0.6f
                                && SystemClock.uptimeMillis() - downAt > SLOW_MS) {
                            ignore = true;
                            return true;
                        }
                        if (homeOn && card != null && dy > act && dy > Math.abs(dx) * 0.6f) {
                            mode = MIN;
                            s.haptic();
                            if (peekOn && !hubAsked) {
                                hubAsked = true;
                                s.hubOverCardPrepare();
                            }
                            GestureService.peekBar(s, true);
                            begin(false);
                            if (GestureService.GDEBUG) Log.d(TAG, "swipe up counted " + (SystemClock.uptimeMillis() - downAt) + " ms after touch");
                        }
                    }
                    if (mode == MIN && card != null) {
                        if (peekOn && !hubAsked && Math.abs(dx) > Ui.dp(8)) {
                            hubAsked = true;
                            s.hubOverCardPrepare();
                        }
                        if (peekOn && card.hasShot && hubAsked && Math.abs(dx) > Ui.dp(36)
                                && (card.locked() || Math.abs(dx) > dy * 0.7f)) {
                            mode = SIDE;
                            sideFrom = Math.abs(dx);
                            s.haptic();
                            card.handOver();
                        } else {
                            card.moveTo(Math.max(0, pdy), pdx);
                        }
                    }
                    if (mode == SIDE) side(SIDE, Math.abs(pdx) - sideFrom);
                    if (mode == PEEK) side(PEEK, pdx - Ui.dp(28));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    int W = getResources().getDisplayMetrics().widthPixels;
                    if (GestureService.GDEBUG) Log.d(TAG, "let go: mode " + mode + ", up " + Math.round(dy / Ui.dp(1)) + " dp at " + Math.round(-vy / Ui.dp(1)) + " dp/s");
                    if (mode == HOME) {
                        float vUp = -vy;
                        float L = getResources().getDisplayMetrics().heightPixels * LOCK_FRAC;
                        boolean go = up && (vUp > Ui.dp(500) || vUp > -Ui.dp(300) && dy > L * 0.35f);
                        s.homePeekEnd(go ? com.bb10.launcher.LauncherActivity.PEEK_FRAMES : com.bb10.launcher.LauncherActivity.PEEK_BACK);
                    } else if (mode == HOME_HUB) {
                        boolean open = up && hubOpens(lastShown, vx, W);
                        resetSide();
                        s.homePeekEnd(open ? com.bb10.launcher.LauncherActivity.PEEK_HUB : com.bb10.launcher.LauncherActivity.PEEK_BACK, vx);
                    } else if (mode == MIN && card != null) {
                        float vUp = -vy;
                        boolean go = up && homeOn && (vUp > Ui.dp(500) || vUp > -Ui.dp(300) && card.progress() > 0.35f);
                        if (go) card.fly(vx, vy);
                        else card.back();
                    } else if (mode == SIDE && card != null) {
                        float v = vx * Math.signum(dx);
                        boolean open = up && hubOpens(lastShown, v, W);
                        resetSide();
                        s.hubOverCardRelease(open, card.below, v);
                    } else if (mode == PEEK) {
                        boolean open = up && hubOpens(lastShown, vx, W);
                        resetSide();
                        s.hubPeekRelease(open, vx);
                    } else if (mode == NONE && up && homeOn && homeFront && dy > Ui.dp(8) && dy > Math.abs(dx)) {
                        s.homePeekEnd(com.bb10.launcher.LauncherActivity.PEEK_FRAMES);
                    } else if (mode == NONE && up && !ignore && homeOn && card != null && dy > Ui.dp(8) && dy > Math.abs(dx) && -vy > Ui.dp(500)) {
                        s.haptic();
                        begin(true);
                    } else {
                        dropPrepared();
                        boolean still = Math.max(maxMove, (float) Math.hypot(dx, dy)) < Ui.dp(6) && Math.abs(vy) < Ui.dp(300);
                        if (mode == NONE && up && still && SystemClock.uptimeMillis() - downAt < 300) passTap(x, y);
                    }
                    if (mode == MIN || mode == NONE) s.hubDropPrepared();
                    if (mode == MIN || mode == SIDE) GestureService.peekBar(s, false);
                    mode = NONE;
                    vt.recycle();
                    vt = null;
                    return true;
                }
            }
            return true;
        }
    }

    public static float ahead(float v) {
        float cap = Ui.dp(24);
        return Math.max(-cap, Math.min(cap, v * PREDICT_S));
    }

    private static final float PREDICT_S = 0.016f;

    public static float heldAt(float x, float t, float held) {
        float k = 1.2f;
        return held * (1f - (float) Math.exp(-k * x / t)) / (1f - (float) Math.exp(-k));
    }

    public static final float LOCK_FINGER = 240f, LOCK_HELD = 50f;
    public static final float LOCK_FLICK_DP = 900f;

    private static boolean hubOpens(float edge, float vx, int W) {
        if (vx > Ui.dp(450)) return true;
        return edge > W * 0.2f && vx > -Ui.dp(400);
    }

    private float resist(float x) {
        return com.bb10.launcher.LauncherActivity.sideResist(x, s.getResources().getDisplayMetrics().widthPixels);
    }

    private boolean passing;

    private void passTap(float x, float y) {
        if (strip == null || passing) return;
        passing = true;
        stripLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(strip, stripLp);
        } catch (Exception ignored) {
        }
        Runnable restore = () -> {
            if (!passing) return;
            passing = false;
            if (strip == null) return;
            if (!s.edgeLocked()) stripLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                wm.updateViewLayout(strip, stripLp);
            } catch (Exception ignored) {
            }
        };
        gh.postDelayed(() -> {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x, y);
            android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50)).build();
            boolean ok = false;
            try {
                ok = s.dispatchGesture(g, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription d) {
                        gh.postDelayed(restore, 30);
                    }

                    @Override
                    public void onCancelled(android.accessibilityservice.GestureDescription d) {
                        gh.post(restore);
                    }
                }, gh);
            } catch (Exception ignored) {
            }
            if (!ok) restore.run();
        }, 90);
        gh.postDelayed(restore, 800);
    }

    private void shoot() {
        if (shooting) return;
        shot = null;
        if (!s.canShoot()) return;
        shooting = true;
        attempt(SystemClock.uptimeMillis(), 0);
    }

    private void attempt(long t0, int tries) {
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, gh::post, new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult r) {
                    Bitmap b = null;
                    android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        b = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    } catch (Exception ignored) {
                    } finally {
                        hb.close();
                    }
                    got(b, t0);
                }

                @Override
                public void onFailure(int code) {
                    if (code == android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && tries < 5) {
                        gh.postDelayed(() -> attempt(t0, tries + 1), 80);
                        return;
                    }
                    if (GestureService.GDEBUG) Log.d(TAG, "screenshot failed: code " + code);
                    got(null, t0);
                }
            });
        } catch (Throwable t) {
            got(null, t0);
        }
    }

    private void got(Bitmap b, long t0) {
        shooting = false;
        shot = b;
        if (GestureService.GDEBUG) Log.d(TAG, "picture " + (b == null ? "failed" : "ready") + " " + (SystemClock.uptimeMillis() - t0) + " ms after touch");
        Consumer<Bitmap> w = shotWaiter;
        shotWaiter = null;
        if (w != null) w.accept(b);
    }

    private void whenShot(Consumer<Bitmap> cb) {
        if (shooting) shotWaiter = cb;
        else if (shot != null) cb.accept(shot);
        else {
            shoot();
            if (shooting) shotWaiter = cb;
            else cb.accept(null);
        }
    }

    private void prepareCard() {
        if (card != null || !s.prefs.bool(Prefs.FRAMES_MINIMIZE, true) || !s.app.frames.trackable(s.frontPkg())) return;
        Card c = new Card(s.themed, s.frontPkg());
        card = c;
        active = true;
        try {
            wm.addView(c, GestureService.fullScreenLp(false));
        } catch (Exception e) {
            card = null;
            active = false;
        }
    }

    private void dropPrepared() {
        if (card != null && !card.begun) card.removeNow();
    }

    private void begin(boolean straightIntoFrame) {
        Card c = card;
        if (c == null) return;
        c.begun = true;
        whenShot(b -> {
            if (card != c) return;
            if (b == null) {
                c.removeNow();
                if (straightIntoFrame) s.goHome();
                return;
            }
            c.show(b);
            if (straightIntoFrame) c.fly(0, 0);
            if (s.prefs.bool(Prefs.FRAMES_THUMBS, true) && !s.app.frames.usesTaskSnapshots()) s.thumbLater(c.pkg, b);
        });
    }

    private void watchFrames() {
        long[] last = {0};
        int[] n = {0};
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long ns) {
                long ms = SystemClock.uptimeMillis();
                if (last[0] != 0 && ms - last[0] > 25) Log.d(TAG, "swipe thread: frame " + n[0] + " " + (ms - last[0]) + " ms after the last");
                last[0] = ms;
                if (++n[0] < 60) Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    static final class WallPic extends View {
        Bitmap bmp;
        private final int dim;
        private final android.graphics.Paint pic = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();

        WallPic(Context c, int dim) {
            super(c);
            this.dim = dim;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF000000);
            Bitmap b = bmp;
            if (b == null || b.isRecycled()) return;
            android.graphics.Rect real = getContext().getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            float z = com.bb10.launcher.LauncherActivity.wallpaperZoom();
            float sw = real.width(), sh = real.height();
            float ox = -(z - 1) * sw / 2, oy = -(z - 1) * sh / 2;
            dst.set(ox, oy, ox + sw * z, oy + sh * z);
            c.drawBitmap(b, null, dst, pic);
            if (dim > 0) c.drawColor(dim << 24);
        }
    }

    private final class Card extends FrameLayout {
        final String pkg;
        private final View wallView;
        private final View column;
        private final GestureService.ClipCard pic;
        boolean begun, hasShot;
        private boolean done, landed, homeUp, closeOver, fading;
        private float lastDy, lastDx, f;
        private long shownAt;
        private ValueAnimator anim;

        final GestureService.Below below = new GestureService.Below() {
            @Override
            public void cancel() {
                gh.post(Card.this::back);
            }

            @Override
            public void removeNow() {
                gh.post(Card.this::removeNow);
            }
        };

        private boolean handedOver;
        private RectF handedRect;
        private float handedCrop;
        private boolean bandOver;

        void handOver() {
            if (!hasShot || done) return;
            boolean band = bandDown();
            if (band) postDelayed(this::dropBand, 150);
            else hideBand();
            bandOver = band;
            f = 1f;
            RectF from = new RectF(pic.r);
            from.offset(pic.getTranslationX(), 0);
            boolean past = lastDy > getHeight() * LOCK_FRAC;
            handedRect = past ? new RectF(from) : middle();
            handedCrop = past ? pic.cropTop : cropAt(1f);
            s.hubOverCardTake(pic.bmp, from, handedRect, handedCrop, bandOver);
            handedOver = true;
            postDelayed(() -> {
                if (handedOver) setAlpha(0f);
            }, 64);
        }

        Card(Context c, String pkg) {
            super(c);
            this.pkg = pkg;
            int pct = 0;
            try {
                pct = Math.max(0, Math.min(90, Integer.parseInt(s.prefs.sp.getString(Prefs.WALL_DIM, "0"))));
            } catch (Exception ignored) {
            }
            WallPic wp = new WallPic(c, Math.round(pct * 2.55f));
            Bitmap w = s.wall;
            if (w != null) {
                wp.bmp = w;
            } else {
                GestureService.loadWall(c, () -> gh.post(() -> {
                    wp.bmp = GestureService.homeWall();
                    wp.invalidate();
                }));
            }
            wallView = wp;
            wallView.setAlpha(0f);
            addView(wallView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            column = buildColumn(c);
            column.setAlpha(0f);
            addView(column, new LayoutParams(Math.round(0.19f * Ui.S()), LayoutParams.MATCH_PARENT));
            pic = new GestureService.ClipCard(c);
            pic.setVisibility(INVISIBLE);
            addView(pic, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            bar = new com.bb10.launcher.ui.StatusBarView(c);
            bar.peekPercent = true;
            bar.setAlpha(0f);
            addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(sb())));
        }

        private final com.bb10.launcher.ui.StatusBarView bar;

        private View band;
        private final Runnable bandIn = this::showBand;

        private void showBand() {
            if (done || !hasShot || band != null) return;
            int top = Math.round(sb());
            View b = makeBand(getContext(), s, top);
            int h = top + Math.round(0.062f * Ui.S());
            band = b;
            addView(b, indexOfChild(bar), new LayoutParams(LayoutParams.MATCH_PARENT, h));
            b.setTranslationY(-h);
            b.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }

        boolean bandDown() {
            return band != null && band.getTranslationY() > -1f;
        }

        private void hideBand() {
            removeCallbacks(bandIn);
            View b = band;
            if (b == null) return;
            band = null;
            b.animate().cancel();
            b.animate().translationY(-b.getHeight()).setDuration(160).withEndAction(() -> removeView(b)).start();
        }

        private void dropBand() {
            removeCallbacks(bandIn);
            View b = band;
            if (b == null) return;
            band = null;
            b.animate().cancel();
            removeView(b);
        }
        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            bar.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            bar.stop();
            super.onDetachedFromWindow();
        }

        private float sb() {
            return Math.max(0, s.statusBarH);
        }

        private RectF full() {
            return new RectF(0, 0, getWidth(), getHeight());
        }

        private float cropAt(float f) {
            if (noBar) return 0f;
            float k = Math.max(0f, Math.min(1f, f / 0.3f));
            return Math.min(sb(), pic.bmp != null ? pic.bmp.getHeight() / 4f : sb()) * k * k * (3f - 2f * k);
        }

        private RectF middle() {
            float W = getWidth(), H = getHeight() - sb(), top = sb();
            return new RectF(W * (1 - LOCK_SCALE) / 2, top + H * (1 - LOCK_SCALE) / 2, W * (1 + LOCK_SCALE) / 2, top + H * (1 + LOCK_SCALE) / 2);
        }

        float progress() {
            return f;
        }

        boolean locked() {
            return f >= 1f;
        }

        private int barRgb = -1;
        private boolean noBar;
        private boolean barDark;

        private int plainBar(Bitmap b, int h) {
            if (b == null || h < 4 || b.getHeight() < h) return -1;
            try {
                Bitmap strip = Bitmap.createBitmap(b, 0, h - 3, b.getWidth(), 2);
                Bitmap soft = strip.getConfig() == Bitmap.Config.HARDWARE ? strip.copy(Bitmap.Config.ARGB_8888, false) : strip;
                if (soft == null) return -1;
                int n = 16, w = soft.getWidth();
                int[] px = new int[n];
                for (int i = 0; i < n; i++) px[i] = soft.getPixel(Math.min(w - 1, (int) ((i + 0.5f) * w / n)), 1);
                for (int cand : px) {
                    int same = 0;
                    for (int p : px) if (near(cand, p)) same++;
                    if (same >= n * 3 / 4) return cand & 0xFFFFFF;
                }
            } catch (Throwable ignored) {
            }
            return -1;
        }

        private boolean near(int a, int b) {
            return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) < 20 && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) < 20
                    && Math.abs((a & 0xFF) - (b & 0xFF)) < 20;
        }

        private void barGround(float crop) {
            if (barRgb < 0) return;
            float k = Math.max(0f, Math.min(1f, 1f - crop / Math.max(1f, sb())));
            bar.solidBg = (Math.round(255 * k) << 24) | barRgb;
            bar.darkIntensity = barDark && k > 0.5f ? 1f : 0f;
            bar.invalidate();
        }

        void show(Bitmap b) {
            pic.bmp = b;
            noBar = "1".equals(android.provider.Settings.Global.getString(getContext().getContentResolver(), com.bb10.launcher.sysui.NoStatusBar.KEY));
            barRgb = noBar ? -1 : plainBar(b, Math.round(sb()));
            if (noBar) {
                pic.setTranslationZ(2f);
                bar.setTranslationZ(1f);
                bar.animate().cancel();
                bar.setAlpha(1f);
                bar.setTranslationY(-Math.max(1f, sb()));
                bar.animate().translationY(0f).setDuration(260).setInterpolator(new DecelerateInterpolator(1.5f)).start();
                bar.peekPercent = false;
                bar.popPercent(true);
            }
            if (barRgb >= 0) {
                int r = (barRgb >> 16) & 0xFF, g = (barRgb >> 8) & 0xFF, bl = barRgb & 0xFF;
                barDark = 0.299f * r + 0.587f * g + 0.114f * bl > 170f;
                barGround(0f);
                bar.animate().cancel();
                bar.setAlpha(1f);
                bar.peekPercent = false;
                bar.popPercent(true);
            }
            pic.cropTop = 0;
            pic.set(full());
            pic.setVisibility(VISIBLE);
            hasShot = true;
            removeCallbacks(bandIn);
            postDelayed(bandIn, 1000);
            wallView.setAlpha(1f);
            if (lastDy > Ui.dp(16)) shownAt = SystemClock.uptimeMillis();
            follow(lastDy, lastDx);
            if (shownAt != 0) postOnAnimation(catchUp);
        }

        private final Runnable catchUp = new Runnable() {
            @Override
            public void run() {
                if (done || anim != null) return;
                follow(lastDy, lastDx);
                if (shownAt != 0) postOnAnimation(this);
            }
        };

        void follow(float dy, float dx) {
            lastDy = dy;
            lastDx = dx;
            if (!hasShot || done || anim != null || getWidth() == 0) return;
            float W = getWidth(), H = getHeight(), L = H * LOCK_FRAC;
            float nf = Math.min(1f, dy / L);
            if (nf >= 1f && f < 1f) s.haptic();
            f = nf;
            float fe = f * f * (3f - 2f * f);
            float sc = 1f - (1f - LOCK_SCALE) * fe;
            float over = Math.max(0f, dy - L), band = Ui.dp(700);
            float more = 1f - (float) Math.exp(-over / band);
            float bottom = H - Math.min(dy, L);
            float cx = W / 2f + dx * 0.5f * (1f - f);
            float crop = cropAt(f), ph = H - crop;
            pic.cropTop = crop;
            if (barRgb >= 0) barGround(crop);
            else if (!noBar) bar.setAlpha(crop / Math.max(1f, sb()));
            RectF r = new RectF(cx - W * sc / 2f, bottom - ph * sc, cx + W * sc / 2f, bottom);
            if (more > 0f) {
                float k = 1f - 0.45f * more, mx = r.centerX(), my = r.centerY();
                float hw = r.width() / 2f * k, hh = r.height() / 2f * k;
                r.set(mx - hw, my - hh, mx + hw, my + hh);
            }
            float k = 1f;
            if (shownAt != 0) {
                float t = Math.min(1f, (SystemClock.uptimeMillis() - shownAt) / (float) CATCH_UP_MS);
                k = 1f - (1f - t) * (1f - t);
                if (t >= 1f) shownAt = 0;
                r = GestureService.lerp(full(), r, k);
            }
            pic.set(r);
            float u = Math.max(0f, Math.min(1f, (f - 0.08f) / 0.62f)) * k;
            u = u * u * (3f - 2f * u);
            column.setAlpha(u);
            column.setTranslationX(-column.getWidth() * 0.5f * (1f - u));
        }

        private float tDy, tDx, sDy, sDx;
        private boolean smoothing;
        private long smoothT;
        private static final float WEIGHT_MS = 55f;

        private final Runnable smoothRun = new Runnable() {
            @Override
            public void run() {
                if (done) {
                    smoothing = false;
                    return;
                }
                long now = SystemClock.uptimeMillis();
                float k = 1f - (float) Math.exp(-Math.max(1, now - smoothT) / WEIGHT_MS);
                smoothT = now;
                sDy += (tDy - sDy) * k;
                sDx += (tDx - sDx) * k;
                follow(sDy, sDx);
                if (Math.abs(tDy - sDy) > 0.5f || Math.abs(tDx - sDx) > 0.5f) postOnAnimation(this);
                else smoothing = false;
            }
        };

        void moveTo(float dy, float dx) {
            tDy = dy;
            tDx = dx;
            if (!smoothing) {
                smoothing = true;
                smoothT = SystemClock.uptimeMillis();
                postOnAnimation(smoothRun);
            }
        }

        void push(float x) {
            hideBand();
            if (pushAnim != null) pushAnim.cancel();
            wallView.setTranslationX(x);
            pic.setTranslationX(x);
            column.setTranslationX(x);
        }

        private ValueAnimator pushAnim;

        void pushTo(float x, long ms) {
            if (pushAnim != null) pushAnim.cancel();
            ValueAnimator a = ValueAnimator.ofFloat(pic.getTranslationX(), x);
            pushAnim = a;
            a.setDuration(ms);
            a.setInterpolator(new DecelerateInterpolator(1.5f));
            a.addUpdateListener(va -> {
                float v = (float) va.getAnimatedValue();
                wallView.setTranslationX(v);
                pic.setTranslationX(v);
                column.setTranslationX(v);
            });
            a.start();
        }

        void toMiddle() {
            if (!hasShot || done) return;
            f = 1f;
            hideBand();
            bar.animate().alpha(1f).setDuration(180).start();
            animateTo(middle(), cropAt(1f), 180, new DecelerateInterpolator(1.5f), null, null);
            column.animate().alpha(1f).translationX(0).setDuration(180).start();
        }

        void back() {
            if (done && anim == null && !hasShot) {
                removeNow();
                return;
            }
            if (!hasShot) {
                removeNow();
                return;
            }
            boolean fromHub = handedOver;
            if (handedOver) {
                handedOver = false;
                if (anim != null) anim.cancel();
                if (pushAnim != null) pushAnim.cancel();
                wallView.setTranslationX(0);
                pic.setTranslationX(0);
                column.animate().cancel();
                column.setTranslationX(0);
                column.setAlpha(1f);
                wallView.setAlpha(1f);
                pic.cropTop = handedCrop;
                pic.set(handedRect != null ? handedRect : middle());
                bar.setAlpha(1f);
                setAlpha(1f);
            }
            done = true;
            hideBand();
            if (pic.getTranslationX() != 0f) pushTo(0f, BACK_MS);
            column.animate().alpha(0f).translationX(-column.getWidth() * 0.5f).setDuration(160).start();
            if (noBar) bar.animate().translationY(-Math.max(1f, sb())).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).start();
            else bar.animate().alpha(0f).setDuration(90).start();
            if (fromHub) animateTo(full(), 0, 300, new android.view.animation.AccelerateDecelerateInterpolator(), null, this::removeNow);
            else animateTo(full(), 0, BACK_MS, new DecelerateInterpolator(1.6f), null, this::removeNow);
        }

        void fly(float vx, float vy) {
            if (!hasShot) {
                removeNow();
                s.goHome();
                return;
            }
            done = true;
            hideBand();
            RectF slot = s.slotRect(getWidth(), getHeight());
            bar.popPercent(false);
            float bar = Ui.dp(com.bb10.launcher.ui.ActiveFramesPage.BAR_DP);
            ValueAnimator trim = ValueAnimator.ofFloat(0f, 1f);
            trim.setDuration(FLY_MS);
            trim.addUpdateListener(a -> {
                float k = Math.max(0f, Math.min(1f, ((float) a.getAnimatedValue() - 0.5f) / 0.5f));
                pic.trimBottom = bar * k * k * (3f - 2f * k);
                pic.shadowAlpha = 1f - k * k * (3f - 2f * k);
                pic.invalidate();
            });
            trim.start();
            float crop = noBar ? 0f : Math.min(s.statusBarH, pic.bmp.getHeight() / 4f);
            boolean viaSystem = RemoteAnims.closeRegistered() || ShellTransitions.homeRegistered();
            column.animate().alpha(0f).setDuration(140).start();
            float[] v = {vx * 0.5f * (1f - f), vy};
            animateTo(slot, crop, FLY_MS, new LinearInterpolator(), v, () -> {
                landed = true;
                fadeWhenDone();
            });
            if (viaSystem) {
                RemoteAnims.pendingCloseFrom = new RectF(slot);
                Runnable up = () -> gh.post(this::homeUp), over = () -> gh.post(this::closeOver);
                RemoteAnims.onCloseStarted = up;
                RemoteAnims.onCloseFinished = over;
                gh.postDelayed(() -> {
                    if (RemoteAnims.onCloseStarted == up) {
                        RemoteAnims.onCloseStarted = null;
                        RemoteAnims.pendingCloseFrom = null;
                    }
                    homeUp();
                }, 2500);
                gh.postDelayed(() -> {
                    if (RemoteAnims.onCloseFinished == over) RemoteAnims.onCloseFinished = null;
                    closeOver();
                }, 3000);
                s.goHome();
            } else {
                s.goHome();
                gh.postDelayed(this::homeUp, 160);
                closeOver = true;
            }
        }

        private void homeUp() {
            if (homeUp) return;
            homeUp = true;
            long wait = 0;
            wallView.animate().alpha(0f).setStartDelay(wait).setDuration(170).start();
            ValueAnimator shade = ValueAnimator.ofFloat(1f, 0f);
            shade.setStartDelay(wait);
            shade.setDuration(170);
            shade.addUpdateListener(a -> {
                bar.groundAlpha = (float) a.getAnimatedValue();
                bar.invalidate();
            });
            shade.start();
        }

        private void closeOver() {
            homeUp();
            closeOver = true;
            fadeWhenDone();
        }

        private void fadeWhenDone() {
            if (!landed || !closeOver || fading) return;
            fading = true;
            pic.animate().alpha(0f).setDuration(110).withEndAction(this::removeNow).start();
        }

        private void animateTo(RectF to, float cropTo, long ms, android.animation.TimeInterpolator ip, float[] v0, Runnable end) {
            if (anim != null) anim.cancel();
            RectF from = new RectF(pic.r);
            float cropFrom = pic.cropTop;
            float x0 = from.centerX(), y0 = from.centerY(), x1 = to.centerX(), y1 = to.centerY();
            float sec = ms / 1000f;
            float mx = 0, my = 0;
            if (v0 != null) {
                mx = v0[0] * sec;
                my = v0[1] * sec;
                float dist = (float) Math.hypot(x1 - x0, y1 - y0), m = (float) Math.hypot(mx, my), cap = Math.max(Ui.dp(40), dist * 2.5f);
                if (m > cap) {
                    mx *= cap / m;
                    my *= cap / m;
                }
            }
            final float fmx = mx, fmy = my;
            final boolean hermite = v0 != null;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            anim = a;
            a.setDuration(ms);
            a.setInterpolator(ip);
            a.addUpdateListener(va -> {
                float t = (float) va.getAnimatedValue();
                RectF r;
                if (hermite) {
                    float e = 1f - (float) Math.pow(1f - t, 2.2f);
                    float t2 = t * t, t3 = t2 * t;
                    float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2;
                    float cx = h00 * x0 + h10 * fmx + h01 * x1, cy = h00 * y0 + h10 * fmy + h01 * y1;
                    float w = from.width() + (to.width() - from.width()) * e, hh = from.height() + (to.height() - from.height()) * e;
                    r = new RectF(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
                    pic.cropTop = cropFrom + (cropTo - cropFrom) * e;
                } else {
                    r = GestureService.lerp(from, to, t);
                    pic.cropTop = cropFrom + (cropTo - cropFrom) * t;
                }
                pic.set(r);
                barGround(pic.cropTop);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator x) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator x) {
                    if (anim == a) anim = null;
                    if (!cancelled && end != null) end.run();
                }
            });
            a.start();
        }

        void removeNow() {
            if (card == this) {
                card = null;
                active = false;
            }
            if (anim != null) anim.cancel();
            try {
                wm.removeView(this);
            } catch (Exception ignored) {
            }
        }
    }

    private View buildColumn(Context c) {
        boolean top = "top".equals(s.prefs.sp.getString("lock_column", "center"));
        LinearLayout col = com.bb10.launcher.ui.NotifyColumn.build(c, com.bb10.launcher.ui.NotifyColumn.entries(), top,
                s.statusBarH + Math.round(0.1f * Ui.S()));
        if (GestureService.GDEBUG) Log.d(TAG, "notifying apps beside the app: " + col.getChildCount());
        return col;
    }

    static List<Object[]> none() {
        return new ArrayList<>();
    }

    static View makeBand(Context c, GestureService s, int top) {
        {
            float S = Ui.S();
            int rowH = Math.round(0.062f * S);
            FrameLayout b = new FrameLayout(c);
            b.setBackgroundColor(0xFF000000);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Math.round(0.012f * S);
            row.setPadding(pad, 0, pad, 0);
            String carrier = "";
            try {
                android.telephony.TelephonyManager tm = s.getSystemService(android.telephony.TelephonyManager.class);
                if (tm != null) {
                    if (tm.getSimState() == android.telephony.TelephonyManager.SIM_STATE_ABSENT) carrier = "SIM not detected";
                    else {
                        CharSequence op = tm.getNetworkOperatorName();
                        if (op == null || op.length() == 0) op = tm.getSimOperatorName();
                        carrier = op == null || op.length() == 0 ? "No Service" : op.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            String[] texts = {android.text.format.DateFormat.format("EEEE, MMMM d", System.currentTimeMillis()).toString(), carrier};
            for (int i = 0; i < 2; i++) {
                TextView t = new TextView(c);
                t.setText(texts[i]);
                t.setTextColor(Ui.TEXT);
                t.setTypeface(Ui.regular());
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 0.042f * S);
                t.setIncludeFontPadding(false);
                t.setSingleLine(true);
                row.addView(t, i == 0 ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            b.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH, Gravity.BOTTOM));
            View rule = new View(c);
            rule.setBackgroundColor(0xFF4A4A4A);
            b.addView(rule, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.003f * S)), Gravity.BOTTOM));
            return b;
        }
    }
}
