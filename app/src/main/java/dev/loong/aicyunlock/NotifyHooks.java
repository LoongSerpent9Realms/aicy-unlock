package dev.loong.aicyunlock;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.BiConsumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Hooks Aicy's own notification collector so read-aloud runs inside Aicy.
 *
 * <p>Verified on Aicy 13.0.55: the app declares
 * {@code com.flyme.agentos.app.phone.notification.NotificationCollectorService}
 * with {@code android.permission.BIND_NOTIFICATION_LISTENER_SERVICE}. It
 * overrides the single-argument
 * {@link NotificationListenerService#onNotificationPosted(StatusBarNotification)},
 * which the framework's two-argument method delegates to. Hooking the single
 * one therefore sees each post exactly once; hooking both would speak twice.
 *
 * <p>The class name is descriptive rather than obfuscated, so it is used as the
 * fast path. If a future build renames it, the package is scanned for any
 * concrete {@link NotificationListenerService} subclass that declares the
 * method, and only that method is hooked.
 */
final class NotifyHooks {

    private static final String TARGET_PACKAGE =
            "com.flyme.agentos.app.phone.notification";
    private static final String EXACT_CLASS =
            TARGET_PACKAGE + ".NotificationCollectorService";

    private final ClassLoader loader;
    private final BiConsumer<String, Throwable> log;

    NotifyHooks(ClassLoader loader, BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.log = log;
    }

    void install(AicyHooks.Installer installer) {
        Class<?> service = load(EXACT_CLASS);
        Method target = targetMethod(service);
        String route = "exact";

        if (target == null) {
            route = "structural";
            service = null;
            List<String> names = DexIndex.classesIn(loader, TARGET_PACKAGE, log);
            for (String name : names) {
                if (name.indexOf('$') >= 0) continue;
                Class<?> candidate = load(name);
                if (candidate == null || candidate == service) continue;
                if (!NotificationListenerService.class.isAssignableFrom(candidate)) {
                    continue;
                }
                if (Modifier.isAbstract(candidate.getModifiers())) continue;
                Method method = targetMethod(candidate);
                if (method == null) continue;
                service = candidate;
                target = method;
                break;
            }
        }

        if (service == null || target == null) {
            log.accept("AicyNotify: no NotificationListenerService found in "
                    + TARGET_PACKAGE, null);
            return;
        }
        if (!NotificationListenerService.class.isAssignableFrom(service)) {
            log.accept("AicyNotify: " + service.getName()
                    + " is not a NotificationListenerService", null);
            return;
        }

        final int params = target.getParameterTypes().length;
        try {
            installer.hook(target, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg = chain.getArg(0);
                    if (self instanceof Context && arg instanceof StatusBarNotification) {
                        NotifyAnnouncer.offer((Context) self, (StatusBarNotification) arg, log);
                    }
                } catch (Throwable error) {
                    log.accept("AicyNotify: onNotificationPosted failed", error);
                }
                return chain.proceed();
            });
            log.accept("AicyNotify: hooked " + service.getName() + "#"
                    + target.getName() + " (" + params + " arg, " + route + ")", null);
            AicyHooks.report("通知播报已挂到 Aicy · " + service.getSimpleName());
            startReadyHeartbeat();
        } catch (Throwable error) {
            log.accept("AicyNotify: hook failed on " + service.getName()
                    + "#" + target.getName(), error);
        }
    }

    /**
     * Tells the module app this process is alive and hooked, once a minute.
     *
     * <p>The settings page uses this to distinguish "Aicy will speak" from
     * "Aicy holds 通知使用权 but the module update has not reached its running
     * process yet". Speech ownership itself depends only on the permission:
     * while Aicy has it, the module's own listener stays silent.
     */
    private void startReadyHeartbeat() {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Context app = currentApplication();
                if (app != null) {
                    NotifyAnnouncer.markAicyReady(app);
                    if (!sleep(60_000L)) return;
                } else {
                    if (!sleep(2_000L)) return;
                }
            }
        }, "AicyNotifyHeartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One-arg first: the framework's two-arg method delegates to it. */
    private static Method targetMethod(Class<?> service) {
        if (service == null) return null;
        Method single = declared(service, "onNotificationPosted",
                StatusBarNotification.class);
        if (single != null) return single;
        return declared(service, "onNotificationPosted",
                StatusBarNotification.class, NotificationListenerService.RankingMap.class);
    }

    private static Method declared(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
