package com.joselofarias.nightzuku.monitor;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class NightzukuNotificationListener extends NotificationListenerService {
    private boolean isNightDog(StatusBarNotification sbn) {
        return sbn != null && MonitorStore.TARGET.equals(sbn.getPackageName())
            && sbn.getId() == MonitorStore.NOTIFICATION_ID
            && MonitorStore.CHANNEL.equals(sbn.getNotification().getChannelId());
    }

    @Override public void onListenerConnected() {
        MonitorStore.listenerConnected(this);
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (isNightDog(sbn)) MonitorStore.posted(this, sbn.getPostTime());
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isNightDog(sbn)) MonitorStore.removed(this);
    }
}
