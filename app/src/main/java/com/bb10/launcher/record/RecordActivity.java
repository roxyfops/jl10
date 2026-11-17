package com.bb10.launcher.record;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class RecordActivity extends Activity {
    private static final int ASK = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), ASK);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == ASK && result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, ScreenRecorder.class)
                    .putExtra(ScreenRecorder.EXTRA_CODE, result)
                    .putExtra(ScreenRecorder.EXTRA_DATA, data));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
