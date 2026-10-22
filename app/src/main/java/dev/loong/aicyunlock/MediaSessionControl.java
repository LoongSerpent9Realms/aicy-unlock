package dev.loong.aicyunlock;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 直接通过标准 MediaSession 控制「当前正在播放」的 App。
 *
 * <p>只依赖 {@code android.permission.MEDIA_CONTENT_CONTROL}，
 * Aicy（系统应用）已 granted，因此免 root、免无障碍。
 *
 * <p>与 {@code cmd media_session dispatch} 的区别：这里可以
 * <b>按包名指定目标 App</b>，而不是只发给当前 media button session
 * （实测 media button session 会在多个 App 之间易主，导致按键打错对象）。
 *
 * <p>API 说明：{@code MediaSessionManager.getActiveSessions(ComponentName)}
 * 在 API 21+ 是公开方法，直接返回 {@code List<MediaController>}，
 * 无需触碰 {@code MediaSession.Token} 等隐藏类。
 */
final class MediaSessionControl {

    /** 候选包名，顺序即优先级。 */
    private static final String[] TARGET_PACKAGES = {
            "com.netease.cloudmusic",   // 网易云音乐
            "cn.kuwo.player",           // 酷我音乐
            "com.kuwo.player",
            "com.tencent.qqmusic",      // QQ音乐
            "com.kugou.android",        // 酷狗
            "com.luna.music",           // 汽水音乐
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appContext;

    static void attach(Context context) {
        if (context == null) return;
        try {
            appContext = context.getApplicationContext();
        } catch (Throwable ignored) {
            appContext = context;
        }
    }

    // ------------------------------------------------------------------
    // 会话获取
    // ------------------------------------------------------------------

    private static List<MediaController> activeControllers() {
        Context context = appContext;
        if (context == null) return null;
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return null;
            List<MediaController> controllers = manager.getActiveSessions(null);
            if (controllers == null || controllers.isEmpty()) return null;
            return new ArrayList<>(controllers);
        } catch (Throwable error) {
            // 权限不足（缺 MEDIA_CONTENT_CONTROL）或 API 差异
            return null;
        }
    }

    private static boolean isMusicPackage(String pkg) {
        if (pkg == null) return false;
        for (String candidate : TARGET_PACKAGES) {
            if (candidate.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isUsable(MediaController controller) {
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state == null) return false;
            long actions = state.getActions();
            return (actions & (PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS)) != 0;
        } catch (Throwable error) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 是否存在一个可被控制的活跃音乐会话。
     * 用于「原后端不可用时，是否还有别的 App 在播」的判定。
     */
    static boolean hasPlayableSession() {
        List<MediaController> controllers = activeControllers();
        if (controllers == null) return false;
        for (MediaController controller : controllers) {
            try {
                String pkg = controller.getPackageName();
                if (!isMusicPackage(pkg)) continue;
                if (isUsable(controller)) return true;
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return false;
    }

    /**
     * 执行一次播控动作。
     *
     * @param action pause / play / next / previous
     * @return true 表示确实选中了目标会话并发出了指令
     */
    static boolean dispatch(String action) {
        MediaController controller = pickController();
        if (controller == null) return false;

        final MediaController target = controller;
        final String op = action;
        // MediaController 的 transport controls 要在主线程调用
        MAIN.post(() -> {
            try {
                MediaController.TransportControls controls = target.getTransportControls();
                if (controls == null) return;
                switch (op) {
                    case "pause":
                        controls.pause();
                        break;
                    case "play":
                    case "resume":
                        controls.play();
                        break;
                    case "next":
                        controls.skipToNext();
                        break;
                    case "previous":
                        controls.skipToPrevious();
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // 目标 App 不支持该动作
            }
        });
        return true;
    }

    /** 选一个可控的会话：优先已知的音乐 App，其次任意在播的会话。 */
    private static MediaController pickController() {
        List<MediaController> controllers = activeControllers();
        if (controllers == null) return null;

        // 1) 已知的音乐 App
        for (String pkg : TARGET_PACKAGES) {
            for (MediaController controller : controllers) {
                try {
                    if (!pkg.equals(controller.getPackageName())) continue;
                    if (!isUsable(controller)) continue;
                    return controller;
                } catch (Throwable ignored) {
                    // 继续
                }
            }
        }

        // 2) 任意正在播放的会话
        for (MediaController controller : controllers) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state == null) continue;
                if (state.getState() != PlaybackState.STATE_PLAYING) continue;
                return controller;
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }

    /** 当前在播的音乐 App 包名（给上层做状态播报用）。 */
    static String currentMusicPackage() {
        List<MediaController> controllers = activeControllers();
        if (controllers == null) return null;
        for (MediaController controller : controllers) {
            try {
                String pkg = controller.getPackageName();
                if (!isMusicPackage(pkg)) continue;
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return pkg;
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }
}
