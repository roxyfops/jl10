package com.bb10.launcher.hub;

import org.json.JSONException;
import org.json.JSONObject;

public class HubItem {
    public String id;
    public String key;
    public String pkg;
    public String title = "";
    public String text = "";
    public String sub = "";
    public long time;
    public boolean read;
    public boolean active;
    public boolean persistent;

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("key", key);
        o.put("pkg", pkg);
        o.put("title", title);
        o.put("text", text);
        o.put("sub", sub);
        o.put("time", time);
        o.put("read", read);
        o.put("active", active);
        o.put("persistent", persistent);
        return o;
    }

    static HubItem fromJson(JSONObject o) {
        HubItem h = new HubItem();
        h.id = o.optString("id");
        h.key = o.optString("key");
        h.pkg = o.optString("pkg");
        h.title = o.optString("title");
        h.text = o.optString("text");
        h.sub = o.optString("sub");
        h.time = o.optLong("time");
        h.read = o.optBoolean("read");
        h.active = o.optBoolean("active");
        h.persistent = o.optBoolean("persistent");
        return h;
    }
}
