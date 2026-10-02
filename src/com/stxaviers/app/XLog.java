package com.stxaviers.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * XLOG (v1.1.8, owner spec 2026-09-30) — the app's rolling activity log.
 *
 * What the owner asked for, and what this delivers:
 *  - a log of the app's work that keeps the LAST HOUR only and updates
 *    continuously as the app works (every interesting event appends a line);
 *  - the file is latestlog.txt and it ATTACHES ITSELF to every bug report,
 *    so developers see exactly what the app was doing;
 *  - every role (student / teacher / admin / developer) gets a "Latest log"
 *    option in the Profile tab: disable logging, view the log, download
 *    latestlog.txt.
 *
 * Design notes:
 *  - NEVER crashes the app: every entry point is wrapped; logging failures
 *    are swallowed silently (a broken logger must not break the school app).
 *  - All writes happen on ONE background thread (serialized, ordered).
 *  - The 1-hour window is enforced by a trim pass at most once a minute:
 *    lines whose timestamp is older than 60 minutes are dropped.
 *  - PRIVACY: log lines carry NO secrets — no cookies, no tokens, no message
 *    bodies; only event names, HTTP methods/paths/status codes, sizes and
 *    durations. The file lives in the app's private storage and leaves the
 *    device only inside a bug report the user themselves sends.
 *  - "Disable logging" is a hard off: when disabled nothing is written and
 *    the existing file is deleted.
 */
public final class XLog {

    private static final String PREFS = "xd_log";
    private static final String KEY_ENABLED = "enabled";
    private static final long WINDOW_MS = 60L * 60 * 1000;   // one hour
    private static final long TRIM_EVERY_MS = 60L * 1000;    // at most 1 pass/min
    private static final long HARD_CAP_BYTES = 2L * 1024 * 1024; // safety valve

    private static File dir;
    private static SharedPreferences prefs;
    private static final ExecutorService io =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "xd-log");
                t.setDaemon(true);
                return t;
            });
    private static volatile long lastTrim;
    private static volatile boolean booted;

    private XLog() {}

    /** Idempotent — called from XdActivity.onCreate (every screen). */
    public static void init(Context c) {
        try {
            if (booted && dir != null) return;
            synchronized (XLog.class) {
                if (dir == null) {
                    dir = new File(c.getFilesDir(), "logs");
                    prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    booted = true;
                    if (!enabled()) {
                        File f = file();
                        if (f.exists()) // no stale logs while disabled
                            f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean enabled() {
        try {
            return prefs == null || prefs.getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** true by default. Turning it off also deletes the current file. */
    public static void setEnabled(Context c, boolean on) {
        try {
            init(c);
            prefs.edit().putBoolean(KEY_ENABLED, on).apply();
            if (!on) {
                final File f = file();
                enqueue(() -> { try { if (f.exists()) f.delete(); } catch (Throwable ignored) {} });
            } else {
                i("log", "logging enabled");
            }
        } catch (Throwable ignored) {}
    }

    public static File file() {
        return dir == null ? null : new File(dir, "latestlog.txt");
    }

    // ── public log entry points ─────────────────────────────────────────

    public static void i(String tag, String msg) { append("I", tag, msg); }
    public static void w(String tag, String msg) { append("W", tag, msg); }
    public static void e(String tag, String msg) { append("E", tag, msg); }

    public static void e(String tag, Throwable t) {
        String m = t == null ? "null" : String.valueOf(t);
        try {
            StackTraceElement[] st = t.getStackTrace();
            if (st != null && st.length > 0)
                m = t + " @ " + st[0];
        } catch (Throwable ignored) {}
        append("E", tag, m);
    }

    /** Network line: "GET /api/quota -> 200 (143ms, 412B)" — no bodies. */
    public static void net(String method, String path, int code, long ms,
                           long bytes) {
        append("N", "net", method + " " + path + " -> " + code
                + " (" + ms + "ms" + (bytes >= 0 ? ", " + bytes + "B" : "") + ")");
    }

    /** The whole current log ("" when empty / disabled / broken). */
    public static String read() {
        try {
            File f = file();
            if (f == null || !f.exists()) return "";
            StringBuilder sb = new StringBuilder();
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(sc.nextLine());
            }
            sc.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Approximate byte size (for the Profile row's caption). */
    public static long size() {
        try {
            File f = file();
            return f != null && f.exists() ? f.length() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean hasContent() {
        return size() > 0;
    }

    // ── internals ───────────────────────────────────────────────────────

    private static void append(String level, String tag, String msg) {
        try {
            if (dir == null || !enabled()) return;
            final String line = stamp() + " " + level + " "
                    + safe(tag) + " " + safe(msg);
            enqueue(() -> writeLine(line));
        } catch (Throwable ignored) {}
    }

    private static String safe(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    private static String stamp() {
        // 2026-09-30 14:05:06.789 — local time, like a real logcat line
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US).format(new Date());
    }

    private interface Job { void run(); }

    private static void enqueue(Job j) {
        try {
            io.execute(() -> {
                try {
                    j.run();
                } catch (Throwable ignored) {}
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private static void writeLine(String line) {
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = file();
            long now = System.currentTimeMillis();
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), "UTF-8");
            w.write(line);
            w.write('\n');
            w.close();
            if (now - lastTrim > TRIM_EVERY_MS
                    && f.length() > 4096) {
                lastTrim = now;
                trim(f, now);
            }
        } catch (Throwable ignored) {}
    }

    /** Drop lines older than the 1-hour window (and enforce the hard cap). */
    private static void trim(File f, long now) {
        try {
            List<String> keep = new ArrayList<>();
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String l = sc.nextLine();
                if (parseTs(l) >= now - WINDOW_MS) keep.add(l);
            }
            sc.close();
            // hard cap keeps the newest lines only
            while (!keep.isEmpty() && sizeOf(keep) > HARD_CAP_BYTES)
                keep.remove(0);
            File tmp = new File(dir, "latestlog.tmp");
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp), "UTF-8");
            for (String l : keep) {
                w.write(l);
                w.write('\n');
            }
            w.close();
            if (!tmp.renameTo(f)) {
                // fall back to a plain rewrite
                OutputStreamWriter w2 = new OutputStreamWriter(
                        new FileOutputStream(f), "UTF-8");
                for (String l : keep) {
                    w2.write(l);
                    w2.write('\n');
                }
                w2.close();
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static long sizeOf(List<String> lines) {
        long n = 0;
        for (String l : lines) n += l.length() + 1;
        return n;
    }

    /** Parse "yyyy-MM-dd HH:mm:ss.SSS" (local) from the start of a line. */
    private static long parseTs(String line) {
        try {
            String d = line.length() >= 23 ? line.substring(0, 23) : line;
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US).parse(d).getTime();
        } catch (Throwable t) {
            return Long.MAX_VALUE;   // unparseable = keep it
        }
    }
}
