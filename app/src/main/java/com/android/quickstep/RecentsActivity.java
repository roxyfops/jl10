package com.android.quickstep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.bb10.launcher.LauncherActivity;

public class RecentsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        com.bb10.launcher.gesture.GestureService.recentsPressed(this);
        overridePendingTransition(0, 0);
        finish();
    }
}
