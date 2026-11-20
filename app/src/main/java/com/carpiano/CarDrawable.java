package com.carpiano;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Top-down outline of a car, nose at the top, used as the backdrop for the seat picker. */
final class CarDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stroke;

    CarDrawable(Context c) {
        stroke = Ui.dp(c, 3);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.SURF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setColor(Ui.LINE);
        glass.setStyle(Paint.Style.FILL);
        glass.setColor(Ui.RAISE);
        lamp.setStyle(Paint.Style.STROKE);
        lamp.setStrokeWidth(stroke * 1.6f);
        lamp.setStrokeCap(Paint.Cap.ROUND);
        lamp.setColor(Ui.DIM);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        float x0 = b.left;
        float y0 = b.top;
        float side = w * 0.08f;

        // Mirrors
        RectF mirror = new RectF();
        mirror.set(x0, y0 + h * 0.275f, x0 + side * 1.4f, y0 + h * 0.315f);
        canvas.drawRoundRect(mirror, stroke * 2, stroke * 2, fill);
        canvas.drawRoundRect(mirror, stroke * 2, stroke * 2, line);
        mirror.set(x0 + w - side * 1.4f, y0 + h * 0.275f, x0 + w, y0 + h * 0.315f);
        canvas.drawRoundRect(mirror, stroke * 2, stroke * 2, fill);
        canvas.drawRoundRect(mirror, stroke * 2, stroke * 2, line);

        // Body
        RectF body = new RectF(x0 + side, y0 + stroke, x0 + w - side, y0 + h - stroke);
        float r = body.width() * 0.30f;
        canvas.drawRoundRect(body, r, r, fill);
        canvas.drawRoundRect(body, r, r, line);

        float bl = body.left;
        float bw = body.width();

        // Windscreen
        Path ws = new Path();
        ws.moveTo(bl + bw * 0.14f, y0 + h * 0.255f);
        ws.quadTo(bl + bw * 0.5f, y0 + h * 0.165f, bl + bw * 0.86f, y0 + h * 0.255f);
        ws.lineTo(bl + bw * 0.80f, y0 + h * 0.295f);
        ws.quadTo(bl + bw * 0.5f, y0 + h * 0.235f, bl + bw * 0.20f, y0 + h * 0.295f);
        ws.close();
        canvas.drawPath(ws, glass);

        // Rear window
        Path rw = new Path();
        rw.moveTo(bl + bw * 0.20f, y0 + h * 0.865f);
        rw.quadTo(bl + bw * 0.5f, y0 + h * 0.905f, bl + bw * 0.80f, y0 + h * 0.865f);
        rw.lineTo(bl + bw * 0.84f, y0 + h * 0.895f);
        rw.quadTo(bl + bw * 0.5f, y0 + h * 0.955f, bl + bw * 0.16f, y0 + h * 0.895f);
        rw.close();
        canvas.drawPath(rw, glass);

        // Headlights
        canvas.drawLine(bl + bw * 0.16f, y0 + h * 0.045f, bl + bw * 0.30f, y0 + h * 0.028f, lamp);
        canvas.drawLine(bl + bw * 0.84f, y0 + h * 0.045f, bl + bw * 0.70f, y0 + h * 0.028f, lamp);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
