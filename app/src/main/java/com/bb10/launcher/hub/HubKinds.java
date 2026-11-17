package com.bb10.launcher.hub;

import android.app.Notification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class HubKinds {
    private HubKinds() {
    }

    public static final String NOTIF = "notif:";
    public static final String CALLS = "calls:";

    private static final Set<String> COMM = new HashSet<>(Arrays.asList(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram",
            "org.telegram.plus", "nekox.messenger", "tw.nekomimi.nekogram", "com.exteragram.messenger", "uz.unnarsx.cherrygram",
            "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "im.molly.app", "com.facebook.orca", "com.facebook.mlite",
            "com.discord", "com.Slack", "com.microsoft.teams", "com.skype.raider", "com.viber.voip", "jp.naver.line.android",
            "com.tencent.mm", "com.kakao.talk", "ch.threema.app", "ch.threema.app.libre", "im.vector.app", "io.element.android.x",
            "network.loki.messenger", "com.wire", "com.snapchat.android", "com.beeper.android", "com.bbm", "com.bbm.enterprise",
            "com.google.android.apps.dynamite", "com.google.android.apps.googlevoice", "com.google.android.talk",
            "us.zoom.videomeetings", "com.mattermost.rn", "chat.rocket.android", "com.zulipchat.android",
            "org.briarproject.briar.android", "chat.simplex.app", "chat.delta", "eu.siacs.conversations", "cx.ring",
            "kik.android", "com.imo.android.imoim", "com.instagram.barcelona", "com.icq.mobile.client", "com.bb10.messages",
            "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.lite", "com.twitter.android",
            "com.reddit.frontpage", "ml.docilealligator.infinityforreddit", "com.linkedin.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.pinterest", "com.tumblr", "org.joinmastodon.android", "com.keylesspalace.tusky",
            "xyz.blueskyweb.app", "com.vkontakte.android", "com.bereal.ft", "tv.twitch.android.app", "com.tinder", "com.bumble.app",
            "co.hinge.app", "com.grindrapp.android", "com.nianticlabs.campfire", "com.github.android", "com.quora.android",
            "com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging", "com.moez.QKSMS",
            "dev.octoshrimp.qksms", "org.fossify.messages", "com.simplemobiletools.smsmessenger", "com.textra",
            "com.android.mms", "org.lineageos.messaging",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.fsck.k9", "net.thunderbird.android",
            "com.android.email", "eu.faircode.email", "com.readdle.spark", "ch.protonmail.android",
            "com.yahoo.mobile.client.android.mail", "me.bluemail.mail", "org.kman.AquaMail",
            "com.samsung.android.email.provider", "com.boxer.email", "de.tutao.tutanota", "com.zoho.mail",
            "ru.mail.mailapp", "com.blackberry.hub", "com.blackberry.email"));

    private static final Set<String> DIALERS = new HashSet<>(Arrays.asList(
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "org.fossify.phone", "com.simplemobiletools.dialer", "com.bb10.phone", "com.android.server.telecom",
            "com.android.phone", "org.lineageos.dialer"));

    public static boolean isDialer(String pkg) {
        return pkg != null && DIALERS.contains(pkg);
    }

    public static boolean knownComm(String pkg) {
        return pkg != null && (COMM.contains(pkg) || DIALERS.contains(pkg) || pkg.startsWith("mail:") || pkg.equals(CALLS));
    }

    public static boolean isComm(Notification n) {
        if (n == null) return false;
        String c = n.category;
        if (Notification.CATEGORY_MESSAGE.equals(c) || Notification.CATEGORY_EMAIL.equals(c)
                || Notification.CATEGORY_CALL.equals(c) || Notification.CATEGORY_MISSED_CALL.equals(c)
                || "voicemail".equals(c)) return true;
        String tpl = n.extras == null ? null : n.extras.getString(Notification.EXTRA_TEMPLATE);
        return tpl != null && tpl.contains("MessagingStyle");
    }

    public static String group(String pkg, boolean comm) {
        if (isDialer(pkg) || CALLS.equals(pkg)) return CALLS;
        return comm ? pkg : NOTIF;
    }
}
