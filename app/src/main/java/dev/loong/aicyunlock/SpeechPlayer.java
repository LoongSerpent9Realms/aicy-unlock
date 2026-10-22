package dev.loong.aicyunlock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Plays the PCM returned by {@link MiMoTts} on the module's own process.
 *
 * <p>The hooked Aicy path never uses this class: Aicy parses our SSE reply and
 * feeds its own AudioTrack. Notification announcements, however, originate here
 * and have no host pipeline to hand the bytes to, so this is the playback end.
 *
 * <p>MiMo always answers 24 kHz / 16 bit / mono PCM (the same format Aicy
 * expects), so no resampling is involved.
 */
final class SpeechPlayer {

    static final int SAMPLE_RATE = 24_000;

    private SpeechPlayer() {
    }

    /**
     * Synthesizes {@code text} and plays it synchronously.
     *
     * <p>Must not run on the main thread: both the HTTP request and the
     * AudioTrack writes block.
     */
    static void play(Context context, VoiceProfile profile, String text) throws Exception {
        feed(context, track -> MiMoTts.stream(profile, text, pcm -> write(track, pcm)));
    }

    /**
     * Plays PCM that is already in hand, so a line synthesized once can be
     * replayed without another request.
     *
     * <p>Returns with the last audible sample: a blocking {@code MODE_STREAM}
     * write only completes when the track has played its buffer down.
     */
    static void playPcm(Context context, byte[] pcm) throws Exception {
        feed(context, track -> write(track, pcm));
    }

    private static void write(AudioTrack track, byte[] pcm) throws Exception {
        int at = 0;
        while (at < pcm.length) {
            int written = track.write(pcm, at, pcm.length - at);
            if (written <= 0) {
                throw new IllegalStateException("AudioTrack write=" + written);
            }
            at += written;
        }
    }

    /** Where the bytes come from: a live synthesis or a finished buffer. */
    private interface Playback {
        void run(AudioTrack track) throws Exception;
    }

    /**
     * Owns the audio focus, the track and their release; {@code playback} only
     * decides where the bytes come from.
     */
    private static void feed(Context context, Playback playback) throws Exception {
        AudioManager manager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFocusRequest focus = null;
        if (manager != null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .build();
            manager.requestAudioFocus(focus);
        }

        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min > 0 ? min : 0, 32 * 1024);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack 初始化失败");
            }
            track.play();
            playback.run(track);
            track.stop();
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {
                    // nothing else to release
                }
            }
            if (manager != null && focus != null) {
                manager.abandonAudioFocusRequest(focus);
            }
        }
    }
}
