package dev.loong.aicyunlock.power;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The power entry that lives inside Aicy's long-press voice sheet.
 *
 * <p>That sheet is a full-screen {@code FrameLayout} whose only child is a
 * {@code ComposeView}, so everything inside it is drawn by Compose and there is
 * no view to attach to. Adding this as one more child of the sheet's own
 * container puts it on top of the Compose canvas and makes it die together with
 * the sheet, without any overlay permission or accessibility service.
 */
public final class VoicePowerEntry extends FrameLayout {

    private static final String TAG = "PowerConfirm";

    private static final int COLOR_BACKDROP = 0x0F000000;

    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable collapseTask = this::collapse;

    private View backdrop;
    private View pill;
    private PowerDualSlider slider;
    private FrameLayout.LayoutParams sliderLp;
    private FrameLayout.LayoutParams pillLp;
    private boolean expanded;
    /** Which side the sheet is laid out on; see {@link PowerDock}. */
    private int position = PowerDock.POS_RIGHT;
    private boolean positionKnown;
    private boolean expandWanted;

    public VoicePowerEntry(Context context) {
        this(context, true);
    }

    /**
     * @param withPill false when the caller already has its own trigger (the
     *                 "电源" item in the pickup row) and only wants the sheet.
     */
    public VoicePowerEntry(Context context, boolean withPill) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);

        backdrop = new View(context);
        backdrop.setBackgroundColor(COLOR_BACKDROP);
        backdrop.setVisibility(GONE);
        backdrop.setOnClickListener(v -> collapse());
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        slider = new PowerDualSlider(context);
        slider.setVertical(true);
        slider.setVisibility(GONE);
        slider.setListener(reboot -> {
            collapse();
            PowerActions.execute(context, reboot);
        });
        sliderLp = new LayoutParams(dp(72), dp(300));
        sliderLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        sliderLp.rightMargin = dp(10);
        addView(slider, sliderLp);

        if (!withPill) {
            // Used as an on-demand sheet: open as soon as it is attached.
            post(this::expand);
            return;
        }

        pill = new PowerDock.Pill(context);
        pill.setOnClickListener(v -> expand());
        pillLp = new LayoutParams(dp(52), dp(52));
        pillLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        pillLp.rightMargin = dp(8);
        addView(pill, pillLp);
    }

    /**
     * Opens on the side the hand is on.
     *
     * <p>The reading costs a few hundred milliseconds the first time, so the
     * slider stays hidden until the side is known - it is placed before it is
     * ever painted, instead of jumping from one edge to the other.
     */
    private void expand() {
        if (expanded) return;
        expandWanted = true;
        if (positionKnown) {
            doExpand();
            return;
        }
        resolvePosition(this::doExpand);
    }

    private void resolvePosition(Runnable then) {
        final int wanted = PowerPrefs.dockPosition(getContext());
        if (wanted != PowerDock.POS_AUTO) {
            applyPosition(wanted);
            if (then != null) then.run();
            return;
        }
        GripSensor.detect(getContext(), (grip, roll) -> {
            int resolved = PowerDock.fromGrip(grip, PowerPrefs.gripFlip(getContext()));
            Log.i(TAG, "sheet grip roll=" + Math.round(roll) + " -> " + PowerDock.name(resolved));
            applyPosition(resolved);
            if (then != null && expandWanted && !expanded) then.run();
        });
    }

    /** Same layouts the standalone dock uses, so both read as one feature. */
    private void applyPosition(int resolved) {
        if (resolved == PowerDock.POS_AUTO) return;
        position = resolved;
        positionKnown = true;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        switch (resolved) {
            case PowerDock.POS_LEFT:
                slider.setVertical(true);
                sliderLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                sliderLp.leftMargin = dp(10);
                sliderLp.rightMargin = 0;
                sliderLp.width = dp(72);
                sliderLp.height = Math.round(screenHeight * 0.34f);
                break;
            case PowerDock.POS_RIGHT:
                slider.setVertical(true);
                sliderLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                sliderLp.rightMargin = dp(10);
                sliderLp.leftMargin = 0;
                sliderLp.width = dp(72);
                sliderLp.height = Math.round(screenHeight * 0.34f);
                break;
            default:
                slider.setVertical(false);
                sliderLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                sliderLp.topMargin = statusBar() + dp(64);
                sliderLp.width = LayoutParams.MATCH_PARENT;
                sliderLp.height = dp(76);
                sliderLp.leftMargin = dp(18);
                sliderLp.rightMargin = dp(18);
                break;
        }
        slider.reset();
        slider.setLayoutParams(sliderLp);

        if (pill != null) {
            if (resolved == PowerDock.POS_LEFT) {
                pillLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                pillLp.leftMargin = dp(8);
                pillLp.rightMargin = 0;
            } else if (resolved == PowerDock.POS_RIGHT) {
                pillLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                pillLp.rightMargin = dp(8);
                pillLp.leftMargin = 0;
            } else {
                pillLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                pillLp.topMargin = statusBar() + dp(8);
            }
            pill.setLayoutParams(pillLp);
        }
    }

    private void doExpand() {
        if (expanded) return;
        expanded = true;
        Log.i(TAG, "voice entry expanded on " + PowerDock.name(position));
        slider.reset();
        slider.setAlpha(0f);
        if (position == PowerDock.POS_TOP) {
            slider.setScaleX(0.72f);
            slider.setScaleY(1f);
        } else {
            slider.setScaleY(0.72f);
            slider.setScaleX(1f);
        }
        slider.setVisibility(VISIBLE);
        slider.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start();
        backdrop.setVisibility(VISIBLE);
        if (pill != null) {
            pill.animate().alpha(0f).setDuration(140L).start();
            pill.setClickable(false);
        }
        main.removeCallbacks(collapseTask);
        main.postDelayed(collapseTask, 9000L);
    }

    private void collapse() {
        if (!expanded) return;
        expanded = false;
        expandWanted = false;
        main.removeCallbacks(collapseTask);
        slider.animate().alpha(0f).setDuration(150L).withEndAction(() -> {
            slider.setVisibility(GONE);
            slider.reset();
        }).start();
        backdrop.setVisibility(GONE);
        if (pill != null) {
            pill.setClickable(true);
            pill.animate().alpha(1f).setDuration(180L).start();
        } else {
            // Sheet-only mode: nothing left to show, so detach entirely.
            postDelayed(this::detach, 200L);
        }
    }

    private int statusBar() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(28);
    }

    private void detach() {
        try {
            android.view.ViewParent parent = getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(this);
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
