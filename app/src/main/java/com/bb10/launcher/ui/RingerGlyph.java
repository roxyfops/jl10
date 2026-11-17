package com.bb10.launcher.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;

public final class RingerGlyph extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int mode;

    public RingerGlyph(int mode, int color) {
        this.mode = mode;
        p.setColor(color);
    }

    public void setMode(int mode) {
        this.mode = mode;
        invalidateSelf();
    }

    public static void draw(Canvas c, Paint paint, Path path, RectF r, int mode, float cx, float cy, float size) {
        if (mode == AudioManager.RINGER_MODE_NORMAL) return;
        float u = size;
        Paint.Style style = paint.getStyle();
        float stroke = paint.getStrokeWidth();
        Paint.Cap cap = paint.getStrokeCap();
        Paint.Join join = paint.getStrokeJoin();
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.075f * u);
            r.set(cx - 0.16f * u, cy - 0.32f * u, cx + 0.16f * u, cy + 0.32f * u);
            c.drawRoundRect(r, 0.06f * u, 0.06f * u, paint);
            paint.setStrokeWidth(0.065f * u);
            for (int side = -1; side <= 1; side += 2) {
                float a = cx + side * 0.29f * u, b = cx + side * 0.41f * u;
                path.rewind();
                path.moveTo(a, cy - 0.21f * u);
                path.lineTo(b, cy - 0.105f * u);
                path.lineTo(a, cy);
                path.lineTo(b, cy + 0.105f * u);
                path.lineTo(a, cy + 0.21f * u);
                c.drawPath(path, paint);
            }
        } else {
            paint.setStyle(Paint.Style.FILL);
            path.rewind();
            r.set(cx - 0.25f * u, cy - 0.36f * u, cx + 0.25f * u, cy + 0.14f * u);
            path.arcTo(r, 180, 180);
            path.lineTo(cx + 0.25f * u, cy + 0.14f * u);
            path.lineTo(cx + 0.36f * u, cy + 0.25f * u);
            path.lineTo(cx - 0.36f * u, cy + 0.25f * u);
            path.lineTo(cx - 0.25f * u, cy + 0.14f * u);
            path.close();
            c.drawPath(path, paint);
            c.drawCircle(cx, cy + 0.33f * u, 0.075f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.08f * u);
            c.drawLine(cx - 0.42f * u, cy - 0.42f * u, cx + 0.42f * u, cy + 0.42f * u, paint);
        }
        paint.setStyle(style);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(cap);
        paint.setStrokeJoin(join);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        draw(c, p, path, r, mode, b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()));
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
