package dev.loong.aicyunlock.power;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Floating power entry for Aicy's voice sheet.
 *
 * <p>The voice sheet never renders what the user said, so there is nothing to
 * read and nothing to intercept. Instead of guessing, this puts a small power
 * pill on top of that screen: tap it and a two-way slider unfolds - one side
 * shuts down, the other reboots.
 *
 * <p>The pill sits mid-right by default (thumb reach on a held phone). The
 * slider unfolds either as a strip under the status bar or as a vertical strip
 * on the left or right edge, following the user's setting.
 */
public final class PowerDock {

    public static final int POS_TOP = 0;
    public static final int POS_LEFT = 1;
    public static final int POS_RIGHT = 2;
    /** Not a layout: ask the accelerometer which side the hand is on. */
    public static final int POS_AUTO = 3;

    /**
     * Turns a grip reading into a real layout. Level phone, mount or table:
     * the strip goes under the status bar where both thumbs can reach it.
     */
    public static int fromGrip(int grip, boolean flip) {
        if (grip == GripSensor.GRIP_FLAT) return POS_TOP;
        boolean right = grip == GripSensor.GRIP_RIGHT;
        if (flip) right = !right;
        return right ? POS_RIGHT : POS_LEFT;
    }

    public static String name(int position) {
        switch (position) {
            case POS_LEFT:
                return "左侧";
            case POS_RIGHT:
                return "右侧";
            case POS_AUTO:
                return "跟随握姿";
            default:
                return "状态栏下方";
        }
    }

    private final Context context;
    private final FrameLayout root;
    private final View scrim;
    private final View button;
    private final PowerDualSlider slider;
    private final FrameLayout.LayoutParams sliderLp;
    private final int position;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Runnable onClose;
    private boolean expanded;

    public PowerDock(Context context, int position) {
        this.context = context;
        // Everything below reads the field, never the argument: an unresolved
        // POS_AUTO must not leak into the layout as if it were POS_TOP.
        this.position = position == POS_AUTO ? POS_TOP : position;

        root = new FrameLayout(context);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Sits under everything else: while the slider is open a tap anywhere
        // else lands here and folds it back up. Barely tinted, just enough that
        // the rest of the screen reads as "not active right now".
        scrim = new View(context);
        scrim.setBackgroundColor(0x1A000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> collapse());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        slider = new PowerDualSlider(context);
        slider.setVertical(this.position != POS_TOP);
        slider.setListener(reboot -> {
            PowerActions.execute(context, reboot);
            collapse();
        });

        sliderLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switch (this.position) {
            case POS_LEFT:
                sliderLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                sliderLp.leftMargin = dp(10);
                sliderLp.height = Math.round(context.getResources()
                        .getDisplayMetrics().heightPixels * 0.34f);
                sliderLp.width = dp(76);
                break;
            case POS_RIGHT:
                sliderLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                sliderLp.rightMargin = dp(10);
                sliderLp.height = Math.round(context.getResources()
                        .getDisplayMetrics().heightPixels * 0.34f);
                sliderLp.width = dp(76);
                break;
            default:
                sliderLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                sliderLp.topMargin = statusBar() + dp(64);
                sliderLp.height = dp(76);
                sliderLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                sliderLp.leftMargin = dp(18);
                sliderLp.rightMargin = dp(18);
                break;
        }
        root.addView(slider, sliderLp);
        slider.setVisibility(View.GONE);

        button = new Pill(context);
        // The pill doubles as a close target: once folded out it fades to
        // nothing but still owns its spot, so tapping there behaves like
        // tapping anywhere else.
        button.setOnClickListener(v -> {
            if (expanded) collapse();
            else expand();
        });

        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        if (this.position == POS_LEFT) {
            btnLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            btnLp.leftMargin = dp(8);
        } else if (this.position == POS_RIGHT) {
            btnLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            btnLp.rightMargin = dp(8);
        } else {
            // Level phone: park it just under the status bar, where either
            // thumb can reach without shifting the grip.
            btnLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            btnLp.topMargin = statusBar() + dp(8);
        }
        root.addView(button, btnLp);
    }

    public void setOnClose(Runnable close) {
        onClose = close;
    }

    /** Opens straight onto the slider, skipping the pill entirely. */
    public void expandNow() {
        button.setVisibility(View.GONE);
        expand();
    }

    public View getRoot() {
        return root;
    }

    private void expand() {
        if (expanded) return;
        expanded = true;
        slider.reset();
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180L).start();
        slider.setVisibility(View.VISIBLE);
        slider.setAlpha(0f);
        if (position == POS_TOP) {
            slider.setTranslationY(-dp(14));
            slider.animate().alpha(1f).translationY(0f)
                    .setDuration(220L).start();
        } else {
            slider.setScaleX(0.86f);
            slider.setScaleY(0.86f);
            slider.setAlpha(0f);
            slider.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220L).start();
        }
        button.animate().alpha(0f).setDuration(140L).start();
        main.removeCallbacks(collapseTask);
        main.postDelayed(collapseTask, 9000L);
    }

    private final Runnable collapseTask = new Runnable() {
        @Override
        public void run() {
            if (!expanded) return;
            if (slider.getValue() != 0f) {
                // Still armed at one end: give it another moment.
                main.postDelayed(this, 1500L);
                return;
            }
            collapse();
        }
    };

    private void collapse() {
        if (!expanded) return;
        expanded = false;
        main.removeCallbacks(collapseTask);
        scrim.animate().alpha(0f).setDuration(140L)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        slider.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> {
                    slider.setVisibility(View.GONE);
                    slider.reset();
                }).start();
        button.animate().alpha(1f).setDuration(180L).start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Height of the status bar, so the top strip clears it instead of hiding behind it. */
    private int statusBar() {
        int id = context.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        if (id > 0) {
            int px = context.getResources().getDimensionPixelSize(id);
            if (px > 0) return px;
        }
        return dp(28);
    }

    /**
     * The pill itself. Drawn rather than text-based: the power glyph \u23fb is
     * missing from several system fonts and renders as a blank box.
     */
    static final class Pill extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path arc = new android.graphics.Path();
        private final float density;

        Pill(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, density));
            stroke.setColor(0x59FFFFFF);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f;
            float cx = w / 2f;
            float cy = h / 2f;

            // Near-opaque, otherwise whatever is behind shows through the pill
            // and makes it look dirty.
            fill.setColor(0xE61A1B21);
            canvas.drawCircle(cx, cy, r - density, fill);
            canvas.drawCircle(cx, cy, r - density, stroke);

            float g = r * 0.26f;
            glyph.setStrokeWidth(Math.max(2f, r * 0.15f));
            arc.reset();
            arc.addArc(cx - g, cy - g * 0.94f, cx + g, cy + g * 1.06f, -216f, 252f);
            canvas.drawPath(arc, glyph);
            canvas.drawLine(cx, cy - g * 1.5f, cx, cy - g * 0.2f, glyph);
        }
    }

    /** Thin separator ring used by the pill, kept for reuse. */
    static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        Ring(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(context.getResources().getDisplayMetrics().density);
            paint.setColor(0x40FFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = paint.getStrokeWidth();
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(oval, 0f, 360f, false, paint);
        }
    }
}
