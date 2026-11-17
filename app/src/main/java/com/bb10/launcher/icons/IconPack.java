package com.bb10.launcher.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class IconPack {
    public final String pkg;
    public final String label;
    private Resources res;
    private final Map<String, String> map = new HashMap<>();
    private boolean loaded;

    public IconPack(String pkg, String label) {
        this.pkg = pkg;
        this.label = label;
    }

    public synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            res = c.getPackageManager().getResourcesForApplication(pkg);
        } catch (Exception e) {
            return;
        }
        InputStream is = null;
        try {
            XmlPullParser xp;
            int id = res.getIdentifier("appfilter", "xml", pkg);
            if (id != 0) {
                xp = res.getXml(id);
            } else {
                is = res.getAssets().open("appfilter.xml");
                xp = XmlPullParserFactory.newInstance().newPullParser();
                xp.setInput(is, "UTF-8");
            }
            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = xp.getName();
                    if ("item".equals(n)) {
                        String comp = xp.getAttributeValue(null, "component");
                        String dr = xp.getAttributeValue(null, "drawable");
                        if (comp != null && dr != null) map.put(normalize(comp), dr);
                    }
                }
                ev = xp.next();
            }
        } catch (Exception ignored) {
        } finally {
            if (is != null) try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }

    static String normalize(String comp) {
        String s = comp.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a + 1, b);
        int sl = s.indexOf('/');
        if (sl > 0 && sl + 1 < s.length() && s.charAt(sl + 1) == '.') {
            s = s.substring(0, sl + 1) + s.substring(0, sl) + s.substring(sl + 1);
        }
        return s;
    }

    public boolean has(ComponentName cn) {
        return map.containsKey(cn.flattenToString());
    }

    public String drawableNameFor(ComponentName cn) {
        return map.get(cn.flattenToString());
    }

    public Drawable iconFor(ComponentName cn) {
        return iconFor(cn, 0);
    }

    public Drawable iconFor(ComponentName cn, int density) {
        String d = map.get(cn.flattenToString());
        return d == null ? null : drawable(d, density);
    }

    public Drawable drawable(String name) {
        return drawable(name, 0);
    }

    public Drawable drawable(String name, int density) {
        if (res == null || name == null) return null;
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
        if (id == 0) return null;
        try {
            return density > 0 ? res.getDrawableForDensity(id, density, null) : res.getDrawable(id, null);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> allDrawables() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (res != null) {
            try {
                int id = res.getIdentifier("drawable", "xml", pkg);
                if (id != 0) {
                    XmlPullParser xp = res.getXml(id);
                    int ev = xp.getEventType();
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && "item".equals(xp.getName())) {
                            String d = xp.getAttributeValue(null, "drawable");
                            if (d != null) names.add(d);
                        }
                        ev = xp.next();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (names.isEmpty()) names.addAll(new TreeSet<>(map.values()));
        return new ArrayList<>(names);
    }
}
