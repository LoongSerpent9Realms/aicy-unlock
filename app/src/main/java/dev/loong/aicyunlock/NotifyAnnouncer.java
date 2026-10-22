package dev.loong.aicyunlock;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Reads incoming notifications aloud, regardless of which process saw them.
 *
 * <p>Two callers feed this class:
 * <ul>
 *   <li>{@link NotifyHooks} inside Aicy's own
 *       {@code NotificationCollectorService} — the preferred path, so the
 *       assistant that already owns the user's timbres does the talking;</li>
 *   <li>{@link NotificationSpeechService} in the module app — a fallback for
 *       when Aicy has not been granted 通知使用权, so the feature still works.</li>
 * </ul>
 *
 * <p>Trigger policy mirrors Aicy 12's removed feature: enabled by default, by
 * default only while the screen is off and a trusted headset (wired / USB /
 * Bluetooth) is connected, and never during a call. The trust list fails
 * closed: an empty list means no headset is allowed, not "any headset". Text
 * is capped at a short sentence so a long news notification does not turn into
 * a podcast.
 *
 * <p>Everything runs on one worker thread: listener callbacks only enqueue, so
 * a slow network request never blocks the host's notification service.
 */
final class NotifyAnnouncer {

    static final String AICY_PACKAGE = "com.meizu.voiceassistant";
    /**
     * Aicy's own listener. Kept here so the settings page can open its detail
     * page directly instead of dropping the user into the full app list.
     */
    static final String AICY_SERVICE =
            "com.flyme.agentos.app.phone.notification.NotificationCollectorService";
    static final String MODULE_PACKAGE = "dev.loong.aicyunlock";

    private static final String TAG = "AicyUnlock";

    /** Longest sentence we will speak; notifications can carry whole articles. */
    private static final int MAX_CHARS = 180;
    /** Time for an app to replace a placeholder ("1 条新消息") with the real one. */
    private static final long SETTLE_MS = 900L;
    /** Re-posts of the same content within this window are ignored. */
    private static final long DEDUPE_MS = 8_000L;
    /** How long the settings page trusts the last Aicy heartbeat. */
    private static final long READY_TTL_MS = 3 * 60_000L;
    /** At most one heartbeat write per process per this window. */
    private static final long READY_WRITE_MS = 30_000L;
    /**
     * A notification that only gets its turn this long after posting is no
     * longer useful: the screen-off/doze path can hold the worker for minutes,
     * and reading an old message just confuses the listener.
     */
    private static final long MAX_QUEUE_AGE_MS = 20_000L;
    /** Covers the settle delay plus one synthesis while the screen is off. */
    private static final long WAKE_TIMEOUT_MS = 60_000L;

    private static volatile long sReadyWrittenAt;
    private static volatile PowerManager.WakeLock sWakeLock;
    /** Newer notifications supersede older ones that are still waiting. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(6),
            runnable -> {
                Thread thread = new Thread(runnable, "aicy-notify-speech");
                thread.setDaemon(true);
                return thread;
            },
            (rejected, executor) -> {
                // Keep the newest sentence: drop the oldest queued one and
                // retry. Discarding the just-posted notification would make
                // the user miss exactly the message they just received.
                if (executor.isShutdown()) return;
                executor.getQueue().poll();
                try {
                    executor.execute(rejected);
                } catch (Throwable ignored) {
                    // shutting down; nothing left to announce
                }
            });

    /** content key -> last spoken timestamp, for apps that post twice. */
    private static final Map<String, Long> SPOKEN = new ConcurrentHashMap<>();

    /** Packages whose exclusion has already been logged in this process. */
    private static final java.util.Set<String> SKIPPED_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private NotifyAnnouncer() {
    }

    /**
     * Entry point for both listeners.
     *
     * @param log hook-process logger; {@code null} from the module's own service
     */
    static void offer(Context context, StatusBarNotification sbn,
                      BiConsumer<String, Throwable> log) {
        try {
            if (context == null || sbn == null || sbn.getNotification() == null) return;
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            String pkg = sbn.getPackageName();
            if (pkg == null || AICY_PACKAGE.equals(pkg) || MODULE_PACKAGE.equals(pkg)
                    || pkg.equals(app.getPackageName())) {
                return;
            }
            boolean insideAicy = AICY_PACKAGE.equals(app.getPackageName());
            if (!insideAicy && hasAicyAccess(app)) {
                // Aicy owns notification access, so its collector is the only
                // speaker. Never fall back on a permission the user gave it.
                return;
            }
            if (!RemoteConfig.master() || !RemoteConfig.notifyEnabled()) return;
            List<String> allowedApps = RemoteConfig.notifyApps();
            if (!allowedApps.isEmpty() && !allowedApps.contains(pkg)) {
                // One line per excluded app is enough: a chatty app would
                // otherwise fill the diagnostics buffer with the same skip.
                if (SKIPPED_LOGGED.add(pkg)) {
                    info(log, "notify: skipped " + pkg + " (不在播报应用内)", null);
                }
                return;
            }
            if (!allowedNow(app)) return;

            Notification notification = sbn.getNotification();
            int flags = notification.flags;
            if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if (sbn.isOngoing()) return;

            String sentence = compose(app, sbn);
            if (sentence.isEmpty()) return;
            if (isDuplicate(pkg + "|" + sentence)) return;

            final Context target = app;
            final boolean fromAicy = insideAicy;
            final long seq = SEQUENCE.incrementAndGet();
            final long queuedAt = System.currentTimeMillis();
            info(log, "notify: queued " + pkg + " · " + sentence, null);
            // Screen-off devices suspend the CPU between the listener callback
            // and the settle delay. Without this the worker can stay parked
            // until some unrelated notification wakes the device, and then
            // speak the old message first.
            keepAwake(target);
            WORKER.execute(() -> {
                try {
                    announce(target, sentence, fromAicy, seq, queuedAt, log);
                } finally {
                    releaseAwakeIfIdle();
                }
            });
        } catch (Throwable error) {
            // A notification callback must never take the host down.
            warn(log, "notify: onNotificationPosted failed", error);
        }
    }

    /** Enforces the same triggers again after the settle delay. */
    private static void announce(Context context, String sentence, boolean insideAicy,
                                 long seq, long queuedAt,
                                 BiConsumer<String, Throwable> log) {
        if (!stillWanted(seq, queuedAt, sentence, log)) return;
        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        // The worker itself can be suspended mid-sleep; re-check after it runs
        // so a backlog does not turn into a delayed replay.
        if (!stillWanted(seq, queuedAt, sentence, log)) return;
        if (!RemoteConfig.master() || !RemoteConfig.notifyEnabled()) return;
        if (!insideAicy && hasAicyAccess(context)) {
            // Aicy held notification access from the start, so the module must
            // never speak on its behalf; the sentence is simply dropped.
            return;
        }
        if (!allowedNow(context)) return;

        if (!MiMoTts.hasKey()) {
            warn(log, "notify: 没有可用的 MiMo Key，先到模块里保存", null);
            return;
        }
        VoiceProfile profile = pickProfile(RemoteConfig.profiles(),
                RemoteConfig.notifyVoice());
        try {
            SpeechPlayer.play(context, profile, sentence);
            info(log, "notify: spoken with " + profile.name + " · " + sentence, null);
        } catch (Throwable error) {
            warn(log, "notify: synthesis/playback failed", error);
        }
    }

    /** A newer notification, or a long suspension, makes this sentence moot. */
    private static boolean stillWanted(long seq, long queuedAt, String sentence,
                                       BiConsumer<String, Throwable> log) {
        if (seq != SEQUENCE.get()) {
            info(log, "notify: dropped superseded · " + sentence, null);
            return false;
        }
        long age = System.currentTimeMillis() - queuedAt;
        if (age > MAX_QUEUE_AGE_MS) {
            info(log, "notify: dropped stale after " + age + "ms · " + sentence, null);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ wake lock

    /**
     * Holds the CPU awake from the moment a notification is queued until its
     * announcement finishes. The worker is otherwise a prime candidate for
     * suspension on an idle, screen-off device, which also freezes the settle
     * delay and the TTS upload.
     */
    private static void keepAwake(Context context) {
        try {
            PowerManager power =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            PowerManager.WakeLock lock = sWakeLock;
            if (lock == null) {
                synchronized (NotifyAnnouncer.class) {
                    lock = sWakeLock;
                    if (lock == null) {
                        lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "AicyUnlock:notify-speech");
                        lock.setReferenceCounted(false);
                        sWakeLock = lock;
                    }
                }
            }
            // Re-acquiring a non-reference-counted lock refreshes its timeout.
            lock.acquire(WAKE_TIMEOUT_MS);
        } catch (Throwable ignored) {
            // The host may not hold WAKE_LOCK; speech still attempts to run.
        }
    }

    /** Releases only when no queued sentence is still waiting behind us. */
    private static void releaseAwakeIfIdle() {
        try {
            if (!WORKER.getQueue().isEmpty()) return;
            PowerManager.WakeLock lock = sWakeLock;
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
            // release is best-effort
        }
    }

    /**
     * The voice used for announcements: the user's explicit pick, else the first
     * ready timbre, else the built-in preset (which only needs the API key).
     * Callers are responsible for checking that a key exists.
     */
    static VoiceProfile pickProfile(List<VoiceProfile> list, String wanted) {
        VoiceProfile fallback = null;
        if (list != null) {
            for (VoiceProfile profile : list) {
                // ready() only checks the configured fields; the clip itself is
                // fetched during synthesis, so selecting a voice stays cheap.
                if (!profile.ready()) continue;
                if (profile.id.equals(wanted)) return profile;
                if (fallback == null) fallback = profile;
            }
        }
        if (fallback != null) return fallback;
        // No timbre yet: a stock MiMo preset still works and keeps the feature
        // usable straight after the key is saved.
        return new VoiceProfile("notify_default", "通知播报", MiMoTts.MODEL_PRESET,
                "", MiMoTts.PRESET_DEFAULT, "", 0);
    }

    /** Master switch + screen/headset/call conditions, re-evaluated live. */
    private static boolean allowedNow(Context context) {
        if (!RemoteConfig.master() || !RemoteConfig.notifyEnabled()) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            int mode = audio.getMode();
            if (mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE) {
                return false;
            }
        }
        if (RemoteConfig.notifyScreenOffOnly()) {
            PowerManager power =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null && power.isInteractive()) return false;
        }
        if (!RemoteConfig.notifyHeadsetOnly()) return true;
        return TrustedHeadsets.allowed(audio, RemoteConfig.notifyTrusted());
    }

    private static boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        Long last = SPOKEN.get(key);
        if (last != null && now - last < DEDUPE_MS) return true;
        SPOKEN.put(key, now);
        if (SPOKEN.size() > 32) {
            SPOKEN.entrySet().removeIf(entry -> now - entry.getValue() > DEDUPE_MS);
        }
        return false;
    }

    // ------------------------------------------------------------- settings

    /**
     * Records from Aicy's process that the collector hook is loaded.
     *
     * <p>Notification access alone is not enough: after a module update, Aicy
     * still holds the permission until it is restarted, but the new hook is not
     * in the running process yet. The settings page uses this heartbeat to say
     * whether announcements really run inside Aicy.
     */
    static void markAicyReady(Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - sReadyWrittenAt < READY_WRITE_MS) return;
        sReadyWrittenAt = now;
        try {
            Bundle extras = new Bundle();
            extras.putLong("at", now);
            context.getContentResolver().call(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    "notify-ready", null, extras);
        } catch (Throwable ignored) {
            // provider missing: the next heartbeat retries
        }
    }

    /** True while an Aicy process reports its notification hook alive. */
    static boolean aicyReady(Context context) {
        if (context == null) return false;
        try {
            long at = context.getSharedPreferences(ConfigProvider.PREFS,
                    Context.MODE_PRIVATE).getLong(ConfigProvider.KEY_NOTIFY_READY_AT, 0L);
            return at > 0 && System.currentTimeMillis() - at < READY_TTL_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** True when Aicy's own collector may run, i.e. the preferred path is live. */
    static boolean hasAicyAccess(Context context) {
        return hasListenerAccess(context, AICY_PACKAGE);
    }

    /** True when the module's fallback listener may run. */
    static boolean hasModuleAccess(Context context) {
        return hasListenerAccess(context, MODULE_PACKAGE);
    }

    private static boolean hasListenerAccess(Context context, String packageName) {
        try {
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) return false;
            for (String part : flat.split(":")) {
                ComponentName enabled = ComponentName.unflattenFromString(part);
                if (enabled != null && packageName.equals(enabled.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // treat as not granted
        }
        return false;
    }

    // ----------------------------------------------------------------- text

    /** Builds the spoken sentence: "微信，张三：晚上一起吃饭吗". */
    private static String compose(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return "";

        String title = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) {
            text = clean(extras.getCharSequence(Notification.EXTRA_TEXT));
        }

        // MessagingStyle carries the newest message separately; prefer it over
        // the summary line some chat apps put in EXTRA_TEXT.
        String sender = "";
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            Parcelable last = messages[messages.length - 1];
            if (last instanceof Bundle) {
                Bundle message = (Bundle) last;
                String messageText = clean(message.getCharSequence("text"));
                String messageSender = clean(message.getCharSequence("sender"));
                if (!messageText.isEmpty()) {
                    text = messageText;
                    sender = messageSender;
                }
            }
        }

        if (text.isEmpty()) {
            // Some notifications only carry a title; speaking it is still useful.
            text = title;
            title = "";
        }
        if (text.isEmpty()) {
            // Last resort: the ticker used by older apps.
            text = clean(notification.tickerText);
        }
        if (text.isEmpty()) return "";

        String app = appLabel(context, sbn.getPackageName());
        StringBuilder sentence = new StringBuilder(app);
        if (!title.isEmpty() && !title.equalsIgnoreCase(app)
                && !text.startsWith(title)) {
            sentence.append("，").append(title);
        }
        if (!sender.isEmpty() && !sender.equalsIgnoreCase(title)
                && !sender.equalsIgnoreCase(app)) {
            sentence.append("，").append(sender).append("说");
        }
        sentence.append("：").append(text);
        return sentence.toString();
    }

    /** Trims a notification field to one spoken-friendly line. */
    private static String clean(CharSequence value) {
        if (value == null) return "";
        String text = value.toString()
                .replace('\u200b', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS).trim() + "…";
        }
        return text;
    }

    static String appLabel(Context context, String pkg) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // fall through to the package name
        }
        return pkg == null ? "" : pkg;
    }

    // --------------------------------------------------------------- logging

    private static void info(BiConsumer<String, Throwable> log, String message,
                             Throwable error) {
        if (log != null) {
            log.accept(message, error);
        } else {
            Log.i(TAG, message);
        }
    }

    private static void warn(BiConsumer<String, Throwable> log, String message,
                             Throwable error) {
        if (log != null) {
            log.accept(message, error);
        } else {
            Log.w(TAG, message, error);
        }
    }
}
