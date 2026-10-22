package dev.loong.aicyunlock.power;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * The trigger and the presenter.
 *
 * Trigger: Aicy's chat requests. The last "role":"user" block of the request
 * body is scanned for a power intent. That covers typing, pasting, IME
 * composition and speech-to-text alike, because all of them end up as a user
 * message on the wire - and it is completely independent of the model, the
 * skill index and the tool catalogue.
 */
public final class PowerGate {

    public interface Reporter {
        void report(String message, Throwable error);
    }

    public interface HookBridge {
        void hook(String className, String methodName,
                  XposedInterface.Hooker hooker, Class<?>... parameterTypes);
    }

    public static final int NONE = 0;
    public static final int SHUTDOWN = 1;
    public static final int REBOOT = 2;

    private static final String TAG = "PowerConfirm";

    private static volatile Reporter reporter = (m, e) -> { };
    private static volatile WeakReference<Activity> current = new WeakReference<>(null);
    private static volatile long lastFireAt;
    private static volatile boolean sheetShowing;

    private static final Pattern ROLE_USER =
            Pattern.compile("\"role\"\\s*:\\s*\"user\"");
    private static final Pattern ROLE_ANY =
            Pattern.compile("\"role\"\\s*:\\s*\"(user|assistant|system|tool)\"");

    /** Words that carry a positive "do it" meaning. */
    private static final String[] SHUTDOWN_WORDS =
            {"关机", "關機", "關掉手機", "關掉手机", "shutdown", "power off", "poweroff", "关掉设备"};
    private static final String[] REBOOT_WORDS =
            {"重启", "重啟", "重新启动", "重新啟動", "reboot", "restart", "重启手机", "重啟手機"};

    /** Anything that turns the sentence into a refusal / question about it. */
    private static final String[] NEGATORS = {
            "不要", "别", "不用", "不必", "无需", "無需", "不需要", "取消", "不想",
            "不准", "禁止", "停止", "没让", "沒讓", "不是", "别给", "別給", "问一下", "問一下",
            "怎么", "怎麼", "如何", "能不能", "可不可以", "是否会", "是否會"
    };

    private static final int BODY_LOG_LIMIT = 260;

    private PowerGate() {
    }

    public static void attach(Reporter sink) {
        if (sink != null) reporter = sink;
    }

    public static void rememberActivity(Activity activity) {
        if (activity == null) return;
        Activity previous = current.get();
        if (previous != activity) {
            current = new WeakReference<>(activity);
        }
    }

    // ------------------------------------------------------------------
    // Request interception
    // ------------------------------------------------------------------

    public static void installRequestHook(HookBridge bridge, ClassLoader loader) {
        // OkHttp is bundled and its internal package survives R8 in this build
        // (verified: Lokhttp3/internal/http/BridgeInterceptor; is in the dex).
        try {
            Class<?> interceptorChain = Class.forName("okhttp3.Interceptor$Chain", false, loader);
            bridge.hook("okhttp3.internal.http.BridgeInterceptor", "intercept",
                    chain -> {
                        try {
                            List<Object> args = chain.getArgs();
                            Object realChain = (args == null || args.isEmpty()) ? null : args.get(0);
                            inspectChain(realChain);
                        } catch (Throwable ignored) {
                            // never disturb the traffic
                        }
                        return chain.proceed();
                    }, interceptorChain);
        } catch (Throwable error) {
            reporter.report("BridgeInterceptor unavailable: " + error, null);
        }

        // Second net: some builds route through RealCall directly.
        try {
            Class<?> call = Class.forName("okhttp3.RealCall", false, loader);
            bridge.hook("okhttp3.RealCall", "execute",
                    chain -> {
                        try {
                            inspectCall(chain.getThisObject());
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            reporter.report("RealCall probe ok: " + call.getName(), null);
        } catch (Throwable ignored) {
            // optional
        }
    }

    /** Pulls request().body() off the interceptor chain without linking okhttp. */
    private static void inspectChain(Object realChain) {
        if (realChain == null) return;
        Object request = invokeNoArg(realChain, "request");
        inspectRequest(request, "chain");
    }

    /** RealCall keeps its Request in a field; reflection by type instead of name. */
    private static void inspectCall(Object call) {
        if (call == null) return;
        Object request = firstFieldOfType(call, "okhttp3.Request");
        inspectRequest(request, "call");
    }

    private static void inspectRequest(Object request, String source) {
        if (request == null) return;
        String url = "";
        try {
            Object u = invokeNoArg(request, "url");
            if (u != null) url = String.valueOf(u);
        } catch (Throwable ignored) {
        }

        Object body = invokeNoArg(request, "body");
        if (body == null) return;

        String payload = readBody(body);
        if (payload == null || payload.isEmpty()) return;

        String userChunk = lastUserBlock(payload);
        String scanned = userChunk != null ? userChunk : "";

        if (userChunk != null) {
            reporter.report("[" + source + "] user block: " + clip(userChunk), null);
        } else {
            reporter.report("[" + source + "] no role=user, body " + payload.length()
                    + " chars, head=" + clip(payload), null);
        }

        int action = detect(scanned);
        if (action == NONE) return;
        reporter.report("[" + source + "] power intent=" + (action == REBOOT ? "reboot" : "shutdown")
                + " url=" + url, null);
        fire(action);
    }

    /** body.writeTo(okio.Buffer) -> readUtf8. Text bodies only, capped. */
    private static String readBody(Object body) {
        try {
            ClassLoader loader = body.getClass().getClassLoader();
            Class<?> bufferClass = Class.forName("okio.Buffer", false, loader);
            Class<?> sinkClass = Class.forName("okio.BufferedSink", false, loader);
            Object buffer = bufferClass.getDeclaredConstructor().newInstance();

            Method writeTo = null;
            Class<?> cursor = body.getClass();
            while (cursor != null && writeTo == null) {
                for (Method candidate : cursor.getDeclaredMethods()) {
                    if (!"writeTo".equals(candidate.getName())) continue;
                    Class<?>[] params = candidate.getParameterTypes();
                    if (params.length == 1 && params[0].isAssignableFrom(sinkClass)) {
                        writeTo = candidate;
                        break;
                    }
                }
                cursor = cursor.getSuperclass();
            }
            if (writeTo == null) {
                for (Method candidate : body.getClass().getMethods()) {
                    if ("writeTo".equals(candidate.getName())
                            && candidate.getParameterTypes().length == 1) {
                        writeTo = candidate;
                        break;
                    }
                }
            }
            if (writeTo == null) return null;
            writeTo.setAccessible(true);
            writeTo.invoke(body, buffer);

            Object text = bufferClass.getMethod("readUtf8").invoke(buffer);
            return text instanceof String ? (String) text : null;
        } catch (Throwable error) {
            reporter.report("body read failed: " + error, null);
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object firstFieldOfType(Object target, String typeName) {
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            for (java.lang.reflect.Field field : cursor.getDeclaredFields()) {
                if (!typeName.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    // keep looking
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Intent detection
    // ------------------------------------------------------------------

    /** Returns the tail of the last user message, so system prompts can't trip it. */
    private static String lastUserBlock(String body) {
        Matcher all = ROLE_USER.matcher(body);
        int start = -1;
        while (all.find()) start = all.end();
        if (start < 0) return null;

        int end = Math.min(body.length(), start + 1600);
        Matcher next = ROLE_ANY.matcher(body);
        if (next.find(start)) {
            end = Math.min(end, next.start());
        }
        if (end <= start) return null;
        return body.substring(start, end);
    }

    public static int detect(String text) {
        if (text == null || text.isEmpty()) return NONE;
        String lower = text.toLowerCase();

        int shutdown = firstLiveHit(lower, SHUTDOWN_WORDS);
        int reboot = firstLiveHit(lower, REBOOT_WORDS);

        if (shutdown < 0 && reboot < 0) return NONE;
        if (shutdown >= 0 && (reboot < 0 || shutdown <= reboot)) return SHUTDOWN;
        return REBOOT;
    }

    /** Index of the first occurrence that isn't wrapped in a refusal. */
    private static int firstLiveHit(String text, String[] words) {
        int best = -1;
        for (String word : words) {
            int index = text.indexOf(word);
            while (index >= 0) {
                if (!negatedAt(text, index)) {
                    if (best < 0 || index < best) best = index;
                    break;
                }
                index = text.indexOf(word, index + word.length());
            }
        }
        return best;
    }

    private static boolean negatedAt(String text, int index) {
        int from = Math.max(0, index - 10);
        String before = text.substring(from, index);
        for (String negator : NEGATORS) {
            if (before.contains(negator)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    /**
     * Send gate. Returns true when the sentence was a power request: the caller
     * must then drop it instead of sending, and the sheet comes up instead.
     */
    public static boolean intercept(String text) {
        if (text == null || text.isEmpty()) return false;
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) return false;
        if (!PowerPrefs.interceptSend(activity)) return false;
        int action = detect(text);
        if (action == NONE) return false;
        reporter.report("intercepted=" + (action == REBOOT ? "reboot" : "shutdown")
                + " text=" + clip(text), null);
        clearInput(activity);
        fire(action);
        return true;
    }

    /** Wipes whatever the user typed, so the intercepted sentence does not linger. */
    private static void clearInput(Activity activity) {
        try {
            activity.runOnUiThread(() -> {
                try {
                    clearIn(activity.getWindow().getDecorView());
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void clearIn(android.view.View view) {
        if (view == null) return;
        if (view instanceof android.widget.EditText) {
            try {
                ((android.widget.EditText) view).setText("");
            } catch (Throwable ignored) {
            }
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearIn(group.getChildAt(i));
            }
        }
    }

    /**
     * Entry point for the voice path. The voice sheet never renders the
     * recognised sentence, so the ASR callback hands it straight to us.
     */
    public static void submitText(String text) {
        if (text == null || text.isEmpty()) return;
        int action = detect(text);
        if (action == NONE) {
            reporter.report("voice text, no intent: " + clip(text), null);
            return;
        }
        reporter.report("voice intent=" + (action == REBOOT ? "reboot" : "shutdown")
                + " text=" + clip(text), null);
        fire(action);
    }

    private static void fire(int action) {
        long now = SystemClock.uptimeMillis();
        if (sheetShowing) return;
        if (now - lastFireAt < 5000L) return;

        Activity activity = current.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            reporter.report("fire skipped: no live Aicy activity", null);
            return;
        }
        lastFireAt = now;
        final boolean reboot = action == REBOOT;
        activity.runOnUiThread(() -> show(activity, reboot));
    }

    private static void show(Activity activity, boolean reboot) {
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            final PowerSheet sheet = new PowerSheet(activity, reboot,
                    () -> PowerActions.execute(activity, reboot));
            sheet.setOnDismiss(() -> {
                try {
                    decor.removeView(sheet.root);
                } catch (Throwable ignored) {
                }
                sheetShowing = false;
            });
            decor.addView(sheet.root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sheet.markShown();
            sheetShowing = true;
            reporter.report("sheet shown: " + (reboot ? "reboot" : "shutdown"), null);
        } catch (Throwable error) {
            sheetShowing = false;
            reporter.report("sheet failed: " + Log.getStackTraceString(error), null);
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ");
        return flat.length() <= BODY_LOG_LIMIT ? flat : flat.substring(0, BODY_LOG_LIMIT) + "…";
    }
}
