package dev.loong.aicyunlock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Aicy 助手 13.0.54 (com.meizu.voiceassistant) hooks.
 *
 * Verified signatures (v13.0.54, 6 dex):
 *   flag field   L  com/google/android/gms/internal/mlkit_vision_common/J3;  field: a (static boolean)
 *   assignment   L  com/flyme/agentos/app/phone/PhoneApp$runtimeInitialization$1;  invoke()
 *                (const/4 v3, #int 0 ; sput-boolean v3, J3.a  -- the only write)
 *   render gate  L  com/flyme/agentos/app/common/ui/settings/n0;  invoke()
 *                (sget-boolean J3.a ; if-eqz -> skips the "模型设置" item)
 *
 * BYOK config keys (SharedPreferences): model.providerPreset / model.apiUrl /
 * model.apiKey / model.name / model.temperature / model.contextWindowRatio.
 *
 * Power-key entry: pref power_key_wakeup (PowerKeyWakeupSettings) is only a
 * boolean wake toggle. Long-press power opens QuickVoiceController, which uses
 * the same global model.* config plus a "tier" chip (tierId), so the BYOK model
 * is already the default for the power-button path.
 */
final class AicyHooks {

    interface Installer {
        void hook(String className, String methodName,
                  XposedInterface.Hooker hooker, Class<?>... parameterTypes);

        /** Hooks an already-resolved method (used by structural discovery). */
        void hook(Method method, XposedInterface.Hooker hooker);
    }

    // ---- BYOK reveal ----
    /**
     * R8 parked AgentOS's feature flags inside this package, so the *package*
     * is stable while the class suffixes (J3, n0, …) change on every build.
     * Discovery therefore works on structure, not on names.
     */
    private static final String FLAG_PACKAGE =
            "com.google.android.gms.internal.mlkit_vision_common";
    /** Exact names from 13.0.54 — used only as a fast path, never required. */
    private static final String FLAG_CLASS = FLAG_PACKAGE + ".J3";
    private static final String FLAG_FIELD = "a";
    private static final String ASSIGN_CLASS =
            "com.flyme.agentos.app.phone.PhoneApp$runtimeInitialization$1";
    /**
     * The settings menu is a Compose list of {@code SettingsContentPage} enum
     * items. In 13.0.56 the enum still carries MODEL ("模型设置") and
     * MODEL_MANAGEMENT, but the app filters both out before rendering, so the
     * rows never appear. Discovered structurally — see {@link #revealModelSettings}.
     */
    private static final String SETTINGS_CONTENT_PAGE =
            "com.flyme.agentos.app.common.ui.settings.SettingsContentPage";
    /**
     * Package prefix WITHOUT a trailing dot: {@link DexIndex#classesIn} appends
     * one itself, so a trailing dot here would scan for {@code "..name"} and
     * silently match nothing.
     */
    private static final String SETTINGS_PKG = "com.flyme.agentos.app.common.ui.settings";

    /** Obfuscated leaf name, e.g. "J3", "a", "n0". */
    private static final Pattern SHORT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,3}");

    // ---- tier diagnostics (power-key / quick voice path) ----
    private static final String QUICK_VOICE_CLASS =
            "com.flyme.agentos.app.phone.voicequick.QuickVoiceController";
    private static final String CHAT_VM_CLASS =
            "com.flyme.agentos.app.common.viewmodel.ChatViewModel";

    // ---- Privacy firewall ----
    /**
     * Master switch. Blocks are *conditional*: they only apply while a custom
     * (BYOK) model is configured. With a stock cloud model nothing is blocked,
     * so normal chat (voice.meizu.com / llm-router-web / g.meizu.com model list)
     * keeps working untouched.
     */
    private static final boolean ENFORCE_EGRESS = true;

    /** Hosts worth cutting once the prompt no longer belongs to Meizu. */
    private static final String[] BLOCK_SUFFIXES = {
            "voice.meizu.com",          // /c/android/v2/llm/chat-messages  (conversation)
            "llm-router-web.flyme.com", // LLM router / realtime
            "g.meizu.com",              // algorithm/service/model.do (model list)
            "credits-api.flyme.com",    // usage / credit reporting
            "aider.meizu.com",
            "skill-hub.flyme.com",
            "mcp-gw.flyme.com",         // MCP tool gateway
    };

    /**
     * Log / telemetry upload endpoints. Blocked whenever the "拦截日志上传"
     * switch is on — independently of BYOK, because the developer tools' log
     * upload is exactly what the user wants off.
     */
    private static final String[] LOG_SUFFIXES = {
            "uxip.meizu.com",           // UXIP: user-experience telemetry
            "uxip-config.meizu.com",
            "uxip-res.meizu.com",
            "api-savior.meizu.com",     // Savior: crash / patch reporting
    };

    /** Anything Meizu/Flyme owned. Third-party endpoints are never touched. */
    private static final String[] VENDOR_SUFFIXES = {"meizu.com", "flyme.com"};

    private static final Pattern PREF_STRING = Pattern.compile(
            "<string name=\"model\\.(providerPreset|apiUrl|apiKey|name)\">([^<]*)</string>");

    /** Cache key Aicy uses for the model catalog document (see CatalogPatch). */
    private static final String CATALOG_PREF_KEY = "model_catalog_remote_json";

    /**
     * Last tier id the app read or wrote (chat-auto / chat-deep / chat-lite /
     * chat-custom). Used to decide whether the BYOK endpoint should be visible
     * when "only the 自定义 tier may use my own API" is enabled.
     *
     * <p>Defaults to the free tier, i.e. "not custom": if the app never tells
     * us which tier is active, the safe assumption is that the stock Meizu
     * cloud should serve the request. Otherwise a freshly started app would
     * push every tier through the user's BYOK endpoint.
     */
    private static volatile String sCurrentTier = CatalogPatch.FREE_TIER_ID;
    /** True once Aicy has actually told us which tier the user picked. */
    private static volatile boolean sTierPicked;

    private static final long MODE_TTL_MS = 3_000L;

    private final ClassLoader loader;
    private final BiConsumer<String, Throwable> log;

    // cached BYOK detection
    private static long sCheckedAt;
    private static boolean sByok;
    private static String sModelName;
    private static String sApiHost;
    private static Boolean sLastLogged;

    AicyHooks(ClassLoader loader, BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.log = log;
    }

    void install(Installer installer) {
        revealModelSettings(installer);
        installCatalogPatches(installer);
        installTierInjection(installer);
        installDataStoreGate(installer);
        installModelConfigGate(installer);
        installTierSelectionHook(installer);
        installTierDiagnostics(installer);
        installEgressFirewall(installer);
        installTtsTakeover(installer);
        installNotificationSpeech(installer);
        installMediaControl(installer);
        log.accept("AicyUnlock: hooks installed for com.meizu.voiceassistant", null);
    }

    /**
     * 媒体控制接管：把 Aicy 内置 {@code media_control} 工具的播控后端
     * 从「QQ音乐 Flyme 版」换成标准 MediaSession 直控，
     * 这样「下一首 / 上一首 / 暂停 / 继续」能作用于当前在播的任意 App。
     *
     * <p>免 root：Aicy 是系统应用且已 granted {@code MEDIA_CONTENT_CONTROL}。
     */
    private void installMediaControl(Installer installer) {
        try {
            new MediaControlInjector(loader, installer, log).install();
        } catch (Throwable error) {
            log.accept("AicyUnlock: media-control injection install failed", error);
        }
    }

    // ------------------------------------------------------------------
    // 0. BYOK config lives in DataStore, not SharedPreferences
    // ------------------------------------------------------------------

    private static final String DATASTORE_KEY = "androidx.datastore.preferences.core.Preferences$Key";
    private static final String DATASTORE_MUTABLE =
            "androidx.datastore.preferences.core.MutablePreferences";

    /**
     * {@code ModelConfigDataStore} keeps {@code model.apiUrl} / {@code
     * model.apiKey} in a DataStore {@code .preferences_pb} file, so the
     * SharedPreferences gate never sees them. Read them through the DataStore
     * accessor instead and blank the two BYOK keys whenever a stock tier is
     * selected — that is what makes "only 自定义 uses my endpoint" true.
     */
    private void installDataStoreGate(Installer installer) {
        Class<?> keyType;
        try {
            keyType = Class.forName(DATASTORE_KEY, false, loader);
        } catch (Throwable error) {
            log.accept("AicyUnlock: datastore gate skipped (no Preferences$Key)", error);
            return;
        }
        String[] owners = {
                DATASTORE_MUTABLE,
                "androidx.datastore.preferences.core.Preferences",
        };
        int armed = 0;
        for (String owner : owners) {
            try {
                Class<?> type = Class.forName(owner, false, loader);
                Method get = type.getDeclaredMethod("get", keyType);
                installer.hook(get, chain -> {
                    Object value = chain.proceed();
                    if (!(value instanceof String)) return value;
                    if (!RemoteConfig.master() || !RemoteConfig.onlyCustomTier()) return value;
                    if (isCustomTier()) return value;
                    String name = keyName(chain.getArg(0));
                    if (name == null) return value;
                    if (isByokKey(name)) {
                        logOnce("byok hidden (datastore) while tier=" + sCurrentTier);
                        return "";
                    }
                    return value;
                });
                armed++;
                log.accept("AicyUnlock: datastore gate armed on " + owner + "#get", null);
            } catch (Throwable error) {
                log.accept("AicyUnlock: datastore gate skipped on " + owner, error);
            }
        }
        if (armed == 0) {
            log.accept("AicyUnlock: datastore gate unavailable (class names likely obfuscated)", null);
        }
    }

    /** Real class name of {@code ModelConfigDataStore} after R8 (via its this$0). */
    private static final String MODEL_STORE = "com.flyme.agentos.app.phone.repository.i";

    /** Package holding the renamed datastore-preferences classes. */
    private static final String PREF_CORE = "androidx.datastore.preferences.core.";

    /**
     * Aicy 的原生语义：{@code model.providerPreset=custom} 是**全局**开关，
     * 一旦配置了自建接口，所有档位都会走它，档位只切换模型名。所以「只有自定义
     * 档位走自建」必须由我们把这层开关按档位屏蔽掉。
     *
     * <p>DataStore 的 {@code Preferences}/{@code MutablePreferences} 被 R8 改名，
     * 按类名 hook 一定落空（实测 {@code datastore gate armed} 从未出现）。
     * 但持有这些键的 {@code ModelConfigDataStore} 没有改名，于是改为在它上面
     * 按「返回值内容」拦截：返回 {@code custom} 或一个非厂商域名的 URL 的
     * 取值方法，在非自定义档位下一律读成空串。</p>
     */
    /** Real class name of {@code ChatViewModel} after R8 (via its this$0). */
    private static final String CHAT_VIEW_MODEL = "com.flyme.agentos.app.common.viewmodel.u0";

    /**
     * The tier is not persisted anywhere we can read (no {@code tier_id} key in
     * either DataStore file), so the only reliable source of truth is the
     * moment the user taps an entry: {@code ChatViewModel#selectModelTier(id)}.
     * Recording that id is what lets the preference gate tell 自定义 apart from
     * the stock tiers.
     */
    private void installTierSelectionHook(Installer installer) {
        try {
            Class<?> type = Class.forName(CHAT_VIEW_MODEL, false, loader);
            int armed = 0;
            for (Method method : declaredMethods(type)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length > 3) continue;
                boolean hasString = false;
                boolean hasContinuation = false;
                for (Class<?> param : params) {
                    if (param == String.class) hasString = true;
                    if ("kotlin.coroutines.Continuation".equals(param.getName())) {
                        hasContinuation = true;
                    }
                }
                if (!hasString || !hasContinuation) continue;
                final int count = params.length;
                try {
                    installer.hook(method, chain -> {
                        for (int i = 0; i < count; i++) {
                            Object arg;
                            try {
                                arg = chain.getArg(i);
                            } catch (Throwable ignored) {
                                continue;
                            }
                            if (arg instanceof String && ((String) arg).startsWith("chat-")) {
                                sCurrentTier = (String) arg;
                                sTierPicked = true;
                                log.accept("AicyUnlock: tier selected -> " + arg, null);
                                break;
                            }
                        }
                        return chain.proceed();
                    });
                    armed++;
                } catch (Throwable error) {
                    log.accept("AicyUnlock: tier selection hook skip", error);
                }
            }
            log.accept("AicyUnlock: tier selection hook armed " + armed, null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: tier selection hook unavailable", error);
        }
    }

    /**
     * The datastore-preferences classes are renamed to single letters
     * ({@code androidx.datastore.preferences.core.a … k}), so the Key type is
     * unknown by name. Instead every {@code (something-in-that-package) ->
     * Object} reader is hooked: that signature is the {@code Preferences#get}
     * family. The key's own name is read off the argument, so a hook that
     * matches something else simply never rewrites anything.
     */
    private void gatePreferenceReads(Installer installer) {
        int hooked = 0;
        StringBuilder where = new StringBuilder();
        for (char c = 'a'; c <= 'k'; c++) {
            Class<?> type;
            try {
                type = Class.forName(PREF_CORE + c, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : declaredMethods(type)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;
                if (method.getReturnType() != Object.class) continue;
                if (!params[0].getName().startsWith(PREF_CORE)) continue;
                try {
                    installer.hook(method, chain -> {
                        Object value = chain.proceed();
                        if (!(value instanceof String)) return value;
                        String text = (String) value;
                        String readName = keyName(chain.getArg(0));
                        // The cached remote catalog ships as a DataStore string
                        // (agentos_settings.preferences_pb -> model_catalog_remote_json),
                        // not a SharedPreferences value, so the asset/gson/prefs
                        // rewrite paths never see it. Patch it on this read — this
                        // is what actually makes the user's default-tier choice and
                        // the injected 自定义 tier reach Aicy, because it parses the
                        // JSON we hand back.
                        if (RemoteConfig.master() && RemoteConfig.catalogRewrite()
                                && CatalogPatch.looksLikeCatalog(text)) {
                            String patched = patchCatalog(text);
                            if (patched != null && !patched.equals(text)) {
                                logOnce("catalog patched via datastore read");
                                return patched;
                            }
                            return value;
                        }
                        if (readName != null && text.startsWith("http")
                                && isByokKey(readName) && !looksVendorUrl(text)) {
                            logOnce("pref read " + readName + " tier=" + sCurrentTier);
                        }
                        if (!RemoteConfig.master() || !RemoteConfig.onlyCustomTier()) return value;
                        if (readName == null || !isByokKey(readName)) return value;
                        if (isCustomTier()) {
                            // 模块自己的配置优先：用户现在在 aicy-unlock 里填
                            // 地址/Key/模型名，Aicy 那个「模型设置」页已经没入口了。
                            String mine = moduleByokValue(readName);
                            if (mine != null) return mine;
                            // The app may have written the blanked value back to
                            // disk; the real one is kept on our side.
                            if (text.isEmpty()) {
                                String saved = recallByok(readName);
                                if (saved != null) return saved;
                            }
                            return value;
                        }
                        logOnce("pref gate blanked " + readName + " while tier=" + sCurrentTier);
                        return "";
                    });
                    hooked++;
                    if (where.length() < 120) {
                        where.append(type.getSimpleName()).append('#')
                                .append(method.getName()).append(' ');
                    }
                } catch (Throwable error) {
                    log.accept("AicyUnlock: pref gate skip " + type.getSimpleName()
                            + "#" + method.getName(), error);
                }
            }
        }
        log.accept("AicyUnlock: pref gate hooked " + hooked + " [" + where + "]", null);
    }

    private void installModelConfigGate(Installer installer) {
        gatePreferenceReads(installer);
        Class<?> type;
        try {
            type = Class.forName(MODEL_STORE, false, loader);
        } catch (Throwable error) {
            log.accept("AicyUnlock: model store missing (" + MODEL_STORE + ")", error);
            probeDexClassNames();
            return;
        }
        StringBuilder shape = new StringBuilder();
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            shape.append(field.getType().getSimpleName()).append(' ')
                    .append(field.getName()).append("; ");
        }
        log.accept("AicyUnlock: model store shape {" + shape + "}", null);

        int hooked = 0;
        StringBuilder names = new StringBuilder();
        for (Method method : declaredMethods(type)) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            Class<?> returns = method.getReturnType();
            if (returns != String.class && returns != Object.class) continue;
            if (method.getParameterCount() > 1) continue;
            final String where = method.getName();
            try {
                installer.hook(method, chain -> {
                    Object value = chain.proceed();
                    if (!(value instanceof String)) return value;
                    if (!RemoteConfig.master() || !RemoteConfig.onlyCustomTier()) return value;
                    if (isCustomTier()) return value;
                    String text = (String) value;
                    if (text.isEmpty() || !looksByok(text)) return value;
                    logOnce("byok blanked at " + where + " -> " + text);
                    return "";
                });
                hooked++;
                if (names.length() < 200) names.append(where).append(' ');
            } catch (Throwable error) {
                log.accept("AicyUnlock: model store hook skip " + where, error);
            }
        }
        log.accept("AicyUnlock: model store gate hooked " + hooked + " [" + names + "]", null);
        installModelMapInjector(installer, type);
    }

    /**
     * 13.0.56 的 {@code ModelConfigDataStore} 不是逐键 {@code get}，而是把整个
     * DataStore 的 Map 一次性映射成配置对象（{@code (Map, Continuation) -> Object}），
     * 所以按 key 的读取门控根本看不到 {@code model.apiUrl}。这里在映射之前把
     * 用户的自定义模型写进那份 Map —— 这是唯一能让 Aicy 真正用上模块配置的点。
     */
    private void installModelMapInjector(Installer installer, Class<?> store) {
        int hooked = 0;
        for (final Method method : declaredMethods(store)) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 2) continue;
            if (!Map.class.isAssignableFrom(p[0])) continue;
            // The continuation really is kotlin.coroutines.jvm.internal.ContinuationImpl.
            if (!p[1].getName().startsWith("kotlin.coroutines")) continue;
            try {
                installer.hook(method, chain -> {
                    if (!RemoteConfig.master() || !RemoteConfig.onlyCustomTier()) {
                        return chain.proceed();
                    }
                    // Aicy 的 BYOK 是全局开关：只要它自己的配置里 providerPreset=custom，
                    // 所有档位都会走自建接口。所以在这条唯一的读取路上按档位掩蔽 ——
                    // 非自定义档位把三项读成空，让它回落到魅族云。
                    if (isCustomTier()) return chain.proceed();
                    Object arg = chain.getArg(0);
                    if (!(arg instanceof Map)) return chain.proceed();
                    Map<Object, Object> patched = new LinkedHashMap<>();
                    boolean touched = false;
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) arg).entrySet()) {
                        Object key = entry.getKey();
                        String name = keyName(key);
                        if (name != null && isByokKey(name)) {
                            patched.put(key, "");
                            touched = true;
                        } else {
                            patched.put(key, entry.getValue());
                        }
                    }
                    if (!touched) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = patched;
                    logOnce("model config gated (preset/url/key cleared) tier=" + sCurrentTier);
                    return chain.proceed(args);
                });
                hooked++;
                log.accept("AicyUnlock: model map injector armed on " + store.getName()
                        + "#" + method.getName(), null);
            } catch (Throwable error) {
                log.accept("AicyUnlock: model map injector skip", error);
            }
        }
        if (hooked == 0) {
            log.accept("AicyUnlock: model map injector found no (Map, Continuation) method", null);
        }
    }

    /** A value that only exists because the user configured a BYOK endpoint. */
    private static boolean looksByok(String text) {
        if ("custom".equalsIgnoreCase(text)) return true;
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false;
        String host = hostOfUrl(text);
        return host != null && !matches(host, VENDOR_SUFFIXES);
    }

    /**
     * Every class name actually present in the app's own dex files. R8 renames
     * most of them, so the point of this probe is to stop guessing: it reports
     * the real names of the classes that mention the model config.
     */
    private void probeDexClassNames() {
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!base.isInstance(loader)) {
                log.accept("AicyUnlock: dex probe skipped (loader "
                        + loader.getClass().getName() + ")", null);
                return;
            }
            java.lang.reflect.Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            java.lang.reflect.Field elementsField =
                    pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);

            StringBuilder hits = new StringBuilder();
            int scanned = 0;
            int matched = 0;
            for (Object element : elements) {
                java.lang.reflect.Field dexField = element.getClass().getDeclaredField("dexFile");
                dexField.setAccessible(true);
                Object dex = dexField.get(element);
                if (dex == null) continue;
                Method entries = dex.getClass().getMethod("entries");
                entries.setAccessible(true);
                java.util.Enumeration<?> names = (java.util.Enumeration<?>) entries.invoke(dex);
                while (names != null && names.hasMoreElements()) {
                    Object item = names.nextElement();
                    if (!(item instanceof String)) continue;
                    String name = (String) item;
                    scanned++;
                    if (!name.contains("ModelConfig") && !name.contains("DataStore")
                            && !name.contains("providerPreset") && !name.contains("ProviderPreset")) {
                        continue;
                    }
                    matched++;
                    if (hits.length() < 700) hits.append(name).append('\n');
                }
            }
            log.accept("AicyUnlock: dex probe scanned " + scanned + " classes, "
                    + matched + " candidates:\n" + hits, null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: dex probe failed", error);
        }
    }

    /**
     * The four keys that together mean "a custom endpoint is configured".
     * Blanking only the URL would leave {@code providerPreset} pointing at a
     * custom provider with an empty address, so all four go together — the app
     * then sees exactly the state of a fresh install and serves the request
     * from its own cloud.
     */
    private static boolean isByokKey(String name) {
        return name.equalsIgnoreCase("model.apiUrl")
                || name.equalsIgnoreCase("model.apiKey")
                || name.equalsIgnoreCase("model.providerPreset")
                || name.equalsIgnoreCase("model.name");
    }

    /** {@code Preferences.Key#getName}, by method or by field, whichever exists. */
    private static String keyName(Object key) {
        if (key == null) return null;
        // 映射表里的键有时是裸字符串，不是 Preferences.Key —— 直接返回。
        if (key instanceof String) return (String) key;
        try {
            Method getName = key.getClass().getMethod("getName");
            Object value = getName.invoke(key);
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {
            // fall through to field access
        }
        for (java.lang.reflect.Field field : key.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(key);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {
                // try the next one
            }
        }
        return null;
    }

    /**
     * Cloud-TTS takeover: rewrites the DoubaoTtsApiClient endpoint to a
     * loopback server so a custom voice can be injected without touching the
     * app's playback pipeline. See {@link TtsHooks}.
     */
    private void installTtsTakeover(Installer installer) {
        try {
            new TtsHooks(loader, log).install(installer);
        } catch (Throwable error) {
            log.accept("AicyUnlock: TTS takeover install failed", error);
        }
    }

    /**
     * Notification read-aloud inside Aicy. When Aicy has 通知使用权 its own
     * collector speaks; the module's listener steps aside (and takes over again
     * if that permission is revoked).
     */
    private void installNotificationSpeech(Installer installer) {
        try {
            new NotifyHooks(loader, log).install(installer);
        } catch (Throwable error) {
            log.accept("AicyUnlock: notification speech install failed", error);
        }
    }

    // ------------------------------------------------------------------
    // 1. Reveal the hidden BYOK "模型设置" entry
    // ------------------------------------------------------------------

    private void revealModelSettings(Installer installer) {
        // (a) Legacy fast paths from 13.0.54. Both are dead on 13.0.56 (the
        //     class no longer exists / was reused by MLKit maths), kept only so
        //     an older ROM still benefits. Failures are non-fatal.
        try {
            installer.hook(ASSIGN_CLASS, "invoke", chain -> {
                Object result = chain.proceed();
                setFlag(true);
                return result;
            });
        } catch (Throwable ignored) {
            // class renamed away — the structural path below does the work
        }
        setFlag(true);

        // (b) Structure-based path: the settings menu is a Compose list over the
        //     SettingsContentPage enum. Whatever produces that list drops MODEL
        //     and MODEL_MANAGEMENT, so hook every producer that hands back a
        //     collection and re-insert the hidden entries.
        snapshotFeatureFlags();
        int patched = installSettingsMenuHook(installer);
        installPolicyRowRename(installer);
        installer.hook("android.app.Activity", "onResume", chain -> {
            Object result = chain.proceed();
            enforceFeatureFlags();
            if (sFirstResume.compareAndSet(false, true)) {
                report("hooks installed · first Activity resumed in Aicy");
            }
            return result;
        });
        log.accept("AicyUnlock: model-settings (BYOK) reveal armed (structural, menu hooks="
                + patched + ")", null);
    }

    /**
     * Finds the methods that build the settings menu and makes them return the
     * full {@code SettingsContentPage} set again.
     *
     * <p>Discovery is by signature, not by name: any zero-argument method in
     * {@code com.flyme.agentos.app.common.ui.settings.*} that returns a
     * {@code List}/{@code Collection} whose elements are SettingsContentPage is
     * a menu producer. The hook filters the result through
     * {@link #ensureModelPages}, which appends the missing entries.
     */
    private int installSettingsMenuHook(Installer installer) {
        Class<?> page;
        try {
            page = Class.forName(SETTINGS_CONTENT_PAGE, false, loader);
        } catch (Throwable error) {
            log.accept("AicyUnlock: SettingsContentPage missing, menu hook skipped", error);
            return 0;
        }
        if (page.isEnum()) {
            log.accept("AicyUnlock: SettingsContentPage pages = " + enumNames(page), null);
            // Trace who actually consumes the enum: that is the settings-menu
            // producer, and its caller decides which rows survive.
            try {
                installer.hook(page.getMethod("values"), chain -> {
                    Object result = chain.proceed();
                    logOnce("page values() from " + caller());
                    return result;
                });
            } catch (Throwable ignored) {
                // not an enum with values(), fine
            }
            try {
                installer.hook(page.getMethod("getEntries"), chain -> {
                    Object result = chain.proceed();
                    logOnce("page entries() from " + caller());
                    return result;
                });
            } catch (Throwable ignored) {
                // no entries accessor in this build
            }
        }
        int pkgSize = 0;
        int armed = 0;
        String pageSimple = page.getSimpleName();
        java.util.List<String> names = classesInPackage(SETTINGS_PKG);
        pkgSize = names.size();
        log.accept("AicyUnlock: settings package scan -> " + pkgSize + " classes", null);
        installedRowInjector = installRowInjector(installer, page, names);
        for (String name : names) {
            if (name.endsWith(pageSimple)) continue;
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : declaredMethods(type)) {
                Class<?> ret = method.getReturnType();
                if (!Collection.class.isAssignableFrom(ret) && !ret.isArray()) continue;
                try {
                    installer.hook(method, chain -> {
                        Object result = chain.proceed();
                        return ensureModelPages(result, page, name, method.getName());
                    });
                    armed++;
                } catch (Throwable error) {
                    log.accept("AicyUnlock: settings menu hook skipped on "
                            + name + "#" + method.getName(), error);
                }
            }
        }
        return armed;
    }

    /**
     * Returns the menu list with MODEL / MODEL_MANAGEMENT guaranteed present.
     * Anything we cannot recognise is passed through untouched.
     *
     * <p>Only collections whose elements really are {@code SettingsContentPage}
     * are rewritten — the settings package also returns lists of unrelated
     * types, and touching those would corrupt the UI.
     */
    private Object ensureModelPages(Object result, Class<?> page, String owner, String method) {
        if (!RemoteConfig.master()) return result;
        try {
            if (result instanceof Object[]) {
                Object[] src = (Object[]) result;
                if (src.length > 0 && !page.isInstance(src[0])) return result;
                List<Object> list = new ArrayList<>();
                java.util.Collections.addAll(list, src);
                boolean grew = addMissingPages(list, page);
                if (grew) logOnce("settings menu (array " + owner + "#" + method
                        + ") +" + list.size());
                return result;
            }
            if (!(result instanceof List<?>)) return result;
            List<?> src = (List<?>) result;
            if (src.isEmpty()) return result;
            Object first = null;
            for (Object o : src) {
                if (o != null) { first = o; break; }
            }
            if (first == null || !page.isInstance(first)) {
                if (sSeenLists.add(owner + "#" + method)) {
                    log.accept("AicyUnlock: settings list " + owner + "#" + method
                            + " size=" + src.size() + " first="
                            + (first == null ? "null" : first.getClass().getSimpleName())
                            + " (not a page list)", null);
                }
                return result;
            }
            List<Object> list = new ArrayList<>(src);
            if (!addMissingPages(list, page)) return result;
            logOnce("settings menu (" + owner + "#" + method + ") restored, now "
                    + list.size() + " rows");
            return list;
        } catch (Throwable error) {
            return result;
        }
    }

    /** Returns the number of rows we injected (0 when the renderer was not found). */
    private int installRowInjector(Installer installer, Class<?> page, List<String> names) {
        int hooked = 0;
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (final Method method : declaredMethods(type)) {
                Class<?>[] params = method.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != void.class) continue;
                if (params.length < 40 || params[0] != page) continue;
                try {
                    method.setAccessible(true);
                    installer.hook(method, chain -> {
                        try {
                            Object self = chain.getArg(0);
                            if (!(self instanceof Enum)) return chain.proceed();
                            String name0 = ((Enum<?>) self).name();
                            if (sRenderedPages.add(name0)) {
                                log.accept("AicyUnlock: settings renders page " + name0, null);
                            }
                            // Swap the section that renders the 权限和隐私 group
                            // for the hidden 模型设置 page. This only changes an
                            // argument at an existing call site, so the Compose
                            // group keys stay untouched (replaying the call
                            // instead would collide and corrupt the composition).
                            if (RemoteConfig.revealModelPage() && SACRIFICE_PAGE.equals(name0)) {
                                Object model = enumConstant(page, "MODEL");
                                if (model != null) {
                                    Object[] args = chain.getArgs().toArray();
                                    args[0] = model;
                                    logOnce("settings: " + name0
                                            + " group rendered as MODEL (模型设置)");
                                    return chain.proceed(args);
                                }
                            }
                        } catch (Throwable ignored) {
                            // never break the host's composition
                        }
                        return chain.proceed();
                    });
                    hooked++;
                    log.accept("AicyUnlock: settings row renderer = "
                            + name + "#" + method.getName() + " (" + params.length + " params)",
                            null);
                } catch (Throwable error) {
                    log.accept("AicyUnlock: row renderer hook skipped on "
                            + name + "#" + method.getName(), error);
                }
            }
        }
        return hooked;
    }

    /**
     * Draws the 模型设置 group right after 界面设置 by replaying the renderer with
     * a different page argument.
     *
     * <p>13.0.56 flattened the settings sections into one scrolling column and
     * simply stopped emitting the MODEL / MODEL_MANAGEMENT entries; the page
     * composable itself is still shipped. Replaying the same call with
     * {@code MODEL} puts the group back without patching dex.
     */
    private void injectModelRow(XposedInterface.Chain chain, Method method,
                                Class<?> page, String rendered) {
        // KILL SWITCH: replaying the settings renderer corrupts the surrounding
        // composition (tapping 声音 crashed Aicy), so the row injection stays
        // off until a slot-safe insertion exists. Only the diagnostics run.
        if (!INJECT_MODEL_ROW) return;
        if (!RemoteConfig.master()) return;
        // 13.0.56 renders one group per call (MAIN / VOICE / DEVICES /
        // PERMISSIONS). Replaying the call with MODEL right after 声音 puts the
        // 模型设置 group back in the list.
        if (!"VOICE".equals(rendered)) return;
        if (!sInjecting.compareAndSet(false, true)) return;
        try {
            Object model = null;
            for (Object constant : page.getEnumConstants()) {
                // MODEL == 模型设置, the BYOK page (apiUrl / apiKey).
                // MODEL_MANAGEMENT is 端侧模型管理 — a different feature, not
                // wanted here, so it is deliberately not injected.
                if ("MODEL".equals(((Enum<?>) constant).name())) model = constant;
            }
            if (model == null) return;
            List<Object> args = chain.getArgs();
            Class<?>[] types = method.getParameterTypes();
            // The renderer's tail is (Composer, int): the composer sits right
            // before the changed-bits int.
            Object composer = null;
            if (types.length >= 2 && args.size() == types.length
                    && types[types.length - 2].isInstance(args.get(args.size() - 2))) {
                composer = args.get(args.size() - 2);
            }
            if (composer == null) {
                logOnce("row injection skipped: no Composer among params");
                return;
            }
            Method[] ops = movableGroupOps(composer.getClass());
            if (ops == null) {
                // Without a movable group the replay reuses the host call site's
                // slots and silently replaces that section, so skip instead.
                logOnce("row injection skipped: no movable-group API ("
                        + composer.getClass().getName() + ")");
                return;
            }
            int key = 0x4D4F4445; // 'MODE' — unique so the group can't collide
            logOnce("movable group ops = " + ops[0].getName() + "(" + ops[0].getDeclaringClass().getSimpleName() + ") / " + ops[1].getName() + "(" + ops[1].getDeclaringClass().getSimpleName() + ")");
            Object[] copy = args.toArray();
            copy[0] = model;
            copy[copy.length - 1] = 0;
            try {
                ops[0].invoke(composer, key, null);
                method.invoke(null, copy);
                ops[1].invoke(composer);
                logOnce("settings + " + ((Enum<?>) model).name()
                        + " (" + titleOf(model) + ") injected");
            } catch (Throwable error) {
                log.accept("AicyUnlock: row injection failed for "
                        + ((Enum<?>) model).name(), error);
            }
        } finally {
            sInjecting.set(false);
        }
    }

    /**
     * {@code [startMovableGroup(int,Object), endMovableGroup()]} for a Composer.
     *
     * <p>R8 renames Compose's methods along with everything else, so both are
     * located by signature: the starter is the only {@code (int, Object) -> void}
     * method, and the matching closer is the next {@code () -> void} entry in
     * the same class (they are declared adjacently).
     */
    private static Method[] movableGroupOps(Class<?> type) {
        try {
            Method[] all = type.getMethods();
            for (int i = 0; i < all.length; i++) {
                Method start = all[i];
                if (start.getReturnType() != void.class) continue;
                Class<?>[] p = start.getParameterTypes();
                if (p.length != 2 || p[0] != int.class || p[1] != Object.class) continue;
                // The matching closer lives on the same type as the starter.
                for (int j = i + 1; j < all.length; j++) {
                    Method end = all[j];
                    if (end.getDeclaringClass() != start.getDeclaringClass()) continue;
                    if (end.getReturnType() == void.class && end.getParameterCount() == 0) {
                        return new Method[]{start, end};
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One-shot guard for {@link #AUTO_OPEN_MODEL}. */
    private static final java.util.concurrent.atomic.AtomicBoolean sAutoOpened =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final boolean AUTO_OPEN_MODEL = false;
    /** Master switch for the settings-row injection; see injectModelRow. */
    private static final boolean INJECT_MODEL_ROW = false;
    /**
     * Which settings group is repurposed as 模型设置. Replacing an argument at
     * an existing call site is safe; adding a new row is not (see
     * {@link #injectModelRow}).
     */
    private static final String SACRIFICE_PAGE = "DEVICES";
    /** The settings row that is relabelled into the BYOK entry. */
    private static final String POLICY_ROW_FROM = "个人信息收集清单";
    private static final String POLICY_ROW_TO = "自定义模型";

    /**
     * Finds the {@code MutableState<SettingsContentPage>} that holds the
     * currently shown settings page. It is one of the renderer's parameters, so
     * capturing it here gives the module a reliable way to navigate to
     * 模型设置 later (e.g. from a shortcut), no matter how the menu is built.
     */
    private void capturePageState(XposedInterface.Chain chain, Class<?> page) {
        for (Object arg : chain.getArgs()) {
            Object value = stateValueOf(arg);
            if (value == null || !page.isInstance(value)) continue;
            if (sPageState != arg) {
                sPageState = arg;
                log.accept("AicyUnlock: page state captured on "
                        + arg.getClass().getName() + " value="
                        + ((Enum<?>) value).name(), null);
            }
            return;
        }
    }

    /** Reads a Compose {@code MutableState}/{@code State} value, or null. */
    private static Object stateValueOf(Object holder) {
        if (holder == null) return null;
        try {
            Method get = holder.getClass().getMethod("getValue");
            return get.invoke(holder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Sets the captured page state; returns true when it worked. */
    static boolean showModelSettings() {
        Object state = sPageState;
        if (state == null) return false;
        try {
            Class<?> page = Class.forName(SETTINGS_CONTENT_PAGE, false,
                    state.getClass().getClassLoader());
            Object model = null;
            for (Object constant : page.getEnumConstants()) {
                if ("MODEL".equals(((Enum<?>) constant).name())) model = constant;
            }
            if (model == null) return false;
            Method set = null;
            for (Method m : state.getClass().getMethods()) {
                if ("setValue".equals(m.getName()) && m.getParameterCount() == 1) {
                    set = m;
                    break;
                }
            }
            if (set == null) return false;
            set.invoke(state, model);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns the enum constant with this name, or null. */
    private static Object enumConstant(Class<?> page, String name) {
        if (!page.isEnum()) return null;
        for (Object constant : page.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    /**
     * Renames the 个人信息收集清单 row to 自定义模型.
     *
     * <p>The row is drawn by a policy-row composable whose click target is
     * derived from its {@code PolicyType}, so this only relabels it — the tap
     * still opens the privacy policy page until the module owns the BYOK
     * config itself. Swapping the title is a pure argument edit at an existing
     * call site, so it cannot disturb the composition.
     */
    private void installPolicyRowRename(Installer installer) {
        String[] candidates = {
                "com.flyme.agentos.app.phone.ui.callagent.setting.O",
        };
        for (String name : candidates) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (final Method method : declaredMethods(type)) {
                Class<?>[] p = method.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != void.class) continue;
                if (p.length != 5 || p[0] != String.class || !p[1].isEnum()) continue;
                try {
                    installer.hook(method, chain -> {
                        try {
                            Object title = chain.getArg(0);
                            if (POLICY_ROW_FROM.equals(title)) {
                                Object[] args = chain.getArgs().toArray();
                                args[0] = POLICY_ROW_TO;
                                logOnce("settings row renamed: "
                                        + POLICY_ROW_FROM + " -> " + POLICY_ROW_TO);
                                return chain.proceed(args);
                            }
                        } catch (Throwable ignored) {
                            // fall through to the original call
                        }
                        return chain.proceed();
                    });
                    log.accept("AicyUnlock: policy row rename armed on "
                            + name + "#" + method.getName(), null);
                } catch (Throwable error) {
                    log.accept("AicyUnlock: policy row rename skipped", error);
                }
            }
        }
    }

    /** @return true when at least one page was added. */
    private boolean addMissingPages(List<Object> into, Class<?> page) {
        if (!page.isEnum()) return false;
        boolean grew = false;
        for (Object constant : page.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (!"MODEL".equals(name) && !"MODEL_MANAGEMENT".equals(name)) continue;
            if (into.contains(constant)) continue;
            into.add(constant);
            grew = true;
            logOnce("settings menu + " + name + " (" + titleOf(constant) + ")");
        }
        return grew;
    }

    /** Nearest project frame outside this module, for "who called this" tracing. */
    private static String caller() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cls = e.getClassName();
                if (cls.startsWith("dev.loong.aicyunlock")) continue;
                if (cls.startsWith("java.") || cls.startsWith("kotlin.")
                        || cls.startsWith("dalvik.") || cls.startsWith("android.os.")
                        || cls.startsWith("io.github.libxposed")) continue;
                return cls + "#" + e.getMethodName() + ":" + e.getLineNumber();
            }
        } catch (Throwable ignored) {
            // tracing is best-effort
        }
        return "?";
    }

    private static String titleOf(Object constant) {        try {
            Method getTitle = constant.getClass().getMethod("getTitle");
            Object title = getTitle.invoke(constant);
            return title == null ? "?" : title.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String enumNames(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (Object constant : type.getEnumConstants()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(((Enum<?>) constant).name()).append('=').append(titleOf(constant));
        }
        return sb.toString();
    }

    private void setFlag(boolean value) {
        if (!RemoteConfig.master()) return;
        try {
            Class<?> type = Class.forName(FLAG_CLASS, false, loader);
            java.lang.reflect.Field field = type.getDeclaredField(FLAG_FIELD);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable error) {
            if (sFlagErrorLogged) return;
            sFlagErrorLogged = true;
            log.accept("AicyUnlock: exact flag " + FLAG_CLASS + "." + FLAG_FIELD
                    + " unavailable, relying on structural discovery", error);
        }
    }

    // ------------------------------------------------------------------
    // Structure-based flag restore (independent of obfuscated names)
    // ------------------------------------------------------------------

    private static final Map<java.lang.reflect.Field, Boolean> sFlags = new LinkedHashMap<>();
    /** Dedupe for the "which list is the menu" diagnostics. */
    private static final Set<String> sSeenLists = new LinkedHashSet<>();
    /** Settings pages actually rendered, for the row-injection trace. */
    private static final Set<String> sRenderedPages = new LinkedHashSet<>();
    /** Guards the renderer replay against re-entrancy. */
    private static final java.util.concurrent.atomic.AtomicBoolean sInjecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int installedRowInjector;
    /** The composable state that selects the visible settings page. */
    private static volatile Object sPageState;

    private static boolean sFlagScanDone;
    private static boolean sFlagErrorLogged;
    private static final java.util.concurrent.atomic.AtomicBoolean sFirstResume =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void snapshotFeatureFlags() {
        if (sFlagScanDone) return;
        sFlagScanDone = true;
        List<String> names = classesInPackage(FLAG_PACKAGE);
        int classes = 0;
        int flags = 0;
        for (String name : names) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            // Obfuscated leaf names only ("J3", "a", "n0"): keeps the blast
            // radius to AgentOS's own flag holders, not MLKit's zz* classes.
            if (!SHORT_NAME.matcher(simple).matches()) continue;
            try {
                Class<?> type = Class.forName(name, false, loader);
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.getType() != boolean.class) continue;
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        sFlags.put(field, field.getBoolean(null));
                        flags++;
                    } catch (Throwable ignored) {
                        // inaccessible, skip
                    }
                }
                classes++;
            } catch (Throwable ignored) {
                // not loadable on its own, skip
            }
        }
        log.accept("AicyUnlock: flag scan " + FLAG_PACKAGE + " -> " + classes
                + " classes, " + flags + " static booleans (expect the gate among them)", null);
    }

    /** Restores every flag this module saw true at snapshot time. */
    private void enforceFeatureFlags() {
        if (sFlags.isEmpty()) return;
        if (!RemoteConfig.master()) return;
        for (Map.Entry<java.lang.reflect.Field, Boolean> entry : sFlags.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            java.lang.reflect.Field field = entry.getKey();
            try {
                if (field.getBoolean(null)) continue;
                field.setBoolean(null, true);
                log.accept("AicyUnlock: restored flag "
                        + field.getDeclaringClass().getName() + "." + field.getName(), null);
            } catch (Throwable ignored) {
                // gone or inaccessible
            }
        }
    }

    /**
     * Enumerates class names from the app's dex files. {@link DexIndex} tries the
     * runtime dex list first and falls back to reading the APKs, so this keeps
     * working after the platform deviates from the hidden field names.
     */
    private List<String> classesInPackage(String pkg) {
        return DexIndex.classesIn(loader, pkg, log);
    }

    private static Object fieldOrNull(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 2. Tier diagnostics: which model tier does each entry ask for?
    // ------------------------------------------------------------------

    private void installTierDiagnostics(Installer installer) {
        XposedInterface.Hooker logger = chain -> {
            try {
                Object arg = chain.getArg(0);
                if (arg != null) {
                    log.accept("AicyUnlock: model tier requested = " + arg, null);
                }
            } catch (Throwable ignored) {
                // diagnostics only
            }
            return chain.proceed();
        };
        try {
            installer.hook(QUICK_VOICE_CLASS, "selectModelTier", logger, String.class);
            log.accept("AicyUnlock: quick-voice tier hook armed", null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: quick-voice tier hook skipped", error);
        }
        try {
            installer.hook(CHAT_VM_CLASS, "selectModelTier", logger, String.class);
        } catch (Throwable error) {
            log.accept("AicyUnlock: chat tier hook skipped", error);
        }
    }

    // ------------------------------------------------------------------
    // 3. Model catalog: default 轻量 + an extra 自定义 tier
    // ------------------------------------------------------------------

    private void installCatalogPatches(Installer installer) {
        // (a) builtin catalog: assets/model_catalog_builtin.json
        XposedInterface.Hooker assetHook = chain -> {
            Object stream = chain.proceed();
            return patchAsset(chain, stream);
        };
        try {
            installer.hook("android.content.res.AssetManager", "open", assetHook, String.class);
        } catch (Throwable error) {
            log.accept("AicyUnlock: asset catalog hook skipped", error);
        }
        try {
            installer.hook("android.content.res.AssetManager", "open", assetHook,
                    String.class, int.class);
        } catch (Throwable error) {
            log.accept("AicyUnlock: asset catalog hook (access mode) skipped", error);
        }

        // (b) Any JSON parse that receives the catalog document. This is the
        //     catch-all: asset, prefs cache and network all end up here, and
        //     Gson's method names survive R8 (unlike the app's own classes).
        installGsonPatches(installer);
        installTierTracking(installer);

        // (b2) Ktor + Gson feeds the network body through a Reader.
        //      Covered inside installGsonPatches (fromJson(Reader, …)).

        // (b3) The prefs file that caches the remote catalog, patched as soon
        //      as the app opens it (catalog 2.x from model.do).
        try {
            installer.hook("android.app.ContextImpl", "getSharedPreferences", chain -> {
                Object result = chain.proceed();
                if (result instanceof android.content.SharedPreferences) {
                    rewriteCatalogPrefs((android.content.SharedPreferences) result);
                }
                return result;
            }, String.class, int.class);
            log.accept("AicyUnlock: prefs catalog rewrite armed (ContextImpl)", null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: prefs catalog rewrite skipped", error);
        }

        // (c) cached catalog, if it is read as a plain preference string.
        try {
            installer.hook("android.app.SharedPreferencesImpl", "getString", chain -> {                Object result = chain.proceed();
                if (!(result instanceof String)) return result;
                String value = (String) result;
                String key = null;
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String) key = (String) arg;
                } catch (Throwable ignored) {
                    // getArg unavailable on this framework, key stays null
                }
                String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
                if (!RemoteConfig.master()) return result;
                noteTier(key, value);

                // "仅自定义档位走自建 API": hide the BYOK endpoint from the app
                // while any other tier is selected, so those requests fall back
                // to the stock Meizu cloud models.
                if (key != null && isByokKey(key)) {
                    if (RemoteConfig.onlyCustomTier() && !isCustomTier()) {
                        logOnce("byok hidden while tier=" + sCurrentTier);
                        return "";
                    }
                    if (value.isEmpty()) {
                        String saved = recallByok(key);
                        if (saved != null) return saved;
                    }
                }

                if (lower.contains("tier")
                        && ("chat-auto".equals(value) || "auto".equals(value))) {
                    // A persisted "last-used tier" of chat-auto should fall back
                    // to the user's chosen default, not blindly to 轻量.
                    logOnce("default tier pref " + key + " -> " + effectiveDefaultTier());
                    return effectiveDefaultTier();
                }
                if (CatalogPatch.looksLikeCatalog(value)) {
                    logOnce("catalog patched via pref " + key);
                    return patchCatalog(value);
                }
                if (lower.contains("tier") || lower.contains("session")) {
                    return CatalogPatch.forceFreeTierId(value, effectiveDefaultTier());
                }
                return result;
            }, String.class, String.class);
        } catch (Throwable error) {
            log.accept("AicyUnlock: prefs catalog hook skipped", error);
        }

        log.accept("AicyUnlock: catalog rewrite armed (default=轻量, extra tier=自定义)", null);
    }

    /**
     * Replaces the cached catalog document the moment its preferences file is
     * opened. This is the path that actually matters: the app pulls catalog
     * 2.x from the network on every launch and caches the raw JSON, so patching
     * only the builtin asset is not enough, and it does not depend on knowing
     * which JSON parser later reads the string.
     */
    private void rewriteCatalogPrefs(android.content.SharedPreferences sp) {
        if (!RemoteConfig.master() || !RemoteConfig.catalogRewrite()) return;
        try {
            Map<String, ?> all = sp.getAll();
            if (all == null || all.isEmpty()) return;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String)) continue;
                String text = (String) value;
                if (!CatalogPatch.looksLikeCatalog(text)) continue;
                String patched = patchCatalog(text);
                if (patched == null || patched.equals(text)) continue;
                sp.edit().putString(entry.getKey(), patched).apply();
                logOnce("catalog rewritten in prefs key " + entry.getKey());
            }
        } catch (Throwable ignored) {
            // best effort; the gson/asset hooks may still catch it
        }
    }

    /** Tracks the tier id the app persists / reads. */
    private static void noteTier(String key, String value) {
        if (key == null || value == null) return;
        if (!key.toLowerCase(Locale.ROOT).contains("tier")) return;
        if (value.startsWith("chat-")) {
            sCurrentTier = value;
            sTierPicked = true;
        }
    }

    /**
     * Records tier writes (the sheet's selection goes through the prefs
     * editor), so "only the 自定义 tier uses my API" can react to it.
     */
    private void installTierTracking(Installer installer) {
        try {
            installer.hook("android.app.SharedPreferencesImpl$EditorImpl", "putString",
                    chain -> {
                        Object key = chain.getArg(0);
                        Object value = chain.getArg(1);
                        if (key instanceof String && value instanceof String) {
                            noteTier((String) key, (String) value);
                        }
                        return chain.proceed();
                    }, String.class, String.class);
            log.accept("AicyUnlock: tier tracking armed (editor)", null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: tier tracking skipped (editor)", error);
        }
    }

    /** True when the sheet's current selection is our injected 自定义 tier. */
    private static boolean isCustomTier() {
        if (CatalogPatch.CUSTOM_TIER_ID.equals(sCurrentTier)) return true;
        // 冷启动早期 Aicy 还没走到选档位那一步，sCurrentTier 只是占位值。
        // 这时按模块里配置的默认档位判断，否则会把 apiUrl 清空、Aicy 缓存成
        // 「没配自定义」，之后一直走云端（而云端又被我们拦掉）。
        if (!sTierPicked) {
            return CatalogPatch.CUSTOM_TIER_ID.equals(RemoteConfig.defaultTier());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 3b. Tier injection: make "自定义" a real entry in the sheet
    // ------------------------------------------------------------------

    private static final String CATALOG_PACKAGE = "com.flyme.agentos.llm.api.model.catalog";
    private static final Object NO_SHAPE = new Object();
    private static final Map<Class<?>, Object> sShapes =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Classes already known not to be a tier (hot-path bail-out). */
    private static final java.util.Set<Class<?>> sNotTier =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * The "选择模型" sheet renders whatever {@code catalog.<wrapper>.a(String,
     * ModelScenario)} returns — a {@code List} of {@code catalog.P} tiers that
     * the app builds from its remote catalog. That is why rewriting the
     * catalog JSON never changed anything: the sheet is not fed from the JSON
     * we can see (2.1 arrives from the network and is cached outside
     * SharedPreferences).
     *
     * <p>So append a tier to that list instead. The appended object is a clone
     * of the free tier with only id / name / description swapped, so it has
     * exactly the field set the running build expects — no obfuscated names,
     * no version constants.
     */
    private void installTierInjection(Installer installer) {
        List<String> names = classesInPackage(CATALOG_PACKAGE);
        int hooked = 0;
        for (String name : names) {
            if (name.indexOf('$') >= 0) continue;
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : declaredMethods(type)) {
                if (method.getReturnType() != List.class) continue;
                if (!enumishParams(method.getParameterTypes())) continue;
                try {
                    installer.hook(method, this::injectTierList);
                    hooked++;
                    log.accept("AicyUnlock: tier list source hooked "
                            + name + "#" + method.getName(), null);
                } catch (Throwable error) {
                    log.accept("AicyUnlock: tier list hook skipped " + name
                            + "#" + method.getName(), error);
                }
            }
        }
        log.accept("AicyUnlock: tier injection -> " + hooked + " source(s) in "
                + CATALOG_PACKAGE, null);
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return new Method[0];
        }
    }

    /**
     * Signatures that can hand back a tier list:
     * {@code (ModelScenario) -> List} (the real one — it filters and re-builds
     * the tiers) and {@code (String, ModelScenario) -> List} (per-tier model
     * list; harmless, the content guard rejects it).
     */
    private static boolean enumishParams(Class<?>[] params) {
        if (params.length == 1) return params[0].isEnum();
        if (params.length == 2) return params[0] == String.class && params[1].isEnum();
        return false;
    }

    /**
     * Appends the 自定义 tier. Fails safe: if the list does not look like a
     * tier list (first String field is not a {@code chat-*} id) the original
     * value is returned untouched.
     */
    private Object injectTierList(XposedInterface.Chain chain) throws Throwable {
        Object result;
        List<Object> args = null;
        try {
            // libxposed returns a List here, not an array.
            args = chain.getArgs();
        } catch (Throwable ignored) {
            // getArgs unavailable on this framework, leave args null
        }
        // Belt-and-suspenders: when the catalog JSON is handed to the parser
        // as a String argument (catalog.p.a(String, ModelScenario)), rewrite it
        // before Aicy parses it. This is the same JSON the datastore-read hook
        // already patches, so it is idempotent — but it also covers any path
        // that bypasses the DataStore read (e.g. the builtin asset).
        if (args != null && RemoteConfig.master() && RemoteConfig.catalogRewrite()) {
            // Only the first catalog-shaped String argument is rewritten; the rest
            // of the signature is unrelated (an enum, a scenario key, a mask).
            for (int i = 0; i < args.size(); i++) {
                Object value = args.get(i);
                if (!(value instanceof String)) continue;
                String json = (String) value;
                if (!CatalogPatch.looksLikeCatalog(json)) continue;
                String patched = patchCatalog(json);
                if (patched != null && !patched.equals(json)) {
                    args.set(i, patched);
                    logOnce("catalog patched via catalog arg");
                }
                break;
            }
        }
        // proceed() forwards the (possibly mutated) argument list; the List handed
        // out by getArgs() is the chain's own copy, so mutating it is safe.
        result = chain.proceed(args.toArray());
        if (!(result instanceof List)) return result;
        List<?> list = (List<?>) result;
        if (list.isEmpty()) return result;
        if (!RemoteConfig.master() || !RemoteConfig.catalogRewrite()) return result;
        if (!byokActive()) {
            // No custom endpoint configured -> the 自定义 tier would be useless,
            // so it is not offered at all.
            logOnce("tier skipped: no custom endpoint");
            return result;
        }
        logOnce("list hook: n=" + list.size() + " first=" + firstStringField(list.get(0)));
        try {
            Object base = null;
            for (Object item : list) {
                if (item == null) return result;
                Class<?> kind = item.getClass();
                if (sNotTier.contains(kind)) return result;
                String id = tierIdOf(item);
                if (id == null) {
                    sNotTier.add(kind);
                    return result;
                }
                if (CatalogPatch.CUSTOM_TIER_ID.equals(id)) return result;
                if (CatalogPatch.FREE_TIER_ID.equals(id)) base = item;
            }
            if (base == null) base = list.get(list.size() - 1);
            Shape shape = shapeOf(base.getClass());
            if (shape == null) return result;
            Object added = shape.cloneOf(base);
            if (added == null) return result;
            List<Object> out = new ArrayList<>(list.size() + 1);
            out.addAll(list);
            out.add(added);
            logOnce("tier injected 自定义 (" + list.size() + " -> " + out.size() + ")");
            return out;
        } catch (Throwable error) {
            log.accept("AicyUnlock: tier injection failed", error);
            return result;
        }
    }

    /** The tier's id: its first String field, which must look like chat-*. */
    private static String tierIdOf(Object tier) {
        String text = firstStringField(tier);
        return text != null && text.startsWith("chat-") ? text : null;
    }

    /** First String field of an object (diagnostics + tier id). */
    private static String firstStringField(Object target) {
        if (target == null) return "<null>";
        for (java.lang.reflect.Field field : target.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                return value instanceof String ? (String) value : "<null>";
            } catch (Throwable ignored) {
                return "<inaccessible>";
            }
        }
        return target.getClass().getName();
    }

    /** Declared-field order + constructor of a tier class, cached per class. */
    private static final class Shape {
        private final java.lang.reflect.Field[] fields;
        private final java.lang.reflect.Constructor<?> ctor;
        private final int idIndex;
        private final int nameIndex;
        private final int descIndex;
        private final int sortIndex;

        Shape(java.lang.reflect.Field[] fields, java.lang.reflect.Constructor<?> ctor,
              int idIndex, int nameIndex, int descIndex, int sortIndex) {
            this.fields = fields;
            this.ctor = ctor;
            this.idIndex = idIndex;
            this.nameIndex = nameIndex;
            this.descIndex = descIndex;
            this.sortIndex = sortIndex;
        }

        Object cloneOf(Object base) {
            try {
                Object[] args = new Object[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    args[i] = fields[i].get(base);
                }
                args[idIndex] = CatalogPatch.CUSTOM_TIER_ID;
                args[nameIndex] = CatalogPatch.CUSTOM_NAME;
                args[descIndex] = "使用你自己配置的接口";
                if (sortIndex >= 0) args[sortIndex] = 9;
                return ctor.newInstance(args);
            } catch (Throwable error) {
                return null;
            }
        }
    }

    private static Shape shapeOf(Class<?> type) {
        Object cached = sShapes.get(type);
        if (cached != null) return cached == NO_SHAPE ? null : (Shape) cached;
        Shape shape = buildShape(type);
        sShapes.put(type, shape == null ? NO_SHAPE : shape);
        return shape;
    }

    private static Shape buildShape(Class<?> type) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (field.isSynthetic()) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
            fields.add(field);
        }
        if (fields.isEmpty()) return null;

        int idIndex = -1;
        int nameIndex = -1;
        int descIndex = -1;
        int sortIndex = -1;
        Class<?>[] types = new Class<?>[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Class<?> kind = fields.get(i).getType();
            types[i] = kind;
            if (kind == String.class) {
                if (idIndex < 0) idIndex = i;
                else if (nameIndex < 0) nameIndex = i;
                else if (descIndex < 0) descIndex = i;
            } else if (kind == int.class) {
                if (sortIndex < 0) sortIndex = i;
            }
        }
        // A tier needs id / name / description to be presentable.
        if (idIndex < 0 || nameIndex < 0 || descIndex < 0) return null;

        for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
            if (!java.util.Arrays.equals(ctor.getParameterTypes(), types)) continue;
            try {
                ctor.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
            return new Shape(fields.toArray(new java.lang.reflect.Field[0]), ctor,
                    idIndex, nameIndex, descIndex, sortIndex);
        }
        return null;
    }

    private void installGsonPatches(Installer installer) {
        XposedInterface.Hooker stringHooker = chain -> {
            Object arg = null;
            try {
                arg = chain.getArg(0);
            } catch (Throwable ignored) {
                return chain.proceed();
            }
            if (!(arg instanceof String)) return chain.proceed();
            String json = (String) arg;
            String patched = patchCatalog(json);
            if (patched == null || patched.equals(json)) return chain.proceed();
            Object second = null;
            try {
                second = chain.getArg(1);
            } catch (Throwable ignored) {
                // keep null
            }
            logOnce("catalog patched via gson string");
            return chain.proceed(new Object[]{patched, second});
        };

        // Ktor's Gson content converter reads the body through a Reader, so the
        // String overload is never reached on the network path.
        XposedInterface.Hooker readerHooker = chain -> {
            Object arg = null;
            Object second = null;
            try {
                arg = chain.getArg(0);
                second = chain.getArg(1);
            } catch (Throwable ignored) {
                return chain.proceed();
            }
            if (!(arg instanceof java.io.Reader)) return chain.proceed();
            String text = readReader((java.io.Reader) arg);
            if (text == null) return chain.proceed();
            String patched = patchCatalog(text);
            if (patched == null || patched.equals(text)) {
                return chain.proceed(new Object[]{new java.io.StringReader(text), second});
            }
            logOnce("catalog patched via gson reader");
            return chain.proceed(new Object[]{new java.io.StringReader(patched), second});
        };

        int armed = 0;
        armed += hookQuietly(installer, "com.google.gson.Gson", "fromJson", stringHooker,
                String.class, Class.class);
        armed += hookQuietly(installer, "com.google.gson.Gson", "fromJson", stringHooker,
                String.class, java.lang.reflect.Type.class);
        try {
            armed += hookQuietly(installer, "com.google.gson.Gson", "fromJson", stringHooker,
                    String.class, Class.forName("com.google.gson.reflect.TypeToken", false, loader));
        } catch (Throwable ignored) {
            // TypeToken absent, fine
        }
        armed += hookQuietly(installer, "com.google.gson.Gson", "fromJson", readerHooker,
                java.io.Reader.class, Class.class);
        armed += hookQuietly(installer, "com.google.gson.Gson", "fromJson", readerHooker,
                java.io.Reader.class, java.lang.reflect.Type.class);
        log.accept("AicyUnlock: catalog gson hooks armed = " + armed, null);

        // Same treatment for the org.json path, in case a build parses the
        // catalog there instead of through Gson.
        try {
            installer.hook("org.json.JSONObject", "<init>", chain -> {
                Object arg = null;
                try {
                    arg = chain.getArg(0);
                } catch (Throwable ignored) {
                    return chain.proceed();
                }
                if (!(arg instanceof String)) return chain.proceed();
                String patched = patchCatalog((String) arg);
                if (patched == null || patched.equals(arg)) return chain.proceed();
                logOnce("catalog patched via org.json");
                return chain.proceed(new Object[]{patched});
            }, String.class);
            log.accept("AicyUnlock: catalog org.json hook armed", null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: catalog org.json hook skipped", error);
        }

        try {
            installer.hook("com.google.gson.JsonParser", "parseString", chain -> {
                Object arg = null;
                try {
                    arg = chain.getArg(0);
                } catch (Throwable ignored) {
                    return chain.proceed();
                }
                if (!(arg instanceof String)) return chain.proceed();
                String patched = patchCatalog((String) arg);
                if (patched == null || patched.equals(arg)) return chain.proceed();
                logOnce("catalog patched via JsonParser");
                return chain.proceedWith(patched);
            }, String.class);
            log.accept("AicyUnlock: catalog JsonParser hook armed", null);
        } catch (Throwable error) {
            log.accept("AicyUnlock: catalog JsonParser hook skipped", error);
        }
    }

    private int hookQuietly(Installer installer, String className, String methodName,
                           XposedInterface.Hooker hooker, Class<?>... parameterTypes) {
        try {
            installer.hook(className, methodName, hooker, parameterTypes);
            return 1;
        } catch (Throwable error) {
            return 0;
        }
    }

    private static String readReader(java.io.Reader reader) {
        try {
            StringBuilder sb = new StringBuilder(1024);
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) sb.append(buffer, 0, read);
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object patchAsset(XposedInterface.Chain chain, Object stream) {
        if (!(stream instanceof InputStream)) return stream;
        Object name = null;
        try {
            name = chain.getArg(0);
        } catch (Throwable ignored) {
            return stream;
        }
        if (!(name instanceof String) || !((String) name).contains("model_catalog")) {
            return stream;
        }
        byte[] data = readAll((InputStream) stream);
        if (data == null) return stream;
        String text = new String(data, StandardCharsets.UTF_8);
        String patched = patchCatalog(text);
        if (patched == null || patched.equals(text)) {
            return new java.io.ByteArrayInputStream(data);
        }
        logOnce("catalog patched via asset " + name);
        return new java.io.ByteArrayInputStream(patched.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the live BYOK config first: the user may have just typed a model
     * name, and the injected tier has to carry exactly that model id.
     */
    /** Applies the user's default-tier choice regardless of BYOK; only the
     * custom tier is gated on BYOK being configured. */
    private String patchCatalog(String json) {
        if (!RemoteConfig.master() || !RemoteConfig.catalogRewrite()) return json;
        sCheckedAt = 0L;
        String defaultTier = effectiveDefaultTier();
        String modelId = null;
        String host = null;
        if (byokActive()) {
            modelId = sModelName;
            host = sApiHost;
        }
        logOnce("default tier -> " + defaultTier + " byok=" + byokActive()
                + " customTierInjected=" + (modelId != null));
        return CatalogPatch.patch(json, modelId, host, defaultTier);
    }

    /** The user's preferred default tier, clamped so 自定义 only wins when BYOK
     * is actually configured (otherwise the custom tier is absent). */
    private static String effectiveDefaultTier() {
        String def = RemoteConfig.defaultTier();
        if (def == null) return null;
        if (CatalogPatch.CUSTOM_TIER_ID.equals(def) && !byokActive()) {
            return CatalogPatch.FREE_TIER_ID;
        }
        return def;
    }

    private void logOnce(String message) {
        if (!sCatalogNotes.add(message)) return;
        log.accept("AicyUnlock: " + message, null);
    }

    // ------------------------------------------------------------------
    // Diagnostics forwarded to our own app (no manager log needed)
    // ------------------------------------------------------------------

    static final String DIAG_AUTHORITY = "dev.loong.aicyunlock.cfg";
    private static final java.util.Set<String> sReported =
            java.util.Collections.newSetFromMap(new java.util.HashMap<>());

    /** Mirrors one log line to the module app's diagnostics page. */
    static void report(String message) {
        if (message == null) return;
        if (sReported.size() >= 40 || !sReported.add(message)) return;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (!(app instanceof android.content.Context)) return;
            // Deliver via the provider's call() method, not a broadcast: the
            // old receiver was exported and any app could inject fake lines.
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("line", message);
            ((android.content.Context) app).getContentResolver().call(
                    android.net.Uri.parse("content://" + DIAG_AUTHORITY),
                    "diag", null, extras);
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static final java.util.Set<String> sCatalogNotes =
            java.util.Collections.newSetFromMap(new java.util.HashMap<>());

    private static byte[] readAll(InputStream in) {
        try (InputStream source = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 4. Conditional privacy firewall
    // ------------------------------------------------------------------

    private void installEgressFirewall(Installer installer) {
        if (!ENFORCE_EGRESS) {
            log.accept("AicyUnlock: egress firewall disabled by build flag", null);
            return;
        }

        // Engine-agnostic layer: every HTTP stack (OkHttp, HttpURLConnection,
        // Ktor/CIO, raw sockets) resolves the host through InetAddress first.
        // The app's OkHttp classes are R8-renamed, so name-based hooks on them
        // never bind — DNS does.
        for (String method : new String[]{"getAllByName", "getByName"}) {
            try {
                installer.hook("java.net.InetAddress", method, chain -> {
                    Object host = chain.getArg(0);
                    if (host instanceof String && blocked((String) host, "dns")) {
                        throw new java.net.UnknownHostException(
                                "AicyUnlock blocked " + host);
                    }
                    return chain.proceed();
                }, String.class);
                log.accept("AicyUnlock: dns block armed via InetAddress#" + method, null);
            } catch (Throwable error) {
                log.accept("AicyUnlock: dns block skipped for InetAddress#" + method, error);
            }
        }

        // HttpURLConnection path (kept: also lets us log the blocked URL).
        installer.hook("java.net.URL", "openConnection", chain -> {
            Object self = chain.getThisObject();
            if (self instanceof java.net.URL) {
                String host = ((java.net.URL) self).getHost();
                if (blocked(host, "URL.openConnection")) {
                    throw new IOException("AicyUnlock blocked egress to " + host);
                }
            }
            return chain.proceed();
        });

        // OkHttp path: the app's okhttp3 classes are renamed, so try the names
        // that usually survive; a miss is logged and DNS still covers it.
        try {
            Class<?> requestClass = Class.forName("okhttp3.Request", false, loader);
            Class<?> callClass = Class.forName("okhttp3.Call", false, loader);
            installer.hook("okhttp3.OkHttpClient", "newCall", chain -> {
                Object request = chain.getArg(0);
                String host = hostOf(request);
                if (blocked(host, "OkHttpClient.newCall")) {
                    return blockedCall(callClass, request);
                }
                return chain.proceed();
            }, requestClass);
            log.accept("AicyUnlock: OkHttp egress firewall armed", null);
        } catch (Throwable notOkHttp) {
            log.accept("AicyUnlock: okhttp3 classes are renamed, its hook skipped "
                    + "(DNS layer still enforces)", null);
        }
    }

    /**
     * Decides whether a host must be cut.
     *
     * <ul>
     *   <li>master switch off → nothing is blocked;</li>
     *   <li>log/telemetry host + log switch on → always blocked;</li>
     *   <li>chat host + chat switch on + BYOK active → blocked;</li>
     *   <li>anything not Meizu/Flyme owned → never touched.</li>
     * </ul>
     */
    private boolean blocked(String host, String where) {
        if (host == null) return false;
        if (!RemoteConfig.master()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (!matches(h, VENDOR_SUFFIXES)) return false;

        if (RemoteConfig.blockLogUpload() && matches(h, LOG_SUFFIXES)) {
            logBlocked(h, where + "/log-upload");
            return true;
        }
        if (RemoteConfig.blockChatEgress() && matches(h, BLOCK_SUFFIXES)) {
            // 只有「自定义」档位才切断云端出口。Aicy 的 BYOK 本身是全局开关，
            // 不按档位区分，所以这里必须自己判档位，否则选轻量也会被拦死。
            boolean byok = byokActive() && isCustomTier();
            logMode(byok);
            if (byok) {
                logBlocked(h, where + "/chat");
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private void logMode(boolean byok) {
        Boolean last = sLastLogged;
        if (last != null && last == byok) return;
        sLastLogged = byok;
        log.accept(byok
                ? "AicyUnlock: mode = CUSTOM model, Meizu egress is now blocked"
                : "AicyUnlock: mode = CLOUD model, all egress allowed", null);
    }

    // ------------------------------------------------------------------
    // BYOK backup copy.
    //
    // Blanking model.apiUrl / model.apiKey for a non-custom tier makes Aicy
    // persist the blank value, destroying the user's own settings. So every
    // non-empty value the app reads is copied here first, and the copy is
    // handed back when the 自定义 tier needs the real endpoint.
    // ------------------------------------------------------------------

    private static final Map<String, String> sByokCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean sByokReady;

    private static void rememberByok(String key, String value) {
        if (key == null || value == null || value.isEmpty()) return;
        if (!isCacheableKey(key)) return; // never mirror anything else
        if (!sByokReady) loadByokCache();
        String old = sByokCache.put(key, value);
        if (!value.equals(old)) persistByokCache();
    }

    private static String recallByok(String key) {
        if (!sByokReady) loadByokCache();
        String value = sByokCache.get(key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static void loadByokCache() {
        sByokReady = true;
        File file = byokCacheFile();
        if (file == null || !file.isFile()) return;
        String text = readText(file, 64 * 1024);
        if (text == null) return;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) sByokCache.put(key, value);
        }
    }

    private static void persistByokCache() {
        File file = byokCacheFile();
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sByokCache.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        writeText(file, sb.toString());
    }

    private static File byokCacheFile() {
        File dir = dataDir();
        if (dir == null) return null;
        File files = new File(dir, "files");
        if (!files.isDirectory() && !files.mkdirs()) return null;
        return new File(files, "aicyunlock.byok");
    }

    private static boolean looksVendorUrl(String url) {
        String host = hostOfUrl(url);
        return host != null && matches(host, VENDOR_SUFFIXES);
    }

    /**
     * The value Aicy should see for a {@code model.*} key, taken from the
     * module's own configuration. Returns null when the module has nothing to
     * say about that key, so the caller falls back to Aicy's stored value.
     */
    /**
     * Aicy 自己的模型配置现在由它自己写入 {@code model_config_prefs}
     * （用户通过「临时打开模型设置页」那个开关填的），模块不再覆盖它 ——
     * 之前无条件注入会把 providerPreset 全局改成 custom，导致所有档位都走自建接口。
     */
    private static String moduleByokValue(String key) {
        return null;
    }

    /** The only keys our backup copy is allowed to hold. */
    private static boolean isCacheableKey(String key) {        return "model.apiUrl".equalsIgnoreCase(key)
                || "model.apiKey".equalsIgnoreCase(key)
                || "model.providerPreset".equalsIgnoreCase(key)
                || "model.selected".equalsIgnoreCase(key)
                || "model.name".equalsIgnoreCase(key);
    }

    // ------------------------------------------------------------------
    // BYOK detection: read Aicy's own model.* preferences from its data dir.
    // ------------------------------------------------------------------

    static boolean byokActive() {
        long now = System.currentTimeMillis();
        if (now - sCheckedAt < MODE_TTL_MS) return sByok;
        sCheckedAt = now;
        try {
            File dataDir = dataDir();
            if (dataDir == null) return sByok = false;
            String preset = null;
            String apiUrl = null;
            String modelName = null;
            boolean apiKey = false;

            // SharedPreferences XML (the usual home of model.* keys).
            File prefsDir = new File(dataDir, "shared_prefs");
            File[] xmls = prefsDir.listFiles((dir, name) -> name.endsWith(".xml"));
            if (xmls != null) {
                for (File xml : xmls) {
                    String text = readText(xml, 512 * 1024);
                    if (text == null) continue;
                    Matcher m = PREF_STRING.matcher(text);
                    while (m.find()) {
                        String value = m.group(2) == null ? "" : m.group(2).trim();
                        if (value.isEmpty()) continue;
                        switch (m.group(1)) {
                            case "providerPreset": preset = value; break;
                            case "apiUrl": apiUrl = value; break;
                            case "apiKey": apiKey = true; break;
                            case "name": modelName = value; break;
                            default: break;
                        }
                    }
                    if (preset != null || apiUrl != null) break;
                }
            }

            // DataStore fallback: the BYOK settings really live in
            // files/datastore/*.pb, as protobuf key/value entries.
            File store = new File(dataDir, "files/datastore");
            if (apiUrl == null) apiUrl = findInDataStore(store, "model.apiUrl", true);
            if (preset == null) preset = findInDataStore(store, "model.providerPreset", false);
            String storedKey = findTextInDataStore(store, "model.apiKey");
            String selected = findTextInDataStore(store, "model.selected");

            // Keep our own copy while the file is still intact: a non-custom
            // tier makes Aicy write the blanked value back, wiping the entry.
            if (apiUrl != null) rememberByok("model.apiUrl", apiUrl);
            if (preset != null) rememberByok("model.providerPreset", preset);
            if (storedKey != null) rememberByok("model.apiKey", storedKey);
            if (selected != null) rememberByok("model.selected", selected);

            if (apiUrl == null || preset == null) {
                // Only trust the backup when the app itself still has these keys
                // (blanked by us), not when the user never configured BYOK.
                boolean configured = keyExistsInDataStore(store, "model.apiUrl")
                        || keyExistsInDataStore(store, "model.providerPreset");
                if (configured) {
                    if (apiUrl == null) apiUrl = recallByok("model.apiUrl");
                    if (preset == null) preset = recallByok("model.providerPreset");
                }
            }
            if (!apiKey) apiKey = storedKey != null || recallByok("model.apiKey") != null;
            if (modelName == null) {
                modelName = selected != null ? selected : recallByok("model.selected");
            }

            sModelName = modelName;
            sApiHost = apiUrl == null ? null : hostOfUrl(apiUrl);
            sByok = isCustom(preset, apiUrl, apiKey);
            return sByok;
        } catch (Throwable error) {
            sByok = false;
            return false;
        }
    }

    private static boolean isCustom(String preset, String apiUrl, boolean apiKey) {
        if (preset != null) {
            String p = preset.toLowerCase(Locale.ROOT);
            if (p.contains("custom") || p.contains("自定义") || p.contains("byok")) return true;
        }
        if (apiUrl != null && !apiUrl.isEmpty()) {
            String host = hostOfUrl(apiUrl);
            if (host != null && !matches(host, VENDOR_SUFFIXES)) return true;
        }
        return apiKey && apiUrl != null;
    }

    private static String hostOfUrl(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File dataDir() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getDataDir();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        File guess = new File("/data/data/com.meizu.voiceassistant");
        return guess.isDirectory() ? guess : null;
    }

    /** Scans DataStore proto files for the value stored under {@code key}. */
    private static String findInDataStore(File dir, String key, boolean url) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile() || file.length() > 4L * 1024 * 1024) continue;
            byte[] data = readBytes(file, 4 * 1024 * 1024);
            if (data == null) continue;
            for (String run : runsNearKey(data, key, 128)) {
                if (url) {
                    String found = urlIn(run);
                    if (found != null) return found;
                } else {
                    String lower = run.toLowerCase(Locale.ROOT);
                    if (lower.contains("custom") || lower.contains("byok")
                            || lower.contains("自定义")) {
                        return run;
                    }
                }
            }
        }
        return null;
    }

    /** True when the DataStore file carries this key at all (value may be empty). */
    private static boolean keyExistsInDataStore(File dir, String key) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return false;
        byte[] needle = key.getBytes(StandardCharsets.UTF_8);
        for (File file : files) {
            if (!file.isFile() || file.length() > 4L * 1024 * 1024) continue;
            byte[] data = readBytes(file, 4 * 1024 * 1024);
            if (data != null && indexOf(data, needle, 0) >= 0) return true;
        }
        return false;
    }

    /** First unrelated value stored under {@code key} (apiKey, model name, ...). */
    private static String findTextInDataStore(File dir, String key) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile() || file.length() > 4L * 1024 * 1024) continue;
            byte[] data = readBytes(file, 4 * 1024 * 1024);
            if (data == null) continue;
            for (String run : runsNearKey(data, key, 128)) {
                if (run.length() < 2) continue;      // '*' / '#' length markers
                if (run.startsWith("model.")) break; // empty value, next key starts
                return run;
            }
        }
        return null;
    }

    /**
     * Printable runs right after a protobuf key. A preferences_pb entry is
     * {@code key(1) + value(2)} where value is a nested message, so the raw
     * value never touches the key bytes — only a small window is usable.
     */
    private static List<String> runsNearKey(byte[] data, String key, int window) {
        List<String> runs = new ArrayList<>();
        byte[] needle = key.getBytes(StandardCharsets.UTF_8);
        int from = 0;
        while (true) {
            int idx = indexOf(data, needle, from);
            if (idx < 0) return runs;
            int start = idx + needle.length;
            int end = Math.min(data.length, start + window);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                int c = data[i] & 0xff;
                if (c >= 0x20 && c < 0x7f) {
                    sb.append((char) c);
                } else if (sb.length() > 0) {
                    runs.add(sb.toString());
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) runs.add(sb.toString());
            from = idx + 1;
        }
    }

    /** Extracts the first http(s) URL out of a printable run. */
    private static String urlIn(String run) {
        int http = run.indexOf("http");
        if (http < 0) return null;
        String url = run.substring(http);
        int cut = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == '"' || c == '<' || c == '>') {
                cut = i;
                break;
            }
        }
        url = url.substring(0, cut);
        return url.length() > 8 ? url : null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String readText(File file, int maxBytes) {
        byte[] data = readBytes(file, maxBytes);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // best effort only
        }
    }

    private static byte[] readBytes(File file, int maxBytes) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (total >= maxBytes) break;
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String hostOf(Object request) {
        if (request == null) return null;
        try {
            Object url = request.getClass().getMethod("url").invoke(request);
            if (url == null) return null;
            Object host = url.getClass().getMethod("host").invoke(url);
            return host instanceof String ? (String) host : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A dynamic-proxy okhttp3.Call that always fails, so a blocked upload
     * surfaces as an ordinary network failure instead of an app crash.
     */
    private static Object blockedCall(Class<?> callClass, Object request) {
        return Proxy.newProxyInstance(callClass.getClassLoader(),
                new Class<?>[]{callClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("request".equals(name)) return request;
                    if ("execute".equals(name)) {
                        throw new IOException("AicyUnlock blocked request");
                    }
                    if ("enqueue".equals(name)) {
                        if (args != null && args.length == 1 && args[0] != null) {
                            try {
                                args[0].getClass()
                                        .getMethod("onFailure", callClass, IOException.class)
                                        .invoke(args[0], proxy,
                                                new IOException("AicyUnlock blocked request"));
                            } catch (Throwable ignored) {
                                // best effort
                            }
                        }
                        return null;
                    }
                    if ("cancel".equals(name)) return null;
                    if ("isExecuted".equals(name)) return false;
                    if ("isCanceled".equals(name)) return false;
                    if ("timeout".equals(name)) return null;
                    if ("clone".equals(name)) return proxy;
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    if ("toString".equals(name)) return "AicyUnlock.BlockedCall";
                    return null;
                });
    }

    private void logBlocked(String host, String where) {
        log.accept("AicyUnlock: BLOCKED egress " + where + " -> " + host, null);
    }
}
