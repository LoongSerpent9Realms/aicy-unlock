package dev.loong.aicyunlock.power;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Just the power glyph, sized to sit inline with a row of labels.
 *
 * <p>Drawn instead of using \u23fb, which is missing from several system fonts
 * and renders as a blank box.
 */
public final class PowerGlyph extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arc = new Path();

    public PowerGlyph(Context context, int color) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.34f;

        paint.setStrokeWidth(Math.max(1.5f, Math.min(w, h) * 0.11f));
        arc.reset();
        arc.addArc(cx - r, cy - r * 0.86f, cx + r, cy + r * 1.14f, -212f, 244f);
        canvas.drawPath(arc, paint);
        canvas.drawLine(cx, cy - r * 1.46f, cx, cy - r * 0.16f, paint);
    }
}
