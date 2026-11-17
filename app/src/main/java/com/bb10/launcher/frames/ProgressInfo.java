package com.bb10.launcher.frames;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ProgressInfo {
    public int progress, max = 100;
    public boolean indeterminate, styledByProgress = true;
    public int[] segLength = new int[0], segColor = new int[0];
    public int[] pointPos = new int[0], pointColor = new int[0];
    public Icon tracker, start, end;
    public boolean styled;

    private static final Map<Notification, ProgressInfo> cache = new WeakHashMap<>();
    private static final ProgressInfo NONE = new ProgressInfo();

    public static synchronized ProgressInfo of(Context ctx, Notification n) {
        ProgressInfo p = cache.get(n);
        if (p == null) {
            p = read(ctx, n);
            cache.put(n, p == null ? NONE : p);
        }
        return p == NONE ? null : p;
    }

    private static ProgressInfo read(Context ctx, Notification n) {
        Bundle ex = n.extras;
        String tpl = ex.getString(Notification.EXTRA_TEMPLATE);
        if (tpl != null && tpl.endsWith("$ProgressStyle")) {
            ProgressInfo p = new ProgressInfo();
            p.styled = true;
            if (!fromStyle(ctx, n, p)) fromExtras(ex, p);
            int sum = 0;
            for (int l : p.segLength) sum += l;
            if (sum > 0) p.max = sum;
            if (p.segLength.length == 0) {
                p.segLength = new int[]{p.max};
                p.segColor = new int[]{0};
            }
            return p;
        }
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean ind = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if (max <= 0 && !ind) return null;
        ProgressInfo p = new ProgressInfo();
        p.max = Math.max(1, max);
        p.progress = ex.getInt(Notification.EXTRA_PROGRESS, 0);
        p.indeterminate = ind;
        p.segLength = new int[]{p.max};
        p.segColor = new int[]{0};
        return p;
    }

    private static boolean fromStyle(Context ctx, Notification n, ProgressInfo p) {
        try {
            Notification.Style st = Notification.Builder.recoverBuilder(ctx, n).getStyle();
            if (st == null || !st.getClass().getName().endsWith("$ProgressStyle")) return false;
            Integer prog = call(st, "getProgress");
            if (prog != null) p.progress = prog;
            Boolean ind = call(st, "isProgressIndeterminate");
            if (ind != null) p.indeterminate = ind;
            Boolean sbp = call(st, "isStyledByProgress");
            if (sbp != null) p.styledByProgress = sbp;
            p.tracker = call(st, "getProgressTrackerIcon");
            p.start = call(st, "getProgressStartIcon");
            p.end = call(st, "getProgressEndIcon");
            List<?> segs = call(st, "getProgressSegments");
            if (segs != null) {
                p.segLength = new int[segs.size()];
                p.segColor = new int[segs.size()];
                for (int i = 0; i < segs.size(); i++) {
                    Integer l = call(segs.get(i), "getLength"), c = call(segs.get(i), "getColor");
                    p.segLength[i] = l == null ? 0 : l;
                    p.segColor[i] = c == null ? 0 : c;
                }
            }
            List<?> pts = call(st, "getProgressPoints");
            if (pts != null) {
                p.pointPos = new int[pts.size()];
                p.pointColor = new int[pts.size()];
                for (int i = 0; i < pts.size(); i++) {
                    Integer pos = call(pts.get(i), "getPosition"), c = call(pts.get(i), "getColor");
                    p.pointPos[i] = pos == null ? 0 : pos;
                    p.pointColor[i] = c == null ? 0 : c;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fromExtras(Bundle ex, ProgressInfo p) {
        p.progress = ex.getInt("android.progress", 0);
        p.indeterminate = ex.getBoolean("android.progressIndeterminate", false);
        p.styledByProgress = ex.getBoolean("android.styledByProgress", true);
        p.tracker = icon(ex, "android.progressTrackerIcon");
        p.start = icon(ex, "android.progressStartIcon");
        p.end = icon(ex, "android.progressEndIcon");
        List<Bundle> segs = bundles(ex, "android.progressSegments");
        p.segLength = new int[segs.size()];
        p.segColor = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            p.segLength[i] = segs.get(i).getInt("length", 0);
            p.segColor[i] = segs.get(i).getInt("color", 0);
        }
        List<Bundle> pts = bundles(ex, "android.progressPoints");
        p.pointPos = new int[pts.size()];
        p.pointColor = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            p.pointPos[i] = pts.get(i).getInt("position", 0);
            p.pointColor[i] = pts.get(i).getInt("color", 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T call(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            return (T) m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Icon icon(Bundle ex, String k) {
        Object o = ex.get(k);
        return o instanceof Icon ? (Icon) o : null;
    }

    @SuppressWarnings("deprecation")
    private static List<Bundle> bundles(Bundle ex, String k) {
        List<Bundle> out = new ArrayList<>();
        Object o = ex.get(k);
        if (o instanceof List) {
            for (Object b : (List<?>) o) if (b instanceof Bundle) out.add((Bundle) b);
        } else if (o instanceof Parcelable[]) {
            for (Parcelable b : (Parcelable[]) o) if (b instanceof Bundle) out.add((Bundle) b);
        }
        return out;
    }
}
