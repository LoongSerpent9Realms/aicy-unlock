package dev.loong.aicyunlock;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/**
 * 把任意音频（mp3 / m4a-aac / ogg-vorbis / wav…）解码成规范的单声道 16 kHz WAV。
 *
 * <p><b>为什么必须转</b>：MiMo 的复刻接口没有 voice_id 注册，参考音频以 DataURL
 * 随<b>每一次</b>请求重发。真机实测（21:06，模块 2.8.0）每句的请求体恒为
 * 428624 B，光等响应头就3.0–5.6 s，客户端多数时候等不到首字节就断开。
 * 裁到 4 秒后请求体约 125 KB，这才是MiMo 侧唯一的固定开销大头。
 *
 * <p><b>为什么走 MediaExtractor + MediaCodec</b>：这是系统原生解码路径，本机 ROM
 * 已实测可用——OMX.google.mp3.decoder、c2.qti.aac.hw.decoder、OMX.google.vorbis.decoder
 * 都在；Ogg/Vorbis 570 KB 曾完整解出 24.00 s PCM。mp3 与 AAC 和它同一条接口。
 * 不引第三方库。
 *
 * <p><b>这台 ROM 的三个坑（都实测撞过）</b>：
 * <ol>
 *   <li>{@code setDataSource(String path)} 对 app 私有目录与 external 目录都抛
 *       {@code IOException}，必须走 {@link #openExtractor} 的 FileDescriptor；</li>
 *   <li>{@code getCacheDir()} 不可写（{@code FileNotFoundException}），
 *       中间文件只能落 {@code getExternalFilesDir(null)}；</li>
 *   <li>跨安装后 external 目录属主会变（旧 uid → 新 uid），老文件读不到，
 *       需 {@code su chown} 修一次。</li>
 * </ol>
 *
 * <p>降采样用线性插值：参考音频只需携带音色特征，MiMo 侧自己会重采样到 24 kHz，
 * 这里不必追求音频保真度。
 */
final class ClipConverter {

    /** MiMo 期望的参考音频：16 kHz 单声道。 */
    static final int TARGET_RATE = 16000;

    /** 复刻只需要音色特征，4 秒足够；再长纯属把延迟买回来。 */
    static final int MAX_SECONDS = 4;

    private ClipConverter() {
    }

    /** Why a conversion failed, or null when it worked. */
    static final class Result {
        final byte[] wav;
        final String error;
        final int sourceBytes;
        final float sourceSeconds;

        Result(byte[] wav, int sourceBytes, float sourceSeconds) {
            this.wav = wav;
            this.error = null;
            this.sourceBytes = sourceBytes;
            this.sourceSeconds = sourceSeconds;
        }

        Result(String error) {
            this.wav = null;
            this.error = error;
            this.sourceBytes = 0;
            this.sourceSeconds = 0f;
        }

        boolean ok() {
            return error == null;
        }
    }

    /**
     * Reads {@code source} and returns canonical 16 kHz mono WAV bytes.
     *
     * <p>Already-canonical WAV input is only trimmed, not re-encoded, so a clip
     * recorded by {@link RecordActivity} stays bit-identical below the cut.
     */
    static Result toWav(File source) {
        if (source == null || !source.exists() || source.length() < 44) {
            return new Result("文件不存在");
        }
        byte[] raw = read(source);
        if (raw == null) {
            return new Result("读取失败");
        }
        // Fast path: our own recording, already 16 kHz mono PCM16.
        if (isCanonicalWav(raw)) {
            byte[] trimmed = trimPcm(raw, MAX_SECONDS);
            return new Result(trimmed, raw.length, secondsOf(trimmed));
        }
        return decodeToWav(source);
    }

    // ------------------------------------------------------------------
    // decode
    // ------------------------------------------------------------------

    private static Result decodeToWav(File file) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = openExtractor(file);
            int track = -1;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    mime = m;
                    break;
                }
            }
            if (track < 0) {
                return new Result("没有音频轨（" + mime + "）");
            }
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(extractor.getTrackFormat(track), null, null, 0);
            codec.start();

            int outRate = 0;
            int outChannels = 0;
            int decodedBytes = 0;
            // Cap on raw PCM we are willing to hold: MAX_SECONDS of 48 kHz stereo
            // is ~768 KB, but a 10-minute song would be 100 MB. Bail out instead.
            final int pcmCap = 8 * 1024 * 1024;
            boolean inputDone = false;
            boolean outputDone = false;
            boolean overflow = false;
            java.io.ByteArrayOutputStream pcm =
                    new java.io.ByteArrayOutputStream(1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 30_000L;

            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        decodedBytes += info.size;
                        if (decodedBytes > pcmCap) {
                            overflow = true;
                            break;
                        }
                        ByteBuffer buf = codec.getOutputBuffer(outIndex);
                        byte[] chunk = new byte[info.size];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            if (outRate <= 0) {
                return new Result("解码器没给出采样率");
            }
            if (!outputDone) {
                return new Result(overflow ? "音频过长" : "解码超时");
            }
            byte[] interleaved = pcm.toByteArray();
            if (interleaved.length < 2) {
                return new Result("解出 0 个采样");
            }
            byte[] mono = toMono(interleaved, outChannels);
            byte[] resampled = resample(mono, outRate, TARGET_RATE);
            byte[] trimmed = trimPcm(wavWrap(resampled), MAX_SECONDS);
            return new Result(trimmed, (int) file.length(),
                    decodedBytes / 2f / outRate);
        } catch (Throwable error) {
            return new Result("解码失败 "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "/" + error.getMessage()));
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
                // nothing to do
            }
            try {
                if (extractor != null) extractor.release();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    /**
     * {@code setDataSource(String)} throws {@code IOException} for app-scoped
     * paths on this ROM; the descriptor form is the one that works.
     */
    private static MediaExtractor openExtractor(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(in.getFD());
        } catch (Throwable error) {
            in.close();
            throw error;
        }
        return extractor;
    }

    // ------------------------------------------------------------------
    // PCM helpers
    // ------------------------------------------------------------------

    /** Averages interleaved channels down to mono, in place of a new array. */
    private static byte[] toMono(byte[] pcm, int channels) {
        if (channels <= 1) return pcm;
        int frames = pcm.length / (2 * channels);
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = (f * channels + c) * 2;
                sum += (short) ((pcm[at] & 0xFF) | ((pcm[at + 1] & 0xFF) << 8));
            }
            short v = (short) (sum / channels);
            out[f * 2] = (byte) (v & 0xFF);
            out[f * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    /** Linear-interpolating resampler; adequate for timbre features. */
    private static byte[] resample(byte[] pcm, int from, int to) {
        if (from == to) return pcm;
        int inSamples = pcm.length / 2;
        if (inSamples == 0) return pcm;
        int outSamples = Math.max(1, (int) ((long) inSamples * to / from));
        byte[] out = new byte[outSamples * 2];
        double step = (double) from / to;
        for (int i = 0; i < outSamples; i++) {
            double src = i * step;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, inSamples - 1);
            double frac = src - i0;
            int a = (short) ((pcm[i0 * 2] & 0xFF) | ((pcm[i0 * 2 + 1] & 0xFF) << 8));
            int b = (short) ((pcm[i1 * 2] & 0xFF) | ((pcm[i1 * 2 + 1] & 0xFF) << 8));
            int v = (int) Math.round(a + (b - a) * frac);
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] wavWrap(byte[] pcm) {
        return wav(pcm, TARGET_RATE);
    }

    /** 44-byte canonical WAV header + PCM16 LE. */
    private static byte[] wav(byte[] pcm, int rate) {
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(new byte[]{'R', 'I', 'F', 'F'}, 0, out, 0, 4);
        putInt(out, 4, out.length - 8);
        System.arraycopy(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}, 0, out, 8, 8);
        putInt(out, 16, 16);
        putShort(out, 20, (short) 1);
        putShort(out, 22, (short) 1);
        putInt(out, 24, rate);
        putInt(out, 28, rate * 2);
        putShort(out, 32, (short) 2);
        putShort(out, 34, (short) 16);
        System.arraycopy(new byte[]{'d', 'a', 't', 'a'}, 0, out, 36, 4);
        putInt(out, 40, pcm.length);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    private static boolean isCanonicalWav(byte[] b) {
        if (b.length < 44) return false;
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return false;
        if (b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') return false;
        return le16(b, 22) > 0 && le32(b, 24) > 0 && le16(b, 34) == 16;
    }

    /** Trims the data chunk of a canonical WAV, rewriting both size fields. */
    private static byte[] trimPcm(byte[] wav, int maxSecs) {
        if (!isCanonicalWav(wav)) return wav;
        int rate = (int) le32(wav, 24);
        int channels = le16(wav, 22);
        int dataAt = 44;
        int dataLen = Math.min((int) le32(wav, 40), wav.length - dataAt);
        if (dataLen <= 0) return wav;
        int keep = Math.min(dataLen, rate * maxSecs * channels * 2);
        if (keep >= dataLen) return wav;
        byte[] out = new byte[dataAt + keep];
        System.arraycopy(wav, 0, out, 0, dataAt);
        System.arraycopy(wav, dataAt, out, dataAt, keep);
        putInt(out, 4, out.length - 8);
        putInt(out, dataAt - 4, keep);
        return out;
    }

    private static float secondsOf(byte[] wav) {
        if (!isCanonicalWav(wav)) return 0f;
        int rate = (int) le32(wav, 24);
        int channels = le16(wav, 22);
        int len = Math.min((int) le32(wav, 40), wav.length - 44);
        if (rate <= 0 || channels <= 0) return 0f;
        return len / (2f * rate * channels);
    }

    private static byte[] read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != buf.length) return null;
            return buf;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int at, short v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }
}