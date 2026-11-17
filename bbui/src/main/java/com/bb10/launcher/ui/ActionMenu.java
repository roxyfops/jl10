package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class ActionMenu extends FrameLayout implements Closable {
    public static class Item {
        final Drawable icon;
        final String label;
        final Runnable run;
        final String hint;

        public Item(Drawable icon, String label, Runnable run) {
            this(icon, label, null, run);
        }

        public Item(Drawable icon, String label, String hint, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.hint = hint;
            this.run = run;
        }
    }

    private final ViewGroup host;
    private final LinearLayout panel;
    private final View dim;
    private final View shadow;
    private boolean closing;

    public static ActionMenu show(ViewGroup host, String title, Drawable titleIcon, List<Item> items) {
        ActionMenu m = new ActionMenu(host.getContext(), host, title, titleIcon, items);
        host.addView(m, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return m;
    }

    private ActionMenu(Context c, ViewGroup host, String title, Drawable titleIcon, List<Item> items) {
        super(c);
        this.host = host;
        dim = new View(c);
        dim.setOnClickListener(v -> close());
        addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF000000);
        panel.setClickable(true);
        int w = Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.68f);
        shadow = new View(c);
        shadow.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00000000, 0x30000000, 0x70000000}));

        if (title != null) {
            LinearLayout hd = new LinearLayout(c);
            hd.setOrientation(LinearLayout.HORIZONTAL);
            hd.setGravity(Gravity.CENTER_VERTICAL);
            hd.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            hd.setBackgroundColor(0xFF1A1A1A);
            if (titleIcon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(titleIcon);
                hd.addView(iv, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
            }
            TextView tv = Ui.text(c, title, 14, Ui.TEXT, true);
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = titleIcon != null ? Ui.dp(10) : 0;
            hd.addView(tv, lp);
            panel.addView(hd);
            View line = new View(c);
            line.setBackgroundColor(Ui.BLUE);
            panel.addView(line, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(2)));
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        View first = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            View row = row(c, it);
            if (first == null) first = row;
            list.addView(row, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(72)));
            if (i < items.size() - 1) {
                View d = new View(c);
                d.setBackgroundColor(0xFF121212);
                list.addView(d, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(1))));
            }
        }
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(panel, new LayoutParams(w, LayoutParams.MATCH_PARENT, Gravity.END));
        LayoutParams sl = new LayoutParams(Ui.dp(7), LayoutParams.MATCH_PARENT, Gravity.END);
        sl.rightMargin = w;
        addView(shadow, sl);

        panel.setTranslationX(w);
        shadow.setTranslationX(w);
        panel.animate().translationX(0).setDuration(180).setUpdateListener(a -> shadow.setTranslationX(panel.getTranslationX())).start();
        View f = first;
        if (f != null) post(f::requestFocus);
    }

    private View row(Context c, Item it) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.selectable(0));
        box.setFocusable(true);
        box.setClickable(true);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(20), 0, Ui.dp(16), 0);
        ImageView iv = new ImageView(c);
        if (it.icon != null) iv.setImageDrawable(it.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
        TextView tv = Ui.text(c, it.label, 20, Ui.TEXT, false);
        tv.setSingleLine();
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(27);
        r.addView(tv, lp);
        box.addView(r, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (it.hint != null) {
            TextView h = Ui.text(c, it.hint, 9, 0xFF6E6E6E, false);
            LayoutParams hl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            hl.topMargin = Ui.dp(5);
            hl.rightMargin = Ui.dp(6);
            box.addView(h, hl);
        }
        box.setOnClickListener(v -> {
            closeNow();
            if (it.run != null) it.run.run();
        });
        return box;
    }

    private void closeNow() {
        closing = true;
        host.removeView(this);
    }

    @Override
    public void close() {
        if (closing) return;
        closing = true;
        panel.animate().translationX(panel.getWidth()).setDuration(140).setUpdateListener(a -> shadow.setTranslationX(panel.getTranslationX())).withEndAction(() -> host.removeView(this)).start();
    }
}
