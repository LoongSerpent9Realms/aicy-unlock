package dev.loong.aicyunlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 把 Aicy 的媒体控制从「QQ音乐 Flyme 版」后端换成标准 MediaSession 直控，
 * 使「下一首 / 上一首 / 暂停 / 继续」能作用于当前正在播放的任意 App
 * （网易云音乐 / 酷我音乐 / QQ音乐 / 酷狗…），而不只是 com.meizu.media.music。
 *
 * <p>注入点选择（Aicy 13.0.56 实测 + dex 静态分析）：
 *
 * <pre>
 *  MediaControlTool.execute            com.flyme.agentos.agent.domain.tools.common.u
 *    ├─ "pause"    → media/t.h()  ─┐
 *    ├─ "next"     → media/t.g()   ├→ media/t.c 字段 = IMusicSupportService（AIDL 代理）
 *    ├─ "previous" → media/t.k()  ─┘      → IMusicSupportService$Stub$Proxy
 *    └─ "play"     → media/d.e()              .pause() / .next() / .prev() / .play()
 * </pre>
 *
 * <p>所以 hook {@code IMusicSupportService$Stub$Proxy} 的这 4 个无参方法，
 * 就能同时覆盖 pause / next / previous / play 四条路径，
 * 且完全绕开 Kotlin 挂起函数（挂起函数的返回值需要构造协程状态机，代价高得多）。
 *
 * <p>为什么可行：Aicy 是系统应用（{@code pkgFlags=[SYSTEM]}，域 {@code platform_app}），
 * 且已持有 {@code android.permission.MEDIA_CONTENT_CONTROL}（{@code granted=true}），
 * 所以进程内可以直接访问媒体会话 —— 不需要 root，也不需要无障碍。
 *
 * <p>类名是 R8 产物，每版可能漂移；全部用多候选名兜底，失败时只记日志、
 * 不影响模块的其它 hook。
 */
final class MediaControlInjector {

    /** AIDL 代理类（真正发 IPC 的地方）。 */
    private static final String[] PROXY_CLASS_CANDIDATES = {
            "com.meizu.media.music.support.IMusicSupportService$Stub$Proxy",
            "com.meizu.media.music.support.IMusicSupportService$Default",
    };

    /** 备选：直接 hook 音乐服务封装类（com.flyme.agentos.app.phone.media.t）。 */
    private static final String[] CLIENT_CLASS_CANDIDATES = {
            "com.flyme.agentos.app.phone.media.t",
    };

    /** 方法名 → 动作。 */
    private static final String[][] ACTION_METHODS = {
            {"pause", "pause"},
            {"next", "next"},
            {"prev", "previous"},
            {"play", "play"},
    };

    private final ClassLoader loader;
    private final AicyHooks.Installer installer;
    private final java.util.function.BiConsumer<String, Throwable> log;

    MediaControlInjector(ClassLoader loader, AicyHooks.Installer installer,
                         java.util.function.BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.installer = installer;
        this.log = log;
    }

    void install() {
        try {
            // MediaSession 直控需要 Context
            MediaSessionControl.attach(currentApplication());
            int hooked = hookProxy();
            if (hooked == 0) {
                hooked = hookClient();
            }
            if (hooked == 0) {
                log.accept("AicyUnlock: media-control injection skipped (no hook point)", null);
            } else {
                log.accept("AicyUnlock: media-control injection armed (" + hooked + " methods)", null);
            }
        } catch (Throwable error) {
            log.accept("AicyUnlock: media-control injection failed", error);
        }
    }

    // ------------------------------------------------------------------
    // 1. 首选：hook AIDL 代理
    // ------------------------------------------------------------------

    private int hookProxy() {
        for (String candidate : PROXY_CLASS_CANDIDATES) {
            Class<?> proxy;
            try {
                proxy = Class.forName(candidate, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            int hooked = 0;
            for (String[] pair : ACTION_METHODS) {
                hooked += hookVoid(proxy, pair[0], pair[1]);
            }
            if (hooked > 0) return hooked;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 2. 备选：hook 音乐服务封装类
    // ------------------------------------------------------------------

    /**
     * 备选路径：{@code com.flyme.agentos.app.phone.media.t}。
     * g()=next / h()=pause / k()=previous（实测自 MediaControlTool.execute 的调用点）。
     */
    private int hookClient() {
        for (String candidate : CLIENT_CLASS_CANDIDATES) {
            Class<?> client;
            try {
                client = Class.forName(candidate, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            int hooked = 0;
            hooked += hookBoolean(client, "g", "next");
            hooked += hookBoolean(client, "h", "pause");
            hooked += hookBoolean(client, "k", "previous");
            hooked += hookAvailability(client);
            if (hooked > 0) return hooked;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 3. hook 细节
    // ------------------------------------------------------------------

    /** hook 无参 void 的 AIDL 方法（pause/next/prev/play）。 */
    private int hookVoid(Class<?> owner, String methodName, String action) {
        Method target;
        try {
            target = owner.getDeclaredMethod(methodName);
        } catch (Throwable ignored) {
            return 0;
        }
        try {
            installer.hook(target, chain -> {
                // 有活跃音乐会话 → 走 MediaSession 直控（能指定目标 App）
                if (MediaSessionControl.hasPlayableSession()) {
                    MediaSessionControl.dispatch(action);
                    log.accept("AicyUnlock: media-control " + action + " via MediaSession", null);
                    return null;
                }
                // 没有别的 App 在播 → 走原逻辑（QQ音乐 Flyme 版）
                return chain.proceed();
            });
            return 1;
        } catch (Throwable error) {
            log.accept("AicyUnlock: hook media " + action + " failed", error);
            return 0;
        }
    }

    /** hook 无参 boolean 的封装类方法。 */
    private int hookBoolean(Class<?> client, String methodName, String action) {
        Method target;
        try {
            target = client.getDeclaredMethod(methodName);
        } catch (Throwable ignored) {
            return 0;
        }
        try {
            installer.hook(target, chain -> {
                if (MediaSessionControl.hasPlayableSession()) {
                    boolean ok = MediaSessionControl.dispatch(action);
                    if (ok) {
                        log.accept("AicyUnlock: media-control " + action + " via MediaSession", null);
                    }
                    return ok;
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable error) {
            log.accept("AicyUnlock: hook media " + action + " failed", error);
            return 0;
        }
    }

    /**
     * 接管「服务可用性」检查 {@code t.e()}。
     *
     * <p>原实现是「QQ音乐 Flyme 版 的 IMusicSupportService 是否连上」。
     * 它返回 false 时 {@code u#c} 会直接走「Service unavailable」分支，
     * 根本不会调用 g/h/k —— 所以这里必须在「有活跃音乐会话」时也放行。
     */
    private int hookAvailability(Class<?> client) {
        Method target;
        try {
            target = client.getDeclaredMethod("e");
        } catch (Throwable ignored) {
            return 0;
        }
        try {
            installer.hook(target, chain -> {
                Object result = chain.proceed();
                if (MediaSessionControl.hasPlayableSession()) {
                    return Boolean.TRUE;
                }
                return result;
            });
            return 1;
        } catch (Throwable error) {
            log.accept("AicyUnlock: hook media availability failed", error);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 4. 工具
    // ------------------------------------------------------------------

    /** 取 Aicy 进程里的 Application（用于拿系统服务）。 */
    private static android.content.Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
