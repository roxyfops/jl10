package com.bb10.launcher.record;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.widget.Toast;

import com.bb10.launcher.R;

public class ScreenRecorder extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "com.bb10.launcher.record.STOP";
    private static final String CHANNEL = "recording";
    private static final int NOTE = 7310;

    private static volatile boolean recording;

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor out;
    private Uri file;
    private final Handler h = new Handler(Looper.getMainLooper());

    public static boolean isRecording() {
        return recording;
    }

    public static void toggle(Context c) {
        if (recording) {
            c.startService(new Intent(c, ScreenRecorder.class).setAction(ACTION_STOP));
        } else {
            c.startActivity(new Intent(c, RecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || ACTION_STOP.equals(i.getAction())) {
            finish(true);
            return START_NOT_STICKY;
        }
        if (recording) return START_NOT_STICKY;
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        try {
            int code = i.getIntExtra(EXTRA_CODE, 0);
            Intent data = i.getParcelableExtra(EXTRA_DATA, Intent.class);
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    h.post(() -> finish(true));
                }
            }, h);
            start();
            recording = true;
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show();
            finish(false);
        }
        return START_NOT_STICKY;
    }

    private void start() throws Exception {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = Math.min(1f, 1080f / Math.max(dm.widthPixels, dm.heightPixels));
        int w = Math.round(dm.widthPixels * k) & ~1, hgt = Math.round(dm.heightPixels * k) & ~1;

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "BB10_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BB10");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        file = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (file == null) throw new IllegalStateException("no file");
        out = getContentResolver().openFileDescriptor(file, "rw");

        recorder = new MediaRecorder(this);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(w, hgt);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(6_000_000);
        recorder.setOutputFile(out.getFileDescriptor());
        recorder.prepare();
        display = projection.createVirtualDisplay("BB10 screen recording", w, hgt, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
        recorder.start();
    }

    private void finish(boolean keep) {
        boolean was = recording;
        recording = false;
        boolean saved = false;
        try {
            if (recorder != null) {
                recorder.stop();
                saved = was;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (recorder != null) recorder.release();
        } catch (Throwable ignored) {
        }
        recorder = null;
        if (display != null) display.release();
        display = null;
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }
        projection = null;
        try {
            if (out != null) out.close();
        } catch (Throwable ignored) {
        }
        out = null;
        if (file != null) {
            try {
                if (keep && saved) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(file, v, null, null);
                    Toast.makeText(this, "Recording saved to Movies/BB10", Toast.LENGTH_SHORT).show();
                } else {
                    getContentResolver().delete(file, null, null);
                }
            } catch (Throwable ignored) {
            }
        }
        file = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification note() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ScreenRecorder.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jl10)
                .setContentTitle("Recording the screen")
                .setContentText("Tap to stop")
                .setContentIntent(stop)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (recording) finish(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
