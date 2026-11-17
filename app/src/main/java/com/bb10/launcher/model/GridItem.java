package com.bb10.launcher.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GridItem {
    public static final int APP = 0, FOLDER = 1;

    public int type;
    public String app;
    public String name;
    public final List<String> apps = new ArrayList<>();

    public static GridItem app(String key) {
        GridItem g = new GridItem();
        g.type = APP;
        g.app = key;
        return g;
    }

    public static GridItem folder(String name) {
        GridItem g = new GridItem();
        g.type = FOLDER;
        g.name = name;
        return g;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (type == APP) {
            o.put("t", "app");
            o.put("c", app);
        } else {
            o.put("t", "folder");
            o.put("n", name);
            JSONArray a = new JSONArray();
            for (String s : apps) a.put(s);
            o.put("apps", a);
        }
        return o;
    }

    static GridItem fromJson(JSONObject o) {
        if ("folder".equals(o.optString("t"))) {
            GridItem g = folder(o.optString("n", "Folder"));
            JSONArray a = o.optJSONArray("apps");
            if (a != null) for (int i = 0; i < a.length(); i++) g.apps.add(a.optString(i));
            return g;
        }
        return app(o.optString("c"));
    }
}
