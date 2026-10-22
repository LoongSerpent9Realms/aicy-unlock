package dev.loong.aicyunlock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Aicy 13.0.54 cloud-TTS takeover ("外挂音色" 的接管层).
 *
 * <p>Verified layout in 13.0.54 (classes3.dex):
 * <pre>
 *   com.flyme.agentos.voice.impl.tts.c   = DoubaoTtsApiClient
 *        field a: CloudTtsConfig   (endpoint / speaker / audioFormat / sampleRate)
 *        field d: ktor HttpClient
 *        method a(CloudTtsConfig, String) : kotlinx.coroutines.flow.L0   &lt;- 合成入口
 *   com.flyme.agentos.voice.impl.tts.b   = CloudTts
 *   com.flyme.agentos.voice.impl.tts.w   = TtsPlaybackManager
 *   A6.h = CloudTtsConfig(endpoint, speaker, audioFormat, sampleRate)
 *
 *   request  : POST https://mcp-gw.flyme.com/gateway/bytedance-tts/tts_synthesize
 *   response : SSE, each "data:" line is Base64 of raw PCM
 * </pre>
 *
 * <p>Instead of replacing the returned Flow (which would mean writing Kotlin
 * coroutine code from Java), we rewrite the <em>endpoint</em> argument before
 * the request leaves the app, and serve the stream ourselves from a loopback
 * socket. The app's own playback pipeline stays 100% intact: it still parses
 * SSE, still decodes Base64, still feeds AudioTrack, still reports completion.
 *
 * <p>Answering is MiMo-first: our own synthesizer is tried, and when it cannot
 * deliver in time the request is replayed against the real gateway with a stock
 * speaker substituted, so a slow MiMo costs timbre quality instead of silence.
 */
final class TtsHooks {

    /** Package is stable; the leaf class names are single letters under R8. */
    private static final String TTS_PACKAGE = "com.flyme.agentos.voice.impl.tts";
    private static final String FLOW_PREFIX = "kotlinx.coroutines.flow";

    private static final int PORT = 18731;
    private static final String LOCAL_ENDPOINT = "http://127.0.0.1:" + PORT + "/tts";

    /** Where the real gateway lives; the probe forwards here verbatim. */
    private static final String UPSTREAM =
            "https://mcp-gw.flyme.com/gateway/bytedance-tts/tts_synthesize";

    /**
     * 单条 SSE data 事件携带的 PCM 上限（4800 字节 = 6.4 KB base64）。
     *
     * <p>MiMo 会把整句一次性塞进一个 chunk，直接转发就是一条 200 KB 的
     * {@code data:} 行；Aicy 自己是一小段一小段地吐的。切齐两条路径的形状，
     * 顺带让播放能提前起头。
     */
    private static final int SSE_SLICE = 4800;

    /**
     * true  = 只走透明代理：原样转发到真实网关并把响应抄进抓包文件。
     * false = MiMo 优先，失败再兜底。
     */
    private static final boolean PROBE_ONLY = false;
    /**
     * Master switch for the endpoint takeover only. The "我的音色" row is
     * injected either way, so flipping this back to false restores stock audio
     * immediately while keeping the UI entry visible.
     */
    private static final boolean TAKEOVER = true;

    /** Our own endpoint, used to tell "already redirected" from "stock". */
    private static final String LOOPBACK_PREFIX = "http://127.0.0.1";

    private final ClassLoader loader;
    private final BiConsumer<String, Throwable> log;

    private final List<Entry> entries = new ArrayList<>();
    private final AtomicBoolean serverUp = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicBoolean dumped = new AtomicBoolean(false);
    private final AtomicBoolean redirected = new AtomicBoolean(false);
    private final AtomicBoolean restored = new AtomicBoolean(false);
    private final AtomicBoolean configMissingReported = new AtomicBoolean(false);
    /** Logs the first synthesize call of the process; later ones only when ours. */
    private final AtomicBoolean speakLogged = new AtomicBoolean(false);
    /**
     * Fingerprint of the injected list, so a rename is picked up without a restart.
     *
     * <p>Starts null (not ""), because an empty list also fingerprints to "" and
     * would otherwise suppress the very first refresh.
     */
    private volatile String catalogState;

    /** The real gateway, learned from the client's own config on first sight. */
    private volatile String originalEndpoint = UPSTREAM;
    /** Stock speaker the client itself carries — used as the forward fallback. */
    private volatile String fallbackSpeaker = "saturn_zh_female_keainvsheng_tob";

    private Class<?> configClass;
    private Constructor<?> configCtor;
    private Field fEndpoint, fSpeaker, fFormat, fRate;

    private volatile String lastText;
    private volatile int sampleRate = 24000;
    private volatile String speaker = "?";
    private volatile String audioFormat = "pcm";

    private static final class Entry {
        String className;
        String methodName;
        Class<?>[] params;
        boolean carriesConfig;
    }

    TtsHooks(ClassLoader loader, BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.log = log;
    }

    void install(AicyHooks.Installer installer) {
        MiMoTts.setTrace(log);
        // The provider is unreachable until the process has an Application
        // context, so the first injection here is usually a no-op; the Activity
        // hook below performs the real one on the first resume.
        if (RemoteConfig.profiles().isEmpty()) {
            log.accept("AicyTts: no config yet, catalogue injection deferred "
                    + "to first Activity resume", null);
        } else {
            refreshCatalog();
        }
        try {
            installer.hook("android.app.Activity", "onResume", chain -> {
                Object result = chain.proceed();
                try {
                    syncCatalogIfChanged();
                } catch (Throwable ignored) {
                    // never break the host app's lifecycle
                }
                return result;
            });
        } catch (Throwable error) {
            log.accept("AicyTts: catalogue retry hook not installed", error);
        }
        if (!TAKEOVER) {
            log.accept("AicyTts: endpoint takeover disabled by build flag, "
                    + "stock voices untouched", null);
            return;
        }
        if (!discover()) return;
        allowCleartext(installer);
        startServer();

        for (Entry entry : entries) {
            try {
                installer.hook(entry.className, entry.methodName, chain -> {
                    Object voice = chain.getArg(0);
                    Object text = chain.getArg(1);
                    if (text instanceof String) lastText = (String) text;
                    String speaker = speakerOf(voice);
                    this.speaker = speaker == null ? "?" : speaker;
                    boolean ours = isCustom(speaker);
                    if (ours) {
                        // A rename or a new timbre should appear in 声音 without
                        // forcing the user to restart the assistant.
                        syncCatalogIfChanged();
                    }
                    // Enforce the endpoint on every single call. The client
                    // keeps the config on its own field, so a redirect made for
                    // one timbre would otherwise stick and drag the stock
                    // voices to the loopback server as well.
                    boolean custom = serverUp.get() && isCustom(speaker);
                    if (speakLogged.getAndSet(true) || custom) {
                        log.accept("AicyTts: synth speaker=" + speaker
                                + " ours=" + custom
                                + " serverUp=" + serverUp.get()
                                + " self=" + selfName(chain.getThisObject()), null);
                    }
                    applyEndpoint(chain.getThisObject(), custom);
                    return chain.proceed();
                }, entry.params);
                log.accept("AicyTts: hooked " + entry.className + "#" + entry.methodName
                        + " carriesConfig=" + entry.carriesConfig, null);
            } catch (Throwable error) {
                log.accept("AicyTts: hook failed " + entry.className + "#"
                        + entry.methodName, error);
            }
        }
        log.accept("AicyTts: takeover armed -> " + LOCAL_ENDPOINT
                + " (serverUp=" + serverUp.get() + ")", null);
        AicyHooks.report("TTS 接管已装载 · " + LOCAL_ENDPOINT);
    }

    // ------------------------------------------------------------------
    // Discovery: find (config, String) -> Flow methods in the tts package
    // ------------------------------------------------------------------

    private boolean discover() {
        List<String> names = classesIn(TTS_PACKAGE);
        if (names.isEmpty()) {
            log.accept("AicyTts: cannot enumerate " + TTS_PACKAGE
                    + ", takeover skipped", null);
            return false;
        }
        int scanned = 0;
        for (String name : names) {
            if (name.indexOf('$') >= 0) continue;
            try {
                Class<?> type = Class.forName(name, false, loader);
                scanned++;
                for (Method method : type.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 2) continue;
                    if (params[1] != String.class) continue;
                    if (params[0].isPrimitive() || params[0] == String.class) continue;
                    if (!method.getReturnType().getName().startsWith(FLOW_PREFIX)) continue;
                    Entry entry = new Entry();
                    entry.className = name;
                    entry.methodName = method.getName();
                    entry.params = params;
                    entry.carriesConfig = bindConfig(params[0]);
                    entries.add(entry);
                }
            } catch (Throwable ignored) {
                // not loadable in isolation
            }
        }
        log.accept("AicyTts: scanned " + scanned + " classes in " + TTS_PACKAGE
                + " -> " + entries.size() + " synthesize candidates, configClass="
                + (configClass == null ? "none" : configClass.getName()), null);
        return !entries.isEmpty();
    }

    /** Binds CloudTtsConfig(endpoint, speaker, audioFormat, sampleRate). */
    private boolean bindConfig(Class<?> type) {
        try {
            // Cheap reject first: the real config is the only class carrying three
            // Strings plus an int under the obfuscated names a/b/c/d. Without this
            // the discovery pass would hand us the *voice descriptor* (C8/a0,
            // String/float/float/String) and latch the wrong type permanently.
            if (configClass != null && configClass != type) return false;
            Constructor<?> ctor = type.getDeclaredConstructor(
                    String.class, String.class, String.class, int.class);
            ctor.setAccessible(true);
            Field a = field(type, "a");
            Field b = field(type, "b");
            Field c = field(type, "c");
            Field d = field(type, "d");
            if (a == null || b == null || c == null || d == null) return false;
            if (a.getType() != String.class || b.getType() != String.class
                    || c.getType() != String.class || d.getType() != int.class) return false;
            configClass = type;
            configCtor = ctor;
            fEndpoint = a;
            fSpeaker = b;
            fFormat = c;
            fRate = d;
            log.accept("AicyTts: bound CloudTtsConfig = " + type.getName(), null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads the speaker id out of the first argument, which is the voice
     * descriptor {@code C8.a0} — not the config, as the first build assumed.
     * Several String fields live on it, so candidates are scored: our own
     * prefix wins, then a 火山 timbre id. A value we cannot recognise yields
     * {@code null}, which keeps the call on the stock (upstream) path.
     */
    private static String speakerOf(Object voice) {
        if (voice == null) return null;
        if (voice instanceof String) return (String) voice;
        String best = null;
        int bestScore = -1;
        try {
            for (Field field : voice.getClass().getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                field.setAccessible(true);
                Object value = field.get(voice);
                if (!(value instanceof String)) continue;
                int score = speakerScore((String) value);
                if (score > bestScore) {
                    bestScore = score;
                    best = (String) value;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return bestScore > 0 ? best : null;
    }

    private static int speakerScore(String value) {
        if (value == null || value.length() < 4) return -1;
        if (value.startsWith(VoiceCatalog.CUSTOM_PREFIX)) return 100;
        if (value.endsWith("_bigtts")) return 80;
        if (value.matches("[a-z]{2}_[a-z]+_[a-z0-9_]+")) return 60;
        return 0;
    }

    /** Only the timbre we injected ourselves is routed to the loopback server. */
    private static boolean isCustom(String speaker) {
        return speaker != null && speaker.startsWith(VoiceCatalog.CUSTOM_PREFIX);
    }

    /**
     * Points the client at the loopback server for our own timbre and back at
     * the real gateway for everything else.
     *
     * <p>This must run on <em>every</em> synthesize call: the endpoint lives on
     * the client's own field, so leaving a redirect in place would silently
     * ruin the stock voices until the process restarts.
     */
    private void applyEndpoint(Object self, boolean custom) {
        if (self == null) return;
        try {
            if (configClass == null && !bindFromSelf(self)) {
                if (configMissingReported.compareAndSet(false, true)) {
                    log.accept("AicyTts: no config field on "
                            + self.getClass().getName()
                            + " — fields=" + describe(self), null);
                }
                return;
            }
            Field target = null;
            for (Field field : self.getClass().getDeclaredFields()) {
                if (field.getType() == configClass) {
                    target = field;
                    break;
                }
            }
            if (target == null) {
                if (configMissingReported.compareAndSet(false, true)) {
                    log.accept("AicyTts: config class " + configClass.getName()
                            + " not among fields of " + self.getClass().getName()
                            + " — fields=" + describe(self), null);
                }
                return;
            }
            target.setAccessible(true);
            Object current = target.get(self);
            if (current == null) {
                log.accept("AicyTts: config field " + target.getName()
                        + " on " + self.getClass().getName() + " is null", null);
                return;
            }

            String endpoint = (String) fEndpoint.get(current);
            // Remember the real gateway the first time we ever see it.
            if (endpoint != null && !endpoint.startsWith(LOOPBACK_PREFIX)) {
                originalEndpoint = endpoint;
            }
            String desired = custom ? LOCAL_ENDPOINT : originalEndpoint;
            if (desired == null) {
                log.accept("AicyTts: no endpoint known yet (current=" + endpoint
                        + ") custom=" + custom, null);
                return;
            }
            if (desired.equals(endpoint)) return;

            Object copy = configCtor.newInstance(desired,
                    fSpeaker.get(current), fFormat.get(current), fRate.getInt(current));
            target.set(self, copy);
            this.audioFormat = (String) fFormat.get(current);
            this.sampleRate = fRate.getInt(current);
            String configured = (String) fSpeaker.get(current);
            if (configured != null && !configured.startsWith(VoiceCatalog.CUSTOM_PREFIX)) {
                fallbackSpeaker = configured;
            }
            if (custom && redirected.compareAndSet(false, true)) {
                log.accept("AicyTts: endpoint -> " + LOCAL_ENDPOINT
                        + " (speaker=" + fSpeaker.get(current) + ", "
                        + sampleRate + "Hz " + audioFormat + ")", null);
            } else if (!custom && restored.compareAndSet(false, true)) {
                log.accept("AicyTts: stock voice restored -> " + desired, null);
            }
        } catch (Throwable error) {
            log.accept("AicyTts: endpoint switch failed on "
                    + self.getClass().getName(), error);
        }
    }

    /** Class name of the hooked instance, or "null". */
    private static String selfName(Object self) {
        return self == null ? "null" : self.getClass().getName();
    }

    /** Field list of a class, for the "why did the bind fail" diagnostics. */
    private static String describe(Object self) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field field : self.getClass().getDeclaredFields()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(field.getName()).append(':').append(field.getType().getSimpleName());
            }
        } catch (Throwable ignored) {
            return "(unreadable)";
        }
        return sb.toString();
    }

    /** Finds CloudTtsConfig among the client's own fields and binds to it. */
    private boolean bindFromSelf(Object self) {
        if (configClass != null) return true;
        for (Field field : self.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.isPrimitive() || type == String.class) continue;
            if (bindConfig(type)) return true;
        }
        return false;
    }

    /** OkHttp refuses cleartext when the app forbids it; loopback is local. */
    private void allowCleartext(AicyHooks.Installer installer) {
        int armed = 0;
        try {
            installer.hook("android.security.NetworkSecurityPolicy",
                    "isCleartextTrafficPermitted", chain -> true, String.class);
            armed++;
        } catch (Throwable ignored) {
            // maybe not declared, or already permitted
        }
        try {
            installer.hook("android.security.NetworkSecurityPolicy",
                    "isCleartextTrafficPermitted", chain -> true);
            armed++;
        } catch (Throwable ignored) {
            // already permitted
        }
        log.accept("AicyTts: cleartext hooks armed " + armed + "/2", null);
    }

    /** Injects one row per configured profile; logs and moves on when it cannot. */
    private void refreshCatalog() {
        java.util.List<VoiceProfile> profiles = RemoteConfig.profiles();
        int size = new VoiceCatalog(loader, log).inject(profiles);
        if (size < 0) {
            log.accept("AicyTts: timbre rows not injected — stock list unchanged", null);
        }
        // Pre-trim the reference clips off the playback path: decoding a few
        // hundred KB of Base64 inside the request would be dead waiting time.
        MiMoTts.warmup(profiles);
    }

/**
     * Rebuilds the catalogue when the user's list changed (they added, renamed or
 * deleted a timbre while Aicy was running), so the settings page picks the new
 * set up without a restart.
 *
 * <p>When the provider cannot be reached the list reads as empty, which is
 * indistinguishable from "the user deleted everything". Rebuilding in that case
 * is harmless (it only re-derives the catalogue from the stock entries), whereas
 * latching the empty state would suppress every later refresh.
 */
    private void syncCatalogIfChanged() {
        // Unreachable provider: the empty list means "unknown", not "no timbres",
        // so leave the fingerprint alone and retry on the next resume.
        if (!RemoteConfig.reachable()) {
            log.accept("AicyTts: config not reachable yet, catalogue refresh skipped", null);
            return;
        }
        String fingerprint = catalogFingerprint();
        if (fingerprint.equals(catalogState)) return;
        catalogState = fingerprint;
        refreshCatalog();
    }

    private static String catalogFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (VoiceProfile p : RemoteConfig.profiles()) {
            sb.append(p.id).append('|').append(p.name).append('|')
                    .append(p.avatar).append(';');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Loopback HTTP server: answer with SSE from MiMo
    // ------------------------------------------------------------------

    private void startServer() {
        Thread thread = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 16,
                    InetAddress.getByName("127.0.0.1"))) {
                serverUp.set(true);
                log.accept("AicyTts: loopback server listening on " + PORT, null);
                while (!Thread.currentThread().isInterrupted()) {
                    final Socket socket = server.accept();
                    // One thread per request: Aicy streams several TTS
                    // segments in parallel and a serial accept loop would
                    // stall them until the previous stream finished.
                    Thread worker = new Thread(() -> {
                        try {
                            handle(socket);
                        } catch (Throwable error) {
                            log.accept("AicyTts: connection error", error);
                        } finally {
                            try {
                                socket.close();
                            } catch (Throwable ignored) {
                                // nothing to do
                            }
                        }
                    }, "AicyTtsConn");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (Throwable error) {
                // Another Aicy process may already own the port; loopback is
                // shared inside the app's sandbox, so reuse it instead of
                // giving up on the takeover.
                boolean alive = probe();
                serverUp.set(alive);
                log.accept("AicyTts: bind " + PORT + " failed, existing server alive="
                        + alive, error);
            }
        }, "AicyTtsLoopback");
        thread.setDaemon(true);
        thread.start();
    }

    /** True when something (another Aicy process) already answers on our port. */
    private boolean probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 600);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            // detect CRLFCRLF
            if (b == '\n') {
                byte[] h = head.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n - 4] == '\r' && h[n - 3] == '\n'
                        && h[n - 2] == '\r' && h[n - 1] == '\n') {
                    state = 1;
                    break;
                }
            }
            if (head.size() > 32 * 1024) break;
        }
        String headerText = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        String requestLine = lines.length > 0 ? lines[0] : "?";
        // A bare connect-and-close (our own probe(), or a port scanner) leaves
        // no request line at all. Treating that as a TTS request produced a
        // phantom "未配置" report and a spurious jump to the settings page, which
        // buried the real traffic in the probe log.
        if (!requestLine.startsWith("POST") && !requestLine.startsWith("GET")) {
            log.accept("AicyTts: ignored non-HTTP connection on " + PORT
                    + " (head=" + headerText.length() + "B)", null);
            return;
        }
        int contentLength = 0;
        boolean chunked = false;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } catch (Throwable ignored) {
                    contentLength = 0;
                }
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        byte[] body = new byte[0];
        if (chunked) {
            body = readChunked(in);
        } else if (contentLength > 0) {
            // The clamp only guards against a lying Content-Length; a real
            // request is a few hundred bytes, and truncating it silently would
            // leave us synthesising from a cut-off sentence.
            body = readN(in, Math.min(contentLength, 4 * 1024 * 1024));
        }

        int id = seq.incrementAndGet();
        boolean first = dumped.compareAndSet(false, true);
        File dump = dumpFile();
        // Read the utterance and the timbre out of THIS request. The shared
        // lastText/speaker fields are written by the hook on one thread and read
        // here on a loopback worker, so with two requests in flight they interleave
        // and one sentence gets synthesised twice while the other never plays.
        String bodyText = textOf(body);
        String bodySpeaker = speakerOfBody(body);
        String bodyPreview = preview(body);
        log.accept("AicyTts: req#" + id + " " + requestLine
                + " len=" + body.length + " chunked=" + chunked
                + " text=" + bodyText + " speaker=" + bodySpeaker
                + " format=" + audioFormat + " rate=" + sampleRate, null);
        if (first) {
            log.accept("AicyTts: req#" + id + " body=" + bodyPreview, null);
            write(dump, "=== request " + requestLine + " ===\n"
                    + headerText + "\n--- body(" + body.length + ") ---\n"
                    + bodyPreview + "\n");
        }

        if (PROBE_ONLY) {
            forward(socket, lines, body, id, dump);
            return;
        }
        serveLocal(socket, lines, body, id, dump, bodyText, bodySpeaker);
    }

    /**
     * Pulls the utterance out of the request body.
     *
     * <p>The host sends the real text in {@code req_params.text}; the hooked
     * argument is unreliable under concurrency, so the body is the only trustworthy
     * source. Falls back to the hook's view only when the field is absent.
     */
    private static String textOf(byte[] body) {
        String text = jsonString(body, "text");
        if (text != null && !text.trim().isEmpty()) return text.trim();
        return null;
    }

    /** The timbre this request asks for, so two concurrent voices cannot swap. */
    private static String speakerOfBody(byte[] body) {
        String value = jsonString(body, "speaker");
        if (value != null && !value.isEmpty()) return value;
        return null;
    }

    /**
     * Minimal string-field reader for the one flat JSON object the host sends.
     * A regex beats pulling in org.json here: the body is a few hundred bytes and
     * the shape is fixed.
     */
    private static String jsonString(byte[] body, String field) {
        if (body == null || body.length == 0) return null;
        String json = new String(body, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Answers the app with audio synthesized by MiMo instead of 字节's gateway.
     *
     * <p>The wire format is dictated by {@code DoubaoTtsApiClient$synthesize$1}:
     * only lines starting with {@code "data: "} are parsed, and the payload is a
     * JSON object whose {@code code}, {@code message} and {@code data} fields carry
     * the status and Base64 PCM. Anything else counts as a non-data line and is
     * dropped silently, which is why the earlier beep produced no sound.
     *
     * <p>The response header goes out with the <em>first</em> audio slice rather
     * than up front: while nothing has been sent, the request can still be replayed
     * against the real gateway, so a slow or silent MiMo costs timbre quality
     * instead of the whole utterance.
     */
    private void serveLocal(Socket socket, String[] lines, byte[] body, int id, File dump,
                        String bodyText, String bodySpeaker)
            throws Exception {
        OutputStream out = socket.getOutputStream();
        // Fall back to the hook's view only if the body carried no text, which
        // should not happen with this client but keeps the sentence speakable.
        String text = bodyText != null ? bodyText : lastText;
        if (text == null || text.trim().isEmpty()) text = "你好";
        String timbre = bodySpeaker != null ? bodySpeaker : speaker;
        VoiceProfile profile = RemoteConfig.bySpeaker(timbre);
        if (!MiMoTts.ready(profile)) {
            String reason = MiMoTts.notReadyReason(profile);
            log.accept("AicyTts: req#" + id + " 未配置 — " + reason + "，改用系统音色", null);
            AicyHooks.report("TTS 未配置 · " + reason + "（本句已用系统音色）");
            forward(socket, lines, body, id, dump);
            return;
        }

        long started = System.currentTimeMillis();
        final boolean[] headSent = {false};
        final int[] chunks = {0};
        final long[] firstAt = {-1};
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int total;
        try {
            total = MiMoTts.stream(profile, text, pcm -> {
                pending.write(pcm);
                emitSlices(out, pending, headSent, chunks, firstAt, started, false);
            });
            emitSlices(out, pending, headSent, chunks, firstAt, started, true);
        } catch (Throwable error) {
            if (headSent[0]) {
                log.accept("AicyTts: req#" + id + " MiMo 中断，已发出 " + chunks[0]
                        + " 段", error);
                try {
                    out.write(emptyEvent());
                    out.flush();
                } catch (Throwable gone) {
                    log.accept("AicyTts: req#" + id + " 客户端已断开", null);
                }
                return;
            }
            log.accept("AicyTts: req#" + id + " MiMo 未能出声，改用系统音色", error);
            AicyHooks.report("TTS 已用系统音色兜底 · " + error);
            forward(socket, lines, body, id, dump);
            return;
        }

        if (!headSent[0]) {
            // 正常结束却没有音频，对 app 来说和静音是同一件事。
            log.accept("AicyTts: req#" + id + " MiMo 返回空音频（" + total
                    + "B），改用系统音色", null);
            AicyHooks.report("TTS 无音频 · 本句已用系统音色");
            forward(socket, lines, body, id, dump);
            return;
        }

        out.write(emptyEvent());
        out.flush();
        log.accept("AicyTts: req#" + id + " MiMo ok " + total + " 字节 / " + chunks[0]
                + " 段 in " + (System.currentTimeMillis() - started) + "ms ttfb="
                + firstAt[0] + "ms text=" + text, null);
        AicyHooks.report("TTS 合成完成 · " + total + " 字节 · " + text);
    }

    /**
     * 把累计的 PCM 切成固定大小的 SSE 事件写出去。
     *
     * <p>MiMo 会把整句一次性返回，直接转发就是一条两百 KB 的 {@code data:} 行；
     * 真实网关是一小段一小段吐的，Aicy 的播放链路按后者调优。force 时把不足一片
     * 的尾巴也送走，否则最后半秒音频永远发不出去。
     */
    private void emitSlices(OutputStream out, ByteArrayOutputStream pending,
                            boolean[] headSent, int[] chunks, long[] firstAt,
                            long started, boolean force) throws java.io.IOException {
        byte[] buf = pending.toByteArray();
        int complete = force ? buf.length : (buf.length / SSE_SLICE) * SSE_SLICE;
        int consumed = 0;
        for (int at = 0; at + SSE_SLICE <= complete; at += SSE_SLICE) {
            consumed = at + SSE_SLICE;
            writeSlice(out, headSent, chunks, firstAt, started, buf, at, SSE_SLICE);
        }
        if (force && consumed < buf.length) {
            int len = buf.length - consumed;
            writeSlice(out, headSent, chunks, firstAt, started, buf, consumed, len);
            consumed += len;
        }
        byte[] rest = new byte[buf.length - consumed];
        System.arraycopy(buf, consumed, rest, 0, rest.length);
        pending.reset();
        pending.write(rest);
    }

    private void writeSlice(OutputStream out, boolean[] headSent, int[] chunks,
                            long[] firstAt, long started, byte[] buf, int at, int len)
            throws java.io.IOException {
        if (!headSent[0]) {
            headSent[0] = true;
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "X-AicyUnlock: mimo-tts\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        byte[] slice = new byte[len];
        System.arraycopy(buf, at, slice, 0, len);
        out.write(("data: {\"code\":0,\"message\":\"\",\"data\":\""
                + Base64.getEncoder().encodeToString(slice) + "\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        chunks[0]++;
        if (firstAt[0] < 0) firstAt[0] = System.currentTimeMillis() - started;
    }

    /** One event carrying zero audio: lets the app finish cleanly on silence. */
    private static byte[] emptyEvent() {
        return "data: {\"code\":0,\"message\":\"\",\"data\":\"\"}\n\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Replays the request against the real gateway so the sentence is still spoken
     * by a genuine voice. The genuine SSE reply is teed into the probe file, which
     * is what keeps the local wire format honest as Aicy's client evolves.
     */
    private void forward(Socket socket, String[] lines, byte[] body, int id, File dump)
            throws Exception {
        // Our own speaker id does not exist upstream, so the gateway would
        // reject it. Swap it for the stock id the client already had configured.
        if (body != null && body.length > 0) {
            String json = new String(body, StandardCharsets.UTF_8);
            if (json.contains(VoiceCatalog.CUSTOM_PREFIX)) {
                String swapped = json.replaceAll(
                        "\"" + VoiceCatalog.CUSTOM_PREFIX + "[^\"]*\"",
                        "\"" + fallbackSpeaker + "\"");
                body = swapped.getBytes(StandardCharsets.UTF_8);
                log.accept("AicyTts: req#" + id + " speaker -> " + fallbackSpeaker
                        + " (placeholder, upstream only accepts known ids)", null);
            }
        }
        java.net.URL url = new java.net.URL(UPSTREAM);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        String requestType = "application/json";
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("content-type")) {
                requestType = value;
                continue;
            }
            if (lower.equals("host") || lower.equals("content-length")
                    || lower.equals("connection") || lower.equals("transfer-encoding")
                    || lower.equals("accept-encoding")) {
                continue;
            }
            try {
                conn.setRequestProperty(name, value);
            } catch (Throwable ignored) {
                // header not allowed by the platform
            }
        }
        conn.setRequestProperty("Content-Type", requestType);
        conn.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream upstream = conn.getOutputStream()) {
            upstream.write(body);
        }

        int code = conn.getResponseCode();
        String contentType = conn.getContentType();
        InputStream response;
        try {
            response = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        } catch (Throwable error) {
            response = conn.getErrorStream();
        }
        if (response == null) {
            log.accept("AicyTts: req#" + id + " upstream " + code + " no body", null);
            socket.getOutputStream().write(("HTTP/1.1 " + code + " X\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return;
        }

        OutputStream client = socket.getOutputStream();
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + (contentType == null
                ? "text/event-stream; charset=utf-8" : contentType) + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "X-AicyUnlock: tts-forward\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        client.write(head.getBytes(StandardCharsets.ISO_8859_1));

        int total = 0;
        ByteArrayOutputStream tee = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = response.read(buffer)) > 0) {
            client.write(buffer, 0, read);
            client.flush();
            if (total < 16 * 1024) {
                tee.write(buffer, 0, Math.min(read, 16 * 1024 - total));
            }
            total += read;
        }
        client.flush();
        synchronized (TtsHooks.class) {
            if (dump != null && total > 0) {
                String sample = new String(tee.toByteArray(), StandardCharsets.ISO_8859_1);
                write(dump, "\n--- response " + code + " " + contentType
                        + " total=" + total + " ---\n" + sample + "\n");
            }
        }
        log.accept("AicyTts: req#" + id + " 系统音色 upstream=" + code + " bytes=" + total
                + " ct=" + contentType, null);
        AicyHooks.report("TTS 已用系统音色 · " + total + " 字节 · req#" + id);
    }

    private static byte[] readN(InputStream in, int length) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            StringBuilder sizeLine = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0 && b != '\n') {
                if (b != '\r') sizeLine.append((char) b);
            }
            if (b < 0) break;
            String hex = sizeLine.toString().trim();
            int semi = hex.indexOf(';');
            if (semi >= 0) hex = hex.substring(0, semi);
            if (hex.isEmpty()) continue;
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (Throwable ignored) {
                break;
            }
            if (size == 0) {
                // trailing CRLF / trailers
                while ((b = in.read()) >= 0) {
                    if (b == '\n') {
                        // second consecutive newline ends the body
                        if (in.available() > 0) continue;
                        break;
                    }
                }
                break;
            }
            out.write(readN(in, size));
            in.read();
            in.read();
            if (out.size() > 4 * 1024 * 1024) break;
        }
        return out.toByteArray();
    }

    private static String preview(byte[] body) {
        if (body == null || body.length == 0) return "(empty)";
        String text = new String(body, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(text.length(), 600); i++) {
            char c = text.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '.');
        }
        return sb.toString();
    }

    private void write(File file, String content) {
        if (file == null) return;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private File dumpFile() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof android.content.Context) {
                File dir = ((android.content.Context) app).getExternalFilesDir(null);
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    return new File(dir, "aicy-tts-probe.log");
                }
            }
        } catch (Throwable ignored) {
            // no external dir
        }
        return null;
    }

    /** Class names of a host package; see {@link DexIndex} for the two routes. */
    private List<String> classesIn(String pkg) {
        return DexIndex.classesIn(loader, pkg, log);
    }

    private static Object fieldOrNull(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
