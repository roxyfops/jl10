package com.bb10.launcher.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class HubKit {
    private HubKit() {}

    public static final class Pal {
        public boolean dark;
        public int bg, header, title, sub, icon, divider, dayBg, dayText, name, nameDim, text, time, bar, barIcon, barText, field;
    }

    public static Pal palette(Context c) {
        String theme = null;
        try {
            theme = Settings.Global.getString(c.getContentResolver(), "bb10_hub_theme");
        } catch (Exception ignored) {
        }
        return palette("dark".equals(theme));
    }

    public static Pal palette(boolean dark) {
        Pal p = new Pal();
        if (dark) {
            p.dark = true;
            p.bg = 0xFF141414; p.header = 0xFF1E1E1E; p.title = Ui.TEXT; p.sub = Ui.TEXT2; p.icon = 0xFFBDBDBD;
            p.divider = 0xFF2A2A2A; p.dayBg = 0xFF1B1B1B; p.dayText = Ui.TEXT2; p.name = Ui.TEXT; p.nameDim = 0xFF8A8A8A;
            p.text = Ui.TEXT2; p.time = Ui.TEXT2; p.bar = 0xFF1A1A1A; p.barIcon = Ui.TEXT; p.barText = Ui.TEXT2;
            p.field = 0xFF262626;
        } else {
            p.bg = 0xFFFCFCFC; p.header = 0xFFFFFFFF; p.title = 0xFF1A1A1A; p.sub = 0xFF7A7A7A; p.icon = 0xFF4E4E4E;
            p.divider = 0xFFDEDEDE; p.dayBg = 0xFFEEEEEE; p.dayText = 0xFF6E6E6E; p.name = 0xFF1E1E1E; p.nameDim = 0xFF9A9A9A;
            p.text = 0xFF8C8C8C; p.time = 0xFF8C8C8C; p.bar = 0xFFF3F3F3; p.barIcon = 0xFF444444; p.barText = 0xFF666666;
            p.field = 0xFFEFEFEF;
        }
        return p;
    }

    @SuppressWarnings("deprecation")
    public static void applyWindow(Activity a, Pal p) {
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(p.bg));
        a.getWindow().setStatusBarColor(p.header);
        a.getWindow().setNavigationBarColor(p.bar);
        a.getWindow().getDecorView();
        WindowInsetsController ic = a.getWindow().getInsetsController();
        if (ic != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(p.dark ? 0 : light, light);
        }
    }

    public static TextView text(Context c, String s, float sizeS, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeS * Ui.S());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static View divider(Context c, Pal p) {
        View v = new View(c);
        v.setBackgroundColor(p.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    public static final class Header {
        public final LinearLayout view;
        public final TextView title, sub;
        private final Pal p;

        Header(Context c, Pal p, String t) {
            this.p = p;
            float S = Ui.S();
            view = new LinearLayout(c);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(Math.round(0.035f * S), 0, 0, 0);
            view.setBackgroundColor(p.header);
            LinearLayout titles = new LinearLayout(c);
            titles.setOrientation(LinearLayout.VERTICAL);
            title = text(c, t, 0.052f, p.title, false);
            sub = text(c, "", 0.03f, p.sub, false);
            sub.setPadding(0, Math.round(0.004f * S), 0, 0);
            titles.addView(title);
            titles.addView(sub);
            view.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.135f * S)));
        }

        public ImageView button(int icon, String desc, View.OnClickListener l) {
            Context c = view.getContext();
            float S = Ui.S();
            if (view.getChildCount() > 1) {
                View sep = new View(c);
                sep.setBackgroundColor(p.divider);
                view.addView(sep, new LinearLayout.LayoutParams(Math.max(1, Ui.dp(1)), Math.round(0.07f * S)));
            }
            ImageView b = new ImageView(c);
            b.setImageDrawable(Ui.glyph(c, icon, p.icon));
            int pad = Math.round(0.03f * S);
            b.setPadding(pad, pad, pad, pad);
            b.setBackground(Ui.selectable(0));
            b.setContentDescription(desc);
            b.setOnClickListener(l);
            int hb = Math.round(0.11f * S);
            view.addView(b, new LinearLayout.LayoutParams(hb, hb));
            return b;
        }
    }

    public static Header header(Context c, Pal p, String title) {
        return new Header(c, p, title);
    }

    public static LinearLayout fieldBar(Context c, Pal p, int icon, EditText field, String hint) {
        float S = Ui.S();
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.field);
        bar.setPadding(Math.round(0.03f * S), 0, Math.round(0.015f * S), 0);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(Ui.glyph(c, icon, p.sub));
        int is = Math.round(0.042f * S);
        bar.addView(iv, new LinearLayout.LayoutParams(is, is));
        field.setBackground(null);
        field.setHint(hint);
        field.setHintTextColor(p.sub);
        field.setTextColor(p.title);
        field.setTypeface(Ui.regular());
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.038f * S);
        field.setSingleLine();
        field.setPadding(Math.round(0.02f * S), 0, Math.round(0.02f * S), 0);
        bar.addView(field, new LinearLayout.LayoutParams(0, Math.round(0.095f * S), 1f));
        return bar;
    }

    public static TextView dayView(Context c, Pal p) {
        float S = Ui.S();
        TextView t = text(c, "", 0.03f, p.dayText, false);
        t.setBackgroundColor(p.dayBg);
        int pad = Math.round(0.035f * S);
        t.setPadding(pad, Math.round(0.014f * S), pad, Math.round(0.014f * S));
        return t;
    }

    public static final float BAR_H = 0.125f;

    public static final class Screen {
        public final FrameLayout root;
        public final LinearLayout column, bar;
        private final Pal p;

        Screen(Context c, Pal p) {
            this.p = p;
            root = new FrameLayout(c);
            root.setBackgroundColor(p.bg);
            column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setFitsSystemWindows(true);
            root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bar = new LinearLayout(c);
            bar.setBackgroundColor(p.bar);
        }

        public void finish(View round) {
            float S = Ui.S();
            column.addView(divider(column.getContext(), p));
            column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(BAR_H * S)));
            if (round != null) {
                int cs = Math.round(0.135f * S);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(cs, cs, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                cl.bottomMargin = Math.round(BAR_H * S * 0.42f);
                root.addView(round, cl);
                round.setOnApplyWindowInsetsListener((v, in) -> {
                    int nav = in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    ((FrameLayout.LayoutParams) v.getLayoutParams()).bottomMargin = Math.round(BAR_H * S * 0.42f) + nav;
                    return in;
                });
            }
        }

        public LinearLayout barButton(int icon, String label, float weight, View.OnClickListener l) {
            Context c = bar.getContext();
            float S = Ui.S();
            LinearLayout b = new LinearLayout(c);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.selectable(0));
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(Ui.glyph(c, icon, p.barIcon));
            int is = Math.round(0.05f * S);
            b.addView(iv, new LinearLayout.LayoutParams(is, is));
            if (label != null) {
                TextView t = text(c, label, 0.026f, p.barText, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(0.008f * S);
                b.addView(t, lp);
            }
            b.setContentDescription(label);
            b.setOnClickListener(l);
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
            return b;
        }

        public void roundSlot(String label, View.OnClickListener l) {
            Context c = bar.getContext();
            LinearLayout slot = new LinearLayout(c);
            slot.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            slot.setPadding(0, 0, 0, Math.round(0.012f * Ui.S()));
            slot.addView(text(c, label, 0.026f, p.barText, false));
            slot.setOnClickListener(l);
            bar.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        }
    }

    public static Screen screen(Context c, Pal p) {
        return new Screen(c, p);
    }

    public static ImageView roundButton(Context c, int icon, String desc, View.OnClickListener l) {
        ImageView b = new ImageView(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF1E9FD8);
        b.setBackground(circle);
        b.setImageDrawable(Ui.glyph(c, icon, 0xFFFFFFFF));
        int cp = Math.round(0.034f * Ui.S());
        b.setPadding(cp, cp, cp, cp);
        b.setElevation(Ui.dp(6));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    public static class Row extends LinearLayout {
        public final FrameLayout picture;
        public final TextView title, text, time, flag;
        public final View strip;

        public Row(Context c, Pal p) {
            super(c);
            float S = Ui.S();
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            picture = new FrameLayout(c);
            int is = Math.round(0.072f * S);
            LayoutParams il = new LayoutParams(is, is);
            il.leftMargin = Math.round(0.03f * S);
            il.rightMargin = Math.round(0.03f * S);
            addView(picture, il);
            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(VERTICAL);
            title = text(c, "", 0.046f, p.name, false);
            mid.addView(title);
            text = text(c, "", 0.033f, p.text, false);
            text.setPadding(0, Math.round(0.006f * S), 0, 0);
            mid.addView(text);
            LayoutParams ml = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.topMargin = ml.bottomMargin = Math.round(0.022f * S);
            addView(mid, ml);
            LinearLayout side = new LinearLayout(c);
            side.setOrientation(VERTICAL);
            side.setGravity(Gravity.END);
            time = text(c, "", 0.028f, p.time, false);
            side.addView(time);
            flag = text(c, "!", 0.04f, 0xFFE53935, true);
            flag.setPadding(0, Math.round(0.004f * S), 0, 0);
            flag.setVisibility(GONE);
            side.addView(flag);
            LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = Math.round(0.02f * S);
            sl.rightMargin = Math.round(0.025f * S);
            sl.gravity = Gravity.TOP;
            sl.topMargin = Math.round(0.022f * S);
            addView(side, sl);
            strip = new View(c);
            addView(strip, new LayoutParams(Math.max(3, Math.round(0.008f * S)), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        public ImageView glyph(int res, int color) {
            ImageView iv;
            if (picture.getChildCount() > 0 && picture.getChildAt(0) instanceof ImageView) iv = (ImageView) picture.getChildAt(0);
            else {
                picture.removeAllViews();
                iv = new ImageView(getContext());
                int pad = Math.round(0.012f * Ui.S());
                iv.setPadding(pad, pad, pad, pad);
                picture.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            iv.setImageDrawable(Ui.glyph(getContext(), res, color));
            return iv;
        }
    }

    public static String dayLabel(long t) {
        return android.text.format.DateFormat.format("EEEE, MMMM d, yyyy", t).toString();
    }

    public static String rowTime(Context c, long t) {
        boolean h24 = android.text.format.DateFormat.is24HourFormat(c);
        return android.text.format.DateUtils.isToday(t)
                ? android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", t).toString()
                : android.text.format.DateFormat.format("MMM d", t).toString();
    }
}
