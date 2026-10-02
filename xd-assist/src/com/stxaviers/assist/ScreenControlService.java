package com.stxaviers.assist;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ScreenControlService — the XD Assist accessibility engine.
 *
 * While enabled in Android Settings it polls the owner's Termux bridge
 * (GET /rig/next-cmd) and performs each command on the screen, then
 * reports the result back (POST /rig/result). Commands:
 *
 *   tap    x y                          — tap anywhere
 *   swipe  x1 y1 x2 y2 [durationMs]     — swipe gesture
 *   text   "hello"                       — type into the focused field
 *   back / home / recents / notifications — global navigation
 *   dump                                  — read the screen (every visible
 *                                           text + its tap coordinates)
 *   open   com.stxaviers.app             — launch an app by package
 *
 * The bridge URL + key come from MainActivity's saved settings, so
 * nothing works until the owner connects it.
 */
public class ScreenControlService extends AccessibilityService {

    public static volatile boolean connected = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String backendUrl, backendKey;
    private volatile boolean polling;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        backendUrl = p.getString(MainActivity.K_URL, "");
        backendKey = p.getString(MainActivity.K_KEY, "");
        startPolling();
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // event stream not used — commands arrive via the bridge poll
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        polling = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        polling = false;
        super.onDestroy();
    }

    // ── the poll loop ──────────────────────────────────────────────────

    private void startPolling() {
        if (polling) return;
        polling = true;
        new Thread(() -> {
            while (polling && connected) {
                try {
                    if (backendUrl == null || backendUrl.isEmpty()) {
                        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
                        backendUrl = p.getString(MainActivity.K_URL, "");
                        backendKey = p.getString(MainActivity.K_KEY, "");
                        if (backendUrl.isEmpty()) { Thread.sleep(3000); continue; }
                    }
                    JSONObject cmd = httpJson("GET", backendUrl + "/rig/next-cmd?timeout=10",
                            null);
                    if (cmd != null && !cmd.optBoolean("wait", false)) {
                        JSONObject result = execute(cmd);
                        if (result != null) {
                            result.put("id", cmd.optString("id", ""));
                            httpJson("POST", backendUrl + "/rig/result", result);
                        }
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "assist-poll").start();
    }

    // ── command execution (all on the UI thread — gestures need it) ────

    private JSONObject execute(final JSONObject cmd) throws Exception {
        final String type = cmd.optString("type", "");
        final JSONObject out = new JSONObject();
        out.put("ok", false);

        final Runnable[] work = new Runnable[1];
        final Object signal = new Object();
        final boolean[] done = {false};

        work[0] = () -> {
            try {
                if ("tap".equals(type)) {
                    int x = cmd.optInt("x"), y = cmd.optInt("y");
                    dispatchClick(x, y);
                    out.put("ok", true).put("done", "tap " + x + "," + y);
                } else if ("swipe".equals(type)) {
                    int x1 = cmd.optInt("x1"), y1 = cmd.optInt("y1");
                    int x2 = cmd.optInt("x2"), y2 = cmd.optInt("y2");
                    int dur = cmd.optInt("duration", 300);
                    dispatchSwipe(x1, y1, x2, y2, dur);
                    out.put("ok", true).put("done", "swipe");
                } else if ("back".equals(type)) {
                    out.put("ok", performGlobalAction(GLOBAL_ACTION_BACK)).put("done", "back");
                } else if ("home".equals(type)) {
                    out.put("ok", performGlobalAction(GLOBAL_ACTION_HOME)).put("done", "home");
                } else if ("recents".equals(type)) {
                    out.put("ok", performGlobalAction(GLOBAL_ACTION_RECENTS)).put("done", "recents");
                } else if ("notifications".equals(type)) {
                    out.put("ok", performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)).put("done", "notifications");
                } else if ("text".equals(type)) {
                    String s = cmd.optString("text", "");
                    AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    boolean ok = false;
                    if (node != null) {
                        Bundle args = new Bundle();
                        args.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
                        ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        node.recycle();
                    }
                    out.put("ok", ok).put("done", "text " + (ok ? "typed" : "no focused input"));
                } else if ("dump".equals(type)) {
                    out.put("ok", true).put("screen", dumpScreen());
                } else if ("open".equals(type)) {
                    String pkg = cmd.optString("package", "");
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        out.put("ok", true).put("done", "opened " + pkg);
                    } else {
                        out.put("ok", false).put("error", "package not found: " + pkg);
                    }
                } else {
                    out.put("ok", false).put("error", "unknown command type: " + type);
                }
            } catch (Throwable t) {
                try { out.put("ok", false).put("error", String.valueOf(t)); } catch (Throwable ignored) {}
            }
            synchronized (signal) { done[0] = true; signal.notifyAll(); }
        };

        h.post(work[0]);
        synchronized (signal) {
            long deadline = System.currentTimeMillis() + 12000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                signal.wait(1000);
            }
        }
        return out;
    }

    // ── gesture helpers ────────────────────────────────────────────────

    private void dispatchClick(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                p, 0, 60));
        dispatchGesture(b.build(), null, null);
    }

    private void dispatchSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                p, 0, Math.max(50, duration)));
        dispatchGesture(b.build(), null, null);
    }

    // ── screen reading ─────────────────────────────────────────────────

    private String dumpScreen() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "(no active window)";
            StringBuilder sb = new StringBuilder();
            walk(root, sb, 0);
            root.recycle();
            return sb.length() == 0 ? "(empty window)" : sb.toString();
        } catch (Throwable t) {
            return "dump failed: " + t;
        }
    }

    private void walk(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 30) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean clickable = node.isClickable();
        Rect r = new Rect();
        try { node.getBoundsInScreen(r); } catch (Throwable ignored) {}
        String t = text == null ? "" : text.toString().trim();
        String d = desc == null ? "" : desc.toString().trim();
        if (!t.isEmpty() || !d.isEmpty() || clickable) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(clickable ? "[tap] " : "      ");
            if (!t.isEmpty()) sb.append(t.replace('\n', ' '));
            if (!d.isEmpty()) sb.append(" (").append(d.replace('\n', ' ')).append(")");
            sb.append("  @").append(r.centerX()).append(",").append(r.centerY());
            sb.append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            try { walk(node.getChild(i), sb, depth + 1); } catch (Throwable ignored) {}
        }
    }

    // ── tiny http (framework only, org.json included) ─────────────────

    private JSONObject httpJson(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(30000);
        c.setRequestProperty("X-Backend-Key", backendKey == null ? "" : backendKey);
        c.setRequestProperty("User-Agent", "XDAssist/1.0.0 (Android)");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) { c.disconnect(); return null; }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        c.disconnect();
        String s = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return null;
        return new JSONObject(s);
    }
}
