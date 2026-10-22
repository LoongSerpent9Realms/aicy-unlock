package dev.loong.aicyunlock.power;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The confirmation layer that sits on top of Aicy's own window: dim scrim,
 * rounded card, slide handle, then a three second ring countdown you can still
 * cancel.
 */
public final class PowerSheet {

    static final long COUNTDOWN_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public final View root;
    private final Context context;
    private final boolean reboot;
    private final Runnable perform;

    private final FrameLayout card;
    private final View scrim;
    private final TextView number;
    private final Ring ring;
    private final LinearLayout countdownRow;
    private final SlideConfirmView slider;

    private Runnable dismissHook;
    private boolean finished;
    private long startedAt;
    private int shownSecond = -1;

    public PowerSheet(Context context, boolean reboot, Runnable perform) {
        this.context = context;
        this.reboot = reboot;
        this.perform = perform;

        int accent = reboot ? 0xFFFFB13B : 0xFFFF5A52;

        FrameLayout scrimView = new FrameLayout(context);
        scrimView.setBackgroundColor(0xC4000000);
        scrimView.setClickable(true);
        scrimView.setOnClickListener(v -> dismiss());
        scrim = scrimView;
        root = scrimView;

        card = new FrameLayout(context);
        // Glass is painted by GlowCard onto the same deformed path as the glow,
        // so the body and the rim move together.
        card.setBackground(null);
        card.setClickable(true);
        card.setPadding(dp(24), dp(22), dp(24), dp(22));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(reboot ? "确认重启" : "确认关机");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        boolean tempRoot = PowerPrefs.tempRoot(context);
        subtitle.setText(tempRoot
                ? (reboot ? "临时 root 模式 · 将只重启 Android 框架" : "临时 root 模式 · 关机已禁用")
                : "为防止误触，请滑动确认");
        subtitle.setTextColor(tempRoot ? 0xFFB58A4A : 0xFF8E8E93);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        column.addView(subtitle, subLp);

        slider = new SlideConfirmView(context);
        slider.setAccent(accent);
        slider.setLabel(reboot ? "滑动以重启" : "滑动以关机");
        LinearLayout.LayoutParams slideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
        slideLp.topMargin = dp(14);
        column.addView(slider, slideLp);

        // Countdown row, hidden until the handle reaches the end.
        countdownRow = new LinearLayout(context);
        countdownRow.setOrientation(LinearLayout.HORIZONTAL);
        countdownRow.setGravity(Gravity.CENTER_VERTICAL);
        countdownRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
        cdLp.topMargin = dp(14);
        column.addView(countdownRow, cdLp);

        FrameLayout ringBox = new FrameLayout(context);
        ring = new Ring(context, accent);
        ringBox.addView(ring, new FrameLayout.LayoutParams(dp(64), dp(64)));

        number = new TextView(context);
        number.setTextColor(Color.WHITE);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        number.setTypeface(null, android.graphics.Typeface.BOLD);
        number.setGravity(Gravity.CENTER);
        number.setText("3");
        ringBox.addView(number, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        countdownRow.addView(ringBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(context);
        label.setText(reboot ? "秒后重启" : "秒后关机");
        label.setTextColor(0xFFC7C7CC);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(label);
        TextView hint = new TextView(context);
        hint.setText("点击「取消」可中止");
        hint.setTextColor(0xFF6E6E73);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        texts.addView(hint);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = dp(14);
        countdownRow.addView(texts, textLp);

        View spacer = new View(context);
        countdownRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView cancel = new TextView(context);
        cancel.setText("取消");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(0x2EFFFFFF);
        cancelBg.setCornerRadius(dp(18));
        cancel.setBackground(cancelBg);
        cancel.setPadding(dp(22), dp(10), dp(22), dp(10));
        cancel.setOnClickListener(v -> dismiss());
        cancel.setOnTouchListener((v, e) -> {
            v.setScaleX(e.getActionMasked() == 0 ? 0.94f : 1f);
            v.setScaleY(e.getActionMasked() == 0 ? 0.94f : 1f);
            return false;
        });
        countdownRow.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Marquee rim: an RGB band chases around the card's own corner radius.
        GlowCard glow = new GlowCard(context);
        glow.setAccent(accent);
        glow.setCornerRadius(dp(26));
        glow.setRainbow(PowerPrefs.rainbow(context));
        glow.wrap(card);


        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(20);
        cardLp.rightMargin = dp(20);
        scrimView.addView(glow, cardLp);

        slider.setListener(() -> {
            if (finished) return;
            ring.setVisibility(View.VISIBLE);
            slider.setVisibility(View.GONE);
            countdownRow.setVisibility(View.VISIBLE);
            countdownRow.setAlpha(0f);
            countdownRow.animate().alpha(1f).setDuration(220L).start();
            startedAt = SystemClock.uptimeMillis();
            shownSecond = -1;
            ring.setSweep(1f);
            MAIN.post(tick);
        });
    }

    public void setOnDismiss(Runnable hook) {
        dismissHook = hook;
    }

    /** Attach happened: run the entrance animation. */
    public void markShown() {
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180L).start();

        card.setScaleX(0.88f);
        card.setScaleY(0.88f);
        card.setAlpha(0f);
        card.setTranslationY(dp(34));
        card.animate()
                .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
                .setDuration(430L)
                // Springy landing: it settles slightly past 1.0 and rebounds.
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.35f))
                .start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (finished) return;
            long elapsed = SystemClock.uptimeMillis() - startedAt;
            long remaining = COUNTDOWN_MS - elapsed;
            if (remaining <= 0) {
                dismissThenRun();
                return;
            }
            float fraction = remaining / (float) COUNTDOWN_MS;
            ring.setSweep(fraction);

            int second = (int) ((remaining + 999L) / 1000L);
            if (second != shownSecond) {
                shownSecond = second;
                number.setText(String.valueOf(second));
                number.setScaleX(1.22f);
                number.setScaleY(1.22f);
                number.animate().scaleX(1f).scaleY(1f).setDuration(180L).start();
            }
            MAIN.postDelayed(this, 32L);
        }
    };

    private void dismissThenRun() {
        if (finished) return;
        finished = true;
        MAIN.removeCallbacks(tick);
        animateOut(() -> {
            if (dismissHook != null) {
                try {
                    dismissHook.run();
                } catch (Throwable ignored) {
                }
            }
            if (perform != null) perform.run();
        });
    }

    public void dismiss() {
        if (finished) return;
        finished = true;
        MAIN.removeCallbacks(tick);
        animateOut(() -> {
            if (dismissHook != null) {
                try {
                    dismissHook.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void animateOut(Runnable end) {
        card.animate()
                .scaleX(0.94f).scaleY(0.94f).alpha(0f).translationY(dp(14))
                .setDuration(160L)
                .withEndAction(end)
                .start();
        scrim.animate().alpha(0f).setDuration(180L).start();
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Thin depleting ring around the remaining seconds. */
    private static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final int accent;
        private float sweep = 1f;

        Ring(Context context, int accent) {
            super(context);
            this.accent = accent;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setSweep(float value) {
            sweep = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float stroke = Math.max(2f, 3f * getResources().getDisplayMetrics().density);
            float inset = stroke;
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0x24FFFFFF);
            canvas.drawArc(oval, 0f, 360f, false, paint);

            paint.setColor(accent);
            canvas.drawArc(oval, -90f, 360f * sweep, false, paint);
        }
    }
}
