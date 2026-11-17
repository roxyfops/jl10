package com.bb10.launcher.lock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.gesture.GestureService;
import com.bb10.launcher.R;
import com.bb10.launcher.hub.HubStore;
import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.util.List;

public class LockActivity extends Activity {
    private FrameLayout root;
    private LinearLayout content, notes;
    private TextView clock, date;
    private float downY;
    private static volatile boolean showing;
    private View scrim;

    public static boolean isShowing() {
        return showing;
    }
    private boolean unlocking;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) finish();
            else update();
        }
    };

    private final HubStore.Listener hubChanged = this::update;
    private final Runnable musicChanged = this::updateMusic;

    private LinearLayout music;
    private ImageView musicArt, musicPlay;
    private TextView musicTitle, musicArtist;

    private View buildMusic() {
        float S = Ui.S();
        music = new LinearLayout(this);
        music.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(0.04f * S);
        music.setPadding(pad, Math.round(0.02f * S), pad, Math.round(0.02f * S));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x80000000);
        bg.setCornerRadius(Ui.dp(4));
        music.setBackground(bg);
        musicArt = new ImageView(this);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        int as = Math.round(0.13f * S);
        music.addView(musicArt, new LinearLayout.LayoutParams(as, as));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        musicTitle = Ui.text(this, "", 0, Ui.TEXT, true);
        musicTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.032f * S);
        musicTitle.setSingleLine(true);
        musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        musicArtist = Ui.text(this, "", 0, 0xCCFFFFFF, false);
        musicArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.027f * S);
        musicArtist.setSingleLine(true);
        musicArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        musicArtist.setPadding(0, Math.round(0.008f * S), 0, 0);
        texts.addView(musicTitle);
        texts.addView(musicArtist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Math.round(0.03f * S);
        music.addView(texts, tlp);
        int bs = Math.round(0.095f * S);
        music.addView(musicButton(R.drawable.ic_m_prev, mc -> mc.getTransportControls().skipToPrevious()), new LinearLayout.LayoutParams(bs, bs));
        musicPlay = musicButton(R.drawable.ic_m_play, mc -> {
            if (com.bb10.launcher.frames.LiveFrames.isPlaying(mc)) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        music.addView(musicPlay, new LinearLayout.LayoutParams(bs, bs));
        music.addView(musicButton(R.drawable.ic_m_next, mc -> mc.getTransportControls().skipToNext()), new LinearLayout.LayoutParams(bs, bs));
        music.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = Math.round(0.05f * S);
        lp.topMargin = Math.round(0.035f * S);
        music.setLayoutParams(lp);
        return music;
    }

    private interface MediaAction {
        void run(android.media.session.MediaController mc);
    }

    private ImageView musicButton(int icon, MediaAction a) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(Ui.glyph(this, icon, Ui.TEXT));
        int p = Math.round(0.022f * Ui.S());
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.selectable(0));
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            android.media.session.MediaController mc = LauncherApp.get().live.current();
            if (mc != null) a.run(mc);
        });
        return b;
    }

    private void updateMusic() {
        if (music == null) return;
        android.media.session.MediaController mc = LauncherApp.get().live.current();
        android.media.MediaMetadata md = mc == null ? null : mc.getMetadata();
        if (md == null) {
            music.setVisibility(View.GONE);
            return;
        }
        music.setVisibility(View.VISIBLE);
        musicTitle.setText(md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE));
        CharSequence ar = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (ar == null) ar = md.getText(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        musicArtist.setText(ar);
        android.graphics.Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (art != null) musicArt.setImageBitmap(art);
        else musicArt.setImageDrawable(LauncherApp.get().iconForPackage(mc.getPackageName()));
        musicPlay.setImageDrawable(Ui.glyph(this, com.bb10.launcher.frames.LiveFrames.isPlaying(mc) ? R.drawable.ic_m_pause : R.drawable.ic_m_play, Ui.TEXT));
    }

    public static void show(Context c) {
        try {
            c.startActivity(new Intent(c, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LauncherApp.get().ensureInit();
        setShowWhenLocked(true);
        build();
        setContentView(root);
        loadWallpaper();
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getInsets(WindowInsets.Type.statusBars()).top;
            content.setPadding(0, Math.max(top, StatusBarView.height()), 0, in.getInsets(WindowInsets.Type.navigationBars()).bottom);
            return in;
        });
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            ic.hide(WindowInsets.Type.navigationBars());
            ic.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    private void build() {
        float S = Ui.S();
        root = new Root();
        wallImage = new ImageView(this);
        wallImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim = new View(this);
        scrim.setBackgroundColor(0x40000000);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout body = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this);
        leftCol = left;
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Math.round(0.045f * S);
        left.addView(notes, nl);
        body.addView(left, new LinearLayout.LayoutParams(Math.round(0.19f * S), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        rightCol = right;
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(Math.round(0.02f * S), 0, Math.round(0.04f * S), 0);
        right.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::fitClock);
        });
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setBaselineAligned(true);
        clock = lockText(0.25f, false);
        clock.setTypeface(Ui.light());
        clockRow.addView(clock);
        ampm = lockText(0.065f, false);
        ampm.setTypeface(Ui.light());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = Math.round(0.015f * S);
        clockRow.addView(ampm, al);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crl.topMargin = Math.round(0.03f * S);
        right.addView(clockRow, crl);
        date = lockText(0.05f, false);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = Math.round(0.008f * S);
        right.addView(date, dl);
        events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = Math.round(0.045f * S);
        el.leftMargin = Math.round(0.008f * S);
        right.addView(events, el);
        View m = buildMusic();
        LinearLayout.LayoutParams ml = (LinearLayout.LayoutParams) m.getLayoutParams();
        ml.leftMargin = 0;
        ml.rightMargin = 0;
        right.addView(m);
        body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout bottom = new FrameLayout(this);
        hint = lockText(0.032f, false);
        hint.setText("Swipe up to unlock");
        hint.setAlpha(0f);
        bottom.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ImageView cam = new ImageView(this);
        cam.setImageDrawable(Ui.glyph(this, R.drawable.ic_camera, Ui.TEXT));
        int cp = Math.round(0.02f * S);
        cam.setPadding(cp, cp, cp, cp);
        cam.setBackground(Ui.selectable(0));
        cam.setFocusable(false);
        cam.setOnClickListener(v -> {
            try {
                KeyguardManager km = getSystemService(KeyguardManager.class);
                boolean secure = km != null && km.isKeyguardLocked();
                startActivity(new Intent(secure ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });
        int cs = Math.round(0.12f * S);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(cs, cs, Gravity.END | Gravity.CENTER_VERTICAL);
        cl.rightMargin = Math.round(0.025f * S);
        bottom.addView(cam, cl);
        camera = cam;
        content.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.14f * S)));

        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView ampm, hint;
    private ImageView camera;
    private LinearLayout leftCol, rightCol;
    private LinearLayout events;

    private TextView lockText(float size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Ui.TEXT);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * Ui.S());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), 0xB3000000);
        return t;
    }

    private void showHint() {
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(1800).setDuration(400).start();
    }

    private void fitClock() {
        float S = Ui.S(), cs = 0.25f * S, as = 0.065f * S;
        int avail = rightCol.getWidth() - rightCol.getPaddingLeft() - rightCol.getPaddingRight();
        float f = 1f;
        if (avail > 0) {
            android.text.TextPaint cp = new android.text.TextPaint(clock.getPaint());
            cp.setTextSize(cs);
            float need = cp.measureText(clock.getText().toString()) + Ui.dp(6);
            if (ampm.getVisibility() == View.VISIBLE && ampm.length() > 0) {
                android.text.TextPaint ap = new android.text.TextPaint(ampm.getPaint());
                ap.setTextSize(as);
                need += ap.measureText(ampm.getText().toString()) + ((LinearLayout.LayoutParams) ampm.getLayoutParams()).leftMargin;
            }
            if (need > avail) f = avail / need;
        }
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cs * f);
        ampm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, as * f);
    }

    private void update() {
        if (clock == null) return;
        LauncherApp app = LauncherApp.get();
        boolean h24 = app.prefs.bool(com.bb10.launcher.Prefs.CLOCK_24, false) || DateFormat.is24HourFormat(this);
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        ampm.setText(h24 ? "" : DateFormat.format("a", now).toString().toUpperCase(java.util.Locale.ROOT));
        ampm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        date.setText(DateFormat.format("EEE, MMM d", now));
        fitClock();

        notes.removeAllViews();
        List<String> pkgs = app.hub.unreadGroups();
        float S = Ui.S();
        for (int i = 0; i < pkgs.size() && i < 5; i++) {
            String pkg = pkgs.get(i);
            ImageView iv = new ImageView(this);
            Drawable d = null;
            if (pkg.equals(com.bb10.launcher.hub.HubKinds.NOTIF)) d = Ui.glyph(this, R.drawable.ic_bell, Ui.TEXT);
            else if (pkg.equals(com.bb10.launcher.hub.HubKinds.CALLS)) d = Ui.glyph(this, R.drawable.ic_phone, Ui.TEXT);
            Icon small = d != null ? null : app.hub.smallIcon(pkg);
            if (small != null) {
                try {
                    d = small.loadDrawable(this);
                    if (d != null) {
                        d = d.mutate();
                        d.setTint(Ui.TEXT);
                    }
                } catch (Exception ignored) {
                }
            }
            if (d == null) d = app.iconForPackage(pkg);
            iv.setImageDrawable(d);
            int is = Math.round(0.065f * S);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(is, is);
            il.gravity = Gravity.CENTER_HORIZONTAL;
            il.topMargin = i == 0 ? 0 : Math.round(0.02f * S);
            notes.addView(iv, il);
            TextView n = lockText(0.042f, false);
            n.setTypeface(Ui.light());
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setText(String.valueOf(app.hub.unread(pkg)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.gravity = Gravity.CENTER_HORIZONTAL;
            tl.topMargin = Math.round(0.006f * S);
            notes.addView(n, tl);
        }

        boolean any = notes.getChildCount() > 0;
        leftCol.setVisibility(any ? View.VISIBLE : View.GONE);
        rightCol.setPadding(Math.round((any ? 0.02f : 0.06f) * S), 0, Math.round(0.04f * S), 0);

        updateEvents(h24, now);
    }

    private void updateEvents(boolean h24, long now) {
        events.removeAllViews();
        com.bb10.launcher.frames.LiveFrames live = LauncherApp.get().live;
        if (!live.canReadCalendar()) return;
        java.util.List<com.bb10.launcher.frames.LiveFrames.Event> list = new java.util.ArrayList<>();
        for (com.bb10.launcher.frames.LiveFrames.Event e : live.upcoming())
            if (e.end > now && e.begin < now + 24 * 3600_000L) list.add(e);
        if (list.isEmpty()) return;
        com.bb10.launcher.frames.LiveFrames.Event e = list.get(0);
        String head;
        if (e.begin <= now) head = "NOW";
        else if (e.begin - now < 3600_000L) head = "IN " + Math.max(1, (e.begin - now) / 60_000L) + " MIN";
        else if (android.text.format.DateUtils.isToday(e.begin)) head = "TODAY";
        else head = "TOMORROW";
        events.addView(eventLine(head, 0.03f, true));
        events.addView(eventLine(e.title, 0.04f, false));
        long len = Math.max(0, e.end - e.begin);
        String dur;
        if (e.allDay) {
            long days = Math.max(1, Math.round(len / 86_400_000.0));
            dur = days + (days == 1 ? " day" : " days");
        } else {
            long mins = len / 60_000L;
            dur = mins >= 60 ? (mins / 60) + " hr" + (mins % 60 > 0 ? " " + (mins % 60) + " min" : "") : mins + " min";
        }
        String start = DateFormat.format(h24 ? "HH:mm" : "h:mm a", e.allDay ? startOfDay(e.begin) : e.begin).toString();
        events.addView(eventLine(start + " (" + dur + ")", 0.035f, false));
        if (list.size() > 1) events.addView(eventLine("+" + (list.size() - 1) + " more", 0.035f, false));
    }

    private static long startOfDay(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        return c.getTimeInMillis();
    }

    private TextView eventLine(String s, float size, boolean bold) {
        TextView t = lockText(size, bold);
        t.setText(s);
        t.setPadding(0, Math.round(0.006f * Ui.S()), 0, 0);
        return t;
    }

    private static final int NONE = 0, UP = 1;
    private int mode = NONE;
    private float downX;
    private android.view.VelocityTracker vt;

    private final class Root extends FrameLayout {
        private final int slop = android.view.ViewConfiguration.get(LockActivity.this).getScaledTouchSlop();

        Root() {
            super(LockActivity.this);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipe(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    return Math.abs(dx) > slop || Math.abs(dy) > slop;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return swipe(e);
        }

        private float dissolve;
        private final android.graphics.Paint eraser = new android.graphics.Paint();

        {
            eraser.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        }

        void setDissolve(float f) {
            if (dissolve <= 0f && f > 0f) {
                KeyguardManager km = getSystemService(KeyguardManager.class);
                keepWall = km != null && km.isKeyguardLocked();
            }
            dissolve = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        private android.graphics.LinearGradient fade;
        private final android.graphics.Matrix fadeMatrix = new android.graphics.Matrix();

        float dissolve() {
            return dissolve;
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas c) {
            if (dissolve <= 0f) {
                super.dispatchDraw(c);
                return;
            }
            float w = getWidth(), h = getHeight(), band = h * 0.45f;
            float front = h - dissolve * (h + band);
            boolean keep = keepWall && wallImage.getDrawable() != null;
            long time = getDrawingTime();
            if (keep) drawChild(c, wallImage, time);
            int save = c.saveLayer(0, 0, w, h, null);
            if (keep) {
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (v != wallImage && v.getVisibility() == VISIBLE) drawChild(c, v, time);
                }
            } else {
                super.dispatchDraw(c);
            }
            if (fade == null) {
                fade = new android.graphics.LinearGradient(0, 0, 0, 1, 0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP);
                eraser.setShader(fade);
            }
            fadeMatrix.setScale(1f, band);
            fadeMatrix.postTranslate(0, front - band);
            fade.setLocalMatrix(fadeMatrix);
            c.drawRect(0, Math.max(0, front - band), w, h, eraser);
            c.restoreToCount(save);
        }
    }

    private boolean swipe(MotionEvent e) {
        float H = Math.max(1, root.getHeight()), W = Math.max(1, root.getWidth());
        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                mode = NONE;
                if (vt != null) vt.recycle();
                vt = android.view.VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (mode == NONE) {
                    if (dy < -slop && Math.abs(dy) > Math.abs(dx)) mode = UP;
                }
                if (mode == UP) {
                    ((Root) root).setDissolve(Math.max(0, -dy) / (H * 0.75f));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY, vx = 0, vy = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (mode == UP) {
                    if (up && (-dy > H * 0.1f || vy < -800)) unlock();
                    else animateDissolve(0f, 180, null);
                }
                if (mode == NONE && up) showHint();
                mode = NONE;
                return true;
            }
        }
        return true;
    }

    private void unlock() {
        if (unlocking) return;
        unlocking = true;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean keyguard = km != null && km.isKeyguardLocked();
        com.bb10.launcher.quickstep.ShellTransitions.note("lock: unlocking (Android keyguard " + (keyguard ? "up" : "not up") + ")");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finishQuietly, keyguard ? 1500 : 450);
        animateDissolve(1f, 260, () -> {
            if (!keyguard) finishQuietly();
        });
        if (!keyguard) return;
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                if (dissolveAnim != null) dissolveAnim.cancel();
                finishQuietly();
            }

            @Override
            public void onDismissCancelled() {
                reset();
            }

            @Override
            public void onDismissError() {
                reset();
            }
        });
    }

    private android.animation.ValueAnimator dissolveAnim;

    private void animateDissolve(float to, long ms, Runnable end) {
        if (dissolveAnim != null) dissolveAnim.cancel();
        Root r = (Root) root;
        dissolveAnim = android.animation.ValueAnimator.ofFloat(r.dissolve(), to);
        dissolveAnim.setDuration(ms);
        dissolveAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        dissolveAnim.addUpdateListener(a -> r.setDissolve((float) a.getAnimatedValue()));
        if (end != null) dissolveAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) end.run();
            }
        });
        dissolveAnim.start();
    }

    private static android.graphics.Bitmap wall;
    private ImageView wallImage;
    private boolean keepWall;

    private void loadWallpaper() {
        if (wall != null) useWall(wall);
        LauncherApp.get().bg.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
                Drawable d = null;
                if (android.os.Build.VERSION.SDK_INT >= 34) d = wm.getDrawable(android.app.WallpaperManager.FLAG_LOCK);
                if (d == null) d = wm.getDrawable();
                if (d instanceof android.graphics.drawable.BitmapDrawable) b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            } catch (Throwable ignored) {
            }
            if (b == null) {
                java.io.File out = new java.io.File(getCacheDir(), "lockwall");
                String o = out.getAbsolutePath();
                String dir = getCacheDir().getAbsolutePath();
                com.bb10.launcher.RootShell.Result r = com.bb10.launcher.RootShell.exec(4000,
                        "f=/data/system/users/0/wallpaper_lock; [ -s $f ] || f=/data/system/users/0/wallpaper; cp $f '" + o + "' && chmod 644 '" + o + "'",
                        "chown $(stat -c %u:%g '" + dir + "') '" + o + "' 2>/dev/null; chcon $(stat -c %C '" + dir + "') '" + o + "' 2>/dev/null; true");
                if (r.ok && out.exists()) b = android.graphics.BitmapFactory.decodeFile(o);
            }
            android.graphics.Bitmap fb = b;
            if (fb != null) runOnUiThread(() -> {
                wall = fb;
                if (!isDestroyed()) useWall(fb);
            });
        });
    }

    private void useWall(android.graphics.Bitmap b) {
        wallImage.setImageBitmap(b);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
    }

    @SuppressWarnings("deprecation")
    private void finishQuietly() {
        if (isFinishing() || isDestroyed()) return;
        com.bb10.launcher.quickstep.ShellTransitions.note("lock: closed");
        finish();
        overridePendingTransition(0, 0);
    }

    private void tellSystemUi(boolean up) {
        try {
            sendBroadcast(new Intent("com.bb10.launcher.LOCK").setPackage("com.android.systemui").putExtra("showing", up));
        } catch (Exception ignored) {
        }
    }

    private void reset() {
        unlocking = false;
        animateDissolve(0f, 180, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) unlock();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private float scrolled;
    private final Runnable scrollReset = () -> {
        scrolled = 0;
        if (!unlocking) animateDissolve(0f, 160, null);
    };

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        float d = 0;
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            d = Math.abs(e.getAxisValue(MotionEvent.AXIS_VSCROLL));
            if (d == 0) d = Math.abs(e.getAxisValue(MotionEvent.AXIS_SCROLL));
        } else if (e.isFromSource(android.view.InputDevice.SOURCE_TRACKBALL) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            d = Math.abs(e.getY()) / 3f;
        }
        if (d <= 0 || unlocking) return super.dispatchGenericMotionEvent(e);
        scrolled += d;
        float H = Math.max(1, root.getHeight());
        ((Root) root).setDissolve(Math.min(1f, scrolled / 2f) * 0.35f);
        root.removeCallbacks(scrollReset);
        if (scrolled >= 2f) unlock();
        else root.postDelayed(scrollReset, 400);
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return super.onKeyDown(code, e);
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
                unlock();
                return true;
            default:
                return true;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camera != null) camera.setVisibility(LauncherApp.get().prefs.bool(com.bb10.launcher.Prefs.LOCK_CAMERA, true) ? View.VISIBLE : View.GONE);
        unlocking = false;
        scrolled = 0;
        content.setTranslationY(0);
        content.setTranslationX(0);
        content.setAlpha(1f);
        scrim.setAlpha(1f);
        ((Root) root).setDissolve(0f);
        showing = true;
        GestureService.refreshEdge();
        tellSystemUi(true);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(rx, f);
        LauncherApp.get().hub.addListener(hubChanged);
        LauncherApp.get().live.addListener(musicChanged);
        LauncherApp.get().live.start();
        updateMusic();
        update();
    }

    @Override
    protected void onPause() {
        showing = false;
        GestureService.refreshEdge();
        tellSystemUi(false);
        try {
            unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        LauncherApp.get().hub.removeListener(hubChanged);
        LauncherApp.get().live.removeListener(musicChanged);
        LauncherApp.get().live.stop();
        super.onPause();
    }
}
