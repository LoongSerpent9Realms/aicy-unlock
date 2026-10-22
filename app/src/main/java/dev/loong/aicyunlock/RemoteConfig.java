package dev.loong.aicyunlock;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live switch state, MiMo credentials and the timbre list, read from the module
 * app's {@link ConfigProvider}.
 *
 * <p>The hooks run inside Aicy's process (a different uid), so they cannot read
 * our SharedPreferences directly; they query the exported provider instead and
 * cache the answer for a few seconds. If the provider is unreachable (app
 * uninstalled, provider disabled) the defaults stay on — a missing app must not
 * silently disable protection.
 *
 * <p>Two payload classes live here: the small profile list (names, models,
 * descriptions, preset ids), refreshed on the normal TTL, and the per-profile
 * reference clips, which run to hundreds of KB and are therefore fetched once
 * on first synthesis and then kept for the process lifetime.
 */
final class RemoteConfig {

    private static final String AUTHORITY = "dev.loong.aicyunlock.cfg";
    private static final long TTL_MS = 5_000L;

    private static long sAt;
    private static boolean sMaster = true;
    private static boolean sLog = true;
    private static boolean sChat = true;
    private static boolean sCatalog = true;
    private static boolean sOnlyCustomTier = true;
    private static boolean sWakeWord = true;
    private static boolean sTaskRobot = true;
    private static boolean sTtsEnabled = true;
    private static String sTtsEndpoint = MiMoTts.DEFAULT_ENDPOINT;
    private static boolean sNotifyEnabled = true;
    private static boolean sNotifyScreenOff = true;
    private static boolean sNotifyHeadset = true;
    private static String sNotifyVoice = "";
    /** Empty means "every app", matching the behaviour before the allow-list. */
    private static List<String> sNotifyApps = new ArrayList<>();
    /** Empty means "any headset", matching the behaviour before the allow-list. */
    private static List<TrustedHeadsets.Entry> sNotifyTrusted = new ArrayList<>();
    private static String sDefaultTier = CatalogPatch.FREE_TIER_ID;
    /** 自定义模型的接口地址 / 密钥 / 模型名，由模块 App 维护。 */
    private static String sByokUrl = "";
    private static String sByokKey = "";
    private static String sByokModel = "";
    /** 临时露出「模型设置」页的开关。 */
    private static boolean sRevealModel;
    private static String sMimoKey = "";
    /** 语音合成统一替换的 model id；空 = 用音色自带的。 */
    private static String sTtsModel = "";
    private static List<VoiceProfile> sProfiles = new ArrayList<>();
    /** True once the provider has answered; see {@link #reachable()}. */
    private static boolean sReachable;

    /**
     * sampleKey -> decrypted Base64 clip. Big payloads, fetched at most once.
     *
     * <p>Concurrent: each loopback connection gets its own worker thread, and the
     * clip warmup thread reads the same map, so this cannot be a plain HashMap.
     */
    private static final Map<String, String> sClips =
            new java.util.concurrent.ConcurrentHashMap<>();

    private RemoteConfig() {
    }

    /** Forces the next accessor call to re-read the provider. */
    static void invalidate() {
        sAt = 0L;
    }

    static boolean master() {
        refresh();
        return sMaster;
    }

    static boolean blockLogUpload() {
        refresh();
        return sLog;
    }

    static boolean blockChatEgress() {
        refresh();
        return sChat;
    }

    static boolean catalogRewrite() {
        refresh();
        return sCatalog;
    }

    /** When on, the BYOK endpoint is only reported while the 自定义 tier is selected. */
    static boolean onlyCustomTier() {
        refresh();
        return sOnlyCustomTier;
    }

    /** Routes Aicy's stock wake-word page to the hidden custom keyphrase page. */
    static boolean wakeWord() {
        refresh();
        return sWakeWord;
    }

    /** Hands "打开X搜索Y" sentences to the Flyme task robot's UI agent. */
    static boolean taskRobot() {
        refresh();
        return sTaskRobot;
    }

    /** 自定义模型的接口地址（空 = 没配置）。 */
    static String byokUrl() {
        refresh();
        return sByokUrl;
    }

    /** 自定义模型的 API Key。 */
    static String byokKey() {
        refresh();
        return sByokKey;
    }

    /** 自定义模型的模型名（服务端真实的 model id）。 */
    static String byokModel() {
        refresh();
        return sByokModel;
    }

    /** 三项都齐了才算配置完成。 */
    static boolean byokConfigured() {
        refresh();
        return !sByokUrl.isEmpty() && !sByokModel.isEmpty();
    }

    /** 临时露出 Aicy 自己的「模型设置」页（借一个设置分组的位置）。 */
    static boolean revealModelPage() {
        refresh();
        return sRevealModel;
    }

    /** User's preferred default tier when opening a conversation. */
    static String defaultTier() {        refresh();
        return sDefaultTier;
    }

    static boolean ttsEnabled() {
        refresh();
        return sTtsEnabled;
    }

    /** Full chat/completions URL, already normalised by the settings app. */
    static String ttsEndpoint() {
        refresh();
        return sTtsEndpoint;
    }

    static boolean notifyEnabled() {
        refresh();
        return sNotifyEnabled;
    }

    static boolean notifyScreenOffOnly() {
        refresh();
        return sNotifyScreenOff;
    }

    static boolean notifyHeadsetOnly() {
        refresh();
        return sNotifyHeadset;
    }

    /** Profile id picked in the settings app; "" means the first ready one. */
    static String notifyVoice() {
        refresh();
        return sNotifyVoice;
    }

    /** Headsets allowed to trigger speech; empty means every headset. */
    static List<TrustedHeadsets.Entry> notifyTrusted() {
        refresh();
        return sNotifyTrusted;
    }

    /** Packages allowed to trigger speech; empty means every app. */
    static List<String> notifyApps() {
        refresh();
        return sNotifyApps;
    }

    static String mimoKey() {
        refresh();
        return sMimoKey;
    }

    /** 语音合成统一替换的 model id；空串表示沿用每条音色自己的模型。 */
    static String ttsModel() {
        refresh();
        return sTtsModel;
    }

    /** All configured timbres in display order. Never null. */
    static List<VoiceProfile> profiles() {
        refresh();
        return sProfiles;
    }

    /**
     * Whether the provider answered at least once in this process.
     *
     * <p>Distinguishes "the user has no timbres" from "the configuration is not
     * readable yet", which callers must not confuse: the first would be a valid
     * empty state, the second means the answer is simply not known.
     */
    static boolean reachable() {
        refresh();
        return sReachable;
    }

    /**
     * @return the profile that owns {@code speakerId}, or null for a stock voice
     *         or a profile the user deleted since the list was cached
     */
    static VoiceProfile bySpeaker(String speakerId) {
        if (speakerId == null) return null;
        List<VoiceProfile> list = profiles();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(speakerId)) return list.get(i);
        }
        return null;
    }

    /**
     * The reference clip for a profile, fetched on first use.
     *
     * @return Base64 audio, or "" when there is none or the fetch failed
     */
    static String clip(VoiceProfile profile) {
        if (profile == null || profile.sampleKey.isEmpty()) return "";
        String cached = sClips.get(profile.sampleKey);
        if (cached != null) return cached;
        Cursor cursor = null;
        String clip = "";
        boolean fetched = false;
        try {
            Context context = app();
            if (context == null) return "";
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + AUTHORITY + "/" + ConfigProvider.PATH_CLIP
                            + "/" + profile.sampleKey),
                    null, null, null, null);
            // The provider answers with every setting as its own row and appends
            // the clip LAST, so reading only the first row found "master" and
            // reported the clip as missing even though it was right there.
            // Scan the whole cursor for the row we actually asked for.
            int k = cursor == null ? -1 : cursor.getColumnIndex("k");
            int s = cursor == null ? -1 : cursor.getColumnIndex("s");
            if (cursor != null && k >= 0 && s >= 0 && cursor.moveToFirst()) {
                do {
                    if (ConfigProvider.KEY_CLIP.equals(cursor.getString(k))) {
                        String value = cursor.getString(s);
                        clip = value == null ? "" : value;
                        fetched = true;
                        break;
                    }
                } while (cursor.moveToNext());
            }
            if (!fetched) {
                // The provider answered but without a clip row: either the key
                // is gone or the UID gate refused us. Worth saying out loud —
                // silently returning "" looks identical to "user never
                // recorded anything", which is what sent us hunting for a
                // missing clip that was actually present on disk.
                android.util.Log.w("AicyUnlock",
                        "AicyTts: clip row missing for key=" + profile.sampleKey
                                + " (rows=" + countRows(cursor) + ")");
            }
        } catch (Throwable error) {
            // A clip is a few hundred KB of Base64; crossing binder with it can
            // fail, and swallowing that is what made this look like a missing
            // recording rather than a transport problem.
            android.util.Log.e("AicyUnlock",
                    "AicyTts: clip fetch failed for " + profile.sampleKey, error);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        // Only a completed round-trip may be cached: caching the empty string
        // after a transient failure would make the clip unavailable for the rest
        // of the process' life.
        if (fetched) sClips.put(profile.sampleKey, clip);
        return clip;
    }

    /** Row count for the "why is the clip not there" diagnostic. */
    private static int countRows(Cursor cursor) {
        if (cursor == null) return -1;
        try {
            return cursor.getCount();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - sAt < TTL_MS) return;
        Cursor cursor = null;
        try {
            Context context = app();
            if (context == null) {
                // Do NOT stamp sAt here. During process start-up the Context
                // appears a moment after the hooks are installed; consuming the
                // TTL window on that failed attempt would make every retry
                // inside those 5 seconds a no-op, and the config would look
                // permanently empty.
                return;
            }
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + AUTHORITY + "/all"), null, null, null, null);
            if (cursor == null) return;
            int keyIndex = cursor.getColumnIndex("k");
            int valueIndex = cursor.getColumnIndex("v");
            int textIndex = cursor.getColumnIndex("s");
            if (keyIndex < 0 || valueIndex < 0) return;
            sReachable = true;
            // Only now is the TTL window meaningful.
            sAt = now;
            while (cursor.moveToNext()) {
                String key = cursor.getString(keyIndex);
                boolean value = cursor.getInt(valueIndex) != 0;
                String text = textIndex >= 0 && !cursor.isNull(textIndex)
                        ? cursor.getString(textIndex) : "";
                if (key == null) continue;
                switch (key) {
                    case "master": sMaster = value; break;
                    case "block_log": sLog = value; break;
                    case "block_chat": sChat = value; break;
                    case "catalog": sCatalog = value; break;
                    case "only_custom_tier": sOnlyCustomTier = value; break;
                    case "wake_word": sWakeWord = value; break;
                    case ConfigProvider.KEY_TASK_ROBOT: sTaskRobot = value; break;
                    case "default_tier":
                        sDefaultTier = text == null || text.isEmpty()
                                ? CatalogPatch.FREE_TIER_ID : text.trim();
                        break;
                    case ConfigProvider.KEY_BYOK_URL:
                        sByokUrl = text == null ? "" : text.trim();
                        break;
                    case ConfigProvider.KEY_BYOK_KEY:
                        sByokKey = text == null ? "" : text.trim();
                        break;
                    case ConfigProvider.KEY_BYOK_MODEL:
                        sByokModel = text == null ? "" : text.trim();
                        break;
                    case ConfigProvider.KEY_REVEAL_MODEL:
                        sRevealModel = value;
                        break;
                    case "tts_enable": sTtsEnabled = value; break;
                    case "tts_endpoint":
                        sTtsEndpoint = MiMoTts.normalizeEndpoint(text);
                        break;
                    case "mimo_api_key": sMimoKey = text == null ? "" : text.trim(); break;
                    case ConfigProvider.KEY_TTS_MODEL:
                        sTtsModel = text == null ? "" : text.trim();
                        break;
                    case "notify_enable": sNotifyEnabled = value; break;
                    case "notify_screen_off": sNotifyScreenOff = value; break;
                    case "notify_headset": sNotifyHeadset = value; break;
                    case "notify_voice":
                        sNotifyVoice = text == null ? "" : text.trim();
                        break;
                    case "notify_apps":
                        sNotifyApps = NotifyApps.parse(text);
                        break;
                    case "notify_trusted":
                        sNotifyTrusted = TrustedHeadsets.parse(text);
                        break;
                    case ConfigProvider.KEY_PROFILES:
                        sProfiles = VoiceProfile.parse(text);
                        break;
                    default: break;
                }
            }
        } catch (Throwable ignored) {
            // provider missing or app not installed: keep the last known values
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * The hooked process's {@link Context}.
 *
     * <p>Only {@code currentApplication()} is usable here. The thread's system
     * context belongs to uid 1000, and since Android 10 the framework rejects a
     * provider call whose package does not match the caller's uid
     * ("Given calling package android does not match caller's uid"), so it would
     * be rejected before reaching our own gate.
     *
     * @return null while the process is still initialising — callers must treat
     *         that as "config unavailable", not as "user configured nothing"
     */
    static Context app() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object application = current.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}