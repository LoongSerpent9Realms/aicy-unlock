package dev.loong.aicyunlock.power;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
 * Slide-to-confirm capsule.
 *
 * <p>Idle it breathes: the handle pulses, a light band sweeps along the track and
 * the chevrons drift towards the far end.
 *
 * <p>While dragging the rail <b>swells</b> (the head room is reserved in
 * {@link #onMeasure} so nothing gets clipped), the filled trail floods out from
 * behind the handle like liquid, and the handle squashes. Pulling past either
 * end stretches into rubber: the further you pull the less it moves, so letting
 * go always snaps back with a bounce instead of a hard stop.
 *
 * <p>On release it overshoots into place (or springs back), a ripple blooms from
 * the handle and a short vibration confirms.
 */
public class SlideConfirmView extends View {

    public interface Listener {
        void onConfirmed();
    }

    /** Handle position, so the frame around it can flex along with the drag. */
    public interface ProgressListener {
        void onProgress(float progress);
    }

    private static final float CONFIRM_AT = 0.86f;
    private static final long BREATH_CYCLE = 2400L;
    private static final long SWEEP_CYCLE = 2900L;
    private static final long RIPPLE_MS = 560L;
    /** How far past either end the handle may travel before it stops giving. */
    private static final float ELASTIC = 0.075f;
    /** Rail height at rest, as a share of the view. */
    private static final float RAIL_MIN = 0.82f;
    /**
     * Horizontal slack kept free inside the view on each side. The whole rail
     * slides into this slack when the handle is pulled past an end, so it stays
     * inside our own bounds and the parent's {@code clipChildren} never slices
     * it off.
     */
    private static final float EDGE_ROOM_DP = 10f;
    /** Maximum slide of the whole rail. Must stay below {@link #EDGE_ROOM_DP}. */
    private static final float EDGE_PULL_DP = 8f;
    /** Progress from which the bar starts to give at the stop. */
    private static final float PINCH_FROM = 0.93f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF rect = new RectF();

    private float density;

    private float progress;
    private float fillLag;
    private float swell;
    private float narrow;
    private float stretch;
    private float downX;
    private float downProgress;
    private boolean dragging;
    private boolean locked;
    private boolean confirmed;

    private int accent = 0xFFFF5A52;
    private int accentSoft = 0xFFFF6B5E;
    private CharSequence label = "";
    private Listener listener;
    private ProgressListener progressListener;
    private ValueAnimator animator;

    private long rippleAt;
    private int lastTick;

    public SlideConfirmView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setAccent(int color) {
        accent = color;
        accentSoft = blend(color, Color.WHITE, 0.26f);
        invalidate();
    }

    public void setLabel(CharSequence text) {
        label = text == null ? "" : text;
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setProgressListener(ProgressListener l) {
        progressListener = l;
    }

    private void report() {
        if (progressListener != null) progressListener.onProgress(progress);
    }

    public void reset() {
        cancelAnimation();
        progress = 0f;
        fillLag = 0f;
        swell = 0f;
        narrow = 0f;
        stretch = 0f;
        dragging = false;
        locked = false;
        confirmed = false;
        rippleAt = 0L;
        lastTick = 0;
        report();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int defW = Math.round(304 * density);
        // Head room above and below the rail, so the swell is never clipped.
        int h = Math.round(78 * density);
        setMeasuredDimension(resolveSize(defW, widthSpec), resolveSize(h, heightSpec));
    }

    // ------------------------------------------------------------------ drawing

    private float baseThumb() {
        return getHeight() - 16 * density;
    }

    /**
     * Inset of the rail inside the view. Wider than the visual margin on purpose:
     * the extra sliver is the runway the rail slides along at either end.
     */
    private float railInset() {
        return 2 * density + EDGE_ROOM_DP * density;
    }

    private float travel() {
        return Math.max(1f, getWidth() - 2f * railInset() - baseThumb());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float inset = railInset();
        final long now = SystemClock.uptimeMillis();

        // Jelly: the filled area chases the handle, never leads it. Slower than
        // a plain lerp so the flood out of the handle is actually visible.
        fillLag += (progress - fillLag) * 0.15f;

        // The swell follows the lagged value plus a kick from drag speed, so the
        // bar visibly breathes while you pull instead of scaling once.
        float level = Math.min(1.2f, Math.abs(fillLag));
        float kick = Math.min(1f, Math.abs(progress - fillLag) * 9f);
        float target = Math.min(1f, level * 1.15f + kick * 0.35f);
        swell += (target - swell) * (dragging ? 0.28f : 0.10f);

        // Pinch only once the handle actually reaches the stop. Mid-drag the bar
        // keeps its full height; the give-away happens at the end, where the
        // rubber band and the edge pull are already doing their work.
        float pinch = Math.min(1f, Math.max(0f, (progress - PINCH_FROM) / (1f - PINCH_FROM)));
        narrow += (pinch - narrow) * 0.30f;

        final float railH = h * RAIL_MIN * (1f - 0.08f * narrow);
        final float railTop = (h - railH) / 2f;
        final float railR = railH / 2f;
        // Handle travel stays keyed to the resting size, so the pinch cannot
        // shift the touch mapping mid-drag.
        final float thumb = baseThumb() * (1f - 0.07f * narrow);

        // Past either end the whole control is dragged along a little, like the
        // track is being pulled out of its slot. Done on the canvas, not with
        // translationX, so the touch coordinates stay put and the drag cannot
        // chase its own tail.
        final float edgePull = edgePull();
        final int restore = canvas.save();
        if (edgePull != 0f) canvas.translate(edgePull, 0f);

        drawTrack(canvas, w, h, railH, railTop, railR, inset, now);
        drawChevrons(canvas, w, h, railH, inset, now);
        drawLabel(canvas, w, h, railH);

        float cx = inset + travel() * clampDraw(progress) + baseThumb() / 2f;
        float cy = h / 2f;

        if (rippleAt > 0L) {
            float t = (now - rippleAt) / (float) RIPPLE_MS;
            if (t >= 1f) {
                rippleAt = 0L;
            } else {
                drawRipple(canvas, cx, cy, thumb, t);
            }
        }

        drawThumb(canvas, cx, cy, thumb, now);

        canvas.restoreToCount(restore);

        if (dragging || !locked) postInvalidateOnAnimation();
    }

    /**
     * How far the whole control slides past its own bounds, in pixels.
     * Normalised against the rubber band so the shift tracks how hard the user
     * is pulling rather than the raw overshoot.
     */
    private float edgePull() {
        float over = 0f;
        if (progress < 0f) over = progress;
        else if (progress > 1f) over = progress - 1f;
        if (over == 0f) return 0f;
        float ratio = Math.max(-1f, Math.min(1f, over / ELASTIC));
        return ratio * EDGE_PULL_DP * density;
    }

    /** Keep the handle from sliding entirely off the view while rubber-banding. */
    private static float clampDraw(float value) {
        if (value < -0.006f) return -0.006f;
        if (value > 1.006f) return 1.006f;
        return value;
    }

    private void drawTrack(Canvas canvas, float w, float h,
                           float railH, float railTop, float railR,
                           float inset, long now) {
        float top = railTop;
        float bottom = railTop + railH;

        rect.set(inset, top, w - inset, bottom);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x38FFFFFF);
        canvas.drawRoundRect(rect, railR, railR, paint);

        // Inner tint so the rail reads as a groove rather than a flat pill.
        paint.setColor(0x14FFFFFF);
        rect.set(inset, top, w - inset, top + railH * 0.44f);
        canvas.drawRoundRect(rect, railR, railR, paint);

        // Flooded trail. The right edge runs *past* the handle by a share of the
        // rail, so the fill looks like it spills out of it rather than stopping
        // at the contact point.
        if (fillLag > 0.004f) {
            float head = inset + railR + travel() * fillLag + railH * 0.52f;
            float clamped = Math.min(w - inset, head);
            rect.set(inset, top, clamped, bottom);
            paint.setColor(withAlpha(accent, (int) (40 + 74 * Math.min(1f, fillLag))));
            canvas.drawRoundRect(rect, railR, railR, paint);

            // Bright leading edge fading left, gives the flood a direction.
            float edgeW = railH * 1.9f;
            paint.setShader(new LinearGradient(
                    Math.max(inset, clamped - edgeW), 0, clamped, 0,
                    new int[]{0x00FFFFFF, withAlpha(accentSoft, 120)},
                    null, Shader.TileMode.CLAMP));
            rect.set(inset, top, clamped, bottom);
            canvas.drawRoundRect(rect, railR, railR, paint);
            paint.setShader(null);
        }

        // Slow light band sweeping across, only while idle.
        if (!dragging && progress < 0.05f) {
            float phase = (now % SWEEP_CYCLE) / (float) SWEEP_CYCLE;
            float bandW = w * 0.42f;
            float x = -bandW + (w + bandW * 2f) * phase;
            paint.setShader(new LinearGradient(x, 0, x + bandW, 0,
                    new int[]{0x00FFFFFF, 0x10FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            rect.set(inset, top, w - inset, bottom);
            canvas.drawRoundRect(rect, railR, railR, paint);
            paint.setShader(null);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0x42FFFFFF);
        rect.set(inset, top, w - inset, bottom);
        canvas.drawRoundRect(rect, railR, railR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawChevrons(Canvas canvas, float w, float h, float railH, float inset, long now) {
        float fade = Math.max(0f, 1f - progress / 0.3f);
        if (fade <= 0.01f) return;

        float phase = (now % 1500L) / 1500f;
        float baseX = w - inset - railH * 0.60f;
        float cy = h / 2f;
        float s = 4.6f * density;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < 3; i++) {
            float p = (phase + i * 0.2f) % 1f;
            // Each chevron brightens as it travels, then hands over to the next.
            float alpha = (float) Math.sin(p * Math.PI) * fade;
            if (alpha <= 0.01f) continue;
            float x = baseX + i * 7.5f * density + p * 4f * density;
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (150 * alpha)));
            canvas.drawLine(x, cy - s, x + s * 0.85f, cy, paint);
            canvas.drawLine(x + s * 0.85f, cy, x, cy + s, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabel(Canvas canvas, float w, float h, float railH) {
        float fade = Math.max(0f, 1f - progress * 1.9f);
        int alpha = (int) (255 * fade);
        if (alpha < 6 || label.length() == 0) return;

        paint.setShader(null);
        paint.setColor(withAlpha(0xFFFFFFFF, alpha));
        paint.setTextSize(15.5f * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);

        canvas.save();
        float cx = w * 0.5f + railH * 0.3f;
        canvas.scale(1f - 0.03f * progress, 1f, cx, h / 2f);
        float cy = h * 0.5f - (paint.descent() + paint.ascent()) * 0.5f;
        canvas.drawText(label, 0, label.length(), cx, cy, paint);
        canvas.restore();

        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawThumb(Canvas canvas, float cx, float cy, float size, long now) {
        float radius = size / 2f;
        // Breathing while idle, flattening out once the user grabs it.
        float breath = 0f;
        if (!dragging && !locked) {
            float phase = (now % BREATH_CYCLE) / (float) BREATH_CYCLE;
            breath = (float) Math.sin(phase * Math.PI * 2) * 0.5f + 0.5f;
        }

        // Halo blooms with the swell and while the handle is being pulled.
        float haloR = radius * (1.26f + 0.10f * breath) + stretch * radius * 0.12f;
        paint.setShader(new RadialGradient(cx, cy, Math.max(1f, haloR),
                withAlpha(accent, (int) (42 + 26 * breath + 40 * swell)), 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, paint);
        paint.setShader(null);

        float sx = 1f + 0.045f * breath + 0.28f * stretch;
        float sy = 1f + 0.045f * breath - 0.10f * stretch;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(sx, sy);

        paint.setShadowLayer(10f * density, 0f, 4f * density, 0x55000000);
        paint.setShader(new LinearGradient(-radius, -radius, radius, radius,
                accentSoft, accent, Shader.TileMode.CLAMP));
        canvas.drawCircle(0f, 0f, radius, paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        // Crisp rim.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0x33FFFFFF);
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

        iconPath.reset();
        iconPath.addArc(-r, -r * 0.94f, r, r * 1.06f, -216f, 252f);
        canvas.drawPath(iconPath, iconPaint);
        canvas.drawLine(0f, -r * 1.45f, 0f, -r * 0.15f, iconPaint);
    }

    private void drawRipple(Canvas canvas, float cx, float cy, float size, float t) {
        float eased = 1f - (1f - t) * (1f - t);
        float radius = size * (0.5f + 0.95f * eased);
        int alpha = (int) (150 * (1f - t) * (1f - t));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, 3.2f * density * (1f - t)));
        paint.setColor(withAlpha(accent, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || locked) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                downX = event.getX();
                downProgress = progress;
                dragging = true;
                stretch = 0f;
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float dx = event.getX() - downX;
                float raw = downProgress + dx / travel();
                progress = elastic(raw);
                stretch = Math.min(1f, Math.abs(dx) / (density * 340f));
                hapticTicks();
                report();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                stretch = 0f;
                if (progress >= CONFIRM_AT) {
                    settle(1f, true);
                } else {
                    // Snap-back gets its own light knock so the return is felt.
                    buzz(12L);
                    settle(0f, false);
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /** Rubber band: past either end the handle gives less and less. */
    private static float elastic(float raw) {
        if (raw < 0f) return -rubber(-raw);
        if (raw > 1f) return 1f + rubber(raw - 1f);
        return raw;
    }

    private static float rubber(float excess) {
        // Asymptotic, so it never runs away however hard you pull.
        return ELASTIC * (1f - 1f / (excess * 6.5f + 1f));
    }

    private void hapticTicks() {
        int step = (int) Math.floor(progress * 4f);
        if (step != lastTick) {
            lastTick = step;
            if (step > 0 && step < 4) buzz(6L);
        }
    }

    private void settle(float target, final boolean fire) {
        cancelAnimation();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(fire ? 320L : 420L);
        animator.setInterpolator(fire
                ? new OvershootInterpolator(1.9f)
                : new AnticipateOvershootInterpolator(1.4f));
        animator.addUpdateListener(a -> {
            float value = (float) a.getAnimatedValue();
            progress = value;
            report();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!fire || confirmed) return;
                confirmed = true;
                locked = true;
                rippleAt = SystemClock.uptimeMillis();
                progress = 1f;
                buzz(26L);
                invalidate();
                if (listener != null) listener.onConfirmed();
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
                // Predefined effects feel far closer to a real detent than a
                // raw one-shot buzz does.
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

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
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
