package com.bb10.launcher.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;

public class PagerView extends ViewGroup {
    public interface Listener {
        void onPageChanged(int page);

        void onOverscrollLeft();

        default boolean pullsLeft() { return false; }

        default void onPullLeft(float px) {}

        default float pulled() { return 0f; }

        default void onPullLeftEnd(float px, float velocity) {}
    }

    private float pull = -1f;

    private final OverScroller scroller;
    private final int touchSlop, minFling;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging;
    private int current;
    private Listener listener;

    public PagerView(Context c) {
        super(c);
        scroller = new OverScroller(c, new DecelerateInterpolator(1.6f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        setMotionEventSplittingEnabled(false);
        setChildrenDrawingOrderEnabled(true);
    }

    private int topPage = -1;

    @Override
    protected int getChildDrawingOrder(int count, int i) {
        int t = topPage;
        if (t < 0 || t >= count) return i;
        if (i == count - 1) return t;
        return i < t ? i : i + 1;
    }

    private int restPage;

    private boolean underFirst;
    private final android.graphics.Rect clip = new android.graphics.Rect();

    public void setUnderFirst(boolean on) {
        if (underFirst == on) return;
        underFirst = on;
        stackPages();
    }

    private void stackPages() {
        int w = getWidth();
        if (w == 0) return;
        float pos = (float) getScrollX() / w;
        int left = (int) Math.floor(pos);
        float p = pos - left;
        if (getChildCount() > 0) {
            View first = getChildAt(0);
            boolean under = underFirst && left == 0 && p > 0;
            first.setTranslationX(under ? p * w : 0);
            if (under) {
                clip.set(0, 0, Math.round(w - p * w), first.getHeight());
                first.setClipBounds(clip);
            } else if (first.getClipBounds() != null) {
                first.setClipBounds(null);
            }
        }
        if (p == 0 && left >= 0) restPage = left;
        boolean stacked = p > 0 && left >= 0 && left + 1 < getChildCount()
                && stacks(getChildAt(left)) && stacks(getChildAt(left + 1));
        int old = -1, top = -1;
        boolean oldFrames = false;
        if (stacked) {
            old = restPage == left + 1 ? left + 1 : left;
            int incoming = old == left ? left + 1 : left;
            oldFrames = getChildAt(old) instanceof ActiveFramesPage;
            top = oldFrames ? incoming : old;
        }
        if (top != topPage) {
            topPage = top;
            invalidate();
        }
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (i == 0 && underFirst) continue;
            float tx = 0, a = 1;
            if (stacked && i == old) {
                if (oldFrames) {
                    a = 1 - Math.abs(pos - old);
                    tx = (pos - old) * w * 0.245f;
                }
            } else if (stacked && (i == left || i == left + 1)) {
                float g = 1 - Math.abs(pos - i);
                tx = oldFrames ? 0 : (pos - i) * w * 0.31f;
                a = oldFrames ? 1 : g * (float) Math.sqrt(g);
            }
            boolean moving = tx != 0 || a < 1;
            if (c.getTranslationX() != tx) c.setTranslationX(tx);
            if (c instanceof ActiveFramesPage) {
                ((ActiveFramesPage) c).setPageFade(a);
                if (c.getAlpha() != 1f) c.setAlpha(1f);
            } else if (c.getAlpha() != a) c.setAlpha(a);
            int layer = moving && !(c instanceof ActiveFramesPage) ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (c.getLayerType() != layer) c.setLayerType(layer, null);
        }
    }

    private static boolean stacks(View v) {
        return v instanceof AppGridPage || v instanceof ActiveFramesPage;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        stackPages();
    }

    public void setListener(Listener l) { listener = l; }

    public int getCurrentPage() { return current; }

    public boolean isSettled() { return !dragging && scroller.isFinished(); }

    public View getCurrentView() { return current < getChildCount() ? getChildAt(current) : null; }

    public void setCurrentPage(int page, boolean animate) {
        page = Math.max(0, Math.min(page, getChildCount() - 1));
        int w = getWidth();
        boolean changed = page != current;
        current = page;
        if (!animate || w == 0) {
            scroller.forceFinished(true);
            scrollTo(page * w, 0);
        } else {
            int dx = page * w - getScrollX();
            scroller.startScroll(getScrollX(), 0, dx, 0, Math.min(420, 200 + Math.abs(dx) * 160 / Math.max(1, w)));
            postInvalidateOnAnimation();
        }
        if (changed && listener != null) listener.onPageChanged(page);
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        View v = getCurrentView();
        if (v != null) v.addFocusables(views, direction, focusableMode);
    }

    public View tallPage;
    public int tallExtra;

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(cw, c == tallPage ? MeasureSpec.makeMeasureSpec(h + tallExtra, MeasureSpec.EXACTLY) : ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(i * w, 0, (i + 1) * w, c == tallPage ? h + tallExtra : h);
        }
        if (scroller.isFinished() && !dragging) scrollTo(current * w, 0);
        stackPages();
    }

    private boolean shouldStartDrag(MotionEvent ev) {
        float dx = Math.abs(ev.getX() - downX), dy = Math.abs(ev.getY() - downY);
        return dx > touchSlop && dx > dy * 1.3f;
    }

    private void startDrag(MotionEvent ev) {
        dragging = true;
        lastX = ev.getX();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void track(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
    }

    private void recycle() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recycle();
                track(ev);
                downX = lastX = ev.getX();
                downY = ev.getY();
                pull = -1f;
                dragging = !scroller.isFinished();
                if (dragging) scroller.abortAnimation();
                break;
            case MotionEvent.ACTION_MOVE:
                track(ev);
                if (!dragging && shouldStartDrag(ev)) startDrag(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) recycle();
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    dragging = true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && shouldStartDrag(ev)) startDrag(ev);
                if (dragging) {
                    float dx = lastX - ev.getX();
                    lastX = ev.getX();
                    int max = Math.max(0, (getChildCount() - 1) * getWidth());
                    int sx = getScrollX();
                    float base = pull < 0f && current == 0 && listener != null && listener.pullsLeft() ? listener.pulled() : 0f;
                    if (pull >= 0f || base > 0f || (sx + dx < 0 && current == 0 && listener != null && listener.pullsLeft())) {
                        if (pull < 0f) pull = base > 0f ? Math.max(0f, base - dx) : Math.max(0f, -(sx + dx));
                        else pull = Math.max(0f, pull - dx);
                        if (sx != 0) scrollTo(0, 0);
                        listener.onPullLeft(pull);
                        return true;
                    }
                    if (sx + dx < 0 || sx + dx > max) dx *= 0.4f;
                    scrollTo(Math.round(sx + dx), 0);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && pull >= 0f) {
                    vt.computeCurrentVelocity(1000);
                    listener.onPullLeftEnd(pull, vt.getXVelocity());
                    pull = -1f;
                    dragging = false;
                    setCurrentPage(current, true);
                    recycle();
                    return true;
                }
                if (dragging) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getXVelocity();
                    int w = Math.max(1, getWidth());
                    int sx = getScrollX();
                    int target;
                    if (sx < -w * 0.12f && current == 0) {
                        target = 0;
                        if (listener != null) listener.onOverscrollLeft();
                    } else {
                        float moved = (sx - restPage * w) / (float) w;
                        boolean flick = Math.abs(v) > minFling && Math.abs(moved) > 0.12f && (v < 0) == (moved > 0);
                        if (Math.abs(moved) >= 1f) target = Math.round((float) sx / w);
                        else if (flick || Math.abs(moved) > 0.4f) target = restPage + (moved > 0 ? 1 : -1);
                        else target = restPage;
                    }
                    dragging = false;
                    setCurrentPage(target, true);
                }
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (pull >= 0f) {
                    listener.onPullLeftEnd(pull, 0f);
                    pull = -1f;
                }
                if (dragging) {
                    dragging = false;
                    setCurrentPage(current, true);
                }
                recycle();
                return true;
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }
}
