package dev.loong.aicyunlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiMo (小米) 语音合成客户端，OpenAI 兼容协议。
 *
 * <p>端点 {@code https://api.xiaomimimo.com/v1/chat/completions}，鉴权
 * {@code Authorization: Bearer <key>}，流式返回每个 chunk 在
 * {@code choices[0].delta.audio.data}，内容是 24 kHz / 16 bit / 单声道 PCM 的
 * Base64。Aicy 的播放链路正是同样的格式，所以这里只搬运，不做重采样。
 *
 * <p>三种用法，对应三个模型：
 * <ul>
 *   <li>{@code mimo-v2.5-tts-voicedesign} — user 消息写音色描述，assistant 写要念的文本
 *       （文字造音色）</li>
 *   <li>{@code mimo-v2.5-tts-voiceclone} — {@code audio.voice} 填
 *       {@code data:audio/mpeg;base64,…} 音频样本（音色复刻）</li>
 *   <li>{@code mimo-v2.5-tts} — 预置音色，{@code audio.voice} 填音色 id</li>
 * </ul>
 */
final class MiMoTts {

    static final String DEFAULT_ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
    static final String MODEL_DESIGN = "mimo-v2.5-tts-voicedesign";
    static final String MODEL_CLONE = "mimo-v2.5-tts-voiceclone";
    static final String MODEL_PRESET = "mimo-v2.5-tts";
    static final String PRESET_DEFAULT = "mimo_default";

    /** Base64 payload larger than this is rejected: it has to cross binder. */
    static final int MAX_SAMPLE_BASE64 = 900_000;

    /**
     * 参考音频上传前裁到这么长。
     *
     * <p>实测：3 s@16 kHz（128 KB base64）首字节 1.9 s，4.8 s@24 kHz（307 KB）
     * 要 5.3 s——等待几乎全花在上传样本上，而不是合成。复刻只需要音色特征，
     * 4 s 已经足够，再长纯属把延迟买回来。
     */
    private static final int MAX_REF_SECONDS = 4;

    /** sampleKey -> 裁好并重新编码的 Base64，避免每次请求重复解析几百 KB。 */
    private static final Map<String, String> TRIMMED = new ConcurrentHashMap<>();

    /**
     * 参考音频，必要时截到 {@link #MAX_REF_SECONDS}。
     *
     * <p>只处理 16 bit PCM 的 WAV；mp3 之类的样本原样返回（服务端自己会解）。
     */
    private static String preparedClip(VoiceProfile profile) {
        String raw = RemoteConfig.clip(profile);
        if (raw == null || raw.isEmpty()) return "";
        String cached = TRIMMED.get(profile.sampleKey);
        if (cached != null) return cached;
        String out = raw;
        try {
            out = trimWav(raw, MAX_REF_SECONDS);
        } catch (Throwable ignored) {
            out = raw; // 解析不了就照旧上传，宁可慢也不能哑
        }
        TRIMMED.put(profile.sampleKey, out);
        return out;
    }

    /** 把 16 bit PCM WAV 的 data 块截到 {@code maxSecs} 秒，其余格式原样返回。 */
    private static String trimWav(String b64, int maxSecs) {
        byte[] wav = Base64.getDecoder().decode(b64);
        if (wav.length < 44) return b64;
        if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return b64;
        if (wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') return b64;

        int channels = 0, rate = 0, bits = 0;
        int dataAt = -1, dataLen = -1;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            int size = le32(wav, pos + 4);
            if (size < 0) break;
            if (wav[pos] == 'f' && wav[pos + 1] == 'm' && wav[pos + 2] == 't'
                    && wav[pos + 3] == ' ') {
                channels = le16(wav, pos + 10);
                rate = le32(wav, pos + 12);
                bits = le16(wav, pos + 22);
            } else if (wav[pos] == 'd' && wav[pos + 1] == 'a' && wav[pos + 2] == 't'
                    && wav[pos + 3] == 'a') {
                dataAt = pos + 8;
                dataLen = Math.min(size, wav.length - dataAt);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataAt < 0 || dataLen < 0 || channels <= 0 || rate <= 0 || bits != 16) return b64;

        int blockAlign = channels * 2;
        int keep = rate * maxSecs * blockAlign;
        if (keep >= dataLen) return b64; // 本来就够短

        byte[] out = new byte[dataAt + keep];
        System.arraycopy(wav, 0, out, 0, dataAt);
        System.arraycopy(wav, dataAt, out, dataAt, keep);
        putLe32(out, 4, out.length - 8);      // RIFF size
        putLe32(out, dataAt - 4, keep);        // data chunk size
        return Base64.getEncoder().encodeToString(out);
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    private static void putLe32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    /** Receives raw PCM16 mono 24 kHz chunks in arrival order. */
    interface Sink {
        void pcm(byte[] data) throws Exception;
    }

    /**
     * 模块日志入口。合成时序必须落在模块自己的日志里：设备上的 logcat
     * 根本读不到这些行，出问题只能靠猜。
     */
    private static volatile java.util.function.BiConsumer<String, Throwable> trace;

    static void setTrace(java.util.function.BiConsumer<String, Throwable> sink) {
        trace = sink;
    }

    private static void tr(String message) {
        java.util.function.BiConsumer<String, Throwable> sink = trace;
        if (sink != null) sink.accept(message, null);
    }

    private MiMoTts() {
    }

    /**
     * Turns whatever the user pasted into a full chat/completions URL.
     *
     * <p>Accepts a base ({@code https://host/v1}), a bare host, or the full
     * endpoint. Anything unrecognised is treated as a base and gets the
     * OpenAI-compatible suffix appended, which is also what a self-hosted
     * proxy expects.
     */
    static String normalizeEndpoint(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return DEFAULT_ENDPOINT;
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        return value + "/v1/chat/completions";
    }

    /** Everything needed for one synthesis is present. */
    static boolean ready(VoiceProfile profile) {
        if (!RemoteConfig.ttsEnabled()) return false;
        String key = RemoteConfig.mimoKey();
        if (key == null || key.isEmpty()) return false;
        if (profile == null) return false;
        if (MODEL_DESIGN.equals(profile.model)) {
            String desc = profile.desc;
            return desc != null && !desc.isEmpty();
        }
        if (MODEL_CLONE.equals(profile.model)) {
            String sample = RemoteConfig.clip(profile);
            return sample != null && !sample.isEmpty();
        }
        return true;
    }

    static boolean hasKey() {
        String key = RemoteConfig.mimoKey();
        return key != null && !key.isEmpty();
    }

    /** Human-readable reason why {@link #ready} is false. */
    static String notReadyReason(VoiceProfile profile) {
        if (!hasKey()) return "未填 MiMo API Key";
        if (profile == null) return "找不到该音色配置";
        if (MODEL_DESIGN.equals(profile.model)) {
            return profile.desc == null || profile.desc.isEmpty()
                    ? "该音色未填描述" : "已停用";
        }
        if (MODEL_CLONE.equals(profile.model)) {
            String sample = RemoteConfig.clip(profile);
            if (sample == null || sample.isEmpty()) {
                // The clip may well be on disk — a failed fetch looks exactly
                // the same from here, so say which it is.
                return "该音色缺参考音频（读取失败或未录音）";
            }
            return "参考音频过大（" + sample.length() + " 字符）";
        }
        return "已停用";
    }

    /**
     * Aicy fires the same sentence twice in parallel (a prewarm plus the real
     * playback), and MiMo answers the second one with HTTP 429. Serialising the
     * calls avoids the 429 — but it also made playback wait for a full synthesis
     * it did not need, which is where most of the perceived lag came from.
     *
     * <p>So the gate stays (still one request in flight), and on top of it the
     * result of a finished synthesis is kept for {@link #CACHE_TTL_MS}. The
     * second of the pair now replays from memory instead of re-synthesising.
     */
    private static final java.util.concurrent.Semaphore GATE =
            new java.util.concurrent.Semaphore(1);

    private static final int CACHE_MAX = 8;
    private static final long CACHE_TTL_MS = 30_000L;

    private static final class Cached {
        final byte[] pcm;
        final long at;

        Cached(byte[] pcm) {
            this.pcm = pcm;
            this.at = System.currentTimeMillis();
        }
    }

    /** key -> finished PCM. Access-ordered so the eldest entry is the LRU one. */
    private static final Map<String, Cached> CACHE =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** Per-sentence locks, so a duplicate waits for its twin instead of repeating it. */
    private static final Map<String, Object> INFLIGHT = new ConcurrentHashMap<>();

    /**
     * 两次读取之间的等待上限。
     *
     * <p>Aicy 自己会超时：曾经一次合成让网关等了 78 秒，app 早就断开，我们回写
     * 就是一记 Broken pipe，用户听到的是彻底的静音。15 秒内吐不出首字节就该
     * 立刻失败，把兜底音频的时间留出来。
     */
    private static final int READ_BETWEEN_CHUNKS_MS = 15_000;

    /** 0.1 s of 24 kHz mono PCM16 — the granularity a replay is chopped into. */
    private static final int REPLAY_CHUNK = 4800;

    private static String cacheKey(VoiceProfile profile, String text) {
        // A rename does not change the audio, but a new model, description or
        // reference clip does — keep those in the key so an edit is never served
        // from a stale entry.
        return RemoteConfig.ttsEndpoint() + "|" + RemoteConfig.ttsModel() + "|"
                + profile.id + "|" + profile.model
                + "|" + profile.sampleKey + "|" + profile.desc.hashCode() + "|" + text;
    }

    private static byte[] cacheGet(String key) {
        synchronized (CACHE) {
            Cached hit = CACHE.get(key);
            if (hit == null) return null;
            if (System.currentTimeMillis() - hit.at > CACHE_TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            return hit.pcm;
        }
    }

    private static void cachePut(String key, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (CACHE) {
            CACHE.put(key, new Cached(pcm));
        }
    }

    /**
     * Synthesizes {@code text} with the parameters of {@code profile} and pushes
     * PCM chunks into {@code sink}.
     *
     * @return total bytes received
     */
    static int stream(VoiceProfile profile, String text, Sink sink) throws Exception {
        String key = cacheKey(profile, text);

        byte[] hit = cacheGet(key);
        if (hit != null) {
            replay(hit, sink);
            return hit.length;
        }

        Object lock = INFLIGHT.computeIfAbsent(key, unused -> new Object());
        try {
            synchronized (lock) {
                // The twin may have finished while we were queuing for the lock.
                hit = cacheGet(key);
                if (hit != null) {
                    replay(hit, sink);
                    return hit.length;
                }

                ByteArrayOutputStream acc = new ByteArrayOutputStream();
                GATE.acquire();
                int total;
                try {
                    total = streamOnce(profile, text, chunk -> {
                        acc.write(chunk);
                        sink.pcm(chunk);
                    }, true);
                } finally {
                    GATE.release();
                }
                cachePut(key, acc.toByteArray());
                return total;
            }
        } finally {
            INFLIGHT.remove(key, lock);
        }
    }

    /** Pushes cached PCM out in playback-sized slices so the player starts early. */
    private static void replay(byte[] pcm, Sink sink) throws Exception {
        for (int at = 0; at < pcm.length; at += REPLAY_CHUNK) {
            int len = Math.min(REPLAY_CHUNK, pcm.length - at);
            byte[] slice = new byte[len];
            System.arraycopy(pcm, at, slice, 0, len);
            sink.pcm(slice);
        }
    }

    /**
     * 后台把复刻音色的参考音频先裁好、编码好。
     *
     * <p>{@link #preparedClip} 要解开几百 KB 的 Base64 再重新编码，第一次跑会
     * 花几十毫秒——放在合成路径上就是白等。目录刷新时先跑一遍，用户点播放时
     * 只剩读缓存。
     */
    static void warmup(java.util.List<VoiceProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return;
        Thread thread = new Thread(() -> {
            for (VoiceProfile profile : profiles) {
                if (!MODEL_CLONE.equals(profile.model)) continue;
                if (profile.sampleKey.isEmpty()) continue;
                try {
                    preparedClip(profile);
                } catch (Throwable ignored) {
                    // a broken clip must not take the warmup thread down
                }
            }
        }, "aicy-clip-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private static int streamOnce(VoiceProfile profile, String text, Sink sink,
                                  boolean mayRetry) throws Exception {
        String key = RemoteConfig.mimoKey();
        String endpoint = RemoteConfig.ttsEndpoint();
        String body = buildBody(profile, text);
        byte[] requestBody = body.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(READ_BETWEEN_CHUNKS_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        long sentAt = System.currentTimeMillis();
        try (OutputStream out = conn.getOutputStream()) {
            out.write(requestBody);
        }

        int code = conn.getResponseCode();
        tr("MiMo: " + modelOf(profile) + " body=" + requestBody.length
                + "B 响应头 " + (System.currentTimeMillis() - sentAt) + "ms HTTP " + code);
        InputStream response = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (response == null) {
            throw new IllegalStateException("MiMo HTTP " + code + " 无响应体");
        }
        if (code >= 400) {
            String error = readAll(response);
            // Rate limited even after serialising: wait out the window and try
            // once more, otherwise the utterance is simply dropped in silence.
            if (code == 429 && mayRetry) {
                conn.disconnect();
                Thread.sleep(1200);
                return streamOnce(profile, text, sink, false);
            }
            throw new IllegalStateException("MiMo HTTP " + code + " " + trim(error));
        }

        AtomicInteger total = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        long readStarted = System.currentTimeMillis();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            byte[] pcm = extractPcm(payload);
            if (pcm == null || pcm.length == 0) continue;
            if (events.incrementAndGet() == 1) {
                tr("MiMo: 首字节 " + (System.currentTimeMillis() - readStarted) + "ms");
            }
            total.addAndGet(pcm.length);
            sink.pcm(pcm);
        }
        tr("MiMo: 结束 " + total.get() + "B / " + events.get() + " 段，共 "
                + (System.currentTimeMillis() - readStarted) + "ms");
        return total.get();
    }

    private static String modelOf(VoiceProfile profile) {
        return profile == null || profile.model == null ? "?" : profile.model;
    }

    /** OpenAI 兼容的 chat 请求体。 */
    private static String buildBody(VoiceProfile profile, String text) throws Exception {
        // 「设计 / 复刻 / 预置」的模式由音色自己的模型决定，但真正发给服务端的
        // model id 可以被用户在语音设置里统一替换（比如挂自己的代理）。
        String model = profile.model;
        String override = RemoteConfig.ttsModel();
        String sendModel = override == null || override.isEmpty() ? model : override;
        JSONObject root = new JSONObject();
        root.put("model", sendModel);
        root.put("stream", true);

        JSONArray messages = new JSONArray();
        String desc = profile.desc;
        if (MODEL_DESIGN.equals(model)) {
            messages.put(new JSONObject().put("role", "user")
                    .put("content", desc == null ? "" : desc));
        } else if (MODEL_CLONE.equals(model)) {
            // 复刻：user 留空，音色来自音频样本
            messages.put(new JSONObject().put("role", "user").put("content", ""));
        } else if (desc != null && !desc.isEmpty()) {
            // 预置音色也可以带一句风格指令
            messages.put(new JSONObject().put("role", "user").put("content", desc));
        }
        messages.put(new JSONObject().put("role", "assistant").put("content", text));
        root.put("messages", messages);

        JSONObject audio = new JSONObject();
        audio.put("format", "pcm16");
        if (MODEL_CLONE.equals(model)) {
            String sample = preparedClip(profile);
            // Hard ceiling independent of the picker: an over-long sample would
            // blow past the request budget and the provider rejects it anyway.
            if (sample == null || sample.length() > MAX_SAMPLE_BASE64) {
                throw new IllegalStateException("参考音频过大（"
                        + (sample == null ? 0 : sample.length()) + " > "
                        + MAX_SAMPLE_BASE64 + " 字符），请重新录/选一段更短的");
            }
            audio.put("voice", "data:" + sampleMime(sample) + ";base64," + sample);
        } else if (!MODEL_DESIGN.equals(model)) {
            String voice = profile.presetVoice;
            audio.put("voice", voice == null || voice.isEmpty() ? PRESET_DEFAULT : voice);
        }
        root.put("audio", audio);
        return root.toString();
    }

    /** WAV 走 audio/wav，其余按 mp3 处理（文档只接受这两种）。 */
    private static String sampleMime(String base64) {
        try {
            byte[] head = Base64.getDecoder().decode(
                    base64.substring(0, Math.min(24, base64.length())));
            if (head.length > 11 && head[0] == 'R' && head[1] == 'I'
                    && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V') {
                return "audio/wav";
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "audio/mpeg";
    }

    /**
     * 用一句最短的话验证 Key 能不能用（非流式、预置音色）。
     *
     * @return null 表示通过，否则是可直接显示的原因
     */
    static String verifyKey(String endpoint, String key) {
        if (key == null || key.trim().isEmpty()) return "API Key 是空的";
        String url = normalizeEndpoint(endpoint);
        HttpURLConnection conn = null;
        try {
            JSONObject root = new JSONObject();
            root.put("model", MODEL_PRESET);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "assistant").put("content", "你好"));
            root.put("messages", messages);
            root.put("audio", new JSONObject().put("format", "wav")
                    .put("voice", PRESET_DEFAULT));

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            try (OutputStream out = conn.getOutputStream()) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readAll(conn.getInputStream());
                return body.contains("audio") || body.contains("data") ? null
                        : "接口通了，但没返回音频";
            }
            String error = readAll(conn.getErrorStream());
            if (code == 401 || code == 403) return "Key 无效（HTTP " + code + "）";
            return "HTTP " + code + " " + trim(error);
        } catch (Throwable error) {
            return "连不上：" + error.getClass().getSimpleName();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Pulls the base64 audio out of one SSE payload and decodes it to PCM. */
    private static byte[] extractPcm(String payload) {
        try {
            JSONObject event = new JSONObject(payload);
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) return null;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) return null;
            Object audio = delta.opt("audio");
            String b64 = null;
            if (audio instanceof JSONObject) {
                b64 = ((JSONObject) audio).optString("data", "");
            } else if (audio instanceof String) {
                b64 = (String) audio;
            }
            if (b64 == null || b64.isEmpty()) return null;
            return Base64.getDecoder().decode(b64);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        try {
            byte[] buffer = new byte[2048];
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) > 0 && sb.length() < 2000) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String trim(String text) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').trim();
        return one.length() > 200 ? one.substring(0, 200) : one;
    }
}
