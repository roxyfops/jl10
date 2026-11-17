package com.bb10.launcher.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bb10.launcher.LauncherActivity;
import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.R;
import com.bb10.launcher.frames.FrameTracker;

import java.util.List;

public class ActiveFramesPage extends FrameLayout {
    private final LauncherActivity act;
    private final PullScroll scroll;
    private final Grid grid;

    private final TextView date;
    private final PullHint pullHint;

    public static final int BAR_DP = 34;

    public static final int GROUND = 0xFF1C1C1C;

    public static Rect iconRect(int w, int h) {
        int s = Math.round(Math.min(w, h) * 0.38f);
        int l = (w - s) / 2, t = (h - s) / 2 - Ui.dp(8);
        return new Rect(l, t, l + s, t + s);
    }

    private final boolean DARK = "dark".equals(LauncherApp.get().prefs.sp.getString(com.bb10.launcher.Prefs.HUB_THEME, "bright"));

    private int barText() {
        return DARK ? Ui.TEXT : 0xFF000000;
    }

    private static final float PULL_THRESHOLD = 0.2f;

    private boolean wasPastHalf;
    public Runnable onHalfChanged;

    public boolean scrollable() {
        return scroll != null && scroll.getChildCount() > 0 && scroll.getChildAt(0).getHeight() > scroll.getHeight();
    }

    private boolean wasScrollable;

    public boolean pastHalf() {
        if (scroll == null || scroll.getChildCount() == 0) return false;
        int max = scroll.getChildAt(0).getHeight() - scroll.getHeight();
        return max > 0 && scroll.getScrollY() > max / 2;
    }

    private class PullScroll extends ScrollView {
        private final int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float downX, downY, offset;
        private boolean pulling, armed;

        PullScroll() {
            super(ActiveFramesPage.this.getContext());
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        private final android.widget.OverScroller flinger = new android.widget.OverScroller(getContext());
        private android.animation.ValueAnimator bounceAnim;
        private float lastV, prevFlingV;
        private boolean overDrag;
        private float overFrom;

        private int maxScroll() {
            return getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getHeight() - getHeight());
        }

        private float bounceMax() {
            return Math.max(Ui.dp(40), grid.fh * 0.5f);
        }

        @Override
        public void fling(int velocityY) {
            if (getChildCount() == 0) return;
            stopBounce();
            lastV = velocityY;
            prevFlingV = velocityY;
            flinger.fling(0, getScrollY(), 0, velocityY, 0, 0, 0, maxScroll());
            postInvalidateOnAnimation();
        }

        @Override
        public void computeScroll() {
            if (flinger.computeScrollOffset()) {
                int y = flinger.getCurrY(), max = maxScroll();
                float v = flinger.getCurrVelocity() * Math.signum(lastV);
                float fast = Math.abs(prevFlingV) > Math.abs(v) ? prevFlingV : v;
                prevFlingV = v;
                if (y != getScrollY()) scrollTo(0, y);
                if (y >= max && lastV > 0 || y <= 0 && lastV < 0) {
                    flinger.forceFinished(true);
                    prevFlingV = 0;
                    if (Math.abs(fast) > Ui.dp(60)) bounce(fast);
                    return;
                }
                postInvalidateOnAnimation();
                return;
            }
            super.computeScroll();
        }

        private final float fullBounceV = android.view.ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity() * 0.4f;

        private void bounce(float v) {
            float from = grid.getTranslationY();
            float k = (float) Math.pow(Math.min(1f, Math.abs(v) / fullBounceV), 0.8);
            float d = bounceMax() * k * (v > 0 ? -1f : 1f);
            if (v > 0 ? from < d : from > d) d = from;
            stopBounce();
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            android.animation.ObjectAnimator out = android.animation.ObjectAnimator.ofFloat(grid, View.TRANSLATION_Y, from, d);
            out.setDuration(Math.round(100 + 50 * k));
            out.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            android.animation.ObjectAnimator back = android.animation.ObjectAnimator.ofFloat(grid, View.TRANSLATION_Y, d, 0f);
            back.setDuration(Math.round(240 + 90 * k));
            back.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            set.playSequentially(out, back);
            set.start();
            bounceSet = set;
        }

        private android.animation.AnimatorSet bounceSet;

        private void stopBounce() {
            if (bounceSet != null) {
                bounceSet.cancel();
                bounceSet = null;
            }
            if (bounceAnim != null) bounceAnim.cancel();
        }

        private int overDir;
        private boolean startedAtTop;

        private boolean overDrag(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                flinger.forceFinished(true);
                stopBounce();
                overDrag = false;
                return false;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (!overDrag) {
                    boolean bottom = maxScroll() > 0 && getScrollY() >= maxScroll() && e.getY() < lastTouchY - 1;
                    boolean top = !startedAtTop && maxScroll() > 0 && getScrollY() <= 0 && e.getY() > lastTouchY + 1;
                    if (bottom || top) {
                        overDrag = true;
                        overDir = bottom ? 1 : -1;
                        overFrom = lastTouchY;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    lastTouchY = e.getY();
                    if (!overDrag) return false;
                }
                float over = (overFrom - e.getY()) * overDir;
                if (over <= 0) {
                    overDrag = false;
                    grid.setTranslationY(0);
                    lastTouchY = e.getY();
                    return false;
                }
                float m = bounceMax();
                grid.setTranslationY(-overDir * m * (1f - (float) Math.exp(-over * 0.6f / m)));
                return true;
            }
            if (overDrag && (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)) {
                overDrag = false;
                float v = upVelocity();
                if (a == MotionEvent.ACTION_UP && v * overDir > Ui.dp(300)) {
                    bounce(v);
                    return true;
                }
                bounceAnim = android.animation.ValueAnimator.ofFloat(grid.getTranslationY(), 0f);
                bounceAnim.setDuration(330);
                bounceAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
                bounceAnim.addUpdateListener(va -> grid.setTranslationY((float) va.getAnimatedValue()));
                bounceAnim.start();
                return true;
            }
            return overDrag;
        }

        private float lastTouchY;
        private android.view.VelocityTracker vt;

        private void track(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && vt != null) vt.clear();
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            vt.addMovement(e);
        }

        private float upVelocity() {
            if (vt == null) return 0f;
            vt.computeCurrentVelocity(1000);
            return -vt.getYVelocity();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            boolean s = scrollable();
            if (s != wasScrollable) {
                wasScrollable = s;
                Runnable cb = onHalfChanged;
                if (cb != null) cb.run();
            }
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            updateClip();
            boolean half = pastHalf();
            if (half != wasPastHalf) {
                wasPastHalf = half;
                Runnable r = onHalfChanged;
                if (r != null) r.run();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            updateClip();
        }

        private final Rect clip = new Rect();
        private android.graphics.Paint peekFadeB;
        private int peekFadeBH;
        private final android.graphics.Paint fade = new android.graphics.Paint();
        private int fadeH;

        private int cut() { return topExt / 2; }

        @Override
        public void draw(android.graphics.Canvas c) {
            int k = getScrollY();
            if (peek) {
                int t = k - peekExt, b = k + getHeight() + peekExt;
                int save = c.saveLayer(0, t, getWidth(), b, null);
                super.draw(c);
                int fb = Math.max(1, Math.round((bottomExt * 2f + peekExt)));
                if (peekFadeB == null || peekFadeBH != fb) {
                    peekFadeBH = fb;
                    peekFadeB = new android.graphics.Paint();
                    peekFadeB.setShader(new android.graphics.LinearGradient(0, 0, 0, fb, 0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
                    peekFadeB.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
                }
                c.save();
                c.translate(0, b - fb);
                c.drawRect(0, 0, getWidth(), fb, peekFadeB);
                c.restore();
                c.restoreToCount(save);
                return;
            }
            if (topExt <= 0 || k <= 0) {
                super.draw(c);
                return;
            }
            int h = Math.max(1, Math.round(0.016f * Ui.S()));
            if (fadeH != h) {
                fadeH = h;
                fade.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF000000, 0x00000000,
                        android.graphics.Shader.TileMode.CLAMP));
                fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            }
            int save = c.saveLayer(0, k, getWidth(), k + getHeight(), null);
            super.draw(c);
            c.translate(0, k + cut());
            c.drawRect(0, 0, getWidth(), h, fade);
            c.restoreToCount(save);
        }

        void forPicture(boolean on) {
            if (!on) {
                updateClip();
                return;
            }
            Rect r = new Rect(clip);
            r.offset(0, getScrollY());
            setClipBounds(r);
        }

        void updateClip() {
            int k = Math.max(0, getScrollY());
            int top = peek ? -peekExt : Math.max(cut(), topExt - k), bottom = peek ? -peekExt : 0;
            clip.set(0, top, getWidth(), getHeight() - bottom);
            setClipBounds(clip);
        }

        private boolean canPull() {
            return getScrollY() == 0 && grid.getChildCount() > 0;
        }

        private boolean startsPull(MotionEvent e) {
            float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
            return startedAtTop && canPull() && dy > slop && dy > dx * 1.5f;
        }

        private void beginPull(MotionEvent e) {
            pulling = true;
            downY = e.getY() - slop;
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP && maxScroll() > 0) {
                if (getScrollY() >= maxScroll() && downY - e.getY() > slop && upVelocity() > Ui.dp(300)) bounce(upVelocity());
                else if (!startedAtTop && getScrollY() <= 0 && e.getY() - downY > slop && upVelocity() < -Ui.dp(300)) bounce(upVelocity());
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startedAtTop = getScrollY() == 0 && flinger.isFinished();
                    downX = e.getX();
                    downY = e.getY();
                    lastTouchY = e.getY();
                    pulling = false;
                    overDrag(e);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!pulling && startsPull(e)) {
                        beginPull(e);
                        return true;
                    }
                    break;
            }
            return pulling || super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!pulling) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startedAtTop = getScrollY() == 0 && flinger.isFinished();
                    downX = e.getX();
                    downY = e.getY();
                    lastTouchY = e.getY();
                    overDrag(e);
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && startsPull(e)) {
                    beginPull(e);
                }
                if (!pulling) {
                    track(e);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !overDrag && maxScroll() > 0) {
                        float v = upVelocity();
                        if (getScrollY() >= maxScroll() && v > Ui.dp(300)) bounce(v);
                        else if (!startedAtTop && getScrollY() <= 0 && v < -Ui.dp(300)) bounce(v);
                    }
                    boolean wasOver = overDrag;
                    if (e.getActionMasked() != MotionEvent.ACTION_DOWN && overDrag(e)) {
                        if (e.getActionMasked() != MotionEvent.ACTION_MOVE || !wasOver) {
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            super.onTouchEvent(c);
                            c.recycle();
                        }
                        return true;
                    }
                    return super.onTouchEvent(e);
                }
            }
            float threshold = Ui.s(PULL_THRESHOLD);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float raw = Math.max(0, e.getY() - downY);
                    offset = raw * 0.5f;
                    if (offset > threshold) offset = threshold + (offset - threshold) * 0.3f;
                    grid.setTranslationY(offset);
                    float p = Math.min(1f, offset / threshold);
                    if (p >= 1f && !armed) {
                        armed = true;
                        Ui.tick(getContext());
                    } else if (p < 1f) {
                        armed = false;
                    }
                    pullHint.setProgress(p);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    pulling = false;
                    if (offset >= threshold) closeAllAnimated();
                    else springBack();
                    armed = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    armed = false;
                    springBack();
                    return true;
            }
            return true;
        }

        private void springBack() {
            offset = 0;
            grid.animate().translationY(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            pullHint.animateTo(0f);
        }
    }

    private void closeAllAnimated() {
        int n = grid.getChildCount();
        float drop = getHeight();
        boolean anyLocked = false;
        for (int i = 0; i < n; i++) {
            View v = grid.getChildAt(i);
            if (v instanceof FrameView && ((FrameView) v).f.locked) {
                anyLocked = true;
                continue;
            }
            v.animate().translationY(drop).alpha(0f).setStartDelay(i * 25L).setDuration(260)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        }
        pullHint.animateTo(0f);
        if (anyLocked) grid.animate().translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        postDelayed(() -> {
            grid.setTranslationY(0);
            LauncherApp.get().frames.closeAll();
            for (int i = 0; i < grid.getChildCount(); i++) {
                View v = grid.getChildAt(i);
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationY(0);
            }
        }, 270 + n * 25L);
    }

    private class PullHint extends View {
        private float progress;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable x;
        private android.animation.ValueAnimator anim;

        PullHint() {
            super(ActiveFramesPage.this.getContext());
            x = Ui.glyph(getContext(), R.drawable.ic_close, Ui.TEXT);
            tp.setColor(Ui.TEXT);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), 0xCC000000);
        }

        void setProgress(float v) {
            if (anim != null) anim.cancel();
            progress = v;
            invalidate();
        }

        void animateTo(float v) {
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(progress, v);
            anim.setDuration(200);
            anim.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (progress <= 0.01f) return;
            float S = Ui.S();
            float shown = Math.min(Ui.s(PULL_THRESHOLD), grid.getTranslationY());
            float cx = getWidth() / 2f, r = 0.032f * S, cy = Math.max(r + Ui.dp(4), shown / 2f - 0.012f * S);
            int alpha = Math.round(255 * Math.min(1f, progress * 1.4f));
            boolean ready = progress >= 1f;

            p.setStyle(Paint.Style.FILL);
            p.setColor(ready ? 0xFFD7262B : 0x99000000);
            p.setAlpha(alpha);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.005f * S);
            p.setColor(Ui.homeFg());
            p.setAlpha(alpha);
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, 360 * progress, false, p);
            int g = Math.round(r * 1.1f);
            x.setAlpha(alpha);
            x.setBounds(Math.round(cx - g / 2f), Math.round(cy - g / 2f), Math.round(cx + g / 2f), Math.round(cy + g / 2f));
            x.draw(c);

            tp.setTypeface(Ui.regular());
            tp.setTextSize(0.03f * S);
            tp.setColor(Ui.homeFg());
            tp.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), Ui.homeShadow(0xCC000000));
            tp.setAlpha(alpha);
            c.drawText(ready ? "Release to close all" : "Pull down to close all", cx, cy + r + 0.04f * S, tp);
        }
    }

    public static final Rect SLOT0 = new Rect();

    private static final long LIVE_DELAY = 2000, LIVE_FADE = 300;
    private static long liveRevealAt;
    private static String liveRevealPkg;

    public void restartLiveReveal() {
        liveRevealPkg = LauncherApp.get().frames.lastForeground();
        if (liveRevealPkg != null) LauncherApp.get().frames.markLeft(liveRevealPkg);
        liveRevealAt = android.os.SystemClock.uptimeMillis();
        invalidateFrames();
    }

    private static float liveAlpha(String pkg) {
        if (liveRevealAt == 0 || pkg == null || !pkg.equals(liveRevealPkg)) return 1f;
        long since = android.os.SystemClock.uptimeMillis() - liveRevealAt - LIVE_DELAY;
        if (since <= 0) return 0f;
        return Math.min(1f, since / (float) LIVE_FADE);
    }

    private static float smooth(float k) {
        k = Math.max(0f, Math.min(1f, k));
        return k * k * (3f - 2f * k);
    }
    public void scrollToTop() {
        int row = 0;
        com.bb10.launcher.frames.FrameTracker tr = LauncherApp.get().frames;
        if (tr.arranged()) {
            int idx = indexOfFrame(tr.lastForeground());
            row = Math.max(0, idx / 2 - 1);
        }
        scroll.scrollTo(0, row * (grid.fh + grid.gap));
        scrollY = scroll.getScrollY();
    }

    private static volatile int scrollY;
    private static volatile int slotGap;

    private int sideL = Ui.dp(5), sideR = Ui.dp(5), topExt, bottomExt;

    private static int indexOfFrame(String pkg) {
        if (pkg == null) return -1;
        List<FrameTracker.Frame> l = LauncherApp.get().frames.list();
        for (int i = 0; i < l.size(); i++) if (pkg.equals(l.get(i).pkg)) return i;
        return -1;
    }

    private static final java.util.WeakHashMap<android.graphics.drawable.Drawable, android.graphics.Bitmap> shadows = new java.util.WeakHashMap<>();

    static int shadowPad() {
        return Ui.dp(3);
    }

    static android.graphics.Bitmap iconShadow(android.graphics.drawable.Drawable d, int size) {
        android.graphics.Bitmap b = shadows.get(d);
        int pad = shadowPad(), full = size + 2 * pad;
        if (b != null && b.getWidth() == full) return b;
        try {
            android.graphics.Bitmap shape = android.graphics.Bitmap.createBitmap(full, full, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(shape);
            Rect keep = d.copyBounds();
            d.setBounds(pad, pad, pad + size, pad + size);
            d.draw(sc);
            d.setBounds(keep);
            Paint blur = new Paint();
            blur.setMaskFilter(new android.graphics.BlurMaskFilter(Ui.dp(2), android.graphics.BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            android.graphics.Bitmap mask = shape.extractAlpha(blur, off);
            shape.recycle();
            b = android.graphics.Bitmap.createBitmap(full, full, android.graphics.Bitmap.Config.ARGB_8888);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0x66000000);
            new Canvas(b).drawBitmap(mask, off[0], off[1], ink);
            mask.recycle();
            shadows.put(d, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String K_SLOT0 = "frames_slot0";

    private static void keepSlot0() {
        try {
            String v = SLOT0.flattenToString() + ";" + slotGap;
            android.content.SharedPreferences sp = com.bb10.launcher.LauncherApp.get().prefs.sp;
            if (!v.equals(sp.getString(K_SLOT0, null))) sp.edit().putString(K_SLOT0, v).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void loadSlot0() {
        try {
            String v = com.bb10.launcher.LauncherApp.get().prefs.sp.getString(K_SLOT0, null);
            if (v == null) return;
            String[] a = v.split(";");
            Rect r = Rect.unflattenFromString(a[0]);
            if (r == null || r.width() <= 0) return;
            SLOT0.set(r);
            if (a.length > 1) slotGap = Integer.parseInt(a[1]);
        } catch (Throwable ignored) {
        }
    }

    public static Rect slotFor(String pkg) {
        Rect r = new Rect(SLOT0);
        if (r.width() <= 0) {
            loadSlot0();
            r.set(SLOT0);
        }
        if (r.width() <= 0) return r;
        int idx = indexOfFrame(pkg);
        if (idx <= 0) return r;
        r.offset((idx % 2) * (r.width() + slotGap), (idx / 2) * (r.height() + slotGap) - scrollY);
        return r;
    }

    private boolean peek;
    private int peekExt;
    private boolean clippedBefore = true;

    public void setPeekScale(float scale) {
        boolean on = scale < 0.999f;
        int ext = on ? Math.round(getHeight() * (1f / scale - 1f) / 2f) : 0;
        if (peek == on && peekExt == ext) return;
        if (peek != on) {
            scroll.setVerticalFadingEdgeEnabled(!on);
            if (on) {
                clippedBefore = getClipChildren();
                setClipChildren(false);
            } else setClipChildren(clippedBefore);
        }
        peek = on;
        peekExt = ext;
        scroll.updateClip();
        scroll.invalidate();
    }

    public void forPicture(boolean on) {
        scroll.forPicture(on);
    }

    public void smoothScrollToTop() {
        scroll.smoothScrollTo(0, 0);
    }

    public boolean isAtTop() {
        return scroll.getScrollY() == 0;
    }

    public View frameViewAt(int i) {
        return i < grid.getChildCount() ? grid.getChildAt(i) : null;
    }

    public View frameViewFor(String pkg) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v instanceof FrameView && ((FrameView) v).f.pkg.equals(pkg)) return v;
        }
        return null;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private float pageFade = 1f;

    public void setPageFade(float a) {
        if (pageFade == a) return;
        pageFade = a;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (pageFade >= 1f) {
            super.dispatchDraw(c);
            return;
        }
        if (pageFade <= 0f) return;
        int save = c.saveLayerAlpha(0, -topExt, getWidth(), getHeight() + bottomExt, Math.round(pageFade * 255));
        super.dispatchDraw(c);
        c.restoreToCount(save);
    }

    public ActiveFramesPage(LauncherActivity a) {
        super(a);
        act = a;
        pullHint = new PullHint();
        addView(pullHint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        scroll = new PullScroll();
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);

        grid = new Grid();
        scroll.addView(grid);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClipChildren(false);
        setClipToPadding(false);

        date = new TextView(a);
        date.setTypeface(Ui.light());
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 44);
        date.setTextColor(0xD9DCEBF2);
        date.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x99000000);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        date.setIncludeFontPadding(false);
        LayoutParams dlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        dlp.topMargin = Ui.dp(34);
        addView(date, dlp);
    }

    public void applyHomeStyle() {
        date.setTextColor(Ui.homeDark ? 0xE6141414 : 0xD9DCEBF2);
        date.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), Ui.homeDark ? 0x80FFFFFF : 0x99000000);
    }

    public void updateDate() {
        date.setText(android.text.format.DateFormat.format("EEE d MMM", new java.util.Date()));
        invalidateFrames();
    }

    public void invalidateFrames() {
        for (int i = 0; i < grid.getChildCount(); i++) grid.getChildAt(i).invalidate();
    }

    private static String openingPkg;

    public void showOpening(String pkg) {
        openingPkg = pkg;
        invalidateFrames();
    }

    public void clearOpening() {
        if (openingPkg == null) return;
        openingPkg = null;
        invalidateFrames();
    }

    boolean needsTicking;
    private boolean secondTicks;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            secondTicks = needsTicking;
            needsTicking = false;
            invalidateFrames();
            long now = System.currentTimeMillis();
            postDelayed(this, secondTicks ? 1000 : 60_000 - now % 60_000 + 20);
        }
    };

    void wantSecondTicks() {
        needsTicking = true;
        if (secondTicks || getWindowVisibility() != VISIBLE) return;
        secondTicks = true;
        removeCallbacks(ticker);
        postDelayed(ticker, 1000);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(ticker);
        secondTicks = false;
        if (visibility == VISIBLE) post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    public void rebuild() {
        grid.removeAllViews();
        refresh();
    }

    public void refresh() {
        updateDate();
        List<FrameTracker.Frame> frames = LauncherApp.get().frames.list();
        date.setVisibility(frames.isEmpty() ? VISIBLE : GONE);
        if (sameFrames(frames)) {
            invalidateFrames();
            return;
        }
        View focused = grid.findFocus();
        String focusPkg = focused instanceof FrameView ? ((FrameView) focused).f.pkg : null;
        java.util.Map<String, int[]> was = new java.util.HashMap<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v instanceof FrameView && v.isLaidOut()) was.put(((FrameView) v).f.pkg, new int[]{v.getLeft(), v.getTop()});
        }
        boolean closed = frames.size() < was.size();
        for (FrameTracker.Frame f : frames) if (!was.containsKey(f.pkg)) closed = false;
        grid.removeAllViews();
        for (FrameTracker.Frame f : frames) {
            FrameView fv = new FrameView(f);
            grid.addView(fv);
            if (f.pkg.equals(focusPkg)) fv.post(fv::requestFocus);
        }
        if (closed && !frames.isEmpty()) slideIntoPlace(was);
    }

    public static void drawPlain(View v, android.graphics.Canvas c) {
        if (!(v instanceof FrameView)) {
            v.draw(c);
            return;
        }
        FrameView f = (FrameView) v;
        f.plain = true;
        try {
            f.draw(c);
        } finally {
            f.plain = false;
        }
    }

    public static boolean showsIconOnly(View v) {
        if (!(v instanceof FrameView)) return false;
        FrameView f = (FrameView) v;
        if (f.f.pkg.equals(openingPkg)) return true;
        return !f.live && (f.f.thumb == null || f.f.thumb.isRecycled());
    }

    public static boolean showsLiveInfo(View v) {
        return v instanceof FrameView && ((FrameView) v).live;
    }

    private boolean sameFrames(List<FrameTracker.Frame> frames) {
        if (grid.dragged != null || grid.getChildCount() != frames.size() || frames.isEmpty()) return false;
        boolean arrange = LauncherApp.get().prefs.bool(com.bb10.launcher.Prefs.FRAMES_ARRANGE, false);
        for (int i = 0; i < frames.size(); i++) {
            View v = grid.getChildAt(i);
            if (!(v instanceof FrameView) || ((FrameView) v).f != frames.get(i)) return false;
            if (((FrameView) v).arrange != arrange) return false;
        }
        return true;
    }

    private void slideIntoPlace(java.util.Map<String, int[]> was) {
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View v = grid.getChildAt(i);
                    if (!(v instanceof FrameView)) continue;
                    int[] o = was.get(((FrameView) v).f.pkg);
                    if (o == null || (o[0] == v.getLeft() && o[1] == v.getTop())) continue;
                    v.setTranslationX(o[0] - v.getLeft());
                    v.setTranslationY(o[1] - v.getTop());
                    v.animate().translationX(0).translationY(0).setStartDelay(i * 25L).setDuration(260)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                }
                return true;
            }
        });
    }

    public void focusFirst() {
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int[] ins = act.framesInsets();
        if (ins[0] != sideL || ins[1] != sideR || ins[2] != topExt || ins[3] != bottomExt) {
            sideL = ins[0];
            sideR = ins[1];
            topExt = ins[2];
            bottomExt = ins[3];
            LayoutParams lp = (LayoutParams) scroll.getLayoutParams();
            lp.topMargin = -topExt;
            lp.bottomMargin = -bottomExt;
            scroll.setLayoutParams(lp);
            grid.requestLayout();
            scroll.updateClip();
        }
        if (MeasureSpec.getMode(hms) == MeasureSpec.EXACTLY) {
            int h = MeasureSpec.getSize(hms);
            if (h > 0 && h != grid.viewportH) {
                grid.viewportH = h;
                grid.requestLayout();
            }
        }
        super.onMeasure(wms, hms);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (h != grid.viewportH) {
            grid.viewportH = h;
            grid.post(grid::requestLayout);
        }
    }

    private class Grid extends ViewGroup {
        int viewportH;
        int fw, fh, gap = Math.round(0.025f * Ui.S());

        Grid() {
            super(ActiveFramesPage.this.getContext());
            setClipChildren(false);
        }

        FrameView dragged;
        private final java.util.List<View> order = new java.util.ArrayList<>();
        private int fromIndex;
        private float offX, offY, startRawX, startRawY;
        private final int[] loc = new int[2];

        void startDrag(FrameView v, float rawX, float rawY) {
            if (dragged != null || getChildCount() < 2) return;
            dragged = v;
            fromIndex = indexOfChild(v);
            startRawX = rawX;
            startRawY = rawY;
            order.clear();
            for (int i = 0; i < getChildCount(); i++) order.add(getChildAt(i));
            getLocationOnScreen(loc);
            offX = rawX - loc[0] - v.getLeft();
            offY = rawY - loc[1] - v.getTop();
            v.setPressed(false);
            v.setTranslationZ(Ui.dp(10));
            v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
            v.getParent().requestDisallowInterceptTouchEvent(true);
            Ui.tick(getContext());
        }

        int cols = 2;

        private int slotX(int i) { return sideL + (i % cols) * (fw + gap); }

        private int top0() { return gap / 2; }

        private int slotY(int i) { return topExt + top0() + (i / cols) * (fh + gap); }

        void drag(MotionEvent e) {
            FrameView v = dragged;
            if (v == null) return;
            getLocationOnScreen(loc);
            float gx = e.getRawX() - loc[0], gy = e.getRawY() - loc[1];
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    v.setTranslationX(gx - offX - v.getLeft());
                    v.setTranslationY(gy - offY - v.getTop());
                    float cx = gx - offX + fw / 2f, cy = gy - offY + fh / 2f;
                    int col = Math.max(0, Math.min(cols - 1, (int) ((cx - sideL + gap / 2f) / (fw + gap))));
                    int row = Math.max(0, (int) ((cy - topExt - top0() + gap / 2f) / (fh + gap)));
                    int slot = Math.max(0, Math.min(order.size() - 1, row * cols + col));
                    if (slot != order.indexOf(v)) {
                        order.remove(v);
                        order.add(slot, v);
                        for (int i = 0; i < order.size(); i++) {
                            View o = order.get(i);
                            if (o == v) continue;
                            o.animate().translationX(slotX(i) - o.getLeft()).translationY(slotY(i) - o.getTop()).setDuration(160).start();
                        }
                    }
                    float sy = e.getRawY();
                    int[] sl = new int[2];
                    scroll.getLocationOnScreen(sl);
                    float edge = fh * 0.35f;
                    if (sy < sl[1] + edge) scroll.scrollBy(0, -Ui.dp(8));
                    else if (sy > sl[1] + scroll.getHeight() - edge) scroll.scrollBy(0, Ui.dp(8));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int to = order.indexOf(v);
                    dragged = null;
                    boolean stayed = to == fromIndex && e.getActionMasked() == MotionEvent.ACTION_UP
                            && Math.hypot(e.getRawX() - startRawX, e.getRawY() - startRawY) < Ui.dp(12);
                    if (stayed) v.toggleLock();
                    v.animate().translationX(slotX(to) - v.getLeft()).translationY(slotY(to) - v.getTop())
                            .scaleX(1f).scaleY(1f).setDuration(160).withEndAction(() -> {
                                v.setTranslationZ(0);
                                if (to != fromIndex) LauncherApp.get().frames.move(fromIndex, to);
                                else for (int i = 0; i < getChildCount(); i++) {
                                    getChildAt(i).setTranslationX(0);
                                    getChildAt(i).setTranslationY(0);
                                }
                            }).start();
                    break;
                }
            }
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            cols = getResources().getDisplayMetrics().widthPixels > 900 ? 3 : 2;
            fw = (w - sideL - sideR - (cols - 1) * gap) / cols;
            int n = getChildCount(), rows = (n + cols - 1) / cols;
            int peek = rows > 2 && viewportH > 0 ? Math.round(viewportH * 0.075f) : 0;
            fh = viewportH > 0 ? (viewportH - top0() - 2 * gap - peek) / 2 : fw;
            scroll.setFadingEdgeLength(peek > 0 ? peek + gap : 0);
            int h = Math.max(viewportH + topExt + bottomExt, topExt + top0() + rows * fh + rows * gap + bottomExt);
            for (int i = 0; i < n; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int[] loc = new int[2];
            ActiveFramesPage.this.getLocationOnScreen(loc);
            SLOT0.set(sideL, loc[1] + top0(), sideL + fw, loc[1] + top0() + fh);
            slotGap = gap;
            if (!peek && fw > 0 && fh > 0) keepSlot0();
            for (int i = 0; i < getChildCount(); i++) {
                int x = slotX(i), y = slotY(i);
                getChildAt(i).layout(x, y, x + fw, y + fh);
            }
        }
    }

    static void lockFeedback(android.content.Context c, boolean locked) {
        android.os.Vibrator v = c.getSystemService(android.os.Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(android.os.VibrationEffect.createPredefined(locked
                ? android.os.VibrationEffect.EFFECT_HEAVY_CLICK : android.os.VibrationEffect.EFFECT_CLICK));
    }

    private class FrameView extends View {
        final FrameTracker.Frame f;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Drawable icon, close;
        final String label;
        final RectF closeRect = new RectF();
        boolean downClose;
        private int capH;
        boolean arrange;
        boolean live;
        boolean plain;
        private boolean opened;

        FrameView(FrameTracker.Frame f) {
            super(ActiveFramesPage.this.getContext());
            this.f = f;
            setFocusable(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            icon = act.iconForPackage(f.pkg);
            label = LauncherApp.get().apps.labelForPackage(f.pkg);
            close = Ui.glyph(getContext(), R.drawable.ic_close, DARK ? 0xFF9A9A9A : 0xFF7A7A7A);
            tp.setColor(barText());
            tp.setTypeface(Ui.regular());
            tp.setTextSize(Ui.sp(15));
            setContentDescription(label);
            setOnClickListener(v -> {
                if (downClose) closeMe();
                else if (!handleControlTap()) {
                    opened = true;
                    invalidate();
                    act.openFrame(f.pkg, this);
                }
            });
            arrange = LauncherApp.get().prefs.bool(com.bb10.launcher.Prefs.FRAMES_ARRANGE, false);
            setOnLongClickListener(v -> {
                if (arrange) grid.startDrag(this, lastRawX, lastRawY);
                else {
                    toggleLock();
                    lockedThisPress = true;
                }
                return true;
            });
        }

        private boolean lockedThisPress;

        void toggleLock() {
            boolean locked = LauncherApp.get().frames.toggleLock(f.pkg);
            lockFeedback(getContext(), locked);
            invalidate();
        }

        private void undoLockThisPress() {
            if (!lockedThisPress) return;
            lockedThisPress = false;
            LauncherApp.get().frames.toggleLock(f.pkg);
            invalidate();
        }

        void closeMe() {
            animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(160)
                    .withEndAction(() -> LauncherApp.get().frames.close(f.pkg)).start();
        }

        float lastRawX, lastRawY;
        private float downRawX, downRawY;
        private boolean swiped;
        private final int tapSlop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            lastRawX = e.getRawX();
            lastRawY = e.getRawY();
            if (grid.dragged == this) {
                grid.drag(e);
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    swiped = false;
                    lockedThisPress = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!swiped && Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) > tapSlop) {
                        swiped = true;
                        undoLockThisPress();
                        cancelLongPress();
                        MotionEvent c = MotionEvent.obtain(e);
                        c.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(c);
                        c.recycle();
                    }
                    if (swiped) return true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) undoLockThisPress();
                    lockedThisPress = false;
                    if (swiped) {
                        swiped = false;
                        return true;
                    }
                    break;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downClose = closeRect.contains(e.getX(), e.getY());
                downControl = -1;
                if (controls != null) {
                    for (int i = 0; i < controls.length; i++) {
                        if (controls[i] != null && controls[i].contains(e.getX(), e.getY())) downControl = i;
                    }
                }
            }
            return super.onTouchEvent(e);
        }

        RectF[] controls, controlRects;
        int downControl = -1;
        private Bitmap artFor;
        private Drawable artDrawable;

        private boolean drawLive(Canvas c, int w, int h) {
            LauncherApp app = LauncherApp.get();
            if (!app.prefs.bool(com.bb10.launcher.Prefs.FRAMES_LIVE, true)) return false;
            com.bb10.launcher.frames.LiveFrames live = app.live;
            android.media.session.MediaController mc = live.mediaFor(f.pkg);
            if (mc != null) {
                drawMusic(c, w, h, live.metadata(mc), live.playing(mc));
                return true;
            }
            android.media.MediaMetadata last = live.lastPlayed(f.pkg);
            if (last != null) {
                drawMusic(c, w, h, last, false);
                return true;
            }
            if (live.isCalendarApp(f.pkg) && live.canReadCalendar()) {
                drawCalendar(c, w, h, live.upcoming());
                return true;
            }
            if ("com.bb10.clock".equals(f.pkg)) {
                drawBbClock(c, w, h, live.nextAlarm());
                return true;
            }
            if (live.isClockApp(f.pkg)) {
                drawClock(c, w, h, live.nextAlarm());
                return true;
            }
            android.app.Notification n = app.hub.ongoing(f.pkg);
            if (n != null) {
                if (android.app.Notification.CATEGORY_NAVIGATION.equals(n.category)) drawNavigation(c, w, h, n);
                else drawOngoing(c, w, h, n);
                return true;
            }
            if (live.isNotesApp(f.pkg)) {
                drawNotes(c, w, h, live.notes());
                return true;
            }
            if (live.isTasksApp(f.pkg)) {
                drawTasks(c, w, h, live.tasks());
                return true;
            }
            if (live.isSettingsApp(f.pkg)) {
                com.bb10.launcher.frames.LiveFrames.DeviceStats ds = live.deviceStats();
                if (ds != null) {
                    drawDevice(c, w, h, ds);
                    return true;
                }
            }
            if (live.isWeatherApp(f.pkg)) {
                com.bb10.launcher.frames.LiveFrames.WeatherNow wn = live.weather();
                if (wn != null) {
                    drawWeather(c, w, h, wn);
                    return true;
                }
            }
            if (live.isPhoneApp(f.pkg) && live.canReadCalls()) {
                drawCalls(c, w, h, live.recentCalls());
                return true;
            }
            if (live.isContactsApp(f.pkg) && live.canReadContacts()) {
                com.bb10.launcher.frames.LiveFrames.OpenContact oc = "com.bb10.contacts".equals(f.pkg) ? live.openContact() : null;
                if (oc != null) {
                    drawContact(c, w, h, oc);
                    return true;
                }
                java.util.List<com.bb10.launcher.frames.LiveFrames.Person> ppl = live.framePeople();
                if (!ppl.isEmpty()) {
                    drawPeople(c, w, h, ppl);
                    return true;
                }
            }
            return false;
        }

        private Bitmap contactFor;
        private Drawable contactDrawable;

        private void drawContact(Canvas c, int w, int h, com.bb10.launcher.frames.LiveFrames.OpenContact oc) {
            float S = Ui.S(), pad = 0.022f * S;
            if (oc.photo != contactFor) {
                contactFor = oc.photo;
                contactDrawable = oc.photo == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), oc.photo);
            }
            if (contactDrawable != null) {
                cover(c, w, h, contactDrawable);
            } else {
                p.setColor(0xFF3A3A3A);
                c.drawRect(0, 0, w, h, p);
                StringBuilder ini = new StringBuilder();
                for (String wd : oc.name.trim().split("\\s+"))
                    if (!wd.isEmpty() && ini.length() < 2) ini.appendCodePoint(Character.toUpperCase(wd.codePointAt(0)));
                TextPaint initT = text(0.11f, 0xFFB0B0B0, Ui.light());
                initT.setTextAlign(Paint.Align.CENTER);
                float area = h - Ui.dp(BAR_DP);
                c.drawText(ini.toString(), w / 2f, area * 0.45f - (initT.ascent() + initT.descent()) / 2f, initT);
            }
            gradient(c, w, h, false);
            TextPaint nameT = text(0.04f, Ui.TEXT, Ui.bold()), jobT = text(0.03f, 0xCCFFFFFF, Ui.regular());
            float bottom = h - Ui.dp(BAR_DP) - pad * 0.6f;
            float y = bottom - (nameT.descent() - nameT.ascent()) - (oc.job.isEmpty() ? 0 : (jobT.descent() - jobT.ascent()));
            y = line(c, oc.name, nameT, pad, y, w - 2 * pad);
            if (!oc.job.isEmpty()) line(c, oc.job, jobT, pad, y, w - 2 * pad);
        }

        private void drawPeople(Canvas c, int w, int h, java.util.List<com.bb10.launcher.frames.LiveFrames.Person> ppl) {
            float S = Ui.S();
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            int n = Math.min(4, ppl.size());
            int cols = n == 1 ? 1 : 2, rows = n <= 2 ? 1 : 2;
            float gap = Math.max(1, Ui.dp(1));
            float area = h - Ui.dp(BAR_DP);
            float cw = (w - gap * (cols - 1)) / cols, ch = (area - gap * (rows - 1)) / rows;
            TextPaint nameT = text(0.026f, Ui.TEXT, Ui.regular());
            TextPaint initT = text(0.06f, 0xFFB0B0B0, Ui.light());
            initT.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                com.bb10.launcher.frames.LiveFrames.Person pe = ppl.get(i);
                int col = n == 3 && i == 0 ? 0 : (n == 3 ? (i - 1) % 2 : i % cols);
                int row = n == 3 ? (i == 0 ? 0 : 1) : i / cols;
                float x0 = col * (cw + gap), y0 = row * (ch + gap);
                float x1 = n == 3 && i == 0 ? w : x0 + cw, y1 = y0 + ch;
                c.save();
                c.clipRect(x0, y0, x1, y1);
                if (pe.photo != null) {
                    float bw = pe.photo.getWidth(), bh = pe.photo.getHeight();
                    float s = Math.max((x1 - x0) / bw, (y1 - y0) / bh);
                    float dw = bw * s, dh = bh * s;
                    float dx = x0 + ((x1 - x0) - dw) / 2f, dy = y0 + ((y1 - y0) - dh) / 2f;
                    tileRect.set(dx, dy, dx + dw, dy + dh);
                    c.drawBitmap(pe.photo, null, tileRect, bitmapPaint);
                } else {
                    p.setColor(0xFF3A3A3A);
                    c.drawRect(x0, y0, x1, y1, p);
                    StringBuilder ini = new StringBuilder();
                    for (String wd : pe.name.trim().split("\\s+")) {
                        if (!wd.isEmpty() && ini.length() < 2) ini.appendCodePoint(Character.toUpperCase(wd.codePointAt(0)));
                    }
                    c.drawText(ini.toString(), (x0 + x1) / 2f, (y0 + y1) / 2f - (initT.ascent() + initT.descent()) / 2f, initT);
                }
                float lh = (nameT.descent() - nameT.ascent()) + 0.012f * S;
                if (shade == null) {
                    shadeShader = new android.graphics.LinearGradient(0, 0, 0, 1, 0x00000000, 0xCC000000, android.graphics.Shader.TileMode.CLAMP);
                    shade = new Paint();
                    shade.setShader(shadeShader);
                }
                shadeMatrix.setScale(1f, lh * 1.6f);
                shadeMatrix.postTranslate(0, y1 - lh * 1.6f);
                shadeShader.setLocalMatrix(shadeMatrix);
                c.drawRect(x0, y1 - lh * 1.6f, x1, y1, shade);
                String nm = TextUtils.ellipsize(pe.name, nameT, (x1 - x0) - 0.02f * S, TextUtils.TruncateAt.END).toString();
                c.drawText(nm, x0 + 0.01f * S, y1 - 0.008f * S - nameT.descent(), nameT);
                c.restore();
            }
        }

        private void drawCalls(Canvas c, int w, int h, java.util.List<com.bb10.launcher.frames.LiveFrames.CallEntry> calls) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            int missed = 0;
            for (com.bb10.launcher.frames.LiveFrames.CallEntry e : calls)
                if (e.isNew && e.type == android.provider.CallLog.Calls.MISSED_TYPE) missed++;
            float y = pad;
            TextPaint head = missed > 0 ? text(0.031f, 0xFFE35B5B, Ui.bold()) : text(0.029f, Ui.BLUE, Ui.bold());
            y = line(c, missed > 0 ? missed + (missed == 1 ? " Missed Call" : " Missed Calls") : "CALL LOG", head, pad, y, w - 2 * pad);
            y += 0.01f * S;
            TextPaint nameT = text(0.032f, Ui.TEXT, Ui.regular()), timeT = text(0.026f, Ui.TEXT2, Ui.regular());
            timeT.setTextAlign(Paint.Align.RIGHT);
            float rowH = Math.max(nameT.descent() - nameT.ascent(), 0.042f * S) + 0.014f * S;
            float limit = h - Ui.dp(BAR_DP) - pad;
            float is = 0.036f * S;
            for (com.bb10.launcher.frames.LiveFrames.CallEntry e : calls) {
                if (y + rowH > limit) break;
                boolean miss = e.type == android.provider.CallLog.Calls.MISSED_TYPE || e.type == android.provider.CallLog.Calls.REJECTED_TYPE;
                int res = miss ? R.drawable.ph_missed : e.type == android.provider.CallLog.Calls.OUTGOING_TYPE ? R.drawable.ph_out : R.drawable.ph_in;
                Drawable ic = drawable(res);
                float cy = y + rowH / 2f;
                if (ic != null) {
                    ic.setBounds(Math.round(pad), Math.round(cy - is / 2), Math.round(pad + is), Math.round(cy + is / 2));
                    ic.draw(c);
                }
                String when = e.when;

                float tw = timeT.measureText(when);
                c.drawText(when, w - pad, cy - (timeT.ascent() + timeT.descent()) / 2f, timeT);
                String who = e.who;

                nameT.setColor(miss ? 0xFFE35B5B : Ui.TEXT);
                float nx = pad + is + 0.015f * S;
                String fit = TextUtils.ellipsize(who, nameT, w - pad - tw - 0.015f * S - nx, TextUtils.TruncateAt.END).toString();
                c.drawText(fit, nx, cy - (nameT.ascent() + nameT.descent()) / 2f, nameT);
                y += rowH;
            }
            if (calls.isEmpty()) line(c, "No recent calls", text(0.031f, Ui.TEXT2, Ui.regular()), pad, y, w - 2 * pad);
        }

        private final android.util.LongSparseArray<TextPaint> paints = new android.util.LongSparseArray<>();

        private TextPaint text(float sizeFrac, int color, android.graphics.Typeface tf) {
            long key = ((long) Float.floatToIntBits(sizeFrac) << 32) ^ (System.identityHashCode(tf) & 0xFFFFFFFFL);
            TextPaint t = paints.get(key);
            if (t == null) {
                t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                t.setTextSize(sizeFrac * Ui.S());
                t.setTypeface(tf);
                paints.put(key, t);
            }
            t.setColor(color);
            t.setTextAlign(Paint.Align.LEFT);
            return t;
        }

        private final android.util.SparseArray<Drawable> drawables = new android.util.SparseArray<>();

        private Drawable drawable(int res) {
            Drawable d = drawables.get(res);
            if (d == null) {
                d = getContext().getDrawable(res);
                drawables.put(res, d);
            }
            return d;
        }

        private final android.util.LongSparseArray<Drawable> glyphs = new android.util.LongSparseArray<>();

        private Drawable glyph(int res, int color) {
            long key = ((long) res << 32) ^ (color & 0xFFFFFFFFL);
            Drawable d = glyphs.get(key);
            if (d == null) {
                d = Ui.glyph(getContext(), res, color);
                glyphs.put(key, d);
            }
            return d;
        }

        private Paint gradPaint;
        private int gradH;
        private boolean gradTop;

        private void gradient(Canvas c, int w, int h, boolean fromTop) {
            if (gradPaint == null || gradH != h || gradTop != fromTop) {
                gradPaint = new Paint();
                gradPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                        fromTop ? new int[]{0xE6000000, 0x99000000, 0x00000000} : new int[]{0x00000000, 0x99000000, 0xE6000000},
                        new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                gradH = h;
                gradTop = fromTop;
            }
            c.drawRect(0, 0, w, h, gradPaint);
        }

        private Paint msgGrad;
        private int msgGradH;
        private final Rect thumbSrc = new Rect(), thumbDst = new Rect();
        private final RectF tileRect = new RectF();
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Paint shade;
        private android.graphics.LinearGradient shadeShader;
        private final android.graphics.Matrix shadeMatrix = new android.graphics.Matrix();

        private void cover(Canvas c, int w, int h, Drawable d) {
            float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) return;
            float s = Math.max(w / iw, h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            c.save();
            c.clipRect(0, 0, w, h);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(c);
            c.restore();
        }

        private float line(Canvas c, String s, TextPaint t, float x, float y, float maxW) {
            String e = TextUtils.ellipsize(s == null ? "" : s, t, maxW, TextUtils.TruncateAt.END).toString();
            float base = y - t.ascent();
            c.drawText(e, x, base, t);
            return base + t.descent();
        }

        private void drawNotes(Canvas c, int w, int h, java.util.List<com.bb10.launcher.frames.LiveFrames.NoteItem> notes) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            float y = pad;
            TextPaint head = text(0.029f, 0xFFF0B429, Ui.bold());
            y = line(c, notes.isEmpty() ? "NOTES" : "NOTES  " + notes.size(), head, pad, y, w - 2 * pad);
            y += 0.012f * S;
            if (notes.isEmpty()) {
                line(c, "No notes yet", text(0.031f, Ui.TEXT2, Ui.regular()), pad, y, w - 2 * pad);
                return;
            }
            TextPaint titleT = text(0.034f, Ui.TEXT, Ui.regular()), bodyT = text(0.027f, Ui.TEXT2, Ui.regular());
            float limit = h - Ui.dp(BAR_DP) - pad;
            float rowH = (titleT.descent() - titleT.ascent()) + (bodyT.descent() - bodyT.ascent()) + 0.022f * S;
            for (com.bb10.launcher.frames.LiveFrames.NoteItem n : notes) {
                if (y + rowH > limit) break;
                y = line(c, n.heading, titleT, pad, y, w - 2 * pad);
                y = n.text.isEmpty() ? y + (bodyT.descent() - bodyT.ascent()) : line(c, n.text, bodyT, pad, y + 0.004f * S, w - 2 * pad);
                y += 0.018f * S;
            }
        }

        private Paint skyPaint;
        private String skyFor;
        private int skyH;

        private void drawWeather(Canvas c, int w, int h, com.bb10.launcher.frames.LiveFrames.WeatherNow wn) {
            float S = Ui.S(), pad = 0.022f * S;
            if (skyPaint == null || !wn.symbol.equals(skyFor) || skyH != h) {
                int[] sky = WeatherGlyph.sky(wn.symbol);
                skyPaint = new Paint();
                skyPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, sky[0], sky[1], android.graphics.Shader.TileMode.CLAMP));
                skyFor = wn.symbol;
                skyH = h;
            }
            c.drawRect(0, 0, w, h, skyPaint);
            float area = h - Ui.dp(BAR_DP);
            float y = pad;
            TextPaint placeT = text(0.032f, Ui.TEXT, Ui.bold()), descT = text(0.027f, 0xDDFFFFFF, Ui.regular());
            y = line(c, wn.place.isEmpty() ? "Weather" : wn.place, placeT, pad, y, w - 2 * pad);
            y = line(c, wn.desc, descT, pad, y + 0.004f * S, w - 2 * pad);

            TextPaint hourT = text(0.022f, 0xCCFFFFFF, Ui.regular()), hourTempT = text(0.026f, Ui.TEXT, Ui.regular());
            hourT.setTextAlign(Paint.Align.CENTER);
            hourTempT.setTextAlign(Paint.Align.CENTER);
            float hIcon = 0.05f * S;
            float stripH = (hourT.descent() - hourT.ascent()) + hIcon + (hourTempT.descent() - hourTempT.ascent()) + 0.012f * S;
            boolean strip = !wn.hours.isEmpty() && area - y > stripH + 0.13f * S;
            float bigBottom = strip ? area - stripH - pad * 0.5f : area - pad * 0.5f;

            float room = bigBottom - y - 0.01f * S;
            float icon = Math.max(0.06f * S, Math.min(room, Math.min(w * 0.42f, 0.16f * S)));
            float iy = y + 0.01f * S + (room - icon) / 2f;
            c.drawBitmap(WeatherGlyph.bitmap(wn.symbol, Math.round(icon), false), pad * 0.6f, iy, bitmapPaint);
            TextPaint tempT = text(Math.min(0.1f, icon / S * 0.62f), Ui.TEXT, Ui.light());
            String temp = wn.temp + "°";
            float tx = pad * 0.6f + icon + 0.012f * S;
            float hiLoH = wn.hi != null ? 0.03f * S : 0;
            float ty = iy + icon / 2f - (tempT.ascent() + tempT.descent()) / 2f - hiLoH / 2f;
            c.drawText(temp, tx, ty, tempT);
            if (wn.hi != null && wn.lo != null) {
                TextPaint hlT = text(0.024f, 0xCCFFFFFF, Ui.regular());
                c.drawText("H " + wn.hi + "°  L " + wn.lo + "°", tx + Ui.dp(2), ty + tempT.descent() + 0.022f * S, hlT);
            }

            if (strip) {
                int n = Math.max(1, Math.min(wn.hours.size(), Math.round(w / (0.1f * S))));
                float cw = (w - pad) / n;
                float top = area - stripH;
                p.setColor(0x33000000);
                c.drawRect(0, top - pad * 0.3f, w, area, p);
                for (int i = 0; i < n; i++) {
                    com.bb10.launcher.frames.LiveFrames.WeatherHour hr = wn.hours.get(i);
                    float cx = pad / 2f + cw * i + cw / 2f;
                    float yy = top - hourT.ascent();
                    c.drawText(hr.label, cx, yy, hourT);
                    yy += hourT.descent();
                    c.drawBitmap(WeatherGlyph.bitmap(hr.symbol, Math.round(hIcon), false), cx - hIcon / 2f, yy, bitmapPaint);
                    yy += hIcon - hourTempT.ascent();
                    c.drawText(hr.temp + "°", cx, yy, hourTempT);
                }
            }
        }

        private void drawTasks(Canvas c, int w, int h, java.util.List<com.bb10.launcher.frames.LiveFrames.TaskItem> tasks) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            int overdue = 0, today = 0;
            for (com.bb10.launcher.frames.LiveFrames.TaskItem t : tasks) {
                if (t.overdue()) overdue++;
                else if (t.dueToday()) today++;
            }
            float y = pad;
            TextPaint head = overdue > 0 ? text(0.031f, 0xFFE35B5B, Ui.bold()) : text(0.029f, 0xFFB085D6, Ui.bold());
            String hd = overdue > 0 ? overdue + " OVERDUE" : today > 0 ? today + " DUE TODAY" : "TASKS";
            y = line(c, hd, head, pad, y, w - 2 * pad);
            y += 0.012f * S;
            if (tasks.isEmpty()) {
                line(c, "Nothing to do", text(0.031f, Ui.TEXT2, Ui.regular()), pad, y, w - 2 * pad);
                return;
            }
            TextPaint nameT = text(0.032f, Ui.TEXT, Ui.regular()), dueT = text(0.026f, Ui.TEXT2, Ui.regular());
            dueT.setTextAlign(Paint.Align.RIGHT);
            float rowH = Math.max(nameT.descent() - nameT.ascent(), 0.042f * S) + 0.014f * S;
            float limit = h - Ui.dp(BAR_DP) - pad, r = 0.013f * S;
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            for (com.bb10.launcher.frames.LiveFrames.TaskItem t : tasks) {
                if (y + rowH > limit) break;
                float cy = y + rowH / 2f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, 0.0035f * S));
                p.setColor(t.overdue() ? 0xFFE35B5B : 0xFF8A8A8A);
                c.drawCircle(pad + r, cy, r, p);
                p.setStyle(Paint.Style.FILL);
                String when = "";
                if (t.due > 0) {
                    if (t.dueToday()) when = t.hasTime ? android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", t.due).toString() : "Today";
                    else when = android.text.format.DateFormat.format("MMM d", t.due).toString();
                }
                float tw = 0;
                if (!when.isEmpty()) {
                    dueT.setColor(t.overdue() ? 0xFFE35B5B : Ui.TEXT2);
                    tw = dueT.measureText(when);
                    c.drawText(when, w - pad, cy - (dueT.ascent() + dueT.descent()) / 2f, dueT);
                }
                float nx = pad + 2 * r + 0.015f * S;
                String name = t.flagged ? "\u2691 " + t.title : t.title;
                nameT.setColor(Ui.TEXT);
                String fit = TextUtils.ellipsize(name, nameT, w - pad - tw - 0.015f * S - nx, TextUtils.TruncateAt.END).toString();
                if (t.flagged && fit.startsWith("\u2691")) {
                    nameT.setColor(0xFFE35B5B);
                    c.drawText("\u2691", nx, cy - (nameT.ascent() + nameT.descent()) / 2f, nameT);
                    float fw = nameT.measureText("\u2691 ");
                    nameT.setColor(Ui.TEXT);
                    c.drawText(fit.substring(2), nx + fw, cy - (nameT.ascent() + nameT.descent()) / 2f, nameT);
                } else {
                    c.drawText(fit, nx, cy - (nameT.ascent() + nameT.descent()) / 2f, nameT);
                }
                y += rowH;
            }
        }
        private void drawMusic(Canvas c, int w, int h, android.media.MediaMetadata md, boolean playing) {
            float S = Ui.S(), pad = 0.022f * S;
            if (md == null) return;
            Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != artFor) {
                artFor = art;
                artDrawable = art == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), art);
            }
            if (artDrawable != null) cover(c, w, h, artDrawable);
            else if (icon != null) {
                p.setColor(0xFF262626);
                c.drawRect(0, 0, w, h, p);
                int s = Math.round(Math.min(w, h) * 0.3f);
                icon.setBounds((w - s) / 2, Math.round(0.03f * S), (w + s) / 2, Math.round(0.03f * S) + s);
                icon.draw(c);
            }
            gradient(c, w, h, false);
            int bar = Ui.dp(BAR_DP);
            float btn = 0.07f * S, rowY = h - bar - pad - btn;
            TextPaint tt = text(0.036f, Ui.TEXT, Ui.bold()), at = text(0.029f, 0xCCFFFFFF, Ui.regular());
            String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            float textBottom = rowY - 0.01f * S;
            float y = textBottom - (at.descent() - at.ascent()) - (tt.descent() - tt.ascent());
            y = marquee(c, title, tt, pad, y, w - 2 * pad);
            line(c, artist, at, pad, y, w - 2 * pad);
            int[] icons = {R.drawable.ic_m_prev, playing ? R.drawable.ic_m_pause : R.drawable.ic_m_play, R.drawable.ic_m_next};
            if (controlRects == null) controlRects = new RectF[]{new RectF(), new RectF(), new RectF()};
            controls = controlRects;
            float slot = w / 3f;
            for (int i = 0; i < 3; i++) {
                float cx = slot * i + slot / 2f, cy = rowY + btn / 2f;
                if (i == 1) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xF2FFFFFF);
                    c.drawCircle(cx, cy, btn * 0.7f, p);
                }
                Drawable g = glyph(icons[i], i == 1 ? 0xFF1A1A1A : Ui.TEXT);
                float gs = btn * (i == 1 ? 0.68f : 0.7f);
                g.setBounds(Math.round(cx - gs / 2), Math.round(cy - gs / 2), Math.round(cx + gs / 2), Math.round(cy + gs / 2));
                g.draw(c);
                controls[i].set(slot * i, rowY - pad / 2, slot * (i + 1), rowY + btn + pad / 2);
            }
        }

        private String marqueeText;
        private final Rect marqueeVisible = new Rect();
        private long marqueeStart;

        private float marquee(Canvas c, String s, TextPaint t, float x, float y, float maxW) {
            if (s == null) s = "";
            float tw = t.measureText(s);
            if (tw <= maxW) return line(c, s, t, x, y, maxW);
            long now = android.os.SystemClock.uptimeMillis();
            if (!s.equals(marqueeText)) {
                marqueeText = s;
                marqueeStart = now;
            }
            float S = Ui.S(), gap = 0.08f * S, speed = 0.07f * S;
            long pause = 1500, travel = (long) ((tw + gap) / speed * 1000);
            long k = (now - marqueeStart) % (pause + travel);
            float off = k < pause ? 0 : (k - pause) / 1000f * speed;
            float base = y - t.ascent();
            int save = c.save();
            c.clipRect(x, y, x + maxW, base + t.descent());
            c.drawText(s, x - off, base, t);
            c.drawText(s, x - off + tw + gap, base, t);
            c.restoreToCount(save);
            if (IconView.onScreen(this)) postInvalidateOnAnimation();
            else postInvalidateDelayed(1000);
            return base + t.descent();
        }

        boolean handleControlTap() {
            if (downControl < 0 || controls == null) return false;
            com.bb10.launcher.frames.LiveFrames live = LauncherApp.get().live;
            android.media.session.MediaController mc = live.mediaFor(f.pkg);
            if (mc == null) {
                if (live.lastPlayed(f.pkg) == null) return false;
                live.mediaKey(downControl == 0 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        : downControl == 2 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY);
                return true;
            }
            android.media.session.MediaController.TransportControls tc = mc.getTransportControls();
            if (downControl == 0) tc.skipToPrevious();
            else if (downControl == 2) tc.skipToNext();
            else if (com.bb10.launcher.frames.LiveFrames.isPlaying(mc)) tc.pause();
            else tc.play();
            return true;
        }

        private void drawCalendar(Canvas c, int w, int h, java.util.List<com.bb10.launcher.frames.LiveFrames.Event> events) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            java.util.Calendar now = java.util.Calendar.getInstance();
            TextPaint dayT = text(0.029f, 0xFF8FC33A, Ui.bold()), dateT = text(0.058f, Ui.TEXT, Ui.light());
            float y = pad;
            y = line(c, android.text.format.DateFormat.format("EEEE", now).toString().toUpperCase(java.util.Locale.ROOT), dayT, pad, y, w - 2 * pad);
            y = line(c, android.text.format.DateFormat.format("MMMM d", now).toString(), dateT, pad, y, w - 2 * pad);
            y += 0.012f * S;
            TextPaint timeT = text(0.027f, Ui.BLUE, Ui.bold()), evT = text(0.032f, Ui.TEXT, Ui.regular());
            float limit = h - Ui.dp(BAR_DP) - pad;
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            String fmt = h24 ? "HH:mm" : "h:mm a";
            long nowMs = System.currentTimeMillis();
            java.util.Calendar cut = java.util.Calendar.getInstance();
            cut.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cut.set(java.util.Calendar.MINUTE, 0);
            cut.set(java.util.Calendar.SECOND, 0);
            cut.set(java.util.Calendar.MILLISECOND, 0);
            cut.add(java.util.Calendar.DAY_OF_MONTH, 2);
            long until = cut.getTimeInMillis();
            int shown = 0;
            for (com.bb10.launcher.frames.LiveFrames.Event e : events) {
                if (e.end <= nowMs || e.begin >= until) continue;
                if (y + (timeT.descent() - timeT.ascent()) + (evT.descent() - evT.ascent()) > limit) break;
                boolean ongoing = e.begin <= nowMs;
                boolean today = ongoing || android.text.format.DateUtils.isToday(e.begin);
                String when;
                if (e.allDay) when = today ? "All day" : "Tomorrow \u00b7 All day";
                else if (ongoing) when = "Now \u00b7 until " + android.text.format.DateFormat.format(fmt, e.end);
                else if (today) when = android.text.format.DateFormat.format(fmt, e.begin).toString();
                else when = "Tomorrow \u00b7 " + android.text.format.DateFormat.format(fmt, e.begin);
                y = line(c, when, timeT, pad, y, w - 2 * pad);
                y = line(c, e.title, evT, pad, y, w - 2 * pad) + 0.008f * S;
                shown++;
            }
            if (shown == 0) line(c, "Nothing today or tomorrow", text(0.031f, Ui.TEXT2, Ui.regular()), pad, y, w - 2 * pad);
        }
        private final android.graphics.Path faceRim = new android.graphics.Path();
        private final Rect numBounds = new Rect();

        private float faceEdge(float a, double t) {
            double cs = Math.abs(Math.cos(t)), sn = Math.abs(Math.sin(t)), n = 4.2;
            return (float) (a / Math.pow(Math.pow(cs, n) + Math.pow(sn, n), 1.0 / n));
        }

        private void drawBbClock(Canvas c, int w, int h, android.app.AlarmManager.AlarmClockInfo alarm) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            c.drawRect(0, 0, w, h, p);
            float area = h - Ui.dp(BAR_DP);
            float cx = w / 2f, cy = area / 2f, a = Math.min(w, area) * 0.43f, u = a / 0.286f;
            faceRim.rewind();
            for (int i = 0; i <= 120; i++) {
                double t = i * Math.PI * 2 / 120;
                float rr = faceEdge(a * 1.04f, t);
                float x = cx + (float) Math.cos(t) * rr, y = cy + (float) Math.sin(t) * rr;
                if (i == 0) faceRim.moveTo(x, y);
                else faceRim.lineTo(x, y);
            }
            faceRim.close();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.022f * u);
            p.setColor(0xFF242424);
            c.drawPath(faceRim, p);
            TextPaint num = text(0, 0xFFFFFFFF, Ui.bold());
            num.setTextSize(0.075f * u);
            num.setTextAlign(Paint.Align.CENTER);
            p.setStrokeCap(Paint.Cap.BUTT);
            float in = 0.012f * u;
            for (int m = 0; m < 60; m++) {
                double t = Math.toRadians(m * 6 - 90);
                float rr = faceEdge(a, t);
                boolean hour = m % 5 == 0;
                float cs = (float) Math.cos(t), sn = (float) Math.sin(t);
                if (hour && m % 15 == 0) {
                    String s = String.valueOf(m == 0 ? 12 : m / 5);
                    num.getTextBounds(s, 0, s.length(), numBounds);
                    float nr = rr - in - numBounds.height() / 2f;
                    c.drawText(s, cx + cs * nr, cy + sn * nr - (numBounds.top + numBounds.bottom) / 2f, num);
                    continue;
                }
                int d = Math.min(m % 15, 15 - m % 15);
                if (!hour && (d == 1 || (d == 2 && (m < 3 || m > 57)))) continue;
                float len = (hour ? 0.036f : 0.018f) * u;
                p.setStrokeWidth((hour ? 0.009f : 0.003f) * u);
                p.setColor(hour ? 0xFFFFFFFF : 0xFF6A6A6A);
                c.drawLine(cx + cs * (rr - in), cy + sn * (rr - in), cx + cs * (rr - in - len), cy + sn * (rr - in - len), p);
            }
            java.util.Calendar now = java.util.Calendar.getInstance();
            TextPaint small = text(0, 0xFFFFFFFF, Ui.regular());
            small.setTextSize(0.04f * u);
            small.setTextAlign(Paint.Align.CENTER);
            String date = now.get(java.util.Calendar.DAY_OF_MONTH) + " "
                    + new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(now.getTime()).toUpperCase(java.util.Locale.getDefault());
            c.drawText(date, cx + 0.135f * u, cy + 0.014f * u, small);
            c.drawText(now.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? "AM" : "PM", cx, cy + 0.16f * u, small);
            int hr = now.get(java.util.Calendar.HOUR), mi = now.get(java.util.Calendar.MINUTE);
            double tm = Math.toRadians(mi * 6 - 90), th = Math.toRadians((hr + mi / 60.0) * 30 - 90);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(0.008f * u);
            c.drawLine(cx, cy, cx + (float) Math.cos(tm) * 0.24f * u, cy + (float) Math.sin(tm) * 0.24f * u, p);
            p.setStrokeWidth(0.024f * u);
            float hx0 = cx + (float) Math.cos(th) * 0.015f * u, hy0 = cy + (float) Math.sin(th) * 0.015f * u;
            float hx1 = cx + (float) Math.cos(th) * 0.15f * u, hy1 = cy + (float) Math.sin(th) * 0.15f * u;
            c.drawLine(hx0, hy0, hx1, hy1, p);
            p.setColor(0xFF000000);
            p.setStrokeWidth(0.01f * u);
            c.drawLine(cx + (float) Math.cos(th) * 0.04f * u, cy + (float) Math.sin(th) * 0.04f * u,
                    cx + (float) Math.cos(th) * 0.14f * u, cy + (float) Math.sin(th) * 0.14f * u, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, 0.022f * u, p);
            if (alarm != null && alarm.getShowIntent() != null && "com.bb10.clock".equals(alarm.getShowIntent().getCreatorPackage())) {
                java.util.Calendar at = java.util.Calendar.getInstance();
                at.setTimeInMillis(alarm.getTriggerTime());
                int mins = at.get(java.util.Calendar.HOUR_OF_DAY) * 60 + at.get(java.util.Calendar.MINUTE);
                double t = Math.toRadians((mins % 720) / 2.0 - 90);
                float rr = faceEdge(a * 1.04f, t);
                p.setColor(0xFFE8383A);
                c.drawCircle(cx + (float) Math.cos(t) * rr, cy + (float) Math.sin(t) * rr, 0.022f * u, p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawClock(Canvas c, int w, int h, android.app.AlarmManager.AlarmClockInfo alarm) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            TextPaint big = text(0.1f, Ui.TEXT, Ui.light());
            big.setTextAlign(Paint.Align.CENTER);
            String now = android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm", System.currentTimeMillis()).toString();
            float cy = (h - Ui.dp(BAR_DP)) * 0.42f;
            c.drawText(now, w / 2f, cy - (big.ascent() + big.descent()) / 2f, big);
            TextPaint small = text(0.031f, 0xCCFFFFFF, Ui.regular());
            small.setTextAlign(Paint.Align.CENTER);
            String sub;
            if (alarm != null) {
                long at = alarm.getTriggerTime();
                String t = android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", at).toString();
                sub = (android.text.format.DateUtils.isToday(at) ? "Alarm " : "Alarm " + android.text.format.DateFormat.format("EEE", at) + " ") + t;
            } else {
                sub = "No alarms set";
            }
            float sy = cy + big.descent() + 0.02f * S - small.ascent();
            c.drawText(sub, w / 2f, sy, small);
            if (alarm != null) {
                Drawable g = glyph(R.drawable.ic_alarm, 0xCCFFFFFF);
                int gs = Math.round(0.036f * S);
                float tw = small.measureText(sub);
                int gx = Math.round(w / 2f - tw / 2f - gs - 0.01f * S), gy = Math.round(sy + small.ascent() / 2f - gs / 2f + small.descent() / 2f);
                g.setBounds(gx, gy, gx + gs, gy + gs);
                g.draw(c);
            }
        }

        private void drawOngoing(Canvas c, int w, int h, android.app.Notification n) {
            float S = Ui.S(), pad = 0.022f * S;
            android.os.Bundle ex = n.extras;
            p.setColor(0xFF1E1E1E);
            c.drawRect(0, 0, w, h, p);
            android.graphics.drawable.Icon big = n.getLargeIcon();
            Drawable bg = null;
            if (big != null) {
                try {
                    bg = big.loadDrawable(getContext());
                } catch (Exception ignored) {
                }
            }
            if (bg != null) {
                cover(c, w, h, bg);
                gradient(c, w, h, true);
            } else if (icon != null) {
                int s = Math.round(0.07f * S);
                icon.setBounds(w - pad > 0 ? Math.round(w - pad - s) : 0, Math.round(pad), Math.round(w - pad), Math.round(pad) + s);
                icon.draw(c);
            }
            CharSequence title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence body = ex.getCharSequence(android.app.Notification.EXTRA_TEXT);
            float y = pad;
            y = line(c, title == null ? label : title.toString(), text(0.036f, Ui.TEXT, Ui.bold()), pad, y, w - 2 * pad - 0.08f * S);
            if (ex.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER, false)) {
                long diff = ex.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
                        ? n.when - System.currentTimeMillis() : System.currentTimeMillis() - n.when;
                long sec = Math.max(0, diff / 1000);
                String chrono = sec >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                        : String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
                y = line(c, chrono, text(0.08f, Ui.TEXT, Ui.light()), pad, y + 0.008f * S, w - 2 * pad);
                wantSecondTicks();
            }
            if (body != null) {
                TextPaint bt = text(0.031f, 0xDDFFFFFF, Ui.regular());
                android.text.StaticLayout sl = android.text.StaticLayout.Builder
                        .obtain(body, 0, body.length(), bt, Math.max(1, Math.round(w - 2 * pad)))
                        .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
                c.save();
                c.translate(pad, y + 0.008f * S);
                sl.draw(c);
                c.restore();
            }
            com.bb10.launcher.frames.ProgressInfo pr = com.bb10.launcher.frames.ProgressInfo.of(getContext(), n);
            if (pr != null) {
                if (pr.styled) {
                    float bh = Ui.dp(24);
                    float by = h - Ui.dp(BAR_DP) - bh;
                    p.setColor(0x99000000);
                    c.drawRect(0, by - Ui.dp(4), w, h - Ui.dp(BAR_DP), p);
                    drawProgress(c, pad, by, w - 2 * pad, bh, pr, Ui.BLUE);
                } else {
                    float by = h - Ui.dp(BAR_DP) - Ui.dp(4);
                    p.setColor(0x55FFFFFF);
                    c.drawRect(0, by, w, by + Ui.dp(3), p);
                    p.setColor(Ui.BLUE);
                    c.drawRect(0, by, pr.indeterminate ? w : w * Math.min(1f, pr.progress / (float) pr.max), by + Ui.dp(3), p);
                }
            }
        }

        private android.graphics.drawable.Icon navIconFor;
        private Drawable navIcon;

        private void drawNavigation(Canvas c, int w, int h, android.app.Notification n) {
            float S = Ui.S(), pad = 0.022f * S;
            android.os.Bundle ex = n.extras;
            boolean ownColour = n.color != 0 && n.color != android.app.Notification.COLOR_DEFAULT;
            p.setColor(ownColour ? (0xFF000000 | n.color) : 0xFF1F3A2B);
            c.drawRect(0, 0, w, h, p);
            float area = h - Ui.dp(BAR_DP);

            CharSequence trip = ex.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT);
            if (trip == null) trip = ex.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT);
            if (trip == null) trip = ex.getCharSequence("android.shortCriticalText");
            com.bb10.launcher.frames.ProgressInfo pr = com.bb10.launcher.frames.ProgressInfo.of(getContext(), n);
            TextPaint tripBold = text(0.027f, Ui.TEXT, Ui.bold()), tripT = text(0.027f, 0xE6FFFFFF, Ui.regular());
            float tripH = trip != null ? tripT.descent() - tripT.ascent() : 0;
            float progH = pr != null && pr.styled ? Ui.dp(22) : pr != null ? Ui.dp(6) : 0;
            float band = tripH + progH > 0 ? tripH + progH + pad * (progH > 0 ? 1.3f : 0.9f) : 0;
            if (band > 0) {
                p.setColor(0x47000000);
                c.drawRect(0, area - band, w, area, p);
            }

            float top = pad * 0.7f, avail = area - band - top - pad * 0.3f;
            android.graphics.drawable.Icon li = n.getLargeIcon();
            if (li != navIconFor) {
                navIconFor = li;
                navIcon = null;
                if (li != null) {
                    try {
                        navIcon = li.loadDrawable(getContext());
                    } catch (Exception ignored) {
                    }
                    if (navIcon != null && darkGlyph(navIcon)) {
                        navIcon = navIcon.mutate();
                        navIcon.setTint(0xFFFFFFFF);
                    }
                }
            }
            float x = pad;
            float is = Math.max(0, Math.min(avail * 0.85f, Math.min(w * 0.26f, 0.11f * S)));
            if (navIcon != null && is > Ui.dp(16)) {
                int iy = Math.round(top + (avail - is) / 2f);
                navIcon.setBounds(Math.round(pad * 0.6f), iy, Math.round(pad * 0.6f + is), iy + Math.round(is));
                navIcon.draw(c);
                x = pad * 0.6f + is + 0.018f * S;
            }
            CharSequence dist = ex.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence street = ex.getCharSequence(android.app.Notification.EXTRA_TEXT);
            String ds = dist == null ? "" : dist.toString().trim(), ss = street == null ? "" : street.toString().trim();
            float tw = Math.max(1, w - x - pad * 0.8f);
            android.text.StaticLayout dl = null, sl = null;
            float gap = 0.004f * S, block = 0;
            boolean shortTitle = ds.length() <= 10;
            float[] sizes = shortTitle ? new float[]{0.05f, 0.043f, 0.037f, 0.032f, 0.028f} : new float[]{0.03f, 0.027f, 0.024f};
            for (float sz : sizes) {
                TextPaint distT = text(sz, Ui.TEXT, shortTitle ? Ui.light() : Ui.regular());
                TextPaint streetT = text(Math.min(0.026f, sz * 0.85f), 0xE6FFFFFF, Ui.regular());
                dl = ds.isEmpty() ? null : android.text.StaticLayout.Builder.obtain(ds, 0, ds.length(), distT, Math.round(tw))
                        .setMaxLines(shortTitle ? 2 : 4).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
                sl = ss.isEmpty() ? null : android.text.StaticLayout.Builder.obtain(ss, 0, ss.length(), streetT, Math.round(tw))
                        .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
                block = (dl != null ? dl.getHeight() : 0) + (sl != null ? sl.getHeight() + (dl != null ? gap : 0) : 0);
                if (block <= avail) break;
            }
            float y = top + Math.max(0, (avail - block) / 2f);
            c.save();
            c.clipRect(x, top, w, top + avail);
            if (dl != null) {
                c.translate(x, y);
                dl.draw(c);
                c.translate(0, dl.getHeight() + gap);
            } else {
                c.translate(x, y);
            }
            if (sl != null) sl.draw(c);
            c.restore();

            float by = area - band + pad * 0.45f;
            if (trip != null) {
                String s = trip.toString();
                int cut = s.indexOf(" · ");
                String first = cut > 0 ? s.substring(0, cut) : s, rest = cut > 0 ? s.substring(cut) : "";
                float base = by - tripT.ascent();
                String f1 = TextUtils.ellipsize(first, tripBold, w - 2 * pad, TextUtils.TruncateAt.END).toString();
                c.drawText(f1, pad, base, tripBold);
                float fw = tripBold.measureText(f1);
                if (!rest.isEmpty() && fw < w - 2 * pad) {
                    c.drawText(TextUtils.ellipsize(rest, tripT, w - 2 * pad - fw, TextUtils.TruncateAt.END).toString(), pad + fw, base, tripT);
                }
                by += tripH;
            }
            if (pr != null) drawProgress(c, pad, by + pad * 0.1f, w - 2 * pad, progH, pr, 0xFFFFFFFF);
        }

        private void drawDevice(Canvas c, int w, int h, com.bb10.launcher.frames.LiveFrames.DeviceStats s) {
            float S = Ui.S(), pad = 0.022f * S;
            p.setColor(0xFF1A1A1A);
            c.drawRect(0, 0, w, h, p);
            float area = h - Ui.dp(BAR_DP);
            TextPaint head = text(0.026f, Ui.BLUE, Ui.bold());
            float y = line(c, "DEVICE MONITOR", head, pad, pad * 0.8f, w - 2 * pad);
            TextPaint labelT = text(0.025f, Ui.TEXT, Ui.regular()), subT = text(0.021f, Ui.TEXT2, Ui.regular());
            labelT.setTextAlign(Paint.Align.CENTER);
            subT.setTextAlign(Paint.Align.CENTER);
            float under = (labelT.descent() - labelT.ascent()) + (subT.descent() - subT.ascent()) + 0.006f * S;
            float colW = (w - pad) / 3f;
            float ring = Math.max(Ui.dp(24), Math.min(colW - pad * 0.8f, area - y - under - pad * 1.1f));
            float cy = y + pad * 0.5f + ring / 2f;
            int bat = Math.max(0, s.battery);
            int batCol = s.charging ? 0xFF4CAF50 : bat <= 15 ? 0xFFE35B5B : bat <= 30 ? 0xFFF0B429 : 0xFF4CAF50;
            float mem = s.memTotal > 0 ? s.memUsed / (float) s.memTotal : 0;
            float disk = s.diskTotal > 0 ? s.diskUsed / (float) s.diskTotal : 0;
            String[] labels = {"Battery", "Memory", "Storage"};
            String[] subs = {
                    s.battery < 0 ? "" : (s.charging ? "Charging" : "") + (s.batteryTemp > 0 ? (s.charging ? " · " : "") + Math.round(s.batteryTemp) + "°C" : ""),
                    gb(s.memUsed) + " / " + gb(s.memTotal),
                    gb(s.diskUsed) + " / " + gb(s.diskTotal)};
            float[] vals = {bat / 100f, mem, disk};
            int[] cols = {batCol, Ui.BLUE, disk > 0.9f ? 0xFFE35B5B : 0xFFB085D6};
            TextPaint pct = text(Math.max(0.022f, Math.min(0.034f, ring / S * 0.26f)), Ui.TEXT, Ui.light());
            pct.setTextAlign(Paint.Align.CENTER);
            float stroke = Math.max(Ui.dp(3), ring * 0.1f);
            for (int i = 0; i < 3; i++) {
                float cx = pad / 2f + colW * i + colW / 2f;
                tileRect.set(cx - ring / 2f + stroke / 2f, cy - ring / 2f + stroke / 2f, cx + ring / 2f - stroke / 2f, cy + ring / 2f - stroke / 2f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(0xFF333333);
                c.drawArc(tileRect, 0, 360, false, p);
                p.setColor(cols[i]);
                float sweep = 360f * Math.max(0, Math.min(1, vals[i]));
                if (sweep > 0) c.drawArc(tileRect, -90, Math.max(2, sweep), false, p);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeCap(Paint.Cap.BUTT);
                String v = i == 0 && s.battery < 0 ? "–" : Math.round(vals[i] * 100) + "%";
                c.drawText(v, cx, cy - (pct.ascent() + pct.descent()) / 2f, pct);
                float ty = cy + ring / 2f + 0.006f * S - labelT.ascent();
                c.drawText(labels[i], cx, ty, labelT);
                if (!subs[i].isEmpty() && ty + labelT.descent() - subT.ascent() < area) {
                    c.drawText(TextUtils.ellipsize(subs[i], subT, colW - Ui.dp(4), TextUtils.TruncateAt.END).toString(),
                            cx, ty + labelT.descent() - subT.ascent(), subT);
                }
            }
        }

        private static String gb(long bytes) {
            if (bytes <= 0) return "0";
            double g = bytes / 1_000_000_000.0;
            return g >= 10 ? Math.round(g) + " GB" : String.format(java.util.Locale.getDefault(), "%.1f GB", g);
        }

        private boolean darkGlyph(Drawable d) {
            try {
                Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                Rect old = new Rect(d.getBounds());
                d.setBounds(0, 0, 16, 16);
                d.draw(new Canvas(b));
                d.setBounds(old);
                double lum = 0, weight = 0;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int px = b.getPixel(x, y);
                        double a = (px >>> 24) / 255.0;
                        if (a < 0.3) continue;
                        lum += a * (0.299 * ((px >> 16) & 0xFF) + 0.587 * ((px >> 8) & 0xFF) + 0.114 * (px & 0xFF)) / 255.0;
                        weight += a;
                    }
                }
                b.recycle();
                return weight > 4 && lum / weight < 0.35;
            } catch (Exception e) {
                return false;
            }
        }

        private final android.util.ArrayMap<android.graphics.drawable.Icon, Drawable> progIcons = new android.util.ArrayMap<>();

        private Drawable progIcon(android.graphics.drawable.Icon ic) {
            if (ic == null) return null;
            Drawable d = progIcons.get(ic);
            if (d == null && !progIcons.containsKey(ic)) {
                try {
                    d = ic.loadDrawable(getContext());
                } catch (Exception ignored) {
                }
                if (progIcons.size() > 12) progIcons.clear();
                progIcons.put(ic, d);
            }
            return d;
        }

        private void drawProgress(Canvas c, float x, float y, float width, float height,
                                  com.bb10.launcher.frames.ProgressInfo pr, int accent) {
            float cy = y + height / 2f;
            float th = Math.max(Ui.dp(3), Math.min(Ui.dp(6), height * 0.25f)), r = th / 2f;
            float icon = Math.min(height, Ui.dp(20));
            Drawable si = progIcon(pr.start), ei = progIcon(pr.end), ti = progIcon(pr.tracker);
            float x0 = x, x1 = x + width;
            if (si != null) {
                si.setBounds(Math.round(x0), Math.round(cy - icon / 2), Math.round(x0 + icon), Math.round(cy + icon / 2));
                si.draw(c);
                x0 += icon + Ui.dp(4);
            }
            if (ei != null) {
                ei.setBounds(Math.round(x1 - icon), Math.round(cy - icon / 2), Math.round(x1), Math.round(cy + icon / 2));
                ei.draw(c);
                x1 -= icon + Ui.dp(4);
            }
            if (pr.styled || ti != null) {
                float inset = Math.min(height, Ui.dp(22)) / 2f;
                if (si == null) x0 += inset;
                if (ei == null) x1 -= inset;
            }
            if (x1 - x0 < Ui.dp(10)) return;
            float span = x1 - x0, max = Math.max(1, pr.max);
            float at = x0 + span * Math.max(0, Math.min(1f, pr.progress / max));
            if (pr.indeterminate) at = x0;
            float gap = pr.segLength.length > 1 ? Ui.dp(2) : 0;
            float pos = 0;
            for (int i = 0; i < pr.segLength.length; i++) {
                float sx = x0 + span * (pos / max), ex = x0 + span * ((pos + pr.segLength[i]) / max);
                pos += pr.segLength[i];
                if (i > 0) sx += gap / 2f;
                if (i < pr.segLength.length - 1) ex -= gap / 2f;
                if (ex <= sx) continue;
                int col = pr.segColor[i] == 0 ? accent : (pr.segColor[i] | 0xFF000000);
                int faded = (col & 0x00FFFFFF) | 0x59000000;
                boolean fade = pr.styledByProgress || !pr.styled;
                if (pr.indeterminate) {
                    p.setColor(faded);
                    tileRect.set(sx, cy - r, ex, cy + r);
                    c.drawRoundRect(tileRect, r, r, p);
                    continue;
                }
                float mid = Math.max(sx, Math.min(ex, at));
                if (mid > sx) {
                    p.setColor(col);
                    tileRect.set(sx, cy - r, mid, cy + r);
                    c.drawRoundRect(tileRect, r, r, p);
                }
                if (ex > mid) {
                    p.setColor(fade ? faded : col);
                    tileRect.set(mid, cy - r, ex, cy + r);
                    c.drawRoundRect(tileRect, r, r, p);
                }
            }
            float pr2 = Math.max(th * 0.9f, Ui.dp(3.5f));
            for (int i = 0; i < pr.pointPos.length; i++) {
                float px = x0 + span * Math.max(0, Math.min(1f, pr.pointPos[i] / max));
                int col = pr.pointColor[i] == 0 ? accent : (pr.pointColor[i] | 0xFF000000);
                if (pr.styledByProgress && pr.pointPos[i] > pr.progress) col = (col & 0x00FFFFFF) | 0x99000000;
                p.setColor(col);
                c.drawCircle(px, cy, pr2, p);
            }
            if (!pr.indeterminate) {
                if (ti != null) {
                    float ts = Math.min(height, Ui.dp(22));
                    ti.setBounds(Math.round(at - ts / 2), Math.round(cy - ts / 2), Math.round(at + ts / 2), Math.round(cy + ts / 2));
                    ti.draw(c);
                } else if (pr.styled) {
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(at, cy, Math.max(r * 1.6f, Ui.dp(4)), p);
                }
            }
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) {
                closeMe();
                return true;
            }
            downClose = false;
            downControl = -1;
            return super.onKeyDown(code, e);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onFocusChanged(boolean g, int d, Rect prev) {
            super.onFocusChanged(g, d, prev);
            opened = false;
            invalidate();
        }

        private String avatarFor;
        private Drawable avatar;

        private com.bb10.launcher.hub.HubItem newMessage() {
            LauncherApp app = LauncherApp.get();
            if (!app.prefs.bool(com.bb10.launcher.Prefs.FRAMES_MESSAGES, true)) return null;
            return app.hub.latestUnreadSince(f.pkg, f.time);
        }

        private void drawMessage(Canvas c, int w, int h, com.bb10.launcher.hub.HubItem m) {
            float S = Ui.S();
            if (!m.id.equals(avatarFor)) {
                avatarFor = m.id;
                avatar = null;
                android.graphics.drawable.Icon ic = LauncherApp.get().hub.avatar(m);
                if (ic != null) {
                    try {
                        avatar = ic.loadDrawable(getContext());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (avatar != null && avatar.getIntrinsicWidth() > 0 && avatar.getIntrinsicHeight() > 0) {
                float iw = avatar.getIntrinsicWidth(), ih = avatar.getIntrinsicHeight();
                float s = Math.max(w / iw, h / ih);
                int dw = Math.round(iw * s), dh = Math.round(ih * s);
                int l = (w - dw) / 2, tp0 = (h - dh) / 2;
                c.save();
                c.clipRect(0, 0, w, h);
                avatar.setBounds(l, tp0, l + dw, tp0 + dh);
                avatar.draw(c);
                c.restore();
            } else if (icon != null) {
                p.setColor(0xFF262626);
                c.drawRect(0, 0, w, h, p);
                int s = Math.round(Math.min(w, h) * 0.32f);
                int l = (w - s) / 2, tp0 = h - Ui.dp(BAR_DP) - s - Math.round(0.03f * S);
                icon.setBounds(l, tp0, l + s, tp0 + s);
                icon.draw(c);
            }
            if (msgGrad == null || msgGradH != h) {
                msgGrad = new Paint();
                msgGrad.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                        new int[]{0xE6000000, 0x99000000, 0x00000000}, new float[]{0f, 0.45f, 1f},
                        android.graphics.Shader.TileMode.CLAMP));
                msgGradH = h;
            }
            c.drawRect(0, 0, w, h, msgGrad);

            float pad = 0.022f * S, x = pad, y = pad;
            TextPaint np = text(0.041f, Ui.TEXT, Ui.bold());

            String name = TextUtils.ellipsize(m.title, np, w - 2 * pad, TextUtils.TruncateAt.END).toString();
            y += -np.ascent();
            c.drawText(name, x, y, np);

            TextPaint tp2 = text(0.029f, 0xCCFFFFFF, Ui.regular());

            CharSequence when = android.text.format.DateUtils.getRelativeTimeSpanString(m.time,
                    System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE);
            y += np.descent() + 0.006f * S - tp2.ascent();
            c.drawText(when.toString(), x, y, tp2);

            TextPaint mp = text(0.034f, Ui.TEXT, Ui.regular());

            y += tp2.descent() + 0.012f * S;
            int avail = Math.max(1, Math.round(w - 2 * pad));
            int maxLines = Math.max(1, (int) ((h - Ui.dp(BAR_DP) - y - pad) / mp.getFontSpacing()));
            android.text.StaticLayout body = android.text.StaticLayout.Builder
                    .obtain(m.text, 0, m.text.length(), mp, avail)
                    .setMaxLines(Math.min(3, maxLines))
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            c.save();
            c.translate(x, y);
            body.draw(c);
            c.restore();
        }

        private boolean drawWidget(Canvas c, int w, int h, com.bb10.launcher.hub.HubItem msg) {
            if (msg != null) {
                drawMessage(c, w, h, msg);
                return true;
            }
            return drawLive(c, w, h);
        }

        private void drawPreview(Canvas c, int w, int h) {
            Bitmap t = f.pkg.equals(openingPkg) ? null : f.thumb;
            if (t != null && !t.isRecycled()) {
                float s = Math.max(w / (float) t.getWidth(), h / (float) t.getHeight());
                float sw = w / s, sh = h / s;
                float sx = (t.getWidth() - sw) / 2f, sy = 0;
                thumbSrc.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));
                thumbDst.set(0, 0, w, h);
                c.drawBitmap(t, thumbSrc, thumbDst, p);
            } else if (icon != null) {
                icon.setBounds(iconRect(w, h));
                icon.draw(c);
            }
        }
        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(GROUND);
            c.drawRect(0, 0, w, h, p);
            com.bb10.launcher.hub.HubItem msg = newMessage();
            controls = null;
            float a = f.left ? liveAlpha(f.pkg) : 0f;
            if (!f.left || f.pkg.equals(openingPkg)) {
                drawPreview(c, w, h);
                live = false;
            } else if (a >= 1f) {
                live = drawWidget(c, w, h, msg);
                if (!live) drawPreview(c, w, h);
            } else {
                live = false;
                boolean has = false;
                if (a > 0f) {
                    c.save();
                    c.clipRect(0, 0, 0, 0);
                    has = drawWidget(c, w, h, msg);
                    c.restore();
                    controls = null;
                }
                if (!has) {
                    drawPreview(c, w, h);
                } else if (a < 0.5f) {
                    drawPreview(c, w, h);
                    p.setColor(Math.round(255 * smooth(a / 0.5f)) << 24);
                    c.drawRect(0, 0, w, h, p);
                } else {
                    p.setColor(0xFF000000);
                    c.drawRect(0, 0, w, h, p);
                    int save = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * smooth((a - 0.5f) / 0.5f)));
                    live = drawWidget(c, w, h, msg);
                    if (!live) drawPreview(c, w, h);
                    c.restoreToCount(save);
                }
                long wait = liveRevealAt + LIVE_DELAY - android.os.SystemClock.uptimeMillis();
                if (wait > 0) postInvalidateDelayed(wait);
                else postInvalidateOnAnimation();
            }
            int bar = Ui.dp(BAR_DP);
            p.setColor(DARK ? 0xFF1E1E1E : 0xFFFFFFFF);
            c.drawRect(0, h - bar, w, h, p);
            p.setColor(DARK ? 0xFF3A3A3A : 0xFFBDBDBD);
            c.drawRect(0, h - bar, w, h - bar + Math.max(1, Ui.dp(1)), p);
            int cs = Ui.dp(20);
            int cx = w - Ui.dp(9) - cs, cy = h - bar + (bar - cs) / 2;
            close.setBounds(cx, cy, cx + cs, cy + cs);
            close.draw(c);
            closeRect.set(w - Ui.dp(48), h - bar - Ui.dp(10), w, h);
            int is = Ui.dp(27), ix = Ui.dp(6), iy = h - bar + (bar - is) / 2;
            int tx = ix;
            if (icon != null) {
                android.graphics.Rect keep = icon.copyBounds();
                android.graphics.Bitmap sh = iconShadow(icon, is);
                if (sh != null) c.drawBitmap(sh, ix - shadowPad(), iy - shadowPad() + Ui.dp(1), null);
                icon.setBounds(ix, iy, ix + is, iy + is);
                icon.draw(c);
                icon.setBounds(keep);
                tx = ix + is + Ui.dp(7);
            }
            String tt = TextUtils.ellipsize(label, tp, w - Ui.dp(48) - tx, TextUtils.TruncateAt.END).toString();
            if (capH == 0) {
                android.graphics.Rect hb = new android.graphics.Rect();
                tp.getTextBounds("H", 0, 1, hb);
                capH = hb.height();
            }
            float mid = h - bar / 2f + Math.max(1, Ui.dp(1)) / 2f;
            c.drawText(tt, tx, mid + capH / 2f, tp);
            p.setStyle(Paint.Style.STROKE);
            if (!plain && !opened && isFocused() && !isInTouchMode()) {
                p.setStrokeWidth(Ui.dp(2.5f));
                p.setColor(Ui.BLUE);
            } else {
                p.setStrokeWidth(Ui.dp(1));
                p.setColor(0x80A0A0A0);
            }
            if (!plain) c.drawRect(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2, w - p.getStrokeWidth() / 2, h - p.getStrokeWidth() / 2, p);
            if (f.locked && !plain) {
                if (lockIcon == null) lockIcon = Ui.glyph(getContext(), R.drawable.ic_lock, Ui.TEXT);
                float r = 0.03f * Ui.S(), m = Ui.dp(6);
                float lx = m + r, ly = m + r;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xCC000000);
                c.drawCircle(lx, ly, r, p);
                int g = Math.round(r * 1.25f);
                lockIcon.setBounds(Math.round(lx - g / 2f), Math.round(ly - g / 2f), Math.round(lx + g / 2f), Math.round(ly + g / 2f));
                lockIcon.draw(c);
            }
        }

        private Drawable lockIcon;
    }
}
