package com.bb10.launcher.sysui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.R;
import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class KeyguardView extends FrameLayout {
    private final StatusBarView bar;
    private final View scrim;
    private final LinearLayout content, notes, leftCol, rightCol, events;
    private LinearLayout expandList;
    private ListScroll listScroll;
    private Bracket bracket;
    private String selected;
    private ImageView selectedIcon;
    private final TextView clock, ampm, date, hint;
    private final int slop;
    private LinearLayout clockRow;
    private final android.graphics.Rect ink = new android.graphics.Rect();
    private ImageView camera;
    private final FrameLayout stage;
    private final ImageView wall;

    final float builtScale = SysUi.lockScale, builtBarScale = StatusBarView.scale;

    static float S() {
        return Ui.S() * SysUi.lockScale;
    }

    private static int extrasH() {
        return Math.round(0.055f * Ui.S() * StatusBarView.scale);
    }

    KeyguardView(Context c) {
        super(c);
        float S = S();
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
        setWillNotDraw(false);

        stage = new FrameLayout(c);
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stage.addView(wall, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glow = new Glow(c);
        stage.addView(glow, new LayoutParams(1, 1));
        scrim = new View(c);
        scrim.setBackgroundColor(0x55000000);
        stage.addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(stage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        int statusH = statusHeight();
        content.setPadding(0, statusH + extrasH(), 0, 0);

        LinearLayout body = new LinearLayout(c);
        body.setClipChildren(false);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        leftCol = new LinearLayout(c);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER);
        notes = new LinearLayout(c);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        leftCol.addView(notes, nl);
        leftCol.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) post(() -> {
                if (fitCells()) notes.requestLayout();
            });
        });
        body.addView(leftCol, new LinearLayout.LayoutParams(Math.round(0.19f * S), ViewGroup.LayoutParams.MATCH_PARENT));
        bracket = new Bracket(c);
        bracket.setVisibility(GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.round(0.035f * S), ViewGroup.LayoutParams.MATCH_PARENT);
        bl.leftMargin = -bl.width;
        body.addView(bracket, bl);

        rightCol = new LinearLayout(c);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setPadding(Math.round(0.02f * S), 0, Math.round(0.04f * S), 0);
        rightCol.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) post(this::refitClock);
        });
        clockRow = new LinearLayout(c);
        clockRow.setBaselineAligned(true);
        clock = text(0.25f, false);
        clock.setTypeface(Ui.light());
        clockRow.addView(clock);
        ampm = text(0.065f, false);
        ampm.setTypeface(Ui.light());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = Math.round(0.015f * S);
        clockRow.addView(ampm, al);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crl.topMargin = Math.round(0.03f * S);
        rightCol.addView(clockRow, crl);
        date = text(0.05f, false);
        for (TextView t : new TextView[]{clock, ampm, date})
            t.setPaintFlags(t.getPaintFlags() | Paint.LINEAR_TEXT_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = Math.round(0.008f * S);
        rightCol.addView(date, dl);
        events = new LinearLayout(c);
        events.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = Math.round(0.045f * S);
        el.leftMargin = Math.round(0.008f * S);
        rightCol.addView(events, el);
        buildMusic(c);
        expandList = new LinearLayout(c);
        expandList.setOrientation(LinearLayout.VERTICAL);
        expandList.setVisibility(GONE);
        expandList.setClipChildren(false);
        rightCol.setClipChildren(false);
        listScroll = new ListScroll(c);
        listScroll.addView(expandList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        xl.topMargin = Math.round(0.04f * S);
        rightCol.addView(listScroll, xl);
        content.addOnLayoutChangeListener((v, l, tp, rt, b, ol, ot, or, ob) -> bracket.invalidate());
        LinearLayout.LayoutParams rcl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rcl.bottomMargin = -Math.round(extrasH() + 0.03f * S);
        body.addView(rightCol, rcl);
        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout bottom = new FrameLayout(c);
        hint = text(0.032f, false);
        hint.setText("Swipe up to unlock");
        hint.setAlpha(0f);
        bottom.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ImageView cam = new ImageView(c);
        camera = cam;
        cam.setImageDrawable(glyph(R.drawable.ic_camera));
        int cp = Math.round(0.034f * S);
        cam.setPadding(cp, cp, cp, cp);
        cam.setBackground(new android.graphics.drawable.ColorDrawable(0x22000000));
        cam.setForeground(Ui.selectable(0));
        cam.setOnClickListener(v -> openCamera());
        int cs = Math.round(0.147f * S);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(cs, cs, Gravity.END | Gravity.BOTTOM);
        cl.rightMargin = Math.round(0.024f * S);
        cl.bottomMargin = Math.round(0.003f * S);
        bottom.addView(cam, cl);
        content.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.15f * S)));
        content.addView(music);
        stage.addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new StatusBarView(c);
        bar.transparent = true;
        bar.passTouches = true;
        bar.lockExtras = true;
        bar.setOnTick(this::refresh);
        bar.rowHeight = statusH;
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusH + extrasH(), Gravity.TOP));

        bedside = new BedsideView(c);
        addView(bedside, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    private static int statusHeight() {
        int id = android.content.res.Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android");
        int sys = id > 0 ? android.content.res.Resources.getSystem().getDimensionPixelSize(id) : 0;
        return Math.max(sys, StatusBarView.height());
    }

    private TextView text(float size, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextColor(Ui.TEXT);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * S());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        float k = size >= 0.2f ? 1.6f : 1f;
        t.setShadowLayer(0.9f * k, 0.9f * k, 1.4f * k, 0xE0000000);
        return t;
    }

    private static Drawable glyph(int res) {
        Drawable d = SysUi.res.getDrawable(res, null).mutate();
        d.setTint(Ui.TEXT);
        return d;
    }

    private boolean live;

    void onShown() {
        unlocking = false;
        scrolled = 0;
        removeCallbacks(unlockTimeout);
        if (dissolveAnim != null) dissolveAnim.cancel();
        removeCallbacks(revealFallback);
        endReveal();
        asked = false;
        KeyguardHook.setHold(false);
        loadWallpaper();
        collapse(false);
        placeColumn();
        updateCamera(false);
        mode = NONE;
        bedsideDrag = false;
        setDissolve(0f);
        holdDebugSwipe();
        content.setTranslationX(0);
        hint.animate().cancel();
        hint.setAlpha(0f);
        if (!live) {
            live = true;
            bar.start();
            SysUi.addListener(onChange);
            attachMedia();
        }
        refresh();
    }

    void onHidden() {
        icons.clear();
        lastSig = null;
        bedside.dismissNow();
        if (!live) return;
        live = false;
        bar.stop();
        SysUi.removeListener(onChange);
        detachMedia();
    }

    private final Runnable refetch = () -> {
        if (live) fetchInfo();
    };

    private final Runnable onChange = () -> {
        refresh();
        removeCallbacks(refetch);
        postDelayed(refetch, 700);
    };

    void refresh() {
        if (!live) return;
        updateCamera(true);
        boolean h24 = SysUi.clock24 || DateFormat.is24HourFormat(getContext());
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        ampm.setText(h24 ? "" : DateFormat.format("a", now).toString().toUpperCase(Locale.ROOT));
        ampm.setVisibility(h24 ? GONE : VISIBLE);
        date.setText(DateFormat.format("EEE, MMM d", now));
        refitClock();
        bar.invalidate();
        fetchInfo();
    }

    private static Handler io;
    private android.content.ContentProviderClient client;
    private Bundle info;

    private void fetchInfo() {
        if (io == null) {
            HandlerThread t = new HandlerThread("bb10-lock-info");
            t.start();
            io = new Handler(t.getLooper());
        }
        io.removeCallbacksAndMessages(null);
        io.post(() -> {
            Bundle b = null;
            try {
                if (client == null)
                    client = SysUi.sys.getContentResolver().acquireUnstableContentProviderClient("com.bb10.launcher.hubbridge");
                if (client != null) b = client.call("lockinfo", null, null);
            } catch (Throwable t) {
                if (client != null) client.close();
                client = null;
            }
            Bundle fb = b;
            SysUi.main.post(() -> {
                info = fb;
                if (live) showInfo();
            });
        });
        if (info == null) showInfo();
    }

    private void showInfo() {
        float S = S();
        List<String> pkgs = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        String[] ip = info == null ? null : info.getStringArray("pkgs");
        int[] ic = info == null ? null : info.getIntArray("counts");
        if (ip != null && ic != null && ip.length == ic.length) {
            for (int i = 0; i < ip.length; i++) {
                pkgs.add(ip[i]);
                counts.add(ic[i]);
            }
        }
        boolean mailListed = false;
        for (String p : pkgs) mailListed |= p.startsWith("mail:");
        for (String p : SysUi.unreadPackages()) {
            if (pkgs.contains(p) || (mailListed && p.equals(SysUi.PKG))) continue;
            String g = groupOf(p);
            if (!g.equals(p) && ip != null && java.util.Arrays.asList(ip).contains(g)) continue;
            int at = pkgs.indexOf(g);
            if (at >= 0) counts.set(at, counts.get(at) + SysUi.count(p));
            else {
                pkgs.add(g);
                counts.add(SysUi.count(p));
            }
        }
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < pkgs.size() && i < 5; i++) sig.append(pkgs.get(i)).append(':').append(counts.get(i)).append(';');
        if (sig.toString().equals(lastSig) && notes.getChildCount() > 0) {
            showEvents();
            if (selected != null) fillList();
            return;
        }
        lastSig = sig.toString();
        notes.removeAllViews();
        cells.clear();
        boolean stillThere = false;
        for (int i = 0; i < pkgs.size() && i < 5; i++) {
            String pkg = pkgs.get(i);
            ImageView iv = new ImageView(getContext());
            Drawable d = icon(pkg);
            iv.setImageDrawable(d);
            final String fp = pkg;
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int vp = Math.round(0.012f * S);
            cell.setPadding(0, vp, 0, vp);
            cell.setBackground(Ui.selectable(0));
            cell.setOnClickListener(v -> toggleApp(fp));
            int is = Math.round(0.065f * S);
            cell.addView(iv, new LinearLayout.LayoutParams(is, is));
            TextView n = text(0.042f, false);
            n.setTypeface(Ui.light());
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setText(String.valueOf(counts.get(i)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Math.round(0.006f * S);
            cell.addView(n, tl);
            LinearLayout.LayoutParams cl2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl2.topMargin = i == 0 ? 0 : Math.round(0.004f * S);
            notes.addView(cell, cl2);
            cells.put(fp, iv);
            if (fp.equals(selected)) {
                selectedIcon = iv;
                stillThere = true;
            }
        }
        if (stillThere) bracket.follow(selectedIcon);
        notes.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                notes.getViewTreeObserver().removeOnPreDrawListener(this);
                return !fitCells();
            }
        });
        boolean any = notes.getChildCount() > 0;
        leftCol.setVisibility(any ? VISIBLE : GONE);
        rightCol.setPadding(Math.round((any ? 0.02f : 0.06f) * S), 0, Math.round(0.04f * S), 0);
        showEvents();
        if (selected != null) {
            if (stillThere) fillList();
            else collapse(true);
        }
    }

    private boolean fitCells() {
        LinearLayout.LayoutParams nlp = (LinearLayout.LayoutParams) notes.getLayoutParams();
        int room = leftCol.getHeight() - leftCol.getPaddingTop() - leftCol.getPaddingBottom() - nlp.topMargin - nlp.bottomMargin;
        if (room <= 0 || notes.getWidth() <= 0) return false;
        int ws = MeasureSpec.makeMeasureSpec(notes.getWidth(), MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        boolean changed = false, full = false;
        int y = 0;
        for (int i = 0; i < notes.getChildCount(); i++) {
            View cell = notes.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            cell.measure(ws, hs);
            y += lp.topMargin + cell.getMeasuredHeight() + lp.bottomMargin;
            if (i > 0 && y > room) full = true;
            int want = full ? GONE : VISIBLE;
            if (cell.getVisibility() != want) {
                cell.setVisibility(want);
                changed = true;
            }
        }
        return changed;
    }

    private static final long ROWS_AFTER = 150, ROW_STAGGER = 85, ROW_FADE = 220;

    private void toggleApp(String pkg) {
        if (pkg.equals(selected)) {
            collapse(true);
            return;
        }
        boolean was = selected != null;
        selected = pkg;
        selectedIcon = cells.get(pkg);
        listSig = null;
        if (!was) animateClock(true);
        if (was) letRowsGo();
        fillList();
        expandList.animate().cancel();
        expandList.setAlpha(1f);
        expandList.setVisibility(VISIBLE);
        events.animate().cancel();
        events.setVisibility(GONE);
        if (was) {
            bracket.moveTo(selectedIcon);
            fadeRowsIn();
        } else {
            bracket.appear(selectedIcon);
            revealRows();
        }
        updateCamera(true);
    }

    private static final long ROWS_OUT = 120, ROWS_IN_AFTER = 90, ROWS_IN = 230;
    private final List<View> leaving = new ArrayList<>();

    private void letRowsGo() {
        android.view.ViewGroupOverlay over = expandList.getOverlay();
        for (int i = expandList.getChildCount() - 1; i >= 0; i--) {
            View r = expandList.getChildAt(i);
            r.animate().cancel();
            if (r.getVisibility() != VISIBLE || r.getAlpha() <= 0f) continue;
            over.add(r);
            if (r.getParent() instanceof ViewGroup) ((ViewGroup) r.getParent()).setClipChildren(false);
            leaving.add(r);
            r.animate().alpha(0f).setStartDelay(0).setDuration(ROWS_OUT).setInterpolator(null)
                    .withEndAction(() -> {
                        over.remove(r);
                        leaving.remove(r);
                    }).start();
        }
    }

    private void fadeRowsIn() {
        for (int i = 0; i < expandList.getChildCount(); i++) {
            View r = expandList.getChildAt(i);
            r.animate().cancel();
            r.setAlpha(0f);
            r.setTranslationX(0f);
            r.animate().alpha(1f).setStartDelay(ROWS_IN_AFTER).setDuration(ROWS_IN)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
    }

    private void revealRows() {
        float dx = -0.02f * S();
        for (int i = 0; i < expandList.getChildCount(); i++) {
            View r = expandList.getChildAt(i);
            r.animate().cancel();
            r.setAlpha(0f);
            r.setTranslationX(dx);
            r.animate().alpha(1f).translationX(0f).setStartDelay(ROWS_AFTER + i * ROW_STAGGER).setDuration(ROW_FADE)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
    }

    static final String K_CAMERA = "bb10_lock_camera";
    private Boolean cameraTarget;

    private void updateCamera(boolean animate) {
        boolean on = true;
        try {
            on = android.provider.Settings.Global.getInt(SysUi.sys.getContentResolver(), K_CAMERA, 1) == 1;
        } catch (Throwable ignored) {
        }
        boolean show = on && KeyguardHook.userUnlocked() && selected == null;
        if (animate && cameraTarget != null && cameraTarget == show) return;
        cameraTarget = show;
        camera.animate().cancel();
        if (!animate) {
            camera.setAlpha(1f);
            camera.setVisibility(show ? VISIBLE : GONE);
        } else if (show && camera.getVisibility() != VISIBLE) {
            camera.setAlpha(0f);
            camera.setVisibility(VISIBLE);
            camera.animate().alpha(1f).setDuration(150).start();
        } else if (!show && camera.getVisibility() == VISIBLE) {
            camera.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                camera.setVisibility(GONE);
                camera.setAlpha(1f);
            }).start();
        }
    }

    private void collapse(boolean animate) {
        if (selected == null) return;
        selected = null;
        selectedIcon = null;
        listSig = null;
        if (animate) {
            expandList.animate().cancel();
            expandList.animate().alpha(0f).setStartDelay(0).setDuration(110).start();
            bracket.disappear(() -> {
                if (selected != null) return;
                hideList();
                events.animate().cancel();
                events.setAlpha(0f);
                events.setVisibility(VISIBLE);
                events.animate().alpha(1f).setStartDelay(0).setDuration(160).start();
            });
            animateClock(false);
        } else {
            bracket.hideNow();
            hideList();
            events.animate().cancel();
            events.setAlpha(1f);
            events.setVisibility(VISIBLE);
            setClockNow(0f);
        }
        updateMusic();
        updateCamera(animate);
    }

    private void hideList() {
        expandList.animate().cancel();
        for (View r : leaving) r.animate().cancel();
        leaving.clear();
        expandList.getOverlay().clear();
        expandList.setVisibility(GONE);
        expandList.removeAllViews();
        expandList.setAlpha(1f);
    }

    private String listSig;

    private static final class Msg {
        String id;
        String key;
        CharSequence title, body;
        String app;
        long time;
        android.app.PendingIntent open;
    }

    private String groupOf(String pkg) {
        String[] comm = info == null ? null : info.getStringArray("comm");
        boolean c = com.bb10.launcher.hub.HubKinds.knownComm(pkg) || (comm != null && java.util.Arrays.asList(comm).contains(pkg));
        if (!c) for (android.service.notification.StatusBarNotification s : SysUi.notificationsFor(pkg))
            if (com.bb10.launcher.hub.HubKinds.isComm(s.getNotification())) {
                c = true;
                break;
            }
        return com.bb10.launcher.hub.HubKinds.group(pkg, c);
    }

    private List<Msg> messagesFor(String pkg) {
        List<Msg> out = new ArrayList<>();
        Bundle all = info == null ? null : info.getBundle("items");
        Bundle b = all == null ? null : all.getBundle(pkg);
        String[] ids = b == null ? null : b.getStringArray("ids");
        String[] keys = b == null ? null : b.getStringArray("keys");
        String[] titles = b == null ? null : b.getStringArray("titles");
        String[] texts = b == null ? null : b.getStringArray("texts");
        long[] times = b == null ? null : b.getLongArray("times");
        String[] apps = b == null ? null : b.getStringArray("apps");
        int n = ids == null ? 0 : ids.length;
        if (n > 0 && keys != null && keys.length == n && titles != null && titles.length == n
                && texts != null && texts.length == n && times != null && times.length == n) {
            for (int i = 0; i < n; i++) {
                Msg m = new Msg();
                m.id = ids[i];
                m.key = keys[i];
                m.title = titles[i];
                m.body = texts[i];
                m.time = times[i];
                if (apps != null && apps.length == n) m.app = apps[i];
                android.service.notification.StatusBarNotification s = SysUi.notification(m.key);
                if (s != null) m.open = s.getNotification().contentIntent;
                out.add(m);
            }
            return out;
        }
        List<android.service.notification.StatusBarNotification> ns = new ArrayList<>();
        boolean many = pkg.equals(com.bb10.launcher.hub.HubKinds.NOTIF) || pkg.equals(com.bb10.launcher.hub.HubKinds.CALLS);
        if (many) {
            for (String p : SysUi.unreadPackages()) if (groupOf(p).equals(pkg)) ns.addAll(SysUi.notificationsFor(p));
            ns.sort((x, y) -> Long.compare(y.getPostTime(), x.getPostTime()));
        } else ns = SysUi.notificationsFor(pkg);
        for (android.service.notification.StatusBarNotification s : ns) {
            Bundle ex = s.getNotification().extras;
            Msg m = new Msg();
            m.app = s.getPackageName();
            m.key = s.getKey();
            m.title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE);
            m.body = ex.getCharSequence(android.app.Notification.EXTRA_TEXT);
            m.time = s.getPostTime();
            m.open = s.getNotification().contentIntent;
            out.add(m);
        }
        return out;
    }

    private void fillList() {
        float S = S();
        List<Msg> list = messagesFor(selected);
        boolean details = true;
        try {
            details = android.provider.Settings.Secure.getInt(SysUi.sys.getContentResolver(), "lock_screen_allow_private_notifications", 1) == 1;
        } catch (Throwable ignored) {
        }
        boolean h24 = SysUi.clock24 || DateFormat.is24HourFormat(getContext());
        int shown = Math.min(MAX_SHOWN, list.size());
        StringBuilder sig = new StringBuilder(selected).append(details).append(h24)
                .append(DateFormat.format("yyyyD", System.currentTimeMillis()));
        for (int i = 0; i < shown; i++) {
            Msg m = list.get(i);
            sig.append('|').append(m.id != null ? m.id : m.key).append('@').append(m.time);
        }
        String s = sig.toString();
        if (s.equals(listSig)) return;
        listSig = s;
        expandList.removeAllViews();
        listScroll.scrollTo(0, 0);
        boolean mail = selected.startsWith("mail:");
        for (int i = 0; i < shown; i++) {
            Msg m = list.get(i);
            CharSequence title = m.title;
            CharSequence body = m.body;
            if (TextUtils.isEmpty(title)) title = mail ? "Email" : appLabel(m.app != null ? m.app : selected);
            if (!details) body = "Contents hidden";
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text(0.04f, true);
            t.setText(title);
            top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView when = text(0.031f, false);
            when.setText(when(m.time, h24));
            when.setPadding(Math.round(0.015f * S), 0, 0, 0);
            top.addView(when);
            row.addView(top);
            if (body != null && body.length() > 0) {
                TextView b = text(0.034f, false);
                b.setSingleLine(false);
                b.setMaxLines(2);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b.setText(body.toString().replace('\n', ' '));
                b.setTextColor(0xE6FFFFFF);
                b.setPadding(0, Math.round(0.008f * S), 0, 0);
                row.addView(b);
            }
            android.app.PendingIntent open = m.open;
            String pkgToOpen = m.app != null ? m.app : selected, keyToOpen = m.key, itemToOpen = m.id;
            row.setBackground(Ui.selectable(0));
            row.setOnClickListener(v -> {
                if (mail) {
                    KeyguardHook.openAfterUnlock(() -> openThroughFrame(pkgToOpen, null, null, itemToOpen));
                    return;
                }
                prepareFrame(pkgToOpen, open, keyToOpen, itemToOpen);
                KeyguardHook.openAfterUnlock(() -> {
                    if (!openThroughFrame(pkgToOpen, open, keyToOpen, itemToOpen)) {
                        if (open != null) KeyguardHook.send(open);
                        else launch(pkgToOpen);
                    }
                }, () -> frameSignal("com.bb10.launcher.UNPREPARE_FRAME", pkgToOpen));
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = i == 0 ? 0 : Math.round(0.05f * S);
            expandList.addView(row, rl);
        }
        fitWhenLaidOut();
        bracket.invalidate();
    }

    private void fitWhenLaidOut() {
        expandList.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                expandList.getViewTreeObserver().removeOnPreDrawListener(this);
                return !fitRows();
            }
        });
        expandList.invalidate();
    }

    private boolean fitRows() {
        if (selected == null) return false;
        if (expandList.getWidth() <= 0) return false;
        float room = rightCol.getHeight() - rightCol.getPaddingBottom() - expandList.getTop();
        int ws = MeasureSpec.makeMeasureSpec(expandList.getWidth(), MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        boolean changed = false, full = false;
        float y = 0;
        for (int i = 0; i < expandList.getChildCount(); i++) {
            View r = expandList.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) r.getLayoutParams();
            r.measure(ws, hs);
            y += lp.topMargin + r.getMeasuredHeight();
            y += lp.bottomMargin;
            int want = full ? GONE : VISIBLE;
            if (r.getVisibility() != want) {
                r.setVisibility(want);
                changed = true;
            }
        }
        if (changed) bracket.invalidate();
        return changed;
    }

    static boolean openThroughFrame(String pkg, android.app.PendingIntent target, String key) {
        return openThroughFrame(pkg, target, key, null);
    }

    static boolean openThroughFrame(String pkg, android.app.PendingIntent target, String key, String item) {
        String top = topApp();
        if (target != null && pkg != null && pkg.equals(top)) {
            KeyguardHook.send(target);
            return true;
        }
        try {
            boolean overApp = top != null && !top.equals("com.bb10.launcher");
            SysUi.sys.startActivity(new android.content.Intent("com.bb10.launcher.OPEN_THROUGH_FRAME")
                    .setClassName("com.bb10.launcher", "com.bb10.launcher.LauncherActivity")
                    .putExtra("pkg", pkg).putExtra("open_pi", target).putExtra("notif_key", key)
                    .putExtra("hub_item", item)
                    .putExtra("open_token", openToken())
                    .putExtra("over_app", overApp)
                    .putExtra("unlock_playing", KeyguardHook.unlockPlaying())
                    .putExtra("bouncer", KeyguardHook.bouncerUp())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            KeyguardHook.log("opening " + pkg + " through its frame: " + t);
            return false;
        }
    }

    private static String openToken() {
        android.os.Bundle b = SplashHook.callLauncher("open_token", null);
        return b == null ? null : b.getString("token");
    }

    private static void prepareFrame(String pkg, android.app.PendingIntent target, String key, String item) {
        if (pkg == null) return;
        try {
            SysUi.sys.sendBroadcast(new android.content.Intent("com.bb10.launcher.PREPARE_FRAME")
                    .setPackage("com.bb10.launcher").putExtra("pkg", pkg)
                    .putExtra("open_pi", target).putExtra("notif_key", key).putExtra("hub_item", item)
                    .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (Throwable t) {
            KeyguardHook.log("preparing " + pkg + "'s frame: " + t);
        }
    }

    private static void frameSignal(String action, String pkg) {
        if (pkg == null) return;
        try {
            SysUi.sys.sendBroadcast(new android.content.Intent(action)
                    .setPackage("com.bb10.launcher").putExtra("pkg", pkg)
                    .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (Throwable t) {
            KeyguardHook.log("preparing " + pkg + "'s frame: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static String topApp() {
        try {
            android.app.ActivityManager am = SysUi.sys.getSystemService(android.app.ActivityManager.class);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t == null || t.isEmpty() || t.get(0).topActivity == null) return null;
            return t.get(0).topActivity.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void launch(String pkg) {
        try {
            android.content.Intent i = new android.content.Intent("com.bb10.launcher.OPEN_THROUGH_FRAME")
                    .setClassName("com.bb10.launcher", "com.bb10.launcher.LauncherActivity")
                    .putExtra("pkg", pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                SysUi.sys.startActivity(i);
            } catch (Throwable notThere) {
                android.content.Intent li = SysUi.sys.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) SysUi.sys.startActivity(li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Throwable t) {
            KeyguardHook.log("opening " + pkg + ": " + t);
        }
    }

    private static CharSequence appLabel(String pkg) {
        try {
            return SysUi.sys.getPackageManager().getApplicationLabel(SysUi.sys.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static String when(long t, boolean h24) {
        if (android.text.format.DateUtils.isToday(t)) return DateFormat.format(h24 ? "HH:mm" : "h:mm a", t).toString();
        if (System.currentTimeMillis() - t < 6L * 86_400_000L) return DateFormat.format("EEEE", t).toString();
        return DateFormat.format("MMM d", t).toString();
    }

    private android.animation.ValueAnimator clockAnim;
    private float clockK;
    private float layoutK;
    private int clockH, dateH;
    private float layoutFit = 1f;

    private static final long CLOCK_MS = 280;

    private static float clockPx(float k) { return (0.25f - 0.13f * k) * S(); }

    private static float ampmPx(float k) { return (0.065f - 0.025f * k) * S(); }

    private static float datePx(float k) { return (0.05f - 0.016f * k) * S(); }

    private float fromK;
    private float fixRowX, fixRowY, fixAmpmX, fixDateY, fixBelowY;
    private static final float DATE_TUCK = 0.264f;

    private void animateClock(boolean small) {
        if (clockAnim != null) clockAnim.cancel();
        float to = small ? 1f : 0f;
        boolean shown = date.getHeight() > 0 && clock.getBaseline() > 0;
        float sRow = clockRow.getScaleX();
        float rowX0 = clockRow.getTranslationX();
        float rowY0 = clockRow.getTranslationY() + (clock.getTop() + clock.getBaseline()) * sRow;
        float ampmX0 = (ampm.getLeft() + ampm.getTranslationX()) * sRow;
        float dateY0 = date.getTop() + date.getTranslationY();
        float belowY0 = dateY0 + date.getHeight() * date.getScaleY();
        setClockLayout(to);
        fromK = clockK;
        fixRowX = fixRowY = fixAmpmX = fixDateY = fixBelowY = 0f;
        if (shown && clock.getBaseline() > 0) {
            float sc = clockScale(clockK), sd = datePx(clockK) / datePx(layoutK);
            float gap = ((LinearLayout.LayoutParams) ampm.getLayoutParams()).leftMargin;
            float rowTop = clockRow.getTop();
            fixRowX = rowX0 - clockAlign(sc, sd);
            fixRowY = rowY0 - (clock.getTop() + clock.getBaseline()) * sc;
            fixAmpmX = ampmX0 - (clock.getMeasuredWidth() * sc + gap);
            fixDateY = dateY0 - (rowTop + clockH * sc);
            fixBelowY = belowY0 - (rowTop + clockH * sc + dateH * sd);
        }
        setClockLook(clockK);
        clockAnim = android.animation.ValueAnimator.ofFloat(clockK, to);
        clockAnim.setDuration(CLOCK_MS);
        clockAnim.setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.15f, 1f));
        clockAnim.addUpdateListener(a -> setClockLook((float) a.getAnimatedValue()));
        clockAnim.start();
    }

    private void setClockNow(float k) {
        if (clockAnim != null) clockAnim.cancel();
        clockK = fromK = k;
        fixRowX = fixRowY = fixAmpmX = fixDateY = fixBelowY = 0f;
        setClockLayout(k);
        setClockLook(k);
    }

    private void refitClock() {
        setClockLayout(layoutK);
        setClockLook(clockK);
    }

    private float clockScale(float k) {
        float fit = k == layoutK ? layoutFit : clockFit(clockPx(k), ampmPx(k));
        return clockPx(k) * fit / (clockPx(layoutK) * layoutFit);
    }

    private void setClockLayout(float k) {
        layoutK = k;
        layoutFit = clockFit(clockPx(k), ampmPx(k));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clockPx(k) * layoutFit);
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_PX, ampmPx(k) * layoutFit);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, datePx(k));
        int w = rightCol.getWidth() - rightCol.getPaddingLeft() - rightCol.getPaddingRight();
        int dw = w - ((LinearLayout.LayoutParams) date.getLayoutParams()).leftMargin;
        int hs = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        clockRow.measure(w > 0 ? MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST) : hs, hs);
        date.measure(dw > 0 ? MeasureSpec.makeMeasureSpec(dw, MeasureSpec.AT_MOST) : hs, hs);
        int tuck = -Math.round(DATE_TUCK * clockPx(k) * layoutFit);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) date.getLayoutParams();
        if (dlp.topMargin != tuck) {
            dlp.topMargin = tuck;
            date.setLayoutParams(dlp);
        }
        clockH = clockRow.getMeasuredHeight() + tuck;
        dateH = date.getMeasuredHeight();
    }

    private void setClockLook(float k) {
        clockK = k;
        float S = S();
        float sc = clockScale(k);
        float sa = ampmPx(k) / ampmPx(layoutK) * (sc * clockPx(layoutK) / clockPx(k));
        float sd = datePx(k) / datePx(layoutK);
        float w = layoutK == fromK ? 0f : 1f - Math.max(0f, Math.min(1f, (k - fromK) / (layoutK - fromK)));
        clockRow.setPivotX(0f);
        clockRow.setPivotY(0f);
        clockRow.setScaleX(sc);
        clockRow.setScaleY(sc);
        clockRow.setTranslationX(clockAlign(sc, sd) + fixRowX * w);
        clockRow.setTranslationY(fixRowY * w);
        float gap = ((LinearLayout.LayoutParams) ampm.getLayoutParams()).leftMargin;
        int base = ampm.getBaseline();
        ampm.setPivotX(0f);
        ampm.setPivotY(base > 0 ? base : 0);
        ampm.setScaleX(sa / sc);
        ampm.setScaleY(sa / sc);
        ampm.setTranslationX((gap * (1f - sc) + fixAmpmX * w) / sc);
        date.setPivotX(0f);
        date.setPivotY(0f);
        date.setScaleX(sd);
        date.setScaleY(sd);
        date.setTranslationY(clockH * (sc - 1f) + fixDateY * w);
        float below = clockH * (sc - 1f) + dateH * (sd - 1f) + fixBelowY * w;
        events.setTranslationY(below);
        listScroll.setTranslationY(below);
        rightCol.setTranslationY(-k * (extrasH() + 0.03f * S));
        bracket.invalidate();
    }

    private float clockFit(float clockSize, float ampmSize) {
        int avail = rightCol.getWidth() - rightCol.getPaddingLeft() - rightCol.getPaddingRight();
        if (avail <= 0) return 1f;
        android.text.TextPaint cp = new android.text.TextPaint(clock.getPaint());
        cp.setTextSize(clockSize);
        float need = cp.measureText(clock.getText().toString());
        if (ampm.getVisibility() == VISIBLE && ampm.length() > 0) {
            android.text.TextPaint ap = new android.text.TextPaint(ampm.getPaint());
            ap.setTextSize(ampmSize);
            need += ap.measureText(ampm.getText().toString()) + ((LinearLayout.LayoutParams) ampm.getLayoutParams()).leftMargin;
        }
        need += Ui.dp(6);
        return need <= avail ? 1f : avail / need;
    }

    private float clockAlign(float sc, float sd) {
        String cs = clock.getText().toString(), ds = date.getText().toString();
        if (cs.isEmpty() || ds.isEmpty()) return 0f;
        clock.getPaint().getTextBounds(cs, 0, 1, ink);
        float clockInk = ink.left * sc;
        date.getPaint().getTextBounds(ds, 0, 1, ink);
        float dateInk = ((LinearLayout.LayoutParams) date.getLayoutParams()).leftMargin + ink.left * sd;
        return dateInk - clockInk;
    }

    private final class Bracket extends View {
        private static final long NOTCH_MS = 110, GROW_MS = 260, BACK_MS = 150, NOTCH_OUT_MS = 70, MOVE_MS = 280;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final int[] me = new int[2], it = new int[2];
        private View anchor;
        private float notch, grow;

        private float move = 1f;
        private float fromY, fromUp, fromDown, fromNotch;
        private float lastY, lastUp, lastDown, lastNotch;
        private boolean drawn;
        private android.animation.ValueAnimator anim;

        Bracket(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Ui.TEXT);
            p.setStrokeWidth(Math.max(2f, 0.004f * S()));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void appear(View icon) {
            stop();
            anchor = icon;
            notch = 0f;
            grow = 0f;
            move = 1f;
            setVisibility(VISIBLE);
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(NOTCH_MS + GROW_MS);
            anim.setInterpolator(null);
            anim.addUpdateListener(a -> {
                float t = a.getAnimatedFraction() * (NOTCH_MS + GROW_MS);
                notch = easeOut(Math.min(1f, t / NOTCH_MS), 2f);
                float g = (t - NOTCH_MS * 0.6f) / GROW_MS;
                grow = g <= 0f ? 0f : easeOut(Math.min(1f, g), 2.2f);
                invalidate();
            });
            anim.start();
        }

        void moveTo(View icon) {
            if (!drawn || getVisibility() != VISIBLE) {
                appear(icon);
                return;
            }
            stop();
            fromY = lastY;
            fromUp = lastUp;
            fromDown = lastDown;
            fromNotch = lastNotch;
            anchor = icon;
            notch = grow = 1f;
            move = 0f;
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(MOVE_MS);
            anim.setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.15f, 1f));
            anim.addUpdateListener(a -> {
                move = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void disappear(Runnable then) {
            stop();
            if (anchor == null || getVisibility() != VISIBLE) {
                hideNow();
                then.run();
                return;
            }
            float g0 = grow, n0 = notch, m0 = move;
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(BACK_MS + NOTCH_OUT_MS);
            anim.setInterpolator(null);
            anim.addUpdateListener(a -> {
                float t = a.getAnimatedFraction() * (BACK_MS + NOTCH_OUT_MS);
                float back = easeOut(Math.min(1f, t / BACK_MS), 1.6f);
                grow = g0 * (1f - back);
                move = m0 + (1f - m0) * back;
                notch = n0 * (1f - Math.max(0f, Math.min(1f, (t - BACK_MS) / NOTCH_OUT_MS)));
                invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    anim = null;
                    hideNow();
                    then.run();
                }
            });
            anim.start();
        }

        void hideNow() {
            stop();
            anchor = null;
            notch = grow = 0f;
            move = 1f;
            drawn = false;
            setVisibility(GONE);
        }

        void follow(View icon) {
            if (anchor != null && icon != null) {
                anchor = icon;
                invalidate();
            }
        }

        private void stop() {
            if (anim == null) return;
            android.animation.ValueAnimator a = anim;
            anim = null;
            a.removeAllListeners();
            a.removeAllUpdateListeners();
            a.cancel();
        }

        private float easeOut(float t, float power) {
            return 1f - (float) Math.pow(1f - t, power);
        }

        @Override
        protected void onDraw(Canvas c) {
            View icon = anchor;
            if (icon == null || !icon.isAttachedToWindow() || notch <= 0f) {
                drawn = false;
                return;
            }
            float S = S();
            getLocationInWindow(me);
            icon.getLocationInWindow(it);
            float y = it[1] - me[1] + icon.getHeight() / 2f;
            float x = getWidth() * 0.6f, d = 0.018f * S;
            float top = y - d * 2, bottom = y + d * 2;
            int lb = 0;
            for (int i = 0; i < expandList.getChildCount(); i++) {
                View r = expandList.getChildAt(i);
                if (r.getVisibility() == VISIBLE) lb = Math.max(lb, r.getBottom());
            }
            if (listScroll.getHeight() > 0) lb = Math.min(lb, listScroll.getHeight());
            if (expandList.getVisibility() == VISIBLE && lb > 0) {
                listScroll.getLocationInWindow(it);
                float lt = it[1] - me[1];
                if (selected != null) lt += -(extrasH() + 0.03f * S) - rightCol.getTranslationY() - listScroll.getTranslationY();
                top = Math.min(top, lt);
                bottom = Math.max(bottom, lt + lb);
            }
            float up = (y - d) - grow * ((y - d) - top);
            float down = (y + d) + grow * (bottom - (y + d));
            float n = notch;
            if (move < 1f) {
                y = fromY + (y - fromY) * move;
                up = fromUp + (up - fromUp) * move;
                down = fromDown + (down - fromDown) * move;
                n = fromNotch + (n - fromNotch) * move;
            }
            lastY = y;
            lastUp = up;
            lastDown = down;
            lastNotch = n;
            drawn = true;
            float a = Math.min(1f, n * 1.5f);
            p.setAlpha(Math.round(255 * a));
            p.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), Math.round(0x99 * a) << 24);
            path.rewind();
            path.moveTo(x, up);
            path.lineTo(x, y - d);
            path.lineTo(x - d * n, y);
            path.lineTo(x, y + d);
            path.lineTo(x, down);
            c.drawPath(path, p);
        }
    }
    private void placeColumn() {
        boolean top = false;
        try {
            top = "top".equals(android.provider.Settings.Global.getString(SysUi.sys.getContentResolver(), "bb10_lock_column"));
        } catch (Throwable ignored) {
        }
        leftCol.setGravity(top ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) notes.getLayoutParams();
        int m = top ? Math.round(0.045f * S()) : 0;
        if (lp.topMargin != m) {
            lp.topMargin = m;
            notes.setLayoutParams(lp);
        }
    }
    private String lastSig;
    private final java.util.Map<String, ImageView> cells = new java.util.HashMap<>();
    private final java.util.Map<String, Drawable> icons = new java.util.HashMap<>();

    private Drawable icon(String pkg) {
        Drawable d = icons.get(pkg);
        if (d != null) return d;
        int res = pkg.startsWith("mail:") ? R.drawable.ic_mail : pkg.equals(com.bb10.launcher.hub.HubKinds.NOTIF) ? R.drawable.ic_bell
                : pkg.equals(com.bb10.launcher.hub.HubKinds.CALLS) ? R.drawable.ic_phone : 0;
        if (res != 0) {
            d = glyph(res);
            icons.put(pkg, d);
            return d;
        }
        Icon small = SysUi.smallIcon(pkg);
        if (small != null) {
            try {
                d = small.loadDrawable(SysUi.sys);
                if (d != null) {
                    d = d.mutate();
                    d.setTint(Ui.TEXT);
                }
            } catch (Throwable ignored) {
            }
        }
        if (d == null) {
            try {
                d = SysUi.sys.getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
        }
        if (d != null) {
            if (icons.size() > 40) icons.clear();
            icons.put(pkg, d);
        }
        return d;
    }
    private void showEvents() {
        events.removeAllViews();
        String[] titles = info == null ? null : info.getStringArray("titles");
        long[] begins = info == null ? null : info.getLongArray("begins");
        long[] ends = info == null ? null : info.getLongArray("ends");
        boolean[] allDay = info == null ? null : info.getBooleanArray("allday");
        if (titles == null || begins == null || ends == null || allDay == null) return;
        long now = System.currentTimeMillis();
        int first = -1, more = 0;
        for (int i = 0; i < titles.length; i++) {
            if (ends[i] <= now || begins[i] >= now + 24 * 3600_000L) continue;
            if (first < 0) first = i;
            else more++;
        }
        if (first < 0) return;
        boolean h24 = SysUi.clock24 || DateFormat.is24HourFormat(getContext());
        long b = begins[first], e = ends[first];
        String head;
        if (b <= now) head = "NOW";
        else if (b - now < 3600_000L) head = "IN " + Math.max(1, (b - now) / 60_000L) + " MIN";
        else if (android.text.format.DateUtils.isToday(b)) head = "TODAY";
        else head = "TOMORROW";
        events.addView(eventLine(head, 0.03f, true));
        events.addView(eventLine(titles[first], 0.04f, false));
        long len = Math.max(0, e - b);
        String dur;
        if (allDay[first]) {
            long days = Math.max(1, Math.round(len / 86_400_000.0));
            dur = days + (days == 1 ? " day" : " days");
        } else {
            long mins = len / 60_000L;
            dur = mins >= 60 ? (mins / 60) + " hr" + (mins % 60 > 0 ? " " + (mins % 60) + " min" : "") : mins + " min";
        }
        String start = allDay[first] ? "All day" : DateFormat.format(h24 ? "HH:mm" : "h:mm a", b).toString();
        events.addView(eventLine(start + " (" + dur + ")", 0.035f, false));
        if (more > 0) events.addView(eventLine("+" + more + " more", 0.035f, false));
    }

    private TextView eventLine(String s, float size, boolean bold) {
        TextView t = text(size, bold);
        t.setText(s);
        t.setPadding(0, Math.round(0.006f * S()), 0, 0);
        return t;
    }

    private LinearLayout music;
    private ImageView musicArt, musicPlay;
    private TextView musicTitle, musicArtist;
    private MediaController mc;
    private MediaSessionManager.OnActiveSessionsChangedListener sessions;
    private final MediaController.Callback mcb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            updateMusic();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            updateMusic();
        }
    };

    private View buildMusic(Context c) {
        float S = S();
        music = new LinearLayout(c);
        music.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(0.04f * S);
        music.setPadding(pad, Math.round(0.02f * S), pad, Math.round(0.02f * S));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MUSIC_PLAIN);
        music.setBackground(bg);
        musicBg = bg;
        musicArt = new ImageView(c);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        int as = Math.round(0.13f * S);
        music.addView(musicArt, new LinearLayout.LayoutParams(as, as));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        musicTitle = text(0.032f, true);
        musicArtist = text(0.027f, false);
        musicArtist.setTextColor(0xCCFFFFFF);
        musicArtist.setPadding(0, Math.round(0.008f * S), 0, 0);
        texts.addView(musicTitle);
        texts.addView(musicArtist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Math.round(0.03f * S);
        music.addView(texts, tlp);
        int bs = Math.round(0.095f * S);
        music.addView(musicButton(c, R.drawable.ic_m_prev, m -> m.getTransportControls().skipToPrevious()), new LinearLayout.LayoutParams(bs, bs));
        musicPlay = musicButton(c, R.drawable.ic_m_play, m -> {
            if (playing(m)) m.getTransportControls().pause();
            else m.getTransportControls().play();
        });
        music.addView(musicPlay, new LinearLayout.LayoutParams(bs, bs));
        music.addView(musicButton(c, R.drawable.ic_m_next, m -> m.getTransportControls().skipToNext()), new LinearLayout.LayoutParams(bs, bs));
        music.setVisibility(GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        music.setLayoutParams(lp);
        return music;
    }

    private interface MediaAction {
        void run(MediaController m);
    }

    private ImageView musicButton(Context c, int icon, MediaAction a) {
        ImageView b = new ImageView(c);
        b.setImageDrawable(glyph(icon));
        int p = Math.round(0.022f * S());
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.selectable(0));
        b.setOnClickListener(v -> {
            if (mc == null) return;
            try {
                a.run(mc);
            } catch (Throwable ignored) {
            }
        });
        return b;
    }

    private static boolean playing(MediaController m) {
        PlaybackState s = m.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void attachMedia() {
        try {
            MediaSessionManager msm = SysUi.sys.getSystemService(MediaSessionManager.class);
            if (sessions == null) sessions = list -> pickMedia(list);
            msm.addOnActiveSessionsChangedListener(sessions, null, SysUi.main);
            pickMedia(msm.getActiveSessions((ComponentName) null));
        } catch (Throwable t) {
            SysUi.note("lock screen music: " + t);
        }
    }

    private void detachMedia() {
        try {
            if (sessions != null) SysUi.sys.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessions);
        } catch (Throwable ignored) {
        }
        setController(null);
    }

    private void pickMedia(List<MediaController> list) {
        MediaController best = null, paused = null;
        if (list != null) {
            for (MediaController m : list) {
                PlaybackState s = m.getPlaybackState();
                if (m.getMetadata() == null || s == null) continue;
                if (SysUi.closedMedia(m.getPackageName()) && SysUi.hiddenPlayer(m.getPackageName(), playing(m))) continue;
                if (playing(m)) {
                    best = m;
                    break;
                }
                int st = s.getState();
                if (paused == null && st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED && st != PlaybackState.STATE_ERROR)
                    paused = m;
            }
        }
        setController(best != null ? best : paused);
    }

    private void setController(MediaController m) {
        if (mc != null && (m == null || !mc.getSessionToken().equals(m.getSessionToken()))) {
            try {
                mc.unregisterCallback(mcb);
            } catch (Throwable ignored) {
            }
            mc = null;
        }
        if (m != null && mc == null) {
            mc = m;
            mc.registerCallback(mcb, SysUi.main);
        }
        updateMusic();
    }

    private static final int MUSIC_PLAIN = 0xB3000000;
    private GradientDrawable musicBg;
    private Bitmap tintFor;
    private int tintColor = MUSIC_PLAIN;
    private android.animation.ValueAnimator tintAnim;

    private void tintMusic(Bitmap art) {
        boolean on = true;
        try {
            on = android.provider.Settings.Global.getInt(SysUi.sys.getContentResolver(), "bb10_lock_music_tint", 1) == 1;
        } catch (Throwable ignored) {
        }
        int want = MUSIC_PLAIN;
        if (on && art != null && !art.isRecycled()) {
            if (art != tintFor) {
                tintFor = art;
                tintColor = artColor(art);
            }
            want = tintColor;
        }
        int from = musicBg == null ? want : currentTint;
        if (from == want) {
            setTint(want);
            return;
        }
        if (tintAnim != null) tintAnim.cancel();
        tintAnim = android.animation.ValueAnimator.ofArgb(from, want);
        tintAnim.setDuration(350);
        tintAnim.addUpdateListener(a -> setTint((int) a.getAnimatedValue()));
        tintAnim.start();
    }

    private int currentTint = MUSIC_PLAIN;

    private void setTint(int c) {
        currentTint = c;
        if (musicBg != null) musicBg.setColor(c);
    }

    private static int artColor(Bitmap art) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(art.isMutable() || art.getConfig() != Bitmap.Config.HARDWARE ? art
                    : art.copy(Bitmap.Config.ARGB_8888, false), 24, 24, true);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, wsum = 0;
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    int px = s.getPixel(x, y);
                    android.graphics.Color.colorToHSV(px, hsv);
                    double w = 0.1 + hsv[1] * hsv[2] * 2;
                    r += android.graphics.Color.red(px) * w;
                    g += android.graphics.Color.green(px) * w;
                    b += android.graphics.Color.blue(px) * w;
                    wsum += w;
                }
            }
            int avg = android.graphics.Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (b / wsum));
            android.graphics.Color.colorToHSV(avg, hsv);
            hsv[1] = Math.min(1f, Math.max(hsv[1], 0.35f) * 1.15f);
            hsv[2] = Math.min(hsv[2], 0.38f);
            return android.graphics.Color.HSVToColor(0xE6, hsv);
        } catch (Throwable t) {
            return MUSIC_PLAIN;
        }
    }

    private void updateMusic() {
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        if (md == null) {
            music.setVisibility(GONE);
            return;
        }
        music.setVisibility(VISIBLE);
        musicTitle.setText(md.getText(MediaMetadata.METADATA_KEY_TITLE));
        CharSequence ar = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (ar == null) ar = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        musicArtist.setText(ar);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art != null) musicArt.setImageBitmap(art);
        else {
            try {
                musicArt.setImageDrawable(SysUi.sys.getPackageManager().getApplicationIcon(mc.getPackageName()));
            } catch (Throwable e) {
                musicArt.setImageDrawable(null);
            }
        }
        tintMusic(art);
        musicPlay.setImageDrawable(glyph(playing(mc) ? R.drawable.ic_m_pause : R.drawable.ic_m_play));
    }

    private void openCamera() {
        try {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            SysUi.sys.startActivity(i);
        } catch (Throwable t) {
            KeyguardHook.log("camera: " + t);
        }
    }

    private float dissolve, front = Float.NaN, lift;
    private final Paint eraser = new Paint(), solid = new Paint();
    private LinearGradient dome;
    private float touchX = Float.NaN;
    private final Matrix fadeMatrix = new Matrix();
    private android.animation.ValueAnimator dissolveAnim;
    private boolean unlocking;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;

    private float band() {
        return getHeight() * 0.15f;
    }

    private float frontFor(float f) {
        float H = getHeight(), b = band();
        f = Math.max(0f, Math.min(1f, f));
        float g = f < 0.5f ? f * 0.9f : Math.min(1f, 0.45f + (f - 0.5f) * 2.2f);
        float ry = b / 0.3f;
        return H + b - g * (H + 2 * b + 0.7f * ry);
    }

    private float liftFor(float f) {
        return 0f;
    }

    private void setDissolve(float f) {
        setSwipe(f, frontFor(f), liftFor(f));
    }

    private void setSwipe(float f, float edge, float up) {
        dissolve = Math.max(0f, Math.min(1f, f));
        front = edge;
        lift = up;
        stage.setTranslationY(lift);
        float k = Math.min(1f, dissolve * 12f);
        if (rays == null && !raysFailed && k >= 0.02f && android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                rays = new android.graphics.RuntimeShader(RAYS);
            } catch (Throwable t) {
                raysFailed = true;
                KeyguardHook.log("lock glow shader: " + t);
            }
        }
        if (k < 0.02f || rays == null) {
            if (glow.getVisibility() != INVISIBLE) {
                glow.setVisibility(INVISIBLE);
                glow.setRenderEffect(null);
            }
        } else {
            float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
            float p = Math.min(1f, dissolve / 0.75f);
            float fx = Float.isNaN(touchX) ? W / 2f : touchX;
            float lx = W / 2f + (fx - W / 2f) * 0.6f, ly = H * (1f - p);
            rays.setFloatUniform("light", lx / GLOW_SCALE, ly / GLOW_SCALE);
            rays.setFloatUniform("strength", k);
            rays.setFloatUniform("size", H / GLOW_SCALE);
            glow.setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createBlurEffect(2.5f, 2.5f, Shader.TileMode.CLAMP),
                    android.graphics.RenderEffect.createRuntimeShaderEffect(rays, "content")));
            if (glow.getVisibility() != VISIBLE) glow.setVisibility(VISIBLE);
        }
        bar.extrasAlpha = 1f - Math.min(1f, dissolve * 1.6f);
        moonOut(dissolve > 0.005f);
        bar.invalidate();
        invalidate();
    }

    private android.graphics.RuntimeShader rays;
    private boolean raysFailed;

    private boolean moonGone;
    private android.animation.ValueAnimator moonAnim;

    private void moonOut(boolean out) {
        if (out == moonGone) return;
        moonGone = out;
        if (moonAnim != null) moonAnim.cancel();
        moonAnim = android.animation.ValueAnimator.ofFloat(bar.bedsideSlide, out ? 1f : 0f);
        moonAnim.setDuration(out ? 180 : 220);
        moonAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        moonAnim.addUpdateListener(a -> {
            bar.bedsideSlide = (float) a.getAnimatedValue();
            bar.invalidate();
        });
        moonAnim.start();
    }

    private static final int GLOW_SCALE = 3;

    private final class Glow extends View {
        Glow(Context c) {
            super(c);
            setPivotX(0f);
            setPivotY(0f);
            setScaleX(GLOW_SCALE);
            setScaleY(GLOW_SCALE);
            Paint add = new Paint();
            add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            setLayerType(LAYER_TYPE_HARDWARE, add);
            setVisibility(INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas c) {
            Drawable d = wall.getDrawable();
            if (d == null) return;
            int s = c.save();
            c.scale(1f / GLOW_SCALE, 1f / GLOW_SCALE);
            c.concat(wall.getImageMatrix());
            d.draw(c);
            c.restoreToCount(s);
        }
    }

    private Glow glow;

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (glow == null) return;
        ViewGroup.LayoutParams lp = glow.getLayoutParams();
        int gw = (w + GLOW_SCALE - 1) / GLOW_SCALE, gh = (h + GLOW_SCALE - 1) / GLOW_SCALE;
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            glow.setLayoutParams(lp);
        }
    }

    private void holdDebugSwipe() {
        try {
            String v = android.provider.Settings.Global.getString(SysUi.sys.getContentResolver(), "bb10_debug_swipe");
            if (v == null || v.isEmpty()) return;
            String[] a = v.split(",");
            float f = Float.parseFloat(a[0]), x = a.length > 1 ? Float.parseFloat(a[1]) : 0.5f;
            post(() -> {
                touchX = x * getWidth();
                setSwipe(f, frontFor(f), 0f);
            });
        } catch (Throwable ignored) {
        }
    }

    private static final String RAYS =
            "uniform shader content;\n"
            + "uniform float2 light;\n"
            + "uniform float strength;\n"
            + "uniform float size;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 base = content.eval(p);\n"
            + "  float2 d = light - p;\n"
            + "  float2 dir = d / max(length(d), 0.001);\n"
            + "  float2 side = float2(-dir.y, dir.x);\n"
            + "  float len = size * 0.12, soft = size * 0.006;\n"
            + "  half far = half(smoothstep(0.0, size * 0.7, length(d)));\n"
            + "  half lo = mix(0.15, 0.07, far);\n"
            + "  half3 acc = half3(0.0);\n"
            + "  for (int i = 0; i < 18; i++) {\n"
            + "    float t = (float(i) + 0.5) / 18.0 * len;\n"
            + "    for (int k = -1; k <= 1; k++) {\n"
            + "      half w = k == 0 ? 0.5 : 0.25;\n"
            + "      half3 s = content.eval(p + dir * t + side * (float(k) * soft)).rgb;\n"
            + "      half l = dot(s, half3(0.299, 0.587, 0.114));\n"
            + "      acc += w * mix(s, half3(l), 0.15) * smoothstep(lo, 0.5, l);\n"
            + "    }\n"
            + "  }\n"
            + "  acc /= 18.0;\n"
            + "  half3 haze = (base.rgb * 1.1 + half3(0.03)) * 0.08;\n"
            + "  half3 c = (acc * 1.1 * mix(0.85, 1.6, far) + haze * mix(1.0, 1.5, far)) * strength;\n"
            + "  return half4(c, 1.0);\n"
            + "}\n";

    @Override
    protected void dispatchDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), b = band();
        if (dissolve <= 0f || Float.isNaN(front) || front - b >= h) {
            super.dispatchDraw(c);
            return;
        }
        long time = getDrawingTime();
        int save = c.saveLayer(0, 0, w, h, null);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != bar && v.getVisibility() == VISIBLE) drawChild(c, v, time);
        }
        if (dome == null) {
            dome = new LinearGradient(0, 0, 0, 1, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            eraser.setShader(dome);
            solid.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            solid.setColor(0xFF000000);
        }
        float ry = b / 0.3f, rx = 1.2f * w;
        float fx = Float.isNaN(touchX) ? w / 2f : touchX;
        int n = 48;
        float sw = w / n;
        for (int i = 0; i < n; i++) {
            float x0 = i * sw, x1 = (i + 1) * sw, dx = (x0 + sw / 2f - fx) / rx;
            float edge = front + ry * (1f - (float) Math.sqrt(Math.max(0f, 1f - dx * dx)));
            if (edge - b >= h) continue;
            fadeMatrix.setScale(1f, b);
            fadeMatrix.postTranslate(0, edge - b);
            dome.setLocalMatrix(fadeMatrix);
            c.drawRect(x0, Math.max(0, edge - b), x1, Math.min(h, edge), eraser);
            if (edge < h) c.drawRect(x0, Math.max(0, edge - 1.5f), x1, h, solid);
        }
        c.restoreToCount(save);
        if (bar.getVisibility() == VISIBLE) drawChild(c, bar, time);
    }

    private static Bitmap ownCopy(Bitmap src) {
        android.util.DisplayMetrics dm = SysUi.sys.getResources().getDisplayMetrics();
        int tw = Math.max(1, dm.widthPixels), th = Math.max(1, dm.heightPixels);
        float s = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
        int cw = Math.min(src.getWidth(), Math.round(tw / s)), ch = Math.min(src.getHeight(), Math.round(th / s));
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, new android.graphics.Rect(cx, cy, cx + cw, cy + ch), new android.graphics.Rect(0, 0, tw, th), p);
        return out;
    }

    private static Bitmap wallBitmap;
    private static int wallId = -1;

    static Bitmap wallpaper() {
        Bitmap b = wallBitmap;
        return b != null && !b.isRecycled() ? b : null;
    }

    private void loadWallpaper() {
        if (wallBitmap != null && !wallBitmap.isRecycled()) {
            wall.setImageBitmap(wallBitmap);
            glow.invalidate();
        }
        new Thread(() -> {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(SysUi.sys);
                int which = android.app.WallpaperManager.FLAG_LOCK;
                int id = wm.getWallpaperId(which);
                if (id <= 0) {
                    which = android.app.WallpaperManager.FLAG_SYSTEM;
                    id = wm.getWallpaperId(which);
                }
                if (id == wallId && wallBitmap != null && !wallBitmap.isRecycled()) return;
                Drawable d = wm.getDrawable(which);
                if (d == null) d = wm.getDrawable();
                Bitmap src = d instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
                if (src == null || src.isRecycled()) return;
                Bitmap b = ownCopy(src);
                if (b == null) return;
                int fid = id;
                SysUi.main.post(() -> {
                    wallBitmap = b;
                    wallId = fid;
                    wall.setImageBitmap(b);
                    glow.invalidate();
                });
            } catch (Throwable t) {
                KeyguardHook.log("lock wallpaper: " + t);
            }
        }, "bb10-lock-wall").start();
    }
    private static final int MAX_SHOWN = 20;

    private final class ListScroll extends android.widget.ScrollView {
        private float tapX, tapY;
        private int tapScroll;

        ListScroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
            setVerticalFadingEdgeEnabled(true);
            setFadingEdgeLength(Math.round(0.05f * S()));
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            int save = c.save();
            c.clipRect(-getWidth(), getScrollY(), getWidth() * 2, getScrollY() + getHeight());
            super.dispatchDraw(c);
            c.restoreToCount(save);
        }

        boolean scrolls() {
            return expandList.getVisibility() == VISIBLE && (canScrollVertically(1) || canScrollVertically(-1));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return scrolls() && super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!scrolls()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tapX = e.getX();
                    tapY = e.getY();
                    tapScroll = getScrollY();
                    break;
                case MotionEvent.ACTION_UP:
                    if (getScrollY() == tapScroll && Math.abs(e.getX() - tapX) < slop && Math.abs(e.getY() - tapY) < slop
                            && selected != null) {
                        super.onTouchEvent(e);
                        collapse(true);
                        return true;
                    }
                    break;
            }
            return super.onTouchEvent(e);
        }
    }

    private boolean downOnList;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (bedside.isOpen() && !bedsideDrag) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                track(e);
                downOnList = false;
                if (selected != null && listScroll.scrolls()) {
                    int[] at = new int[2];
                    listScroll.getLocationOnScreen(at);
                    downOnList = e.getRawX() >= at[0] && e.getRawX() < at[0] + listScroll.getWidth()
                            && e.getRawY() >= at[1] && e.getRawY() < at[1] + listScroll.getHeight();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (downOnList && Math.abs(dy) > Math.abs(dx)
                        && (dy < 0 ? listScroll.canScrollVertically(1) : listScroll.canScrollVertically(-1))) return false;
                return Math.abs(dx) > slop || Math.abs(dy) > slop;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return track(e);
    }

    private static final int NONE = 0, UP = 1;
    private int mode = NONE;
    private BedsideView bedside;
    private boolean bedsideDrag;
    private boolean reveal;

    private boolean track(MotionEvent e) {
        float H = Math.max(1, getHeight()), W = Math.max(1, getWidth());
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                touchX = e.getX();
                mode = NONE;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                bedsideDrag = bar.bedsideShown && !unlocking && e.getY() < bar.getHeight() + slop
                        && Math.abs(e.getX() - W / 2f) < W * 0.15f;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (unlocking) return true;
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (bedsideDrag) {
                    if (dy > slop || bedside.isOpen()) bedside.dragOpen(Math.max(0f, dy));
                    return true;
                }
                if (mode == NONE) {
                    if (dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                        mode = UP;
                        reveal = KeyguardHook.canDismiss();
                        if (!reveal) askPassword();
                    }
                }
                if (mode == UP) {
                    float f = Math.max(0, -dy) / H;
                    touchX = e.getX();
                    setSwipe(f, frontFor(f), 0f);
                    if (reveal) KeyguardHook.dismissAmount(Math.min(1f, f), true);
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
                if (bedsideDrag) {
                    bedsideDrag = false;
                    if (bedside.isOpen()) bedside.release(up && (dy > H * 0.3f || vy > 900));
                    mode = NONE;
                    return true;
                }
                if (mode == UP && !unlocking) {
                    if (up && (-dy > H * 0.1f || vy < -800)) unlock();
                    else snapBack();
                } else if (mode == NONE && up && !unlocking) {
                    if (selected != null) collapse(true);
                    else showHint();
                }
                mode = NONE;
                return true;
            }
        }
        return true;
    }

    private boolean asked;

    private void askPassword() {
        if (asked) return;
        asked = true;
        KeyguardHook.setHold(true);
        KeyguardHook.requestUnlock();
    }

    private void snapBack() {
        boolean a = asked;
        endReveal();
        animateDissolve(0f, 180, () -> {
            if (a) {
                asked = false;
                KeyguardHook.cancelPassword();
                postDelayed(() -> KeyguardHook.setHold(false), 350);
            }
        });
    }

    private void showHint() {
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(1800).setDuration(400).start();
    }

    private void endReveal() {
        if (reveal) KeyguardHook.dismissAmount(0f, false);
        reveal = false;
    }

    private final Runnable unlockTimeout = () -> {
        if (!unlocking) return;
        unlocking = false;
        endReveal();
        animateDissolve(0f, 180, null);
    };

    void onScreenOff() {
        if (dissolveAnim != null) dissolveAnim.cancel();
        removeCallbacks(scrollReset);
        removeCallbacks(unlockTimeout);
        removeCallbacks(revealFallback);
        scrolled = 0;
        unlocking = false;
        dragging = false;
        mode = NONE;
        asked = false;
        endReveal();
        setDissolve(0f);
        KeyguardHook.setHold(false);
    }

    void pokeActivity() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - pokedAt < 500) return;
        pokedAt = now;
        try {
            android.os.PowerManager pm = getContext().getSystemService(android.os.PowerManager.class);
            if (userActivity == null)
                userActivity = android.os.PowerManager.class.getMethod("userActivity", long.class, int.class, int.class);
            userActivity.invoke(pm, now, 2 , 0);
        } catch (Throwable t) {
            KeyguardHook.log("lock screen activity: " + t);
        }
    }

    private long pokedAt;
    private static java.lang.reflect.Method userActivity;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        pokeActivity();
        return super.dispatchTouchEvent(e);
    }

    private final Runnable revealFallback = () -> {
        if (unlocking && KeyguardHook.showing) KeyguardHook.requestUnlock();
    };

    void unlock() {
        unlock(false);
    }

    void unlock(boolean fromKey) {
        if (unlocking) return;
        long ms = fromKey && dissolve < 0.05f ? KEY_UNLOCK_MS : 220;
        if (ms == KEY_UNLOCK_MS) touchX = Float.NaN;
        unlocking = true;
        removeCallbacks(unlockTimeout);
        postDelayed(unlockTimeout, 2500);
        if (!reveal && !asked && KeyguardHook.canDismiss()) {
            reveal = true;
            animateDissolve(1f, ms, ms == KEY_UNLOCK_MS ? new android.view.animation.AccelerateDecelerateInterpolator() : null, () -> {
                KeyguardHook.dismissAmount(1f, false);
                postDelayed(revealFallback, 600);
            });
            return;
        }
        if (reveal) {
            animateDissolve(1f, 200, () -> {
                KeyguardHook.dismissAmount(1f, false);
                postDelayed(revealFallback, 600);
            });
            return;
        }
        endReveal();
        boolean ok = asked || KeyguardHook.canDismiss();
        if (!asked) {
            asked = true;
            KeyguardHook.setHold(true);
            ok = KeyguardHook.requestUnlock();
        }
        boolean fine = ok;
        animateDissolve(1f, ms, ms == KEY_UNLOCK_MS ? new android.view.animation.AccelerateDecelerateInterpolator() : null, () -> {
            KeyguardHook.setHold(false);
            if (!fine) unlockTimeout.run();
        });
    }

    private static final long KEY_UNLOCK_MS = 300;

    private void animateDissolve(float to, long ms, Runnable end) {
        animateDissolve(to, ms, null, end);
    }

    private void animateDissolve(float to, long ms, android.animation.TimeInterpolator ip, Runnable end) {
        if (dissolveAnim != null) dissolveAnim.cancel();
        float f0 = dissolve, e0 = Float.isNaN(front) ? frontFor(dissolve) : front, l0 = lift;
        float e1 = frontFor(to), l1 = liftFor(to);
        dissolveAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        dissolveAnim.setDuration(ms);
        dissolveAnim.setInterpolator(ip != null ? ip : new DecelerateInterpolator(1.4f));
        dissolveAnim.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            float v = f0 + (to - f0) * k;
            setSwipe(v, e0 + (e1 - e0) * k, l0 + (l1 - l0) * k);
            if (reveal) KeyguardHook.dismissAmount(Math.min(1f, v), true);
        });
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

    boolean handleKey(KeyEvent e) {
        pokeActivity();
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) unlock(true);
                return true;
        }
        int ch = e.getUnicodeChar(e.getMetaState());
        if (ch > 0 && !Character.isISOControl(ch)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                KeyguardHook.typeIntoPassword((char) ch);
                if (!unlocking) unlock(true);
            }
            return true;
        }
        return false;
    }

    private float scrolled;
    private final Runnable scrollReset = () -> {
        scrolled = 0;
        if (!unlocking) animateDissolve(0f, 160, null);
    };

    boolean handleGeneric(MotionEvent e) {
        pokeActivity();
        float d = 0;
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            d = Math.abs(e.getAxisValue(MotionEvent.AXIS_VSCROLL));
            if (d == 0) d = Math.abs(e.getAxisValue(MotionEvent.AXIS_SCROLL));
        } else if (e.isFromSource(android.view.InputDevice.SOURCE_TRACKBALL) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            d = Math.abs(e.getY()) / 3f;
        }
        if (d <= 0) return false;
        if (unlocking) return true;
        scrolled += d;
        setDissolve(Math.min(1f, scrolled / 2f) * 0.35f);
        removeCallbacks(scrollReset);
        if (scrolled >= 2f) unlock();
        else postDelayed(scrollReset, 400);
        return true;
    }
}
