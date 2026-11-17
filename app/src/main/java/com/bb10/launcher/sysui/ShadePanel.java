package com.bb10.launcher.sysui;

import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ShadePanel extends FrameLayout {
    private static final int COLS = 2;
    private static final int TYPE_SUB_PANEL = 2017;
    private static final int PANEL_BG = 0xFF0E0E0E;
    private static final int TILE_BG = 0xFF262626, TILE_ICON_BG = 0xFF303030;
    private static ShadePanel sInstance;

    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final View dim;
    private final LinearLayout panel, grid, ongoing;
    private final ScrollView gridScroll, ongoingScroll;
    private final View ongoingDivider;
    private final SeekBar brightness;
    private View brightnessRow;
    private final StatusBarView bar;
    private final int slop, statusH;
    private final List<TileView> tileViews = new ArrayList<>();
    private final Map<String, Drawable> bbIcons = new HashMap<>();
    private final ImageView sun;
    private final List<MediaRow> mediaRows = new ArrayList<>();

    private boolean added, open, closing;
    private int panelH;
    private float expansion;
    private ValueAnimator anim;
    private long openedAt;

    private float downX, downY;
    private boolean sbDragging, tracking, outsideDown;
    private View downScroller;
    private VelocityTracker vt;

    private final Runnable refresh = this::refreshTiles;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshTiles();
            for (MediaRow m : mediaRows) m.updatePosition();
            if (added) SysUi.main.postDelayed(this, 1000);
        }
    };
    private final Runnable notifsChanged = this::buildOngoing;

    static ShadePanel get() {
        if (sInstance == null) sInstance = new ShadePanel(windowContext());
        return sInstance;
    }

    private static Context windowContext() {
        Context base = SysUi.sys;
        try {
            Display d = base.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            base = base.createWindowContext(d, TYPE_SUB_PANEL, null);
        } catch (Throwable t) {
            SysUi.note("window context: " + t);
        }
        return SysUi.wrap(base);
    }

    static void toggle() {
        if (SysUi.sys == null || SysUi.res == null) return;
        ShadePanel p = get();
        if (p.open && !p.closing) p.close(true);
        else p.openAnimated();
    }

    static void expand() {
        if (SysUi.sys == null || SysUi.res == null) return;
        ShadePanel p = get();
        if (!p.open || p.closing) p.openAnimated();
    }

    static void closeIfOpen(boolean fromCollapse) {
        ShadePanel p = sInstance;
        if (p == null || !p.added) return;
        if (fromCollapse && System.currentTimeMillis() - p.openedAt < 600) return;
        p.close(true);
    }

    static boolean isOpen() {
        return sInstance != null && sInstance.added;
    }

    private ShadePanel(Context c) {
        super(c);
        wm = c.getSystemService(WindowManager.class);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        float S = Ui.S();
        int sbId = Resourcesystem("status_bar_height");
        statusH = Math.max(sbId > 0 ? SysUi.sys.getResources().getDimensionPixelSize(sbId) : 0, Math.round(Ui.s(StatusBarView.HEIGHT)));

        lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.setTitle("BB10QuickSettings");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;

        dim = new View(c);
        dim.setBackgroundColor(0x99000000);
        dim.setAlpha(0f);
        addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL_BG);
        panel.setElevation(Ui.dp(8));
        panel.setClickable(true);

        bar = new StatusBarView(c);
        bar.passTouches = true;
        bar.solidBg = 0xFF000000;
        panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusH));

        date = text(c, "", 0.033f, Ui.TEXT, false);
        int dp = Math.round(0.035f * S);
        date.setPadding(dp, Math.round(0.008f * S), dp, Math.round(0.008f * S));
        panel.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(divider(c));

        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        gridScroll = new MaxScroll(c, Math.round((tileH() + tileGap()) * 4 + tileGap()));
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        gridScroll.addView(grid);
        panel.addView(gridScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(divider(c));

        LinearLayout br = new LinearLayout(c);
        br.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(0.035f * S);
        br.setPadding(pad, 0, pad, 0);
        sun = new ImageView(c);
        sun.setImageDrawable(bb("displaybrightness", Brightness.auto()));
        int sp = Math.round(0.012f * S);
        sun.setPadding(sp, sp, sp, sp);
        sun.setBackground(Ui.selectable(0));
        sun.setOnClickListener(v -> {
            Brightness.setAuto(!Brightness.auto());
            SysUi.main.postDelayed(refresh, 100);
        });
        int is = Math.round(0.084f * S);
        br.setPadding(pad - sp, 0, pad, 0);
        br.addView(sun, new LinearLayout.LayoutParams(is, is));
        brightness = new SeekBar(c);
        brightness.setMax(1000);
        brightness.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
        brightness.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        brightness.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF555555));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) Brightness.set(Brightness.gammaToLinear(v / 1000f), false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Brightness.set(Brightness.gammaToLinear(s.getProgress() / 1000f), true);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = Math.round(0.02f * S);
        br.addView(brightness, blp);
        panel.addView(br, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.09f * S)));
        brightnessRow = br;

        ongoingDivider = divider(c);
        panel.addView(ongoingDivider);
        ongoing = new LinearLayout(c);
        ongoing.setOrientation(LinearLayout.VERTICAL);
        ongoingScroll = new MaxScroll(c, Math.round(0.17f * S));
        ongoingScroll.setVerticalScrollBarEnabled(false);
        ongoingScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        ongoingScroll.addView(ongoing);
        panel.addView(ongoingScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(divider(c));
        FrameLayout bottom = new FrameLayout(c);
        View handle = new View(c);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(0xFF6A6A6A);
        hd.setCornerRadius(Ui.dp(3));
        handle.setBackground(hd);
        bottom.addView(handle, new FrameLayout.LayoutParams(Math.round(0.09f * S), Math.max(3, Math.round(0.008f * S)), Gravity.CENTER));
        panel.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.045f * S)));

        addView(panel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setFocusableInTouchMode(true);
    }

    private static int Resourcesystem(String name) {
        return android.content.res.Resources.getSystem().getIdentifier(name, "dimen", "android");
    }

    private static float tileH() {
        return 0.1f * Ui.S();
    }

    private static float tileGap() {
        return 0.013f * Ui.S();
    }

    private View divider(Context c) {
        View d = new View(c);
        d.setBackgroundColor(0xFF2E2E2E);
        d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(1))));
        return d;
    }

    private static TextView text(Context c, CharSequence s, float sizeS, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeS * Ui.S());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private Drawable bb(String name, boolean active) {
        String key = name + (active ? "_active" : "");
        Drawable d = bbIcons.get(key);
        if (d != null) return d.getConstantState().newDrawable(SysUi.res).mutate();
        try (InputStream in = SysUi.res.getAssets().open("bb10/qs/" + key + ".png")) {
            Bitmap b = BitmapFactory.decodeStream(in);
            d = new BitmapDrawable(SysUi.res, b);
        } catch (Throwable e) {
            if (active) return bb(name, false);
            return null;
        }
        bbIcons.put(key, d);
        return d.getConstantState().newDrawable(SysUi.res).mutate();
    }

    private final class TileView extends LinearLayout {
        final String spec;
        final ImageView icon;
        final TextView label, sub;
        final View litBar;
        Boolean shownLit;

        TileView(Context c, String spec) {
            super(c);
            this.spec = spec;
            float S = Ui.S();
            setOrientation(HORIZONTAL);
            setForeground(Ui.selectable(0));

            FrameLayout box = new FrameLayout(c);
            box.setBackgroundColor(TILE_ICON_BG);
            icon = new ImageView(c);
            int is = Math.round(0.064f * S);
            box.addView(icon, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            litBar = new View(c);
            litBar.setBackgroundColor(Ui.BLUE);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, Math.round(0.0075f * S)), Gravity.BOTTOM);
            bl.leftMargin = bl.rightMargin = Math.round(0.006f * S);
            bl.bottomMargin = Math.round(0.004f * S);
            box.addView(litBar, bl);
            LayoutParams boxLp = new LayoutParams(Math.round(tileH()), ViewGroup.LayoutParams.MATCH_PARENT);
            boxLp.rightMargin = Math.max(1, Math.round(0.004f * S));
            addView(box, boxLp);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            texts.setBackgroundColor(TILE_BG);
            int tp = Math.round(0.024f * S);
            texts.setPadding(tp, 0, Math.round(0.012f * S), 0);
            label = text(c, BbTiles.label(spec), 0.029f, Ui.TEXT, true);
            texts.addView(label);
            sub = text(c, "", 0.025f, Ui.TEXT2, false);
            LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Math.round(0.008f * S);
            texts.addView(sub, sl);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            setOnClickListener(v -> {
                BbTiles.toggle(SysUi.sys, spec);
                if (BbTiles.opens(spec)) {
                    close(true);
                    return;
                }
                SysUi.main.postDelayed(refresh, 150);
                SysUi.main.postDelayed(refresh, 600);
                SysUi.main.postDelayed(refresh, 1500);
            });
            setOnLongClickListener(v -> {
                Intent i = BbTiles.settings(spec);
                if (i != null) startSettings(i);
                return true;
            });
        }

        void update() {
            apply(BbTiles.state(SysUi.sys, spec), BbTiles.secondary(SysUi.sys, spec));
        }

        void apply(int st, String s) {
            boolean lit = st == BbTiles.ACTIVE;
            if (shownLit == null || shownLit != lit) {
                icon.setImageDrawable(bb(BbTiles.icon(spec), lit));
                shownLit = lit;
            }
            litBar.setVisibility(lit ? VISIBLE : INVISIBLE);
            sub.setText(s == null ? "" : s);
            sub.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
            setAlpha(st == BbTiles.UNAVAILABLE ? 0.35f : 1f);
        }
    }

    private void buildTiles() {
        tileViews.clear();
        grid.removeAllViews();
        List<String> tiles = BbTiles.parse(Settings.Global.getString(SysUi.sys.getContentResolver(), SysUi.K_TILES));
        int gap = Math.round(tileGap());
        grid.setPadding(gap, 0, gap, gap);
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(getContext());
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(tileH()));
                rl.topMargin = gap;
                grid.addView(row, rl);
            }
            TileView tv = new TileView(getContext(), tiles.get(i));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % COLS != 0) tl.leftMargin = gap;
            row.addView(tv, tl);
            tileViews.add(tv);
        }
        if (row != null) {
            for (int k = row.getChildCount(); k < COLS; k++) {
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, 1, 1f);
                pl.leftMargin = gap;
                row.addView(new View(getContext()), pl);
            }
        }
        refreshTiles();
    }
    private TextView date;

    private static android.os.Handler tileIo;
    private boolean tilesPending;

    private void refreshTiles() {
        date.setText(android.text.format.DateFormat.format("EEEE, MMMM d", System.currentTimeMillis()));
        if (tileIo == null) {
            android.os.HandlerThread ht = new android.os.HandlerThread("bb10-qs-tiles");
            ht.start();
            tileIo = new android.os.Handler(ht.getLooper());
        }
        if (!tilesPending) {
            tilesPending = true;
            List<TileView> views = new ArrayList<>(tileViews);
            tileIo.post(() -> {
                int[] states = new int[views.size()];
                String[] subs = new String[views.size()];
                for (int i = 0; i < views.size(); i++) {
                    states[i] = BbTiles.state(SysUi.sys, views.get(i).spec);
                    subs[i] = BbTiles.secondary(SysUi.sys, views.get(i).spec);
                }
                SysUi.main.post(() -> {
                    tilesPending = false;
                    for (int i = 0; i < views.size(); i++) views.get(i).apply(states[i], subs[i]);
                });
            });
        }
        brightnessRow.setVisibility(SysUi.brightnessSlider ? VISIBLE : GONE);
        if (!brightness.isPressed()) brightness.setProgress(Math.round(Brightness.linearToGamma(Brightness.get()) * 1000));
        sun.setImageDrawable(bb("displaybrightness", Brightness.auto()));
    }

    private void stopListening() {
        for (MediaRow m : mediaRows) m.release();
        mediaRows.clear();
    }

    private static boolean playing(Context c, MediaSession.Token token) {
        try {
            PlaybackState s = new MediaController(c, token).getPlaybackState();
            return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        } catch (Throwable t) {
            return false;
        }
    }

    private void buildOngoing() {
        for (MediaRow m : mediaRows) m.release();
        mediaRows.clear();
        ongoing.removeAllViews();
        List<StatusBarNotification> list = SysUi.ongoingList();
        Context c = getContext();
        float S = Ui.S();
        int pad = Math.round(0.035f * S);
        java.util.Set<String> mediaPkgs = new java.util.HashSet<>();
        for (StatusBarNotification sbn : list) {
            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            View row = null;
            MediaSession.Token token = ex.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            if (token != null) {
                if (!mediaPkgs.add(sbn.getPackageName())) continue;
                if (SysUi.closedMedia(sbn.getPackageName()) && SysUi.hiddenPlayer(sbn.getPackageName(), playing(c, token))) continue;
                try {
                    MediaRow m = new MediaRow(c, sbn, token);
                    mediaRows.add(m);
                    row = m;
                } catch (Throwable e) {
                    SysUi.note("music controls: " + e);
                }
            }
            if (row == null) row = plainRow(c, sbn);
            if (ongoing.getChildCount() > 0) {
                View d = new View(c);
                d.setBackgroundColor(0xFF262626);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dl.leftMargin = pad;
                ongoing.addView(d, dl);
            }
            ongoing.addView(row);
        }
        boolean any = ongoing.getChildCount() > 0;
        ongoingScroll.setVisibility(any ? VISIBLE : GONE);
        ongoingDivider.setVisibility(any ? VISIBLE : GONE);
        if (added) remeasure();
    }

    private View plainRow(Context c, StatusBarNotification sbn) {
        float S = Ui.S();
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(0.035f * S);
        r.setPadding(pad, Math.round(0.018f * S), Math.round(0.02f * S), Math.round(0.018f * S));
        r.setBackground(Ui.selectable(0));
        ImageView ic = new ImageView(c);
        ic.setImageDrawable(tinted(n.getSmallIcon(), Ui.TEXT));
        int is = Math.round(0.045f * S);
        r.addView(ic, new LinearLayout.LayoutParams(is, is));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) title = appName(sbn.getPackageName());
        CharSequence body = ex.getCharSequence(Notification.EXTRA_TEXT);
        texts.addView(text(c, title, 0.03f, Ui.TEXT, true));
        if (!TextUtils.isEmpty(body)) {
            TextView b = text(c, body, 0.026f, Ui.TEXT2, false);
            b.setPadding(0, Math.round(0.006f * S), 0, 0);
            texts.addView(b);
        }
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if (max > 0 || indet) {
            ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            pb.setIndeterminate(indet);
            pb.setMax(Math.max(1, max));
            pb.setProgress(ex.getInt(Notification.EXTRA_PROGRESS, 0));
            pb.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
            pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.BLUE));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.012f * S));
            plp.topMargin = Math.round(0.008f * S);
            texts.addView(pb, plp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Math.round(0.03f * S);
        r.addView(texts, tlp);

        if (n.actions != null) {
            int shown = 0;
            for (Notification.Action a : n.actions) {
                if (shown >= 2 || a.actionIntent == null || TextUtils.isEmpty(a.title)) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                TextView tv = text(c, a.title.toString().toUpperCase(), 0.024f, Ui.BLUE, true);
                int p = Math.round(0.015f * S);
                tv.setPadding(p, p, p, p);
                tv.setBackground(Ui.selectable(0));
                final PendingIntent pi = a.actionIntent;
                tv.setOnClickListener(v -> {
                    send(pi);
                    close(true);
                });
                r.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                shown++;
            }
        }
        final PendingIntent content = n.contentIntent;
        final String pkg = sbn.getPackageName(), key = sbn.getKey();
        r.setOnClickListener(v -> {
            if (!KeyguardView.openThroughFrame(pkg, content, key) && content != null) send(content);
            close(true);
        });
        return r;
    }

    private static Drawable tinted(Icon icon, int color) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(SysUi.sys);
            if (d == null) return null;
            d = d.mutate();
            d.setTint(color);
            return d;
        } catch (Throwable e) {
            return null;
        }
    }

    private final class MediaRow extends LinearLayout {
        final MediaController mc;
        final ImageView art, playPause;
        final TextView title, artist;
        final ProgressBar pos;
        final MediaController.Callback cb = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState s) {
                updateState();
            }

            @Override
            public void onMetadataChanged(MediaMetadata m) {
                updateMeta();
            }
        };

        MediaRow(Context c, StatusBarNotification sbn, MediaSession.Token token) {
            super(c);
            mc = new MediaController(SysUi.sys, token);
            float S = Ui.S();
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = Math.round(0.03f * S);
            setPadding(pad, Math.round(0.02f * S), Math.round(0.015f * S), Math.round(0.02f * S));
            setBackground(Ui.selectable(0));

            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setClipToOutline(true);
            GradientDrawable artBg = new GradientDrawable();
            artBg.setColor(0xFF2A2A2A);
            artBg.setCornerRadius(Ui.dp(3));
            art.setBackground(artBg);
            int as = Math.round(0.11f * S);
            addView(art, new LayoutParams(as, as));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = text(c, "", 0.03f, Ui.TEXT, true);
            artist = text(c, "", 0.026f, Ui.TEXT2, false);
            artist.setPadding(0, Math.round(0.006f * S), 0, 0);
            texts.addView(title);
            texts.addView(artist);
            pos = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            pos.setMax(1000);
            pos.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
            pos.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF444444));
            LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, Math.round(0.006f * S)));
            plp.topMargin = Math.round(0.012f * S);
            texts.addView(pos, plp);
            LayoutParams tlp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Math.round(0.03f * S);
            tlp.rightMargin = Math.round(0.01f * S);
            addView(texts, tlp);

            int bs = Math.round(0.085f * S), bp = Math.round(0.02f * S);
            ImageView prev = button(c, com.bb10.launcher.R.drawable.ic_m_prev, bp);
            prev.setOnClickListener(v -> mc.getTransportControls().skipToPrevious());
            addView(prev, new LayoutParams(bs, bs));
            playPause = button(c, com.bb10.launcher.R.drawable.ic_m_play, bp);
            playPause.setOnClickListener(v -> {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
                else mc.getTransportControls().play();
            });
            addView(playPause, new LayoutParams(bs, bs));
            ImageView next = button(c, com.bb10.launcher.R.drawable.ic_m_next, bp);
            next.setOnClickListener(v -> mc.getTransportControls().skipToNext());
            addView(next, new LayoutParams(bs, bs));

            final PendingIntent content = sbn.getNotification().contentIntent;
            final String pkg = sbn.getPackageName();
            setOnClickListener(v -> {
                PendingIntent pi = mc.getSessionActivity() != null ? mc.getSessionActivity() : content;
                if (!KeyguardView.openThroughFrame(pkg, pi, null) && pi != null) send(pi);
                close(true);
            });
            fallbackArt = sbn.getNotification().getLargeIcon();
            fallbackTitle = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            fallbackText = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
            appIcon = sbn.getNotification().getSmallIcon();
            mc.registerCallback(cb, SysUi.main);
            updateMeta();
            updateState();
        }

        private final Icon fallbackArt, appIcon;
        private final CharSequence fallbackTitle, fallbackText;

        private ImageView button(Context c, int res, int pad) {
            ImageView b = new ImageView(c);
            Drawable d = SysUi.res.getDrawable(res, null).mutate();
            d.setTint(Ui.TEXT);
            b.setImageDrawable(d);
            b.setPadding(pad, pad, pad, pad);
            b.setBackground(Ui.selectable(0));
            return b;
        }

        void updateMeta() {
            MediaMetadata m = mc.getMetadata();
            CharSequence t = m == null ? null : m.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = m == null ? null : m.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(a) && m != null) a = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            title.setText(!TextUtils.isEmpty(t) ? t : fallbackTitle);
            artist.setText(!TextUtils.isEmpty(a) ? a : fallbackText);
            Bitmap bmp = null;
            if (m != null) {
                bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            if (bmp != null) {
                art.setPadding(0, 0, 0, 0);
                art.setImageBitmap(bmp);
            } else {
                Drawable d = null;
                try {
                    d = fallbackArt == null ? null : fallbackArt.loadDrawable(SysUi.sys);
                } catch (Throwable ignored) {
                }
                if (d == null) {
                    d = tinted(appIcon, Ui.TEXT);
                    int p = Math.round(0.025f * Ui.S());
                    art.setPadding(p, p, p, p);
                } else {
                    art.setPadding(0, 0, 0, 0);
                }
                art.setImageDrawable(d);
            }
            updatePosition();
        }

        void updateState() {
            PlaybackState s = mc.getPlaybackState();
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            Drawable d = SysUi.res.getDrawable(playing ? com.bb10.launcher.R.drawable.ic_m_pause : com.bb10.launcher.R.drawable.ic_m_play, null).mutate();
            d.setTint(Ui.TEXT);
            playPause.setImageDrawable(d);
            updatePosition();
        }

        void updatePosition() {
            MediaMetadata m = mc.getMetadata();
            PlaybackState s = mc.getPlaybackState();
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur <= 0 || s == null) {
                pos.setVisibility(INVISIBLE);
                return;
            }
            long p = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING)
                p += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            pos.setVisibility(VISIBLE);
            pos.setProgress((int) Math.max(0, Math.min(1000, p * 1000 / dur)));
        }

        void release() {
            try {
                mc.unregisterCallback(cb);
            } catch (Throwable ignored) {
            }
        }
    }
    private static CharSequence appName(String pkg) {
        try {
            PackageManager pm = SysUi.sys.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Throwable e) {
            return pkg;
        }
    }

    private void send(PendingIntent pi) {
        if (SysUi.securelyLocked()) {
            close(false);
            return;
        }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34)
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(SysUi.sys, 0, null, null, null, null, o.toBundle());
        } catch (Throwable e) {
            SysUi.note("notification tap failed: " + e);
        }
    }

    private void startSettings(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            SysUi.sys.startActivity(i);
        } catch (Throwable e) {
            SysUi.note("can't open settings: " + e);
        }
        close(true);
    }

    private boolean ensureAdded() {
        if (added) return true;
        if (SysUi.securelyLocked()) return false;
        try {
            buildTiles();
            buildOngoing();
            wm.addView(this, lp);
        } catch (Throwable e) {
            SysUi.note("can't show Quick Settings: " + e);
            stopListening();
            return false;
        }
        added = true;
        closing = false;
        openedAt = System.currentTimeMillis();
        SysUi.addListener(notifsChanged);
        SysUi.main.postDelayed(ticker, 1000);
        bar.start();
        panel.setTranslationX(0);
        remeasure();
        setExpansion(0);
        requestFocus();
        return true;
    }

    private void remeasure() {
        int w = getResources().getDisplayMetrics().widthPixels;
        try {
            w = wm.getCurrentWindowMetrics().getBounds().width();
        } catch (Throwable ignored) {
        }
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int old = panelH;
        panelH = Math.max(1, panel.getMeasuredHeight());
        if (open && !closing && anim == null && old != panelH) setExpansion(panelH);
    }

    private void setExpansion(float px) {
        expansion = Math.max(0, Math.min(panelH, px));
        panel.setTranslationY(expansion - panelH);
        dim.setAlpha(expansion / panelH);
    }

    private void animateTo(float target, float velocity, Runnable end) {
        if (anim != null) anim.cancel();
        float dist = Math.abs(target - expansion);
        long dur = (long) Math.max(120, Math.min(300, dist / Math.max(1f, Math.abs(velocity) / 1000f + panelH / 280f)));
        anim = ValueAnimator.ofFloat(expansion, target);
        anim.setDuration(dur);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> setExpansion((float) a.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                anim = null;
                if (!cancelled && end != null) end.run();
            }
        });
        anim.start();
    }

    private void openAnimated() {
        if (!ensureAdded()) return;
        open = true;
        closing = false;
        animateTo(panelH, 0, null);
    }

    void close(boolean animate) {
        if (!added) return;
        open = false;
        closing = true;
        if (animate && panelH > 0 && expansion > 0) animateTo(0, 0, this::removeNow);
        else removeNow();
    }

    private void removeNow() {
        if (anim != null) anim.cancel();
        anim = null;
        if (!added) return;
        added = false;
        open = false;
        closing = false;
        stopListening();
        SysUi.removeListener(notifsChanged);
        SysUi.main.removeCallbacks(ticker);
        bar.stop();
        try {
            wm.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
    }

    void statusBarTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                sbDragging = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY;
                if (!sbDragging && dy > slop) {
                    sbDragging = true;
                    if (!ensureAdded()) return;
                    open = true;
                    downY += slop;
                    dy -= slop;
                }
                if (sbDragging) setExpansion(dy);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000);
                }
                float vy = vt == null ? 0 : vt.getYVelocity();
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                if (!sbDragging) break;
                sbDragging = false;
                settle(vy);
                break;
            }
        }
    }

    private void settle(float vy) {
        boolean openIt = vy > 600 || (vy > -600 && expansion > panelH * 0.35f);
        if (openIt) {
            open = true;
            animateTo(panelH, vy, null);
        } else {
            close(true);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                tracking = false;
                hTracking = false;
                outsideDown = e.getY() > panel.getTranslationY() + panel.getHeight();
                downScroller = scrollerAt(e.getX(), e.getY());
                downOnSlider = hit(brightness, e.getX(), e.getY());
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (anim != null) anim.cancel();
                return outsideDown;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                if (downOnSlider) return false;
                if (dy < -slop && Math.abs(dy) > Math.abs(dx)
                        && (downScroller == null || !downScroller.canScrollVertically(1))) {
                    tracking = true;
                    downY = e.getRawY();
                    return true;
                }
                if (dx > slop && dx > Math.abs(dy) * 1.2f) {
                    hTracking = true;
                    downX = e.getRawX() - slop;
                    bridge("begin", 0);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                outsideDown = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                if (!tracking && !hTracking) {
                    if (dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                        tracking = true;
                        downY = e.getRawY();
                        dy = 0;
                    } else if (dx > slop && dx > Math.abs(dy) * 1.2f) {
                        hTracking = true;
                        downX = e.getRawX() - slop;
                        dx = slop;
                        bridge("begin", 0);
                    }
                }
                if (tracking) setExpansion(panelH + dy);
                if (hTracking) {
                    float x = Math.max(0, dx);
                    panel.setTranslationX(x);
                    dim.setAlpha(Math.max(0f, 1f - x / Math.max(1, getWidth())));
                    bridge("move", x);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0, vx = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vy = vt.getYVelocity();
                    vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (hTracking) {
                    hTracking = false;
                    float x = panel.getTranslationX();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && (x > getWidth() * 0.3f || vx > 800)) {
                        openHub();
                    } else {
                        bridge("cancel", 0);
                        panel.animate().translationX(0).setDuration(160).start();
                        dim.animate().alpha(1f).setDuration(160).start();
                    }
                } else if (tracking) {
                    tracking = false;
                    settle(vy);
                } else if (outsideDown && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    close(true);
                } else if (open) {
                    animateTo(panelH, 0, null);
                }
                outsideDown = false;
                return true;
            }
        }
        return true;
    }

    private boolean hTracking, downOnSlider;

    private boolean hit(View v, float x, float y) {
        if (v.getVisibility() != VISIBLE || !v.isAttachedToWindow()) return false;
        int[] loc = new int[2], me = new int[2];
        v.getLocationInWindow(loc);
        getLocationInWindow(me);
        float vx = x + me[0] - loc[0], vy = y + me[1] - loc[1];
        return vx >= 0 && vy >= 0 && vx < v.getWidth() && vy < v.getHeight();
    }

    private static final long FOLLOW_MS = 200;

    private void openHub() {
        if (!hTracking && panel.getTranslationX() == 0) bridge("begin", 0);
        float x0 = panel.getTranslationX();
        long t0 = android.os.SystemClock.uptimeMillis();
        commitT = t0;
        bridge("commit", x0);
        closing = true;
        float d0 = dim.getAlpha();
        ValueAnimator va = ValueAnimator.ofFloat(x0, getWidth());
        va.setDuration(FOLLOW_MS);
        va.setInterpolator(new DecelerateInterpolator(1.5f));
        va.addUpdateListener(a -> {
            panel.setTranslationX((float) a.getAnimatedValue());
            dim.setAlpha(d0 * (1f - a.getAnimatedFraction()));
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                removeNow();
            }
        });
        va.start();
    }

    private static android.os.Handler bridgeH;
    private android.content.ContentProviderClient bridgeClient;
    private volatile boolean hubOk;
    private volatile long commitT;
    private volatile String hubReason;

    private void bridge(String op, float x) {
        if (bridgeH == null) {
            android.os.HandlerThread ht = new android.os.HandlerThread("bb10-hub-bridge");
            ht.start();
            bridgeH = new android.os.Handler(ht.getLooper());
        }
        if (op.equals("move")) bridgeH.removeCallbacksAndMessages(MOVE_TOKEN);
        Runnable r = () -> {
            try {
                if (bridgeClient == null)
                    bridgeClient = SysUi.sys.getContentResolver().acquireUnstableContentProviderClient("com.bb10.launcher.hubbridge");
                Bundle ex = new Bundle();
                ex.putFloat("x", x);
                ex.putLong("t", commitT);
                Bundle res = bridgeClient == null ? null : bridgeClient.call(op, null, ex);
                if (op.equals("begin")) {
                    hubOk = res != null && res.getBoolean("ok");
                    hubReason = res == null ? "service" : res.getString("reason");
                }
            } catch (Throwable e) {
                if (op.equals("begin")) {
                    hubOk = false;
                    hubReason = "service";
                }
                if (bridgeClient != null) bridgeClient.close();
                bridgeClient = null;
            }
            if (op.equals("commit") && !hubOk && "service".equals(hubReason)) {
                SysUi.main.post(() -> {
                    try {
                        SysUi.sys.startActivity(new Intent().setClassName(SysUi.PKG, SysUi.PKG + ".HubShortcutActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable ignored) {
                    }
                });
            }
        };
        if (op.equals("move")) bridgeH.postAtTime(r, MOVE_TOKEN, android.os.SystemClock.uptimeMillis());
        else bridgeH.post(r);
    }

    private static final Object MOVE_TOKEN = new Object();
    private View scrollerAt(float x, float y) {
        for (ScrollView s : new ScrollView[]{gridScroll, ongoingScroll}) {
            if (s.getVisibility() != VISIBLE) continue;
            int[] loc = new int[2], me = new int[2];
            s.getLocationInWindow(loc);
            getLocationInWindow(me);
            float sx = x + me[0] - loc[0], sy = y + me[1] - loc[1];
            if (sx >= 0 && sy >= 0 && sx < s.getWidth() && sy < s.getHeight()) return s;
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close(true);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
    }

    private static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    static final class Brightness {
        private static final float R = 0.5f, A = 0.17883277f, B = 0.28466892f, C = 0.55991073f;

        static float gammaToLinear(float g) {
            float ret = g <= R ? (g / R) * (g / R) : (float) Math.exp((g - C) / A) + B;
            return clamp(ret / 12f);
        }

        static float linearToGamma(float v) {
            float n = v * 12f;
            float ret = n <= 1f ? (float) Math.sqrt(n) * R : A * (float) Math.log(n - B) + C;
            return clamp(ret);
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        static boolean auto() {
            return Settings.System.getInt(SysUi.sys.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        }

        static void setAuto(boolean on) {
            try {
                Settings.System.putInt(SysUi.sys.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            } catch (Throwable e) {
                SysUi.note("auto brightness: " + e);
            }
        }

        static float get() {
            DisplayManager dm = SysUi.sys.getSystemService(DisplayManager.class);
            try {
                Method m = DisplayManager.class.getMethod("getBrightness", int.class);
                return (float) m.invoke(dm, Display.DEFAULT_DISPLAY);
            } catch (Throwable e) {
                return Settings.System.getInt(SysUi.sys.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
            }
        }

        static void set(float v, boolean persist) {
            DisplayManager dm = SysUi.sys.getSystemService(DisplayManager.class);
            try {
                Method m = DisplayManager.class.getMethod(persist ? "setBrightness" : "setTemporaryBrightness", int.class, float.class);
                m.invoke(dm, Display.DEFAULT_DISPLAY, v);
                return;
            } catch (Throwable ignored) {
            }
            if (persist) {
                try {
                    Settings.System.putInt(SysUi.sys.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.round(v * 255));
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
