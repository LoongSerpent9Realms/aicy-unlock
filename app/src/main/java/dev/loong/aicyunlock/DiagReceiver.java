package dev.loong.aicyunlock;

/**
 * Diagnostics storage keys shared by {@link ConfigProvider} (which now owns the
 * {@code call()} based collection channel) and the settings UI that displays
 * them.
 *
 * <p>The old exported broadcast receiver was removed: any app could send to
 * {@code dev.loong.aicyunlock.DIAG} and inject fake log lines. Collection now
 * goes through {@link ConfigProvider#call}, which is gated on the caller uid.
 */
public final class DiagReceiver {

    static final String PREFS = "diag";
    static final String KEY_LOG = "log";
    static final String KEY_AT = "at";
    private static final int MAX_CHARS = 8000;

    private DiagReceiver() {
    }

    static int maxChars() {
        return MAX_CHARS;
    }
}
