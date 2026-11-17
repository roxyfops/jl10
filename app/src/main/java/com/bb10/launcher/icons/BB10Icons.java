package com.bb10.launcher.icons;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.text.format.DateFormat;

import com.bb10.launcher.model.AppInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BB10Icons {
    public static final String PACK_ID = "bb10";
    public static final String PACK_LABEL = "BlackBerry 10 (built in)";
    private static final String DIR = "bb10/apps/";
    public static final String CALENDAR = "calendar";
    public static final String CLOCK = "clock";

    private static final Map<String, String> PKG = new HashMap<>();
    private static Map<String, String> sRoles;
    private static final Map<String, Bitmap> sCache = new HashMap<>();

    private static void p(String asset, String... pkgs) {
        for (String s : pkgs) PKG.put(s, asset);
    }

    static {
        p("phone", "com.bb10.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer");
        p("contacts", "com.bb10.contacts", "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts");
        p("text_messages", "com.android.mms", "com.google.android.apps.messaging", "com.android.messaging",
                "com.samsung.android.messaging", "org.fossify.messages", "com.textra");
        p("camera", "com.bb10.camera", "com.android.camera", "com.android.camera2", "org.codeaurora.snapcam", "com.google.android.GoogleCamera",
                "com.mediatek.camera", "com.sec.android.app.camera", "net.sourceforge.opencamera", "app.grapheneos.camera");
        p("picture_viewer", "com.android.gallery3d", "com.android.gallery", "com.google.android.apps.photos",
                "com.mediatek.gallery3d", "org.fossify.gallery", "com.sec.android.gallery3d", "com.google.android.apps.photosgo");
        p("browser", "com.android.chrome", "com.android.browser", "org.mozilla.firefox", "com.brave.browser",
                "com.opera.browser", "com.opera.mini.native", "com.duckduckgo.mobile.android", "com.microsoft.emmx",
                "com.kiwibrowser.browser", "org.bromite.bromite", "app.vanadium.browser", "com.sec.android.app.sbrowser");
        p(CALENDAR, "com.android.calendar", "com.google.android.calendar", "org.fossify.calendar",
                "com.samsung.android.calendar", "ws.xsoh.etar", "org.lineageos.etar");
        p("clock", "com.bb10.clock", "com.android.deskclock", "com.google.android.deskclock", "org.fossify.clock", "com.sec.android.app.clockpackage");
        p("calculator", "com.android.calculator2", "com.google.android.calculator", "org.fossify.calculator",
                "com.sec.android.app.popupcalculator");
        p("settings", "com.android.settings");
        p("maps", "com.google.android.apps.maps", "com.here.app.maps", "net.osmand", "net.osmand.plus",
                "app.organicmaps", "com.waze", "com.google.android.apps.mapslite");
        p("music_air_tunes", "com.android.music", "com.google.android.apps.youtube.music", "org.lineageos.eleven",
                "org.fossify.musicplayer", "com.maxmpz.audioplayer", "in.krosbits.musicolet", "com.sec.android.app.music");
        p("file_manager", "com.android.documentsui", "com.google.android.documentsui", "com.google.android.apps.nbu.files",
                "com.mediatek.filemanager", "com.android.fileexplorer", "org.fossify.filemanager", "com.sec.android.app.myfiles",
                "com.ghisler.android.TotalCommander", "com.lonelycatgames.Xplore", "me.zhanghai.android.files");
        p("video_player", "org.videolan.vlc", "com.google.android.videos", "com.mxtech.videoplayer.ad",
                "com.mxtech.videoplayer.pro", "is.xyz.mpv", "com.android.gallery3d.videoplayer");
        p("weather", "com.bb10.weather", "com.google.android.apps.weather", "com.accuweather.android", "com.weather.Weather",
                "org.breezyweather", "com.sec.android.daemonapp", "org.fossify.weather");
        p("compass", "com.google.android.apps.compass", "com.gamma.compass", "com.vincentlee.compass");
        p("remember", "com.google.android.keep", "org.fossify.notes", "com.samsung.android.app.notes",
                "com.microsoft.todos", "com.todoist", "com.ticktick.task");
        p("blackberry_world", "com.android.vending", "org.fdroid.fdroid", "com.aurora.store", "com.machiav3lli.fdroid",
                "com.looker.droidify", "dev.imranr.obtainium");
        p("amazon_app_store", "com.amazon.venezia");
        p("assistant_search", "com.google.android.googlequicksearchbox", "com.google.android.apps.googleassistant",
                "com.google.android.apps.searchlite");
        p("bbm_1", "com.bbm", "com.bbm.enterprise", "com.bbm.consumer");
        p("docs_to_go", "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs",
                "cn.wps.moffice_eng", "com.microsoft.office.officehubrow", "com.microsoft.office.word",
                "com.dataviz.docstogo", "org.documentfoundation.libreoffice", "com.collabora.libreoffice");
        p("adobe_acrobat_reader", "com.adobe.reader");
        p("evernote", "com.evernote");
        p("facebook_1", "com.facebook.katana", "com.facebook.lite");
        p("foursquare_1", "com.joelapenna.foursquared", "com.foursquare.robin");
        p("linkedin_1", "com.linkedin.android", "com.linkedin.android.lite");
        p("twitter_1", "com.twitter.android", "com.twitter.android.lite");
        p("youtube_1", "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
                "com.vanced.android.youtube", "org.schabi.newpipe", "free.rm.skytube.oss");
        p("whatsapp", "com.whatsapp", "com.whatsapp.w4b");
        p("wechat", "com.tencent.mm");
        p("dropbox", "com.dropbox.android");
        p("box", "com.box.android");
        p("tunein_pro", "tunein.player", "radiotime.player");
        p("bbc_news", "bbc.mobile.news.ww", "bbc.mobile.news.uk", "bbc.mobile.news.int");
        p("the_economist", "com.economist.lamarr", "com.economist.hummingbird");
        p("press_reader", "com.newspaperdirect.pressreader.android");
        p("sim_tools", "com.android.stk", "com.android.stk1", "com.android.stk2");
        p("nfc_smart_tags", "com.wakdev.wdnfc", "com.wakdev.nfctasks", "com.nxp.taginfolite");
        p("voicemail", "com.google.android.apps.googlevoice", "com.android.phone.vvm", "com.google.android.vvm");
        p("password_keeper", "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid",
                "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
                "proton.android.pass", "com.google.android.apps.authenticator2");
        p("help", "com.google.android.apps.helprtc", "com.android.help");
        p("tutorials", "com.google.android.apps.tips", "com.android.tips");
        p("google_talk", "com.google.android.talk", "com.google.android.apps.dynamite");
        p("screen_reader", "com.google.android.marvin.talkback");
        p("blackberry_blend", "com.microsoft.appmanager", "com.google.android.apps.chromecast.app");
        p("story_maker_movie_magic", "com.google.android.apps.youtube.creator", "com.lemon.lvoverseas", "com.cyberlink.powerdirector.DRA140225_01");
        p("bbm_meetings", "us.zoom.videomeetings", "com.microsoft.teams", "com.google.android.apps.meetings", "com.cisco.webex.meetings");
        p("bluetooth_2", "com.android.bluetooth");
    }

    private BB10Icons() {}

    private static synchronized Map<String, String> roles(Context c) {
        if (sRoles != null) return sRoles;
        Map<String, String> m = new HashMap<>();
        PackageManager pm = c.getPackageManager();
        String[][] cats = {
                {Intent.CATEGORY_APP_BROWSER, "browser"},
                {Intent.CATEGORY_APP_CALENDAR, CALENDAR},
                {Intent.CATEGORY_APP_CALCULATOR, "calculator"},
                {Intent.CATEGORY_APP_CONTACTS, "contacts"},
                {Intent.CATEGORY_APP_GALLERY, "picture_viewer"},
                {Intent.CATEGORY_APP_MAPS, "maps"},
                {Intent.CATEGORY_APP_MESSAGING, "text_messages"},
                {Intent.CATEGORY_APP_MUSIC, "music_air_tunes"},
                {Intent.CATEGORY_APP_FILES, "file_manager"},
                {Intent.CATEGORY_APP_MARKET, "blackberry_world"},
        };
        for (String[] cat : cats) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat[0]), 0)) {
                m.putIfAbsent(ri.activityInfo.packageName, cat[1]);
            }
        }
        putResolved(m, pm, new Intent(Intent.ACTION_DIAL), "phone");
        putResolved(m, pm, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "camera");
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(c);
            if (sms != null) m.put(sms, "text_messages");
        } catch (Exception ignored) {
        }
        sRoles = m;
        return m;
    }

    private static void putResolved(Map<String, String> m, PackageManager pm, Intent i, String asset) {
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            m.putIfAbsent(ri.activityInfo.packageName, asset);
        }
    }

    public static synchronized void invalidate() {
        sRoles = null;
    }

    public static String assetFor(Context c, AppInfo a) {
        if (a.pkg.equals(c.getPackageName())) {
            if (a.cn.getClassName().endsWith("HubShortcutActivity")) return "blackberry_hub";
            return "setup";
        }
        String s = PKG.get(a.pkg);
        if (s == null) s = roles(c).get(a.pkg);
        return s;
    }

    public static Drawable iconFor(Context c, AppInfo a) {
        String asset = assetFor(c, a);
        return asset == null ? null : drawable(c, asset);
    }

    public static Drawable drawable(Context c, String asset) {
        if (CLOCK.equals(asset)) {
            Bitmap face = bitmap(c, "clock_face_blank");
            if (face != null) return new ClockDrawable(face);
        }
        Bitmap b = CALENDAR.equals(asset) ? calendar(c) : bitmap(c, asset);
        return b == null ? null : new BitmapDrawable(c.getResources(), b);
    }

    public static synchronized Bitmap bitmap(Context c, String asset) {
        Bitmap b = sCache.get(asset);
        if (b == null) {
            try (InputStream is = c.getAssets().open(DIR + asset + ".png")) {
                b = BitmapFactory.decodeStream(is);
            } catch (Exception e) {
                return null;
            }
            if (b != null) sCache.put(asset, b);
        }
        return b;
    }

    public static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String[] names = c.getAssets().list("bb10/apps");
            if (names != null) {
                for (String n : names) if (n.endsWith(".png")) out.add(n.substring(0, n.length() - 4));
            }
        } catch (Exception ignored) {
        }
        out.remove("calendar_31");
        out.remove("calendar_base_blank");
        out.remove("clock_face_blank");
        out.add(0, CALENDAR);
        return out;
    }

    public static Bitmap calendar(Context c) {
        Bitmap base = bitmap(c, "calendar_base_blank");
        if (base == null) return null;
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        Canvas cv = new Canvas(out);
        float S = out.getWidth();
        Calendar now = Calendar.getInstance();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        String day = DateFormat.format("EEEE", now).toString();
        p.setColor(0xFFFFFFFF);
        p.setTypeface(com.bb10.launcher.ui.Ui.medium());
        p.setTextSize(S * 0.15f);
        float maxW = S * 0.78f, w = p.measureText(day);
        if (w > maxW) p.setTextScaleX(maxW / w);
        c(cv, p, day, S / 2f, S * 0.151f);

        p.setTextScaleX(1f);
        p.setColor(0xFF669933);
        p.setTypeface(com.bb10.launcher.ui.Ui.bold());
        p.setTextSize(S * 0.5f);
        c(cv, p, String.format(Locale.US, "%02d", now.get(Calendar.DAY_OF_MONTH)), S / 2f, S * 0.612f);
        return out;
    }

    public interface Live {
        long nextChangeIn();
    }

    public static final class ClockDrawable extends Drawable implements Live {
        private final Bitmap face;
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private int alpha = 255;

        ClockDrawable(Bitmap face) {
            this.face = face;
            hand.setColor(0xFFFFFFFF);
            hand.setStrokeCap(Paint.Cap.BUTT);
            shadow.setColor(0x38000000);
            shadow.setStrokeCap(Paint.Cap.BUTT);
            dot.setColor(0xFF0686D5);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float S = Math.min(b.width(), b.height());
            if (S <= 0) return;
            float x0 = b.left + (b.width() - S) / 2f, y0 = b.top + (b.height() - S) / 2f;
            dst.set(x0, y0, x0 + S, y0 + S);
            bmp.setAlpha(alpha);
            c.drawBitmap(face, null, dst, bmp);
            float cx = x0 + S / 2f, cy = y0 + S / 2f;
            Calendar now = Calendar.getInstance();
            float sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f, min = now.get(Calendar.MINUTE) + sec / 60f;
            float hr = (now.get(Calendar.HOUR) % 12) + min / 60f;
            float w = S * 0.032f, off = S * 0.016f;
            line(c, shadow, cx + off, cy + off * 1.4f, hr * 30f, S * 0.26f, w * 1.3f);
            line(c, shadow, cx + off, cy + off * 1.4f, min * 6f, S * 0.385f, w * 1.3f);
            line(c, hand, cx, cy, hr * 30f, S * 0.26f, w);
            line(c, hand, cx, cy, min * 6f, S * 0.385f, w);
            shadow.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + off * 0.8f, cy + off, S * 0.048f, shadow);
            hand.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, S * 0.042f, hand);
            dot.setAlpha(alpha);
            c.drawCircle(cx, cy, S * 0.0235f, dot);
            double a = Math.toRadians(sec * 6f);
            float r = S * 0.4125f;
            c.drawCircle(cx + (float) (Math.sin(a) * r), cy - (float) (Math.cos(a) * r), S * 0.024f, dot);
        }

        private static void line(Canvas c, Paint p, float cx, float cy, float deg, float len, float w) {
            double a = Math.toRadians(deg);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            c.drawLine(cx, cy, cx + (float) (Math.sin(a) * len), cy - (float) (Math.cos(a) * len), p);
        }

        @Override
        public long nextChangeIn() {
            return 66;
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            hand.setAlpha(a);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            bmp.setColorFilter(cf);
            hand.setColorFilter(cf);
            dot.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() { return face.getWidth(); }

        @Override
        public int getIntrinsicHeight() { return face.getHeight(); }
    }

    private static void c(Canvas cv, Paint p, String s, float cx, float cy) {
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds(s, 0, s.length(), r);
        cv.drawText(s, cx, cy - r.exactCenterY(), p);
    }
}
