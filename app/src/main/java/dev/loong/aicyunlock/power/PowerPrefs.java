package dev.loong.aicyunlock.power;

import android.content.Context;
import android.content.SharedPreferences;

/** Power-confirmation options, written by the settings page and read by the sheet. */
public final class PowerPrefs {

    private static final String FILE = "power_confirm";
    private static final String KEY_RAINBOW = "rainbow";
    private static final String KEY_TEMP_ROOT = "temp_root";
    private static final String KEY_DRY_RUN = "dry_run";
    private static final String KEY_INTERCEPT = "intercept_send";
    private static final String KEY_DOCK = "dock_enabled";
    private static final String KEY_DOCK_POS = "dock_position";
    private static final String KEY_DOCK_VER = "dock_position_ver";
    /** Bumped whenever the meaning of the stored numbers changes. */
    private static final int VER_DOCK_POS = 3;
    private static final String KEY_GRIP_FLIP = "grip_flip";

    private PowerPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** RGB chase on the card rim, vs a plain accent-coloured band. */
    public static boolean rainbow(Context context) {
        return prefs(context).getBoolean(KEY_RAINBOW, true);
    }

    public static void setRainbow(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_RAINBOW, on).apply();
    }

    /**
     * Temporary-root mode. Meant for a device that is only rooted for the
     * session: powering it off can leave it wedged, so shutdown is refused
     * outright and "reboot" is downgraded to a framework-only soft reboot.
     */
    public static boolean tempRoot(Context context) {
        return prefs(context).getBoolean(KEY_TEMP_ROOT, true);
    }

    public static void setTempRoot(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_TEMP_ROOT, on).apply();
    }

    /** Rehearsal latch: everything animates and reports, nothing leaves the UI. */
    public static boolean dryRun(Context context) {
        return prefs(context).getBoolean(KEY_DRY_RUN, true);
    }

    public static void setDryRun(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_DRY_RUN, on).apply();
    }

    /**
     * Catch the request before it is sent: the sentence never reaches Aicy, the
     * input box is cleared and the sheet comes up instead.
     */
    public static boolean interceptSend(Context context) {
        return prefs(context).getBoolean(KEY_INTERCEPT, true);
    }

    public static void setInterceptSend(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_INTERCEPT, on).apply();
    }

    /** Power pill on top of Aicy's voice sheet. */
    public static boolean dock(Context context) {
        return prefs(context).getBoolean(KEY_DOCK, true);
    }

    public static void setDock(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_DOCK, on).apply();
    }

    /**
     * 0 = under the status bar, 1 = left edge, 2 = right edge,
     * 3 = follow the grip (accelerometer picks the side).
     *
     * <p>Version 2 inserted "follow the grip" in front of the list, so older
     * stored numbers mean something else now. Whoever was parked on a fixed
     * side gets moved to the grip rule, which is what they were asking for by
     * leaving it alone.
     */
    public static int dockPosition(Context context) {
        SharedPreferences p = prefs(context);
        int version = p.getInt(KEY_DOCK_VER, 1);
        if (version < VER_DOCK_POS) {
            p.edit()
                    .putInt(KEY_DOCK_POS, 3)
                    .putInt(KEY_DOCK_VER, VER_DOCK_POS)
                    .apply();
            return 3;
        }
        return p.getInt(KEY_DOCK_POS, 3);
    }

    public static void setDockPosition(Context context, int position) {
        prefs(context).edit()
                .putInt(KEY_DOCK_POS, position)
                .putInt(KEY_DOCK_VER, VER_DOCK_POS)
                .apply();
    }

    /**
     * Swaps the left/right reading of the grip sensor. Which way a phone leans
     * depends on the hand and on how the person holds it, so the mapping is
     * theirs to correct.
     */
    public static boolean gripFlip(Context context) {
        return prefs(context).getBoolean(KEY_GRIP_FLIP, false);
    }

    public static void setGripFlip(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_GRIP_FLIP, on).apply();
    }
}
