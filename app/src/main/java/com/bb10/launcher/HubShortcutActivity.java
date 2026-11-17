package com.bb10.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class HubShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, LauncherActivity.class)
                .setAction(LauncherActivity.ACTION_SHOW)
                .putExtra(LauncherActivity.EXTRA_PAGE, "hub")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(0, 0);
        finish();
    }
}
