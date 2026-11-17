package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import com.bb10.launcher.R;

public class PageIndicator extends View {
    private static final float PITCH = 0.0667f, GLYPH = 0.035f, GLYPH_ON = 0.045f, DOT = 0.022f, CURRENT = 0.036f, NUMBER = 0.025f;

    private final PagerView pager;
    private final Drawable hub, home, hubShadow, homeShadow;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private int gridPages = 1;
    private float[] centers = new float[0];

    public PageIndicator(Context c, PagerView pager) {
        super(c);
        this.pager = pager;
        hub = c.getDrawable(R.drawable.ic_mail).mutate();
        home = c.getDrawable(R.drawable.ic_home).mutate();
        hubShadow = c.getDrawable(R.drawable.ic_mail).mutate();
        homeShadow = c.getDrawable(R.drawable.ic_home).mutate();
        hubShadow.setTint(0x80000000);
        homeShadow.setTint(0x80000000);
        num.setColor(0xFF000000);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(Typeface.create(Ui.bold(), Typeface.BOLD));
    }

    public void setup(int gridPages) {
        this.gridPages = gridPages;
        invalidate();
    }

    private static void put(Canvas c, Drawable d, Drawable shadow, float cx, float cy, float size, int color) {
        int l = Math.round(cx - size / 2), t = Math.round(cy - size / 2), s = Math.round(size);
        Ui.outline(c, shadow, l, t, s);
        d.setTint(color);
        d.setBounds(l, t, l + s, t + s);
        d.draw(c);
    }

    @Override
    protected void onDraw(Canvas c) {
        float S = Ui.S();
        int n = 2 + gridPages;
        float cy = getHeight() / 2f;
        int cur = pager.getCurrentPage();
        layoutGroup(n, cur, S);
        int on = Ui.homeFg(), off = on;
        int sh = Ui.homeOutline();
        hubShadow.setTint(sh);
        homeShadow.setTint(sh);
        num.setColor(Ui.homeDark ? 0xFFFFFFFF : 0xFF000000);

        boolean moving = step(n, cur);
        if (shownAt(0)) put(c, hub, hubShadow, centers[0], cy, lerp(GLYPH, GLYPH_ON, ease(prog[0])) * S, cur == 0 ? on : off);
        float hs = lerp(GLYPH, GLYPH_ON, ease(prog[1])) * S;
        if (shownAt(1)) put(c, home, homeShadow, centers[1], cy, hs, cur == 1 ? on : off);
        View hv = cur == 1 ? pager.getCurrentView() : null;
        if (cur == 1 && shownAt(1) && hv instanceof ActiveFramesPage && ((ActiveFramesPage) hv).scrollable()) {
            View v = hv;
            boolean up = v instanceof ActiveFramesPage && ((ActiveFramesPage) v).pastHalf();
            float d = hs / 2f + 0.012f * S;
            caret(c, centers[1], up ? cy - d : cy + d, on, up);
        }
        p.clearShadowLayer();
        float ow = Ui.outlineW();
        for (int i = 0; i < gridPages; i++) {
            if (!shownAt(2 + i)) continue;
            float x = centers[2 + i], k = ease(prog[2 + i]);
            float r = lerp(DOT, CURRENT, k) * S / 2f;
            p.setColor(sh);
            c.drawCircle(x, cy + ow * 0.6f, r + ow, p);
            p.setColor(k > 0f ? on : off);
            c.drawCircle(x, cy, r, p);
            if (k > 0.3f) {
                num.setTextSize(NUMBER * S * k);
                int a = num.getAlpha();
                num.setAlpha(Math.round(255 * Math.min(1f, (k - 0.3f) / 0.5f)));
                String s = String.valueOf(i + 1);
                Rect b = bounds;
                num.getTextBounds(s, 0, s.length(), b);
                c.drawText(s, x, cy - b.exactCenterY(), num);
                num.setAlpha(a);
            }
        }
        if (moreLeft) arrow(c, leftX, cy, on, true);
        if (moreRight) arrow(c, rightX, cy, on, false);
        if (scrubbing) {
            if (bubbleOut != null) bubbleOut.invalidate();
            else drawBubble(c, cy);
        }
        if (moving) postInvalidateOnAnimation();
    }

    private static final int PER_GROUP = 6;
    private int from, shown;
    private boolean moreLeft, moreRight;
    private float leftX, rightX;

    private void layoutGroup(int n, int cur, float S) {
        if (centers.length != n) centers = new float[n];
        java.util.Arrays.fill(centers, Float.NaN);
        float w = getWidth(), room = w - Ui.s(0.04f);
        if (n <= PER_GROUP) {
            from = 0;
            shown = n;
            moreLeft = moreRight = false;
            float pitch = Math.min(PITCH * S, room / Math.max(1, n - 1));
            float first = w / 2f - pitch * (n - 1) / 2f;
            for (int i = 0; i < n; i++) centers[i] = first + pitch * i;
            return;
        }
        float pitch = Math.min(PITCH * S, room / (PER_GROUP + 1));
        float first = w / 2f - pitch * (PER_GROUP - 1) / 2f;
        from = Math.max(0, Math.min(cur, n - 1)) / PER_GROUP * PER_GROUP;
        shown = Math.min(PER_GROUP, n - from);
        for (int i = 0; i < shown; i++) centers[from + i] = first + pitch * i;
        moreLeft = from > 0;
        moreRight = from + shown < n;
        leftX = first - pitch;
        rightX = first + pitch * PER_GROUP;
    }

    private boolean shownAt(int page) {
        return page < centers.length && !Float.isNaN(centers[page]);
    }

    private void arrow(Canvas c, float cx, float cy, int color, boolean left) {
        float hh = 0.011f * Ui.S(), d = 0.0095f * Ui.S();
        float base = left ? cx + d / 2f : cx - d / 2f, tip = left ? cx - d / 2f : cx + d / 2f;
        caretPath.reset();
        caretPath.moveTo(base, cy - hh);
        caretPath.lineTo(base, cy + hh);
        caretPath.lineTo(tip, cy);
        caretPath.close();
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Ui.outlineW() * 2);
        p.setColor(Ui.homeOutline());
        c.drawPath(caretPath, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(caretPath, p);
    }

    private int arrowAt(float x, int nearestPage) {
        float best = nearestPage >= 0 ? Math.abs(centers[nearestPage] - x) : Float.MAX_VALUE;
        if (moreLeft && Math.abs(leftX - x) < best) return from - 1;
        if (moreRight && Math.abs(rightX - x) < best) return from + shown;
        return -1;
    }

    private long arrowTime;

    private static final float GROW_MS = 180f;
    private float[] prog = new float[0];
    private long lastStep;

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

    private boolean step(int n, int cur) {
        long now = android.os.SystemClock.uptimeMillis();
        if (prog.length != n) {
            prog = new float[n];
            if (cur >= 0 && cur < n) prog[cur] = 1f;
            lastStep = now;
            return false;
        }
        if (!wasMoving) lastStep = now - 16;
        float d = Math.min(1f, (now - lastStep) / GROW_MS);
        lastStep = now;
        boolean moving = false;
        for (int i = 0; i < n; i++) {
            float to = i == cur ? 1f : 0f;
            if (prog[i] == to) continue;
            prog[i] = to > prog[i] ? Math.min(to, prog[i] + d) : Math.max(to, prog[i] - d);
            if (prog[i] != to) moving = true;
        }
        wasMoving = moving;
        return moving;
    }

    private boolean wasMoving;

    private static float ease(float k) { return k * k * (3f - 2f * k); }

    private final android.graphics.Path caretPath = new android.graphics.Path();

    private void caret(Canvas c, float cx, float cy, int color, boolean up) {
        float hw = 0.0085f * Ui.S(), h = 0.0075f * Ui.S();
        float base = up ? cy + h / 2f : cy - h / 2f, tip = up ? cy - h / 2f : cy + h / 2f;
        caretPath.reset();
        caretPath.moveTo(cx - hw, base);
        caretPath.lineTo(cx + hw, base);
        caretPath.lineTo(cx, tip);
        caretPath.close();
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Ui.outlineW() * 2);
        p.setColor(Ui.homeOutline());
        c.drawPath(caretPath, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(caretPath, p);
    }

    private void squares(Canvas c, float cx, float cy, float size, int color) {
        float gap = size * 0.16f, s = (size - gap) / 2f, r = s * 0.18f;
        float l = cx - size / 2f, t = cy - size / 2f, ow = Ui.outlineW();
        p.clearShadowLayer();
        for (int pass = 0; pass < 2; pass++) {
            p.setColor(pass == 0 ? Ui.homeOutline() : color);
            float g = pass == 0 ? ow : 0, dy = pass == 0 ? ow * 0.6f : 0;
            for (int i = 0; i < 4; i++) {
                float x = l + (i % 2) * (s + gap), y = t + (i / 2) * (s + gap) + dy;
                c.drawRoundRect(x - g, y - g, x + s + g, y + s + g, r + g, r + g, p);
            }
        }
    }

    public interface ScrubListener {
        void onScrub(boolean on);

        default void onPeekTouch(int action, float rawX, float rawY) {}
    }

    private boolean peeking;
    private float downRawX, downRawY;
    private android.view.VelocityTracker vt;

    public ScrubListener scrubListener;
    private boolean scrubbing;
    private float downX, downY;
    private final Runnable hold = () -> startScrub();

    private void startScrub() {
        if (scrubbing) return;
        scrubbing = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (scrubListener != null) scrubListener.onScrub(true);
        invalidate();
    }

    private void endScrub() {
        removeCallbacks(hold);
        if (!scrubbing) return;
        scrubbing = false;
        if (bubbleOut != null) bubbleOut.invalidate();
        if (scrubListener != null) scrubListener.onScrub(false);
        invalidate();
    }

    private int nearest(float x) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < centers.length; i++) {
            if (Float.isNaN(centers[i])) continue;
            float d = Math.abs(centers[i] - x);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = android.view.VelocityTracker.obtain();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) vt.clear();
        MotionEvent raw = MotionEvent.obtain(e);
        raw.setLocation(e.getRawX(), e.getRawY());
        vt.addMovement(raw);
        raw.recycle();
        int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (peeking || (!scrubbing && e.getActionMasked() == MotionEvent.ACTION_MOVE
                && downY - e.getY() > Ui.dp(24) && downY - e.getY() > Math.abs(e.getX() - downX) * 2f)) {
            if (!peeking) {
                peeking = true;
                removeCallbacks(hold);
                if (scrubListener != null) scrubListener.onPeekTouch(MotionEvent.ACTION_DOWN, downRawX, downRawY);
            }
            int a = e.getActionMasked();
            if (scrubListener != null && a != MotionEvent.ACTION_DOWN) scrubListener.onPeekTouch(a, e.getRawX(), e.getRawY());
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) peeking = false;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                peeking = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing && Math.abs(e.getX() - downX) > slop && Math.abs(e.getX() - downX) > (downY - e.getY()) * 0.8f) startScrub();
                if (scrubbing) {
                    int i = nearest(e.getX());
                    int a = arrowAt(e.getX(), i);
                    if (a >= 0) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - arrowTime < 450) break;
                        arrowTime = now;
                        i = a;
                    }
                    if (i >= 0 && i != pager.getCurrentPage()) {
                        pager.setCurrentPage(i, true);
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!scrubbing) {
                    int best = nearest(e.getX());
                    int a = arrowAt(e.getX(), best);
                    if (a >= 0) {
                        float ax = a < from ? leftX : rightX;
                        if (Math.abs(ax - e.getX()) < Ui.s(0.035f)) pager.setCurrentPage(a, true);
                    } else if (best >= 0 && Math.abs(centers[best] - e.getX()) < Ui.s(0.035f)) {
                        pager.setCurrentPage(best, true);
                    }
                }
                endScrub();
                break;
            case MotionEvent.ACTION_CANCEL:
                endScrub();
                break;
        }
        return true;
    }

    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);

    public android.view.View bubbleOut;

    public void drawBubbleOut(Canvas c) {
        if (scrubbing && centers.length > 0) drawBubble(c, getHeight() / 2f);
    }

    private void drawBubble(Canvas c, float cy) {
        int cur = pager.getCurrentPage();
        if (cur < 0 || cur >= centers.length || Float.isNaN(centers[cur])) return;
        float S = Ui.S(), x = centers[cur], y = cy - 0.153f * S, r = 0.05f * S;
        bubble.setStyle(Paint.Style.FILL);
        bubble.setColor(0xF0101214);
        c.drawCircle(x, y, r, bubble);
        bubble.setStyle(Paint.Style.STROKE);
        bubble.setStrokeWidth(Math.max(1f, S * 0.003f));
        bubble.setColor(0x50FFFFFF);
        c.drawCircle(x, y, r, bubble);
        bubble.setStyle(Paint.Style.FILL);
        if (cur >= 2) {
            Paint n = new Paint(num);
            n.setColor(0xFFFFFFFF);
            n.setTextSize(0.05f * S);
            String s = String.valueOf(cur - 1);
            n.getTextBounds(s, 0, s.length(), bounds);
            c.drawText(s, x, y - bounds.exactCenterY(), n);
        } else {
            Drawable d = cur == 0 ? hub : home;
            int sz = Math.round(0.05f * S), l = Math.round(x - sz / 2f), t = Math.round(y - sz / 2f);
            d.setTint(0xFFFFFFFF);
            d.setBounds(l, t, l + sz, t + sz);
            d.draw(c);
        }
    }
}
