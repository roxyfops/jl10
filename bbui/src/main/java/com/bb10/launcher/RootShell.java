package com.bb10.launcher;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootShell {
    private static volatile Boolean sAvailable;
    private static final long DEFAULT_TIMEOUT_MS = 45_000;

    public static class Result {
        public boolean ok;
        public boolean timedOut;
        public String out = "";
    }

    public interface Callback {
        void done(Result r);
    }

    private RootShell() {}

    public static volatile boolean held;

    public static boolean isAvailable() {
        if (held) return false;
        if (!Boolean.TRUE.equals(sAvailable)) {
            Result r = exec(30_000, "id");
            sAvailable = r.ok && r.out.contains("uid=0");
        }
        return sAvailable;
    }

    public static boolean knownAvailable() {
        return Boolean.TRUE.equals(sAvailable);
    }

    public static Result exec(String... cmds) {
        return exec(DEFAULT_TIMEOUT_MS, cmds);
    }

    public static Result exec(long timeoutMs, String... cmds) {
        Result r = new Result();
        if (held) {
            r.out = "Root not asked for yet (setup hasn't started)";
            return r;
        }
        StringBuffer out = new StringBuffer();
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            Process proc = p;
            Thread reader = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }, "su-reader");
            reader.setDaemon(true);
            reader.start();
            Thread writer = new Thread(() -> {
                try (OutputStream os = proc.getOutputStream()) {
                    for (String c : cmds) os.write((c + "\n").getBytes(StandardCharsets.UTF_8));
                    os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {
                }
            }, "su-writer");
            writer.setDaemon(true);
            writer.start();
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                reader.join(2000);
                r.ok = p.exitValue() == 0;
            } else {
                r.timedOut = true;
                r.ok = false;
                p.destroyForcibly();
                reader.join(1000);
                out.append("\n[timed out after ").append(timeoutMs / 1000).append(" s]");
            }
        } catch (Exception e) {
            r.ok = false;
            out.append(e.getMessage());
            if (p != null) p.destroyForcibly();
        }
        r.out = out.toString();
        return r;
    }

    public static void execAsync(Callback cb, String... cmds) {
        execAsync(DEFAULT_TIMEOUT_MS, cb, cmds);
    }

    public static void execAsync(long timeoutMs, Callback cb, String... cmds) {
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            Result r;
            if (!isAvailable()) {
                r = new Result();
                r.out = "Root not available (grant root to JL10 in KernelSU/Magisk/APatch)";
            } else {
                r = exec(timeoutMs, cmds);
            }
            if (cb != null) {
                Result fr = r;
                main.post(() -> cb.done(fr));
            }
        }, "root");
        t.setDaemon(true);
        t.start();
    }
}
