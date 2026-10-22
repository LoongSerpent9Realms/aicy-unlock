package dev.loong.aicyunlock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

/**
 * Exposes the module's switches and the MiMo credentials to the hooked process.
 *
 * <p>Hooks run inside Aicy (different uid), so this provider is the only
 * permission-free channel for them to read our settings. Every row carries both
 * a numeric {@code v} and a string {@code s} column, so the same cursor serves
 * booleans and text values without a second provider path.
 */
public final class ConfigProvider extends ContentProvider {

    static final String AUTHORITY = "dev.loong.aicyunlock.cfg";
    static final String PREFS = "config";

    static final String KEY_MASTER = "master";
    static final String KEY_LOG = "block_log";
    static final String KEY_CHAT = "block_chat";
    static final String KEY_CATALOG = "catalog";
    static final String KEY_ONLY_CUSTOM = "only_custom_tier";
    static final String KEY_DEFAULT_TIER = "default_tier";

    /**
     * 自定义模型（BYOK）—— 由模块 App 自己维护，不再依赖 Aicy 那个被藏起来的
     * 「模型设置」页。Aicy 进程里的 hook 会把这几个值当成 {@code model.apiUrl}
     * / {@code model.apiKey} / {@code model.name} 返回给它。
     */
    static final String KEY_BYOK_URL = "byok_url";
    static final String KEY_BYOK_KEY = "byok_key";
    static final String KEY_BYOK_MODEL = "byok_model";

    /**
     * 临时把 Aicy 设置里的「设备」分组换成被藏起来的「模型设置」页，
     * 好让用户在 Aicy 自己的界面里填一次配置（那份配置由 Aicy 自己写入，
     * 不经过我们的 hook，所以一定能生效）。填完把开关关掉即可。
     */
    static final String KEY_REVEAL_MODEL = "reveal_model";
    /** Routes Aicy's stock wake-word page to its hidden custom keyphrase page. */
    static final String KEY_WAKE_WORD = "wake_word";
    /** Hands "打开X搜索Y" sentences to the Flyme task robot's UI agent. */
    static final String KEY_TASK_ROBOT = "task_robot";

    /** MiMo TTS — the voice source behind the custom timbre rows. */
    static final String KEY_TTS_ENABLE = "tts_enable";
    /** Full chat/completions URL; see {@link MiMoTts#normalizeEndpoint}. */
    static final String KEY_TTS_ENDPOINT = "tts_endpoint";
    static final String KEY_MIMO_KEY = "mimo_api_key";
    /**
     * 语音合成时真正发给服务端的 model id。留空 = 用每条音色自带的模型；
     * 填了就统一替换（音色的「设计 / 复刻 / 预置」模式仍按音色自己的选择走）。
     */
    static final String KEY_TTS_MODEL = "tts_model";

    /**
     * Notification read-aloud. Read by both the module's fallback listener and
     * the hook inside Aicy's NotificationCollectorService.
     */
    static final String KEY_NOTIFY_ENABLE = "notify_enable";
    static final String KEY_NOTIFY_SCREEN_OFF = "notify_screen_off";
    static final String KEY_NOTIFY_HEADSET = "notify_headset";
    static final String KEY_NOTIFY_VOICE = "notify_voice";
    /** JSON allow-list from {@link NotifyApps}; empty means every app. */
    static final String KEY_NOTIFY_APPS = "notify_apps";
    /** JSON allow-list from {@link TrustedHeadsets}; empty means any headset. */
    static final String KEY_NOTIFY_TRUSTED = "notify_trusted";
    /**
     * Timestamp written from Aicy's process while its notification hook is
     * alive. The settings page uses it to tell "announcements really run inside
     * Aicy" from "Aicy holds the permission but our hook is not loaded (yet)".
     */
    static final String KEY_NOTIFY_READY_AT = "notify_ready_at";

    /**
     * Pre-multi-voice keys, read once by {@link VoiceStore} to upgrade a 1.x
     * install into a one-entry profile list. No longer written.
     */
    static final String KEY_MIMO_MODEL = "mimo_model";
    static final String KEY_MIMO_DESC = "mimo_voice_desc";
    /** Legacy single reference clip; {@link VoiceStore} claims it as {@code sample_v1}. */
    static final String KEY_MIMO_SAMPLE = "mimo_sample";

    /** Per-profile clip storage: {@code sample_<key>}. Legacy single clip is "mimo_sample". */
    static final String KEY_SAMPLE_PREFIX = "sample_";

    /** Serialised profile list (see {@link VoiceProfile}). */
    static final String KEY_PROFILES = "voice_profiles";
    /** One profile's decrypted clip, served on .../clip/<key>. */
    static final String KEY_CLIP = "clip";

    /** URI path that serves one profile's decrypted clip on demand. */
    static final String PATH_CLIP = "clip";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The only foreign uid allowed to read this provider (the hooked app). */
    private static final String TARGET_PACKAGE = "com.meizu.voiceassistant";

    /**
     * The wake-word engine. It may not read any setting — it is only allowed to
     * ask for one spoken cue line through {@link WakeCue#METHOD}.
     */
    private static final String SVA_PACKAGE = "com.qualcomm.qti.sva";

    /**
     * Serialised profile list, cached against the prefs file it came from.
     *
     * <p>{@code query} runs on a binder thread and the caller polls it every few
     * seconds; re-parsing JSON plus running the upgrade path each time would be
     * wasteful. The cache key is the stored document itself, so an edit from the
     * settings app invalidates it without any explicit notification.
     */
    private volatile String cachedRaw;
    private volatile String cachedList;

    private String profileList() {
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // A 1.x install has no list yet, so load() materialises and persists one
        // on the way through — re-read afterwards so the cache key is the real
        // document rather than the empty pre-migration state.
        List<VoiceProfile> list = VoiceStore.load(getContext());
        String stored = sp.getString(VoiceProfile.PREFS_LIST, null);
        if (stored == null) return VoiceProfile.wrap(list).toString();
        if (stored.equals(cachedRaw)) return cachedList;
        String serialised = VoiceProfile.wrap(list).toString();
        cachedRaw = stored;
        cachedList = serialised;
        return serialised;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // The provider has to stay exported (hooks run inside Aicy's uid), but
        // an unrestricted export would hand the plaintext MiMo key to any app
        // on the device. Gate it on the caller's uid instead.
        if (!allowedCaller()) {
            return new MatrixCursor(new String[]{"k", "v", "s"});
        }
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(new String[]{"k", "v", "s"});
        cursor.addRow(new Object[]{KEY_MASTER, sp.getBoolean(KEY_MASTER, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_LOG, sp.getBoolean(KEY_LOG, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_CHAT, sp.getBoolean(KEY_CHAT, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_CATALOG, sp.getBoolean(KEY_CATALOG, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_ONLY_CUSTOM, sp.getBoolean(KEY_ONLY_CUSTOM, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_DEFAULT_TIER, 0,
                sp.getString(KEY_DEFAULT_TIER, CatalogPatch.FREE_TIER_ID)});
        cursor.addRow(new Object[]{KEY_BYOK_URL, 0, sp.getString(KEY_BYOK_URL, "")});
        cursor.addRow(new Object[]{KEY_BYOK_KEY, 0, sp.getString(KEY_BYOK_KEY, "")});
        cursor.addRow(new Object[]{KEY_BYOK_MODEL, 0, sp.getString(KEY_BYOK_MODEL, "")});
        cursor.addRow(new Object[]{KEY_REVEAL_MODEL,
                sp.getBoolean(KEY_REVEAL_MODEL, false) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_WAKE_WORD, sp.getBoolean(KEY_WAKE_WORD, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_TASK_ROBOT, sp.getBoolean(KEY_TASK_ROBOT, true) ? 1 : 0, ""});

        cursor.addRow(new Object[]{KEY_TTS_ENABLE, sp.getBoolean(KEY_TTS_ENABLE, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_TTS_ENDPOINT, 0,
                sp.getString(KEY_TTS_ENDPOINT, MiMoTts.DEFAULT_ENDPOINT)});
        cursor.addRow(new Object[]{KEY_MIMO_KEY, 0,
                SecretStore.open(getContext(), sp.getString(KEY_MIMO_KEY, ""))});
        cursor.addRow(new Object[]{KEY_TTS_MODEL, 0, sp.getString(KEY_TTS_MODEL, "")});

        cursor.addRow(new Object[]{KEY_NOTIFY_ENABLE,
                sp.getBoolean(KEY_NOTIFY_ENABLE, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_NOTIFY_SCREEN_OFF,
                sp.getBoolean(KEY_NOTIFY_SCREEN_OFF, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_NOTIFY_HEADSET,
                sp.getBoolean(KEY_NOTIFY_HEADSET, true) ? 1 : 0, ""});
        cursor.addRow(new Object[]{KEY_NOTIFY_VOICE, 0,
                sp.getString(KEY_NOTIFY_VOICE, "")});
        cursor.addRow(new Object[]{KEY_NOTIFY_APPS, 0,
                sp.getString(KEY_NOTIFY_APPS, "")});
        cursor.addRow(new Object[]{KEY_NOTIFY_TRUSTED, 0,
                sp.getString(KEY_NOTIFY_TRUSTED, "")});

        // The whole profile list in one small row: names, descriptions and preset
        // ids are short, so one binder round-trip per refresh covers all of them.
        cursor.addRow(new Object[]{KEY_PROFILES, 0, profileList()});

        // A clone profile's clip is up to 900 KB of Base64 — far too large to
        // carry on every refresh — so it is served separately at .../clip/<key>
        // and pulled only for the profile actually being synthesized.
        String clip = clipKeyOf(uri);
        if (clip != null) {
            cursor.addRow(new Object[]{KEY_CLIP, 0, VoiceStore.clip(getContext(), clip)});
        }
        return cursor;
    }

    /** Extracts the {@code sampleKey} from {@code .../clip/<key>}, or null. */
    private static String clipKeyOf(Uri uri) {
        if (uri == null) return null;
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() < 2 || !PATH_CLIP.equals(segments.get(0))) return null;
        String key = segments.get(1);
        return key.isEmpty() ? null : key;
    }

    /**
     * True only for our own process and the hooked assistant.
     *
     * <p>Package-name based permissions cannot express this: a
     * {@code signature} permission would also lock Aicy out (different signer),
     * so the uid check is the only workable gate.
     */
    private boolean allowedCaller() {
        int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return true;
        String[] packages = null;
        try {
            packages = getContext().getPackageManager().getPackagesForUid(uid);
        } catch (Throwable ignored) {
            // fall through to deny
        }
        if (packages != null) {
            for (String name : packages) {
                if (TARGET_PACKAGE.equals(name)) {
                    android.util.Log.i("AicyUnlock",
                            "cfg read allowed · uid=" + uid + " · " + name);
                    return true;
                }
            }
        }
        android.util.Log.w("AicyUnlock",
                "cfg read denied · uid=" + uid + " · "
                        + (packages == null ? "?" : java.util.Arrays.toString(packages)));
        return false;
    }

    /**
     * True for the wake-word engine that asks to have a round spoken.
     *
     * <p>{@code com.qualcomm.qti.sva} runs as {@code android.uid.system}, so a
     * uid check cannot be narrowed below "one of the system's own processes";
     * that is exactly why the cue method answers only a boolean and a reason and
     * never returns the MiMo key or the settings the assistant is allowed to
     * read. A third-party app still gets denied here.
     */
    private boolean wakeCueCaller() {
        int uid = android.os.Binder.getCallingUid();
        String[] packages = null;
        try {
            packages = getContext().getPackageManager().getPackagesForUid(uid);
        } catch (Throwable ignored) {
            // fall through to deny
        }
        if (packages != null) {
            for (String name : packages) {
                if (SVA_PACKAGE.equals(name)) return true;
            }
        }
        android.util.Log.w("AicyUnlock",
                "wake cue denied · uid=" + uid + " · "
                        + (packages == null ? "?" : java.util.Arrays.toString(packages)));
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.dev.loong.aicyunlock.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * Diagnostics and the wake-word cue channel for the hooked processes.
     *
     * <p>Replaces the old exported broadcast receiver: {@code call()} keeps the
     * caller's uid, so the same gate as {@link #query} applies and an unrelated
     * app can no longer inject fake log lines.
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        // Runs the synthesis and waits for the audio to finish, so it stays on
        // this binder thread; the engine's hook is the caller and only ever
        // learns whether the line was spoken.
        if (WakeCue.METHOD.equals(method)) {
            return wakeCueCaller() && extras != null
                    ? WakeCue.play(getContext(), extras.getInt(WakeCue.KEY_ROUND, 1))
                    : null;
        }
        if (!allowedCaller()) return null;
        if ("notify-ready".equals(method)) {
            long at = extras == null ? System.currentTimeMillis()
                    : extras.getLong("at", System.currentTimeMillis());
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_NOTIFY_READY_AT, at).apply();
            return null;
        }
        if (!"diag".equals(method) || extras == null) return null;
        String line = extras.getString("line");
        if (line == null || line.isEmpty()) return null;
        SharedPreferences sp = getContext()
                .getSharedPreferences(DiagReceiver.PREFS, Context.MODE_PRIVATE);
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        String log = sp.getString(DiagReceiver.KEY_LOG, "");
        String next = stamp + "  " + line + "\n" + (log == null ? "" : log);
        if (next.length() > DiagReceiver.maxChars()) {
            next = next.substring(0, DiagReceiver.maxChars());
        }
        sp.edit().putString(DiagReceiver.KEY_LOG, next)
                .putLong(DiagReceiver.KEY_AT, System.currentTimeMillis())
                .apply();
        return null;
    }
}
