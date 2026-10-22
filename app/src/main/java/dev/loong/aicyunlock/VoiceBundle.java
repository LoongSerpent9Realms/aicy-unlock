package dev.loong.aicyunlock;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Import / export of a timbre bundle, so a set of voices can be shared as one
 * file and re-imported on another device (or another user).
 *
 * <p>Bundle format (a {@code .mimotts.json} file):
 * <pre>
 *   {
 *     "kind": "aicyunlock.timbres",
 *     "v": 1,
 *     "exportedAt": "2026-10-20T12:00:00Z",
 *     "timbres": [
 *       {"name":"少女音","model":"…voicedesign","desc":"…","preset":"…",
 *        "avatar":1,"clip":"&lt;base64 wav/mp3, optional&gt;"}
 *     ]
 *   }
 * </pre>
 *
 * <p>Two deliberate omissions / properties:
 * <ul>
 *   <li><b>The MiMo API key is never exported.</b> A bundle carries only voice
 *       definitions; each device supplies its own key.</li>
 *   <li><b>Speaker ids are not exported.</b> They are positional
 *       ({@code aicyunlock_vN}) and are re-assigned on import, so a bundle
 *       imported onto a device that already has timbres cannot collide.</li>
 * </ul>
 */
final class VoiceBundle {

    static final String KIND = "aicyunlock.timbres";
    static final int VERSION = 1;

    private VoiceBundle() {
    }

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    /** Serialises the given profiles (clips included) to the bundle JSON. */
    static String export(Context context, List<VoiceProfile> profiles) {
        JSONArray array = new JSONArray();
        for (VoiceProfile p : profiles) {
            JSONObject o = p.toJson();
            // Strip the device-local fields; they are re-created on import.
            try {
                o.remove("id");
                o.remove("sample");
                String clip = VoiceStore.clip(context, p.sampleKey);
                if (clip != null && !clip.isEmpty()) o.put("clip", clip);
            } catch (Throwable ignored) {
                // org.json remove only throws on misuse
            }
            array.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("kind", KIND);
            root.put("v", VERSION);
            root.put("timbres", array);
        } catch (Throwable ignored) {
            // unreachable
        }
        return root.toString();
    }

    /** Writes the bundle for {@code profiles} into {@code target}. */
    static void exportTo(Context context, Uri target, List<VoiceProfile> profiles)
            throws Exception {
        String json = export(context, profiles);
        try (java.io.OutputStream out = context.getContentResolver()
                .openOutputStream(target, "wt")) {
            if (out == null) throw new IllegalStateException("无法写入所选文件");
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------
    // import
    // ------------------------------------------------------------------

    /** Parsed bundle: profiles without ids yet, plus their clips. */
    static final class Parsed {
        final List<Entry> entries = new ArrayList<>();

        static final class Entry {
            String name = "";
            String model = MiMoTts.MODEL_DESIGN;
            String desc = "";
            String preset = MiMoTts.PRESET_DEFAULT;
            int avatar;
            String clip = "";
        }
    }

    /**
     * Parses bundle JSON.
     *
     * @return null when the document is not a bundle at all
     */
    static Parsed parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
        if (!KIND.equals(root.optString("kind"))) return null;
        JSONArray array = root.optJSONArray("timbres");
        if (array == null) return null;
        Parsed out = new Parsed();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Parsed.Entry e = new Parsed.Entry();
            e.name = o.optString("name", "");
            e.model = o.optString("model", MiMoTts.MODEL_DESIGN);
            e.desc = o.optString("desc", "");
            e.preset = o.optString("preset", MiMoTts.PRESET_DEFAULT);
            e.avatar = o.optInt("avatar", 0);
            e.clip = o.optString("clip", "");
            if (e.clip.length() > MiMoTts.MAX_SAMPLE_BASE64) e.clip = "";
            out.entries.add(e);
        }
        return out;
    }

    /**
     * Merges a bundle into the device's timbres.
     *
     * <p>New rows are appended with freshly assigned {@code aicyunlock_vN} ids and
     * their clips (if any) stored under fresh keys. Existing rows are never
     * overwritten — importing the same bundle twice creates two copies, which is
     * the least surprising behaviour for a "share" feature.
     *
     * @return the number of timbres actually added
     */
    static int importInto(Context context, Parsed parsed) {
        if (parsed == null || parsed.entries.isEmpty()) return 0;
        List<VoiceProfile> list = VoiceStore.load(context);
        int added = 0;
        for (Parsed.Entry e : parsed.entries) {
            if (list.size() >= VoiceProfile.MAX_PROFILES) break;
            int index = list.size() + 1;
            String clipKey = "";
            if (e.clip != null && !e.clip.isEmpty()) {
                clipKey = "b" + System.currentTimeMillis() + "_" + added;
                VoiceStore.putClip(context, clipKey, e.clip);
            }
            VoiceProfile profile = new VoiceProfile(
                    VoiceProfile.speakerFor(index),
                    e.name == null || e.name.isEmpty() ? "导入音色" : e.name,
                    e.model, e.desc, e.preset, clipKey, e.avatar);
            list.add(profile);
            added++;
        }
        VoiceStore.save(context, list);
        return added;
    }

    /** Convenience: read a bundle file into memory. */
    static Parsed read(Context context, Uri source) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IllegalStateException("无法读取所选文件");
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                total += read;
                if (total > 8 * 1024 * 1024) break; // a bundle is never this big
            }
        }
        return parse(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }
}