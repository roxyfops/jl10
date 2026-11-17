package com.bb10.launcher.hub;

import android.content.ComponentName;
import android.content.Context;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.bb10.launcher.LauncherApp;

public class HubListenerService extends NotificationListenerService {
    private static HubListenerService sInstance;

    public static boolean isGranted(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, HubListenerService.class));
    }

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static void cancelAll() {
        if (sInstance != null) {
            try {
                sInstance.cancelAllNotifications();
            } catch (Exception ignored) {
            }
        }
    }

    public static void cancel(String key) {
        if (sInstance != null) {
            try {
                sInstance.cancelNotification(key);
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean cancelMedia(String pkg) {
        HubListenerService s = sInstance;
        if (s == null || pkg == null) return false;
        boolean any = false;
        try {
            StatusBarNotification[] all = s.getActiveNotifications();
            if (all == null) return false;
            for (StatusBarNotification n : all) {
                if (!pkg.equals(n.getPackageName()) || !n.getNotification().extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) continue;
                any = true;
                if (n.isClearable()) s.cancelNotification(n.getKey());
            }
        } catch (Exception ignored) {
        }
        return any;
    }

    public static java.util.List<StatusBarNotification> newsNow() {
        java.util.List<StatusBarNotification> out = new java.util.ArrayList<>();
        HubListenerService s = sInstance;
        if (s == null) return out;
        try {
            StatusBarNotification[] all = s.getActiveNotifications();
            if (all == null) return out;
            for (StatusBarNotification n : all) {
                android.app.Notification no = n.getNotification();
                if (n.isOngoing() || !n.isClearable() || (no.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0
                        || no.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
                        || s.getPackageName().equals(n.getPackageName())) continue;
                out.add(n);
            }
            out.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
        LauncherApp.get().ensureInit();
        try {
            LauncherApp.get().hub.sync(getActiveNotifications());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
        reconnect(this);
    }

    public static void reconnect(Context c) {
        if (sInstance != null || !isGranted(c)) return;
        try {
            requestRebind(new ComponentName(c, HubListenerService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        LauncherApp.get().hub.posted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        LauncherApp.get().hub.removed(sbn.getKey());
    }
}
