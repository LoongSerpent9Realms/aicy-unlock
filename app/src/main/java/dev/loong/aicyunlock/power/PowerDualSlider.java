package dev.loong.aicyunlock.power;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Two-way slide to confirm.
 *
 * <p>The handle starts in the middle. Dragging one way arms shutdown, the other
 * way arms reboot, so a single control covers both actions without a menu. The
 * whole thing works along either axis: horizontal for a strip under the status
 * bar, vertical for a thumb-reachable edge strip.
 *
 * <p>Drawing is written in a horizontal coordinate system and rotated for the
 * vertical case, so both orientations share one layout, one animation and one
 * set of touch rules.
 */
public class PowerDualSlider extends View {

    public interface Listener {
        /** @param reboot true for the positive end, false for the negative one. */
        void onConfirm(boolean reboot);
    }

    private static final long BREATH_CYCLE = 2400L;
    private static final float RAIL_SHARE = 0.84f;
    private static final float ELASTIC = 0.075f;
    /** |value| past this, released, confirms. */
    private static final float CONFIRM_AT = 0.80f;
    private static final float EDGE_ROOM_DP = 10f;
    private static final float EDGE_PULL_DP = 7f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix flip = new Matrix();

    private float density;

    /** -1 = shutdown end, +1 = reboot end. */
    private float value;
    private float lag;
    private float stretch;
    private float narrow;
    private float downPos;
    private float downValue;
    private boolean dragging;
    private boolean locked;
    private boolean confirmed;
    private boolean vertical;

    private int accentDown = 0xFFFF5A52;
    private int accentUp = 0xFFFFB13B;
    private Listener listener;
    private ValueAnimator animator;

    private int lastTick;

    public PowerDualSlider(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setVertical(boolean vertical) {
        this.vertical = vertical;
        requestLayout();
        invalidate();
    }

    public boolean isVertical() {
        return vertical;
    }

    /** -1 shutdown end, 0 at rest, +1 reboot end. */
    public float getValue() {
        return value;
    }

    public void setAccents(int shutdown, int reboot) {
        accentDown = shutdown;
        accentUp = reboot;
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void reset() {
        cancelAnimation();
        value = 0f;
        lag = 0f;
        stretch = 0f;
        narrow = 0f;
        dragging = false;
        locked = false;
        confirmed = false;
        lastTick = 0;
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int longSide = Math.round(vertical ? 300 * density : 300 * density);
        int shortSide = Math.round(76 * density);
        int w = vertical ? shortSide : longSide;
        int h = vertical ? longSide : shortSide;
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    /** Length along the drag axis, in the drawing coordinate system. */
    private float axisLength() {
        return vertical ? getHeight() : getWidth();
    }

    /** Thickness across the drag axis. */
    private float axisThickness() {
        return vertical ? getWidth() : getHeight();
    }

    private float railInset() {
        return 2 * density + EDGE_ROOM_DP * density;
    }

    private float baseThumb() {
        return axisThickness() - 16 * density;
    }

    private float travel() {
        return Math.max(1f, axisLength() - 2f * railInset() - baseThumb());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final float len = axisLength();
        final float thick = axisThickness();
        final long now = SystemClock.uptimeMillis();

        lag += (value - lag) * 0.16f;
        float pinch = Math.min(1f, Math.max(0f,
                (Math.abs(value) - 0.90f) / 0.10f));
        narrow += (pinch - narrow) * 0.30f;

        final int restore = canvas.save();
        if (vertical) {
            // Rotate the whole drawing so both orientations share this code.
            // +90 maps (x, y) -> (-y, x); the translate that follows pushes the
            // whole strip into the view, so the x axis runs from the top edge
            // (x = 0, shutdown end) down to the bottom (x = len, reboot end).
            canvas.translate(thick, 0f);
            canvas.rotate(90f);
        }

        final float railThick = thick * RAIL_SHARE * (1f - 0.08f * narrow);
        final float railTop = (thick - railThick) / 2f;
        final float railR = railThick / 2f;
        final float thumb = baseThumb() * (1f - 0.07f * narrow);
        final float inset = railInset();

        float pull = 0f;
        float over = Math.abs(value) > 1f ? Math.abs(value) - 1f : 0f;
        if (over > 0f) {
            float ratio = Math.min(1f, over / ELASTIC);
            pull = Math.signum(value) * ratio * EDGE_PULL_DP * density;
        }
        final int inner = canvas.save();
        if (pull != 0f) canvas.translate(pull, 0f);

        drawTrack(canvas, len, railTop, railThick, railR, inset, now);
        drawHints(canvas, len, thick, inset);

        final float mid = len / 2f;
        final float cx = mid + clampDraw(value) * travel() / 2f;
        final float cy = thick / 2f;

        drawThumb(canvas, cx, cy, thumb, now);
        canvas.restoreToCount(inner);
        canvas.restoreToCount(restore);

        if (!locked) postInvalidateOnAnimation();
    }

    private void drawTrack(Canvas canvas, float len, float railTop, float railThick,
                           float railR, float inset, long now) {
        final float top = railTop;
        final float bottom = railTop + railThick;

        // Groove.
        rect.set(inset, top, len - inset, bottom);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x3AFFFFFF);
        canvas.drawRoundRect(rect, railR, railR, paint);

        paint.setColor(0x18FFFFFF);
        rect.set(inset, top, len - inset, top + railThick * 0.42f);
        canvas.drawRoundRect(rect, railR, railR, paint);

        // Fill from the middle toward the handle, coloured by direction.
        final float mid = len / 2f;
        if (Math.abs(lag) > 0.006f) {
            int tint = lag < 0f ? accentDown : accentUp;
            float head = mid + lag * travel() / 2f;
            float from = Math.min(mid, head);
            float to = Math.max(mid, head);
            float feather = railThick * 0.55f;
            from = Math.max(inset, from - feather * 0.2f);
            to = Math.min(len - inset, to + feather * 0.2f);
            rect.set(from, top, to, bottom);
            paint.setColor(withAlpha(tint, (int) (46 + 62 * Math.min(1f, Math.abs(lag)))));
            canvas.drawRoundRect(rect, railR, railR, paint);

            // Bright edge where the fill meets the handle.
            paint.setShader(new LinearGradient(
                    head < mid ? from : to, 0, head < mid ? head : head, 0,
                    new int[]{0x00FFFFFF, withAlpha(tint, 130)},
                    null, Shader.TileMode.CLAMP));
            rect.set(Math.min(from, head), top, Math.max(to, head), bottom);
            canvas.drawRoundRect(rect, railR, railR, paint);
            paint.setShader(null);
        }

        // Outline.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0x4AFFFFFF);
        rect.set(inset, top, len - inset, bottom);
        canvas.drawRoundRect(rect, railR, railR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Labels at both ends: they fade out as the handle travels toward them. */
    private void drawHints(Canvas canvas, float len, float thick, float inset) {
        float goneLeft = Math.max(0f, 1f - Math.max(0f, -value) / 0.35f);
        float goneRight = Math.max(0f, 1f - Math.max(0f, value) / 0.35f);

        paint.setShader(null);
        paint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);

        if (vertical) {
            // The canvas is already turned a quarter turn; undo it here so the
            // two labels stay readable instead of lying on their side.
            float pad = inset + 24 * density;
            if (goneLeft > 0.02f) {
                upright(canvas, thick / 2f, pad, "关机", (int) (210 * goneLeft));
            }
            if (goneRight > 0.02f) {
                upright(canvas, thick / 2f, len - pad, "重启", (int) (210 * goneRight));
            }
        } else {
            float cy = thick / 2f - (paint.descent() + paint.ascent()) * 0.5f;
            float pad = inset + thick * 0.62f;
            if (goneLeft > 0.02f) {
                paint.setColor(withAlpha(0xFFFFFFFF, (int) (210 * goneLeft)));
                canvas.drawText("关机", pad, cy, paint);
            }
            if (goneRight > 0.02f) {
                paint.setColor(withAlpha(0xFFFFFFFF, (int) (210 * goneRight)));
                canvas.drawText("重启", len - pad, cy, paint);
            }
        }
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** Draws a label flat, cancelling the vertical quarter turn. */
    private void upright(Canvas canvas, float cx, float cy, String text, int alpha) {
        final int save = canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(-90f);
        paint.setColor(withAlpha(0xFFFFFFFF, alpha));
        canvas.drawText(text, 0f, -(paint.descent() + paint.ascent()) * 0.5f, paint);
        canvas.restoreToCount(save);
    }

    private void drawThumb(Canvas canvas, float cx, float cy, float size, long now) {
        float radius = size / 2f;
        float breath = 0f;
        if (!dragging && !locked) {
            float phase = (now % BREATH_CYCLE) / (float) BREATH_CYCLE;
            breath = (float) Math.sin(phase * Math.PI * 2) * 0.5f + 0.5f;
        }
        int accent = value < 0f ? accentDown : (value > 0f ? accentUp : 0xFF5A5F6B);

        float haloR = radius * (1.24f + 0.08f * breath) + stretch * radius * 0.12f;
        paint.setShader(new RadialGradient(cx, cy, Math.max(1f, haloR),
                withAlpha(accent, (int) (44 + 22 * breath)), 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, paint);
        paint.setShader(null);

        float sx = 1f + 0.04f * breath + 0.26f * stretch;
        float sy = 1f + 0.04f * breath - 0.09f * stretch;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(sx, sy);

        paint.setShadowLayer(10f * density, 0f, 4f * density, 0x55000000);
        paint.setShader(new LinearGradient(-radius, -radius, radius, radius,
                blend(accent, Color.WHITE, 0.24f), accent, Shader.TileMode.CLAMP));
        canvas.drawCircle(0f, 0f, radius, paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0x38FFFFFF);
        canvas.drawCircle(0f, 0f, radius - 0.5f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        drawPowerGlyph(canvas, radius);
        canvas.restore();
    }

    private void drawPowerGlyph(Canvas canvas, float radius) {
        float r = radius * 0.19f;
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Math.max(2f, radius * 0.145f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setColor(0xFFFFFFFF);
        android.graphics.Path path = new android.graphics.Path();
        path.addArc(-r, -r * 0.94f, r, r * 1.06f, -216f, 252f);
        canvas.drawPath(path, iconPaint);
        canvas.drawLine(0f, -r * 1.45f, 0f, -r * 0.15f, iconPaint);
    }

    /** Keeps the handle from leaving the bar while rubber-banding. */
    private static float clampDraw(float v) {
        if (v < -1.006f) return -1.006f;
        if (v > 1.006f) return 1.006f;
        return v;
    }

    // ------------------------------------------------------------------ input

    /** Maps a touch point into the drawing coordinate system. */
    private float axisOf(MotionEvent event) {
        return vertical ? event.getY() : event.getX();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || locked) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                downPos = axisOf(event);
                downValue = value;
                dragging = true;
                stretch = 0f;
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float delta = axisOf(event) - downPos;
                float raw = downValue + delta / (travel() / 2f);
                value = elastic(raw);
                stretch = Math.min(1f, Math.abs(delta) / (density * 300f));
                hapticTicks();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                stretch = 0f;
                if (Math.abs(value) >= CONFIRM_AT) {
                    settle(Math.signum(value), true);
                } else {
                    buzz(12L);
                    settle(0f, false);
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private static float elastic(float raw) {
        if (raw < -1f) return -1f - rubber(-1f - raw);
        if (raw > 1f) return 1f + rubber(raw - 1f);
        return raw;
    }

    private static float rubber(float excess) {
        return ELASTIC * (1f - 1f / (excess * 6.5f + 1f));
    }

    private void hapticTicks() {
        int step = (int) Math.floor(Math.abs(value) * 4f);
        if (step != lastTick) {
            lastTick = step;
            if (step > 0 && step < 4) buzz(6L);
        }
    }

    private void settle(float target, final boolean fire) {
        cancelAnimation();
        animator = ValueAnimator.ofFloat(value, target);
        animator.setDuration(fire ? 320L : 420L);
        animator.setInterpolator(fire
                ? new OvershootInterpolator(1.9f)
                : new AnticipateOvershootInterpolator(1.4f));
        animator.addUpdateListener(a -> {
            value = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!fire || confirmed) return;
                confirmed = true;
                locked = true;
                value = target;
                buzz(26L);
                invalidate();
                if (listener != null) listener.onConfirm(target > 0f);
            }
        });
        animator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                int effect = ms >= 20
                        ? VibrationEffect.EFFECT_HEAVY_CLICK
                        : VibrationEffect.EFFECT_TICK;
                v.vibrate(VibrationEffect.createPredefined(effect));
            } else {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimation();
        super.onDetachedFromWindow();
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.rgb(r, g, b);
    }
}
