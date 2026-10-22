package dev.loong.aicyunlock;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * In-app guide. Also serves as the LSPosed "module settings" entry, so the
 * manager's "Open" button lands on the same instructions.
 */
public final class MainActivity extends Activity {

    private static final String AICY_PACKAGE = "com.meizu.voiceassistant";
    /** 长按识屏那条浮条归 Aicy Touch 自己管，和 Aicy 本体是两个包。 */
    private static final String PICKER_PACKAGE = "com.meizu.picker";
    /** SnapdragonSVA: the vendor trainer that actually records the wake word. */
    private static final String SVA_PACKAGE = "com.qualcomm.qti.sva";
    private static final int ACCENT = 0xFF818CF8;
    private static final int CARD = 0xFF1C1C1E;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int MUTED = 0xFF9BA0A6;
    private static final int OK = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    /**
     * Colours standing in for the stock avatar templates. The real avatar is a
     * Compose resource handle inside Aicy, so these swatches are the honest
     * preview: index N here is the Nth stock row we clone the avatar from.
     */
    private static final int[] AVATAR_COLORS = {
            0xFF6C7A89, 0xFF8E7CC3, 0xFF4F9D69, 0xFFC77D54, 0xFF4A90A4,
    };

    private float d;
    private TextView scopeState;
    private android.view.View dockPreviewRoot;
    private XposedService service;
    private boolean autoRequested;

    private static final int REQUEST_SAMPLE = 1001;
    /** Raw-bytes ceiling matching {@link MiMoTts#MAX_SAMPLE_BASE64} (×3/4 for base64). */
    private static final int MAX_SAMPLE_BYTES = MiMoTts.MAX_SAMPLE_BASE64 * 3 / 4;
    private static final int REQUEST_EXPORT = 1002;
    private static final int REQUEST_IMPORT = 1003;

    /** Registration entry point; the referral code is baked into the query. */
    private static final String MIMO_PLATFORM_REF =
            "https://platform.xiaomimimo.com?ref=CCZX36";
    private static final String MIMO_CONSOLE = "https://platform.xiaomimimo.com";
    private static final String GITHUB_URL =
            "https://github.com/LoongSerpent9Realms/aicy-unlock";
    private static final String ISSUES_URL = GITHUB_URL + "/issues";

    private static final String GUIDE_INSTALL =
            "需要 Root（Magisk 27+ / KernelSU / APatch）并开启 Zygisk；"
          + "框架用 LSPosed 2.x、Vector 2.1+，或 LSPosed 1.9.2。\n\n"
          + "1. 安装 APK（装过 dev.rikumi.aicyunlock 的先卸载）。\n"
          + "2. 在管理器里启用 Aicy Unlock。\n"
          + "3. 用本页「申请 Aicy 作用域」，或在管理器里手动勾选 "
          + AICY_PACKAGE + "。\n"
          + "4. 强行停止 Aicy 后重新打开。\n\n"
          + "验证：\n"
          + "• 界面：Aicy → 我的 → 设置。13.0.56 需先打开「临时打开模型设置页」。\n"
          + "• 日志：管理器 → 日志，搜索 AicyUnlock，正常顺序是\n"
          + "  module loaded → probe present → reveal armed → hooked。\n"
          + "  连 module loaded 都没有，说明模块没注入（作用域 / 启用状态 / Zygisk）。";

    private static final String GUIDE_MODEL =
            "打开「临时打开 Aicy 的模型设置页」，进 Aicy → 我的 → 设置 →「模型设置」，"
          + "服务商选「自定义」：\n\n"
          + "• API 地址：http://192.168.1.x:1234/v1（电脑的局域网 IP，不是 127.0.0.1）\n"
          + "• API Key：本地服务随便填，如 local\n"
          + "• 模型名称：服务端真实的模型 id\n\n"
          + "协议是 OpenAI 兼容的 /chat/completions（SSE 流式），"
          + "OpenAI / DeepSeek / Moonshot / 硅基流动 / OpenRouter 等都可直接填 baseUrl。"
          + "手机和电脑要在同一 Wi-Fi；LM Studio 需开启 Serve on Local Network，"
          + "Ollama 需设置 OLLAMA_HOST=0.0.0.0。\n\n"
          + "默认档位在「默认档位」卡片里选，模块会改写模型目录的 default_tier_id。"
          + "Aicy 冷启动时读取，改完要强停 Aicy。选「自定义」前先配好自定义模型，"
          + "否则该档位不存在，会自动回落到轻量。";

    private static final String GUIDE_INTERCEPT =
            "• 日志 / 遥测：默认断掉 uxip.meizu.com 与 api-savior.meizu.com，可在上方关掉。\n"
          + "• 只用自带云模型：对话、路由、模型列表全部放行，体验与原版一致。\n"
          + "• 自定义模型生效：切断 voice.meizu.com、llm-router-web.flyme.com、"
          + "g.meizu.com、credits-api.flyme.com，以及技能 / 工具网关；登录与 OAuth 保留。\n"
          + "• 判定每 3 秒刷新，改完模型设置自动切模式；日志里会打印 "
          + "mode = CLOUD / CUSTOM。\n\n"
          + "拦截点在 java.net.InetAddress 解析，与 HTTP 引擎无关；"
          + "被拦的请求会写 BLOCKED egress。";

    private static final String GUIDE_WAKE =
            "电源键：Aicy 的「电源键唤醒」唤起 QuickVoice 浮层，它读的是同一套 model.* 配置。"
          + "把「模型设置」配好即可，档位列表同样会多出「自定义」。\n\n"
          + "自定义唤醒词：打开上方开关后，Aicy → 我的 → 设置 → 语音唤醒"
          + "会直接进入隐藏的自定义唤醒词页，可以录入 / 重录或从建议词里选。"
          + "关掉开关恢复系统原页面，已录入的词仍保留。";

    private static final String GUIDE_MIMO =
            "接口：默认 https://api.xiaomimimo.com/v1，可换成任何兼容的代理地址"
          + "（OpenAI chat/completions + 流式音频，Bearer 鉴权）。\n\n"
          + "模型：\n"
          + "• mimo-v2.5-tts-voicedesign — 文字描述造音色\n"
          + "• mimo-v2.5-tts-voiceclone — 音频样本复刻音色（mp3 / wav）\n"
          + "• mimo-v2.5-tts — 预置音色（冰糖 / 茉莉 / 苏打 / 白桦 等）\n\n"
          + "计费：官方标注限时免费，单价与免费时长未公布；"
          + "用量与账单在 platform.xiaomimimo.com → 账单明细查看。本模块不收费。\n\n"
          + "邀请码 CCZX36：双方各得 ¥10 体验金，首单 9 折，体验金 40 天有效。"
          + "本页的「打开 MiMo 开放平台」链接已带码。";

    private EditText mimoKeyInput;
    /** 语音合成统一替换的 model id（留空 = 用音色自带的）。 */
    private EditText ttsModelInput;
    private EditText mimoEndpointInput;
    private TextView voiceState;
    /** Host of the timbre rows; rebuilt whenever the list changes. */
    private LinearLayout voiceListHost;
    /** The timbre currently open in the editor, or null when the list is shown. */
    private VoiceProfile editing;
    private EditText nameInput;
    private EditText descInput;
    private TextView editorClipState;

    private TextView notifyState;
    private TextView notifyGrantButton;
    private TextView notifyTrustSummary;
    private TextView notifyAppsSummary;
    private LinearLayout notifyVoiceHost;

    private LinearLayout defaultTierChips;
    private String selectedDefaultTier;

    @Override
    protected void onResume() {
        super.onResume();
        // The recorder may have attached a clip to the timbre being edited.
        if (voiceListHost != null) {
            if (editing != null) {
                VoiceProfile fresh = findProfile(editing.id);
                if (fresh != null) {
                    editing = fresh;
                    renderEditor(fresh);
                } else {
                    renderVoiceList();
                }
            } else {
                renderVoiceList();
            }
        }
        String def = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE)
                .getString(ConfigProvider.KEY_DEFAULT_TIER, CatalogPatch.FREE_TIER_ID);
        if (!def.equals(selectedDefaultTier)) {
            selectedDefaultTier = def;
            refreshDefaultTierChips();
        }
        // Coming back from the system's notification-access page, or from
        // editing a timbre, both change what this card should show.
        refreshNotifyState();
        refreshNotifyVoices();
        refreshTrustSummary();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111113);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        header(root);
        statusCard(root);        switches(root);
        byokCard(root);
        defaultTierCard(root);
        voiceCard(root);
        notifyCard(root);
        section(root, "唤醒词");
        wakeWordCard(root);
        section(root, "电源滑动确认");
        powerPreview(root);
        diagCard(root);
        scopeCard(root);
        section(root, "快速上手");
        step(root, 1, "启用模块", "在 LSPosed / Vector 里勾选 Aicy Unlock，并确认模块已启用。");
        step(root, 2, "授权作用域", "点上方「申请 Aicy 作用域」，管理器确认一次即可。");
        step(root, 3, "重启 Aicy", "强行停止 Aicy 后重新打开；刚开机就装的话直接重启手机。");

        section(root, "使用说明");
        LinearLayout guideCard = cardContainer();
        infoRow(guideCard, "安装与验证", "Root、作用域、日志顺序", GUIDE_INSTALL);
        infoRow(guideCard, "配置自定义模型", "接口四元组、本地网络、默认档位", GUIDE_MODEL);
        infoRow(guideCard, "拦截逻辑", "什么时候切断魅族出口", GUIDE_INTERCEPT);
        infoRow(guideCard, "电源键与唤醒词", "QuickVoice、自定义唤醒词", GUIDE_WAKE);
        root.addView(guideCard);

        section(root, "常见问题");
        LinearLayout faqCard = cardContainer();
        questionRow(faqCard, "设置里没有「模型设置」",
                "13.0.56 上先打开本页的「临时打开模型设置页」，再进 Aicy 设置。若还是没有，"
              + "看下方「诊断」：没有 module loaded 说明模块没注入。");
        questionRow(faqCard, "日志里没有 AicyUnlock",
                "模块没注入。检查模块启用状态、作用域是否含 com.meizu.voiceassistant、"
              + "Zygisk 是否开启，以及是否强停过 Aicy。");
        questionRow(faqCard, "测试连接失败",
                "多半是地址或网络：用电脑局域网 IP 而不是 127.0.0.1；"
              + "确认电脑防火墙放行 1234 / 11434；模型名称必须是服务端 id。");
        questionRow(faqCard, "配了自定义，自带模型也不能用了",
                "预期行为：判定为自定义模式就会切断魅族对话出口。"
              + "把服务商改回任意魅族内置项，3 秒内自动切回 CLOUD。");
        questionRow(faqCard, "Aicy 升级后还生效吗",
                "核心逻辑按结构特征发现，不绑版本号；13.0.54 的精确类名只用作加速路径。"
              + "日志里的 probe MISSING 说明这一版换了包名或字段结构。");
        questionRow(faqCard, "默认档位没生效 / 还在自动",
                "目录在 Aicy 冷启动时读取：先在模块里选好「默认档位」，再强停 Aicy 重开。"
              + "选了「自定义」但没配模型会回落到轻量。");
        questionRow(faqCard, "选了「自定义」提示模型不存在",
                "「模型设置」里的模型名称还是空的。填真实模型 id，再强停重开 Aicy。");
        questionRow(faqCard, "想彻底回滚",
                "在管理器里停用模块并强停 Aicy 即恢复原样，卸载本 APK 也可以。"
              + "模块不修改 Aicy 的 APK 与数据。");
        questionRow(faqCard, "唤醒词想恢复系统页面",
                "关掉「自定义唤醒词」开关，再强停 Aicy。已录入的词由 Aicy 保存，不会丢。");
        root.addView(faqCard);

        section(root, "快捷操作");
        rowButtons(root);
        aboutCard(root);

        TextView footer = new TextView(this);
        footer.setText("钩子只在内存里运行 · 不修改 Aicy 的 APK 与数据");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        footer.setTextColor(0xFF6B7076);
        footer.setPadding(dp(4), dp(18), dp(4), 0);
        footer.setLineSpacing(dp(3), 1f);
        root.addView(footer);

        registerScopeService();
        handlePreviewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePreviewIntent(intent);
    }

    /** adb am start ... --ez preview_power true, so the sheet can be shown headless. */
    private void handlePreviewIntent(Intent intent) {
        if (intent == null) return;
        boolean power = intent.getBooleanExtra("preview_power", false);
        boolean reboot = intent.getBooleanExtra("preview_reboot", false);
        boolean dock = intent.getBooleanExtra("preview_dock", false);
        // Headless hook: adb am start ... --ez probe_stream true
        if (intent.getBooleanExtra("probe_stream", false)) {
            new Thread(() -> {
                String result;
                try {
                    result = StreamProbe.run(getApplicationContext());
                } catch (Throwable error) {
                    result = "probe 崩溃 " + error;
                }
                android.util.Log.i("AicyUnlock", "StreamProbe: " + result);
            }, "AicyStreamProbe").start();
        }
        if (!power && !reboot && !dock) return;
        final boolean asReboot = reboot;
        findViewById(android.R.id.content).post(() -> {
            if (dock) showDockPreview();
            else showPowerPreview(asReboot);
        });
    }

    // ---------------- dynamic scope ----------------

    private void scopeCard(LinearLayout root) {
        section(root, "作用域 · 动态申请");
        LinearLayout card = cardContainer();

        TextView t = new TextView(this);
        t.setText("不锁死作用域");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        card.addView(t);

        TextView body = new TextView(this);
        body.setText("模块不锁死作用域。点下面的按钮通过框架服务申请 "
                + AICY_PACKAGE + "，管理器确认一次即可；也可以在管理器里手动勾选。");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        body.setTextColor(TEXT);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);

        scopeState = new TextView(this);
        scopeState.setText("正在连接框架服务…");
        scopeState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        scopeState.setTextColor(MUTED);
        scopeState.setPadding(0, dp(10), 0, 0);
        card.addView(scopeState);

        TextView action = button("申请 Aicy 作用域", v -> requestScope(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(action, lp);

        root.addView(card);
    }

    private void registerScopeService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService bound) {
                    service = bound;
                    String info;
                    try {
                        info = "已连接框架：" + bound.getFrameworkName() + " "
                                + bound.getFrameworkVersion() + "（API " + bound.getApiVersion() + "）";
                    } catch (Throwable error) {
                        info = "已连接框架服务";
                    }
                    String text = info;
                    runOnUiThread(() -> {
                        if (scopeState != null) {
                            scopeState.setText(text);
                            scopeState.setTextColor(OK);
                        }
                    });
                    if (!autoRequested) {
                        autoRequested = true;
                        requestScope(true);
                    }
                }

                @Override
                public void onServiceDied(XposedService dead) {
                    service = null;
                    runOnUiThread(() -> {
                        if (scopeState != null) {
                            scopeState.setText("未连接框架服务：管理器没运行或框架不支持动态作用域，请手动在管理器里勾 Aicy。");
                            scopeState.setTextColor(WARN);
                        }
                    });
                }
            });
        } catch (Throwable error) {
            if (scopeState != null) {
                scopeState.setText("动态作用域不可用（" + error.getClass().getSimpleName()
                        + "），请手动在管理器里勾 Aicy。");
                scopeState.setTextColor(WARN);
            }
        }
    }

    private void requestScope(boolean silentIfGranted) {
        XposedService bound = service;
        if (bound == null) {
            if (!silentIfGranted) {
                Toast.makeText(this, "还没连上框架服务，先在管理器里手动勾一下作用域",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            List<String> current = bound.getScope();
            if (current != null && current.contains(PICKER_PACKAGE)
                    && current.contains(SVA_PACKAGE)) {
                if (scopeState != null) {
                    scopeState.setText("作用域已授予：" + AICY_PACKAGE + " + " + PICKER_PACKAGE
                            + " + " + SVA_PACKAGE);
                    scopeState.setTextColor(OK);
                }
                return;
            }
            bound.requestScope(
                    java.util.Arrays.asList(AICY_PACKAGE, PICKER_PACKAGE, SVA_PACKAGE),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(List<String> packages) {
                            runOnUiThread(() -> {
                                if (scopeState != null) {
                                    scopeState.setText("作用域已授予：" + AICY_PACKAGE + "，强停 Aicy 后重开");
                                    scopeState.setTextColor(OK);
                                }
                                Toast.makeText(MainActivity.this,
                                        "作用域已授予，记得强停 Aicy 和语音唤醒",
                                        Toast.LENGTH_LONG).show();
                            });
                        }

                        @Override
                        public void onScopeRequestFailed(String message) {
                            runOnUiThread(() -> {
                                if (scopeState != null) {
                                    scopeState.setText("申请失败：" + message + "（可手动在管理器里勾）");
                                    scopeState.setTextColor(WARN);
                                }
                            });
                        }
                    });
        } catch (Throwable error) {
            if (scopeState != null) {
                scopeState.setText("申请出错：" + error.getClass().getSimpleName()
                        + "，请手动在管理器里勾 Aicy。");
                scopeState.setTextColor(WARN);
            }
        }
    }

    // ---------------- building blocks ----------------

    private void header(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Aicy Unlock");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView github = new TextView(this);
        github.setText("GitHub ↗");
        github.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        github.setTextColor(0xFFC7D2FE);
        github.setGravity(Gravity.CENTER);
        github.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(18));
        chip.setColor(0xFF232326);
        chip.setStroke(dp(1), 0xFF3F3F52);
        github.setBackground(chip);
        github.setOnClickListener(v -> openUrl(GITHUB_URL));
        row.addView(github);

        root.addView(row);

        TextView sub = new TextView(this);
        sub.setText("LSPosed 模块 · 自定义模型 / 音色 / 通知播报");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);
    }

    private void statusCard(LinearLayout root) {
        LinearLayout card = cardContainer();
        String version;
        boolean installed;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(AICY_PACKAGE, 0);
            version = info.versionName;
            installed = true;
        } catch (Throwable ignored) {
            version = "未安装";
            installed = false;
        }
        statusRow(card, "作用域", AICY_PACKAGE, TEXT);
        statusRow(card, "本机 Aicy", version, installed ? OK : WARN);
        statusRow(card, "框架要求", "libxposed API 101+（Vector ≥2.1 / LSPosed 2.x）", TEXT);
        statusRow(card, "模块状态", "见下方「诊断」", MUTED);
        root.addView(card);
    }

    private void statusRow(LinearLayout parent, String key, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        k.setTextColor(MUTED);
        k.setWidth(dp(76));
        row.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setTextColor(valueColor);
        v.setLineSpacing(dp(2), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private void section(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ACCENT);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(4), dp(18), 0, dp(8));
        root.addView(tv);
    }

    // ------------------------------------------------------------------
    // Switches (read by the hooks through ConfigProvider)
    // ------------------------------------------------------------------

    private void switches(LinearLayout root) {
        section(root, "开关");
        LinearLayout card = cardContainer();

        addSwitch(card, ConfigProvider.KEY_MASTER, "总开关",
                "关掉后所有钩子空转：不拦网络、不改目录。");
        addSwitch(card, ConfigProvider.KEY_LOG, "拦截日志 / 遥测上传",
                "断开 uxip / savior，日志上传和打点发不出去。");
        addSwitch(card, ConfigProvider.KEY_CHAT, "拦截对话出口（仅自定义模型时）",
                "仅在自定义模型生效时切断魅族对话出口。");
        addSwitch(card, ConfigProvider.KEY_CATALOG, "模型目录改写（默认轻量 + 自定义档位）",
                "默认档位改为轻量，并追加「自定义」档。");
        addSwitch(card, ConfigProvider.KEY_ONLY_CUSTOM, "只有「自定义」档位走自建 API（实验）",
                "选其他档位时对 Aicy 隐藏 apiUrl，回落到魅族云；此时「模型设置」地址栏会显示为空。");

        root.addView(card);
    }

    // ------------------------------------------------------------------
    // 唤醒词
    // ------------------------------------------------------------------

    private void wakeWordCard(LinearLayout root) {
        LinearLayout card = cardContainer();
        addSwitch(card, ConfigProvider.KEY_WAKE_WORD,
                "自定义唤醒词（Aicy 设置 → 语音唤醒）", null);
        addSwitch(card, ConfigProvider.KEY_WAKE_TONE,
                "唤醒提示音", "语音唤醒和长按电源键唤起 Aicy 时，播一次 Aicy 自带的「我在听」提示音（12.5 那样）。");
        addSwitch(card, ConfigProvider.KEY_TASK_ROBOT,
                "任务机器人联动（打开X搜索Y）", null);

        LinearLayout.LayoutParams yes = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        yes.topMargin = dp(10);
        card.addView(button("打开原子能力设置", v -> openAbilitySettings()), yes);

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        full.topMargin = dp(10);
        card.addView(button("添加语音作用域", v -> requestScope(false)), full);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("重启语音", v -> restart("语音唤醒引擎", false, SVA_PACKAGE)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = dp(10);
        row.addView(button("重启 Aicy 助手",
                v -> restart("Aicy 助手", true, AICY_PACKAGE)), right);

        LinearLayout.LayoutParams restarts = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        restarts.topMargin = dp(10);
        card.addView(row, restarts);

        root.addView(card);
    }

    /**
     * Force-stops hooked processes so the framework loads the module into them
     * again.
     *
     * <p>Both targets run with privileges that let {@code am force-stop} answer
     * failure even after it killed the process, so the pid decides: the same
     * pid before and after is the only real sign that nothing restarted.
     *
     * @param relaunch start Aicy's own launcher activity afterwards, so the
     *                 assistant comes back on its own; the wake engine has
     *                 nothing to launch and waits for the next wake event.
     */
    private void restart(String name, boolean relaunch, String... packages) {
        Toast.makeText(this, "正在重启" + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String> stuck = new ArrayList<>();
            for (String pkg : packages) {
                String before = pidOf(pkg);
                dev.loong.aicyunlock.power.PowerActions.shell("am force-stop " + pkg);
                if (!before.isEmpty() && before.equals(pidOf(pkg))) stuck.add(pkg);
            }
            String result = stuck.isEmpty()
                    ? name + (relaunch ? "已重启" : "已停止，下次唤醒事件里重新拉起")
                    : "仍在运行：" + String.join("、", stuck);
            runOnUiThread(() -> {
                if (stuck.isEmpty() && relaunch) relaunch(AICY_PACKAGE);
                Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
            });
        }, "pkg-restart").start();
    }

    private void relaunch(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) startActivity(launch);
        } catch (Throwable ignored) {
            // Aicy not installed or the launcher refused: the stop already happened
        }
    }

    /** 原子能力 → 应用管理；任务机器人的 Aicy 助手授权就在这里。 */
    private void openAbilitySettings() {
        try {
            startActivity(new Intent("flyme.intent.action.ENHANCED_SERVICES_MANAGER"));
        } catch (Throwable error) {
            Toast.makeText(this, "无法打开原子能力设置", Toast.LENGTH_LONG).show();
        }
    }

    private static String pidOf(String pkg) {
        String out = dev.loong.aicyunlock.power.PowerActions.shell("pidof " + pkg);
        return out == null ? "" : out.trim();
    }

    // ------------------------------------------------------------------
    // 默认档位（开新会话时落在哪一项）
    // ------------------------------------------------------------------

    /**
     * 自定义模型（BYOK）：Aicy 13.0.56 把「模型设置」入口删了，而它的模型配置
     * 现在走独立的 model_config_prefs + 协程流，外部注入不可靠。所以这里不再
     * 自带输入框，只留一个开关把 Aicy 自己的那页临时露出来，让 Aicy 自己写配置。
     */
    private void byokCard(LinearLayout root) {
        section(root, "自定义模型");
        LinearLayout card = cardContainer();

        TextView title = new TextView(this);
        title.setText("用你自己的 OpenAI 兼容接口");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);

        TextView note = new TextView(this);
        note.setText("13.0.56 删掉了「模型设置」入口，但页面还在。打开开关后，"
                + "设置里的「设备」分组会变成 Aicy 原生的「模型设置」页；"
                + "填好地址 / Key / 模型名并保存，再回来关掉开关即可。\n"
                + "地址填电脑的局域网 IP（如 192.168.1.21:1234/v1），不是 127.0.0.1。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        note.setTextColor(MUTED);
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(0, dp(8), 0, dp(6));
        card.addView(note);

        addSwitch(card, ConfigProvider.KEY_REVEAL_MODEL, "临时打开 Aicy 的「模型设置」页",
                "打开后，设置里的「设备」分组会变成 Aicy 原生的「模型设置」页。"
                + "填完保存，再回来把开关关掉即可恢复。\n"
                + "切换后重进一次设置页生效（配置有 5 秒缓存）。");

        root.addView(card);
    }

    private void defaultTierCard(LinearLayout root) {
        section(root, "默认档位（开新会话时）");
        LinearLayout card = cardContainer();
        SharedPreferences sp = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
        selectedDefaultTier = sp.getString(ConfigProvider.KEY_DEFAULT_TIER, CatalogPatch.FREE_TIER_ID);

        TextView title = new TextView(this);
        title.setText("新会话默认落在哪个档位");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);

        TextView note = new TextView(this);
        note.setText("模块会改写模型目录的 default_tier_id。"
                + "选「自定义」前先配好自定义模型；改完强停 Aicy 生效。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        note.setTextColor(MUTED);
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(0, dp(8), 0, dp(6));
        card.addView(note);

        defaultTierChips = new LinearLayout(this);
        defaultTierChips.setOrientation(LinearLayout.HORIZONTAL);
        defaultTierChips.setPadding(0, dp(2), 0, dp(6));
        String[] labels = {"自动", "深度", "轻量", "自定义"};
        String[] ids = {CatalogPatch.AUTO_TIER_ID, CatalogPatch.DEEP_TIER_ID,
                CatalogPatch.FREE_TIER_ID, CatalogPatch.CUSTOM_TIER_ID};
        for (int i = 0; i < ids.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            defaultTierChips.addView(tierChip(labels[i], ids[i]), lp);
        }
        card.addView(defaultTierChips);

        root.addView(card);
    }

    private TextView tierChip(String label, String id) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTag(id);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(4), dp(9), dp(4), dp(9));
        tv.setOnClickListener(v -> {
            selectedDefaultTier = (String) v.getTag();
            getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE).edit()
                    .putString(ConfigProvider.KEY_DEFAULT_TIER, selectedDefaultTier).apply();
            refreshDefaultTierChips();
            Toast.makeText(this, "已设为「" + label + "」，强停 Aicy 后生效",
                    Toast.LENGTH_SHORT).show();
        });
        styleChip(tv, id.equals(selectedDefaultTier));
        return tv;
    }

    private void refreshDefaultTierChips() {
        if (defaultTierChips == null) return;
        for (int i = 0; i < defaultTierChips.getChildCount(); i++) {
            View child = defaultTierChips.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                styleChip((TextView) child, tag.equals(selectedDefaultTier));
            }
        }
    }

    private void addSwitch(LinearLayout card, String key, String title, String desc) {
        addSwitch(card, key, title, desc, true);
    }

    private void addSwitch(LinearLayout card, String key, String title, String desc,
                           boolean defaultValue) {
        SharedPreferences sp = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
        boolean on = sp.getBoolean(key, defaultValue);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textsLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        texts.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(desc);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            b.setTextColor(MUTED);
            b.setLineSpacing(dp(2), 1f);
            b.setPadding(0, dp(3), dp(8), 0);
            texts.addView(b);
        }

        row.addView(texts);

        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(on);
        tintSwitch(toggle, on);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            tintSwitch(toggle, isChecked);
            getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(key, isChecked).apply();
        });
        row.addView(toggle);

        card.addView(row);
    }

    /** Thumb and track tint per state, so on/off is readable on the dark page. */
    private void tintSwitch(android.widget.Switch sw, boolean on) {
        int thumb = on ? 0xFF34C759 : 0xFF8A8E99;
        int track = on ? 0x8034C759 : 0x33FFFFFF;
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(thumb));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(track));
    }

    // ------------------------------------------------------------------
    // 音色 · MiMo
    // ------------------------------------------------------------------

private void voiceCard(LinearLayout root) {
        section(root, "音色 · MiMo 语音合成");
        LinearLayout card = cardContainer();

        SharedPreferences sp = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);

        TextView title = new TextView(this);
        title.setText("Aicy 声音列表里的自定义音色");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);

        TextView note = new TextView(this);
        note.setText("每条音色会出现在 Aicy 的「声音」列表里，选中后由本机调用 MiMo 合成，"
                + "不经过魅族服务器。支持预置、文字描述、音频复刻。\n"
                + "Key 只保存在本机，模块不收费。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        note.setTextColor(MUTED);
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(0, dp(8), 0, dp(6));
        card.addView(note);

        infoRow(card, "MiMo 接口、模型与计费", "接口地址、三种模型、邀请码", GUIDE_MIMO);

        card.addView(label("API 地址（所有音色共用）"));
        mimoEndpointInput = new EditText(this);
        mimoEndpointInput.setHint(MiMoTts.DEFAULT_ENDPOINT);
        mimoEndpointInput.setText(sp.getString(ConfigProvider.KEY_TTS_ENDPOINT,
                MiMoTts.DEFAULT_ENDPOINT));
        mimoEndpointInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mimoEndpointInput.setTextColor(TEXT);
        mimoEndpointInput.setHintTextColor(0xFF6B7076);
        mimoEndpointInput.setSingleLine(true);
        card.addView(mimoEndpointInput);

        card.addView(label("API Key（所有音色共用）"));
        mimoKeyInput = new EditText(this);
        mimoKeyInput.setHint("MiMo 控制台创建的 API Key");
        mimoKeyInput.setText(SecretStore.open(this, sp.getString(ConfigProvider.KEY_MIMO_KEY, "")));
        mimoKeyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mimoKeyInput.setTextColor(TEXT);
        mimoKeyInput.setHintTextColor(0xFF6B7076);
        mimoKeyInput.setSingleLine(true);
        card.addView(mimoKeyInput);

        card.addView(label("模型名称（可选：统一替换所有音色）"));
        ttsModelInput = new EditText(this);
        ttsModelInput.setHint("留空 = 每条音色用自己带的模型");
        ttsModelInput.setText(sp.getString(ConfigProvider.KEY_TTS_MODEL, ""));
        ttsModelInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ttsModelInput.setTextColor(TEXT);
        ttsModelInput.setHintTextColor(0xFF6B7076);
        ttsModelInput.setSingleLine(true);
        card.addView(ttsModelInput);

        TextView saveTtsModel = button("保存模型名称（不验证接口）", v -> saveTtsModel());
        LinearLayout.LayoutParams stmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stmLp.topMargin = dp(6);
        card.addView(saveTtsModel, stmLp);

        TextView saveKey = button("验证并保存接口与 Key", v -> saveKey());
        LinearLayout.LayoutParams saveKeyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveKeyLp.topMargin = dp(8);
        card.addView(saveKey, saveKeyLp);

        // Registration / console entry point. The referral code rides in the URL,
        // so the user does not have to type it.
        TextView openPlatform = button("打开 MiMo 开放平台",
                v -> openUrl(MIMO_PLATFORM_REF));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(6);
        card.addView(openPlatform, openLp);

        TextView invite = new TextView(this);
        invite.setText("还没 Key？邀请码 CCZX36，双方各得 ¥10 体验金，按钮链接已带码。");
        invite.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        invite.setTextColor(0xFF6B7076);
        invite.setPadding(0, dp(6), 0, dp(10));
        card.addView(invite);

        voiceState = new TextView(this);
        boolean hasKey = !sp.getString(ConfigProvider.KEY_MIMO_KEY, "").isEmpty();
        voiceState.setText(hasKey ? "Key 已加密保存" : "Key 还没保存");
        voiceState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        voiceState.setTextColor(MUTED);
        voiceState.setPadding(0, dp(6), 0, dp(4));
        card.addView(voiceState);

        if (hasKey) {
            TextView console = button("查看用量与账单", v -> openUrl(MIMO_CONSOLE));
            LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            card.addView(console, consoleLp);
        }

        // Timbre rows and the editor both live here; whichever is showing is
        // rebuilt from scratch on every change.
        voiceListHost = new LinearLayout(this);
        voiceListHost.setOrientation(LinearLayout.VERTICAL);
        card.addView(voiceListHost);

        root.addView(card);
        renderVoiceList();
    }

    // ------------------------------------------------------------------
    // 通知播报
    // ------------------------------------------------------------------

    private void notifyCard(LinearLayout root) {
        section(root, "通知播报");
        LinearLayout card = cardContainer();

        notifyState = new TextView(this);
        notifyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        notifyState.setPadding(0, dp(2), 0, dp(6));
        card.addView(notifyState);

        notifyGrantButton = button("", v -> openNotificationAccess());
        LinearLayout.LayoutParams grantLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        grantLp.bottomMargin = dp(6);
        card.addView(notifyGrantButton, grantLp);

        addSwitch(card, ConfigProvider.KEY_NOTIFY_ENABLE,
                "启用通知播报", null);
        addSwitch(card, ConfigProvider.KEY_NOTIFY_SCREEN_OFF,
                "仅息屏时播报", null);
        addSwitch(card, ConfigProvider.KEY_NOTIFY_HEADSET,
                "仅连接信任的耳机时播报", null);
        notifyAppsRow(card);
        trustedHeadsetsRow(card);

        card.addView(label("播报音色"));
        android.widget.HorizontalScrollView scroller =
                new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        notifyVoiceHost = new LinearLayout(this);
        notifyVoiceHost.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(notifyVoiceHost);
        card.addView(scroller);

        TextView test = button("试听播报音色", v -> testAnnouncement());
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testLp.topMargin = dp(8);
        card.addView(test, testLp);

        root.addView(card);
        refreshNotifyState();
        refreshNotifyVoices();
    }

    /** Title, live state and "管理" for the per-app allow-list. */
    private void notifyAppsRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setOnClickListener(v -> showNotifyApps());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("播报应用");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        texts.addView(title);

        notifyAppsSummary = new TextView(this);
        notifyAppsSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        notifyAppsSummary.setTextColor(MUTED);
        notifyAppsSummary.setSingleLine(true);
        notifyAppsSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        notifyAppsSummary.setPadding(0, dp(3), dp(8), 0);
        texts.addView(notifyAppsSummary);

        TextView manage = new TextView(this);
        manage.setText("管理 ›");
        manage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        manage.setTextColor(ACCENT);
        manage.setPadding(dp(8), dp(6), 0, dp(6));
        manage.setOnClickListener(v -> showNotifyApps());
        row.addView(manage);

        card.addView(row);
        refreshNotifyAppsSummary();
    }

    private void refreshNotifyAppsSummary() {
        if (notifyAppsSummary == null) return;
        List<String> apps = NotifyApps.load(this);
        if (apps.isEmpty()) {
            notifyAppsSummary.setText("未选择，播报全部应用");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < apps.size() && i < 2; i++) {
            if (i > 0) names.append("、");
            names.append(NotifyAnnouncer.appLabel(this, apps.get(i)));
        }
        if (apps.size() > 2) names.append(" 等");
        notifyAppsSummary.setText("已选 " + apps.size() + " 个：" + names);
    }

    /** One launcher entry; apps without a launcher are not notification sources. */
    private static final class AppChoice {
        final String packageName;
        final String label;
        final Drawable icon;

        AppChoice(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private void showNotifyApps() {
        List<AppChoice> apps = loadLaunchableApps();
        Set<String> selected = new LinkedHashSet<>(NotifyApps.load(this));

        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(dp(20), dp(4), dp(20), 0);

        EditText search = new EditText(this);
        search.setHint("搜索应用");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        host.addView(search);

        TextView selectedCount = new TextView(this);
        selectedCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        selectedCount.setTextColor(MUTED);
        selectedCount.setPadding(0, dp(2), 0, dp(6));
        host.addView(selectedCount);
        updateNotifyAppsCount(selectedCount, selected);

        LinearLayout listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listHost);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(dp(360), Math.round(screenHeight * 0.5f));
        host.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight));

        Runnable render = () -> renderNotifyAppList(listHost, apps,
                search.getText().toString(), selected, selectedCount);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render.run();
            }
        });
        render.run();

        new android.app.AlertDialog.Builder(this)
                .setTitle("播报应用")
                .setView(host)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    List<String> chosen = new ArrayList<>(selected);
                    NotifyApps.save(this, chosen);
                    refreshNotifyAppsSummary();
                    Toast.makeText(this, chosen.isEmpty()
                                    ? "已清空，播报全部应用"
                                    : "已选 " + chosen.size() + " 个应用",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private List<AppChoice> loadLaunchableApps() {
        List<AppChoice> out = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName())
                        || pkg.equals(NotifyAnnouncer.AICY_PACKAGE)) {
                    continue;
                }
                if (!seen.add(pkg)) continue;
                CharSequence raw = info.loadLabel(pm);
                String label = raw == null ? "" : raw.toString().trim();
                if (label.isEmpty()) label = pkg;
                Drawable icon = null;
                try {
                    icon = info.loadIcon(pm);
                } catch (Throwable ignored) {
                    // a missing icon must not hide the app
                }
                out.add(new AppChoice(pkg, label, icon));
            }
        } catch (Throwable ignored) {
            // treat as "no apps visible"
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (left, right) -> collator.compare(left.label, right.label));
        return out;
    }

    private void renderNotifyAppList(LinearLayout listHost, List<AppChoice> apps,
                                     String query, Set<String> selected,
                                     TextView selectedCount) {
        listHost.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AppChoice app : apps) {
            if (!needle.isEmpty()
                    && !app.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown++;
            listHost.addView(notifyAppRow(app, selected, selectedCount));
        }
        if (shown == 0) {
            listHost.addView(dialogNote(apps.isEmpty()
                    ? "没有可选择的启动器应用" : "没有匹配的应用"));
        }
    }

    private LinearLayout notifyAppRow(AppChoice app, Set<String> selected,
                                      TextView selectedCount) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setClickable(true);

        ImageView icon = new ImageView(this);
        if (app.icon != null) icon.setImageDrawable(app.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView name = new TextView(this);
        name.setText(app.label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(12);
        nameLp.rightMargin = dp(8);
        row.addView(name, nameLp);

        CheckBox box = new CheckBox(this);
        box.setChecked(selected.contains(app.packageName));
        box.setClickable(false);
        box.setFocusable(false);
        row.addView(box);

        row.setOnClickListener(v -> {
            if (!selected.remove(app.packageName)) selected.add(app.packageName);
            box.setChecked(selected.contains(app.packageName));
            updateNotifyAppsCount(selectedCount, selected);
        });
        return row;
    }

    private void updateNotifyAppsCount(TextView view, Set<String> selected) {
        if (view == null) return;
        view.setText(selected.isEmpty()
                ? "未选择 · 播报全部应用"
                : "已选 " + selected.size() + " 个应用");
    }

    /** Title, live state and "管理" for the trusted-headset allow-list. */
    private void trustedHeadsetsRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setOnClickListener(v -> showTrustedHeadsets());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("信任的耳机");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        texts.addView(title);

        notifyTrustSummary = new TextView(this);
        notifyTrustSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        notifyTrustSummary.setTextColor(MUTED);
        notifyTrustSummary.setSingleLine(true);
        notifyTrustSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        notifyTrustSummary.setPadding(0, dp(3), dp(8), 0);
        texts.addView(notifyTrustSummary);

        TextView manage = new TextView(this);
        manage.setText("管理 ›");
        manage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        manage.setTextColor(ACCENT);
        manage.setPadding(dp(8), dp(6), 0, dp(6));
        manage.setOnClickListener(v -> showTrustedHeadsets());
        row.addView(manage);

        card.addView(row);
        refreshTrustSummary();
    }

    private void refreshTrustSummary() {
        if (notifyTrustSummary == null) return;
        List<TrustedHeadsets.Entry> trusted = TrustedHeadsets.load(this);
        if (trusted.isEmpty()) {
            notifyTrustSummary.setText("未信任耳机，连接耳机时不会播报");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < trusted.size() && i < 2; i++) {
            if (i > 0) names.append("、");
            names.append(trusted.get(i).displayName());
        }
        if (trusted.size() > 2) names.append(" 等");
        notifyTrustSummary.setText("已信任 " + trusted.size() + " 个：" + names);
    }

    private void showTrustedHeadsets() {
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(dp(20), dp(6), dp(20), dp(4));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(host);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("信任的耳机")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        renderTrustedHeadsets(host);
        dialog.show();
    }

    private void renderTrustedHeadsets(LinearLayout host) {
        host.removeAllViews();
        List<TrustedHeadsets.Entry> trusted = TrustedHeadsets.load(this);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        List<AudioDeviceInfo> devices = TrustedHeadsets.connectedHeadsets(audio);

        Set<String> connectedTrusted = new HashSet<>();
        host.addView(dialogHeading("当前连接的耳机"));
        if (devices.isEmpty()) {
            host.addView(dialogNote("当前没有连接耳机"));
        } else {
            Set<String> seen = new HashSet<>();
            for (AudioDeviceInfo device : devices) {
                TrustedHeadsets.Entry entry = TrustedHeadsets.from(device);
                if (!seen.add(entry.id)) continue;
                TrustedHeadsets.Entry match = TrustedHeadsets.find(trusted, entry);
                boolean on = match != null;
                if (on) connectedTrusted.add(match.id);
                LinearLayout row = dialogRow(entry.displayName(),
                        entry.kindLabel() + (on ? " · 已信任" : ""),
                        on ? "移除" : "信任",
                        on ? 0xFFF87171 : ACCENT,
                        v -> toggleTrustedHeadset(entry, host));
                host.addView(row);
            }
        }

        host.addView(dialogHeading("已信任"));
        if (trusted.isEmpty()) {
            host.addView(dialogNote("还没有信任任何耳机，连接耳机时不会播报。先点上方「信任」添加。"));
        } else {
            for (TrustedHeadsets.Entry entry : trusted) {
                boolean connected = connectedTrusted.contains(entry.id);
                LinearLayout row = dialogRow(entry.displayName(),
                        entry.kindLabel() + (connected ? " · 已连接" : " · 未连接"),
                        "移除", 0xFFF87171,
                        v -> confirmRemoveTrusted(entry, host));
                host.addView(row);
            }
        }
    }

    private void toggleTrustedHeadset(TrustedHeadsets.Entry device, LinearLayout host) {
        List<TrustedHeadsets.Entry> trusted = TrustedHeadsets.load(this);
        TrustedHeadsets.Entry found = TrustedHeadsets.find(trusted, device);
        if (found != null) {
            // The trusted list is the security boundary; never drop an entry on
            // a single tap, not even from the connected-device row.
            confirmRemoveTrusted(found, host);
            return;
        }
        if (trusted.size() >= TrustedHeadsets.MAX) {
            Toast.makeText(this, "最多信任 " + TrustedHeadsets.MAX + " 个耳机",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        trusted.add(device);
        TrustedHeadsets.save(this, trusted);
        Toast.makeText(this, "已信任「" + device.displayName() + "」，重启后仍保留",
                Toast.LENGTH_SHORT).show();
        refreshTrustSummary();
        renderTrustedHeadsets(host);
    }

    private void confirmRemoveTrusted(TrustedHeadsets.Entry entry, LinearLayout host) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("不再信任「" + entry.displayName() + "」？")
                .setMessage("移除后，连接这个耳机时通知不会再自动播报。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    List<TrustedHeadsets.Entry> current = TrustedHeadsets.load(this);
                    TrustedHeadsets.Entry found = TrustedHeadsets.find(current, entry);
                    if (found != null) current.remove(found);
                    TrustedHeadsets.save(this, current);
                    refreshTrustSummary();
                    renderTrustedHeadsets(host);
                })
                .show();
    }

    private TextView dialogHeading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(MUTED);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private TextView dialogNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(MUTED);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(2), 0, dp(8));
        return tv;
    }

    private LinearLayout dialogRow(String title, String subtitle,
                                   String action, int actionColor,
                                   View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTextColor(TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s.setTextColor(MUTED);
            s.setPadding(0, dp(2), dp(8), 0);
            texts.addView(s);
        }

        TextView a = new TextView(this);
        a.setText(action);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        a.setTextColor(actionColor);
        a.setPadding(dp(12), dp(10), dp(4), dp(10));
        // Only the action label is tappable: tapping a row to look at it must
        // not silently change the trust list.
        if (onAction != null) a.setOnClickListener(onAction);
        row.addView(a);
        return row;
    }

    private void refreshNotifyState() {
        if (notifyState == null) return;
        boolean aicy = NotifyAnnouncer.hasAicyAccess(this);
        boolean ready = NotifyAnnouncer.aicyReady(this);
        boolean module = NotifyAnnouncer.hasModuleAccess(this);
        if (aicy && ready) {
            notifyState.setText("通知使用权：Aicy 已授权，播报在 Aicy 内运行");
            notifyState.setTextColor(OK);
            if (notifyGrantButton != null) {
                notifyGrantButton.setText("重新打开 Aicy 通知使用权");
            }
        } else if (aicy) {
            // Aicy owns the permission, so the module stays silent even while
            // the new hook has not loaded in the running process yet.
            notifyState.setText("通知使用权：Aicy 已授权，等待 Aicy 重启加载播报");
            notifyState.setTextColor(WARN);
            if (notifyGrantButton != null) {
                notifyGrantButton.setText("重新打开 Aicy 通知使用权");
            }
        } else if (module) {
            notifyState.setText("通知使用权：Aicy 未授权，当前由模块代播");
            notifyState.setTextColor(WARN);
            if (notifyGrantButton != null) {
                notifyGrantButton.setText("给 Aicy 开启通知使用权");
            }
        } else {
            notifyState.setText("通知使用权：未授权，播报不会启动");
            notifyState.setTextColor(WARN);
            if (notifyGrantButton != null) {
                notifyGrantButton.setText("去开启通知使用权");
            }
        }
    }

    private void refreshNotifyVoices() {
        if (notifyVoiceHost == null) return;
        notifyVoiceHost.removeAllViews();
        SharedPreferences sp = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
        String selected = sp.getString(ConfigProvider.KEY_NOTIFY_VOICE, "");
        addNotifyChip("默认音色", "", selected);
        for (VoiceProfile profile : VoiceStore.load(this)) {
            addNotifyChip(profile.name, profile.id, selected);
        }
    }

    private void addNotifyChip(String label, String id, String selected) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setOnClickListener(v -> {
            getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE).edit()
                    .putString(ConfigProvider.KEY_NOTIFY_VOICE, id).apply();
            refreshNotifyVoices();
            Toast.makeText(this, "播报音色：" + label, Toast.LENGTH_SHORT).show();
        });
        styleChip(chip, id.equals(selected));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        notifyVoiceHost.addView(chip, lp);
    }

    private void openNotificationAccess() {
        // Prefer Aicy's own permission detail page, so the user does not have
        // to hunt for it in the full list. Some OEM builds ignore the extra and
        // fall back to the list themselves; we do the same on failure.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                Intent detail = new Intent(
                        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                detail.putExtra(android.provider.Settings
                                .EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        // Settings reads this as a StringExtra and unflattens it
                        // itself. Passing a ComponentName makes getStringExtra
                        // return null and Flyme's NotificationAccessDetails then
                        // crashes on the missing component.
                        new android.content.ComponentName(NotifyAnnouncer.AICY_PACKAGE,
                                NotifyAnnouncer.AICY_SERVICE).flattenToString());
                startActivity(detail);
                return;
            } catch (Throwable ignored) {
                // fall through to the general list
            }
        }
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable error) {
            Toast.makeText(this, "打不开通知使用权设置："
                    + error.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void testAnnouncement() {
        SharedPreferences sp = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
        String key = SecretStore.open(this, sp.getString(ConfigProvider.KEY_MIMO_KEY, ""));
        if (key.isEmpty()) {
            Toast.makeText(this, "先在上面的音色卡片里保存 API 地址和 Key",
                    Toast.LENGTH_LONG).show();
            return;
        }
        VoiceProfile profile = NotifyAnnouncer.pickProfile(VoiceStore.load(this),
                sp.getString(ConfigProvider.KEY_NOTIFY_VOICE, ""));
        Toast.makeText(this, "正在合成试听…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                SpeechPlayer.play(MainActivity.this, profile,
                        "通知播报已就绪，收到消息会这样念给你听。");
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "试听完成", Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                String reason = error.getMessage();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = error.getClass().getSimpleName();
                }
                final String shown = reason;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "试听失败：" + shown, Toast.LENGTH_LONG).show());
            }
        }, "AicyNotifyTest").start();
    }

    // ------------------------------------------------------------------
    // 音色列表 · 列表视图
    // ------------------------------------------------------------------

    private void renderVoiceList() {
        if (voiceListHost == null) return;
        voiceListHost.removeAllViews();
        editing = null;
        nameInput = null;
        descInput = null;
        editorClipState = null;

        List<VoiceProfile> list = VoiceStore.load(this);
        TextView head = new TextView(this);
        head.setText("我的音色（" + list.size() + "/" + VoiceProfile.MAX_PROFILES + "）");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        head.setTextColor(MUTED);
        head.setPadding(0, dp(4), 0, dp(6));
        voiceListHost.addView(head);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有音色。点下面的「＋ 新建音色」创建第一条，"
                    + "填好描述后它就会出现在 Aicy 的「声音」列表里。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            empty.setTextColor(MUTED);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(0, dp(4), 0, dp(10));
            voiceListHost.addView(empty);
        } else {
            for (final VoiceProfile profile : list) {
                voiceListHost.addView(voiceRow(profile));
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = button("＋ 新建音色", v -> addVoice());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        actions.addView(add, addLp);
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        exportLp.leftMargin = dp(8);
        actions.addView(button("导出", v -> exportBundle()), exportLp);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        importLp.leftMargin = dp(8);
        actions.addView(button("导入", v -> importBundle()), importLp);
        voiceListHost.addView(actions);

        TextView shareHint = new TextView(this);
        shareHint.setText("导出的 .mimotts.json 含音色描述与参考音频，不含 API Key——"
                + "对方导入后用自己的 Key 即可用。");
        shareHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        shareHint.setTextColor(0xFF6B7076);
        shareHint.setLineSpacing(dp(3), 1f);
        shareHint.setPadding(0, dp(8), 0, 0);
        voiceListHost.addView(shareHint);
    }

    /** One tappable row: name, mode, readiness. */
    private View voiceRow(final VoiceProfile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        // Avatar swatch: the injected row reuses a stock avatar template, so a
        // colour swatch here is the honest preview of "which template is picked".
        TextView swatch = new TextView(this);
        swatch.setText(String.valueOf(profile.name == null || profile.name.isEmpty()
                ? "?" : profile.name.charAt(0)));
        swatch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        swatch.setTextColor(0xFFEDEBFF);
        swatch.setGravity(Gravity.CENTER);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(AVATAR_COLORS[Math.floorMod(profile.avatar, AVATAR_COLORS.length)]);
        swatch.setBackground(sd);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        swatchLp.rightMargin = dp(10);
        row.addView(swatch, swatchLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, textLp);

        TextView name = new TextView(this);
        name.setText(profile.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        name.setTextColor(TEXT);
        text.addView(name);

        TextView sub = new TextView(this);
        String detail = profile.modelLabel();
        if (!profile.ready()) detail += " · " + profile.missingReason();
        sub.setText(detail);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setTextColor(profile.ready() ? MUTED : WARN);
        sub.setPadding(0, dp(2), 0, 0);
        text.addView(sub);

        row.setOnClickListener(v -> openEditor(profile.id));
        return row;
    }

    // ------------------------------------------------------------------
    // 音色列表 · 编辑器
    // ------------------------------------------------------------------

    private void openEditor(String speakerId) {
        List<VoiceProfile> list = VoiceStore.load(this);
        VoiceProfile found = null;
        for (VoiceProfile p : list) {
            if (p.id.equals(speakerId)) {
                found = p;
                break;
            }
        }
        if (found == null) {
            Toast.makeText(this, "这条音色已不存在", Toast.LENGTH_SHORT).show();
            renderVoiceList();
            return;
        }
        editing = found;
        renderEditor(found);
    }

    private void renderEditor(final VoiceProfile profile) {
        if (voiceListHost == null) return;
        voiceListHost.removeAllViews();

        TextView head = new TextView(this);
        head.setText("编辑音色");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(0xFFFFFFFF);
        head.setPadding(0, dp(6), 0, dp(2));
        voiceListHost.addView(head);

        voiceListHost.addView(label("名称（显示在 Aicy 声音列表里）"));
        nameInput = new EditText(this);
        nameInput.setText(profile.name);
        nameInput.setHint("例：小玖 / 少女音 / 新闻播报");
        nameInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        nameInput.setTextColor(TEXT);
        nameInput.setHintTextColor(0xFF6B7076);
        nameInput.setSingleLine(true);
        voiceListHost.addView(nameInput);

        voiceListHost.addView(label("类型"));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(2), 0, dp(4));
        chips.addView(modelChip("文字造音色", MiMoTts.MODEL_DESIGN),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(6);
        midLp.rightMargin = dp(6);
        chips.addView(modelChip("音频复刻", MiMoTts.MODEL_CLONE), midLp);
        chips.addView(modelChip("预置音色", MiMoTts.MODEL_PRESET),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        voiceListHost.addView(chips);

        // The two fields below only matter for some models, but keeping them
        // visible avoids a mode switch wiping what the user just typed.
        voiceListHost.addView(label("音色描述（造音色必填；预置音色可当风格指令）"));
        descInput = new EditText(this);
        descInput.setHint("例：二十多岁的女生，声音清亮偏甜，语速稍快，像在跟朋友聊天");
        descInput.setText(profile.desc);
        descInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        descInput.setTextColor(TEXT);
        descInput.setHintTextColor(0xFF6B7076);
        descInput.setMinLines(2);
        descInput.setGravity(Gravity.TOP);
        voiceListHost.addView(descInput);

        voiceListHost.addView(label("头像（挑一个内置样式）"));
        LinearLayout avatars = new LinearLayout(this);
        avatars.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < AVATAR_COLORS.length; i++) {
            final int index = i;
            TextView swatch = new TextView(this);
            swatch.setText(String.valueOf(i + 1));
            swatch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextColor(i == profile.avatar ? 0xFFFFFFFF : MUTED);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(AVATAR_COLORS[i]);
            if (i == profile.avatar) bg.setStroke(dp(2), ACCENT);
            swatch.setBackground(bg);
            swatch.setOnClickListener(v -> {
                VoiceStore.setAvatar(MainActivity.this, editing.id, index);
                editing = editing.withAvatar(index);
                renderEditor(editing);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(8);
            avatars.addView(swatch, lp);
        }
        voiceListHost.addView(avatars);

        TextView avatarNote = new TextView(this);
        avatarNote.setText("Aicy 的音色行头像是编译进它包里的固定资源，"
                + "没法塞任意图片，所以这里是在原生头像里挑一个模板。");
        avatarNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        avatarNote.setTextColor(0xFF6B7076);
        avatarNote.setLineSpacing(dp(2), 1f);
        avatarNote.setPadding(0, dp(6), 0, dp(4));
        voiceListHost.addView(avatarNote);

        voiceListHost.addView(label("参考音频（复刻用，mp3 / wav）"));
        editorClipState = new TextView(this);
        String clip = VoiceStore.clip(this, profile.sampleKey);
        editorClipState.setText(clip.isEmpty() ? "未选择"
                : "已选择 · 约 " + (clip.length() * 3 / 4 / 1024) + " KB");
        editorClipState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        editorClipState.setTextColor(MUTED);
        editorClipState.setPadding(0, dp(2), 0, dp(8));
        voiceListHost.addView(editorClipState);

        LinearLayout clipActions = new LinearLayout(this);
        clipActions.setOrientation(LinearLayout.HORIZONTAL);
        clipActions.addView(button("录音复刻", v -> recordFor(editing.id)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pickLp.leftMargin = dp(10);
        clipActions.addView(button("选择音频", v -> pickSample(editing.id)), pickLp);
        voiceListHost.addView(clipActions);

        TextView save = button("保存这条音色", v -> saveEditing());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(12);
        voiceListHost.addView(save, saveLp);

        TextView back = button("← 返回音色列表", v -> renderVoiceList());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.topMargin = dp(8);
        voiceListHost.addView(back, backLp);

        TextView del = button("删除这条音色", v -> confirmDelete(profile));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        delLp.topMargin = dp(8);
        voiceListHost.addView(del, delLp);
    }

    private void confirmDelete(final VoiceProfile profile) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除音色")
                .setMessage("确定删除「" + profile.name + "」？它会从 Aicy 的声音列表里消失，"
                        + "参考音频也会一并删掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    VoiceStore.remove(MainActivity.this, profile.id);
                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    renderVoiceList();
                })
                .show();
    }

    /** Collects the editor fields into a profile and persists it. */
    private void saveEditing() {
        if (editing == null) return;
        String name = nameInput == null ? "" : nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "先给它起个名字", Toast.LENGTH_SHORT).show();
            return;
        }
        String desc = descInput == null ? "" : descInput.getText().toString().trim();
        VoiceProfile updated = new VoiceProfile(editing.id, name, editing.model,
                desc, editing.presetVoice, editing.sampleKey, editing.avatar);
        VoiceStore.putProfile(this, updated);
        Toast.makeText(this, "已保存「" + name + "」", Toast.LENGTH_SHORT).show();
        renderVoiceList();
    }

    private void addVoice() {
        VoiceProfile fresh = VoiceStore.add(this, "新音色");
        if (fresh == null) {
            Toast.makeText(this, "最多 " + VoiceProfile.MAX_PROFILES + " 条音色",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "已创建，填好描述后保存", Toast.LENGTH_SHORT).show();
        renderEditor(fresh);
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(MUTED);
        tv.setPadding(0, dp(12), 0, dp(2));
        return tv;
    }

    private TextView modelChip(String label, String model) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTag(model);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(6), dp(9), dp(6), dp(9));
        final String current = editing == null ? MiMoTts.MODEL_DESIGN : editing.model;
        tv.setOnClickListener(v -> {
            if (editing == null) return;
            // Switching type keeps every field the user already typed; only the
            // model changes. The payload is re-read from the inputs on save.
            editing = new VoiceProfile(editing.id, editing.name, (String) v.getTag(),
                    editing.desc, editing.presetVoice, editing.sampleKey, editing.avatar);
            renderEditor(editing);
        });
        styleChip(tv, model.equals(current));
        return tv;
    }

    private void refreshChips(android.view.ViewGroup group) {
        if (group == null) return;
        String current = editing == null ? null : editing.model;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                styleChip((TextView) child, tag.equals(current));
            }
        }
    }

    private void styleChip(TextView tv, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? 0xFF3B3B57 : 0xFF232326);
        bg.setStroke(dp(1), on ? ACCENT : 0xFF3F3F42);
        tv.setBackground(bg);
        tv.setTextColor(on ? 0xFFEDEBFF : MUTED);
    }

    private void pickSample() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, REQUEST_SAMPLE);
        } catch (Throwable error) {
            Toast.makeText(this, "打不开文件选择器：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_SAMPLE) {
            attachClip(data.getData());
        } else if (requestCode == REQUEST_EXPORT) {
            writeBundle(data.getData());
        } else if (requestCode == REQUEST_IMPORT) {
            readBundle(data.getData());
        }
    }

    /**
     * Reads a picked audio file into the timbre being edited.
     *
     * <p>The bytes are converted to 16 kHz mono WAV before they are stored. MiMo
     * re-sends the clip with every single request (there is no voice_id to cache
     * against), and a 310 KB compressed sample costs 3.0–5.6 s of response-header
     * wait before synthesis even starts. Four seconds of 16 kHz mono is ~125 KB.
     *
     * <p>Decoding needs a real file: {@code MediaExtractor} cannot read an
     * {@code InputStream}, so the stream is spooled to {@link #clipWorkDir} first.
     */
    private void attachClip(android.net.Uri source) {
        final String targetId = editing == null ? "" : editing.id;
        if (targetId.isEmpty()) {
            Toast.makeText(this, "先打开一条音色再选音频", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在转换音频…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            String summary = "";
            String base64 = "";
            File work = null;
            try {
                work = new File(clipWorkDir(), "import-" + System.currentTimeMillis() + ".src");
                long copied = spoolToFile(source, work, MAX_SAMPLE_BYTES);
                if (copied < 0) {
                    error = "音频太大（上限约 " + (MAX_SAMPLE_BYTES / 1024) + " KB）";
                } else {
                    ClipConverter.Result result = ClipConverter.toWav(work);
                    if (!result.ok()) {
                        error = result.error;
                    } else {
                        base64 = android.util.Base64.encodeToString(
                                result.wav, android.util.Base64.NO_WRAP);
                        VoiceProfile profile = findProfile(targetId);
                        if (profile == null) {
                            error = "找不到这条音色";
                        } else {
                            String key = profile.sampleKey.isEmpty()
                                    ? "v" + System.currentTimeMillis() : profile.sampleKey;
                            VoiceStore.putClip(this, key, base64);
                            VoiceStore.putProfile(this, new VoiceProfile(profile.id,
                                    profile.name, MiMoTts.MODEL_CLONE, profile.desc,
                                    profile.presetVoice, key, profile.avatar));
                            summary = String.format(java.util.Locale.US,
                                    "已转成 16 kHz 单声道 WAV · %.1f 秒 → %d KB（原 %d KB）",
                                    result.sourceSeconds,
                                    result.wav.length / 1024,
                                    result.sourceBytes / 1024);
                        }
                    }
                }
            } catch (Throwable failure) {
                error = "读取失败：" + failure.getClass().getSimpleName();
            } finally {
                if (work != null) {
                    try {
                        work.delete();
                    } catch (Throwable ignored) {
                        // nothing to do
                    }
                }
            }
            final String shownError = error;
            final String shownSummary = summary;
            runOnUiThread(() -> {
                if (shownError != null) {
                    Toast.makeText(this, "转换失败：" + shownError,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, shownSummary, Toast.LENGTH_LONG).show();
                VoiceProfile edited = findProfile(targetId);
                if (edited != null) renderEditor(edited);
            });
        }, "AicyClipImport").start();
    }

    /**
     * Scratch dir for the conversion pipeline.
     *
     * <p>Deliberately not {@code getCacheDir()}: on this ROM it is not writable and
     * every write throws {@code FileNotFoundException}. The external files dir is
     * proven to work.
     */
    private File clipWorkDir() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = new File(getFilesDir(), "clips");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Copies a content:// stream to a real file. @return bytes or -1 when too big. */
    private long spoolToFile(android.net.Uri source, File target, long limit) {
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(source);
            if (in == null) return -1;
            out = new java.io.FileOutputStream(target);
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
                total += read;
                if (total > limit) return -1;
            }
            out.flush();
            return total;
        } catch (Throwable error) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    private VoiceProfile findProfile(String speakerId) {
        for (VoiceProfile p : VoiceStore.load(this)) {
            if (p.id.equals(speakerId)) return p;
        }
        return null;
    }

    /** Opens the recorder, remembering which timbre the clip will belong to. */
    private void recordFor(String speakerId) {
        editing = findProfile(speakerId);
        if (editing == null) return;
        startActivity(new Intent(this, RecordActivity.class)
                .putExtra(RecordActivity.EXTRA_PROFILE_ID, speakerId));
    }

    private void pickSample(String speakerId) {
        editing = findProfile(speakerId);
        if (editing == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, REQUEST_SAMPLE);
        } catch (Throwable error) {
            Toast.makeText(this, "打不开文件选择器：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Verifies the API key and stores it encrypted; shared by all timbres. */
    /**
     * 只保存「模型名称」这一个字段，不需要重填 Key、也不做接口验证 ——
     * 想换代理上的模型 id 时不必再走一遍验证流程。
     */
    private void saveTtsModel() {
        String model = ttsModelInput == null ? ""
                : ttsModelInput.getText().toString().trim();
        getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE).edit()
                .putString(ConfigProvider.KEY_TTS_MODEL, model)
                .apply();
        RemoteConfig.invalidate();
        Toast.makeText(this, model.isEmpty()
                ? "已清空：每条音色用自己带的模型"
                : "已保存：合成统一用 " + model, Toast.LENGTH_SHORT).show();
    }

    private void saveKey() {        final String key = mimoKeyInput == null ? "" : mimoKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "先填 MiMo API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        final String endpoint = MiMoTts.normalizeEndpoint(
                mimoEndpointInput == null ? "" : mimoEndpointInput.getText().toString());
        if (mimoEndpointInput != null) mimoEndpointInput.setText(endpoint);
        if (voiceState != null) {
            voiceState.setText("正在验证接口与 Key…");
            voiceState.setTextColor(MUTED);
        }
        new Thread(() -> {
            final String error = MiMoTts.verifyKey(endpoint, key);
            runOnUiThread(() -> {
                if (error != null) {
                    if (voiceState != null) {
                        voiceState.setText("验证失败：" + error);
                        voiceState.setTextColor(WARN);
                    }
                    Toast.makeText(this, "接口或 Key 没通过：" + error, Toast.LENGTH_LONG).show();
                    return;
                }
                // The endpoint is not secret; only the key is sealed.
                getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE).edit()
                        .putString(ConfigProvider.KEY_TTS_ENDPOINT, endpoint)
                        .putString(ConfigProvider.KEY_MIMO_KEY, SecretStore.seal(this, key))
                        .putString(ConfigProvider.KEY_TTS_MODEL,
                                ttsModelInput == null ? ""
                                        : ttsModelInput.getText().toString().trim())
                        .apply();
                RemoteConfig.invalidate();
                if (voiceState != null) {
                    voiceState.setText("接口与 Key 已验证 · 所有音色共用");
                    voiceState.setTextColor(OK);
                }
                Toast.makeText(this, "接口与 Key 已保存", Toast.LENGTH_SHORT).show();
            });
        }, "AicyVerifyKey").start();
    }

    // ------------------------------------------------------------------
    // 音色包 · 导出 / 导入
    // ------------------------------------------------------------------

    private void exportBundle() {
        List<VoiceProfile> list = VoiceStore.load(this);
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有音色可以导出", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "aicy-timbres.json");
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Throwable error) {
            Toast.makeText(this, "打不开文件选择器：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void writeBundle(android.net.Uri target) {
        List<VoiceProfile> list = VoiceStore.load(this);
        try {
            VoiceBundle.exportTo(this, target, list);
            Toast.makeText(this, "已导出 " + list.size() + " 条音色", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "导出失败：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void importBundle() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Throwable error) {
            Toast.makeText(this, "打不开文件选择器：" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void readBundle(android.net.Uri source) {
        new Thread(() -> {
            String error = null;
            int added = 0;
            try {
                VoiceBundle.Parsed parsed = VoiceBundle.read(MainActivity.this, source);
                if (parsed == null) {
                    error = "这不是本模块导出的音色包";
                } else {
                    added = VoiceBundle.importInto(MainActivity.this, parsed);
                }
            } catch (Throwable e) {
                error = e.getClass().getSimpleName();
            }
            final int result = added;
            final String failure = error;
            runOnUiThread(() -> {
                if (failure != null) {
                    Toast.makeText(MainActivity.this, "导入失败：" + failure,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(MainActivity.this,
                        result == 0 ? "音色包是空的" : "已导入 " + result + " 条音色",
                        Toast.LENGTH_LONG).show();
                renderVoiceList();
            });
        }, "AicyImportBundle").start();
    }

    // ------------------------------------------------------------------
    // Runtime diagnostics forwarded from Aicy's process
    // ------------------------------------------------------------------

    private void diagCard(LinearLayout root) {
        SharedPreferences sp = getSharedPreferences(DiagReceiver.PREFS, MODE_PRIVATE);
        String log = sp.getString(DiagReceiver.KEY_LOG, "");
        long at = sp.getLong(DiagReceiver.KEY_AT, 0L);
        String body;
        if (log == null || log.trim().isEmpty()) {
            body = "还没收到 Aicy 进程的报告 —— 模块没有注入。\n\n"
                 + "检查：模块已启用 → 作用域含 com.meizu.voiceassistant"
                 + "→ Zygisk 已开启 → 强停过 Aicy。\n\n"
                 + "注入成功后这里会显示 module loaded / hooks armed / catalog patched。";
        } else {
            String when = new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date(at));
            body = "最近一次：" + when + "\n\n" + log;
        }
        card(root, "诊断 · Aicy 进程自报", body);
    }

    private void card(LinearLayout root, String title, String body) {        LinearLayout card = cardContainer();

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        card.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(TEXT);
        b.setLineSpacing(dp(4), 1f);
        b.setPadding(0, dp(8), 0, 0);
        card.addView(b);

        root.addView(card);
    }

    private void step(LinearLayout root, int number, String title, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(10));
        row.setGravity(Gravity.TOP);

        TextView badge = new TextView(this);
        badge.setText(String.valueOf(number));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF4F46E5);
        badge.setBackground(circle);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        badgeLp.rightMargin = dp(10);
        row.addView(badge, badgeLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        text.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setTextColor(MUTED);
        b.setLineSpacing(dp(3), 1f);
        b.setPadding(0, dp(3), 0, 0);
        text.addView(b);

        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
    }

    private void infoRow(LinearLayout card, String title, String subtitle, String body) {
        linkRow(card, title, subtitle, body, TEXT, 13.5f);
    }

    private void questionRow(LinearLayout card, String question, String answer) {
        linkRow(card, question, null, answer, WARN, 13f);
    }

    private void linkRow(LinearLayout card, String title, String subtitle, String body,
                         int titleColor, float titleSize) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setOnClickListener(v -> showInfoDialog(title, body));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(titleColor);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s.setTextColor(MUTED);
            s.setPadding(0, dp(2), dp(8), 0);
            texts.addView(s);
        }

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        arrow.setTextColor(MUTED);
        arrow.setPadding(dp(10), 0, dp(2), 0);
        row.addView(arrow);

        card.addView(row);
    }

    private void showInfoDialog(String title, String body) {
        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTextColor(TEXT);
        text.setLineSpacing(dp(4), 1f);
        text.setPadding(dp(22), dp(8), dp(22), dp(4));
        text.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void aboutCard(LinearLayout root) {
        section(root, "关于");
        LinearLayout card = cardContainer();
        statusRow(card, "版本", "Aicy Unlock " + versionName(), TEXT);
        statusRow(card, "范围", "仅作用于 " + AICY_PACKAGE, MUTED);
        statusRow(card, "数据", "钩子只在内存里运行，不上传内容", MUTED);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.topMargin = dp(14);
        row.addView(button("GitHub 项目主页 ↗", v -> openUrl(GITHUB_URL)), left);

        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        right.topMargin = dp(14);
        right.leftMargin = dp(8);
        row.addView(button("反馈问题 ↗", v -> openUrl(ISSUES_URL)), right);

        card.addView(row);
        root.addView(card);
    }

    private void rowButtons(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(button("打开 LSPosed", v -> openManager()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        row.addView(button("复制日志关键字", v -> copy("AicyUnlock")), lp);

        root.addView(row);
    }

    private TextView button(String label, View.OnClickListener listener) {        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(0xFFEDEBFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF2A2A34);
        bg.setStroke(dp(1), 0xFF3F3F52);
        tv.setBackground(bg);
        tv.setOnClickListener(listener);
        return tv;
    }

    // ---------------- power confirmation preview ----------------

    private void powerPreview(LinearLayout root) {
        LinearLayout card = cardContainer();

        TextView head = new TextView(this);
        head.setText("在 Aicy 里说「关机」或「重启」，确认条会直接盖在对话上");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        head.setTextColor(0xFFC9CBD3);
        head.setLineSpacing(dp(3), 1f);
        card.addView(head);

        TextView note = new TextView(this);
        note.setText("拖到尽头松手吸附，3 秒环形倒计时，随时可取消；拖出两端会回弹。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        note.setTextColor(0xFF8A8E99);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(6), 0, dp(10));
        card.addView(note);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView off = button("预览 · 关机", v -> showPowerPreview(false));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        first.rightMargin = dp(6);
        row.addView(off, first);

        TextView reboot = button("预览 · 重启", v -> showPowerPreview(true));
        row.addView(reboot, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);

        card.addView(powerSwitch("RGB 跑马灯",
                dev.loong.aicyunlock.power.PowerPrefs.rainbow(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setRainbow(this, checked)));

        card.addView(powerSwitch("语音浮窗电源按钮",
                dev.loong.aicyunlock.power.PowerPrefs.dock(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setDock(this, checked)));

        card.addView(dockPositionRow());

        card.addView(powerSwitch("握姿左右反转（跟随时左右相反就打开）",
                dev.loong.aicyunlock.power.PowerPrefs.gripFlip(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setGripFlip(this, checked)));

        // Direct entry: one tap brings the slider up, without waiting for Aicy.
        TextView open = button("打开电源滑动条", v -> openPowerSlider());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(12);
        card.addView(open, openLp);

        card.addView(powerSwitch("发送前拦截",
                dev.loong.aicyunlock.power.PowerPrefs.interceptSend(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setInterceptSend(this, checked)));

        card.addView(powerSwitch("临时 root 模式（关机拦截 · 重启改软重启）",
                dev.loong.aicyunlock.power.PowerPrefs.tempRoot(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setTempRoot(this, checked)));

        card.addView(powerSwitch("演练模式（不真正执行）",
                dev.loong.aicyunlock.power.PowerPrefs.dryRun(this),
                checked -> dev.loong.aicyunlock.power.PowerPrefs.setDryRun(this, checked)));

        TextView warn = new TextView(this);
        warn.setText("关掉演练模式后，滑条会真执行：临时 root 开启时关机被拦下、重启走软重启"
                + "（只重启 Android 框架，不动内核）；关闭临时 root 才会走真正的关机/重启。");
        warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        warn.setTextColor(0xFF8A6A3C);
        warn.setLineSpacing(dp(3), 1f);
        warn.setPadding(0, dp(10), 0, 0);
        card.addView(warn);

        root.addView(card);
    }

    /** Cycles the slider placement: under the status bar, left edge, right edge. */
    private LinearLayout dockPositionRow() {
        // Display order is not the constant order: "follow the grip" is the
        // first choice on screen but the last constant, so the two are mapped
        // explicitly instead of assuming index == value.
        final String[] names = {"跟随握姿", "状态栏下方", "左侧", "右侧"};
        final int[] order = {
                dev.loong.aicyunlock.power.PowerDock.POS_AUTO,
                dev.loong.aicyunlock.power.PowerDock.POS_TOP,
                dev.loong.aicyunlock.power.PowerDock.POS_LEFT,
                dev.loong.aicyunlock.power.PowerDock.POS_RIGHT};

        int stored = dev.loong.aicyunlock.power.PowerPrefs.dockPosition(this);
        int start = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == stored) start = i;
        }
        final int[] slot = {start};

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        TextView title = new TextView(this);
        title.setText("滑动条位置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTextColor(0xFFC9CBD3);
        row.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(names[slot[0]] + "  ›");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setTextColor(0xFF5AC8FA);
        value.setOnClickListener(v -> {
            slot[0] = (slot[0] + 1) % order.length;
            dev.loong.aicyunlock.power.PowerPrefs.setDockPosition(this, order[slot[0]]);
            ((TextView) v).setText(names[slot[0]] + "  ›");
            Toast.makeText(this, "滑动条：左/上是关机，反方向是重启 · "
                    + names[slot[0]], Toast.LENGTH_SHORT).show();
        });
        row.addView(value);
        return row;
    }

    /** Shows the floating power pill on top of this activity, for layout checks. */
    private void showDockPreview() {
        resolveDockPosition(position -> mountDock(position, false));
    }

    /** Same layout, but unfolded: the button under the switches jumps here. */
    private void openPowerSlider() {
        resolveDockPosition(position -> mountDock(position, true));
    }

    private interface PositionSink {
        void accept(int position);
    }

    private void resolveDockPosition(PositionSink sink) {
        final int wanted = dev.loong.aicyunlock.power.PowerPrefs.dockPosition(this);
        if (wanted != dev.loong.aicyunlock.power.PowerDock.POS_AUTO) {
            sink.accept(wanted);
            return;
        }
        Toast.makeText(this, "读取握姿…", Toast.LENGTH_SHORT).show();
        dev.loong.aicyunlock.power.GripSensor.detect(this, (grip, roll) -> {
            int position = dev.loong.aicyunlock.power.PowerDock.fromGrip(grip,
                    dev.loong.aicyunlock.power.PowerPrefs.gripFlip(this));
            sink.accept(position);
            Toast.makeText(this, "倾角 " + Math.round(roll) + "° → "
                    + dev.loong.aicyunlock.power.PowerDock.name(position),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void mountDock(int position, boolean unfolded) {
        try {
            android.view.ViewGroup host = findViewById(android.R.id.content);
            if (dockPreviewRoot != null) {
                host.removeView(dockPreviewRoot);
                dockPreviewRoot = null;
            }
            dev.loong.aicyunlock.power.PowerDock dock =
                    new dev.loong.aicyunlock.power.PowerDock(this, position);
            android.view.View root = dock.getRoot();
            dockPreviewRoot = root;
            host.addView(root, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            if (unfolded) dock.expandNow();
            android.util.Log.i("AicyUnlock", "slider open, position="
                    + dev.loong.aicyunlock.power.PowerDock.name(position)
                    + " unfolded=" + unfolded);
        } catch (Throwable error) {
            android.util.Log.e("AicyUnlock", "slider open failed", error);
        }
    }

    private LinearLayout powerSwitch(String label, boolean checked,
                                     java.util.function.Consumer<Boolean> sink) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTextColor(0xFFC9CBD3);
        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        tintSwitch(sw, checked);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            tintSwitch(sw, isChecked);
            sink.accept(isChecked);
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void showPowerPreview(boolean reboot) {
        try {
            android.view.ViewGroup host = findViewById(android.R.id.content);
            final dev.loong.aicyunlock.power.PowerSheet sheet =
                    new dev.loong.aicyunlock.power.PowerSheet(this, reboot,
                            () -> dev.loong.aicyunlock.power.PowerActions.execute(this, reboot));
            sheet.setOnDismiss(() -> {
                try {
                    host.removeView(sheet.root);
                } catch (Throwable ignored) {
                }
            });
            host.addView(sheet.root, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            sheet.markShown();
        } catch (Throwable ignored) {
        }
    }

    private void openManager() {
        // LSPosed and Vector both ship the manager as org.lsposed.manager.
        for (String pkg : new String[]{"org.lsposed.manager", "io.github.libxposed.manager"}) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                startActivity(intent);
                return;
            }
        }
        Toast.makeText(this, "没找到 LSPosed 管理器，请从系统里手动打开",
                Toast.LENGTH_SHORT).show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AicyUnlock", text));
            Toast.makeText(this, "已复制：" + text, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens a web link, falling back to a copyable toast when no browser can
     * handle it (rare, but a dead button is worse than a copy hint).
     */
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable error) {
            copy(url);
        }
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(CARD);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private int dp(float value) {
        return Math.round(value * d);
    }

    /** 本模块版本号，footer 用，避免每次发版都改字符串。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
