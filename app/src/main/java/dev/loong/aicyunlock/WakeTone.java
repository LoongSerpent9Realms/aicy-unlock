package dev.loong.aicyunlock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * The Flyme 12.5 style "I am listening" tone, played when Aicy is summoned.
 *
 * <p>12.x played {@code R.raw.tone_start} through
 * {@code VA_SoundPoolWarningToneHandler} every time the recogniser started. The
 * resource and that handler are both still in 13.0.56 - the caller is not: the
 * three call sites that remain are the legacy float module, the Aicy Touch card
 * and the cross-process {@code displayWindow} binder, and the QuickVoice sheet
 * that replaced all of them has none. Adding the sound therefore means reaching
 * the moment the sheet is opened and playing Aicy's own file; nothing is
 * shipped and nothing is synthesised, so the cue costs no time and no key.
 *
 * <p>The trigger is {@code QuickVoiceService#onStartCommand} with the
 * {@code QUICK_VOICE_TRIGGER} or {@code QUICK_VOICE_BRIDGE} action. Every route
 * into the sheet funnels through it - the voice wake and the headset key post
 * that intent themselves ({@code StartBroadcastReceiver},
 * {@code TwsBroadcastReceiver}, {@code VoiceCommandActivity}) and the in-process
 * launchers build it first - and unlike the view classes those two names cannot
 * be renamed: the service is declared in the manifest and the actions are the
 * contract other components call us with.
 */
final class WakeTone {

    /** The two actions that open the sheet; anything else is a control command. */
    private static final String ACTION_TRIGGER =
            "com.flyme.agentos.app.phone.action.QUICK_VOICE_TRIGGER";
    private static final String ACTION_BRIDGE =
            "com.flyme.agentos.app.phone.action.QUICK_VOICE_BRIDGE";

    /** Two wakes that close in on each other must not produce two tones. */
    private static final long MIN_GAP_MS = 1_500L;

    private static volatile SoundPool pool;
    /** 0 until the asset has been loaded; SoundPool needs a completed load. */
    private static volatile int soundId;
    private static volatile boolean loaded;
    private static volatile long lastAt;

    private WakeTone() {
    }

    /**
     * Plays the tone for a start command that opens the sheet.
     *
     * <p>Runs on the main thread and only starts asynchronous work:
     * {@code SoundPool} loads and plays off-thread, so the wake never waits on
     * the tone.
     */
    static void onStartCommand(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!isWake(action)) return;
        if (!RemoteConfig.master() || !RemoteConfig.wakeTone()) return;
        long now = System.currentTimeMillis();
        if (now - lastAt < MIN_GAP_MS) return;
        lastAt = now;
        Context context = RemoteConfig.app();
        if (context == null) return;
        android.util.Log.i("AicyUnlock", "wake tone request: " + action);
        try {
            trigger(context);
        } catch (Throwable error) {
            android.util.Log.w("AicyUnlock", "wake tone failed: " + error, error);
        }
    }

    /** True for the two actions that summon Aicy, not for a control command. */
    static boolean isWake(String action) {
        return ACTION_TRIGGER.equals(action) || ACTION_BRIDGE.equals(action);
    }

    /** True for Aicy's own voice sheet, whatever R8 renamed its view to. */
    static boolean isVoiceSheet(String name) {
        return name != null && (name.contains("voicequick") || name.contains("QuickVoice"));
    }

    /**
     * Loads once, then plays on every wake.
     *
     * <p>{@code SoundPool.load} is asynchronous and Aicy's own handler has the
     * same shape: the first wake plays from the load-complete callback, later
     * ones play straight away.
     */
    private static void trigger(Context context) {
        int id = resource(context);
        if (id == 0) {
            android.util.Log.w("AicyUnlock",
                    "wake tone skipped: no raw/tone_start in " + context.getPackageName());
            return;
        }
        SoundPool current = pool;
        if (current != null) {
            play(current);
            return;
        }
        final SoundPool created = build();
        pool = created;
        created.setOnLoadCompleteListener((source, sampleId, status) -> {
            loaded = true;
            android.util.Log.i("AicyUnlock",
                    "wake tone loaded sample=" + sampleId + " status=" + status);
            play(created);
        });
        soundId = created.load(context, id, 1);
    }

    private static void play(SoundPool ready) {
        if (!loaded || soundId == 0) return;
        ready.play(soundId, 1f, 1f, 1, 0, 1f);
        android.util.Log.i("AicyUnlock", "wake tone played");
    }

    /** {@code R.raw.tone_start} of the host app, or 0 when it has no such file. */
    private static int resource(Context context) {
        try {
            return context.getResources().getIdentifier(
                    "tone_start", "raw", context.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Sonification attributes: a cue about the assistant's own state, so it is
     * routed and ducked like Aicy's stock tone rather than like media.
     */
    private static SoundPool build() {
        return new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
    }
}
