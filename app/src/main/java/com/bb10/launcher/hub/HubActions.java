package com.bb10.launcher.hub;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

public final class HubActions {
    private HubActions() {}

    public static Notification.Action replyAction(Notification n) {
        if (n == null || n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri == null) continue;
            for (RemoteInput r : ri) if (r.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    public static boolean send(Context c, PendingIntent pi, Intent fill) {
        try {
            Bundle opts = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                opts = o.toBundle();
            }
            pi.send(c, 0, fill, null, null, null, opts);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean reply(Context c, Notification n, String text) {
        Notification.Action a = replyAction(n);
        if (a == null || text == null || text.trim().isEmpty()) return false;
        Intent fill = new Intent();
        Bundle res = new Bundle();
        for (RemoteInput r : a.getRemoteInputs()) res.putCharSequence(r.getResultKey(), text.trim());
        RemoteInput.addResultsToIntent(a.getRemoteInputs(), fill, res);
        return send(c, a.actionIntent, fill);
    }

    public static void open(Context c, HubStore hub, HubItem it) {
        open(c, hub, it, (android.graphics.Rect) null);
    }

    public static boolean openMail(Context c, HubItem it) {
        if (!HubStore.isMail(it)) return false;
        try {
            c.startActivity(com.bb10.launcher.mail.MailActivity.Companion.view(c, it.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Exception ignored) {
        }
        return true;
    }

    public static void open(Context c, HubStore hub, HubItem it, android.view.View src) {
        android.graphics.Rect r = null;
        if (src != null && src.isAttachedToWindow() && src.getWidth() > 0) {
            int[] l = new int[2];
            src.getLocationOnScreen(l);
            r = new android.graphics.Rect(l[0], l[1], l[0] + src.getWidth(), l[1] + src.getHeight());
        }
        open(c, hub, it, r);
    }

    public static void open(Context c, HubStore hub, HubItem it, android.graphics.Rect from) {
        if (openMail(c, it)) return;
        if (HubStore.isCall(it)) {
            if (!it.read) hub.setRead(it, true);
            com.bb10.launcher.LauncherActivity.openThroughFrame(c, CallLogHub.dialer(c), it.id, null);
            return;
        }
        android.app.PendingIntent target = hub.target(it);
        com.bb10.launcher.LauncherActivity.openThroughFrame(c, it.pkg, it.id, target);
    }
}