package com.carpiano;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons drawn on a 24-unit grid, so the widget needs no image files. */
final class IconDrawable extends Drawable {

    static final int MINUS = 0, EXPAND = 1, CHEVRON_DOWN = 2, MIC = 3, SPEAKER = 4, STOP = 5;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    IconDrawable(int kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float u = Math.min(b.width(), b.height()) / 24f;
        float ox = b.left + (b.width() - 24 * u) / 2f;
        float oy = b.top + (b.height() - 24 * u) / 2f;
        c.save();
        c.translate(ox, oy);
        c.scale(u, u);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        Path p = new Path();
        switch (kind) {
            case MINUS:
                c.drawLine(6, 12, 18, 12, paint);
                break;
            case EXPAND:
                p.moveTo(8, 3);
                p.lineTo(3, 3);
                p.lineTo(3, 8);
                p.moveTo(16, 3);
                p.lineTo(21, 3);
                p.lineTo(21, 8);
                p.moveTo(8, 21);
                p.lineTo(3, 21);
                p.lineTo(3, 16);
                p.moveTo(16, 21);
                p.lineTo(21, 21);
                p.lineTo(21, 16);
                c.drawPath(p, paint);
                break;
            case CHEVRON_DOWN:
                p.moveTo(6, 9);
                p.lineTo(12, 15);
                p.lineTo(18, 9);
                c.drawPath(p, paint);
                break;
            case MIC:
                c.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, paint);
                c.drawArc(new RectF(5, 4, 19, 18), 0, 180, false, paint);
                c.drawLine(12, 18, 12, 21, paint);
                break;
            case SPEAKER:
                p.moveTo(4, 9);
                p.lineTo(4, 15);
                p.lineTo(8, 15);
                p.lineTo(13, 19);
                p.lineTo(13, 5);
                p.lineTo(8, 9);
                p.close();
                c.drawPath(p, paint);
                c.drawArc(new RectF(11, 8, 18, 16), -55, 110, false, paint);
                break;
            default:
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(6, 6, 18, 18), 2, 2, paint);
                break;
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
