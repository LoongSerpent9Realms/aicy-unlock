package dev.loong.aicyunlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The spoken cue that opens every round of a custom wake word.
 *
 * <p>Meizu ships prompt audio for the five preset phrases only, so a word the
 * user typed has nothing to play and the engine's own tip callback - the thing
 * that starts a capture - never runs. The module fills that gap with five cue
 * lines shipped in {@code assets/wake_cues}: one is played here, in the module's
 * own process, while the engine process waits for the reply before it opens its
 * microphone. The cue is finished before the mic opens, so it never lands in a
 * training sample.
 *
 * <p>The lines are pre-recorded rather than synthesized because synthesis
 * cannot be part of this path: it needs the user's own API key, which stays out
 * of the app, and its few seconds of waiting would make every round stand silent
 * before the microphone opens. They speak the generic wording the vendor uses
 * ("你定义的提示词") because the word itself is different for every user; the
 * page already shows it on screen.
 *
 * <p>Answering from this process rather than from inside the engine also keeps
 * the API key on this side of the binder: the caller gets back whether the line
 * was spoken, never a credential.
 */
final class WakeCue {

    /** Provider method the engine process calls to have one round announced. */
    static final String METHOD = "wake-cue";
    static final String KEY_ROUND = "round";
    /** Reply keys, read by the hook inside the engine. */
    static final String KEY_PLAYED = "played";
    static final String KEY_DETAIL = "detail";

    /** A custom wake word is captured in this many rounds. */
    static final int ROUNDS = 5;

    private static final String DIR = "wake_cues";

    private WakeCue() {
    }

    /**
     * Plays round {@code round} and reports the outcome.
     *
     * <p>{@code played} is true only once the audio has run to its end. Every
     * other case (feature off, asset missing, playback failed) answers false with
     * a reason, and the caller opens the microphone anyway instead of hanging the
     * session on a voice that is not there.
     */
    static Bundle play(Context context, int round) {
        SharedPreferences sp = context.getSharedPreferences(
                ConfigProvider.PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(ConfigProvider.KEY_WAKE_WORD, true)) {
            return answer(false, "唤醒词功能已关闭");
        }
        byte[] pcm = asset(context, round);
        if (pcm == null) {
            return answer(false, "第" + round + "轮内置提示音缺失");
        }
        try {
            SpeechPlayer.playPcm(context, pcm);
        } catch (Throwable error) {
            return answer(false, "播放失败：" + error);
        }
        return answer(true, "第" + round + "轮提示音（内置）");
    }

    private static Bundle answer(boolean played, String detail) {
        Bundle reply = new Bundle();
        reply.putBoolean(KEY_PLAYED, played);
        reply.putString(KEY_DETAIL, detail);
        return reply;
    }

    /**
     * The whole clip for one round, or null when the asset is not there.
     *
     * <p>{@code AudioTrack} is written in one go, so the clip is read into memory
     * first; at four seconds of 24 kHz mono 16-bit that is under 200 KB, and it
     * keeps the write path free of a decoder.
     */
    private static byte[] asset(Context context, int round) {
        try (InputStream in = context.getAssets().open(DIR + "/round" + round + ".pcm")) {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                pcm.write(buffer, 0, read);
            }
            return pcm.size() > 0 ? pcm.toByteArray() : null;
        } catch (Throwable missing) {
            return null;
        }
    }
}
