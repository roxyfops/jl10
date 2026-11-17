package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.R;
import com.bb10.launcher.hub.HubKinds;
import com.bb10.launcher.hub.HubStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class NotifyColumn {
    private NotifyColumn() {
    }

    public static final class Entry {
        public final String key;
        public final Icon small;
        public int count;

        Entry(String key, Icon small, int count) {
            this.key = key;
            this.small = small;
            this.count = count;
        }
    }

    public static List<Entry> entries() {
        LinkedHashMap<String, Entry> out = new LinkedHashMap<>();
        LauncherApp app = LauncherApp.get();
        HubStore hub = app.hub;
        try {
            for (String g : hub.unreadGroups()) out.put(g, new Entry(g, hub.smallIcon(g), hub.unread(g)));
        } catch (Exception ignored) {
        }
        try {
            List<String> listed = hub.unreadPackages();
            for (android.service.notification.StatusBarNotification n : com.bb10.launcher.hub.HubListenerService.newsNow()) {
                String pkg = n.getPackageName();
                if (listed.contains(pkg)) continue;
                String g = hub.group(pkg);
                Entry e = out.get(g);
                if (e == null) out.put(g, new Entry(g, n.getNotification().getSmallIcon(), 1));
                else e.count++;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out.values());
    }

    public static Drawable glyph(Context c, Entry e) {
        int res = HubKinds.NOTIF.equals(e.key) ? R.drawable.ic_bell : HubKinds.CALLS.equals(e.key) ? R.drawable.ic_phone
                : HubStore.isMailPkg(e.key) ? R.drawable.ic_mail : 0;
        if (res != 0) return Ui.glyph(c, res, 0xFFFFFFFF);
        Drawable d = null;
        try {
            if (e.small != null) d = e.small.loadDrawable(c);
            if (d != null) {
                d = d.mutate();
                d.setTint(0xFFFFFFFF);
            } else d = LauncherApp.get().iconForPackage(e.key);
        } catch (Exception ignored) {
        }
        return d;
    }

    public static LinearLayout build(Context c, List<Entry> entries, boolean top, int topPad) {
        float S = Ui.S();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(top ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        if (top) col.setPadding(0, topPad, 0, 0);
        int shown = 0;
        for (Entry e : entries) {
            if (shown >= 5) break;
            Drawable d = glyph(c, e);
            if (d == null) continue;
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int vp = Math.round(0.012f * S);
            cell.setPadding(0, vp, 0, vp);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(d);
            int is = Math.round(0.065f * S);
            cell.addView(iv, new LinearLayout.LayoutParams(is, is));
            TextView n = new TextView(c);
            n.setTextColor(0xFFFFFFFF);
            n.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 0.042f * S);
            n.setTypeface(Ui.light());
            n.setShadowLayer(Ui.dp(3), 0, 0, 0x99000000);
            n.setText(String.valueOf(e.count));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Math.round(0.006f * S);
            cell.addView(n, tl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = shown == 0 ? 0 : Math.round(0.004f * S);
            col.addView(cell, cl);
            shown++;
        }
        return col;
    }
}
