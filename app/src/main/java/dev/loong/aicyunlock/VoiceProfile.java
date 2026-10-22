package dev.loong.aicyunlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One user-defined timbre: the unit that gets a row in Aicy's 声音 list.
 *
 * <p>A profile is a named bundle of synthesis parameters. Which MiMo model it
 * uses decides which fields matter:
 * <ul>
 *   <li>{@code mimo-v2.5-tts-voicedesign} — {@link #desc} (the voice description)</li>
 *   <li>{@code mimo-v2.5-tts-voiceclone} — {@link #sampleKey} (the reference clip)</li>
 *   <li>{@code mimo-v2.5-tts} — {@link #presetVoice} (a stock MiMo voice id)</li>
 * </ul>
 *
 * <p>The reference clip is deliberately <em>not</em> part of the serialised list:
 * it is up to 900 KB of Base64 per profile, and shipping every clip through the
 * provider on every read would blow the binder budget as soon as a user has two
 * or three clone voices. Clips are therefore stored on their own under
 * {@link ConfigProvider#KEY_SAMPLE_PREFIX} and pulled lazily by speaker id.
 *
 * <p>Serialised form (SharedPreferences {@code voices} key):
 * <pre>
 *   {"v":1,"profiles":[{"id":"aicyunlock_v1","name":"少女音","model":"…",
 *                       "desc":"…","preset":"mimo_default","sample":"v1",
 *                       "avatar":1}]}
 * </pre>
 */
final class VoiceProfile {

    /** Schema version, so a future format change can migrate instead of drop. */
    static final int SCHEMA = 1;

    static final String PREFS_LIST = "voice_profiles";

    /** Maximum rows we will inject into Aicy's list; keeps the UI scannable. */
    static final int MAX_PROFILES = 8;

    final String id;
    final String name;
    final String model;
    final String desc;
    final String presetVoice;
    /** Key suffix under {@link ConfigProvider#KEY_SAMPLE_PREFIX} for the clip. */
    final String sampleKey;
    /** Index into the stock avatar list, 0-based. */
    final int avatar;

    VoiceProfile(String id, String name, String model, String desc,
                 String presetVoice, String sampleKey, int avatar) {
        this.id = id == null || id.isEmpty() ? VoiceCatalog.CUSTOM_SPEAKER : id;
        this.name = name == null || name.isEmpty() ? "我的音色" : name;
        this.model = model == null || model.isEmpty() ? MiMoTts.MODEL_DESIGN : model;
        this.desc = desc == null ? "" : desc;
        this.presetVoice = presetVoice == null || presetVoice.isEmpty()
                ? MiMoTts.PRESET_DEFAULT : presetVoice;
        this.sampleKey = sampleKey == null ? "" : sampleKey;
        this.avatar = avatar;
    }

    /** A blank profile with the first free {@code aicyunlock_vN} id. */
    static VoiceProfile blank(String name, int index) {
        return new VoiceProfile(speakerFor(index), name, MiMoTts.MODEL_DESIGN,
                "", MiMoTts.PRESET_DEFAULT, "", 0);
    }

    /** Maps a 1-based row number to its stable speaker id. */
    static String speakerFor(int index) {
        return VoiceCatalog.CUSTOM_PREFIX + "v" + index;
    }

    /** Extracts the 1-based index back out of a speaker id, or 0 if foreign. */
    static int indexOf(String speakerId) {
        if (speakerId == null || !speakerId.startsWith(VoiceCatalog.CUSTOM_PREFIX)) {
            return 0;
        }
        try {
            return Integer.parseInt(
                    speakerId.substring(VoiceCatalog.CUSTOM_PREFIX.length()));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** True when this profile has everything its model needs. */
    boolean ready() {
        if (MiMoTts.MODEL_DESIGN.equals(model)) return !desc.trim().isEmpty();
        if (MiMoTts.MODEL_CLONE.equals(model)) return !sampleKey.isEmpty();
        return true;
    }

    /** Short reason shown in the list when {@link #ready()} is false. */
    String missingReason() {
        if (MiMoTts.MODEL_DESIGN.equals(model)) return "缺音色描述";
        if (MiMoTts.MODEL_CLONE.equals(model)) return "缺参考音频";
        return "已停用";
    }

    String modelLabel() {
        if (MiMoTts.MODEL_CLONE.equals(model)) return "音频复刻";
        if (MiMoTts.MODEL_PRESET.equals(model)) return "预置音色";
        return "文字造音色";
    }

    VoiceProfile withName(String newName) {
        return new VoiceProfile(id, newName, model, desc, presetVoice, sampleKey, avatar);
    }

    VoiceProfile withAvatar(int newAvatar) {
        return new VoiceProfile(id, name, model, desc, presetVoice, sampleKey, newAvatar);
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("model", model);
            o.put("desc", desc);
            o.put("preset", presetVoice);
            o.put("sample", sampleKey);
            o.put("avatar", avatar);
        } catch (Throwable ignored) {
            // org.json only throws on NaN/Infinity, which we never write
        }
        return o;
    }

    static VoiceProfile fromJson(JSONObject o) {
        if (o == null) return null;
        return new VoiceProfile(
                o.optString("id", ""),
                o.optString("name", ""),
                o.optString("model", MiMoTts.MODEL_DESIGN),
                o.optString("desc", ""),
                o.optString("preset", MiMoTts.PRESET_DEFAULT),
                o.optString("sample", ""),
                o.optInt("avatar", 0));
    }

    // ------------------------------------------------------------------
    // list (de)serialisation
    // ------------------------------------------------------------------

    static JSONObject wrap(List<VoiceProfile> list) {
        JSONArray array = new JSONArray();
        for (VoiceProfile p : list) array.put(p.toJson());
        JSONObject root = new JSONObject();
        try {
            root.put("v", SCHEMA);
            root.put("profiles", array);
        } catch (Throwable ignored) {
            // unreachable
        }
        return root;
    }

    /**
     * Parses the stored document.
     *
     * <p>A row whose id does not match its own position is <em>renumbered</em>
     * rather than dropped: dropping it would shift every later row and silently
     * discard the user's remaining timbres (a single bad row must not empty the
     * list). The renumbered ids are what gets written back on the next save.
     *
     * @return never null; empty when nothing is stored
     */
    static List<VoiceProfile> parse(String json) {
        List<VoiceProfile> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray array = new JSONObject(json).optJSONArray("profiles");
            if (array == null) return out;
            for (int i = 0; i < array.length() && out.size() < MAX_PROFILES; i++) {
                VoiceProfile p = fromJson(array.optJSONObject(i));
                if (p == null) continue;
                String wanted = speakerFor(out.size() + 1);
                if (wanted.equals(p.id)) {
                    out.add(p);
                } else {
                    // Keep the payload (name, model, clip) and only fix the id.
                    out.add(new VoiceProfile(wanted, p.name, p.model, p.desc,
                            p.presetVoice, p.sampleKey, p.avatar));
                }
            }
        } catch (Throwable ignored) {
            // corrupt document: start clean rather than crash the provider
        }
        return out;
    }

    /**
     * Upgrades a 1.x single-voice setup to a one-entry list, so an existing
     * install keeps working after the multi-voice upgrade.
     */
    static List<VoiceProfile> migrateOrParse(String json, String legacyDesc,
                                              String legacyModel, String legacySampleKey) {
        List<VoiceProfile> parsed = parse(json);
        if (!parsed.isEmpty()) return parsed;
        boolean legacyConfigured = (legacyDesc != null && !legacyDesc.trim().isEmpty())
                || (legacySampleKey != null && !legacySampleKey.isEmpty())
                || (legacyModel != null && !legacyModel.trim().isEmpty());
        if (!legacyConfigured) return parsed;
        List<VoiceProfile> out = new ArrayList<>();
        out.add(new VoiceProfile(speakerFor(1), "我的音色",
                legacyModel == null || legacyModel.isEmpty()
                        ? MiMoTts.MODEL_DESIGN : legacyModel,
                legacyDesc == null ? "" : legacyDesc,
                MiMoTts.PRESET_DEFAULT,
                legacySampleKey == null ? "" : legacySampleKey,
                0));
        return out;
    }

    /** Stable id for the clip storage key of a profile. */
    String clipKey() {
        return ConfigProvider.KEY_SAMPLE_PREFIX + sampleKey;
    }
}