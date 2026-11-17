package com.bb10.launcher.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.bb10.launcher.model.GridItem;

public class IconView extends View {
    private static final int[] sLoc = new int[2];

    public static boolean onScreen(View v) {
        if (!v.isShown() || v.getWidth() == 0) return false;
        View root = v.getRootView();
        v.getLocationInWindow(sLoc);
        return sLoc[0] < root.getWidth() && sLoc[0] + v.getWidth() > 0 && sLoc[1] < root.getHeight() && sLoc[1] + v.getHeight() > 0;
    }

    public interface DragStarter {
        void startDrag(IconView v, float rawX, float rawY);
    }

    public GridItem item;
    public int index = -1;
    private Drawable icon;
    private Drawable[] minis;
    private String label = "";
    private int badge;
    private boolean edit, armed, showLabel = true, downClose;
    private float downX, downY;
    private final int slop;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF iconRect = new RectF(), closeRect = new RectF();
    private ObjectAnimator wiggle;
    private DragStarter dragStarter;
    private float labelSp = 12;
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF shadowRect = new RectF();
    private static final int ICON_SHADOW = 0x8C000000;

    public IconView(Context c) {
        super(c);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setDefaultFocusHighlightEnabled(false);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        tp.setColor(Ui.TEXT);
        tp.setTypeface(Ui.regular());
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), 0xDD000000);
        setOnTouchListener(this::onTouchForDrag);
    }

    public void bindApp(GridItem g, int idx, String label, Drawable icon, int badge) {
        item = g;
        index = idx;
        this.label = label;
        this.icon = icon;
        this.badge = badge;
        setContentDescription(label);
        invalidate();
    }

    public void bindFolder(GridItem g, int idx, Drawable[] minis, int badge) {
        item = g;
        index = idx;
        this.label = g.name;
        this.minis = minis;
        this.badge = badge;
        setContentDescription(g.name);
        invalidate();
    }

    public void setDragStarter(DragStarter d) { dragStarter = d; }

    public void setShowLabel(boolean s) {
        showLabel = s;
        invalidate();
    }

    public void setLabelSize(float sp) { labelSp = sp; }

    public void setBadge(int b) {
        if (b != badge) {
            badge = b;
            invalidate();
        }
    }

    public void setEdit(boolean e) {
        if (edit == e) return;
        edit = e;
        if (e) {
            wiggle = ObjectAnimator.ofPropertyValuesHolder(this,
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 0.92f),
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 0.92f));
            wiggle.setDuration(650);
            wiggle.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.start();
            wiggle.setCurrentPlayTime(android.os.SystemClock.uptimeMillis() % 1300);
        } else {
            if (wiggle != null) wiggle.cancel();
            wiggle = null;
            setScaleX(1f);
            setScaleY(1f);
            setRotation(0);
        }
        invalidate();
    }

    public void armDrag() {
        armed = true;
        ViewParent vp = getParent();
        if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
    }

    public boolean downOnClose() { return downClose; }

    public Drawable icon() { return icon; }

    private boolean onTouchForDrag(View v, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                armed = false;
                downClose = edit && closeRect.contains(downX, downY);
                if (edit && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if ((edit || armed) && !downClose && dragStarter != null
                        && Math.hypot(ev.getX() - downX, ev.getY() - downY) > slop) {
                    armed = false;
                    cancelLongPress();
                    setPressed(false);
                    dragStarter.startDrag(this, ev.getRawX(), ev.getRawY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                armed = false;
                break;
        }
        return false;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    private float mIcon, mIconCy, mLabel, mLabelBase, mLine;
    private StaticLayout labelLayout;
    private String laidOutLabel;
    private int laidOutWidth;

    public void setMetrics(float iconPx, float iconCenterY, float labelPx, float labelBaseline, float lineHeight) {
        mIcon = iconPx;
        mIconCy = iconCenterY;
        mLabel = labelPx;
        mLabelBase = labelBaseline;
        mLine = lineHeight;
        labelLayout = null;
        labelBitmap = null;
        styleGen = -1;
        invalidate();
    }

    private int styleGen = -1;

    private void applyStyle() {
        if (styleGen == Ui.homeGen) return;
        styleGen = Ui.homeGen;
        tp.setColor(Ui.homeFg());
        tp.setShadowLayer(Math.max(1f, Ui.s(0.004f)), 0, Ui.s(0.0018f), Ui.homeShadow(0xE6000000));
        labelLayout = null;
        labelBitmap = null;
    }

    private android.graphics.Bitmap labelBitmap;
    private float labelBitmapTop;

    private void drawTwoLineLabel(Canvas c, int w, float firstBaseline) {
        int avail = Math.max(1, w - Math.round(Ui.s(0.006f)));
        if (labelLayout == null || !label.equals(laidOutLabel) || avail != laidOutWidth) {
            labelLayout = StaticLayout.Builder.obtain(label, 0, label.length(), tp, avail)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .setLineSpacing(mLine - tp.getFontSpacing(), 1f)
                    .build();
            laidOutLabel = label;
            laidOutWidth = avail;
            labelBitmap = null;
        }
        if (labelBitmap == null && labelLayout.getHeight() > 0) {
            float halo = Math.max(1.5f, Ui.s(0.005f)), ow = Ui.outlineW();
            int pad = (int) Math.ceil(halo * 2 + ow * 2 + Ui.s(0.0018f)) + 1;
            labelBitmap = android.graphics.Bitmap.createBitmap(avail + pad * 2, labelLayout.getHeight() + pad * 2,
                    android.graphics.Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(labelBitmap);
            bc.translate(pad, pad);
            int fg = tp.getColor();
            tp.setShadowLayer(halo, 0, Ui.s(0.0015f), Ui.homeDark ? 0x80FFFFFF : 0x80000000);
            labelLayout.draw(bc);
            tp.clearShadowLayer();
            tp.setStyle(android.graphics.Paint.Style.STROKE);
            tp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            tp.setStrokeWidth(ow * 2);
            tp.setColor(Ui.homeOutline());
            labelLayout.draw(bc);
            tp.setStyle(android.graphics.Paint.Style.FILL);
            tp.setColor(fg);
            labelLayout.draw(bc);
            tp.setShadowLayer(Math.max(1f, Ui.s(0.004f)), 0, Ui.s(0.0018f), Ui.homeShadow(0xE6000000));
            labelBitmapTop = -pad;
        }
        float top = firstBaseline - labelLayout.getLineBaseline(0);
        if (labelBitmap != null && labelAlpha > 0f) {
            labelPaint.setAlpha(Math.round(255 * labelAlpha));
            c.drawBitmap(labelBitmap, Math.round((w - avail) / 2f + labelBitmapTop), Math.round(top + labelBitmapTop), labelAlpha < 1f ? labelPaint : null);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        applyStyle();
        int w = getWidth(), h = getHeight();
        boolean grid = mIcon > 0;
        float size;
        if (grid) {
            size = mIcon;
            iconRect.set((w - size) / 2f, mIconCy - size / 2f, (w + size) / 2f, mIconCy + size / 2f);
        } else {
            tp.setTextSize(Ui.sp(labelSp));
            float labelH = showLabel ? tp.getFontSpacing() : 0;
            float gap = showLabel ? Ui.dp(4) : 0;
            size = Math.min(w * 0.66f, (h - labelH - gap) * 0.86f);
            size = Math.min(size, Ui.dp(76));
            float left = (w - size) / 2f, top = (h - size - labelH - gap) / 2f;
            iconRect.set(left, top, left + size, top + size);
        }

        if (isFocused() && !isInTouchMode()) {
            p.setStyle(Paint.Style.FILL);
            if (grid) {
                p.setColor(0xB30A72BA);
                c.drawRect(0, 0, w, h, p);
            } else {
                RectF r = new RectF(Ui.dp(2), Ui.dp(2), w - Ui.dp(2), h - Ui.dp(2));
                p.setColor(Ui.BLUE_DIM);
                c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(2));
                p.setColor(Ui.BLUE);
                c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        if (item != null && item.type == GridItem.FOLDER) {
            if (!folderOpen) drawFolder(c, size);
        } else if (icon != null) {
            drawShadow(c, icon, size);
            icon.setBounds(Math.round(iconRect.left), Math.round(iconRect.top), Math.round(iconRect.right), Math.round(iconRect.bottom));
            icon.draw(c);
            if (icon instanceof com.bb10.launcher.icons.BB10Icons.Live)
                postInvalidateDelayed(onScreen(this) ? ((com.bb10.launcher.icons.BB10Icons.Live) icon).nextChangeIn() : 1000);
        }

        if (showLabel && grid) {
            tp.setTextSize(mLabel);
            tp.setTextAlign(Paint.Align.LEFT);
            drawTwoLineLabel(c, w, mIconCy + mLabelBase);
        } else if (showLabel) {
            tp.setTextAlign(Paint.Align.CENTER);
            float gap = Ui.dp(4);
            String t = TextUtils.ellipsize(label, tp, w - Ui.dp(4), TextUtils.TruncateAt.END).toString();
            c.drawText(t, w / 2f, iconRect.bottom + gap - tp.ascent(), tp);
        }

        if (badge > 0) Ui.splat(c, iconRect.right - size * 0.04f, iconRect.top + size * 0.04f, size * 0.15f, p);

        if (edit) {
            float r = Ui.dp(9);
            float cx = iconRect.left + r * 0.3f, cy = iconRect.top + r * 0.3f;
            closeRect.set(cx - r * 1.8f, cy - r * 1.8f, cx + r * 1.8f, cy + r * 1.8f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xEE1E1E1E);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1.5f));
            p.setColor(Ui.TEXT);
            c.drawCircle(cx, cy, r, p);
            float s = r * 0.42f;
            c.drawLine(cx - s, cy - s, cx + s, cy + s, p);
            c.drawLine(cx + s, cy - s, cx - s, cy + s, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            closeRect.setEmpty();
        }
    }

    private void drawShadow(Canvas c, Drawable shape, float size) {
        Shadows.Mask m = Shadows.icon(shape, this, com.bb10.launcher.LauncherApp.get().bg);
        if (m == null) return;
        float k = size / Shadows.ICON_REF;
        float l = iconRect.left + m.dx * k, t = iconRect.top + m.dy * k + size * Shadows.ICON_DROP;
        shadowRect.set(l, t, l + m.bmp.getWidth() * k, t + m.bmp.getHeight() * k);
        shadowPaint.setColor(ICON_SHADOW);
        c.drawBitmap(m.bmp, null, shadowRect, shadowPaint);
    }

    public static final int FOLDER_DIM = 0x50000000;
    private static final float MINI_PAD = 0.07f, MINI_GAP = 0.06f;
    private boolean folderOpen;
    private float labelAlpha = 1f;
    private final Paint labelPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void drawFolder(Canvas c, float size) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(FOLDER_DIM);
        p.setAntiAlias(false);
        c.drawRect(Math.round(iconRect.left), Math.round(iconRect.top), Math.round(iconRect.right), Math.round(iconRect.bottom), p);
        p.setAntiAlias(true);
        if (minis == null) return;
        RectF r = new RectF();
        for (int i = 0; i < 4 && i < minis.length; i++) {
            if (minis[i] == null || !miniRect(i, r)) continue;
            minis[i].setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            minis[i].draw(c);
        }
    }

    public void drawMinis(Canvas c, float dx, float dy, int alpha) {
        if (minis == null || alpha <= 0) return;
        RectF r = new RectF();
        for (int i = 0; i < 4 && i < minis.length; i++) {
            if (minis[i] == null || !miniRect(i, r)) continue;
            r.offset(dx, dy);
            int was = minis[i].getAlpha();
            minis[i].setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            minis[i].setAlpha(alpha);
            minis[i].draw(c);
            minis[i].setAlpha(was);
        }
    }

    public void setFolderOpen(boolean open) {
        if (folderOpen == open) return;
        folderOpen = open;
        invalidate();
    }

    public void setLabelAlpha(float a) {
        if (labelAlpha == a) return;
        labelAlpha = a;
        invalidate();
    }

    public void iconBounds(RectF out) {
        int w = getWidth(), h = getHeight();
        if (mIcon > 0) {
            out.set((w - mIcon) / 2f, mIconCy - mIcon / 2f, (w + mIcon) / 2f, mIconCy + mIcon / 2f);
        } else {
            tp.setTextSize(Ui.sp(labelSp));
            float labelH = showLabel ? tp.getFontSpacing() : 0, gap = showLabel ? Ui.dp(4) : 0;
            float size = Math.min(Math.min(w * 0.66f, (h - labelH - gap) * 0.86f), Ui.dp(76));
            float left = (w - size) / 2f, top = (h - size - labelH - gap) / 2f;
            out.set(left, top, left + size, top + size);
        }
    }

    public boolean miniRect(int i, RectF out) {
        if (i < 0 || i > 3) return false;
        RectF b = new RectF();
        iconBounds(b);
        float size = b.width(), pad = size * MINI_PAD, gap = size * MINI_GAP, cell = (size - pad * 2 - gap) / 2f;
        float l = b.left + pad + (i % 2) * (cell + gap), t = b.top + pad + (i / 2) * (cell + gap);
        out.set(l, t, l + cell, t + cell);
        return size > 0;
    }
}
