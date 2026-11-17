package com.bb10.launcher.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.bb10.launcher.LauncherActivity;
import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.model.AppInfo;
import com.bb10.launcher.model.GridItem;

import java.util.ArrayList;
import java.util.List;

public class FolderOverlay extends FrameLayout implements Closable {
    private static final int DIM = 0x88, TILE_DIM = IconView.FOLDER_DIM >>> 24;
    private static final float BAR_H = 0.12f, NAME = 0.05f;

    private final LauncherActivity act;
    public final GridItem folder;
    private final IconView source;
    private final FrameLayout pagesBox;
    private final List<AppGridPage> pages = new ArrayList<>();
    private final Bar bar;
    private final TextView title;
    private final Paint p = new Paint();
    private final Rect area = new Rect();
    private final RectF tile = new RectF(), bg = new RectF(), r1 = new RectF(), r2 = new RectF();
    private final int[] loc = new int[2], loc2 = new int[2];
    private ValueAnimator anim;
    private float t;
    private boolean closing;
    private int page;
    private float dragX;

    public FolderOverlay(LauncherActivity a, GridItem folder, IconView source) {
        super(a);
        act = a;
        this.folder = folder;
        this.source = source;
        setWillNotDraw(false);
        setClipChildren(false);
        setClickable(true);
        setOnClickListener(v -> close());

        pagesBox = new FrameLayout(a);
        pagesBox.setClipChildren(false);
        addView(pagesBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        bar = new Bar(a);
        title = new TextView(a);
        title.setText(folder.name);
        title.setOnClickListener(v -> act.renameFolder(folder, () -> {
            title.setText(folder.name);
            bar.invalidate();
        }));
        LayoutParams tl = new LayoutParams(Math.round(Ui.s(0.5f)), LayoutParams.MATCH_PARENT, Gravity.END);
        title.setAlpha(0f);
        bar.addView(title, tl);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(Ui.s(BAR_H)), Gravity.BOTTOM));

        if (source != null) source.setFolderOpen(true);
        refresh();
        setProgress(0f);
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                setProgress(0f);
                animateTo(1f, 300, null);
                return true;
            }
        });
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        act.gridArea(this, area);
        if (changed) {
            getLocationInWindow(loc2);
            ViewGroup pg = (ViewGroup) getParent();
            android.util.Log.i("BB10Folder", "at y " + loc2[1] + " h " + getHeight() + ", layer padding " + pg.getPaddingTop()
                    + " clipToPadding " + pg.getClipToPadding() + " clipChildren " + pg.getClipChildren());
        }
        pagesBox.measure(MeasureSpec.makeMeasureSpec(area.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(area.height(), MeasureSpec.EXACTLY));
        pagesBox.layout(area.left, area.top, area.right, area.bottom);
        placePages();
    }

    public void refresh() {
        title.setText(folder.name);
        pagesBox.removeAllViews();
        pages.clear();
        LauncherApp app = LauncherApp.get();
        boolean labels = app.prefs.bool(com.bb10.launcher.Prefs.SHOW_LABELS, true);
        int cols = act.gridCols(), rows = act.gridRows(), per = cols * rows, n = 0;
        AppGridPage g = null;
        for (String key : folder.apps) {
            AppInfo ai = app.apps.app(key);
            if (ai == null) continue;
            if (n % per == 0) {
                g = new AppGridPage(getContext(), n, cols, rows);
                pages.add(g);
                pagesBox.addView(g, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            IconView v = new IconView(getContext());
            v.bindApp(GridItem.app(key), n, ai.label(), app.icons.get(ai), app.hub.unread(ai.pkg));
            v.setShowLabel(labels);
            v.setOnClickListener(x -> {
                act.launchApp(key, v);
                postDelayed(this::removeNow, 600);
            });
            v.setOnLongClickListener(x -> {
                act.showItemOptions(GridItem.app(key), folder);
                return true;
            });
            g.addView(v);
            n++;
        }
        if (pages.isEmpty()) {
            pages.add(g = new AppGridPage(getContext(), 0, cols, rows));
            pagesBox.addView(g, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        page = Math.min(page, pages.size() - 1);
        placePages();
        if (!isInTouchMode() && pages.get(0).getChildCount() > 0) pages.get(0).getChildAt(0).post(() -> {
            if (pages.get(0).getChildCount() > 0) pages.get(0).getChildAt(0).requestFocus();
        });
        bar.invalidate();
    }

    private void animateTo(float to, long ms, Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(t, to);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(va -> setProgress((float) va.getAnimatedValue()));
        if (end != null) anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator x) { cancelled = true; }

            @Override
            public void onAnimationEnd(android.animation.Animator x) { if (!cancelled) end.run(); }
        });
        anim.start();
    }

    private boolean tileRect(RectF out) {
        if (source == null || !source.isAttachedToWindow() || getWidth() == 0) return false;
        source.getLocationInWindow(loc);
        getLocationInWindow(loc2);
        source.iconBounds(out);
        out.offset(loc[0] - loc2[0], loc[1] - loc2[1]);
        return out.width() > 0;
    }

    private void setProgress(float k) {
        t = k;
        boolean fromTile = tileRect(tile);
        if (!fromTile) {
            float w = getWidth() * 0.15f, h = getHeight() * 0.15f;
            tile.set(getWidth() / 2f - w / 2, getHeight() / 2f - h / 2, getWidth() / 2f + w / 2, getHeight() / 2f + h / 2);
        }
        bg.set(lerp(tile.left, 0, k), lerp(tile.top, 0, k), lerp(tile.right, getWidth(), k), lerp(tile.bottom, getHeight(), k));
        bar.setAlpha(k);
        act.fadeHome(1f - k);
        float zs = bg.width() / Math.max(1f, getWidth());
        getLocationInWindow(loc2);
        for (int pi = 0; pi < pages.size(); pi++) {
            AppGridPage g = pages.get(pi);
            for (int i = 0; i < g.getChildCount(); i++) {
                IconView v = (IconView) g.getChildAt(i);
                if (v.getWidth() == 0) continue;
                v.setPivotX(0);
                v.setPivotY(0);
                v.getLocationInWindow(loc);
                float vx = loc[0] - loc2[0] - v.getTranslationX(), vy = loc[1] - loc2[1] - v.getTranslationY();
                v.iconBounds(r2);
                float ex = vx + r2.left, ey = vy + r2.top, es = r2.width();
                boolean mini = false;
                if (mini) {
                    source.getLocationInWindow(loc);
                    r1.offset(loc[0] - loc2[0], loc[1] - loc2[1]);
                } else {
                    r1.set(bg.left + ex * zs, bg.top + ey * zs, bg.left + (ex + es) * zs, bg.top + (ey + es) * zs);
                }
                float cx = lerp(r1.left, ex, k), cy = lerp(r1.top, ey, k), cs = lerp(r1.width(), es, k);
                float s = cs / Math.max(1f, es);
                v.setScaleX(s);
                v.setScaleY(s);
                v.setTranslationX(cx - vx - r2.left * s);
                v.setTranslationY(cy - vy - r2.top * s);
                v.setLabelAlpha(k * k);
                v.setAlpha(mini ? 1f : k);
            }
        }
        invalidate();
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

    private final int[] onScreen = new int[2];

    @Override
    protected void onDraw(Canvas c) {
        p.setColor(Math.round(lerp(TILE_DIM, DIM, t)) << 24);
        c.drawRect(bg, p);
        if (t < 1f && source != null && source.isAttachedToWindow()) {
            source.getLocationInWindow(loc);
            getLocationInWindow(loc2);
            source.drawMinis(c, loc[0] - loc2[0], loc[1] - loc2[1], Math.round(255 * (1f - t) * (1f - t)));
        }
        StatusBarView sb = ownBar();
        if (sb != null) {
            int[] at = new int[2];
            getLocationOnScreen(onScreen);
            sb.getLocationOnScreen(at);
            float ground = sb.groundAlpha;
            sb.groundAlpha = 0f;
            int s = c.save();
            c.translate(at[0] - onScreen[0], at[1] - onScreen[1]);
            sb.draw(c);
            c.restoreToCount(s);
            sb.groundAlpha = ground;
        }
    }

    private StatusBarView ownBar() {
        if (!(getParent() instanceof ViewGroup) || ((ViewGroup) getParent()).getPaddingTop() != 0) return null;
        return findBar(getRootView());
    }

    private static StatusBarView findBar(View v) {
        if (v instanceof StatusBarView) return v.isShown() && v.getAlpha() > 0.5f ? (StatusBarView) v : null;
        if (v instanceof FolderOverlay || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            StatusBarView s = findBar(g.getChildAt(i));
            if (s != null) return s;
        }
        return null;
    }

    @Override
    public void close() {
        if (closing) return;
        closing = true;
        setClickable(false);
        if (page != 0) {
            page = 0;
            placePages();
        }
        animateTo(0f, 250, this::removeNow);
    }

    private void removeNow() {
        if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getParent() instanceof ViewGroup && getLayoutParams() instanceof MarginLayoutParams) {
            ViewGroup parent = (ViewGroup) getParent();
            ((MarginLayoutParams) getLayoutParams()).topMargin = -parent.getPaddingTop();
            requestLayout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
        if (source != null) source.setFolderOpen(false);
        act.fadeHome(1f);
    }

    private void placePages() {
        int w = area.width();
        for (int i = 0; i < pages.size(); i++) pages.get(i).setTranslationX((i - page) * w + dragX);
        bar.invalidate();
    }

    private float downX, downY;
    private boolean swiping;
    private VelocityTracker vt;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (anim != null && anim.isRunning()) return true;
        track(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            swiping = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && pages.size() > 1) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (Math.abs(dx) > ViewConfiguration.get(getContext()).getScaledTouchSlop() && Math.abs(dx) > Math.abs(dy)) {
                swiping = true;
                downX = e.getX();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (anim != null && anim.isRunning()) return true;
        if (!swiping) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) track(e);
            return super.onTouchEvent(e);
        }
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                dragX = e.getX() - downX;
                if ((page == 0 && dragX > 0) || (page == pages.size() - 1 && dragX < 0)) dragX *= 0.3f;
                placePages();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                vt.computeCurrentVelocity(1000);
                float v = vt.getXVelocity(), w = area.width();
                int to = page;
                if (dragX < -w / 3 || v < -Ui.dp(600)) to = page + 1;
                else if (dragX > w / 3 || v > Ui.dp(600)) to = page - 1;
                to = Math.max(0, Math.min(pages.size() - 1, to));
                float from = dragX + (page - to) * w;
                page = to;
                ValueAnimator va = ValueAnimator.ofFloat(from, 0);
                va.setDuration(220);
                va.setInterpolator(new DecelerateInterpolator());
                va.addUpdateListener(x -> {
                    dragX = (float) x.getAnimatedValue();
                    placePages();
                });
                va.start();
                swiping = false;
                vt.recycle();
                vt = null;
                break;
        }
        return true;
    }

    private void track(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && vt != null) {
            vt.recycle();
            vt = null;
        }
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    private class Bar extends FrameLayout {
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint name = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Rect nb = new Rect();

        Bar(android.content.Context c) {
            super(c);
            setWillNotDraw(false);
            name.setColor(Ui.TEXT);
            name.setTypeface(Ui.regular());
            name.setTextAlign(Paint.Align.RIGHT);
            num.setColor(0xFF000000);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(Typeface.create(Ui.bold(), Typeface.BOLD));
        }

        @Override
        protected void onDraw(Canvas c) {
            float S = Ui.S(), h = getHeight();
            bp.setColor(0x48000000);
            c.drawRect(0, 0, getWidth(), h, bp);
            bp.setColor(0x30000000);
            c.drawRect(0, 0, getWidth(), Math.max(1f, S * 0.003f), bp);
            float cx = S * 0.05f, cy = h / 2f, r = S * 0.036f / 2f;
            name.setTextSize(NAME * S);
            String fn = TextUtils.ellipsize(folder.name, name, getWidth() - S * 0.14f, TextUtils.TruncateAt.END).toString();
            name.getTextBounds("F", 0, 1, nb);
            c.drawText(fn, getWidth() - S * 0.02f, cy - nb.exactCenterY(), name);
            bp.setColor(Ui.homeOutline());
            c.drawCircle(cx, cy + Ui.outlineW() * 0.6f, r + Ui.outlineW(), bp);
            bp.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r, bp);
            num.setTextSize(0.025f * S);
            String s = String.valueOf(page + 1);
            num.getTextBounds(s, 0, s.length(), nb);
            c.drawText(s, cx, cy - nb.exactCenterY(), num);
        }
    }
}
