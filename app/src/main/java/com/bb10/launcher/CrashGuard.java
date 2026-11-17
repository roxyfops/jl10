package com.bb10.launcher;

import android.content.Context;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashGuard {
    private CrashGuard() {}

    private static final String LOG = "crashes.txt", TIMES = "crash_times";
    private static final long WINDOW_MS = 3 * 60_000L;
    private static final int LIMIT = 3;

    private static File dir(Context c) {
        return c.createDeviceProtectedStorageContext().getFilesDir();
    }

    public static void install(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                record(app, t, e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void record(Context c, Thread t, Throwable e) throws Exception {
        File d = dir(c);
        long now = System.currentTimeMillis();
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now))
                + " (thread " + t.getName() + ", " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE + ")\n" + sw + "\n";
        File log = new File(d, LOG);
        String old = log.exists() ? new String(Files.readAllBytes(log.toPath())) : "";
        String all = old + entry;
        if (all.length() > 24_000) all = all.substring(all.length() - 24_000);
        Files.write(log.toPath(), all.getBytes());
        File times = new File(d, TIMES);
        String ts = times.exists() ? new String(Files.readAllBytes(times.toPath())) : "";
        Files.write(times.toPath(), (ts + now + "\n").getBytes());
    }

    static boolean crashLooping(Context c) {
        try {
            File times = new File(dir(c), TIMES);
            if (!times.exists()) return false;
            long now = System.currentTimeMillis();
            int n = 0;
            for (String s : new String(Files.readAllBytes(times.toPath())).split("\n")) {
                try {
                    if (now - Long.parseLong(s.trim()) < WINDOW_MS) n++;
                } catch (NumberFormatException ignored) {
                }
            }
            if (n < LIMIT) return false;
            times.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String log(Context c) {
        try {
            File log = new File(dir(c), LOG);
            return log.exists() ? new String(Files.readAllBytes(log.toPath())) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
