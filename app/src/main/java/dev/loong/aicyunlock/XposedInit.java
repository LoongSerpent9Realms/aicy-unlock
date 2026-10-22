package dev.loong.aicyunlock;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Entry point. Scope is Aicy 助手 (com.meizu.voiceassistant) only, and the
 * scope is requested dynamically from the module app (see MainActivity).
 *
 * Two jobs:
 *   1. Reveal the hidden "model settings" (BYOK) UI. Structure-based: the
 *      feature flags live in the mlkit_vision_common package, so we snapshot
 *      the static booleans there and restore the ones the app flips off at
 *      startup. Exact names from 13.0.54 are used as a fast path only.
 *   2. Conditional egress firewall: Meizu conversation/upload traffic is cut
 *      only while a custom (BYOK) model is configured.
 */
public final class XposedInit extends XposedModule {
    static final String TARGET = "com.meizu.voiceassistant";
    /** Aicy Touch (长按识屏) — the row with 问 Aicy / 分词 / 关键词 lives here. */
    static final String PICKER = "com.meizu.picker";
    /** Stock wake-word page; the custom keyphrase UI ships in the same APK. */
    private static final String WAKE_WORD_ENTRY =
            "com.meizu.voicewakeup.VoiceWakeUpActivity";
    private static final String WAKE_WORD_CUSTOM =
            "com.meizu.voiceassistant.keyphrase.KeyphraseSettingActivity";
    /** Snapdragon Voice Activate: it, not Aicy, records the five samples. */
    static final String SVA = "com.qualcomm.qti.sva";
    /** Score VerifyRecordingTask requires from verifyUdkRecording to accept a round. */
    private static final int UDK_ROUND_PASS_SNR = 16;
    /**
     * How long a round waits for its spoken cue before the microphone opens
     * anyway. Covers a module that is not installed, cannot start or has no key;
     * a cue slower than this loses one round to a retry rather than the session.
     */
    private static final long CUE_WATCHDOG_MS = 12_000L;
    /** Vendor (SVA) key-value store the keyphrase UI asks about. */
    private static final String WAKE_WORD_PREFS = "voice_wake_up_sp";
    /** Which preset is active; negative means "engine has no 4.1.0 support". */
    private static final String WAKE_WORD_WHICH = "voice_switch_ver410_which";
    private static final String TAG = "AicyUnlock";

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean svaInstalled = new AtomicBoolean(false);
    private volatile java.nio.ByteBuffer udkBase;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            log(Log.INFO, TAG, "module loaded: framework=" + getFrameworkName()
                    + " " + getFrameworkVersion() + " api=" + getApiVersion()
                    + " process=" + param.getProcessName(), null);
        } catch (Throwable ignored) {
            // logging only
        }
    }

    /** API 100 frameworks call this one. */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (param.getPackageName() == null) return;
        if (TARGET.equals(param.getPackageName())) {
            installFor(param.getDefaultClassLoader(), "onPackageLoaded");
        } else if (PICKER.equals(param.getPackageName())) {
            installPicker(param.getDefaultClassLoader());
        } else if (SVA.equals(param.getPackageName())) {
            installSva(param.getDefaultClassLoader(), "onPackageLoaded");
        }
    }

    /** API 101+ frameworks call this one. */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (param.getPackageName() == null) return;
        if (TARGET.equals(param.getPackageName())) {
            installFor(param.getClassLoader(), "onPackageReady");
        } else if (PICKER.equals(param.getPackageName())) {
            installPicker(param.getClassLoader());
        } else if (SVA.equals(param.getPackageName())) {
            installSva(param.getClassLoader(), "onPackageReady");
        }
    }

    // ------------------------------------------------------------ Aicy Touch

    /**
     * Puts the power entry into Aicy Touch's own interface.
     *
     * <p>No accessibility service involved: this runs inside the process, so it
     * can walk the real view tree and drop the button into the row that holds
     * "问 Aicy / 分词 / 关键词".
     */
    private void installPicker(ClassLoader loader) {
        if (loader == null) {
            log(Log.ERROR, TAG, "picker: null classloader", null);
            return;
        }
        installWindowProbe(loader, "picker");
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, loader);
            Method onCreate = instrumentation.getDeclaredMethod("callActivityOnCreate",
                    android.app.Activity.class, android.os.Bundle.class);
            install(onCreate, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (arg instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) arg;
                        activity.getWindow().getDecorView().post(
                                () -> probePicker(activity));
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable error) {
            log(Log.WARN, TAG, "picker hook failed: " + error, null);
        }
    }

    /** One-shot look at what the pickup row is made of. */
    private void probePicker(android.app.Activity activity) {
        try {
            StringBuilder sb = new StringBuilder("picker act=")
                    .append(activity.getClass().getName());
            walkViews(activity.getWindow().getDecorView(), sb, 0);
            log(Log.INFO, TAG, sb.toString(), null);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ window probe

    private final AtomicInteger windowsProbed = new AtomicInteger();

    /**
     * The pickup row is not an Activity (callActivityOnCreate never fires), so
     * it has to be a window someone added through WindowManager. Every such
     * window lands in WindowManagerGlobal#addView, which is public and stable
     * across versions - that is the earliest point where the real view tree of
     * the row can be inspected.
     */
    private void installWindowProbe(ClassLoader loader, String who) {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal", false, loader);
            Method add = null;
            for (Method m : global.getDeclaredMethods()) {
                if (!m.getName().equals("addView")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 2 && android.view.View.class.isAssignableFrom(params[0])) {
                    add = m;
                    break;
                }
            }
            if (add == null) {
                log(Log.WARN, TAG, who + ": addView not found", null);
                return;
            }
            add.setAccessible(true);
            final Method target = add;
            install(target, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (arg instanceof android.view.View) {
                        watchWindow((android.view.View) arg, who);
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            log(Log.INFO, TAG, who + ": hooked WindowManagerGlobal#addView("
                    + target.getParameterTypes().length + ")", null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, who + " window probe failed: " + error, null);
        }
    }

    /** Dumps a freshly added window a beat later, once it has been laid out. */
    private void watchWindow(final android.view.View view, final String who) {
        if (view == null) return;
        final int index = windowsProbed.incrementAndGet();
        if (index > 24) return;
        view.postDelayed(() -> {
            try {
                StringBuilder sb = new StringBuilder("win#").append(index)
                        .append(" pkg=").append(who)
                        .append(" cls=").append(view.getClass().getName());
                walkViews(view, sb, 0);
                String text = sb.toString();
                boolean interesting = text.contains("分词") || text.contains("Aicy")
                        || text.contains("关键词") || text.contains("翻译");
                boolean voice = view.getClass().getName().contains("voicequick")
                        || view.getClass().getName().contains("QuickVoice");
                if (interesting) {
                    log(Log.INFO, TAG, text, null);
                    if (index <= 6) {
                        StringBuilder tree = new StringBuilder("row tree:");
                        walkTree(view, tree, 0);
                        log(Log.INFO, TAG, tree.toString(), null);
                    }
                    attachWithRetries(view, who);
                } else {
                    log(Log.INFO, TAG, "win#" + index + " pkg=" + who
                            + " cls=" + view.getClass().getName()
                            + " size=" + view.getWidth() + "x" + view.getHeight()
                            + " texts=" + viewTexts(view), null);
                }
                if (voice) {
                    // Compose builds its tree asynchronously, so one snapshot
                    // at 220ms can catch nothing at all. Sample three times.
                    for (long delay : new long[]{280L, 900L, 1800L}) {
                        view.postDelayed(() -> {
                            try {
                                StringBuilder tree = new StringBuilder("voice tree @")
                                        .append(System.currentTimeMillis() % 100000)
                                        .append(" group=").append(view instanceof android.view.ViewGroup)
                                        .append(" children=").append(view instanceof android.view.ViewGroup
                                                ? ((android.view.ViewGroup) view).getChildCount() : -1)
                                        .append(" chain=").append(classChain(view.getClass()));
                                try {
                                    android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                                    if (lp instanceof android.view.WindowManager.LayoutParams) {
                                        android.view.WindowManager.LayoutParams w =
                                                (android.view.WindowManager.LayoutParams) lp;
                                        tree.append("\n  wtype=").append(w.type)
                                                .append(" gravity=").append(w.gravity)
                                                .append(" flags=0x").append(Integer.toHexString(w.flags))
                                                .append(" wh=").append(w.width).append('x').append(w.height);
                                    }
                                } catch (Throwable ignored) {
                                }
                                walkTree(view, tree, 0);
                                log(Log.INFO, TAG, tree.toString(), null);
                            } catch (Throwable ignored) {
                            }
                        }, delay);
                    }
                }
            } catch (Throwable ignored) {
            }
        }, 220L);
    }

    /** Class names + geometry, so the row's real container can be identified. */
    private void walkTree(android.view.View view, StringBuilder sb, int depth) {
        if (view == null || depth > 9) return;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        sb.append('\n').append("  ".repeat(Math.max(0, depth)))
                .append(view.getClass().getSimpleName())
                .append(' ').append(at[0]).append(',').append(at[1])
                .append(' ').append(view.getWidth()).append('x').append(view.getHeight())
                .append(' ').append(view.getVisibility());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walkTree(group.getChildAt(i), sb, depth + 1);
            }
        }
    }

    private String classChain(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        Class<?> cursor = type;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            if (sb.length() > 0) sb.append(" < ");
            sb.append(cursor.getName());
            cursor = cursor.getSuperclass();
        }
        return sb.toString();
    }

    /**
     * Slots the power pill into the voice sheet's own container.
     *
     * <p>The sheet root is a full-screen FrameLayout whose only child is a
     * ComposeView, so a new child sits on top of everything Compose draws and
     * is destroyed with the sheet - no overlay window, no accessibility.
     */
    private void attachPowerEntry(android.view.ViewGroup host) {
        if (host == null) return;
        try {
            for (int i = 0; i < host.getChildCount(); i++) {
                if (host.getChildAt(i) instanceof dev.loong.aicyunlock.power.VoicePowerEntry) {
                    return;
                }
            }
            dev.loong.aicyunlock.power.VoicePowerEntry entry =
                    new dev.loong.aicyunlock.power.VoicePowerEntry(host.getContext());
            host.addView(entry, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            log(Log.INFO, TAG, "voice power entry attached, host children=" + host.getChildCount(), null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "voice power entry failed: " + error, null);
        }
    }

    private static final String ENTRY_TAG = "aicy-power-entry";

    /**
     * The row is built and animated in asynchronously, so a single 220ms
     * snapshot can land while it still only holds one or two items. Try a
     * handful of times until the item is actually in place.
     */
    private void attachWithRetries(final android.view.View root, final String who) {
        final long[] delays = new long[]{0L, 400L, 900L, 1600L, 2600L};
        for (int i = 0; i < delays.length; i++) {
            final int attempt = i;
            root.postDelayed(() -> {
                try {
                    if (attachRowEntry(root, who, attempt == 0)) return;
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "row attach attempt " + attempt + " failed: " + error, null);
                }
            }, delays[i]);
        }
    }

    /**
     * Adds a power button next to the bar that holds 问 Aicy / 分词.
     *
     * <p>That bar turned out to be Compose, not a view row: its labels are
     * AndroidView interop children parked inside AndroidViewsHandler, and
     * Compose lays anything added there out at 0x0 - which is why an earlier
     * attempt produced an invisible item. The bar's strip is also full, so the
     * button is pinned just above its right end inside the plain FrameLayout
     * that hosts the whole bar. Nothing Compose owns is touched, so it stays
     * put across recompositions.
     *
     * @return true when the button is present (already there or just added)
     */
    private boolean attachRowEntry(final android.view.View root, String who, boolean verbose) {
        java.util.List<android.view.View> labels = new java.util.ArrayList<>();
        collectLabels(root, labels);

        android.widget.TextView source = null;
        for (android.view.View candidate : labels) {
            if (!(candidate instanceof android.widget.TextView)) continue;
            if (candidate.getWidth() <= 0 || candidate.getHeight() <= 0) continue;
            if (!isComposeHosted(candidate)) continue;
            source = (android.widget.TextView) candidate;
            break;
        }
        if (source == null) {
            if (verbose) log(Log.INFO, TAG, who + ": labels=" + labels.size()
                    + ", none compose-hosted and measured yet", null);
            return false;
        }

        android.view.ViewGroup strip = null;
        android.view.ViewParent parent = source.getParent();
        int guard = 0;
        while (parent != null && guard++ < 14) {
            if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) parent;
                String name = group.getClass().getName();
                boolean framework = name.startsWith("android.widget.")
                        || name.startsWith("android.view.");
                boolean band = group.getHeight() > 0 && group.getHeight() <= root.getHeight() / 2
                        && group.getWidth() >= root.getWidth() / 2;
                if (framework && band) {
                    strip = group;
                    break;
                }
            }
            parent = parent.getParent();
        }
        if (strip == null) {
            if (verbose) log(Log.INFO, TAG, who + ": bar strip not found", null);
            return false;
        }

        // Prefer the strip's parent: the strip itself clips what leaves its
        // bounds, and the bar already fills it edge to edge.
        android.view.ViewGroup host = strip;
        android.view.ViewGroup.LayoutParams lp = null;
        android.view.ViewParent up = strip.getParent();
        if (up instanceof android.view.ViewGroup
                && ((android.view.ViewGroup) up).getHeight() > strip.getHeight() * 2) {
            android.view.ViewGroup outer = (android.view.ViewGroup) up;
            host = outer;
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    Math.round(52 * strip.getResources().getDisplayMetrics().density),
                    Math.round(52 * strip.getResources().getDisplayMetrics().density),
                    android.view.Gravity.END | android.view.Gravity.BOTTOM);
            int lift = Math.max(0, outer.getHeight() - strip.getTop())
                    + Math.round(12 * strip.getResources().getDisplayMetrics().density);
            flp.bottomMargin = lift;
            flp.rightMargin = Math.round(16 * strip.getResources().getDisplayMetrics().density);
            lp = flp;
        } else {
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    Math.round(52 * strip.getResources().getDisplayMetrics().density),
                    Math.round(52 * strip.getResources().getDisplayMetrics().density),
                    android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            flp.rightMargin = Math.round(8 * strip.getResources().getDisplayMetrics().density);
            lp = flp;
        }

        for (int i = 0; i < host.getChildCount(); i++) {
            if (ENTRY_TAG.equals(host.getChildAt(i).getTag())) {
                reposition(host.getChildAt(i), strip, host);
                return true;
            }
        }

        android.content.Context ctx = host.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        dev.loong.aicyunlock.power.PowerGlyph glyph =
                new dev.loong.aicyunlock.power.PowerGlyph(ctx, 0xFFFFFFFF);
        android.widget.FrameLayout button = new android.widget.FrameLayout(ctx);
        button.setTag(ENTRY_TAG);
        button.setClickable(true);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xE61C1C1E);
        button.setBackground(bg);
        android.widget.FrameLayout.LayoutParams inner =
                new android.widget.FrameLayout.LayoutParams(
                        Math.round(22 * density), Math.round(22 * density),
                        android.view.Gravity.CENTER);
        button.addView(glyph, inner);

        button.setOnClickListener(v -> showSheet(root, v.getContext()));
        host.addView(button, lp);

        log(Log.INFO, TAG, who + ": power button added to " + host.getClass().getName()
                + " " + host.getWidth() + "x" + host.getHeight()
                + " above strip at y=" + strip.getTop(), null);

        host.postDelayed(() -> log(Log.INFO, TAG, "power button now "
                + button.getWidth() + "x" + button.getHeight()
                + " at " + location(button), null), 400L);
        return true;
    }

    /** Keeps the button glued above the bar when the strip moves. */
    private void reposition(android.view.View button, android.view.ViewGroup strip,
                           android.view.ViewGroup host) {
        try {
            android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
            if (!(params instanceof android.widget.FrameLayout.LayoutParams)) return;
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) params;
            int lift = Math.max(0, host.getHeight() - strip.getTop())
                    + Math.round(12 * button.getResources().getDisplayMetrics().density);
            if (lp.bottomMargin != lift) {
                lp.bottomMargin = lift;
                button.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /** True when the view is rendered through Compose interop. */
    private boolean isComposeHosted(android.view.View view) {
        android.view.ViewParent parent = view.getParent();
        int guard = 0;
        while (parent != null && guard++ < 14) {
            if (parent.getClass().getName().startsWith("androidx.compose")) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private String location(android.view.View view) {
        try {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return at[0] + "," + at[1];
        } catch (Throwable ignored) {
            return "?";
        }
    }


    /** Opens the slide-to-confirm sheet over the whole pickup window. */
    private void showSheet(android.view.View root, android.content.Context context) {
        try {
            if (!(root instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup host = (android.view.ViewGroup) root;
            for (int i = 0; i < host.getChildCount(); i++) {
                if (host.getChildAt(i) instanceof dev.loong.aicyunlock.power.VoicePowerEntry) return;
            }
            dev.loong.aicyunlock.power.VoicePowerEntry sheet =
                    new dev.loong.aicyunlock.power.VoicePowerEntry(context, false);
            host.addView(sheet, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            log(Log.INFO, TAG, "power sheet opened", null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "power sheet failed: " + error, null);
        }
    }

    private void collectLabels(android.view.View view, java.util.List<android.view.View> out) {
        if (view == null || out.size() > 40) return;
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null) {
                String value = text.toString().trim();
                if (value.equals("问 Aicy") || value.equals("问Aicy")
                        || value.equals("分词") || value.equals("关键词")
                        || value.equals("翻译") || value.equals("全选")
                        || value.equals("复制") || value.equals("解释")) {
                    out.add(view);
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLabels(group.getChildAt(i), out);
            }
        }
    }

    private String viewTexts(android.view.View view) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.List<android.view.View> queue = new java.util.ArrayList<>();
            queue.add(view);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 400) {
                android.view.View v = queue.remove(0);
                if (v instanceof android.widget.TextView) {
                    CharSequence t = ((android.widget.TextView) v).getText();
                    if (t != null && t.length() > 0 && t.length() < 20) {
                        sb.append('[').append(t).append(']');
                    }
                }
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        android.view.View c = g.getChildAt(i);
                        if (c != null) queue.add(c);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void walkViews(android.view.View view, StringBuilder sb, int depth) {
        if (view == null || depth > 26) return;
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0 && text.length() < 40) {
                int[] at = new int[2];
                view.getLocationOnScreen(at);
                sb.append("\n  ").append(at[0]).append(',').append(at[1])
                        .append(' ').append(view.getWidth()).append('x').append(view.getHeight())
                        .append(' ').append(text);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walkViews(group.getChildAt(i), sb, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------ wake word

    /**
     * Sends Aicy's stock wake-word entry to its custom keyphrase page.
     *
     * <p>The keyphrase UI ships in this same APK but the entry only picks it on
     * two models; the page itself has no device check, so redirecting the
     * launch target is enough. Both classes were verified on 13.0.55
     * (versionCode 13900055): the stock entry is an explicit-component intent
     * to {@code VoiceWakeUpActivity}, and {@code KeyphraseSettingActivity}
     * reads no extras.
     */
    private void installWakeWordRedirect() {
        XposedInterface.Hooker redirect = chain -> {
            try {
                Object first = chain.getArg(0);
                if (first instanceof android.content.Intent) {
                    android.content.Intent intent = (android.content.Intent) first;
                    android.content.ComponentName target = intent.getComponent();
                    if (target != null
                            && TARGET.equals(target.getPackageName())
                            && WAKE_WORD_ENTRY.equals(target.getClassName())
                            && RemoteConfig.master()
                            && RemoteConfig.wakeWord()) {
                        Object[] args = chain.getArgs().toArray();
                        args[0] = new android.content.Intent(intent)
                                .setClassName(TARGET, WAKE_WORD_CUSTOM);
                        log(Log.INFO, "WakeWord",
                                "stock entry -> keyphrase settings", null);
                        return chain.proceedWith(chain.getThisObject(), args);
                    }
                }
            } catch (Throwable error) {
                log(Log.WARN, "WakeWord", "wake word redirect failed", error);
            }
            return chain.proceed();
        };

        int armed = 0;
        try {
            install(android.app.Activity.class.getDeclaredMethod(
                    "startActivity", android.content.Intent.class), redirect);
            armed++;
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "Activity#startActivity hook failed", error);
        }
        try {
            install(android.app.Activity.class.getDeclaredMethod(
                    "startActivity", android.content.Intent.class, android.os.Bundle.class),
                    redirect);
            armed++;
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "Activity#startActivity(Bundle) hook failed", error);
        }
        try {
            install(android.content.ContextWrapper.class.getDeclaredMethod(
                    "startActivity", android.content.Intent.class), redirect);
            armed++;
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "ContextWrapper#startActivity hook failed", error);
        }
        try {
            install(android.content.ContextWrapper.class.getDeclaredMethod(
                    "startActivity", android.content.Intent.class, android.os.Bundle.class),
                    redirect);
            armed++;
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "ContextWrapper#startActivity(Bundle) hook failed", error);
        }
        log(armed > 0 ? Log.INFO : Log.ERROR, "WakeWord",
                "wake word redirect armed on " + armed + " entry points", null);
    }

    /**
     * Vendor switch gate. The keyphrase page asks the wake-word engine (here
     * {@code com.qualcomm.qti.sva.provider.SETTINGS}) through
     * {@code ContentResolver#call(authority, "getSharedPreferences", null,
     * bundle)} whether 4.1.0 voice is available. Meizu ships the engine without
     * those keys, so every read comes back {@code result=-1} / {@code false} and
     * Aicy bounces straight to the preset-phrase guide page. We answer the
     * engine's own question instead, only for the two switches the settings UI
     * needs; the DSP keeps reading its own file in its own process.
     */
    private void installWakeWordGate() {
        XposedInterface.Hooker gate = chain -> {
            Object out = chain.proceed();
            try {
                if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) return out;
                if (!(out instanceof android.os.Bundle)) return out;
                java.util.List<Object> args = chain.getArgs();
                if (args.size() < 4 || !"getSharedPreferences".equals(args.get(1))) {
                    return out;
                }
                Object in = args.get(3);
                if (!(in instanceof android.os.Bundle)) return out;
                android.os.Bundle ask = (android.os.Bundle) in;
                if (!WAKE_WORD_PREFS.equals(ask.getString("sp_name"))) return out;
                String key = ask.getString("sp_key");
                String type = ask.getString("data_type");
                if (key == null || type == null) return out;

                android.os.Bundle answer = (android.os.Bundle) out;
                Object raw = answer.get("result");
                String now = raw == null ? null : String.valueOf(raw);
                String forced = null;
                if ("int".equals(type) && WAKE_WORD_WHICH.equals(key) && !isPositive(now)) {
                    forced = "0";
                } else if ("boolean".equals(type) && isSupportFlag(key)
                        && !Boolean.parseBoolean(now)) {
                    forced = "true";
                }
                if (forced == null) return out;
                // The engine answers with a real Boolean / Integer, and Bundle
                // getters only convert for the types they know, so mirror it.
                if (raw instanceof Boolean) {
                    answer.putBoolean("result", true);
                } else if (raw instanceof Integer) {
                    answer.putInt("result", 0);
                } else {
                    answer.putString("result", forced);
                }
                log(Log.INFO, "WakeWord", key + " (" + type + ") " + now + " -> " + forced, null);
                return answer;
            } catch (Throwable error) {
                log(Log.WARN, "WakeWord", "switch gate hook failed", error);
            }
            return out;
        };

        int armed = 0;
        try {
            install(android.content.ContentResolver.class.getDeclaredMethod(
                    "call", String.class, String.class, String.class, android.os.Bundle.class),
                    gate);
            armed++;
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "ContentResolver#call(authority) hook failed", error);
        }
        log(armed > 0 ? Log.INFO : Log.ERROR, "WakeWord",
                "wake word switch gate armed on " + armed + " entry points", null);
    }

    /**
     * Give the chooser page back its 自定义唤醒词 chip.
     *
     * <p>{@code KeyphraseSettingsFrg} is the page that offers 小溪小溪 / 小梦 /
     * 自定义 as the three selectable wake words, but this build hides the third
     * one ({@code tvUdk.setVisibility(GONE)} runs unconditionally in
     * {@code onViewCreated}), so the custom flow cannot be entered at all. The
     * page it used to open - {@code UserDefinedKeyphraseSettingFrg}, the typed
     * wake word plus the soft keyboard - is still shipped, and the activity's
     * own {@code q(keyphrase.b, Bundle, String)} is what pushes it, onto the
     * back stack, so 返回 comes back to the chooser. Revealing the chip after
     * the layout pass keeps the preset entries on screen: nothing is replaced,
     * the choice stays Aicy's.
     */
    private void installWakeWordCustomEntry(ClassLoader loader) {
        try {
            Class<?> settings = Class.forName(
                    "com.meizu.voiceassistant.keyphrase.KeyphraseSettingsFrg", false, loader);
            Class<?> base = Class.forName(
                    "com.meizu.voiceassistant.keyphrase.b", false, loader);
            Class<?> page = Class.forName(
                    "com.meizu.voiceassistant.keyphrase.UserDefinedKeyphraseSettingFrg",
                    false, loader);
            Class<?> activity = Class.forName(WAKE_WORD_CUSTOM, false, loader);
            Method push = activity.getDeclaredMethod(
                    "q", base, android.os.Bundle.class, String.class);
            install(settings.getDeclaredMethod("onViewCreated",
                    android.view.View.class, android.os.Bundle.class), chain -> {
                Object out = chain.proceed();
                try {
                    if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) return out;
                    android.view.View root = (android.view.View) chain.getArg(0);
                    android.content.res.Resources res = root.getResources();
                    int chipId = res.getIdentifier("tv_udk", "id", TARGET);
                    android.view.View chip = chipId == 0 ? null : root.findViewById(chipId);
                    if (chip == null) {
                        log(Log.WARN, "WakeWord", "custom keyphrase chip not found", null);
                        return out;
                    }
                    chip.setVisibility(android.view.View.VISIBLE);
                    chip.setAlpha(1f);
                    StringBuilder hidden = new StringBuilder();
                    android.view.ViewParent up = chip.getParent();
                    for (int depth = 0; up instanceof android.view.ViewGroup
                            && up != root && depth < 6; depth++) {
                        android.view.ViewGroup group = (android.view.ViewGroup) up;
                        hidden.append(' ').append(depth).append(':')
                                .append(group.getVisibility()).append('/').append(group.getAlpha());
                        if (group.getVisibility() != android.view.View.VISIBLE) {
                            group.setVisibility(android.view.View.VISIBLE);
                        }
                        if (group.getAlpha() < 0.05f) group.setAlpha(1f);
                        up = group.getParent();
                    }
                    int label = res.getIdentifier("user_defined_keyphrase", "string", TARGET);
                    if (label != 0 && chip instanceof android.widget.TextView
                            && ((android.widget.TextView) chip).getText().length() == 0) {
                        ((android.widget.TextView) chip).setText(label);
                    }
                    android.view.View preset = find(res, root, "tv_xiaoxi");
                    if (preset != null && preset.getLayoutParams() != null) {
                        // The vendor chip is measured 0 wide in its own container, so
                        // un-hiding it is not enough to draw it. Take the geometry of
                        // the 小溪小溪 chip and sit in that chip's row next to it.
                        android.view.ViewGroup.LayoutParams own = chip.getLayoutParams();
                        if (own != null) {
                            own.width = preset.getLayoutParams().width;
                            own.height = preset.getLayoutParams().height;
                            chip.setLayoutParams(own);
                        }
                        if (preset.getParent() instanceof android.view.ViewGroup
                                && chip.getParent() != preset.getParent()) {
                            android.view.ViewGroup row = (android.view.ViewGroup) preset.getParent();
                            if (chip.getParent() instanceof android.view.ViewGroup) {
                                ((android.view.ViewGroup) chip.getParent()).removeView(chip);
                            }
                            row.addView(chip, row.indexOfChild(preset) + 1,
                                    copyOf(preset.getLayoutParams()));
                        }
                    }
                    Object host = chain.getThisObject().getClass()
                            .getMethod("requireActivity").invoke(chain.getThisObject());
                    chip.setOnClickListener(view -> {
                        try {
                            push.invoke(host, page.getDeclaredConstructor().newInstance(),
                                    null, "UserDefinedKeyphraseSettingFrg");
                            log(Log.INFO, "WakeWord", "custom keyphrase page opened", null);
                        } catch (Throwable error) {
                            log(Log.WARN, "WakeWord", "custom keyphrase page failed", error);
                        }
                    });
                    final android.view.View measured = preset;
                    root.postDelayed(() -> log(Log.INFO, "WakeWord", "chip geometry "
                            + geometry(chip) + " ancestors " + hidden
                            + " preset " + (measured == null ? "missing" : geometry(measured)), null),
                            400);
                    log(Log.INFO, "WakeWord", "custom keyphrase chip revealed", null);
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "custom keyphrase chip failed", error);
                }
                return out;
            });
            log(Log.INFO, "WakeWord", "custom keyphrase entry armed", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "custom keyphrase entry hook failed", error);
        }
    }

    /**
     * Open the microphone for the custom wake word.
     *
     * <p>The five sample recordings are not captured by Aicy at all but by the
     * vendor trainer, {@code com.qualcomm.qti.sva.MzTrainingActivity}, which is
     * started with {@code previous_sm_name = "Create your own 7.0 model"}. That
     * string makes the engine's own {@code isUdk()} return true, and the first
     * round of a training session is only started from the callback of
     * {@code PlayRecordingTipManager.playGuideSpeech} - a call that sits behind
     * {@code if (!isUdk())}, because no prompt audio exists for a phrase the
     * engine has never heard (the APK only ships 小溪小溪/小梦/灵克/… clips).
     * Skipping the prompt therefore also skips the capture: the page sits on
     * 1/5, nothing is recorded, no round advances.
     *
     * <p>{@code enterRecording()} is the private funnel the vendor path uses
     * itself: it opens {@code AudioRecord} (VOICE_RECOGNITION, 16 kHz) through
     * {@code RecordingsMgr.startTrainingRecording}, sets {@code mIsTraining}
     * and arms the 3 s round timer. Calling exactly that, once, and only when
     * the engine is in its own UDK branch and still has not started, opens the
     * first round; presets still play their prompt and record as before.
     * {@link #installWakeWordRoundChain} covers the rounds after it.
     */
    private void installSva(ClassLoader loader, String callback) {
        if (loader == null) {
            log(Log.ERROR, TAG, callback + ": SVA null classloader, hooks not installed", null);
            return;
        }
        if (!svaInstalled.compareAndSet(false, true)) {
            log(Log.INFO, TAG, callback + ": SVA hooks already installed, skip", null);
            return;
        }
        log(Log.INFO, TAG, callback + ": target " + SVA + " loaded, installing hooks", null);
        installWakeWordUdkText(loader);
        installWakeWordUdkModel(loader);
        installWakeWordRoundChain(loader);
        installWakeWordUdkCheck(loader);
        installWakeWordCueGate(loader);
    }

   /**
     * Hand the engine the phrase it is supposed to be listening for.
     *
     * <p>{@code MzTrainingActivity} takes the word to train from the
     * {@code text_input} intent extra and passes it to
     * {@code SMLParametersManager#setSMLUDKParameters}, which serialises it into
     * the 1492-byte tuning blob at offset 1212; that blob is the ADSP's only
     * copy of the target keyword. Aicy launches the training page without the
     * extra, {@code fillSMLUDKParams} sees null and leaves the field zeroed, so
     * the engine scores every recording against an empty word:
     * {@code lsm_checkUserRecording} exits with status 14 ("no speech") and the
     * final {@code lsm_getUserDefinedKeywordSize} with status 1, which surfaces
     * as "还是听不清" followed by the vendor failure page.
     *
     * <p>The typed phrase is already in {@code mKeyphraseOrNewSmName}, so supply
     * it when the extra is missing and keep {@code mTextInput} in step with it.
     */
    private void installWakeWordUdkText(ClassLoader loader) {
        try {
            Class<?> activity = Class.forName(
                    "com.qualcomm.qti.sva.MzTrainingActivity", false, loader);
            final java.lang.reflect.Field keyphrase =
                    activity.getDeclaredField("mKeyphraseOrNewSmName");
            keyphrase.setAccessible(true);
            final java.lang.reflect.Field textInput =
                    activity.getDeclaredField("mTextInput");
            textInput.setAccessible(true);
            install(activity.getDeclaredMethod("setSMLParameters", String.class), chain -> {
                try {
                    if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) {
                        return chain.proceed();
                    }
                    Object text = chain.getArg(0);
                    if (text instanceof String && !((String) text).isEmpty()) {
                        return chain.proceed();
                    }
                    final Object self = chain.getThisObject();
                    Object name = keyphrase.get(self);
                    if (!(name instanceof String) || ((String) name).isEmpty()) {
                        return chain.proceed();
                    }
                    String phrase = (String) name;
                    textInput.set(self, phrase);
                    log(Log.INFO, "WakeWord",
                            "engine keyword supplied for custom training: " + phrase, null);
                    return chain.proceedWith(self, new Object[]{phrase});
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "custom keyword text not supplied", error);
                }
                return chain.proceed();
            });
            log(Log.INFO, "WakeWord", "custom keyword text armed", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "custom keyword text unavailable", error);
        }
    }

    /**
     * Acceptance gate for every round of a custom wake word.
     *
     * <p>{@code MzTrainingActivity$VerifyRecordingTask} only lets a round pass
     * when {@code ListenSoundModel.verifyUdkRecording} returns at least
     * {@link #UDK_ROUND_PASS_SNR}. This device used to return nothing but the
     * engine's refusal for every recording because the keyword never reached the
     * DSP (see {@link #installWakeWordUdkText}); that fallback stays for the
     * rounds the engine still declines to score, while the JNI reports -25 and
     * the round is rejected as "no speech found". Rounds 2-5 chain off the same
     * result, so the session can never reach {@code createUdkSm} and the typed
     * word can never be trained.
     *
     * <p>A negative return means "the engine refused to score this", not "this
     * take is quiet", so it is replaced only after a local loudness check has
     * found real speech in the very same buffer. A genuine low score (zero or
     * positive, below the threshold) still fails.
     */
    private void installWakeWordUdkCheck(ClassLoader loader) {
        install(loader, "com.qualcomm.listen.ListenSoundModel", "verifyUdkRecording",
                chain -> {
                    int result = (Integer) swapUdkModel(chain, 0);
                    if (result >= 0 || !RemoteConfig.master() || !RemoteConfig.wakeWord()) {
                        return result;
                    }
                    java.nio.ShortBuffer recording =
                            (java.nio.ShortBuffer) chain.getArg(1);
                    int estimated = speechSnr(recording);
                    if (estimated < 0) {
                        log(Log.WARN, "WakeWord", "custom round: engine error " + result
                                + ", no speech found locally either, keeping the failure", null);
                        return result;
                    }
                    log(Log.INFO, "WakeWord", "custom round: engine error " + result
                            + ", local SNR " + estimated + " dB, accepting", null);
                    return Math.max(UDK_ROUND_PASS_SNR, estimated);
                },
                java.nio.ByteBuffer.class, java.nio.ShortBuffer.class);
    }

    /**
     * Rounds 2-5 of a custom wake word.
     *
     * <p>A round the activity accepted chains the next one through
     * {@code performTrainingCompleteAnim} and then through
     * {@code PlayRecordingTipManager.playGuideSpeech}, whose MediaPlayer is
     * prepared from {@code tip_round_N.mp3} inside the keyphrase's own folder.
     * A phrase the user typed has no such folder, preparation throws, the
     * callback that would start the capture never runs and the session sits at
     * 1/5 - while a <em>failed</em> round does retry by itself, because that
     * path goes through the record-suspend timer and never asks for a tip.
     *
     * <p>So after an accepted round, call what that callback would have called:
     * {@code initializeRecord()}, which itself enters and starts the next
     * capture and refreshes the round UI.
     */
    private void installWakeWordRoundChain(ClassLoader loader) {
        try {
            Class<?> activity = Class.forName(
                    "com.qualcomm.qti.sva.MzTrainingActivity", false, loader);
            final Method isUdk = activity.getDeclaredMethod("isUdk");
            isUdk.setAccessible(true);
            final Method initialize = activity.getDeclaredMethod("initializeRecord");
            initialize.setAccessible(true);
            final java.lang.reflect.Field counterField =
                    activity.getDeclaredField("mRecordingCounter");
            counterField.setAccessible(true);
            Class<?> task = Class.forName("com.qualcomm.qti.sva.MzTrainingActivity"
                    + "$VerifyRecordingTask", false, loader);
            final java.lang.reflect.Field host = task.getDeclaredField("this$0");
            host.setAccessible(true);
            final Method isFinished = Class.forName(
                    "com.qualcomm.qti.sva.utils.RecordingCounter", false, loader)
                    .getDeclaredMethod("isFinished");
            install(task.getDeclaredMethod("onPostExecute", Integer.class), chain -> {
                Object out = chain.proceed();
                try {
                    if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) return out;
                    final android.app.Activity self =
                            (android.app.Activity) host.get(chain.getThisObject());
                    if (!Boolean.TRUE.equals(isUdk.invoke(self))) return out;
                    if ((Integer) chain.getArg(0) != 0) return out;
                    final Object counter = counterField.get(self);
                    if (counter != null && Boolean.TRUE.equals(isFinished.invoke(counter))) {
                        return out;
                    }
                    self.getWindow().getDecorView().postDelayed(() -> {
                        try {
                            initialize.invoke(self);
                            log(Log.INFO, "WakeWord",
                                    "custom next round capture started", null);
                        } catch (Throwable error) {
                            log(Log.WARN, "WakeWord", "custom next round failed", error);
                        }
                    }, 1500);
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "custom next round not scheduled", error);
                }
                return out;
            });
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "custom round chain unavailable", error);
        }
    }

    /**
     * Puts the spoken cue and the round animation in front of every capture.
     *
     * <p>{@code enterRecording()} is the funnel each round starter uses: the
     * page's own layout callback (which is what opens round 1),
     * {@code initializeRecord()} for the rounds after it. Holding that one call
     * open until the voice is finished covers the first round too - the round
     * that used to start before anything could be announced, which is why 第 1 轮
     * was silent while 2-5 had a cue. Each round is announced once: a separate
     * kick of our own only started round 1 a second time.
     */
    private void installWakeWordCueGate(ClassLoader loader) {
        final WakeWordRounds rounds = new WakeWordRounds(loader);
        try {
            Class<?> activity = Class.forName(
                    "com.qualcomm.qti.sva.MzTrainingActivity", false, loader);
            final Method isUdk = activity.getDeclaredMethod("isUdk");
            isUdk.setAccessible(true);
            final Method enter = activity.getDeclaredMethod("enterRecording");
            enter.setAccessible(true);
            install(enter, chain -> {
                final Object self = chain.getThisObject();
                try {
                    if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) {
                        return chain.proceed();
                    }
                    if (!Boolean.TRUE.equals(isUdk.invoke(self))) return chain.proceed();
                    if (rounds.isDeferring()) return chain.proceed();
                    rounds.defer((android.app.Activity) self, () -> enter.invoke(self));
                    return null;
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "cue gate let the round through", error);
                    return chain.proceed();
                }
            });
            log(Log.INFO, "WakeWord", "round cue gate armed", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "round cue gate unavailable", error);
        }
    }

    /**
     * Opens one capture round the way the preset flow would.
     *
     * <p>Two things the vendor does before its microphone runs and the UDK
     * branch never reaches: the spoken tip (a typed phrase has no clip to play,
     * so {@link WakeCue} plays a shipped clip instead), and the round-start
     * visuals of
     * {@code MzTrainingActivity$2.onGlobalLayout} - fade the tip area in,
     * restart the loading PAG, hide the intro view. Without the first the user
     * cannot tell when to speak; without the second the page looks frozen while
     * it is really recording, which is what the 流光特效 request was about.
     *
     * <p>Everything here is vendor code reached by reflection, so a piece that
     * Meizu renamed only costs its own step: the cue is still asked for and the
     * microphone still opens.
     */
    private final class WakeWordRounds {
        /** Set only while this class runs a capture call it deferred itself. */
        private final AtomicBoolean insideFunnel = new AtomicBoolean(false);
        private final java.lang.reflect.Field counterField;
        private final Method counterValue;
        private final java.lang.reflect.Field tipArea;
        private final java.lang.reflect.Field loading;
        private final Method pagSetFrame;
        private final Method pagPlay;
        private final java.lang.reflect.Field compose;
        private final Method animInstance;
        private final Method animStart;

        WakeWordRounds(ClassLoader loader) {
            java.lang.reflect.Field counterField = null;
            Method counterValue = null;
            java.lang.reflect.Field tipAreaField = null;
            java.lang.reflect.Field loadingField = null;
            Method setFrame = null;
            Method play = null;
            java.lang.reflect.Field composeField = null;
            Method instance = null;
            Method start = null;
            try {
                Class<?> activity = Class.forName(
                        "com.qualcomm.qti.sva.MzTrainingActivity", false, loader);
                counterField = field(activity, "mRecordingCounter");
                tipAreaField = field(activity, "mTipArea");
                loadingField = field(activity, "mLoadingView");
                composeField = field(activity, "composeView");
                counterValue = method(Class.forName(
                        "com.qualcomm.qti.sva.utils.RecordingCounter", false, loader),
                        "getCounter");
                Class<?> anim = Class.forName(
                        "com.qualcomm.qti.sva.utils.MzTrainingAnimManager", false, loader);
                instance = method(anim, "getInstance");
                start = method(anim, "performTrainingAnim", android.view.View.class);
                Class<?> pag = Class.forName("org.libpag.PAGImageView", false, loader);
                setFrame = method(pag, "setCurrentFrame", int.class);
                play = method(pag, "play");
            } catch (Throwable error) {
                log(Log.WARN, "WakeWord", "round cue/animation partly unavailable", error);
            }
            this.counterField = counterField;
            this.counterValue = counterValue;
            tipArea = tipAreaField;
            loading = loadingField;
            pagSetFrame = setFrame;
            pagPlay = play;
            compose = composeField;
            animInstance = instance;
            animStart = start;
            log(Log.INFO, "WakeWord", "round cue armed, animation "
                    + (animInstance != null && animStart != null && tipArea != null
                            ? "ready" : "missing"), null);
        }

        /**
         * Announces the round, then runs the capture call it just held back.
         *
         * <p>The order is the whole point: a microphone opened while the cue is
         * still sounding trains the module's own voice instead of the user's.
         * A module that cannot answer must not strand the session either, so
         * {@link #CUE_WATCHDOG_MS} runs the deferred call on its own and the
         * first of the two wins.
         *
         * <p>{@code realCall} re-enters the hooked method rather than reusing the
         * hook's chain, because a chain is only good for the call it was handed
         * to and this runs later, from another thread.
         */
        void defer(android.app.Activity self, ThrowingCall realCall) {
            final int round = roundOf(self);
            final AtomicBoolean opened = new AtomicBoolean(false);
            final Runnable finish = () -> {
                if (!opened.compareAndSet(false, true)) return;
                self.runOnUiThread(() -> {
                    if (self.isFinishing()) return;
                    insideFunnel.set(true);
                    try {
                        realCall.run();
                        animateRound(self);
                    } catch (Throwable error) {
                        log(Log.WARN, "WakeWord", "deferred capture failed", error);
                    } finally {
                        insideFunnel.set(false);
                    }
                });
            };
            new Thread(() -> {
                askCue(self, round);
                try {
                    finish.run();
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "cue reply dropped", error);
                }
            }, "aicy-wake-cue").start();
            self.getWindow().getDecorView().postDelayed(finish, CUE_WATCHDOG_MS);
        }

        /** True while {@link #defer} is running the call it deferred. */
        boolean isDeferring() {
            return insideFunnel.get();
        }

        /** A re-invocation of a private vendor method, which can throw anything. */
        interface ThrowingCall {
            void run() throws Throwable;
        }

        /** Rounds already accepted, so 1..5 is what the cue should say. */
        private int roundOf(Object self) {
            try {
                Object counter = counterField == null ? null : counterField.get(self);
                if (counter == null || counterValue == null) return 1;
                return Math.min(WakeCue.ROUNDS, Math.max(1,
                        (Integer) counterValue.invoke(counter) + 1));
            } catch (Throwable error) {
                return 1;
            }
        }

        /**
         * Asks the module process to speak this round and reports its answer in
         * the module log, which is where a missing key or a refused call shows
         * up as something readable instead of as a silent page.
         */
        private void askCue(android.content.Context context, int round) {
            try {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putInt(WakeCue.KEY_ROUND, round);
                android.os.Bundle reply = context.getContentResolver().call(
                        android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY),
                        WakeCue.METHOD, null, extras);
                boolean played = reply != null && reply.getBoolean(WakeCue.KEY_PLAYED);
                log(played ? Log.INFO : Log.WARN, "WakeWord", "cue round " + round + ": "
                        + (reply == null ? "模块没有应答"
                                : reply.getString(WakeCue.KEY_DETAIL)), null);
            } catch (Throwable error) {
                log(Log.WARN, "WakeWord", "cue request failed, opening the mic anyway", error);
            }
        }

        /** The tip-area fade plus the looping PAG, exactly as the vendor runs them. */
        private void animateRound(android.app.Activity self) {
            try {
                // Measured, not assumed: the tip area fades with explicit 0 to 1
                // values and the PAG loads asynchronously from its path, so only the
                // live state says whether a round really has its visuals.
                describe("round start", self);
                if (animInstance != null && animStart != null && tipArea != null) {
                    animStart.invoke(animInstance.invoke(null),
                            (android.view.View) tipArea.get(self));
                }
                Object pag = loading == null ? null : loading.get(self);
                if (pag != null) {
                    if (pagSetFrame != null) pagSetFrame.invoke(pag, 0);
                    if (pagPlay != null) pagPlay.invoke(pag);
                }
                Object intro = compose == null ? null : compose.get(self);
                if (intro instanceof android.view.View) {
                    ((android.view.View) intro).setVisibility(android.view.View.GONE);
                }
            } catch (Throwable error) {
                log(Log.WARN, "WakeWord", "round animation skipped", error);
            }
        }

        /**
         * What the two round-start visuals actually look like at this instant.
         *
         * <p>The tip area fades with explicit 0 to 1 values, so a round that shows
         * no light is either a view that was never attached or a PAG that has no
         * composition yet; only the live state tells those apart, and round one is
         * the one that has not had time to load anything.
         */
        private void describe(String when, android.app.Activity self) {
            try {
                StringBuilder line = new StringBuilder("round visuals " + when);
                Object tip = tipArea == null ? null : tipArea.get(self);
                if (tip instanceof android.view.View) {
                    android.view.View view = (android.view.View) tip;
                    line.append(" · tip vis=").append(view.getVisibility())
                            .append(" alpha=").append(view.getAlpha())
                            .append(" transY=").append(view.getTranslationY())
                            .append(" attached=").append(view.isAttachedToWindow());
                }
                Object pag = loading == null ? null : loading.get(self);
                if (pag instanceof android.view.View) {
                    android.view.View view = (android.view.View) pag;
                    // PAGImageView.getComposition() answers null for as long as the
                    // view owns a path, so it says nothing about whether the clip
                    // loaded; the path, the animator and the frame counter do.
                    line.append(" · pag vis=").append(view.getVisibility())
                            .append(" alpha=").append(view.getAlpha())
                            .append(" path=").append(read(pag, "getPath"))
                            .append(" playing=").append(read(pag, "isPlaying"))
                            .append(" frames=").append(read(pag, "numFrames"))
                            .append(" frame=").append(read(pag, "currentFrame"));
                }
                log(Log.INFO, "WakeWord", line.toString(), null);
            } catch (Throwable ignored) {
                // Diagnostics never decide whether the round runs.
            }
        }

        /** One no-arg accessor of a vendor view, rendered for the log. */
        private static String read(Object target, String name) {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method found = c.getDeclaredMethod(name);
                    found.setAccessible(true);
                    return String.valueOf(found.invoke(target));
                } catch (Throwable notHere) {
                    // keep walking up
                }
            }
            return "?";
        }

        private static java.lang.reflect.Field field(Class<?> owner, String name) {
            try {
                java.lang.reflect.Field found = owner.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (Throwable error) {
                return null;
            }
        }

        private static Method method(Class<?> owner, String name, Class<?>... types) {
            try {
                Method found = owner.getDeclaredMethod(name, types);
                found.setAccessible(true);
                return found;
            } catch (Throwable error) {
                return null;
            }
        }
    }

    /** Estimated SNR in dB of a training capture, or -1 when it holds no speech. */
    private int speechSnr(java.nio.ShortBuffer recording) {
        if (recording == null) return -1;
        java.nio.ShortBuffer data = recording.duplicate();
        // VerifyRecordingTask only ever puts the buffer here, and the vendor JNI
        // reads the whole backing array, so position/limit carry no information.
        data.clear();
        int frames = data.remaining() / 160;
        if (frames < 10) {
            log(Log.WARN, "WakeWord", "custom round: capture buffer too small, capacity="
                    + data.capacity() + " position=" + recording.position()
                    + " limit=" + recording.limit(), null);
            return -1;
        }
        float[] rms = new float[frames];
        int peak = 0;
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            for (int i = 0; i < 160; i++) {
                int value = data.get();
                if (value < 0) value = -value;
                if (value > peak) peak = value;
                sum += (long) value * value;
            }
            rms[f] = (float) Math.sqrt(sum / 160.0);
        }
        if (peak < 1000) {
            log(Log.WARN, "WakeWord", "custom round: capture really is silent, peak=" + peak
                    + " frames=" + frames, null);
            return -1;
        }
        java.util.Arrays.sort(rms);
        float noise = Math.max(rms[frames / 10], 1f);
        return (int) (20 * Math.log10(rms[frames - 1] / noise));
    }

    /**
     * Hand the UDK7 engine a base model it can actually open.
     *
     * <p>For a word the user typed, the activity passes
     * {@code sm7_combined_chinese_language_model.lat} to all three engine calls
     * - score a round, size the model, build the model - and the ADSP refuses
     * every one: {@code verifyUdkRecording} answers -25 and
     * {@code getUserDefinedKeywordSize} dies in
     * {@code E/UDK7_2ND_STAGE: Error in eai_init_encoder_a}, so a finished
     * five-round session still ends with "抱歉，出了点问题". That parameter is the
     * user-independent sound model, not a language model, and this build ships
     * one the app never reads: {@code chinese_merged_language_model.lat}. With
     * that file the engine scores a round, sizes it and compiles the keyword
     * model (measured over synthetic audio: status 0, model 18292680 bytes).
     */
    private void installWakeWordUdkModel(ClassLoader loader) {
        try {
            Class<?> sm = Class.forName("com.qualcomm.listen.ListenSoundModel", false, loader);
            Class<?> conf = Class.forName("com.qualcomm.listen.ListenTypes$ConfidenceData",
                    false, loader);
            install(sm.getDeclaredMethod("getUdkSmSize", String.class, String.class,
                            java.nio.ShortBuffer[].class, java.nio.ByteBuffer.class),
                    chain -> swapUdkModel(chain, 3));
            install(sm.getDeclaredMethod("createUdkSm", String.class, String.class, int.class,
                            java.nio.ShortBuffer[].class, java.nio.ByteBuffer.class,
                            java.nio.ByteBuffer.class, conf),
                    chain -> swapUdkModel(chain, 4));
            log(Log.INFO, "WakeWord", "custom base model substitution armed", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "custom base model substitution unavailable", error);
        }
    }

    /** Replaces the base-model argument of one of the static UDK calls. */
    private Object swapUdkModel(XposedInterface.Chain chain, int index) throws Throwable {
        java.nio.ByteBuffer model = udkBaseModel();
        if (model == null) return chain.proceed();
        Object[] args = chain.getArgs().toArray();
        args[index] = model;
        return chain.proceed(args);
    }

    private java.nio.ByteBuffer udkBaseModel() {
        if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) return null;
        java.nio.ByteBuffer cached = udkBase;
        if (cached != null) return cached;
        try {
            java.io.File file = new java.io.File("/data/user/0/" + SVA
                    + "/files/chinese_merged_language_model.lat");
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            cached = java.nio.ByteBuffer.wrap(bytes);
            udkBase = cached;
            log(Log.INFO, "WakeWord", "udk base model loaded: " + bytes.length + " bytes", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "udk base model unreadable, keeping the vendor file",
                    error);
        }
        return cached;
    }

    private static android.view.ViewGroup.LayoutParams copyOf(
            android.view.ViewGroup.LayoutParams source) {
        if (source instanceof android.widget.LinearLayout.LayoutParams) {
            return new android.widget.LinearLayout.LayoutParams(source);
        }
        if (source instanceof android.view.ViewGroup.MarginLayoutParams) {
            return new android.view.ViewGroup.MarginLayoutParams(source);
        }
        return new android.view.ViewGroup.LayoutParams(source);
    }

    private static android.view.View find(android.content.res.Resources res,
            android.view.View root, String id) {
        int viewId = res.getIdentifier(id, "id", TARGET);
        return viewId == 0 ? null : root.findViewById(viewId);
    }

    private static String geometry(android.view.View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return "vis=" + view.getVisibility() + " alpha=" + view.getAlpha()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " at=" + at[0] + "," + at[1]
                + (view instanceof android.widget.TextView
                        ? " text=" + ((android.widget.TextView) view).getText() : "");
    }

    /**
     * Stop 下一步 on the typed wake-word page from taking Aicy down.
     *
     * <p>{@code BeginRecordKeyphraseFrg.onViewCreated} appends {@code
     * privacy_check_content} to a builder, looks {@code privacy_term} up inside
     * it and spans the returned index. The zh-rCN copy of {@code
     * privacy_check_content} was shipped without the trailing 请仔细阅读《Aicy
     * 助手隐私政策》… sentence every other locale carries (that sentence lives
     * on its own in {@code privacy_check_content_second}), so the lookup
     * returns -1 and {@code setSpan} dies with {@code setSpan (-1 ... 12)},
     * which is what the white screen was. Handing the record page the same
     * concatenated text the Compose privacy dialog assembles makes the index
     * valid again; the ids are taken from {@code R$string} so they are the
     * ones the fragment itself uses, and only that fragment is affected.
     */
    private void installWakeWordPrivacyText(ClassLoader loader) {
        try {
            Class<?> record = Class.forName(
                    "com.meizu.voiceassistant.keyphrase.BeginRecordKeyphraseFrg", false, loader);
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, loader);
            Class<?> strings = Class.forName("com.meizu.voiceassistant.R$string", false, loader);
            final int content = strings.getField("privacy_check_content").getInt(null);
            final int second = strings.getField("privacy_check_content_second").getInt(null);
            final int term = strings.getField("privacy_term").getInt(null);
            final Method getContext = fragment.getMethod("getContext");
            install(fragment.getDeclaredMethod("getString", int.class), chain -> {
                Object out = chain.proceed();
                try {
                    if (((Integer) chain.getArg(0)) != content) return out;
                    if (!RemoteConfig.master() || !RemoteConfig.wakeWord()) return out;
                    if (!(out instanceof String) || !record.isInstance(chain.getThisObject())) {
                        return out;
                    }
                    android.content.Context ctx =
                            (android.content.Context) getContext.invoke(chain.getThisObject());
                    if (ctx == null) return out;
                    android.content.res.Resources res = ctx.getResources();
                    String text = (String) out;
                    if (text.contains(res.getString(term))) return out;
                    String joined = text + res.getString(second,
                            namedString(res, "aicy_user_policy_with_brackets"),
                            namedString(res, "mz_permission_agree"));
                    log(Log.INFO, "WakeWord", "privacy text completed for record page", null);
                    return joined;
                } catch (Throwable error) {
                    log(Log.WARN, "WakeWord", "privacy text fix skipped", error);
                }
                return out;
            });
            log(Log.INFO, "WakeWord", "record privacy text guard armed", null);
        } catch (Throwable error) {
            log(Log.WARN, "WakeWord", "record privacy text hook failed", error);
        }
    }

    private static String namedString(android.content.res.Resources res, String name) {
        int id = res.getIdentifier(name, "string", TARGET);
        return id == 0 ? "" : res.getString(id);
    }

    /**
     * Only the per-feature switch is answered for. {@code voice_switch_ver410}
     * itself stays false on purpose: while it reads false Aicy keeps the normal
     * record flow, and that flow is what asks this switch before offering the
     * 自定义唤醒词 page.
     */
    private static boolean isSupportFlag(String key) {
        return "voice_switch_ver410_custom".equals(key);
    }

    private static boolean isPositive(String value) {
        try {
            return value != null && Integer.parseInt(value.trim()) >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void installFor(ClassLoader loader, String callback) {
        if (loader == null) {
            log(Log.ERROR, TAG, callback + ": null classloader, hooks not installed", null);
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            log(Log.INFO, TAG, callback + ": hooks already installed, skip", null);
            return;
        }
        log(Log.INFO, TAG, callback + ": target " + TARGET + " loaded, installing hooks", null);
        installWakeWordRedirect();
        installWakeWordGate();
        installWakeWordCustomEntry(loader);
        installWakeWordPrivacyText(loader);
        installWindowProbe(loader, "aicy");
        probe(loader, "com.google.android.gms.internal.mlkit_vision_common.J3");
        probe(loader, "com.flyme.agentos.app.phone.PhoneApp$runtimeInitialization$1");
        probe(loader, "com.flyme.agentos.app.common.ui.settings.n0");
        probe(loader, "okhttp3.OkHttpClient");
        probe(loader, "com.flyme.agentos.voice.impl.tts.c");
        probe(loader, "com.flyme.agentos.app.common.voice.n");
        installPowerConfirm(loader);
        try {
            AicyHooks hooks = new AicyHooks(loader,
                    (message, error) -> {
                        log(error == null ? Log.INFO : Log.ERROR, TAG, message, error);
                        AicyHooks.report(message);
                    });
            hooks.install(new AicyHooks.Installer() {
                @Override
                public void hook(String className, String methodName,
                                 XposedInterface.Hooker hooker, Class<?>... parameterTypes) {
                    install(loader, className, methodName, hooker, parameterTypes);
                }

                @Override
                public void hook(Method method, XposedInterface.Hooker hooker) {
                    install(method, hooker);
                }
            });
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot install Aicy hooks", error);
        }
    }

    /**
     * Power confirmation slide bar. Trigger comes from the user's own words in
     * the outgoing chat request, so it does not depend on the model, the skill
     * index or the tool catalogue. The sheet is added to Aicy's own decor view.
     */
    private void installPowerConfirm(ClassLoader loader) {
        try {
            dev.loong.aicyunlock.power.PowerGate.attach((message, error) ->
                    log(error == null ? Log.INFO : Log.ERROR, "PowerConfirm", message, error));

            try {
                Method onResume = android.app.Activity.class.getDeclaredMethod("onResume");
                install(onResume, chain -> {
                    Object self = null;
                    try {
                        self = chain.getThisObject();
                    } catch (Throwable ignored) {
                    }
                    if (self instanceof android.app.Activity) {
                        dev.loong.aicyunlock.power.PowerGate
                                .rememberActivity((android.app.Activity) self);
                    }
                    return chain.proceed();
                });
            } catch (Throwable error) {
                log(Log.WARN, "PowerConfirm", "Activity#onResume hook failed", error);
            }

            dev.loong.aicyunlock.power.PowerGate.installRequestHook(
                    (className, methodName, hooker, parameterTypes) ->
                            install(loader, className, methodName, hooker, parameterTypes), loader);

            installAsrHooks(loader);
            installVoiceStaging(loader);
            installSendProbe(loader);
            installSendGate(loader);
            installSkillTriggers(loader);
        } catch (Throwable error) {
            log(Log.ERROR, "PowerConfirm", "power confirm not installed", error);
        }
    }

    /**
     * Voice path. The voice sheet never renders the recognised sentence, only
     * Aicy's answer, so the ASR callback is the one place it can be read.
     *
     * <p>{@code SpeechRecognitionCallback} is an abstract Bundle callback owned
     * by the Meizu AI service, so it survives R8 - Aicy subclasses it and only
     * overrides {@code onSuccess}, which leaves the base {@code onCall} as the
     * single choke point every result passes through.
     */
    private void installAsrHooks(ClassLoader loader) {
        try {
            Class<?> callback = Class.forName(
                    "com.meizu.aiservice.algorithm.voice.asr.SpeechRecognitionCallback",
                    false, loader);
            Method onCall = callback.getDeclaredMethod("onCall", android.os.Bundle.class);
            install(onCall, chain -> {
                try {
                    Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (arg instanceof android.os.Bundle) {
                        readAsrBundle((android.os.Bundle) arg);
                    }
                } catch (Throwable error) {
                    log(Log.WARN, "PowerConfirm", "asr bundle read failed", error);
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            log(Log.WARN, "PowerConfirm", "asr callback unavailable", error);
        }

        // On-device recogniser, in case the sheet runs without the cloud.
        try {
            Class<?> recognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer",
                    false, loader);
            for (Method m : recognizer.getDeclaredMethods()) {
                if (!m.getName().equals("getResult")) continue;
                install(m, chain -> {
                    Object out = chain.proceed();
                    try {
                        if (out != null) {
                            String text = String.valueOf(out);
                            log(Log.INFO, "PowerConfirm", "sherpa=" + text, null);
                            dev.loong.aicyunlock.power.PowerGate.submitText(text);
                        }
                    } catch (Throwable ignored) {
                    }
                    return out;
                });
            }
        } catch (Throwable error) {
            log(Log.WARN, "PowerConfirm", "sherpa recogniser unavailable", error);
        }
    }

    /**
     * Voice quick window. Wake-word commands never reach the chat view model
     * (the sheet posts the recognised sentence straight to the model), so the
     * send gate above only ever sees typed text. Watch every String-taking
     * entry point of the merged voice controller and the ASR wrapper instead.
     *
     * <p>The recogniser emits partial hypotheses and the presenter receives the
     * model's answer through the same classes; only complete, command-shaped
     * sentences without answer markers are staged, and the gates themselves
     * still reject anything that is not a full command.
     */
    private void installVoiceStaging(ClassLoader loader) {
        String[] names = {
                "com.flyme.agentos.app.phone.voicequick.l0",
                "com.flyme.agentos.app.phone.voicequick.P",
                "com.flyme.agentos.app.common.voice.asr.g",
        };
        for (String className : names) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                String shortName = className.substring(className.lastIndexOf('.') + 1);
                int count = 0;
                for (Method m : type.getDeclaredMethods()) {
                    boolean hasText = false;
                    for (Class<?> param : m.getParameterTypes()) {
                        if (param == String.class) {
                            hasText = true;
                            break;
                        }
                    }
                    if (!hasText) continue;
                    install(m, chain -> {
                        try {
                            for (Object arg : chain.getArgs()) {
                                if (!(arg instanceof String)) continue;
                                String text = ((String) arg).trim();
                                if (!looksLikeUtterance(text)) continue;
                                log(Log.INFO, "Voice", shortName + "#" + m.getName()
                                        + " " + clip(text), null);
                                TaskRobotGate.stage(text);
                                VolumeGate.stage(text);
                            }
                        } catch (Throwable error) {
                            log(Log.WARN, "Voice", "voice staging error at " + m.getName(), error);
                        }
                        return chain.proceed();
                    });
                    count++;
                }
                log(Log.INFO, "Voice", "voice probe on " + count + " " + className + " methods", null);
            } catch (Throwable error) {
                log(Log.WARN, "Voice", "voice probe unavailable: " + className, error);
            }
        }
    }

    /** Complete spoken sentences only: partial ASR text and answers are ignored. */
    private static boolean looksLikeUtterance(String text) {
        if (text.length() < 2 || text.length() > 80) return false;
        boolean han = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                han = true;
                break;
            }
        }
        return han && !ANSWER_MARKER.matcher(text).find();
    }

    /** "已将音量调到…" is Aicy's own answer, not a new request. */
    private static final java.util.regex.Pattern ANSWER_MARKER = java.util.regex.Pattern.compile(
            "已经|已将|已为|已调|已设|已开|已关|已静音");

    /**
     * Send gate. The chat view model is R8-renamed and the rename moved in
     * Aicy 13.0.56 ({@code w3} became {@code v3}), so resolve every method that
     * still carries the same {@code (long, String)} shape instead of pinning a
     * name. Both the power sheet and the task-robot bridge hang off this point.
     */
    private void installSendGate(ClassLoader loader) {
        try {
            TaskRobotBridge.attach((message, error) ->
                    log(error == null ? Log.INFO : Log.ERROR, "TaskRobot", message, error));
            TaskRobotGate.attach((message, error) ->
                    log(error == null ? Log.INFO : Log.ERROR, "TaskRobot", message, error));
            VolumeGate.attach((message, error) ->
                    log(error == null ? Log.INFO : Log.ERROR, "Volume", message, error));

            Class<?> vm = Class.forName("com.flyme.agentos.app.common.viewmodel.u0",
                    false, loader);
            int count = 0;
            for (Method m : vm.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 2 || types[0] != long.class || types[1] != String.class) {
                    continue;
                }
                final String name = m.getName();
                install(m, chain -> {
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        Object text = args.size() > 1 ? args.get(1) : null;
                        if (text instanceof String) {
                            String sentence = (String) text;
                            if (dev.loong.aicyunlock.power.PowerGate.intercept(sentence)) {
                                log(Log.INFO, "PowerConfirm",
                                        "send intercepted at " + name + ": "
                                                + clip(sentence), null);
                                return null;
                            }
                            TaskRobotGate.stage(sentence);
                            VolumeGate.stage(sentence);
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, "PowerConfirm", "send gate error at " + name, error);
                    }
                    return chain.proceed();
                });
                count++;
            }
            if (count == 0) {
                log(Log.WARN, "PowerConfirm", "send gate found no (long, String) method", null);
            } else {
                log(Log.INFO, "PowerConfirm", "send gate on " + count
                        + " ChatViewModel methods", null);
            }
            hookSubmitEntry(vm);
        } catch (Throwable error) {
            log(Log.WARN, "PowerConfirm", "send gate unavailable", error);
        }
    }

    /**
     * The common submit entry (u0.f3 in 13.0.56). Typed text reaches it after
     * the per-keystroke (long, String) calls; voice input reaches it directly,
     * with no keystroke call at all - so this is the one point every user
     * sentence passes through. Resolved by shape, not by name: void, six or
     * more parameters including a String, a flag, a count and a list.
     */
    private void hookSubmitEntry(Class<?> vm) {
        int matched = 0;
        for (Method m : vm.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (m.getReturnType() != void.class) continue;
            if (types.length < 6) continue;
            boolean hasText = false;
            boolean hasFlag = false;
            boolean hasCount = false;
            boolean hasList = false;
            for (Class<?> type : types) {
                if (type == String.class) hasText = true;
                if (type == boolean.class) hasFlag = true;
                if (type == int.class) hasCount = true;
                if (java.util.List.class.isAssignableFrom(type)) hasList = true;
            }
            if (!hasText || !hasFlag || !hasCount || !hasList) continue;

            install(m, chain -> {
                try {
                    for (Object arg : chain.getArgs()) {
                        if (arg instanceof String) {
                            TaskRobotGate.stage((String) arg);
                            VolumeGate.stage((String) arg);
                            break;
                        }
                    }
                } catch (Throwable error) {
                    log(Log.WARN, "PowerConfirm", "submit gate error at " + m.getName(), error);
                }
                return chain.proceed();
            });
            matched++;
            log(Log.INFO, "PowerConfirm", "submit gate on " + m.getName(), null);
        }
        if (matched == 0) {
            log(Log.WARN, "PowerConfirm", "submit gate found no entry method", null);
        }
    }

    /**
     * Skill trigger. Aicy reports every tool invocation through this R8-renamed
     * reporter right before the skill runs; the report carries the resolved
     * skill id in the tool-name field. The reporter class name moves between
     * versions, so it is resolved by the event parameter type instead.
     */
    private void installSkillTriggers(ClassLoader loader) {
        try {
            Class<?> reporter = Class.forName("b4.b", false, loader);
            Method target = null;
            for (Method m : reporter.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 3 || types[2] != String.class) continue;
                if (!"com.flyme.agentos.app.common.usagestats.i".equals(types[1].getName())) {
                    continue;
                }
                target = m;
                break;
            }
            if (target == null) {
                log(Log.WARN, "TaskRobot", "tool_invoke reporter not found", null);
                return;
            }
            install(target, chain -> {
                try {
                    java.util.List<Object> args = chain.getArgs();
                    Object event = args.size() > 1 ? args.get(1) : null;
                    if (event != null) {
                        String skill = toolName(event);
                        if (TaskRobotGate.SKILL_ID.equals(skill)) {
                            TaskRobotGate.dispatchPending();
                        } else if (VolumeGate.SKILL_ID.equals(skill)) {
                            boolean applied = VolumeGate.dispatchPending();
                            log(Log.INFO, "Volume",
                                    "skill invoked, pending applied=" + applied, null);
                        }
                    }
                } catch (Throwable error) {
                    log(Log.WARN, "TaskRobot", "skill trigger error", error);
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            log(Log.WARN, "TaskRobot", "skill trigger unavailable", error);
        }
    }

    private static String toolName(Object event) {
        try {
            java.lang.reflect.Field field = event.getClass().getDeclaredField("a");
            field.setAccessible(true);
            Object value = field.get(event);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Send-path probe. The chat view model is R8-renamed, so the only reliable
     * way to find the method that publishes a message is to watch it happen:
     * every String-taking method is logged with its arguments.
     */
    private void installSendProbe(ClassLoader loader) {
        try {
            Class<?> vm = Class.forName("com.flyme.agentos.app.common.viewmodel.u0",
                    false, loader);
            int count = 0;
            for (Method m : vm.getDeclaredMethods()) {
                boolean hasText = false;
                for (Class<?> type : m.getParameterTypes()) {
                    if (type == String.class) {
                        hasText = true;
                        break;
                    }
                }
                if (!hasText) continue;
                count++;
                install(m, chain -> {
                    StringBuilder line = new StringBuilder("vm ")
                            .append(m.getName()).append(" (");
                    try {
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof String) {
                                line.append('"').append(clip((String) arg)).append("\" ");
                            } else {
                                line.append(arg == null ? "null"
                                        : arg.getClass().getSimpleName()).append(' ');
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    line.append(')');
                    log(Log.INFO, "PowerConfirm", line.toString(), null);
                    return chain.proceed();
                });
            }
            log(Log.INFO, "PowerConfirm", "send probe on " + count + " methods", null);
        } catch (Throwable error) {
            log(Log.WARN, "PowerConfirm", "send probe unavailable", error);
        }
    }

    /** Pulls the recognised sentence out of the callback Bundle and logs the rest. */
    private void readAsrBundle(android.os.Bundle bundle) {
        String best = null;
        try {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                if (value == null) continue;
                String text = asText(value);
                if (text == null || text.isEmpty()) continue;
                log(Log.INFO, "PowerConfirm", "asr[" + key + "]=" + clip(text), null);
                if (best == null || text.length() > best.length()) best = text;
            }
        } catch (Throwable error) {
            log(Log.WARN, "PowerConfirm", "asr bundle walk failed", error);
        }
        if (best != null) {
            dev.loong.aicyunlock.power.PowerGate.submitText(best);
        }
    }

    private static String asText(Object value) {
        try {
            if (value instanceof String) return (String) value;
            if (value instanceof java.util.Collection) {
                StringBuilder out = new StringBuilder();
                for (Object item : (java.util.Collection<?>) value) {
                    if (item == null) continue;
                    if (out.length() > 0) out.append(' ');
                    out.append(item);
                }
                return out.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 240 ? text : text.substring(0, 240) + "…";
    }

    private void probe(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            log(Log.INFO, TAG, "probe " + className + " -> present", null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "probe " + className + " -> MISSING", null);
        }
    }

    private void install(ClassLoader loader, String className, String methodName,
                         XposedInterface.Hooker hooker, Class<?>... parameterTypes) {
        try {
            Method target = Class.forName(className, false, loader)
                    .getDeclaredMethod(methodName, parameterTypes);
            hook(target).intercept(hooker);
            log(Log.INFO, TAG, "hooked " + className + "#" + methodName, null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "skip hook " + className + "#" + methodName
                    + " (" + error.getClass().getSimpleName() + ")", null);
        }
    }

    /** Hooks a method that structural discovery already resolved. */
    private void install(Method method, XposedInterface.Hooker hooker) {
        try {
            hook(method).intercept(hooker);
            log(Log.INFO, TAG, "hooked " + method.getDeclaringClass().getName()
                    + "#" + method.getName(), null);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "skip hook " + method.getDeclaringClass().getName()
                    + "#" + method.getName() + " (" + error.getClass().getSimpleName() + ")", null);
        }
    }
}
