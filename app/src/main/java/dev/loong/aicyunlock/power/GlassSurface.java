package dev.loong.aicyunlock.power;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid-glass surface, painted straight into an arbitrary path.
 *
 * <p>Taking the path rather than a rectangle is the whole point: the card body
 * and the glow around it are then driven by the exact same outline.
 *
 * <p>The body is deliberately close to opaque. A truly translucent slab over
 * scrolling chat text reads as dirty noise rather than glass, because the text
 * behind shows through at uneven opacity. Depth comes from the light instead: a
 * sheen along the top edge, a wide diagonal reflection, a bloom of the accent
 * colour leaking in from the lower right, and a bright inner rim.
 */
final class GlassSurface {

    private GlassSurface() {
    }

    static void draw(Canvas canvas, Path path, RectF box, float density, int accent) {
        if (box.isEmpty()) return;
        final float l = box.left;
        final float t = box.top;
        final float r = box.right;
        final float b = box.bottom;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);

        // 1. Body. Near opaque, only a whisper of a gradient: this is what keeps
        //    the chat text behind from turning into grain.
        paint.setShader(new LinearGradient(l, t, l, b,
                new int[]{0xFB282A34, 0xFA1B1D25, 0xFC101118},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);

        // 2. Grazing light along the top edge.
        paint.setShader(new LinearGradient(l, t, l, t + (b - t) * 0.34f,
                0x2AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);

        // 3. Wide, very faint diagonal sheen.
        paint.setShader(new LinearGradient(l, t, r, b,
                new int[]{0x14FFFFFF, 0x00FFFFFF, 0x08FFFFFF},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);

        // 4. Accent light leaking through the lower right, so the glass picks up
        //    the colour of the thing it belongs to.
        paint.setShader(new RadialGradient(
                r - (r - l) * 0.12f, b - (b - t) * 0.05f,
                Math.max(1f, (r - l) * 0.48f),
                withAlpha(accent, 42), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        final float cx = (l + r) / 2f;
        final float cy = (t + b) / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * 1.05f));

        // 5. Inner rim: bright at the top, almost gone at the bottom. This is
        //    what makes the slab read as thick instead of flat.
        paint.setShader(new LinearGradient(l, t, l, b,
                new int[]{0xBBFFFFFF, 0x2AFFFFFF, 0x10FFFFFF},
                new float[]{0f, 0.34f, 1f}, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.scale(0.9986f, 0.9972f, cx, cy);
        canvas.drawPath(path, paint);
        canvas.restore();
        paint.setShader(null);

        // 5b. A brighter hairline right on the top edge, clipped to the top
        //     fifth, so the light appears to sit *on* the glass.
        paint.setStrokeWidth(Math.max(1f, density * 0.85f));
        paint.setShader(new LinearGradient(l, t, l, t + (b - t) * 0.18f,
                0xE0FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.clipRect(l, t, r, t + (b - t) * 0.22f);
        canvas.drawPath(path, paint);
        canvas.restore();
        paint.setShader(null);

        // 6. Dark line just inside, the rim's own shadow.
        paint.setColor(0x30000000);
        canvas.save();
        canvas.scale(0.9905f, 0.9875f, cx, cy);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
