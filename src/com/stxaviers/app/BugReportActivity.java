package com.stxaviers.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BUG REPORTS (v1.1.8, owner spec 2026-09-30).
 *
 * Hub (reached from the Profile tab): Report a bug · My reports — plus
 * "Reported bugs" for the DEVELOPER rank only.
 *
 * The report form: a text box, a mic button (speech-to-type, same engine as
 * the AI composer), an attach-file button, an attach-image button, and the
 * send button. Reports land on Drive exactly as the owner specified:
 *
 *   Xavier-Drive/BUG_REPORTS/bugreport#NNN/bug.txt (+ every attached file)
 *
 * Attachments are limited to 100 MB each. The reporter sees their reports
 * with status (Under review by default / Resolved / False) and every
 * developer response; developers open any report, respond to the reporter
 * and set the status — each report individually.
 */
public class BugReportActivity extends XdActivity {

    // v1.1.9 endpoint dedupe: every bug-report route hangs off this one base,
    // so the app side can never drift from the worker's /api/bugs tree again.
    private static final String EP_BUGS = "/api/bugs";

    private static final int PICK_FILE = 71;
    private static final int PICK_IMAGE = 72;
    private static final int REQ_MIC = 73;
    private static final long MAX_FILE = 100L * 1024 * 1024;   // owner spec
    private static final int MAX_ATTACHMENTS = 5;

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;
    private LinearLayout content;

    /** One picked attachment for a new report. */
    private static final class Pending {
        String name;
        String mime;
        byte[] bytes;
    }

    private final List<Pending> pending = new ArrayList<>();
    /** the open report form's chip strip (null when the form is closed) */
    private LinearLayout chipStrip;
    private VoiceInput voiceInput;
    private boolean listening;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bug_report);
        st = XDState.get(this);
        content = findViewById(R.id.bug_content);
        findViewById(R.id.bug_back).setOnClickListener(v -> {
            if (detailOpen) { showHub(); } else { finish(); }
        });
        showHub();
    }

    @Override
    protected void onDestroy() {
        if (voiceInput != null) voiceInput.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (detailOpen) { showHub(); return; }
        super.onBackPressed();
    }

    // ═════════════════════════════════════ hub ═════════════════════════

    private boolean detailOpen;

    private void showHub() {
        detailOpen = false;
        pending.clear();
        chipStrip = null;
        content.removeAllViews();
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, dp(24));
        sc.addView(box);
        content.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        box.addView(hubCard(R.drawable.ic_bug,
                getString(R.string.bug_report_new),
                getString(R.string.bug_report_new_sub),
                R.color.home_brand, () -> showReportForm()));
        box.addView(hubCard(R.drawable.ic_check_circle,
                getString(R.string.bug_report_mine),
                getString(R.string.bug_report_mine_sub),
                R.color.home_muted, () -> loadList(false)));
        if (st.isDeveloper) {
            box.addView(hubCard(R.drawable.ic_people,
                    getString(R.string.bug_report_all),
                    getString(R.string.bug_report_all_sub),
                    R.color.home_muted, () -> loadList(true)));
        }
    }

    /** One big tappable card in the hub. */
    private View hubCard(int icon, String title, String sub, int tint,
                         Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.home_ripple_card);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        card.setLayoutParams(cp);

        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.sheet_icon_bg);
        card.addView(tile, new LinearLayout.LayoutParams(dp(46), dp(46)));

        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(Fx.color(this, tint));
        tile.addView(ic, new FrameLayout.LayoutParams(dp(22), dp(22),
                Gravity.CENTER));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(14);
        card.addView(col, lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_ink));
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12.5f);
        s.setTextColor(Fx.color(this, R.color.home_muted));
        col.addView(s);

        ImageView go = new ImageView(this);
        go.setImageResource(R.drawable.ic_chevron);
        go.setColorFilter(Fx.color(this, R.color.home_nav_inactive));
        card.addView(go, new LinearLayout.LayoutParams(dp(18), dp(18)));

        card.setOnClickListener(v -> action.run());
        return card;
    }

    // ═══════════════════════════ report form ═══════════════════════════

    private void showReportForm() {
        pending.clear();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Fx.color(this, R.color.home_bg));
        final EditText text = new EditText(this);
        text.setHint(R.string.bug_hint);
        text.setTextSize(14.5f);
        text.setTypeface(Typefaces.interRegular(this));
        text.setTextColor(Fx.color(this, R.color.home_ink));
        text.setBackground(null);
        text.setGravity(Gravity.TOP);
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(6);
        text.setPadding(dp(18), dp(14), dp(18), dp(6));
        root.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // attachment chips
        chipStrip = new LinearLayout(this);
        chipStrip.setOrientation(LinearLayout.HORIZONTAL);
        chipStrip.setPadding(dp(14), 0, dp(14), 0);
        HorizontalScrollView chips = new HorizontalScrollView(this);
        chips.setHorizontalScrollBarEnabled(false);
        chips.addView(chipStrip);
        root.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshChips();

        // action row: mic · attach file · attach image · send
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(14), dp(8), dp(14), dp(14));
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        actions.addView(formBtn(R.drawable.ic_mic, R.string.bug_mic_cd,
                R.color.home_tile_ink, () -> toggleMic(text)));
        actions.addView(formBtn(R.drawable.ic_file, R.string.bug_attach_file,
                R.color.home_brand, () -> pick(PICK_FILE, "*/*")));
        actions.addView(formBtn(R.drawable.ic_image, R.string.bug_attach_image,
                R.color.home_brand, () -> pick(PICK_IMAGE, "image/*")));

        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(
                0, 1, 1f));

        TextView send = new TextView(this);
        send.setText(R.string.bug_send);
        send.setTextSize(14f);
        send.setTypeface(Typefaces.interMedium(this));
        send.setTextColor(Color.WHITE);
        send.setGravity(Gravity.CENTER);
        send.setBackgroundResource(R.drawable.home_ai_btn_bg);
        send.setPadding(dp(20), dp(11), dp(20), dp(11));
        actions.addView(send, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        send.setOnClickListener(v -> submitReport(text));

        final Dialog d = new Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setContentView(root);
        // a slim header so the fullscreen form can be closed
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(10), dp(16), 0);
        ((ViewGroup) root).addView(head, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Fx.color(this, R.color.home_ink));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setOnClickListener(x -> d.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText(R.string.bug_report_new);
        title.setTextSize(18f);
        title.setTypeface(Typefaces.outfitMedium(this));
        title.setTextColor(Fx.color(this, R.color.home_ink));
        head.addView(title);
        d.show();
        d.setOnDismissListener(x -> chipStrip = null);
    }

    /** Rebuild the open form's attachment chips (name + ✕ to remove). */
    private void refreshChips() {
        if (chipStrip == null) return;
        float dp = getResources().getDisplayMetrics().density;
        chipStrip.removeAllViews();
        for (final Pending p : pending) {
            TextView chip = new TextView(this);
            chip.setText("✕  " + p.name);
            chip.setTextSize(12f);
            chip.setTypeface(Typefaces.interMedium(this));
            chip.setTextColor(Fx.color(this, R.color.home_ink));
            chip.setBackgroundResource(R.drawable.notice_chip);
            chip.setPadding((int) (12 * dp), (int) (6 * dp),
                    (int) (12 * dp), (int) (6 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                pending.remove(p);
                refreshChips();
            });
            chipStrip.addView(chip);
        }
        if (chipStrip.getParent() instanceof HorizontalScrollView) {
            ((HorizontalScrollView) chipStrip.getParent())
                    .post(() -> ((HorizontalScrollView) chipStrip.getParent())
                            .fullScroll(View.FOCUS_RIGHT));
        }
    }

    /** One round action button for the report form's action row. */
    private View formBtn(int icon, int label, int tint, Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(44), dp(44));
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        b.setBackgroundResource(R.drawable.sheet_icon_bg);
        b.setClickable(true);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(Fx.color(this, tint));
        b.addView(ic, new FrameLayout.LayoutParams(dp(20), dp(20),
                Gravity.CENTER));
        b.setOnClickListener(v -> action.run());
        if (label != 0) b.setContentDescription(getString(label));
        return b;
    }

    private void pick(int code, String mime) {
        if (pending.size() >= MAX_ATTACHMENTS) {
            Ui.toast(this, getString(R.string.ai_att_max));
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(mime);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, code);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req != PICK_FILE && req != PICK_IMAGE) return;
        try {
            Uri u = data.getData();
            long size = -1;
            try {
                android.database.Cursor c = getContentResolver()
                        .query(u, null, null, null, null);
                if (c != null) {
                    int ix = c.getColumnIndex(
                            android.provider.OpenableColumns.SIZE);
                    if (ix >= 0 && c.moveToFirst()) size = c.getLong(ix);
                    c.close();
                }
            } catch (Throwable ignored) {}
            if (size > MAX_FILE) {
                Ui.toast(this, getString(R.string.bug_too_big));
                return;
            }
            String name = "attachment";
            try {
                android.database.Cursor c = getContentResolver()
                        .query(u, null, null, null, null);
                if (c != null) {
                    int ix = c.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME);
                    if (ix >= 0 && c.moveToFirst()
                            && c.getString(ix) != null) name = c.getString(ix);
                    c.close();
                }
            } catch (Throwable ignored) {}
            String mime = getContentResolver().getType(u);
            if (mime == null) mime = "application/octet-stream";

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = getContentResolver().openInputStream(u);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] bytes = bos.toByteArray();
            if (bytes.length > MAX_FILE) {
                Ui.toast(this, getString(R.string.bug_too_big));
                return;
            }
            Pending p = new Pending();
            p.name = name;
            p.mime = mime;
            p.bytes = bytes;
            pending.add(p);
            refreshChips();
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.error_load));
        }
    }

    // ── voice input (the AI composer's engine) ─────────────────────────

    private void toggleMic(final EditText into) {
        if (listening) {
            if (voiceInput != null) voiceInput.stop();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startBugMic(into);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            micTarget = into;
        }
    }

    private EditText micTarget;

    @Override
    public void onRequestPermissionsResult(int req, String[] perms,
                                           int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC) {
            for (int i = 0; i < perms.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(perms[i])
                        && grants[i] == PackageManager.PERMISSION_GRANTED) {
                    startBugMic(micTarget != null ? micTarget
                            : (EditText) content.findFocus());
                    return;
                }
            }
        }
    }

    private void startBugMic(final EditText into) {
        if (into == null) return;
        if (voiceInput == null) {
            voiceInput = new VoiceInput(this, new VoiceInput.Events() {
                @Override public void onListening() {
                    listening = true;
                    Ui.toast(BugReportActivity.this,
                            getString(R.string.ai_listening));
                }
                @Override public void onText(String text, boolean isFinal) {
                    if (text == null || text.trim().isEmpty()) return;
                    String cur = into.getText().toString();
                    boolean glue = !cur.isEmpty()
                            && !Character.isWhitespace(cur.charAt(cur.length() - 1));
                    into.setText(cur + (glue ? " " : "") + text.trim());
                    into.setSelection(into.getText().length());
                }
                @Override public void onDone() {
                    listening = false;
                }
            });
        }
        voiceInput.start();
    }

    // ── submit ──────────────────────────────────────────────────────────

    private void submitReport(final EditText text) {
        final String msg = text.getText().toString().trim();
        if (msg.length() < 3) {
            Ui.toast(this, getString(R.string.bug_empty));
            return;
        }
        final android.app.AlertDialog pd = new AlertDialog.Builder(this,
                R.style.Theme_XavierDrive_Dialog)
                .setView(textProgress())
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String err = null;
            String folderId = null, id = null;
            // v1.1.8: device specs + the app's last-hour log travel with the
            // report (owner order) — collected on THIS thread, off the UI.
            final JSONObject device = DeviceInfo.collect(BugReportActivity.this);
            final String deviceSummary = DeviceInfo.summary(BugReportActivity.this);
            final byte[] logBytes = XLog.hasContent()
                    ? XLog.read().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : null;
            XLog.i("bug", "submitting report ("
                    + msg.length() + " chars, " + pending.size() + " attachments"
                    + (logBytes != null ? ", +latestlog.txt" : "") + ")");
            try {
                JSONObject body = ApiClient.obj("message", msg);
                body.put("device", device);
                body.put("deviceSummary", deviceSummary);
                ApiClient.Resp r = ApiClient.requestJson("POST", EP_BUGS, body);
                if (r.ok && r.json != null) {
                    folderId = r.json.optString("folderId", "");
                    id = r.json.optString("id", "");
                } else {
                    err = r.error().isEmpty() ? ("HTTP " + r.code) : r.error();
                }
                // attachments, one request each
                if (err == null && folderId != null) {
                    for (Pending p : pending) {
                        ApiClient.Resp a = ApiClient.postMultipart(
                                EP_BUGS + "/" + folderId + "/file",
                                p.name, p.mime, p.bytes, null);
                        if (!a.ok) {
                            err = p.name + ": " + (a.error().isEmpty()
                                    ? ("HTTP " + a.code) : a.error());
                            break;
                        }
                    }
                }
                // the app's own last-hour log attaches LAST and may never
                // fail the report itself — it is diagnostics, not payload
                if (err == null && folderId != null && logBytes != null
                        && logBytes.length > 0) {
                    try {
                        ApiClient.Resp l = ApiClient.postMultipart(
                                EP_BUGS + "/" + folderId + "/file",
                                "latestlog.txt", "text/plain", logBytes, null);
                        if (!l.ok) XLog.w("bug",
                                "latestlog.txt attach failed: HTTP " + l.code);
                    } catch (Throwable t) {
                        XLog.e("bug", t);
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final String e = err, fid = folderId, bid = id;
            h.post(() -> {
                if (isFinishing()) return;
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (e != null) {
                    Ui.toast(BugReportActivity.this,
                            getString(R.string.bug_failed) + ": " + e);
                    XLog.e("bug", "report failed: " + e);
                } else {
                    XLog.i("bug", "report " + bid + " sent");
                    // the owner's one-time acknowledgement right there
                    new AlertDialog.Builder(BugReportActivity.this,
                            R.style.Theme_XavierDrive_Dialog)
                            .setTitle(R.string.bug_ack_title)
                            .setMessage(R.string.bug_ack)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    pending.clear();
                    loadList(false);
                }
            });
        }, "xd-bug-send").start();
    }

    private View textProgress() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding((int) (24 * dp), (int) (18 * dp), (int) (24 * dp), 0);
        TextView t = new TextView(this);
        t.setText(R.string.bug_sending);
        t.setTextSize(14f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_ink));
        box.addView(t);
        return box;
    }

    // ═══════════════════════════ lists ═════════════════════════════════

    private void loadList(final boolean all) {
        content.removeAllViews();
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(24));
        sc.addView(box);
        content.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView loading = new TextView(this);
        loading.setText(R.string.bug_sending);
        loading.setTextColor(Fx.color(this, R.color.home_muted));
        box.addView(loading);

        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET",
                    all ? EP_BUGS : EP_BUGS + "/mine");
            final List<JSONObject> reports = new ArrayList<>();
            String err = null;
            if (r.ok && r.json != null) {
                JSONArray a = r.json.optJSONArray("reports");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) reports.add(o);
                    }
                }
            } else {
                err = r.error().isEmpty() ? ("HTTP " + r.code) : r.error();
            }
            final String e = err;
            h.post(() -> {
                if (isFinishing()) return;
                box.removeAllViews();
                if (e != null) {
                    TextView t = new TextView(this);
                    t.setText(getString(R.string.bug_load_failed) + ": " + e);
                    t.setTextSize(13f);
                    t.setTextColor(Fx.color(this, R.color.home_muted));
                    box.addView(t);
                    return;
                }
                if (reports.isEmpty()) {
                    TextView t = new TextView(this);
                    t.setText(R.string.bug_none_yet);
                    t.setTextSize(13f);
                    t.setTextColor(Fx.color(this, R.color.home_muted));
                    box.addView(t);
                    return;
                }
                for (final JSONObject o : reports) {
                    box.addView(reportRow(o, all));
                }
            });
        }, "xd-bug-list").start();
    }

    /** One row in the reports list: id + time + status chip + preview. */
    private View reportRow(final JSONObject o, final boolean all) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackgroundResource(R.drawable.home_ripple_card);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        card.setLayoutParams(cp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView id = new TextView(this);
        id.setText(o.optString("id", ""));
        id.setTextSize(14.5f);
        id.setTypeface(Typefaces.interMedium(this));
        id.setTextColor(Fx.color(this, R.color.home_ink));
        top.addView(id, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(statusChip(o.optString("status", "Under review")));

        String by = o.optString("reporter", "");
        String when = o.optString("reported", "");
        String meta = (by.isEmpty() ? "" : by + " · ") + when;
        if (!meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextSize(11f);
            m.setTextColor(Fx.color(this, R.color.home_muted));
            card.addView(m);
        }
        String preview = o.optString("preview", "");
        if (!preview.isEmpty()) {
            TextView p = new TextView(this);
            p.setText(preview);
            p.setTextSize(12.5f);
            p.setTextColor(Fx.color(this, R.color.home_muted));
            p.setMaxLines(2);
            p.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(p);
        }

        card.setOnClickListener(v -> showDetail(o.optString("folderId", "")));
        return card;
    }

    /** The coloured status chip (Under review / Resolved / False). */
    private TextView statusChip(String status) {
        float dp = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        boolean resolved = "Resolved".equals(status);
        boolean False = "False".equals(status);
        chip.setText(resolved ? R.string.bug_status_resolved
                : False ? R.string.bug_status_false
                : R.string.bug_status_under_review);
        chip.setTextSize(10.5f);
        chip.setTypeface(Typefaces.interMedium(this));
        int color = resolved ? Fx.color(this, R.color.success)
                : False ? Fx.color(this, R.color.danger)
                : Fx.color(this, R.color.home_muted);
        chip.setTextColor(color);
        chip.setBackgroundResource(R.drawable.notice_chip);
        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
        return chip;
    }

    // ═══════════════════════════ detail ════════════════════════════════

    private void showDetail(final String folderId) {
        if (folderId == null || folderId.isEmpty()) return;
        detailOpen = true;
        content.removeAllViews();
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(24));
        sc.addView(box);
        content.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView loading = new TextView(this);
        loading.setText(R.string.bug_sending);
        loading.setTextColor(Fx.color(this, R.color.home_muted));
        box.addView(loading);

        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET",
                    EP_BUGS + "/" + folderId);
            JSONObject bug = null;
            JSONArray attachments = null;
            boolean canRespond = false;
            String err = null;
            if (r.ok && r.json != null) {
                bug = r.json.optJSONObject("bug");
                attachments = r.json.optJSONArray("attachments");
                canRespond = r.json.optBoolean("canRespond", false);
                if (bug == null) err = "bad response";
            } else {
                err = r.error().isEmpty() ? ("HTTP " + r.code) : r.error();
            }
            final JSONObject b = bug;
            final JSONArray att = attachments == null ? new JSONArray() : attachments;
            final boolean dev = canRespond;
            final String e = err;
            h.post(() -> {
                if (isFinishing()) return;
                box.removeAllViews();
                if (e != null || b == null) {
                    TextView t = new TextView(this);
                    t.setText(getString(R.string.bug_load_failed) + ": " + e);
                    t.setTextSize(13f);
                    t.setTextColor(Fx.color(this, R.color.home_muted));
                    box.addView(t);
                    return;
                }
                renderDetail(box, folderId, b, att, dev);
            });
        }, "xd-bug-detail").start();
    }

    private void renderDetail(final LinearLayout box, final String folderId,
                              final JSONObject b, final JSONArray att,
                              final boolean dev) {
        float dp = getResources().getDisplayMetrics().density;

        // header: id + status
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView id = new TextView(this);
        id.setText(b.optString("id", ""));
        id.setTextSize(16.5f);
        id.setTypeface(Typefaces.outfitMedium(this));
        id.setTextColor(Fx.color(this, R.color.home_ink));
        top.addView(id, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(statusChip(b.optString("status", "Under review")));

        String meta = b.optString("reported", "");
        String by = b.optString("reporter", "");
        if (!by.isEmpty()) {
            meta = getString(R.string.bug_reported_by, by)
                    + (meta.isEmpty() ? "" : " · " + meta);
        }
        if (!meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextSize(11.5f);
            m.setTextColor(Fx.color(this, R.color.home_muted));
            box.addView(m);
        }
        // v1.1.8: the reporter's device (one line, muted) — devs asked for
        // specs with every report so they can reproduce on similar hardware
        String device = b.optString("device", "");
        if (!device.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(device);
            d.setTextSize(10.5f);
            d.setTextColor(Fx.color(this, R.color.home_muted));
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(d);
        }

        // the report itself
        LinearLayout msg = new LinearLayout(this);
        msg.setOrientation(LinearLayout.VERTICAL);
        msg.setPadding(dp(14), dp(12), dp(14), dp(12));
        msg.setBackgroundResource(R.drawable.row_card);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(12);
        msg.setLayoutParams(mp);
        TextView body = new TextView(this);
        body.setText(b.optString("message", ""));
        body.setTextSize(13.5f);
        body.setTypeface(Typefaces.interRegular(this));
        body.setTextColor(Fx.color(this, R.color.home_ink));
        msg.addView(body);
        box.addView(msg);

        // attachments
        if (att.length() > 0) {
            TextView head = sectionLabel(getString(R.string.bug_attachments));
            box.addView(head);
            for (int i = 0; i < att.length(); i++) {
                JSONObject a = att.optJSONObject(i);
                if (a == null) continue;
                box.addView(attachmentRow(folderId,
                        a.optString("name", ""),
                        a.optLong("size", 0)));
            }
        }

        // developer responses
        JSONArray responses = b.optJSONArray("responses");
        TextView rhead = sectionLabel(getString(R.string.bug_responses));
        box.addView(rhead);
        if (responses == null || responses.length() == 0) {
            TextView none = new TextView(this);
            none.setText(R.string.bug_no_responses);
            none.setTextSize(12.5f);
            none.setTextColor(Fx.color(this, R.color.home_muted));
            box.addView(none);
        } else {
            for (int i = 0; i < responses.length(); i++) {
                JSONObject resp = responses.optJSONObject(i);
                if (resp == null) continue;
                LinearLayout rc = new LinearLayout(this);
                rc.setOrientation(LinearLayout.VERTICAL);
                rc.setPadding(dp(14), dp(10), dp(14), dp(10));
                rc.setBackgroundResource(R.drawable.row_card);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(8);
                rc.setLayoutParams(rp);
                TextView who = new TextView(this);
                who.setText(resp.optString("by", "") + " · "
                        + resp.optString("ts", ""));
                who.setTextSize(11f);
                who.setTypeface(Typefaces.interMedium(this));
                who.setTextColor(Fx.color(this, R.color.home_brand));
                rc.addView(who);
                TextView txt = new TextView(this);
                txt.setText(resp.optString("text", ""));
                txt.setTextSize(13f);
                txt.setTypeface(Typefaces.interRegular(this));
                txt.setTextColor(Fx.color(this, R.color.home_ink));
                rc.addView(txt);
                box.addView(rc);
            }
        }

        // developer controls — per report, individually
        if (dev) {
            TextView chead = sectionLabel("Developer");
            box.addView(chead);

            TextView add = new TextView(this);
            add.setText(R.string.bug_add_response);
            add.setTextSize(14f);
            add.setTypeface(Typefaces.interMedium(this));
            add.setTextColor(Color.WHITE);
            add.setGravity(Gravity.CENTER);
            add.setBackgroundResource(R.drawable.home_ai_btn_bg);
            add.setPadding(dp(16), dp(11), dp(16), dp(11));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ap.topMargin = dp(8);
            add.setLayoutParams(ap);
            box.addView(add);
            add.setOnClickListener(v -> askResponse(folderId));

            // status selector — three chips
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.topMargin = dp(10);
            chips.setLayoutParams(chp);
            box.addView(chips);
            final String current = b.optString("status", "Under review");
            String options[] = {"Under review", "Resolved", "False"};
            for (final String opt : options) {
                TextView chip = statusChip(opt);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                chip.setLayoutParams(lp);
                if (opt.equals(current)) {
                    chip.setBackgroundResource(R.drawable.chip_on);
                }
                chip.setOnClickListener(v -> setStatus(folderId, opt));
                chips.addView(chip);
            }
        }
    }

    private TextView sectionLabel(String text) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setLetterSpacing(0.08f);
        t.setTextColor(Fx.color(this, R.color.home_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * dp);
        lp.bottomMargin = (int) (4 * dp);
        t.setLayoutParams(lp);
        return t;
    }

    /** One attachment row: icon + name + size + a Download tap. */
    private View attachmentRow(final String folderId, final String name,
                               long size) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackgroundResource(R.drawable.home_ripple_card);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);

        ImageView ic = new ImageView(this);
        ic.setImageResource("image/".equals(guessMime(name))
                ? R.drawable.ic_image : R.drawable.ic_file);
        ic.setColorFilter(Fx.color(this, R.color.home_brand));
        row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(12);
        row.addView(col, cp);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(13.5f);
        n.setTypeface(Typefaces.interMedium(this));
        n.setTextColor(Fx.color(this, R.color.home_ink));
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        TextView s = new TextView(this);
        s.setText(Ui.size(size));
        s.setTextSize(11f);
        s.setTextColor(Fx.color(this, R.color.home_muted));
        col.addView(s);

        TextView dl = new TextView(this);
        dl.setText(R.string.bug_download);
        dl.setTextSize(12.5f);
        dl.setTypeface(Typefaces.interMedium(this));
        dl.setTextColor(Fx.color(this, R.color.home_brand));
        dl.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.addView(dl);
        row.setOnClickListener(v -> downloadAttachment(folderId, name));
        return row;
    }

    private static String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void downloadAttachment(final String folderId, final String name) {
        Ui.toast(this, name);
        new Thread(() -> {
            String err = null, path = null;
            try {
                InputStream in = ApiClient.openStream(
                        EP_BUGS + "/" + folderId + "/file?f="
                                + ApiClient.enc(name), null);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                path = Ui.saveToDownloads(BugReportActivity.this,
                        bos.toByteArray(), name, guessMime(name));
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final String e = err, p = path;
            h.post(() -> {
                if (isFinishing()) return;
                if (e != null) {
                    Ui.toast(BugReportActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                } else {
                    Ui.toast(BugReportActivity.this,
                            getString(R.string.ai_saved_downloads) + " " + p);
                }
            });
        }, "xd-bug-dl").start();
    }

    // ── developer actions ───────────────────────────────────────────────

    private void askResponse(final String folderId) {
        final EditText text = new EditText(this);
        text.setHint(R.string.bug_response_hint);
        float dp = getResources().getDisplayMetrics().density;
        text.setPadding((int) (14 * dp), (int) (10 * dp),
                (int) (14 * dp), (int) (10 * dp));
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.bug_add_response)
                .setView(text)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String t = text.getText().toString().trim();
                    if (t.isEmpty()) return;
                    bugUpdate(folderId, ApiClient.obj("response", t),
                            R.string.bug_response_sent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setStatus(final String folderId, final String status) {
        bugUpdate(folderId, ApiClient.obj("status", status),
                R.string.bug_status_updated);
    }

    private void bugUpdate(final String folderId, final JSONObject body,
                           final int okMsg) {
        new Thread(() -> {
            String what = body.has("response") ? "response" : "status";
            XLog.i("bug", "update " + folderId + " (" + what + ")");
            ApiClient.Resp r = ApiClient.requestJson("POST",
                    EP_BUGS + "/" + folderId, body);
            final boolean ok = r.ok;
            final String err = r.error();
            h.post(() -> {
                if (isFinishing()) return;
                Ui.toast(BugReportActivity.this, ok
                        ? getString(okMsg)
                        : getString(R.string.bug_failed) + ": " + err);
                if (ok) showDetail(folderId);
            });
        }, "xd-bug-update").start();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
