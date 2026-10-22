# Aicy Unlock

**让 Aicy 助手用上你自己的模型，并且只在你的接口上说话。**

Aicy 助手 13.0.54–13.0.56 内置了完整的「模型设置」页面，但入口被隐藏；即使配置了自定义模型，对话、模型目录和用量上报仍可能发往魅族云。Aicy Unlock 是一个 LSPosed 模块，在 Aicy 进程内做两件事：

- 露出隐藏的「模型设置」，让你填写 OpenAI 兼容接口；
- 自定义模型生效时切断魅族出口，登录 / OAuth 保持可用。

不修改 APK、不写系统文件；模块自身不上传数据，所有 Key 只保存在本机。

## 功能

| 能力 | 你能得到什么 |
| --- | --- |
| **自定义模型（BYOK）** | 在 Aicy 自己的设置页里填 API 地址、Key、模型名，支持 OpenAI 兼容协议 |
| **模型档位** | 新会话默认落在「轻量」，模型列表多出「自定义」，选中后走你的接口 |
| **出口控制** | 自定义模型生效时切断魅族对话 / 路由 / 计费 / 技能网关；日志和遥测可单独断 |
| **自定义唤醒词** | Aicy 的「语音唤醒」入口直接进入隐藏的自定义唤醒词页 |
| **自定义音色** | 用 MiMo 合成音色替换 Aicy 朗读，支持预置、文字描述、音频复刻 |
| **通知播报** | 息屏 / 耳机时朗读通知，支持应用白名单和信任耳机 |
| **语音增强** | 任意音乐 App 的播放控制、音量控制、任务机器人「打开 X 搜索 Y」、电源滑动确认 |

## 支持环境

| 项目 | 要求 |
| --- | --- |
| 手机 | MEIZU 21 / 22 系列，Flyme 12.6（Android 16）实测 |
| Aicy | 13.0.54–13.0.56（`com.meizu.voiceassistant`） |
| Root | Magisk 27+ / KernelSU / APatch，开启 Zygisk |
| 框架 | LSPosed 2.x 或 Vector 2.1+；LSPosed 1.9.2（API 100）也可载入 |
| 作用域 | `com.meizu.voiceassistant`（模块内可一键申请） |

## 安装

1. 安装 APK。
2. 在 LSPosed / Vector 中启用 **Aicy Unlock**。
3. 打开模块应用，点「申请 Aicy 作用域」；也可以在管理器里手动勾选 `com.meizu.voiceassistant`。
4. 强行停止 Aicy 后重新打开。刚开机就安装的话，重启手机最稳。
5. 打开 Aicy → 我的 → 设置，确认模块已生效（13.0.56 上需要先执行下一步的「临时打开模型设置页」）。

> 停用模块并强停 Aicy 即可完全回滚；模块不会修改 Aicy 的 APK 或用户数据。

## 自定义模型

### 1. 露出 Aicy 的「模型设置」

Aicy 13.0.56 把设置列表里的「模型设置」入口删掉了，但页面本身还在：

1. 打开模块应用 → **自定义模型** → 打开「临时打开 Aicy 的模型设置页」。
2. 进入 Aicy → 我的 → 设置；原来的「设备」分组会变成 Aicy 原生的「模型设置」页（切换后重进一次设置页生效）。
3. 服务商选「自定义」，填写 API 地址、API Key、模型名称，保存。
4. 回到模块应用，关闭这个开关，「设备」分组恢复原样。

配置由 Aicy 自己写入，关掉开关和重启后仍然生效。

### 2. 填写什么

协议是 OpenAI 兼容的 `/chat/completions`（SSE 流式），所以下面这些都能直接填 baseUrl：

- OpenAI、DeepSeek、Moonshot、硅基流动、OpenRouter、通义兼容模式；
- LM Studio、Ollama 等本地服务。

本地服务注意：

- 手机和电脑要在同一个 Wi-Fi；
- 地址填电脑的局域网 IP，例如 `http://192.168.1.21:1234/v1`，**不是** `127.0.0.1`；
- LM Studio 需开启 Serve on Local Network，Ollama 需设置 `OLLAMA_HOST=0.0.0.0`；
- 模型名称必须是服务端真实的模型 id。

### 3. 档位与「自定义」

- 模块应用里可以选新会话的默认档位：**自动 / 深度 / 轻量 / 自定义**，默认「轻量」。
- 模型列表会多出第四项「自定义」，选中后直接走你配置的接口，模型 id 取「模型设置」里的模型名称。
- 「自定义」档位需要先配好接口；没配好时选中会提示模型不存在，并回落到轻量。
- 改完默认档位需要强停 Aicy 再打开，模型目录只在冷启动时读取。

### 4. 电源键 / 语音入口

Aicy 设置里的「电源键唤醒」只是开关；长按电源键唤起的快捷语音浮层读取同一套 `model.*` 配置。所以配好自定义模型后，电源键入口也会使用它，档位列表同样会多出「自定义」。

## 出口控制

模块在 `java.net.InetAddress` 解析层拦截，Ktor / OkHttp / HttpURLConnection 都绕不过；登录、账号、OAuth 域名始终放行。

| 场景 | 行为 |
| --- | --- |
| 未配置自定义模型 | 普通对话完全走魅族云，**不拦**，体验和原版一致 |
| 自定义模型生效 | 切断对话上传、路由、模型列表、用量上报、技能与工具网关 |
| 日志 / 遥测 | 始终切断 UXIP 打点和 Savior 崩溃 / 补丁上报（可在开关里关） |
| 登录 / OAuth | 始终放行，不影响登录状态 |

判定每 3 秒刷新一次；改完模型设置会自动切换模式，日志里能看到 `mode = CLOUD / CUSTOM`。

## 自定义唤醒词

打开 Aicy → 我的 → 设置 → 语音唤醒。开关开着时，这个入口会直接进入 Aicy 隐藏的「自定义唤醒词」页面：录入 / 重录唤醒词，或从建议词里选一个。

关掉开关后入口恢复成系统原来的语音唤醒页；已录入的自定义唤醒词仍由 Aicy 保存，不会删除。

## 自定义音色

Aicy 的声音列表可以出现多条自定义音色，每条单独配置；朗读时由本机直接请求 MiMo（或你填的兼容代理），再按 Aicy 的协议回放，不经过魅族服务器。

1. 模块应用 → **音色 · MiMo**，填写 API 地址和 Key（默认 `https://api.xiaomimimo.com/v1/chat/completions`）。
2. 新建音色，选择一种方式：
   - **预置音色**：冰糖、茉莉、苏打、白桦等；
   - **文字描述**：用一段描述生成音色；
   - **音频复刻**：上传 mp3 / wav 样本复刻。
3. 在 Aicy 的「声音」里选中对应音色即可。

Key 用 AES/GCM 加密后保存在本机，只能由本机和 Aicy 进程读取，不会上传。Aicy 升级后如果合成入口结构变化，模块会停止接管并保留系统原音色，日志里会给出原因。

## 通知播报

1. 在模块应用里授予通知使用权（或打开 Aicy 的通知使用权详情页）。
2. 打开「启用通知播报」，按需设置：
   - 仅息屏时播报；
   - 仅连接信任的耳机时播报；
   - 播报应用白名单；
   - 播报音色。
3. 可点「试听播报音色」。

Aicy 13.0.55+ 优先在 Aicy 进程内接管通知；未授权时使用模块自带的备用监听服务，同一条通知不会播报两次。安装模块更新后需要重启一次 Aicy。

## 语音技能

技能源文件在 `skills/`，可直接导入的包：

- [`app-search-launcher.zip`](skills/app-search-launcher.zip) — 应用内搜索，配合模块的「任务机器人联动」；
- [`volume-control.zip`](skills/volume-control.zip) — 音量控制；
- [`task-robot.zip`](skills/task-robot.zip) — 任务机器人（触发词与 app-search-launcher 相同，二选一）。

### 媒体控制

Aicy 的「上一首 / 下一首 / 暂停 / 继续」会作用于当前正在播放的任意音乐 App（网易云、QQ 音乐、酷我、酷狗等），不再只认 QQ 音乐 Flyme 版。利用 Aicy 自带的系统媒体权限，不需要 root 或无障碍。

### 音量控制

说「音量调到 50」「声音小一点」「静音」等，模块直接调整媒体音量。只处理媒体音量，通话音量、铃声音量、闹钟音量不动；询问当前音量不会触发。

### 任务机器人

导入 `app-search-launcher.zip`，开启「任务机器人联动」，再到「原子能力 → 应用管理」给 Aicy 授权一次。之后说「打开淘宝搜索蓝牙耳机」「在抖音里搜索猫咪视频」即可交给任务机器人执行；技能没被召回时，模块在发送后 10 秒兜底提交。

### 电源滑动确认

在 Aicy 里说「关机」或「重启」，确认条会盖在对话上，需要滑动才会执行，避免误触。

- **临时 root 模式**：关机被拦下，重启降级为软重启（只重启 Android 框架，不动内核）；
- **演练模式**：完整走流程但不执行；
- 默认先尝试 Aicy 的系统权限，失败后再走 root。

## 开关一览

| 开关 | 默认 | 作用 |
| --- | --- | --- |
| 总开关 | 开 | 关闭后所有钩子空转：不拦网络、不改目录 |
| 拦截日志 / 遥测上传 | 开 | 断掉 UXIP、Savior 等日志与崩溃上报 |
| 拦截对话出口（仅自定义模型时） | 开 | 自定义模型生效时切断魅族对话 / 路由 / 计费 |
| 模型目录改写 | 开 | 默认档位改为轻量，并追加「自定义」档位 |
| 只有「自定义」档位走自建 API | 开 | 实验项：其他档位隐藏接口地址；模型设置页地址栏可能显示为空 |
| 自定义唤醒词 | 开 | 语音唤醒入口进入隐藏的自定义唤醒词页 |
| 任务机器人联动 | 开 | 把「打开 X 搜索 Y」交给任务机器人 |
| 启用通知播报 | 开 | 朗读通知 |
| 仅息屏时播报 | 开 | 屏幕亮起时不播报 |
| 仅连接信任的耳机时播报 | 开 | 空列表表示任意耳机 |

## 隐私与密钥

- 本仓库不包含任何 API Key、Token 或签名材料。
- **BYOK Key**：由 Aicy 自己保存；模块只负责露出设置页，不读取、不转发。
- 开启「只有『自定义』档位走自建 API」时，模块会把 `apiUrl` / `apiKey` 镜像到 Aicy 私有目录的 `aicyunlock.byok`，只包含这两个键，用于在非自定义档位时还原真实配置；不会上传。
- **MiMo Key**：AES/GCM 加密后保存在模块本机，配置读取接口只允许 Aicy 进程访问。
- 模块自身不收集、不上传任何统计或日志；自定义音色的请求只发往你填写的接口。

## 常见问题

**设置里没有「模型设置」**

13.0.56 上先打开模块应用里的「临时打开 Aicy 的模型设置页」，再重进 Aicy 设置。仍然没有的话，看日志顺序：没有 `module loaded` 说明模块没注入（作用域 / 启用状态 / 框架问题）；有 `probe … MISSING` 说明 Aicy 版本结构变了。

**日志里没有 AicyUnlock**

模块根本没注入。检查模块是否启用、作用域是否包含 `com.meizu.voiceassistant`、Zygisk 是否开启、Aicy 是否被系统冻结或分身。

**测试连接失败**

多半是地址或网络：用电脑的局域网 IP，不要用 `127.0.0.1`；确认电脑防火墙放行对应端口；确认模型名称就是服务端 id。

**配了自定义模型后，Aicy 自带模型也不能用了**

这是预期行为：一旦判定为自定义模式，魅族对话出口就被切断。想切回云模型，把服务商改回任意魅族内置项即可，3 秒内自动切回 CLOUD。

**默认档位没生效**

模型目录在 Aicy 冷启动时读取。先在模块应用里选好默认档位，再强停 Aicy 重新打开；日志里应有 `catalog rewrite armed` 和 `catalog patched via …`。

**Aicy 升级后还生效吗**

核心逻辑不绑定版本号：BYOK 开关按包内静态布尔结构发现，出口防火墙只依赖框架类和 `model.*` 配置键；TTS、通知、媒体按结构或候选类名发现。目标结构变化时模块会停止对应钩子、保留系统原生行为，并写日志说明原因。

**想彻底回滚**

在管理器里停用模块并强停 Aicy 即恢复原样；卸载 APK 也可以。

**唤醒词想恢复系统页面**

在模块应用里关掉「自定义唤醒词」，再强停 Aicy 重开。已录入的词由 Aicy 保存，不会删除。

## 技术说明

### 作用域与注入

- 模块只作用于 `com.meizu.voiceassistant`；`staticScope=false`，作用域由模块应用动态申请。
- 入口同时实现 `onPackageLoaded`（API 100）和 `onPackageReady`（API 101+），每个进程只安装一次钩子。
- 所有钩子只在内存里改运行状态；找不到目标类或签名时记录日志并保留原生行为（fail closed）。

### 主要注入点

| 能力 | 注入点 |
| --- | --- |
| BYOK 开关 | `mlkit_vision_common` 静态布尔 + `PhoneApp`；13.0.54 精确名仅作快速路径 |
| 模型目录 | `AssetManager#open`、`SharedPreferencesImpl#getString`、Gson `fromJson`、`JsonParser` |
| 出口控制 | `java.net.InetAddress#getAllByName / getByName`；`URL#openConnection` 仅用于日志 |
| 唤醒词 | `Activity#startActivity` / `ContextWrapper#startActivity`，以及高通 SVA 回调链 |
| 音色 | `voice.impl.tts` 包内按结构发现合成入口；本地 loopback 接管 SSE |
| 通知播报 | Aicy `NotificationCollectorService`；未授权时使用模块内备用监听服务 |
| 媒体控制 | `IMusicSupportService$Stub$Proxy` 的 pause / next / prev / play |
| 电源确认 | 扫描聊天请求里的最后一条 user 消息，命中后弹出模块自己的滑动确认层 |

### 诊断

模块日志 tag：`AicyUnlock`。应用内「诊断 · Aicy 进程自报」卡片会显示最近日志，不用连电脑。

正常启动顺序：

```
module loaded: framework=… api=…
onPackageReady: target … loaded
probe … -> present / MISSING
flag scan … -> N static booleans
model-settings (BYOK) reveal armed
hooked … / skip hook …
```

### 构建

环境：JDK 17+、Android SDK 35、Gradle 8.13+（AGP 8.13.0）。

```sh
gradle :app:assembleDebug
```

Windows 上也可以直接用仓库里的脚本（复用 Android Studio 自带 JDK 和本地 Gradle 发行版）：

```powershell
.\gradle-local.ps1 :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Debug 包使用本机调试证书，可直接覆盖安装同包的 Debug 版本。

### 目录

```
app/src/main/java/dev/loong/aicyunlock/   # hook、设置页、技能桥
app/src/main/res/                         # 模块设置界面
app/src/main/resources/META-INF/xposed/   # module.prop / scope
skills/                                   # Aicy 技能源文件与导入包
```

## 免责声明

本项目仅供个人学习与自用。修改系统应用行为存在风险，使用前请自行评估并备份数据；作者不对设备损坏、数据丢失、账号异常或其它后果负责。请遵守你所在地区和服务提供商的使用条款。
