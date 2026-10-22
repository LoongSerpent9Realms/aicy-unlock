package dev.loong.aicyunlock.power;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Glowing rim around the confirmation card.
 *
 * <p>No travelling band and no size change: the outline stays put and simply
 * breathes in brightness. The whole rounded-rect path is stroked several times,
 * widest and faintest first, so the light spreads outward from the edge instead
 * of sitting on it as a hard line.
 *
 * <p>Drawn in {@link #draw(Canvas)} <em>after</em> the children, so the light
 * sits on the card edge and is never clipped by the layout.
 */
public class GlowCard extends FrameLayout {

    /** Gutter between the view edge and the card; the spread lives in here. */
    private static final float GUTTER_DP = 20f;
    /** One full breath, in milliseconds. */
    private static final long PULSE_MS = 2900L;
    /** One full trip round the colour wheel, in milliseconds. */
    private static final long HUE_MS = 11000L;

    /** Outward glow: stroke width in dp, alpha at rest. Widest first. */
    private static final float[] GLOW_WIDTHS = {17f, 12f, 8f, 5f, 2.6f};
    private static final int[] GLOW_ALPHAS = {9, 14, 24, 40, 74};

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rimPath = new Path();
    private final RectF box = new RectF();
    private final float[] hsv = new float[3];

    private float density;
    private float cornerPx;
    private int accent = 0xFFFF453A;
    private boolean rainbow = true;
    private boolean pathDirty = true;
    private long startedAt = SystemClock.uptimeMillis();

    public GlowCard(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        glowPaint.setStyle(Paint.Style.STROKE);
        corePaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.BUTT);
        corePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Colour used when the rainbow is switched off. */
    public void setAccent(int color) {
        accent = color;
    }

    /** Corner radius of the wrapped card, in pixels. The glow follows it exactly. */
    public void setCornerRadius(float px) {
        cornerPx = px;
        pathDirty = true;
        invalidate();
    }

    /** False = hold the accent hue instead of drifting through the wheel. */
    public void setRainbow(boolean on) {
        rainbow = on;
        invalidate();
    }

    public void wrap(View content) {
        int gutter = Math.round(GUTTER_DP * density);
        setPadding(gutter, gutter, gutter, gutter);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        pathDirty = true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (pathDirty) rebuildPath(getWidth(), getHeight());
        // The body is painted on the same path as the rim, so when the frame
        // deforms the glass deforms with it.
        GlassSurface.draw(canvas, rimPath, box, density, hsvColor(hueOf(accent) + 0.02f));
        super.draw(canvas);
        drawGlow(canvas);
        postInvalidateOnAnimation();
    }

    /** Plain rounded rect. The rim follows the card exactly, no deformation. */
    private void rebuildPath(float w, float h) {
        float inset = GUTTER_DP * density;
        box.set(inset, inset, w - inset, h - inset);
        rimPath.reset();
        rimPath.addRoundRect(box, cornerPx, cornerPx, Path.Direction.CW);
        pathDirty = false;
    }

    private void drawGlow(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w < 8 || h < 8) return;
        if (pathDirty) rebuildPath(w, h);

        final long now = SystemClock.uptimeMillis();
        final long elapsed = now - startedAt;

        // 0..1 breath, eased so the peak lingers instead of bouncing.
        float sine = (float) Math.sin((elapsed % PULSE_MS) / (float) PULSE_MS * Math.PI * 2);
        float pulse = 0.5f + 0.5f * sine;
        pulse = pulse * pulse * (3f - 2f * pulse);

        // The hue drifts around the accent rather than all the way round the
        // wheel: still moving, still shifting colour, but it stays in the same
        // family as the handle instead of clashing with it.
        float baseHue = hueOf(accent);
        float hue = rainbow
                ? baseHue + 0.055f * (float) Math.sin(elapsed / (float) HUE_MS * Math.PI * 2)
                : baseHue;

        // Outward spread. Width is fixed - only the opacity breathes, so the
        // frame never appears to grow. Each ring sits a shade further round the
        // wheel than the one outside it, which makes the falloff read as light
        // rather than as a stack of identical outlines.
        for (int i = 0; i < GLOW_WIDTHS.length; i++) {
            glowPaint.setStrokeWidth(GLOW_WIDTHS[i] * density);
            glowPaint.setColor(hsvColor(hue + i * 0.007f));
            float swing = 0.58f + 0.42f * pulse;
            glowPaint.setAlpha(Math.min(255, (int) (GLOW_ALPHAS[i] * swing)));
            canvas.drawPath(rimPath, glowPaint);
        }

        // Crisp edge that defines the shape. Kept close to the hue itself
        // instead of washed out to white, so the colour actually reads.
        corePaint.setStrokeWidth(1.15f * density);
        corePaint.setColor(blend(hsvColor(hue), Color.WHITE, 0.30f));
        corePaint.setAlpha(150 + (int) (46 * pulse));
        canvas.drawPath(rimPath, corePaint);
    }

    private int hsvColor(float hue) {
        float h = hue % 1f;
        if (h < 0f) h += 1f;
        hsv[0] = h * 360f;
        hsv[1] = 0.68f;
        hsv[2] = 0.97f;
        return Color.HSVToColor(255, hsv);
    }

    private static float hueOf(int color) {
        float[] out = new float[3];
        Color.colorToHSV(color, out);
        return out[0] / 360f;
    }

    private static int blend(int from, int to, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int rr = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * r);
        int gg = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * r);
        int bb = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r);
        return Color.rgb(rr, gg, bb);
    }
}
