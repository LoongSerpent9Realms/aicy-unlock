package dev.loong.aicyunlock;

import android.content.Context;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性探针：用同一句话、同一个stream:true，分别打预置音色与复刻音色，
 * 记录首字节时间与chunk 数量。
 *
 * <p>官方文档称只有 mimo-v2.5-tts 支持低延迟真流式，voiceclone / voicedesign
 * 的流式接口「降级为兼容模式，仅在所有推理完成后以流式格式返回一次」。
 * 这里实测一遍，不靠文档断言。
 */
public final class StreamProbe {

    private static final String ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String TEXT =
            "这是一段用来测试流式行为的句子，长度适中，既不太短也不太长。";

    public static String run(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(preset(context));
        sb.append(" || ");
        sb.append(clone(context));
        return sb.toString();
    }

    private static String preset(Context context) {
        String body = "{\"model\":\"mimo-v2.5-tts\",\"stream\":true,\"messages\":["
                + "{\"role\":\"assistant\",\"content\":\"" + TEXT + "\"}],"
                + "\"audio\":{\"format\":\"pcm16\",\"voice\":\"mimo_default\"}}";
        return measure("preset", body);
    }

    private static String clone(Context context) {
        String clip = RemoteConfig.clip(firstCloneProfile(context));
        if (clip == null || clip.isEmpty()) return "clone=无样本";
        String body = "{\"model\":\"mimo-v2.5-tts-voiceclone\",\"stream\":true,\"messages\":["
                + "{\"role\":\"user\",\"content\":\"\"},"
                + "{\"role\":\"assistant\",\"content\":\"" + TEXT + "\"}],"
                + "\"audio\":{\"format\":\"pcm16\","
                + "\"voice\":\"data:audio/wav;base64," + clip + "\"}}";
        return measure("clone", body);
    }

    private static VoiceProfile firstCloneProfile(Context context) {
        for (VoiceProfile p : RemoteConfig.profiles()) {
            if (MiMoTts.MODEL_CLONE.equals(p.model)
                    && !p.sampleKey.isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /** One request, reported as first-byte time and chunk count. */
    private static String measure(String label, String body) {
        HttpURLConnection conn = null;
        try {
            String key = RemoteConfig.mimoKey();
            if (key == null || key.isEmpty()) return label + "=无Key";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            long sentAt = System.currentTimeMillis();
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            long headersMs = System.currentTimeMillis() - sentAt;
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return label + "=HTTP" + code + " 无体";

            long readStart = System.currentTimeMillis();
            long firstAt = -1;
            long lastAt = readStart;
            AtomicInteger chunks = new AtomicInteger();
            AtomicInteger total = new AtomicInteger();
            long[] gaps = {0};
            long prev = readStart;
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String s = line.substring(5).trim();
                if (s.isEmpty() || "[DONE]".equals(s)) continue;
                int n = 0;
                try {
                    org.json.JSONObject ev = new org.json.JSONObject(s);
                    org.json.JSONArray cs = ev.optJSONArray("choices");
                    if (cs == null || cs.length() == 0) continue;
                    org.json.JSONObject d = cs.optJSONObject(0).optJSONObject("delta");
                    if (d == null) continue;
                    Object a = d.opt("audio");
                    String b64 = a instanceof org.json.JSONObject
                            ? ((org.json.JSONObject) a).optString("data", "")
                            : (a instanceof String ? (String) a : "");
                    if (b64.isEmpty()) continue;
                    n = Base64.getDecoder().decode(b64).length;
                } catch (Throwable ignored) {
                    continue;
                }
                long now = System.currentTimeMillis();
                if (firstAt < 0) firstAt = now - readStart;
                else gaps[0] = Math.max(gaps[0], now - prev);
                prev = now;
                lastAt = now;
                chunks.incrementAndGet();
                total.addAndGet(n);
            }
            return label + "[hdr=" + headersMs + "ms ttfb=" + firstAt
                    + "ms total=" + (lastAt - readStart) + "ms chunks=" + chunks.get()
                    + " pcm=" + total.get() + "B maxGap=" + gaps[0] + "ms]";
        } catch (Throwable error) {
            return label + "失败:" + error.getClass().getSimpleName();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}