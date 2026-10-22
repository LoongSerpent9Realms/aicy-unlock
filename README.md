# aicy-unlock

LSPosed module for **Aicy 助手 13.0.54–13.0.56** (`com.meizu.voiceassistant`).

Two goals:

1. **Reveal the hidden custom-model (BYOK) settings.** Aicy ships the entire
   "模型设置" UI (provider preset, API URL, API Key, model name, test
   connection) but gates it behind one feature flag hard-coded to `false`.
   The module forces that flag to `true` at runtime.
2. **Block Meizu conversation / upload egress.** When you point Aicy at a
   custom endpoint (e.g. local LM Studio), the module stops prompts and
   conversation payloads from also going to Meizu's cloud. Login / OAuth
   hosts are left reachable.

## Verified hook points (v13.0.54, 6 dex)

| Purpose | Target | Action |
|---|---|---|
| flag field | `com.google.android.gms.internal.mlkit_vision_common.J3` field `a` (static boolean) | set `true` |
| assignment site (only write) | `com.flyme.agentos.app.phone.PhoneApp$runtimeInitialization$1#invoke()` | after `proceed()`, set flag `true` |
| settings render gate | `com.flyme.agentos.app.common.ui.settings.n0#invoke()` | set flag `true` before `proceed()` |

The flag also drives the "DEBUG/RELEASE" and "脱敏策略 DEFAULT/FULL" labels;
forcing it means those show DEBUG/FULL locally. It does not affect network
egress.

## Switches

The module app owns the switches; the hooks run inside Aicy (a different uid),
so they read the values through an exported provider
(`content://dev.loong.aicyunlock.cfg/all`) with a 5 s cache. Missing provider =
defaults stay **on**.

| Switch | Key | Effect |
|---|---|---|
| 总开关 | `master` | master gate: no blocking, no catalog rewrite, no flag restore |
| 拦截日志 / 遥测上传 | `block_log` | always blocks `uxip.meizu.com`, `uxip-config`, `uxip-res`, `api-savior.meizu.com` |
| 拦截对话出口 | `block_chat` | blocks the chat hosts, but only while a BYOK model is active |
| 模型目录改写 | `catalog` | default tier → 轻量, inject 自定义 tier |
| 自定义唤醒词 | `wake_word` | routes the stock 语音唤醒 entry to Aicy's hidden keyphrase page |
| 默认档位 | `default_tier` | overrides `default_tier_id` to the user's choice; one of `chat-auto` / `chat-deep` / `chat-lite` / `chat-custom`, default `chat-lite`; `chat-custom` only wins when BYOK is configured |

## Egress firewall (DNS level)

Aicy's own `okhttp3` classes are R8-renamed (`okhttp3.Request` /
`OkHttpClient` do not exist under those names), so name-based network hooks
never bind. Blocking therefore happens in **`java.net.InetAddress#getAllByName`
/ `getByName`**, which every HTTP stack (Ktor→OkHttpEngine, HttpURLConnection,
raw sockets) goes through. A blocked host throws `UnknownHostException` before a
socket is opened.

`java.net.URL#openConnection` is still hooked (gives the URL in the log), and
the OkHttp hook is attempted when the class names survive.

Log / telemetry hosts (blocked independently of model mode):

```
uxip.meizu.com            # UXIP telemetry
uxip-config.meizu.com
uxip-res.meizu.com
api-savior.meizu.com      # Savior crash / patch reporting
```

Chat hosts (blocked only while a custom model is active):

```
voice.meizu.com / llm-router-web.flyme.com / g.meizu.com
credits-api.flyme.com / aider.meizu.com / skill-hub.flyme.com / mcp-gw.flyme.com
```

## Runtime diagnostics

Every hook log line is mirrored to the module app as a targeted broadcast
(`dev.loong.aicyunlock.DIAG` → `DiagReceiver`). The app's "诊断 · Aicy 进程自报"
card shows the last lines with timestamps, so "did the module inject at all"
can be answered on the phone without opening the LSPosed log.

## Model tiers (the "选择模型" sheet)

The sheet is driven entirely by a catalog document shipped as
`assets/model_catalog_builtin.json` and cached verbatim under the
`model_catalog_remote_json` preference key:

```
chat-auto   自动  0.5x   (default_tier_id in stock build)
chat-deep   深度  0.5x
chat-lite   轻量  free   (fallback_free_tier_id)
```

`CatalogPatch` rewrites that JSON **on the way in**, so no obfuscated class
names are involved:

| Edit | Effect |
|---|---|
| `default_tier_id` → user's `default_tier` (default `chat-lite`) | new sessions start on the chosen tier; null leaves the vendor default untouched |
| append tier `chat-custom` ("自定义") + its `models[]` entry | a fourth option in the sheet, bound to the model id from `model.name`, `cost_type=free` (only when BYOK is configured) |
| any pref value `chat-auto` / `auto` under a `*tier*` key → `default_tier` | sessions that persisted the old vendor default also land on the chosen tier |

Hook points (all framework / stable names):

- `android.content.res.AssetManager#open(String)` and `open(String,int)` —
  builtin asset.
- `android.app.ContextImpl#getSharedPreferences` — the prefs file holding the
  cached catalog is rewritten the moment the app opens it (this is the one that
  matters: the app pulls **catalog 2.x** from the network on every launch and
  the remote document overrides the builtin 2.0 one).
- `android.app.SharedPreferencesImpl#getString` — read-time fallback.
- `com.google.gson.Gson#fromJson(...)` string **and Reader** overloads — Ktor's
  Gson content converter feeds the body through a `Reader`, so the `String`
  overload alone never sees the network catalog.
- `org.json.JSONObject(String)` / `com.google.gson.JsonParser#parseString` —
  in case a build parses it there.

The injected tier carries the live `model.name` as its model id, so 自定义
routes to your own endpoint; if no BYOK model is configured yet it falls back to
the placeholder `aicy-custom` (selecting it then reports "model not found").

### Object-level injection (1.9.0+, what actually runs)

The sheet is **not** rendered from the JSON above — catalog 2.x is fetched and
parsed into `com.flyme.agentos.llm.api.model.catalog.P` objects, and the list
comes from `catalog.p.j(ModelScenario)`. So 1.9.0 hooks that method by
**structure** (`ModelScenario → List`, no class names, no version numbers) and
appends a **clone** of the existing 轻量 tier with only id/name/desc/sort
replaced, which keeps the field set identical to the running build.

### 自定义 only appears when an endpoint is configured

`byokActive()` reads `model.providerPreset` / `model.apiUrl` straight out of
`files/datastore/model_config_prefs.preferences_pb`. That is a DataStore
protobuf file where the key and the value sit in **separate** fields, so the
parser scans a small window after each key instead of expecting `key=value`
adjacency. With no endpoint configured the tier list is returned untouched
(diagnostics: `tier skipped: no custom endpoint`).

### Only 自定义 uses your endpoint

Aicy's BYOK setting is **global**, not per tier: configuring a provider routes
every tier to it. With `only_custom_tier` on (default), reads of
`model.apiUrl` / `model.apiKey` / `model.providerPreset` are blanked while any
other tier is selected, so those requests fall back to the Meizu cloud.

Aicy persists that blanked value back, which would erase the user's endpoint, so
the real values are mirrored to `files/aicyunlock.byok` — only those keys, never
tokens or account data — and handed back when the 自定义 tier reads them. The
mirror is trusted only while the DataStore file still carries the key itself, so
a profile that never configured BYOK never grows a 自定义 tier.

## Custom wake word (2.6.0)

Aicy 13.0.55 still ships the custom keyphrase UI —
`com.meizu.voiceassistant.keyphrase.KeyphraseSettingActivity` (main process, no
device gate) — but its 语音唤醒 entry only ever opens the legacy
`com.meizu.voicewakeup.VoiceWakeUpActivity`. With `wake_word` on (default),
`installWakeWordRedirect()` hooks `Activity#startActivity` /
`ContextWrapper#startActivity` and swaps the component before the Intent
launches, so the stock entry lands on the hidden 自定义唤醒词 page (record /
re-record, or pick a suggested phrase). Turning the switch off restores the
original page; phrases already recorded stay, Aicy owns them.

## Power-key / voice entry

`power_key_wakeup` (`PowerKeyWakeupSettings`) is only a wake-up toggle. The
long-press entry opens `QuickVoiceController`, which reads the same global
`model.*` config and the same catalog, so both the BYOK model and the
轻量-default / 自定义 tier edits apply to it too.

## Accessibility feature removed (2.6.0)

The `PowerOverlayService` AccessibilityService (tree scan +
`TYPE_ACCESSIBILITY_OVERLAY` power sheet) is gone — on Aicy it caused visible
screen stutter. Power confirmation now runs entirely in-process: the ASR
callback (`SpeechRecognitionCallback#onCall`, sherpa `OnlineRecognizer#getResult`)
hands the recognised sentence to `PowerGate`, which detects the intent, clears
the field and raises the sheet through the module's own window. No accessibility
permission is requested any more; only 通知使用权 remains (notification
read-aloud fallback).

## Module metadata

`app/src/main/resources/META-INF/xposed/`:

| File | Content |
|---|---|
| `java_init.list` | `dev.loong.aicyunlock.XposedInit` |
| `module.prop` | `minApiVersion=100`, `targetApiVersion=102`, `staticScope=false` |
| `scope.list` | `com.meizu.voiceassistant` (default suggestion only) |

Scope is **dynamic**: `staticScope=false`, and the module app requests
`com.meizu.voiceassistant` through `libxposed/service` on launch (button + auto
attempt), so no manual scope picking is required. Bundled jars:
`app/libs/service.jar`, `app/libs/interface.jar`; provider authority is
`${applicationId}.XposedService`.

The entry implements **both** `onPackageLoaded` (API 100 frameworks) and
`onPackageReady` (API 101+), installing hooks once per process.

## Version independence

Reveal logic no longer depends on 13.0.54's obfuscated names:

1. Fast path: exact names `…mlkit_vision_common.J3#a`,
   `PhoneApp$runtimeInitialization$1#invoke`, `settings/n0#invoke`.
2. Structural path (version-agnostic): enumerate classes in
   `com.google.android.gms.internal.mlkit_vision_common`, snapshot the static
   `boolean` fields of short-named (obfuscated) classes — their declared default
   is `true` — then on every `Activity#onResume` restore any flag the app turned
   off during startup.

If the package or field shape changes, the log says so
(`probe … -> MISSING`, `flag scan … -> 0 static booleans`) instead of failing
silently.

Firewall and BYOK detection only rely on framework classes and the `model.*`
preference keys, so they are not tied to an Aicy build either.

## Diagnostics (tag `AicyUnlock`)

```
module loaded: framework=… api=…      # module injected at all?
onPackageReady: target … loaded       # callback that fired
probe … -> present / MISSING          # do the analysed classes exist?
flag scan … -> N static booleans      # structural discovery result
model-settings (BYOK) reveal armed
catalog rewrite armed (default=轻量, extra tier=自定义)
catalog patched via asset|pref …      # catalog actually rewritten
hooked … / skip hook …                # per-hook result
stock entry -> keyphrase settings     # wake-word redirect fired
mode = CLOUD model / CUSTOM model     # firewall state
```

## In-app guide

The launcher activity *is* the guide (also wired to
`de.robv.android.xposed.category.MODULE_SETTINGS`, so LSPosed's "Open" button
shows it): prerequisites, install steps, verification, BYOK configuration,
firewall behaviour, power-key notes, troubleshooting, plus "open manager" and
"copy log tag" buttons.


## Build

```
JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" \
~/.gradle/dist/gradle-8.14.3/bin/gradle \
  -p H:/Flymetool/aicy-unlock :app:assembleDebug --no-daemon
```

`--offline` only works once the AGP pom is cached; with no network for it, drop
the flag. If the system proxy (`127.0.0.1:7892`) is not running, resolution fails
even on a working direct connection — add
`-I <file>` with an init script that sets `http(s).nonProxyHosts=*`.

Output: `app/build/outputs/apk/debug/app-debug.apk` (V2-signed, debug key).
A copy is in `dist/aicy-unlock-2.8.11-debug.apk`.

## Install

1. Install the APK (`dist/aicy-unlock-2.8.11-debug.apk`). Uninstall the old
   `dev.rikumi.aicyunlock` first — the applicationId changed.
2. LSPosed / Vector manager → Modules → enable **Aicy Unlock**.
3. Open the module app once; it requests the `com.meizu.voiceassistant` scope
   dynamically (approve the manager dialog), or pick it manually.
4. Force-stop Aicy, reopen it.
5. Settings should now show the model entry; configure provider + URL + key.
6. Verify in the manager log (tag `AicyUnlock`) — see diagnostics below.

The app's own launcher icon opens the full in-app guide.

The power-button / voice invocation uses the same global model config, so the
model you set here is the default for every entry point.

## Blueprint reference (extracted from dex)

Provider baseUrls found verbatim in the APK:

| Provider | baseUrl |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| Anthropic | `https://api.anthropic.com` |
| DeepSeek | `https://api.deepseek.com/v1` |
| Moonshot | `https://api.moonshot.cn/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Ollama | local (`http://localhost:11434/v1`) |
| LM Studio | local (`http://localhost:1234/v1`) |
| 自定义 | user-provided |

Protocol: OpenAI-compatible `/chat/completions` + SSE streaming.
Persistence keys (from disassembly): `model.providerPreset`, `model.apiUrl`,
`model.apiKey`, `model.temperature`, `model.contextWindowRatio`.

---

## 1.6.0 — TTS takeover (probe stage)

Goal: give Aicy 13.0.54 a custom voice. Aicy dropped the old 讯飞 voice-clone
module entirely, and the new cloud TTS is 字节 bigtts, so old voice ids cannot
be reused. The plan is to intercept the synthesis request and answer it with
our own audio.

### How the interception works

Verified layout (classes3.dex, 13.0.54):

| Role | Obfuscated name | Detail |
|---|---|---|
| DoubaoTtsApiClient | `com.flyme.agentos.voice.impl.tts.c` | method `a(CloudTtsConfig, String) : Flow` |
| CloudTts | `com.flyme.agentos.voice.impl.tts.b` | wraps the client, caching/prewarm |
| TtsPlaybackManager | `com.flyme.agentos.voice.impl.tts.w` | queue + AudioTrack |
| CloudTtsConfig | `A6.h` | `(endpoint, speaker, audioFormat, sampleRate)` |
| Upstream | — | `POST https://mcp-gw.flyme.com/gateway/bytedance-tts/tts_synthesize` |

The module rewrites the **endpoint** field of the config to
`http://127.0.0.1:18731/tts` before the request leaves the app, and serves that
socket itself. Nothing else is replaced: the app still parses SSE, still decodes
Base64, still feeds AudioTrack and still reports completion, so barge-in and
the streaming state machine keep working.

Discovery is structural — it scans the `voice.impl.tts` package for a
`(non-primitive, String) -> kotlinx.coroutines.flow.*` method and derives the
config class from its first parameter. No class letter is hardcoded.

### This build = transparent probe

`FORWARD = true`: every request is passed upstream verbatim and the real
response is teed into a log file. Audio keeps working exactly as before, so the
probe is zero-risk.

Captured at:

```
/sdcard/Android/data/com.meizu.voiceassistant/files/aicy-tts-probe.log
```

(logcat tag `AicyTts` carries the same lines, plus `AicyUnlock`.)

The log holds: request line, headers, body preview, and the first 16 KB of the
real SSE response. That is everything needed to replay the format locally with
a cloned voice.

### Status

- [x] Locate the synthesize entry and config class structurally
- [x] Loopback endpoint rewrite + per-connection thread
- [x] Transparent forward + wire capture
- [x] Parse the captured SSE schema
- [x] Answer locally with a custom voice (`FORWARD = false`)
- [x] Settings UI: TTS endpoint / voice id / key

### Notification read-aloud (2.4.0, Aicy-native since 2.4.1)

Aicy 13 dropped the automatic notification broadcast that 12.1.46 had. The
module restores it and prefers to run inside Aicy itself:

- `NotifyHooks` hooks Aicy's own
  `com.flyme.agentos.app.phone.notification.NotificationCollectorService`
  (verified on 13.0.55) and feeds every post to `NotifyAnnouncer`. Aicy's
  existing audio focus, timbres and life cycle therefore stay in charge.
- `NotificationSpeechService` in the module app remains as a fallback for when
  Aicy has not been granted 通知使用权. It skips whenever Aicy's collector is
  enabled, so a notification is never spoken twice.
- The settings card shows which of the two paths is active and opens Aicy's
  notification-access detail page (or the full list on older builds). After
  installing a module update, restart Aicy once so the hook enters its running
  process; until then the card says it is waiting for that restart.
- Defaults mirror the old feature: only while the screen is off and a wired /
  USB / Bluetooth headset is connected; never during a call.
- "信任的耳机" keeps an allow-list next to the headset switch. Identity is the
  Bluetooth address when available, otherwise the product name. An empty list
  keeps the old behaviour (any headset); once it has entries, only a matching
  connected device triggers speech. Stored under `notify_trusted`.
- `notify_enable`, `notify_screen_off`, `notify_headset`, `notify_voice` live
  in the same `config` preferences file as the other switches.
- Group summaries, ongoing notifications and duplicate re-posts are skipped.
  Text is taken from the latest `MessagingStyle` message when present, then
  `EXTRA_BIG_TEXT` / `EXTRA_TEXT`; it is capped at 180 characters.
- Playback is local (24 kHz PCM16 via `AudioTrack`, same format Aicy expects).

The TTS endpoint is configurable next to the key (`tts_endpoint`), so a
compatible relay can be used instead of `api.xiaomimimo.com`. The expected
protocol is still the MiMo/OpenAI chat-completions stream with Base64 PCM in
`choices[0].delta.audio.data`.
