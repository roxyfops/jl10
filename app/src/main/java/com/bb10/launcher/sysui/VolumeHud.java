package com.bb10.launcher.sysui;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.R;
import com.bb10.launcher.ui.RingerGlyph;
import com.bb10.launcher.ui.Tr;
import com.bb10.launcher.ui.Ui;

import java.io.InputStream;
import java.util.List;

final class VolumeHud extends FrameLayout {
    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final long TIMEOUT = 3000;
    private static VolumeHud sInstance;

    static volatile int lastStream = -1;

    static boolean shown(int s) {
        return s == AudioManager.STREAM_MUSIC || s == AudioManager.STREAM_RING || s == AudioManager.STREAM_ALARM
                || s == AudioManager.STREAM_NOTIFICATION || s == AudioManager.STREAM_VOICE_CALL;
    }

    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final AudioManager am;
    private final LinearLayout card, media;
    private final ImageView icon, art, playPause;
    private final TextView label, title, artist;
    private final LevelBar bar;
    private boolean added, hiding;
    private int stream = AudioManager.STREAM_MUSIC;
    private MediaController mc;

    private final Runnable hide = this::hide;
    private final MediaController.Callback mcb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            updateMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            updateMedia();
        }
    };

    static void onShowRequested() {
        if (SysUi.sys == null || SysUi.res == null) return;
        if (SysUi.shuttingDown()) {
            dismissNow();
            return;
        }
        try {
            if (sInstance == null) sInstance = new VolumeHud(windowContext());
            sInstance.show();
        } catch (Throwable t) {
            SysUi.note("volume overlay failed: " + android.util.Log.getStackTraceString(t));
        }
    }

    static void dismissNow() {
        VolumeHud h = sInstance;
        if (h != null) SysUi.main.post(() -> {
            try {
                h.removeNow();
            } catch (Throwable ignored) {
            }
        });
    }

    static void onVolumeChanged() {
        VolumeHud h = sInstance;
        if (h != null && h.added && !h.hiding) SysUi.main.post(() -> {
            try {
                h.refresh();
            } catch (Throwable t) {
                SysUi.note("volume refresh failed: " + t);
            }
        });
    }

    private static Context windowContext() {
        Context base = SysUi.sys;
        try {
            Display d = base.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            base = base.createWindowContext(d, TYPE_VOLUME_OVERLAY, null);
        } catch (Throwable t) {
            SysUi.note("volume window: " + t);
        }
        return SysUi.wrap(base);
    }

    private VolumeHud(Context c) {
        super(c);
        wm = c.getSystemService(WindowManager.class);
        am = c.getSystemService(AudioManager.class);
        float S = Ui.S();

        lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                TYPE_VOLUME_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.setTitle("BB10Volume");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        int sbId = android.content.res.Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android");
        lp.y = 0;

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xF5101010);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(0.018f * S), 0, Math.round(0.035f * S), 0);
        icon = new ImageView(c);
        int is = Math.round(0.083f * S);
        int ip = Math.round(0.008f * S);
        icon.setPadding(ip, ip, ip, ip);
        icon.setBackground(com.bb10.launcher.ui.Ui.selectable(0));
        icon.setOnClickListener(v -> {
            toggleMute();
            poke();
        });
        row.addView(icon, new LinearLayout.LayoutParams(is, is));
        label = text(c, "", 0.037f, 0xFFD2D2D2, false);
        centreCaps(label);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Math.round(0.017f * S);
        row.addView(label, llp);
        bar = new LevelBar(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Math.round(0.08f * S), 1f);
        blp.leftMargin = Math.round(0.03f * S);
        row.addView(bar, blp);
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.125f * S)));
        card.addView(rule(c));

        ringRow = new LinearLayout(c);
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x00000000);
        box.setStroke(Math.max(1, Math.round(0.0028f * S)), 0xFF5A5A5A);
        box.setCornerRadius(Math.round(0.008f * S));
        ringRow.setBackground(box);
        ringRow.setClipToOutline(true);
        int[] modes = {AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT};
        String[] names = {"Normal", "Vibrate", "Silent"};
        for (int i = 0; i < 3; i++) {
            final int mode = modes[i];
            LinearLayout seg = new LinearLayout(c);
            seg.setGravity(Gravity.CENTER);
            seg.setBackground(Ui.selectable(0));
            ImageView gi = new ImageView(c);
            gi.setImageDrawable(mode == AudioManager.RINGER_MODE_NORMAL ? glyph("speaker") : new RingerGlyph(mode, Ui.TEXT));
            int gs = Math.round(0.045f * S);
            seg.addView(gi, new LinearLayout.LayoutParams(gs, gs));
            TextView tv = text(c, names[i], 0.032f, 0xFF9A9A9A, false);
            centreCaps(tv);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = Math.round(0.012f * S);
            seg.addView(tv, tl);
            seg.setOnClickListener(v -> {
                setRinger(mode);
                poke();
            });
            segLabels[i] = tv;
            segBars[i] = seg;
            segIcons[i] = gi;
            ringRow.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            if (i < 2) {
                View sep = new View(c);
                sep.setBackgroundColor(0xFF5A5A5A);
                ringRow.addView(sep, new LinearLayout.LayoutParams(Math.max(1, Math.round(0.0028f * S)), ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        LinearLayout ringWrap = new LinearLayout(c);
        ringWrap.setOrientation(LinearLayout.VERTICAL);
        int rp = Math.round(0.025f * S);
        ringWrap.setPadding(rp, Math.round(0.02f * S), rp, Math.round(0.02f * S));
        ringWrap.addView(ringRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.075f * S)));
        LinearLayout ringAll = new LinearLayout(c);
        ringAll.setOrientation(LinearLayout.VERTICAL);
        ringAll.addView(ringWrap);
        ringAll.addView(rule(c));
        ringAllRef = ringAll;
        card.addView(ringAll);

        media = new LinearLayout(c);
        media.setGravity(Gravity.CENTER_VERTICAL);
        int mp = Math.round(0.011f * S);
        media.setPadding(mp, Math.round(0.014f * S), mp, Math.round(0.014f * S));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF3A3A3A);
        int as = Math.round(0.163f * S);
        media.addView(art, new LinearLayout.LayoutParams(as, as));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(c, "", 0.0375f, Ui.TEXT, false);
        artist = text(c, "", 0.0375f, Ui.TEXT, false);
        artist.setPadding(0, Math.round(0.014f * S), 0, 0);
        texts.addView(title);
        texts.addView(artist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Math.round(0.028f * S);
        media.addView(texts, tlp);
        int bs = Math.round(0.125f * S);
        media.addView(button(c, R.drawable.ic_m_prev, () -> mc.getTransportControls().skipToPrevious()), new LinearLayout.LayoutParams(bs, bs));
        playPause = button(c, R.drawable.ic_m_play, () -> {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        media.addView(playPause, new LinearLayout.LayoutParams(bs, bs));
        media.addView(button(c, R.drawable.ic_m_next, () -> mc.getTransportControls().skipToNext()), new LinearLayout.LayoutParams(bs, bs));
        LinearLayout mediaWrap = new LinearLayout(c);
        mediaWrap.setOrientation(LinearLayout.VERTICAL);
        mediaWrap.addView(media);
        card.addView(mediaWrap);
        mediaWrapRef = mediaWrap;

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = clp.rightMargin = 0;
        clp.bottomMargin = 0;
        addView(card, clp);
    }

    private final LinearLayout mediaWrapRef, ringAllRef;
    private final LinearLayout ringRow;

    private static View rule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF5E5E5E);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.0028f * Ui.S()))));
        return v;
    }
    private final TextView[] segLabels = new TextView[3];
    private final View[] segBars = new View[3];
    private final ImageView[] segIcons = new ImageView[3];
    private final java.util.Map<String, Drawable> glyphs = new java.util.HashMap<>();
    private Drawable vibrateIcon, silentIcon;

    private static TextView text(Context c, CharSequence s, float sizeS, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(Tr.t(s));
        t.setTextColor(color);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeS * Ui.S());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static void centreCaps(TextView t) {
        Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        android.graphics.Rect caps = new android.graphics.Rect();
        t.getPaint().getTextBounds("H", 0, 1, caps);
        float box = (fm.ascent + fm.descent) / 2f;
        float cap = (caps.top + caps.bottom) / 2f;
        t.setTranslationY(box - cap);
    }

    private ImageView button(Context c, int res, Runnable action) {
        ImageView b = new ImageView(c);
        Drawable d = SysUi.res.getDrawable(res, null).mutate();
        d.setTint(Ui.TEXT);
        b.setImageDrawable(d);
        int p = Math.round(0.031f * Ui.S());
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.selectable(0));
        b.setOnClickListener(v -> {
            if (mc != null) {
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            }
            poke();
        });
        return b;
    }

    private Drawable glyph(String name) {
        Drawable cached = glyphs.get(name);
        if (cached != null) return cached.getConstantState().newDrawable(SysUi.res).mutate();
        try (InputStream in = SysUi.res.getAssets().open("bb10/vol/" + name + ".png")) {
            Bitmap b = BitmapFactory.decodeStream(in);
            Drawable d = new BitmapDrawable(SysUi.res, b);
            glyphs.put(name, d);
            return d.getConstantState().newDrawable(SysUi.res).mutate();
        } catch (Throwable e) {
            return null;
        }
    }

    private void show() {
        stream = pickStream();
        if (!added) {
            try {
                wm.addView(this, lp);
            } catch (Throwable e) {
                SysUi.note("can't show BB10 volume: " + e);
                return;
            }
            added = true;
            attachMedia();
            card.setAlpha(0f);
            card.setTranslationY(-Ui.s(0.45f));
        }
        hiding = false;
        card.animate().cancel();
        card.animate().alpha(1f).translationY(0).setDuration(140).start();
        refresh();
        poke();
    }

    private void poke() {
        SysUi.main.removeCallbacks(hide);
        SysUi.main.postDelayed(hide, TIMEOUT);
    }

    private void hide() {
        if (!added || hiding) return;
        hiding = true;
        card.animate().cancel();
        card.animate().alpha(0f).translationY(-Ui.s(0.45f)).setDuration(160).withEndAction(this::removeNow).start();
    }

    private void removeNow() {
        SysUi.main.removeCallbacks(hide);
        if (mc != null) {
            try {
                mc.unregisterCallback(mcb);
            } catch (Throwable ignored) {
            }
            mc = null;
        }
        if (!added) return;
        added = false;
        hiding = false;
        try {
            wm.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            hide();
            return true;
        }
        poke();
        return super.dispatchTouchEvent(e);
    }

    private int pickStream() {
        int s = lastStream;
        if (s == AudioManager.STREAM_MUSIC || s == AudioManager.STREAM_RING || s == AudioManager.STREAM_ALARM
                || s == AudioManager.STREAM_NOTIFICATION || s == AudioManager.STREAM_VOICE_CALL) return s;
        if (added && !hiding) return stream;
        if (am.getMode() == AudioManager.MODE_IN_CALL || am.getMode() == AudioManager.MODE_IN_COMMUNICATION)
            return AudioManager.STREAM_VOICE_CALL;
        return am.isMusicActive() ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_RING;
    }

    private void refresh() {
        try {
            refreshInner();
        } catch (Throwable t) {
            SysUi.note("volume refresh failed: " + t);
        }
    }

    private void refreshInner() {
        stream = pickStream();
        int max = am.getStreamMaxVolume(stream), min = am.getStreamMinVolume(stream);
        int v = am.getStreamVolume(stream);
        bar.set(min, max, v);
        boolean muted = v <= min || am.isStreamMute(stream);
        String what;
        switch (stream) {
            case AudioManager.STREAM_RING:
                what = "Ringer";
                break;
            case AudioManager.STREAM_NOTIFICATION:
                what = "Notifications";
                break;
            case AudioManager.STREAM_ALARM:
                what = "Alarm";
                break;
            case AudioManager.STREAM_VOICE_CALL:
                what = "Call";
                break;
            default:
                what = "Media";
        }
        boolean ringish = stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION;
        int mode = am.getRingerMode();
        Output out = ringish ? null : output(stream);
        if (out != null && !TextUtils.isEmpty(out.name)) what += " - " + out.name;
        else what += " Volume";
        if (ringish && mode == AudioManager.RINGER_MODE_VIBRATE) what += " · Vibrate";
        if (ringish && mode == AudioManager.RINGER_MODE_SILENT) what += " · Silent";
        label.setText(Tr.t(what));
        if (ringish && mode == AudioManager.RINGER_MODE_VIBRATE) {
            if (vibrateIcon == null) vibrateIcon = new RingerGlyph(mode, Ui.TEXT);
            icon.setImageDrawable(vibrateIcon);
        } else if (ringish && mode == AudioManager.RINGER_MODE_SILENT) {
            if (silentIcon == null) silentIcon = new RingerGlyph(mode, Ui.TEXT);
            icon.setImageDrawable(silentIcon);
        } else if (out != null) {
            icon.setImageDrawable(new com.bb10.launcher.ui.OutputGlyph(out.kind, muted, Ui.TEXT));
        } else {
            icon.setImageDrawable(glyph(muted ? (stream == AudioManager.STREAM_RING ? "silent" : "muted") : "speaker"));
        }
        ringAllRef.setVisibility(stream == AudioManager.STREAM_VOICE_CALL ? GONE : VISIBLE);
        int[] modes = {AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT};
        for (int i = 0; i < 3; i++) {
            boolean on = modes[i] == mode;
            segLabels[i].setTextColor(on ? Ui.TEXT : 0xFF9A9A9A);
            if (on) segBars[i].setBackgroundColor(0xFF3C3C3C);
            else segBars[i].setBackground(Ui.selectable(0));
            segIcons[i].setAlpha(on ? 1f : 0.5f);
        }
        updateMedia();
    }

    private static final class Output {
        final int kind;
        final String name;

        Output(int kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    private Output output(int stream) {
        try {
            java.util.List<android.media.AudioDeviceInfo> devs;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                int usage = stream == AudioManager.STREAM_VOICE_CALL ? android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                        : stream == AudioManager.STREAM_ALARM ? android.media.AudioAttributes.USAGE_ALARM
                        : android.media.AudioAttributes.USAGE_MEDIA;
                devs = am.getAudioDevicesForAttributes(new android.media.AudioAttributes.Builder().setUsage(usage).build());
            } else {
                devs = java.util.Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
            }
            Output wired = null;
            for (android.media.AudioDeviceInfo d : devs) {
                switch (d.getType()) {
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case android.media.AudioDeviceInfo.TYPE_BLE_HEADSET:
                    case android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    case android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST:
                    case android.media.AudioDeviceInfo.TYPE_HEARING_AID: {
                        CharSequence n = d.getProductName();
                        return new Output(com.bb10.launcher.ui.OutputGlyph.BLUETOOTH, n == null ? "Bluetooth" : n.toString().trim());
                    }
                    case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                        if (wired == null) wired = new Output(com.bb10.launcher.ui.OutputGlyph.HEADPHONES,
                                d.getType() == android.media.AudioDeviceInfo.TYPE_USB_HEADSET && d.getProductName() != null
                                        ? d.getProductName().toString().trim() : "Headphones");
                        break;
                    default:
                }
            }
            return wired;
        } catch (Throwable t) {
            return null;
        }
    }

    private final android.util.SparseIntArray beforeMute = new android.util.SparseIntArray();

    private void toggleMute() {
        try {
            if (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION) {
                int m = am.getRingerMode();
                android.os.Vibrator vib = SysUi.sys.getSystemService(android.os.Vibrator.class);
                boolean canVibrate = vib != null && vib.hasVibrator();
                int target = m == AudioManager.RINGER_MODE_NORMAL
                        ? (canVibrate ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT)
                        : m == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                applyRinger(target);
                return;
            } else {
                int min = am.getStreamMinVolume(stream), max = am.getStreamMaxVolume(stream);
                int v = am.getStreamVolume(stream);
                if (v > min && !am.isStreamMute(stream)) {
                    beforeMute.put(stream, v);
                    am.setStreamVolume(stream, min, 0);
                } else {
                    if (am.isStreamMute(stream)) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    int back = beforeMute.get(stream, 0);
                    am.setStreamVolume(stream, back > min ? back : Math.max(min + 1, max / 3), 0);
                }
            }
        } catch (Throwable e) {
            SysUi.note("mute failed: " + e);
        }
        SysUi.main.postDelayed(this::refresh, 60);
    }

    private void setRinger(int mode) {
        try {
            applyRinger(mode);
        } catch (Throwable e) {
            SysUi.note("ringer mode failed: " + e);
        }
    }

    private void applyRinger(int mode) {
        try {
            AudioManager.class.getMethod("setRingerModeInternal", int.class).invoke(am, mode);
        } catch (Throwable e) {
            am.setRingerMode(mode);
        }
        SysUi.main.postDelayed(this::refresh, 60);
    }

    private void setLevel(int v) {
        try {
            am.setStreamVolume(stream, v, 0);
        } catch (Throwable ignored) {
        }
        refresh();
    }

    private void attachMedia() {
        mc = null;
        try {
            MediaSessionManager msm = SysUi.sys.getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions((ComponentName) null);
            MediaController paused = null;
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (c.getMetadata() == null || s == null) continue;
                int st = s.getState();
                boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
                if (SysUi.hiddenPlayer(c.getPackageName(), playing)) continue;
                if (playing) {
                    mc = c;
                    break;
                }
                if (paused == null && st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED && st != PlaybackState.STATE_ERROR)
                    paused = c;
            }
            if (mc == null) mc = paused;
            if (mc != null) mc.registerCallback(mcb, SysUi.main);
        } catch (Throwable e) {
            SysUi.note("volume media: " + e);
        }
    }

    private void updateMedia() {
        MediaMetadata m = mc == null ? null : mc.getMetadata();
        if (m == null) {
            mediaWrapRef.setVisibility(GONE);
            return;
        }
        mediaWrapRef.setVisibility(VISIBLE);
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(a)) a = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        title.setText(t);
        artist.setText(a);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            art.setImageBitmap(b);
        } else {
            int ap = Math.round(0.035f * Ui.S());
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setPadding(ap, ap, ap, ap);
            try {
                art.setImageDrawable(SysUi.res.getDrawable(R.drawable.ic_music_note, null));
            } catch (Throwable e) {
                art.setImageDrawable(null);
            }
        }
        PlaybackState s = mc.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        Drawable d = SysUi.res.getDrawable(playing ? R.drawable.ic_m_pause : R.drawable.ic_m_play, null).mutate();
        d.setTint(Ui.TEXT);
        playPause.setImageDrawable(d);
    }

    private final class LevelBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int min, max = 1, value;
        private final RectF track = new RectF();

        LevelBar(Context c) {
            super(c);
        }

        void set(int min, int max, int value) {
            this.min = min;
            this.max = Math.max(min + 1, max);
            this.value = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float S = Ui.S();
            float h = getHeight(), w = getWidth();
            float cy = h / 2f;
            int n = Math.max(1, max - min);
            float gap = 0.008f * S;
            float dash = (w - gap * (n - 1)) / n;
            if (dash < 0.012f * S) gap = 0f;
            dash = (w - gap * (n - 1)) / n;
            int lit = value - min;
            float on = 0.0056f * S, off = 0.0028f * S;
            for (int i = 0; i < n; i++) {
                float x0 = i * (dash + gap);
                boolean l = i < lit;
                float th = l ? on : off;
                p.setColor(l ? 0xFFFFFFFF : 0xFF8C8C8C);
                track.set(x0, cy - th / 2, x0 + dash, cy + th / 2);
                c.drawRect(track, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP: {
                    float f = e.getX() / Math.max(1f, getWidth());
                    f = Math.max(0f, Math.min(1f, f));
                    int v = min + (int) Math.ceil(f * (max - min) - 0.15f);
                    v = Math.max(min, Math.min(max, v));
                    if (v != value) setLevel(v);
                    poke();
                    return true;
                }
            }
            return true;
        }
    }
}
