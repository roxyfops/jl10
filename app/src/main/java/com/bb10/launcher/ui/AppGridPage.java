package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

public class AppGridPage extends ViewGroup {
    public static final int NONE = 0, INSERT = 1, MERGE = 2;

    static final float COL_PITCH = 0.194f, ROW_PITCH = 0.2465f, FIRST_ROW = 0.113f;
    static final float ICON = 0.133f, LABEL = 0.034f, LABEL_BASE = 0.111f, LINE = 0.036f;
    static final float CELL_W = 0.185f, CELL_H = 0.23f, CELL_ABOVE_ICON = 0.0785f;

    public final int start;
    private final int cols, rows;
    private int hoverMode = NONE, hoverTarget = -1;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AppGridPage(Context c, int start, int cols, int rows) {
        super(c);
        this.start = start;
        this.cols = cols;
        this.rows = rows;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    private float colPitch() { return getWidth() * 0.97f / cols; }

    private float rowPitch() { return ROW_PITCH * Ui.S() * 3f / rows; }

    private float k() {
        float S = Ui.S();
        return Math.min(colPitch() / (COL_PITCH * S), rowPitch() / (ROW_PITCH * S));
    }

    private float colCenter(int col) { return getWidth() / 2f + (col - (cols - 1) / 2f) * colPitch(); }

    private float rowIconCenter(int row) { return FIRST_ROW * Ui.S() * (rowPitch() / (ROW_PITCH * Ui.S())) + row * rowPitch(); }

    public void styleIcon(IconView v) {
        if (getWidth() == 0) return;
        float S = Ui.S(), k = k();
        v.setMetrics(ICON * S * k, CELL_ABOVE_ICON * S * k, LABEL * S * k, LABEL_BASE * S * k, LINE * S * k);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof IconView) styleIcon((IconView) child);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof IconView) styleIcon((IconView) getChildAt(i));
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        setMeasuredDimension(w, h);
        float S = Ui.S();
        float kx = (w * 0.97f / cols) / (COL_PITCH * S), ky = 3f / rows;
        int cw = Math.round(CELL_W * S * kx), ch = Math.round(CELL_H * S * ky);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float above = CELL_ABOVE_ICON * Ui.S() * k();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int col = i % cols, row = i / cols;
            int x = Math.round(colCenter(col) - v.getMeasuredWidth() / 2f);
            int y = Math.round(rowIconCenter(row) - above);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    public void hover(float x, float y, int dragGlobal, boolean dragIsApp) {
        float cp = colPitch(), rp = rowPitch();
        float left0 = colCenter(0) - cp / 2f, top0 = rowIconCenter(0) - rp * 0.4f;
        int col = clamp((int) Math.floor((x - left0) / cp), 0, cols - 1);
        int row = clamp((int) Math.floor((y - top0) / rp), 0, rows - 1);
        int local = row * cols + col, n = getChildCount();
        float fx = (x - left0 - col * cp) / cp, fy = (y - top0 - row * rp) / rp;
        int mode, target;
        if (local < n) {
            int global = start + local;
            if (dragIsApp && global != dragGlobal && fx > 0.22f && fx < 0.78f && fy > 0.1f && fy < 0.72f) {
                mode = MERGE;
                target = local;
            } else {
                mode = INSERT;
                target = fx < 0.5f ? local : local + 1;
            }
        } else {
            mode = INSERT;
            target = n;
        }
        if (mode != hoverMode || target != hoverTarget) {
            hoverMode = mode;
            hoverTarget = target;
            invalidate();
        }
    }

    public int dropMode() { return hoverMode; }

    public int dropGlobal() { return start + hoverTarget; }

    public void clearHover() {
        hoverMode = NONE;
        hoverTarget = -1;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (hoverMode == NONE) return;
        float S = Ui.S(), k = k();
        float cw = CELL_W * S * k, above = CELL_ABOVE_ICON * S * k, ch = CELL_H * S * k;
        if (hoverMode == MERGE) {
            int col = hoverTarget % cols, row = hoverTarget / cols;
            float cx = colCenter(col), top = rowIconCenter(row) - above;
            RectF r = new RectF(cx - cw / 2, top, cx + cw / 2, top + ch);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x4D00A8DF);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(2));
            p.setColor(Ui.BLUE);
            c.drawRect(r, p);
        } else {
            int n = getChildCount();
            float x;
            int row;
            if (hoverTarget < n || n == 0) {
                int idx = Math.min(Math.max(0, hoverTarget), cols * rows - 1);
                row = idx / cols;
                x = colCenter(idx % cols) - colPitch() / 2f;
            } else {
                int idx = n - 1;
                row = idx / cols;
                x = colCenter(idx % cols) + colPitch() / 2f;
            }
            float icon = ICON * S * k;
            float top = rowIconCenter(row) - icon / 2, bot = rowIconCenter(row) + icon / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.BLUE);
            c.drawRect(x - Ui.dp(2), top, x + Ui.dp(2), bot, p);
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
