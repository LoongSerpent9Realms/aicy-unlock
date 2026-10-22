package dev.loong.aicyunlock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a spoken/typed app command into a task-robot request.
 *
 * <p>The sentence keeps its normal route through Aicy so the
 * {@code app-search-launcher} skill can answer visibly. The request is staged
 * here and only submitted once Aicy reports that skill as invoked; a fallback
 * fires shortly after the send in case the skill is not recalled.
 */
final class TaskRobotGate {

    interface Reporter {
        void report(String message, Throwable error);
    }

    static final String SKILL_ID = "app-search-launcher";

    private static final String TAG = "TaskRobot";
    private static final long DUPLICATE_MS = 8_000L;
    private static final long FALLBACK_MS = 10_000L;
    private static final long STAGE_TTL_MS = 60_000L;

    private static final Pattern ROBOT_PREFIX = Pattern.compile(
            "^(?:让|用|叫)?\\s*任务机器人\\s*[：:，,]?\\s*");
    private static final Pattern[] OPEN_SEARCH = {
            Pattern.compile("^(?:请|帮我|帮忙|麻烦)?\\s*打开\\s*(.+?)\\s*[，,、]?\\s*搜索(?:一下)?\\s*(.+?)\\s*$"),
            Pattern.compile("^(?:请|帮我|帮忙|麻烦)?\\s*在\\s*(.+?)\\s*(?:里|上|中)?\\s*搜索(?:一下)?\\s*(.+?)\\s*$"),
            Pattern.compile("^(?:请|帮我|帮忙|麻烦)?\\s*用\\s*(.+?)\\s*搜索(?:一下)?\\s*(.+?)\\s*$"),
    };
    private static final Pattern TRAILING = Pattern.compile("[。.!！?？]+$");
    private static final Pattern APP_SUFFIX = Pattern.compile(
            "(?i)\\s*(?:app|客户端|手机版)$");

    /** Common spoken names whose launcher label may differ from the package. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("淘宝", "com.taobao.taobao");
        ALIASES.put("京东", "com.jingdong.app.mall");
        ALIASES.put("美团", "com.sankuai.meituan");
        ALIASES.put("闲鱼", "com.taobao.idlefish");
        ALIASES.put("微信", "com.tencent.mm");
        ALIASES.put("支付宝", "com.eg.android.AlipayGphone");
        ALIASES.put("高德", "com.autonavi.minimap");
        ALIASES.put("高德地图", "com.autonavi.minimap");
        ALIASES.put("抖音", "com.ss.android.ugc.aweme");
        ALIASES.put("小红书", "com.xingin.xhs");
        ALIASES.put("拼多多", "com.xunmeng.pinduoduo");
        ALIASES.put("哔哩哔哩", "tv.danmaku.bili");
        ALIASES.put("b站", "tv.danmaku.bili");
        ALIASES.put("知乎", "com.zhihu.android");
        ALIASES.put("微博", "com.sina.weibo");
    }

    private static volatile Reporter reporter = (m, e) -> { };
    private static volatile Map<String, App> sLauncherApps;

    private static String sStagedText = "";
    private static long sStagedAt;
    private static Pending sPending;

    private TaskRobotGate() {
    }

    static void attach(Reporter sink) {
        if (sink != null) reporter = sink;
    }

    static synchronized void stage(String text) {
        if (text == null) return;
        String raw = text.trim();
        if (raw.isEmpty()) return;
        if (!RemoteConfig.master() || !RemoteConfig.taskRobot()) return;

        Matcher prefix = ROBOT_PREFIX.matcher(raw);
        String command = prefix.find() ? raw.substring(prefix.end()).trim() : raw;

        String appName = null;
        String keyword = null;
        for (Pattern pattern : OPEN_SEARCH) {
            Matcher matcher = pattern.matcher(command);
            if (!matcher.matches()) continue;
            appName = matcher.group(1).trim();
            keyword = TRAILING.matcher(matcher.group(2).trim()).replaceAll("").trim();
            if (!appName.isEmpty() && !keyword.isEmpty()) break;
            appName = null;
        }
        if (appName == null) return;

        long now = System.currentTimeMillis();
        if (raw.equals(sStagedText) && now - sStagedAt < DUPLICATE_MS) return;

        Context context = RemoteConfig.app();
        if (context == null) {
            reporter.report("no context, leaving sentence to Aicy", null);
            return;
        }
        App app = resolve(context, appName);
        if (app == null) {
            reporter.report("app not found: " + clip(appName), null);
            return;
        }

        Pending pending = new Pending(app.pkg, app.label, command, now);
        sPending = pending;
        sStagedText = raw;
        sStagedAt = now;
        reporter.report("staged for " + SKILL_ID + " app=" + app.pkg
                + " (" + app.label + ") keyword=" + clip(keyword), null);
        scheduleFallback(pending);
    }

    static boolean dispatchPending() {
        Pending pending;
        synchronized (TaskRobotGate.class) {
            pending = sPending;
            sPending = null;
        }
        return submit(pending);
    }

    private static synchronized Pending takeIfCurrent(Pending expected) {
        if (sPending != expected) return null;
        sPending = null;
        return expected;
    }

    private static boolean submit(Pending pending) {
        if (pending == null) return false;
        if (System.currentTimeMillis() - pending.at > STAGE_TTL_MS) return false;
        Context context = RemoteConfig.app();
        if (context == null) {
            reporter.report("no context, pending dropped", null);
            return false;
        }
        boolean started = TaskRobotBridge.submit(context, pending.pkg, pending.label, pending.query);
        if (started) {
            reporter.report("submit from skill app=" + pending.pkg + " (" + pending.label + ")"
                    + " query=" + clip(pending.query), null);
        }
        return started;
    }

    private static void scheduleFallback(Pending pending) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(FALLBACK_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            submit(takeIfCurrent(pending));
        }, "aicy-taskrobot-fallback");
        worker.setDaemon(true);
        worker.start();
    }

    private static App resolve(Context context, String spoken) {
        PackageManager pm = context.getPackageManager();
        String normalized = normalize(spoken);
        if (normalized.isEmpty()) return null;

        String direct = ALIASES.get(normalized);
        if (direct == null) {
            String noSuffix = normalize(APP_SUFFIX.matcher(spoken).replaceAll(""));
            direct = ALIASES.get(noSuffix);
            if (direct == null) direct = noSuffix;
        }
        if (direct != null && direct.indexOf('.') >= 0) {
            App byPackage = installed(pm, direct);
            if (byPackage != null) return byPackage;
        }

        Map<String, App> apps = launcherApps(pm);
        App exact = apps.get(normalize(spoken));
        if (exact == null) exact = apps.get(normalize(APP_SUFFIX.matcher(spoken).replaceAll("")));
        if (exact != null) return exact;

        App best = null;
        for (Map.Entry<String, App> entry : apps.entrySet()) {
            String label = entry.getKey();
            if (label.length() < 2 || normalized.length() < 2) continue;
            if (label.startsWith(normalized) || normalized.startsWith(label)) {
                if (best == null || label.length() < normalize(best.label).length()) {
                    best = entry.getValue();
                }
            }
        }
        return best;
    }

    private static Map<String, App> launcherApps(PackageManager pm) {
        Map<String, App> cached = sLauncherApps;
        if (cached != null) return cached;
        Map<String, App> apps = new HashMap<>();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo info : list) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                CharSequence label = info.loadLabel(pm);
                String text = label == null ? pkg : label.toString();
                apps.put(normalize(text), new App(pkg, text));
            }
            sLauncherApps = apps;
            reporter.report("launcher apps cached: " + apps.size(), null);
        } catch (Throwable error) {
            reporter.report("launcher query failed", error);
        }
        return apps;
    }

    private static App installed(PackageManager pm, String pkg) {
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return new App(pkg, label == null ? pkg : label.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "")
                .replace("，", "")
                .replace(",", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    private static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private static final class Pending {
        final String pkg;
        final String label;
        final String query;
        final long at;

        Pending(String pkg, String label, String query, long at) {
            this.pkg = pkg;
            this.label = label;
            this.query = query;
            this.at = at;
        }
    }
}
