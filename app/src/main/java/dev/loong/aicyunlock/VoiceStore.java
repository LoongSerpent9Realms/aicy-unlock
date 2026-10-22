package dev.loong.aicyunlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the user's timbre list in the module's own SharedPreferences.
 *
 * <p>Only the settings app writes here; the hooked Aicy process reads the same
 * document back through {@link ConfigProvider}. Reference clips live under their
 * own prefixed keys so the list document stays small enough to hand across
 * binder on every query.
 */
final class VoiceStore {

    /** Set once the pre-multi-voice keys have been folded into the profile list. */
    private static final String KEY_MIGRATED = "voice_migrated";

    private VoiceStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE);
    }

    /** Current profiles, migrating a pre-multi-voice install on first read. */
    static List<VoiceProfile> load(Context context) {
        SharedPreferences sp = prefs(context);
        String stored = sp.getString(VoiceProfile.PREFS_LIST, null);
        if (stored != null && !stored.isEmpty()) {
            return VoiceProfile.parse(stored);
        }
        // Nothing stored: this may be a 1.x install whose single-voice settings we
        // can still carry over. Claim the clip first (that is what proves a 1.x
        // setup exists at all), then materialise and persist the list so the
        // hooked process sees the same thing the settings UI shows.
        String sampleKey = legacySampleKey(sp);
        List<VoiceProfile> migrated = VoiceProfile.migrateOrParse(null,
                sp.getString(ConfigProvider.KEY_MIMO_DESC, null),
                sp.getString(ConfigProvider.KEY_MIMO_MODEL, null),
                sampleKey);
        if (!migrated.isEmpty()) {
            save(context, migrated);
        }
        return migrated;
    }

    /** Persists the whole list. No-op when {@code list} is null. */
    static void save(Context context, List<VoiceProfile> list) {
        if (list == null) return;
        List<VoiceProfile> trimmed = list.size() > VoiceProfile.MAX_PROFILES
                ? new ArrayList<>(list.subList(0, VoiceProfile.MAX_PROFILES))
                : list;
        prefs(context).edit()
                .putString(VoiceProfile.PREFS_LIST, VoiceProfile.wrap(trimmed).toString())
                .apply();
    }

    /** Renames a profile in place and persists. */
    static boolean rename(Context context, String speakerId, String newName) {
        List<VoiceProfile> list = load(context);
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            VoiceProfile p = list.get(i);
            if (!p.id.equals(speakerId)) continue;
            list.set(i, p.withName(newName));
            hit = true;
            break;
        }
        if (hit) save(context, list);
        return hit;
    }

    /** Sets the avatar template index. */
    static boolean setAvatar(Context context, String speakerId, int avatar) {
        List<VoiceProfile> list = load(context);
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            VoiceProfile p = list.get(i);
            if (!p.id.equals(speakerId)) continue;
            list.set(i, p.withAvatar(avatar));
            hit = true;
            break;
        }
        if (hit) save(context, list);
        return hit;
    }

    /**
     * Appends a blank profile.
     *
     * @return the new profile, or null when the list is full
     */
    static VoiceProfile add(Context context, String name) {
        List<VoiceProfile> list = load(context);
        if (list.size() >= VoiceProfile.MAX_PROFILES) return null;
        int index = list.size() + 1;
        VoiceProfile fresh = VoiceProfile.blank(name, index);
        list.add(fresh);
        save(context, list);
        return fresh;
    }

    /**
     * Removes a profile and the clip it owns.
     *
     * <p>Speaker ids are positional ({@code aicyunlock_vN}), so the rows after
     * the deleted one are renumbered — their payload (name, model, clip) moves
     * with them rather than being dropped. {@link VoiceProfile#parse} performs
     * the same normalisation, so this only needs to drop the row and hand the
     * tail to the serialiser with fresh ids.
     */
    static boolean remove(Context context, String speakerId) {
        List<VoiceProfile> list = load(context);
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(speakerId)) {
                at = i;
                break;
            }
        }
        if (at < 0) return false;
        SharedPreferences sp = prefs(context);
        String clip = list.get(at).clipKey();
        if (!clip.isEmpty() && sp.contains(clip)) sp.edit().remove(clip).apply();
        list.remove(at);

        // Shift the ids down by the removed slot; the payload travels with each row.
        List<VoiceProfile> renumbered = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            VoiceProfile p = list.get(i);
            renumbered.add(new VoiceProfile(VoiceProfile.speakerFor(i + 1), p.name,
                    p.model, p.desc, p.presetVoice, p.sampleKey, p.avatar));
        }
        save(context, renumbered);
        return true;
    }

    /** Stores a Base64 clip under a profile's own key. */
    static void putClip(Context context, String sampleKey, String base64) {
        if (sampleKey == null || sampleKey.isEmpty()) return;
        prefs(context).edit()
                .putString(ConfigProvider.KEY_SAMPLE_PREFIX + sampleKey,
                        SecretStore.seal(context, base64 == null ? "" : base64))
                .apply();
    }

    /** @return the decrypted Base64 clip, or "" when absent. */
    static String clip(Context context, String sampleKey) {
        if (sampleKey == null || sampleKey.isEmpty()) return "";
        String sealed = prefs(context)
                .getString(ConfigProvider.KEY_SAMPLE_PREFIX + sampleKey, "");
        return SecretStore.open(context, sealed);
    }

    /** Drops a clip without touching the profile list. */
    static void dropClip(Context context, String sampleKey) {
        if (sampleKey == null || sampleKey.isEmpty()) return;
        prefs(context).edit()
                .remove(ConfigProvider.KEY_SAMPLE_PREFIX + sampleKey)
                .apply();
    }

    /** Puts a new profile's parameters in place, allocating a clip key if needed. */
    static void putProfile(Context context, VoiceProfile profile) {
        List<VoiceProfile> list = load(context);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                save(context, list);
                return;
            }
        }
        list.add(profile);
        save(context, list);
    }

    // ------------------------------------------------------------------
    // upgrade glue
    // ------------------------------------------------------------------

    /**
     * Claims the single-clip key used before multi-voice support.
     *
     * <p>Before this change the clip lived under {@code mimo_sample}; clips now
     * live under {@code sample_<key>}. The first profile adopts it as
     * {@code sample_v1}, and the legacy entry is copied across (not moved) so a
     * downgrade still finds the old key intact.
     *
     * @return the sampleKey to use for the first profile, or "" when there is
     *         nothing to adopt
     */
    private static String legacySampleKey(SharedPreferences sp) {
        if (sp.getBoolean(KEY_MIGRATED, false)) return "v1";
        String legacy = sp.getString(ConfigProvider.KEY_MIMO_SAMPLE, null);
        if (legacy == null || legacy.isEmpty()) {
            sp.edit().putBoolean(KEY_MIGRATED, true).apply();
            return "";
        }
        String key = "v1";
        if (!sp.contains(ConfigProvider.KEY_SAMPLE_PREFIX + key)) {
            sp.edit().putString(ConfigProvider.KEY_SAMPLE_PREFIX + key, legacy).apply();
        }
        sp.edit().putBoolean(KEY_MIGRATED, true).apply();
        return key;
    }
}