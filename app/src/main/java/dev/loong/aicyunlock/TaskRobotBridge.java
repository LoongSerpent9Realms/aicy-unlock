package dev.loong.aicyunlock;

import android.content.Context;
import android.content.pm.PackageManager;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Calls the task robot's own UI-agent cloud from inside Aicy.
 *
 * <p>The ability SDK is bundled in Aicy and Aicy already holds
 * {@code flyme.permission.AI_AGENT_ACCESS}, so no permission has to be added.
 * The call shape mirrors SceneEngine's {@code AppOperationActionAbility}, which
 * is the on-device caller that is known to work:
 * {@code task_agent_execute} + {@code intent=user_custom_ui_agent_execute}.
 */
final class TaskRobotBridge {

    interface Reporter {
        void report(String message, Throwable error);
    }

    private static final String TAG = "TaskRobot";
    private static final String ABILITY = "task_agent_execute";
    private static final String UGC_INTENT = "user_custom_ui_agent_execute";
    /** Same value SceneEngine sends for app operations. */
    private static final int SOURCE_SCENE_ENGINE = 3;

    private static volatile Reporter reporter = (m, e) -> { };

    private TaskRobotBridge() {
    }

    static void attach(Reporter sink) {
        if (sink != null) reporter = sink;
    }

    /**
     * Builds the ability on the calling thread, then performs the call on a
     * worker because the SDK refuses synchronous ability calls on the main
     * thread.
     *
     * @return true when the call was dispatched; false when Aicy's bundled SDK
     *         could not be used, in which case the sentence should keep its
     *         normal route through Aicy
     */
    static boolean submit(Context context, String pkg, String title, String query) {
        if (context == null || isEmpty(pkg) || isEmpty(query)) return false;
        Prepared prepared;
        try {
            prepared = prepare(context, pkg, title, query);
        } catch (Throwable error) {
            reporter.report("prepare failed", error);
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                prepared.callMethod.invoke(prepared.ability, prepared.callback);
                reporter.report("dispatched pkg=" + pkg + " title=" + title
                        + " query=" + clip(query), null);
            } catch (Throwable error) {
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                        && error.getCause() != null ? error.getCause() : error;
                reporter.report("call failed", cause);
            }
        }, "aicy-taskrobot");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static Prepared prepare(Context context, String pkg, String title,
                                    String query) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> abilityClass = Class.forName(
                "com.flyme.ability.AtomicAbility", false, loader);
        Class<?> builderClass = Class.forName(
                "com.flyme.ability.AtomicAbility$Builder", false, loader);
        Class<?> paramClass = Class.forName(
                "com.flyme.ability.AtomicAbility$AtomicAbilityParam", false, loader);
        Class<?> callbackClass = Class.forName(
                "com.flyme.ability.IAbilityCallback", false, loader);
        Class<?> managerClass = Class.forName(
                "com.flyme.ability.AtomicAbilityManager", false, loader);

        Object manager = managerClass.getMethod("getInstance").invoke(null);
        // No-op when Aicy already initialised the SDK on startup.
        managerClass.getMethod("init", Context.class).invoke(manager, context);

        int uid;
        try {
            uid = context.getPackageManager().getPackageUid(pkg, 0);
        } catch (PackageManager.NameNotFoundException error) {
            throw new PackageManager.NameNotFoundException(pkg + " is not installed");
        }
        boolean clone = String.valueOf(uid).startsWith("999");

        Object[] params = (Object[]) Array.newInstance(paramClass, 9);
        params[0] = paramClass.getConstructor(String.class, String.class)
                .newInstance("intent", UGC_INTENT);
        params[1] = paramClass.getConstructor(String.class, int.class)
                .newInstance("calling_source", SOURCE_SCENE_ENGINE);
        params[2] = paramClass.getConstructor(String.class, String.class)
                .newInstance("title", title);
        params[3] = paramClass.getConstructor(String.class, String.class)
                .newInstance("package_name", pkg);
        params[4] = paramClass.getConstructor(String.class, String.class)
                .newInstance("query", query);
        params[5] = paramClass.getConstructor(String.class, Object.class)
                .newInstance("uid", Integer.valueOf(uid));
        params[6] = paramClass.getConstructor(String.class, boolean.class)
                .newInstance("is_clone", clone);
        params[7] = paramClass.getConstructor(String.class, boolean.class)
                .newInstance("is_trying_by_scene_engine", false);
        params[8] = paramClass.getConstructor(String.class, String.class)
                .newInstance("metadata", "{}");

        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("setAbilitySign", String.class)
                .invoke(builder, ABILITY);
        builderClass.getMethod("setOperateType", String.class)
                .invoke(builder, "set");
        builderClass.getMethod("setParams", params.getClass())
                .invoke(builder, (Object) params);
        Object ability = builderClass.getMethod("build").invoke(builder);

        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass},
                callbackHandler(abilityClass));
        Method call = abilityClass.getMethod("call", callbackClass);
        return new Prepared(ability, callback, call);
    }

    private static InvocationHandler callbackHandler(Class<?> abilityClass) {
        return (proxy, method, args) -> {
            if ("onAbilityCallback".equals(method.getName())
                    && args != null && args.length == 1 && args[0] != null) {
                Object answer = args[0];
                try {
                    Object code = abilityClass.getMethod("getCode").invoke(answer);
                    Object msg = abilityClass.getMethod("getMsg").invoke(answer);
                    reporter.report("callback code=" + code + " msg=" + msg, null);
                } catch (Throwable error) {
                    reporter.report("callback read failed", error);
                }
                return null;
            }
            if ("toString".equals(method.getName())) return TAG;
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
            return null;
        };
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    private static final class Prepared {
        final Object ability;
        final Object callback;
        final Method callMethod;

        Prepared(Object ability, Object callback, Method callMethod) {
            this.ability = ability;
            this.callback = callback;
            this.callMethod = callMethod;
        }
    }
}
