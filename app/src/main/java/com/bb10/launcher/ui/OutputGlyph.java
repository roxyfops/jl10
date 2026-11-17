package com.bb10.launcher.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

public final class OutputGlyph extends Drawable {
    public static final int BLUETOOTH = 1, HEADPHONES = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final int kind;
    private final boolean muted;

    public OutputGlyph(int kind, boolean muted, int color) {
        this.kind = kind;
        this.muted = muted;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float u = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
        if (kind == BLUETOOTH) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.07f * u);
            float top = cy - 0.36f * u, bot = cy + 0.36f * u, x = cx - 0.02f * u, w = 0.17f * u, l = cx - 0.2f * u;
            path.rewind();
            path.moveTo(l, cy - 0.18f * u);
            path.lineTo(x + w, cy + 0.18f * u);
            path.lineTo(x, bot);
            path.lineTo(x, top);
            path.lineTo(x + w, cy - 0.18f * u);
            path.lineTo(l, cy + 0.18f * u);
            c.drawPath(path, p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.075f * u);
            r.set(cx - 0.33f * u, cy - 0.34f * u, cx + 0.33f * u, cy + 0.32f * u);
            c.drawArc(r, 180, 180, false, p);
            c.drawLine(cx - 0.33f * u, cy, cx - 0.33f * u, cy + 0.12f * u, p);
            c.drawLine(cx + 0.33f * u, cy, cx + 0.33f * u, cy + 0.12f * u, p);
            p.setStyle(Paint.Style.FILL);
            for (int side = -1; side <= 1; side += 2) {
                float x0 = cx + side * 0.33f * u;
                r.set(Math.min(x0, x0 - side * 0.17f * u), cy + 0.02f * u, Math.max(x0, x0 - side * 0.17f * u), cy + 0.36f * u);
                r.inset(-0.035f * u, 0);
                c.drawRoundRect(r, 0.06f * u, 0.06f * u, p);
            }
        }
        if (muted) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.08f * u);
            c.drawLine(cx - 0.42f * u, cy - 0.42f * u, cx + 0.42f * u, cy + 0.42f * u, p);
        }
    }

    @Override
    public void setTint(int color) {
        p.setColor(color);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
