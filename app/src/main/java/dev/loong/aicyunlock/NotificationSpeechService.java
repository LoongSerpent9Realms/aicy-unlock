package dev.loong.aicyunlock;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Fallback listener for notification read-aloud.
 *
 * <p>The feature prefers to run inside Aicy (see {@link NotifyHooks}), because
 * that is where the user's timbres and playback life cycle live. This service
 * keeps the feature working before (or instead of) Aicy being granted
 * 通知使用权: whenever Aicy's own collector is enabled, this one skips, so a
 * notification is never spoken twice.
 */
public final class NotificationSpeechService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        onPosted(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn,
                                     RankingMap rankingMap) {
        onPosted(sbn);
    }

    private void onPosted(StatusBarNotification sbn) {
        try {
            // NotifyAnnouncer stays silent whenever Aicy holds notification
            // access; it only speaks when the module is the sole listener.
            NotifyAnnouncer.offer(this, sbn, null);
        } catch (Throwable ignored) {
            // A notification callback must never take the service down.
        }
    }
}
