package com.stxaviers.assist;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * XD Assist (v1.0.0) — the white permissions + connection app.
 *
 * What the owner does here, in order:
 *   1. type the Termux bridge URL (the cloudflared https link)
 *   2. type the secure key (the backend key)
 *   3. tap SAVE & TEST  → the app checks the bridge and shows its status
 *   4. tap ENABLE SCREEN CONTROL → Android Accessibility settings open;
 *      find "XD Assist screen control" and switch it ON
 *   5. done — the service now polls the bridge and performs commands
 *      (tap / swipe / type / back / home / read screen) so the developer
 *      can test and fix the XavierDrive app on this phone remotely.
 */
public class MainActivity extends Activity {

    static final String PREFS = "xd_assist";
    static final String K_URL = "backend_url";
    static final String K_KEY = "backend_key";

    private EditText urlField, keyField;
    private TextView status, log;
    private final StringBuilder logBuf = new StringBuilder();
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences p = prefs();
        String savedUrl = p.getString(K_URL, "");
        String savedKey = p.getString(K_KEY, "");

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);
        sc.addView(root);
        setContentView(sc);

        // ── title ─────────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("XD Assist");
        title.setTextSize(26f);
        title.setTextColor(0xFF171D3A);
        title.setTypeface(TypefaceProxy.medium(this));
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Connect the phone to your Termux bridge, then enable screen control.");
        sub.setTextSize(13.5f);
        sub.setTextColor(0xFF667089);
        root.addView(sub);
        space(root, 20);

        // ── backend URL ───────────────────────────────────────────────
        root.addView(label("Bridge URL (the cloudflared link)"));
        urlField = field(savedUrl, false);
        urlField.setHint("https://xxxx.trycloudflare.com");
        root.addView(urlField);
        space(root, 12);

        // ── secure key ────────────────────────────────────────────────
        root.addView(label("Secure key"));
        keyField = field(savedKey, true);
        keyField.setHint("the backend key");
        root.addView(keyField);
        space(root, 16);

        // ── save & test ───────────────────────────────────────────────
        TextView save = button("SAVE  &  TEST BRIDGE", 0xFF2F6BFF, Color.WHITE);
        save.setOnClickListener(v -> saveAndTest());
        root.addView(save);
        space(root, 10);

        // ── enable accessibility ──────────────────────────────────────
        TextView enable = button("ENABLE SCREEN CONTROL", 0xFF171D3A, Color.WHITE);
        enable.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(enable);
        space(root, 18);

        // ── status ────────────────────────────────────────────────────
        status = new TextView(this);
        status.setTextSize(14f);
        status.setTextColor(0xFF667089);
        status.setText("Bridge: not tested yet\nScreen control: unknown — enable it above");
        root.addView(status);
        space(root, 10);

        log = new TextView(this);
        log.setTextSize(11.5f);
        log.setTextColor(0xFF8A93A8);
        root.addView(log);

        refreshStatus();
    }

    // ── actions ────────────────────────────────────────────────────────

    private void saveAndTest() {
        String url = urlField.getText().toString().trim();
        String key = keyField.getText().toString().trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            toast("The URL must start with https://");
            return;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        final String fUrl = url;
        prefs().edit().putString(K_URL, fUrl).putString(K_KEY, key).apply();
        appendLog("saved bridge " + fUrl);
        toast("Saved — testing the bridge…");
        new Thread(() -> {
            String result;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(fUrl + "/health").openConnection();
                c.setConnectTimeout(9000);
                c.setReadTimeout(9009);
                c.setRequestProperty("X-Backend-Key", key);
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = is == null ? "" : readAll(is);
                c.disconnect();
                result = code == 200
                        ? "Bridge: ONLINE (" + body + ")"
                        : "Bridge: answered HTTP " + code + (body.isEmpty() ? "" : " — " + body);
            } catch (Exception e) {
                result = "Bridge: UNREACHABLE — " + e.getMessage();
            }
            final String r = result;
            h.post(() -> {
                appendLog(r);
                status.setText(r + "\n" + screenControlState());
            });
        }, "test-bridge").start();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("Find \u201cXD Assist screen control\u201d and switch it ON");
        } catch (Throwable t) {
            toast("Could not open settings: " + t.getMessage());
        }
    }

    private void refreshStatus() {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing()) {
                    status.setText(bridgeState() + "\n" + screenControlState());
                    h.postDelayed(this, 2500);
                }
            }
        }, 2500);
    }

    private String bridgeState() {
        String u = prefs().getString(K_URL, "");
        return u.isEmpty() ? "Bridge: not configured yet"
                : "Bridge: " + u + " (saved)";
    }

    private String screenControlState() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(ACCESSIBILITY_SERVICE);
            boolean on = am != null && am.isEnabled() && ScreenControlService.connected;
            return "Screen control: " + (on ? "ON — the bridge can drive this phone"
                    : "OFF — tap ENABLE above, then switch XD Assist ON in settings");
        } catch (Throwable t) {
            return "Screen control: unknown";
        }
    }

    // ── small ui helpers ───────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(0xFF667089);
        t.setAllCaps(true);
        return t;
    }

    private EditText field(String value, boolean secret) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(15f);
        e.setTextColor(0xFF171D3A);
        e.setBackgroundColor(0xFFF2F4F8);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private TextView button(String text, int bg, int fg) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(TypefaceProxy.medium(this));
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        return b;
    }

    private void space(LinearLayout root, int h) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String line) {
        logBuf.insert(0, line + "\n");
        if (logBuf.length() > 4000) logBuf.setLength(4000);
        log.setText(logBuf.toString());
    }

    private String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray()).trim();
    }
}

/** Typefaces without any dependency: framework default + a medium weight
 *  approximation via Typeface.create for pre-installed families. */
final class TypefaceProxy {
    static android.graphics.Typeface medium(Context c) {
        try {
            return android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL);
        } catch (Throwable t) {
            return android.graphics.Typeface.DEFAULT_BOLD;
        }
    }
}
