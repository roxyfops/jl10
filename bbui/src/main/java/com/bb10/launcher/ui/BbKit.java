package com.bb10.launcher.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class BbKit {
    private BbKit() {}

    public static TextView text(Context c, float sizeS, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTypeface(bold ? Ui.bold() : Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeS * Ui.S());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static View line(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    public static LinearLayout header(Context c, int icon, String title) {
        float S = Ui.S();
        LinearLayout h = new LinearLayout(c);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(0xFF1E1E1E);
        int p = Math.round(0.035f * S);
        h.setPadding(p, 0, Math.round(0.02f * S), 0);
        if (icon != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(Ui.glyph(c, icon, Ui.BLUE));
            int s = Math.round(0.05f * S);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = Math.round(0.025f * S);
            h.addView(iv, lp);
        }
        TextView t = text(c, 0.042f, Ui.TEXT, false);
        t.setText(title);
        t.setTag("title");
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.095f * S)));
        return h;
    }

    public static LinearLayout bar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setBackgroundColor(0xFF1A1A1A);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.115f * Ui.S())));
        return bar;
    }

    public static LinearLayout barButton(Context c, int icon, String label, View.OnClickListener l) {
        float S = Ui.S();
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.selectable(0));
        b.setFocusable(false);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(Ui.glyph(c, icon, Ui.TEXT));
        int is = Math.round(0.05f * S);
        b.addView(iv, new LinearLayout.LayoutParams(is, is));
        TextView t = text(c, 0.025f, Ui.TEXT2, false);
        t.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(0.008f * S);
        b.addView(t, lp);
        b.setOnClickListener(l);
        b.setContentDescription(label);
        return b;
    }

    public static void add(LinearLayout bar, View button) {
        bar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    public static String when(Context c, long t) {
        if (t <= 0) return "";
        boolean h24 = android.text.format.DateFormat.is24HourFormat(c);
        if (android.text.format.DateUtils.isToday(t))
            return android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", t).toString();
        if (android.text.format.DateUtils.isToday(t + 86_400_000L)) return "Yesterday";
        long age = System.currentTimeMillis() - t;
        if (age >= 0 && age < 6L * 86_400_000L) return android.text.format.DateFormat.format("EEE", t).toString();
        java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance();
        then.setTimeInMillis(t);
        boolean sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR);
        return android.text.format.DateFormat.format(sameYear ? "MMM d" : "MMM d, yyyy", t).toString();
    }
}
