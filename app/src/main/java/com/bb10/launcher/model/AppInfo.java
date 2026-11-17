package com.bb10.launcher.model;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;

import com.bb10.launcher.LauncherApp;

public class AppInfo {
    public final LauncherActivityInfo info;
    public final ComponentName cn;
    public final String key;
    public final String pkg;
    public final String originalLabel;

    AppInfo(LauncherActivityInfo i) {
        info = i;
        cn = i.getComponentName();
        key = cn.flattenToString();
        pkg = cn.getPackageName();
        originalLabel = String.valueOf(i.getLabel());
    }

    public String label() {
        String o = LauncherApp.get().prefs.label(key);
        return o != null ? o : originalLabel;
    }
}
