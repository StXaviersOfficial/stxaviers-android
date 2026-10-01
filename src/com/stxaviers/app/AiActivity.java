package com.stxaviers.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * XAVIERDRIVE AI — v1.2.0 — the full-screen experience.
 *
 *  • App bar shows THE CHAT'S OWN TITLE — "Untitled" until the AI names
 *    it after the first reply (/api/chat/title, the website's engine).
 *  • The drawer: search bar, compact new-chat + settings buttons,
 *    SQUARE chat rows, and a 3-dot menu per chat — Rename · Pin ·
 *    Summarize · Delete (teachers and above only, owner order).
 *  • Reopening the app lands on the LAST SELECTED chat, never a blank
 *    new one; the drawer refreshes itself the moment Drive answers.
 *  • Composer: send is GREY when the box is empty and BLUE when ready;
 *    the mic is plain by default and turns blue with a waveform while
 *    listening (server Whisper on devices without a recognizer).
 *  • Responses are selectable; actions are icon-only (no pills).
 *  • Attachments up to 15 MB; Generate image is available to EVERYONE
 *    (owner order v1.1.7 — students included).
 */
public class AiActivity extends XdActivity {

    // ── request codes ───────────────────────────────────────────────────
    private static final int PICK_PHOTOS = 51;
    private static final int PICK_FILES = 52;
    private static final int TAKE_PHOTO = 53;
    private static final int REQ_MIC = 60;

    // ── local cache (offline fallback for the Drive store) ─────────────
    private static final String PREFS = "xd_ai";
    private static final String KEY_CACHE = "sessions_v3";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_PINS = "pins";
    private static final int MAX_SESSIONS = 30;

    /** Owner order v1.2.0: attachments may be at most 15 MB. */
    private static final long MAX_ATT_BYTES = 15L * 1024 * 1024;

    private static final String[] THINKING = {
            "Reading your question…", "Researching the web…",
            "Reviewing sources…", "Writing your answer…"
    };

    // ── views ───────────────────────────────────────────────────────────
    private LinearLayout msgs, drawerList, attachRow;
    private ScrollView scroll;
    private EditText input, drawerSearch;
    private TextView titleView, quotaView;
    private View send, sendIcon, emptyState, typingRow;
    /** step 6: live action rows inside the typing row (UI thread only). */
    private AgentTrail agentTrail;
    private View drawer, drawerScrim, drawerPanel;
    private View attachStrip, modeStrip, modeOff;
    private TextView modeChip;
    private ImageView micIcon;
    private View micBtn;

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;

    // ── state ───────────────────────────────────────────────────────────
    private final List<ChatSync.Session> sessions = new ArrayList<>();
    private String currentId = "";
    private boolean pendingNew;   // a just-created chat not yet saved
    private boolean busy;
    private boolean deepResearch;
    private boolean imageMode;
    private String voice;
    private VoiceInput voiceInput;
    private boolean listening;
    private final Set<String> pins = new HashSet<>();
    private String lastQuery = "";

    /** True once the AI screen has opened in THIS app process. The FIRST
     *  open after launching the app always starts a NEW chat (owner
     *  order v1.1.5 — "when reopened the app and clicked on the AI icon,
     *  it must start from the new chat"); later visits within the same
     *  app run restore the conversation the user was on. */
    private static boolean openedInProcess;

    /** Chat ids whose workspace (artifacts) listing was already pulled. */
    private final Set<String> wsLoaded = new HashSet<>();

    /** One picked attachment (image bitmap, or extracted text). */
    private static final class Att {
        String name;
        boolean image;
        Bitmap bmp;       // images (already normalized)
        String text;      // documents
    }

    private final List<Att> attach = new ArrayList<>();

    /** One AI-delivered ```file block (name + mime + content). */
    private static final class FileBlock {
        String name;
        String mime;
        String content;
    }

    /** One AI-delivered ```pdffile block (server renders the PDF). */
    private static final class PdfBlock {
        String name;
        String title;
        String prompt;
    }

    /** v1.1.8: one AI-delivered ```image block — the model asks for a
     *  picture and the app draws it with the image engine (Pollinations). */
    private static final class ImageBlock {
        String prompt;
    }

    private final Runnable thinkCycle = new Runnable() {
        private int stage = 0;
        @Override public void run() {
            if (typingRow == null) return;
            TextView t = typingRow.findViewById(R.id.bubble_text);
            if (t != null && stage < THINKING.length) {
                t.setText(THINKING[stage]);
                stage = Math.min(stage + 1, THINKING.length - 1);
            }
            h.postDelayed(this, 3500L);
        }
    };

    // ═══════════════════════════════════════════════ lifecycle ═══════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai);
        st = XDState.get(this);

        voice = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_VOICE, "austin");
        for (String p : getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PINS, "").split(",")) {
            if (p != null && !p.trim().isEmpty()) pins.add(p.trim());
        }

        bindViews();
        loadChats(!openedInProcess);
        openedInProcess = true;
    }

    private void bindViews() {
        msgs = findViewById(R.id.ai_msgs);
        scroll = findViewById(R.id.ai_scroll);
        input = findViewById(R.id.ai_input);
        send = findViewById(R.id.ai_send);
        sendIcon = findViewById(R.id.ai_send_icon);
        emptyState = findViewById(R.id.ai_empty);
        titleView = findViewById(R.id.ai_title);
        quotaView = findViewById(R.id.ai_quota);

        drawer = findViewById(R.id.ai_drawer);
        drawerScrim = findViewById(R.id.drawer_scrim);
        drawerPanel = findViewById(R.id.drawer_panel);
        drawerList = findViewById(R.id.drawer_list);
        drawerSearch = findViewById(R.id.drawer_search);

        attachStrip = findViewById(R.id.ai_attach_strip);
        attachRow = findViewById(R.id.ai_attach_row);
        modeStrip = findViewById(R.id.ai_research_strip);
        modeChip = findViewById(R.id.ai_research_chip);
        modeOff = findViewById(R.id.ai_research_off);
        micIcon = findViewById(R.id.ai_mic_icon);
        micBtn = findViewById(R.id.ai_mic);

        findViewById(R.id.ai_close).setOnClickListener(v -> exitToHome());
        findViewById(R.id.ai_menu).setOnClickListener(v -> openDrawer());
        drawerScrim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.drawer_new).setOnClickListener(v -> {
            closeDrawer();
            newChat();
        });
        findViewById(R.id.drawer_settings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, AiSettingsActivity.class));
        });

        send.setOnClickListener(v -> {
            // v1.1.8: while the AI is working the same button is a STOP
            // button — one tap ends the task right there (owner order)
            if (busy) { stopGeneration(); return; }
            send();
        });
        // v1.1.7: the keyboard's enter key inserts a new line (multiline
        // field, no IME action) — only the send arrow submits.

        // the send button lights the moment there is something to send
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                 int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                refreshSendUi();
            }
        });
        refreshSendUi();

        findViewById(R.id.ai_plus).setOnClickListener(v -> showPlusMenu());
        findViewById(R.id.ai_mic).setOnClickListener(v -> toggleMic());
        modeOff.setOnClickListener(v -> {
            deepResearch = false;
            imageMode = false;
            input.setHint(R.string.ai_hint);
            refreshModeStrip();
            refreshSendUi();
        });

        // chat search — matches titles AND message contents
        drawerSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                 int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                lastQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuildDrawer();
            }
        });

        int sugIds[] = {R.id.ai_sug1, R.id.ai_sug2, R.id.ai_sug3};
        for (int id : sugIds) {
            TextView sug = findViewById(id);
            if (sug != null) {
                sug.setTypeface(Typefaces.interMedium(this));
                sug.setOnClickListener(v -> {
                    input.setText(((TextView) v).getText());
                    input.setSelection(input.getText().length());
                    focusInput();
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // settings (font / colour / voice) may have changed
        renderSession();
        refreshSendUi();
        // v1.1.8: the usage chip is a LIVE tracker now — every visit to the
        // AI screen pulls the real server-side count (backend-stored, no
        // more per-isolate reset) and shows it immediately
        fetchQuota();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(thinkCycle);
        h.removeCallbacksAndMessages(null);
        TtsPlayer.stop();
        if (voiceInput != null) voiceInput.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
        overridePendingTransition(0, R.anim.ai_out);
    }

    /** The cross — leave the AI and land on the Home tab. */
    private void exitToHome() {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
        overridePendingTransition(0, R.anim.ai_out);
    }

    // ═════════════════════════════════════════ session store ═══════════

    /** Load the synced store: Drive (website truth) merged with the local
     *  offline cache; local-only conversations are pushed up to Drive.
     *  freshOpen=true (first AI open after the APP was launched) starts
     *  a NEW chat instead of restoring the last one (owner order v1.1.5). */
    private void loadChats(final boolean freshOpen) {
        loadCache();
        if (freshOpen) {
            currentId = ChatSync.newId();
            pendingNew = true;
        }
        // v1.1.5: heal the store — merges any doubled chat folders so one
        // chat can never show twice (silent, fire-and-forget)
        ChatSync.dedupSweep(this);
        renderSession();
        ChatSync.load(this, (loaded, err) -> {
            if (isFinishing()) return;
            if (err != null && loaded.isEmpty()) {
                // offline: keep the cached copy, say so once
                if (!sessions.isEmpty()) {
                    Ui.toast(this, getString(R.string.ai_offline_chats));
                }
                ensureCurrent();
                renderSession();
                return;
            }
            mergeSessions(loaded);
            ensureCurrent();
            persistCache();
            renderSession();
            // the drawer refreshes itself even when it is already open —
            // chats must NEVER wait for a manual interaction (owner order)
            if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                rebuildDrawer();
            }
            // push any conversation that exists only on this device
            for (ChatSync.Session s : sessions) {
                if (s.dirty) {
                    ChatSync.save(this, s, null);
                }
            }
        });
    }

    /** Drive truth wins; local-only ids are kept (and marked dirty). */
    private void mergeSessions(List<ChatSync.Session> drive) {
        List<ChatSync.Session> merged = new ArrayList<>(drive);
        for (ChatSync.Session local : sessions) {
            boolean known = false;
            for (ChatSync.Session d : drive) {
                if (d.id.equals(local.id)) {
                    known = true;
                    break;
                }
            }
            if (!known && local.history.length() > 0) {
                local.dirty = true;
                merged.add(local);
            }
        }
        sessions.clear();
        sessions.addAll(merged);
        sortSessions();
    }

    /** Pinned chats first, then newest. */
    private void sortSessions() {
        java.util.Collections.sort(sessions, (a, b) -> {
            boolean pa = pins.contains(a.id), pb = pins.contains(b.id);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(b.created, a.created);
        });
    }

    private void ensureCurrent() {
        // keep the user's context: the last selected chat stays selected,
        // and a brand-new empty chat is never clobbered by a late Drive
        // load (the v1.1.2 "opens a new chat every time" family of bugs)
        if (currentId != null && !currentId.isEmpty()) {
            if (findSession(currentId) != null) return;
            if (pendingNew) return;
        }
        if (!sessions.isEmpty()) {
            currentId = sessions.get(0).id;
        } else {
            currentId = ChatSync.newId();
        }
    }

    private ChatSync.Session findSession(String id) {
        for (ChatSync.Session s : sessions) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    private ChatSync.Session ensureSession(String id) {
        ChatSync.Session s = findSession(id);
        if (s == null) {
            s = new ChatSync.Session();
            s.id = id;
            s.name = "";
            s.created = System.currentTimeMillis();
            sessions.add(s);
            sortSessions();
        }
        return s;
    }

    private JSONArray histOf(String id) {
        ChatSync.Session s = findSession(id);
        return s == null ? new JSONArray() : s.history;
    }

    private void addMessage(String id, String role, String text,
                            String time, String imgPath) {
        ChatSync.Session s = ensureSession(id);
        pendingNew = false;   // the conversation is real now
        try {
            JSONObject m = new JSONObject();
            m.put("role", role);
            m.put("text", text);
            m.put("time", time);
            if (imgPath != null) m.put("img", imgPath);
            s.history.put(m);
            if ((s.name == null || s.name.isEmpty())
                    && "user".equals(role)) {
                s.name = ChatSync.titleOf(s.history);
            }
            s.created = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }

    private void newChat() {
        currentId = ChatSync.newId();
        pendingNew = true;
        // every mode resets — a stuck Generate-image state made plain
        // prompts generate images (owner-reported glitch, v1.2.0 fix)
        deepResearch = false;
        imageMode = false;
        input.setHint(R.string.ai_hint);
        refreshModeStrip();
        attach.clear();
        refreshAttachStrip();
        persistCache();
        renderSession();
        refreshSendUi();
    }

    // ── local cache (offline fallback) ─────────────────────────────────

    private void persistCache() {
        try {
            trimSessions();
            JSONObject o = new JSONObject();
            o.put("current", currentId);
            JSONArray a = new JSONArray();
            for (ChatSync.Session s : sessions) {
                JSONObject j = new JSONObject();
                j.put("id", s.id);
                j.put("title", s.name == null ? "" : s.name);
                j.put("ts", s.created);
                j.put("hist", s.history);
                a.put(j);
            }
            o.put("sessions", a);
            StringBuilder p = new StringBuilder();
            for (String id : pins) {
                if (p.length() > 0) p.append(',');
                p.append(id);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CACHE, o.toString())
                    .putString(KEY_PINS, p.toString())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void loadCache() {
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_CACHE, null);
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            currentId = o.optString("current", "");
            JSONArray a = o.optJSONArray("sessions");
            if (a == null) return;
            sessions.clear();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                ChatSync.Session s = new ChatSync.Session();
                s.id = j.optString("id", "");
                s.name = j.optString("title", "");
                s.created = j.optLong("ts", 0);
                s.history = j.optJSONArray("hist");
                if (s.history == null) s.history = new JSONArray();
                if (!s.id.isEmpty() && s.history.length() > 0) {
                    sessions.add(s);
                }
            }
            sortSessions();
        } catch (Throwable ignored) {}
    }

    private void trimSessions() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            ChatSync.Session s = sessions.get(i);
            if (s.history.length() == 0 && !s.id.equals(currentId)) {
                sessions.remove(i);
            }
        }
        while (sessions.size() > MAX_SESSIONS) {
            int oldest = -1;
            long oldestTs = Long.MAX_VALUE;
            for (int i = 0; i < sessions.size(); i++) {
                ChatSync.Session s = sessions.get(i);
                if (s.id.equals(currentId)) continue;
                if (pins.contains(s.id)) continue;
                if (s.created < oldestTs) {
                    oldestTs = s.created;
                    oldest = i;
                }
            }
            if (oldest < 0) break;
            sessions.remove(oldest);
        }
    }

    // ═════════════════════════════════════════════ rendering ═════════════

    /** The chat's own title — "Untitled" until the AI names it. */
    private void updateTitle() {
        ChatSync.Session s = findSession(currentId);
        String name = s == null ? "" : s.name;
        titleView.setText(name == null || name.trim().isEmpty()
                ? getString(R.string.ai_untitled) : name);
    }

    private void renderSession() {
        msgs.removeAllViews();
        JSONArray hist = histOf(currentId);
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.optJSONObject(i);
            if (m == null) continue;
            appendBubble(m.optString("role", "ai"),
                    m.optString("text", ""),
                    m.optString("time", ""),
                    m.optString("img", null), i, false);
        }
        emptyState.setVisibility(hist.length() == 0
                ? View.VISIBLE : View.GONE);
        updateTitle();
        if (hist.length() > 0) scrollDown();
        // v1.1.5: pull this chat's workspace (artifact names) so the AI
        // knows what it has to work with on the next turn
        loadWorkspace(currentId);
    }

    /** Async-load the artifact names of this chat (once per id). */
    private void loadWorkspace(final String id) {
        if (id == null || id.isEmpty() || wsLoaded.contains(id)) return;
        final ChatSync.Session s = findSession(id);
        if (s == null) return;
        wsLoaded.add(id);
        new Thread(() -> {
            final List<String> names = ChatSync.artifactsOf(this, s);
            h.post(() -> {
                for (String n : names) {
                    if (!s.ws.contains(n)) s.ws.add(n);
                }
            });
        }, "xd-ws-load").start();
    }

    private void appendBubble(String role, CharSequence text, String time,
                              String imgPath, int histIndex, boolean animate) {
        boolean user = "user".equals(role);
        View v = LayoutInflater.from(this).inflate(user ? R.layout.item_msg_user
                        : R.layout.item_msg_ai, msgs, false);
        TextView tv = v.findViewById(R.id.bubble_text);
        // v1.1.5 (owner order): the size + font settings apply to BOTH
        // sides — the AI's replies AND the user's own messages
        tv.setTextSize(composedFontSize());
        tv.setTypeface(AiSettingsActivity.fontOf(this,
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(AiSettingsActivity.KEY_FONT, "inter")));
        String shown = text == null ? "" : text.toString();
        // v1.1.5: AI file blocks never render as raw code — they become
        // file cards (preview + download) below the reply
        List<Object> blocks = new ArrayList<>();
        if (!user) shown = stripFileBlocks(shown, blocks);
        // step 4: plain prose stays in bubble_text; replies with code /
        // ```copy blocks are built as text parts + copy boxes
        final RowSpeech speech = user ? null : buildAiBody(v, tv, shown);
        if (user) tv.setText(shown);
        TextView tt = v.findViewById(R.id.bubble_time);
        if (tt != null) tt.setText(time);

        if (!user) {
            // inline image (generated pictures)
            ImageView img = v.findViewById(R.id.bubble_img);
            if (img != null && imgPath != null) {
                File f = new File(imgPath);
                if (f.exists()) {
                    final Bitmap bmp = BitmapFactory.decodeFile(imgPath);
                    if (bmp != null) {
                        img.setImageBitmap(bmp);
                        img.setClipToOutline(true);   // rounded card
                        img.setVisibility(View.VISIBLE);
                        img.setOnClickListener(x ->
                                showImageViewer(bmp, f, f.getName()));
                    }
                }
            }
            bindActions(v, histIndex, shown, speech);
            addFileCards(v, blocks);
        } else {
            bindUserCopy(v, shown);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) ((user ? 18 : 16)
                * getResources().getDisplayMetrics().density);
        v.setLayoutParams(lp);
        msgs.addView(v);
        if (animate) Ui.reveal(v);
        scrollDown();
    }

    /** Copy / Listen / Regenerate under an AI response — icon-only. */
    private void bindActions(View row, final int histIndex,
                             final String text, final RowSpeech speech) {
        View copy = row.findViewById(R.id.act_copy);
        final ImageView copyIcon = row.findViewById(R.id.act_copy_icon);
        // copies the reply's markdown source; icon turns into a tick
        if (copy != null) copy.setOnClickListener(v -> {
            if (copyText(text)) flashCopied(copyIcon);
        });

        final View listen = row.findViewById(R.id.act_listen);
        final ImageView listenIcon = row.findViewById(R.id.act_listen_icon);
        if (listen != null && speech != null) {
            if (speech.spoken.trim().isEmpty()) {
                listen.setVisibility(View.GONE);      // code-only reply
            } else {
                speech.button = listen;
                speech.icon = listenIcon;
                listen.setOnClickListener(v -> {
                    if (TtsPlayer.isCurrent(speech)) {
                        int st = TtsPlayer.getState();
                        if (st == TtsPlayer.PLAYING) TtsPlayer.pause();
                        else if (st == TtsPlayer.PAUSED) TtsPlayer.resume();
                        else TtsPlayer.stop();          // still loading: cancel
                        return;
                    }
                    // starting another message stops (and resets) the first
                    TtsPlayer.speak(AiActivity.this, speech.spoken, voice,
                            speech);
                });
            }
        }

        View regen = row.findViewById(R.id.act_regen);
        if (regen != null) regen.setOnClickListener(v ->
                regenerate(histIndex));
    }

    /** Copy button under the user's own message (icon -> tick). */
    private void bindUserCopy(View row, final String text) {
        View copy = row.findViewById(R.id.act_copy);
        final ImageView icon = row.findViewById(R.id.act_copy_icon);
        if (copy != null) copy.setOnClickListener(v -> {
            if (copyText(text)) flashCopied(icon);
        });
    }

    private boolean copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.app_name), text == null ? "" : text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copy icon -> brand-coloured tick for ~1.5 s, then back. */
    private void flashCopied(final ImageView icon) {
        if (icon == null) return;
        Object prev = icon.getTag();
        if (prev instanceof Runnable) h.removeCallbacks((Runnable) prev);
        icon.setImageResource(R.drawable.ic_tick);
        icon.setImageTintList(ColorStateList.valueOf(
                Fx.color(this, R.color.home_brand)));
        Runnable back = () -> {
            icon.setImageResource(R.drawable.ic_copy);
            icon.setImageTintList(ColorStateList.valueOf(
                    Fx.color(this, R.color.home_muted)));
            icon.setTag(null);
        };
        icon.setTag(back);
        h.postDelayed(back, 1500L);
    }

    // ═══════════════ message body: prose + copy boxes + speech ═════════

    /**
     * Fill an AI bubble. No fences -> the single bubble_text. Fences ->
     * bubble_text is hidden and bubble_body gets, in order, prose TextViews
     * and copy boxes (```copy and ordinary code blocks, each with its own
     * Copy button that copies ONLY that block). Returns the speech helper
     * that maps the spoken string back onto the visible TextViews.
     */
    private RowSpeech buildAiBody(View row, TextView tv, String shown) {
        List<Ui.Seg> segs = Ui.segments(shown);
        boolean hasCode = false;
        for (Ui.Seg sg : segs) if (sg.code) { hasCode = true; break; }
        List<TextView> views = new ArrayList<>();
        List<Integer> offs = new ArrayList<>();
        StringBuilder spoken = new StringBuilder();
        LinearLayout body = row.findViewById(R.id.bubble_body);
        if (!hasCode || body == null) {
            CharSequence f = Ui.formatAi(shown);
            tv.setText(f, TextView.BufferType.SPANNABLE);
            views.add(tv);
            offs.add(0);
            spoken.append(f);
        } else {
            tv.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
            for (Ui.Seg sg : segs) {
                if (sg.code) {
                    body.addView("chart".equals(sg.lang)
                            ? buildChartCard(sg) : buildCodeBox(sg));
                    continue;
                }
                TextView t = cloneBodyText(tv);
                CharSequence f = Ui.formatAi(sg.body);
                t.setText(f, TextView.BufferType.SPANNABLE);
                body.addView(t);
                views.add(t);
                offs.add(spoken.length());
                spoken.append(f).append("\n\n");   // code is never read aloud
            }
        }
        return new RowSpeech(views, offs, spoken.toString());
    }

    /** A prose TextView that looks exactly like bubble_text. */
    private TextView cloneBodyText(TextView src) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        t.setLayoutParams(lp);
        t.setTextSize(composedFontSize());
        t.setTypeface(src.getTypeface());
        t.setTextColor(src.getTextColors());
        t.setLinkTextColor(src.getLinkTextColors());
        t.setLineSpacing(src.getLineSpacingExtra(),
                src.getLineSpacingMultiplier());
        t.setTextIsSelectable(true);
        return t;
    }

    /** Step 6: a ```chart block as a native chart card (title + Canvas
     *  chart). A spec that cannot be drawn shows one quiet note line. */
    private View buildChartCard(Ui.Seg sg) {
        final float dp = getResources().getDisplayMetrics().density;
        ChartView cv = ChartView.from(this, sg.body);
        if (cv == null) {
            TextView note = new TextView(this);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = (int) (8 * dp);
            note.setLayoutParams(np);
            note.setText(R.string.ai_chart_failed);
            note.setTextSize(12.5f);
            note.setTextColor(Fx.color(this, R.color.home_muted));
            return note;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.copybox_bg);
        card.setPadding((int) (12 * dp), (int) (12 * dp),
                (int) (12 * dp), (int) (10 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        lp.bottomMargin = (int) (2 * dp);
        card.setLayoutParams(lp);
        if (!cv.getTitle().isEmpty()) {
            TextView t = new TextView(this);
            t.setText(cv.getTitle());
            t.setTextSize(13.5f);
            t.setTypeface(Typefaces.interMedium(this));
            t.setTextColor(Fx.color(this, R.color.home_ink));
            t.setPadding(0, 0, 0, (int) (8 * dp));
            card.addView(t);
        }
        card.addView(cv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** Bordered rounded box + Copy button (top-right) for one fenced block. */
    private View buildCodeBox(final Ui.Seg sg) {
        final float dp = getResources().getDisplayMetrics().density;
        final boolean plainBox = sg.lang.isEmpty() || "copy".equals(sg.lang);
        final int brand = Fx.color(this, R.color.home_brand);
        final int muted = Fx.color(this, R.color.home_muted);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.copybox_bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (10 * dp);
        blp.bottomMargin = (int) (2 * dp);
        box.setLayoutParams(blp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding((int) (14 * dp), (int) (4 * dp),
                (int) (6 * dp), 0);

        TextView label = new TextView(this);
        label.setText(plainBox ? getString(R.string.ai_copybox_label)
                : sg.lang.toUpperCase(Locale.ROOT));
        label.setTextSize(11f);
        label.setTextColor(muted);
        label.setTypeface(Typefaces.interRegular(this));
        head.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding((int) (10 * dp), (int) (8 * dp),
                (int) (10 * dp), (int) (8 * dp));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setForeground(getDrawable(R.drawable.home_ripple_pill));
        btn.setContentDescription(getString(R.string.ai_copy));
        final ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_copy);
        ic.setImageTintList(ColorStateList.valueOf(brand));
        btn.addView(ic, new LinearLayout.LayoutParams(
                (int) (14 * dp), (int) (14 * dp)));
        final TextView tx = new TextView(this);
        tx.setText(R.string.ai_copy);
        tx.setTextSize(12f);
        tx.setTextColor(brand);
        tx.setTypeface(Typefaces.interRegular(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = (int) (6 * dp);
        btn.addView(tx, tlp);
        final Runnable back = () -> {
            ic.setImageResource(R.drawable.ic_copy);
            tx.setText(R.string.ai_copy);
        };
        btn.setOnClickListener(x -> {
            if (!copyText(sg.body)) return;       // ONLY this block
            ic.setImageResource(R.drawable.ic_tick);
            tx.setText(R.string.ai_copied);
            h.removeCallbacks(back);
            h.postDelayed(back, 1500L);
        });
        head.addView(btn);
        box.addView(head);

        TextView code = new TextView(this);
        code.setText(sg.body);
        code.setTextSize(12.5f);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextColor(Fx.color(this, R.color.home_ink));
        code.setTextIsSelectable(true);
        code.setPadding((int) (14 * dp), (int) (2 * dp),
                (int) (14 * dp), (int) (14 * dp));
        if (plainBox) {
            box.addView(code, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            code.setHorizontallyScrolling(true);   // code keeps its lines
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(code, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    /**
     * One AI message's speech: owns the icon state (speaker / pause / play)
     * and the live word highlight. Being the TtsPlayer listener itself is
     * how a tap knows whether THIS message is the one that is speaking.
     */
    private final class RowSpeech implements TtsPlayer.Listener {
        final List<TextView> views;
        final List<Integer> offs;
        final String spoken;
        View button;
        ImageView icon;
        BackgroundColorSpan span;
        TextView spanOn;

        RowSpeech(List<TextView> views, List<Integer> offs, String spoken) {
            this.views = views;
            this.offs = offs;
            this.spoken = spoken;
        }

        @Override public void onStateChanged(int st) {
            if (icon != null) {
                icon.setImageResource(st == TtsPlayer.PLAYING
                        ? R.drawable.ic_pause
                        : st == TtsPlayer.PAUSED ? R.drawable.ic_play
                        : R.drawable.ic_volume);
                icon.setImageTintList(ColorStateList.valueOf(Fx.color(
                        AiActivity.this, st == TtsPlayer.IDLE
                                ? R.color.home_muted : R.color.home_brand)));
            }
            if (button != null) {
                button.setContentDescription(getString(
                        st == TtsPlayer.PLAYING ? R.string.ai_pause
                                : st == TtsPlayer.PAUSED ? R.string.ai_resume
                                : R.string.ai_listen));
            }
            if (st == TtsPlayer.IDLE) clearHighlight();
        }

        @Override public void onWord(int start, int end) {
            clearHighlight();
            if (start < 0) return;
            for (int i = 0; i < views.size(); i++) {
                TextView t = views.get(i);
                int off = offs.get(i);
                CharSequence cs = t.getText();
                if (start >= off && start < off + cs.length()
                        && cs instanceof Spannable) {
                    span = new BackgroundColorSpan(
                            (Fx.color(AiActivity.this, R.color.home_brand)
                                    & 0x00FFFFFF) | 0x40000000);
                    ((Spannable) cs).setSpan(span, start - off,
                            Math.min(end - off, cs.length()),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanOn = t;
                    return;
                }
            }
        }

        @Override public void onError(String message) {
            Ui.toast(AiActivity.this, getString(R.string.ai_tts_failed));
        }

        void clearHighlight() {
            if (span != null && spanOn != null) {
                CharSequence cs = spanOn.getText();
                if (cs instanceof Spannable) ((Spannable) cs).removeSpan(span);
            }
            span = null;
            spanOn = null;
        }
    }

    /** Bitmap -> raw base64 (the images[] format /api/chat expects). */
    private static String rawBase64(Bitmap bmp) {
        if (bmp == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 84, bos);
        return android.util.Base64.encodeToString(bos.toByteArray(),
                android.util.Base64.NO_WRAP);
    }

    // ═══════════════════════════════ AI file blocks (v1.1.5) ═══════════

    /** Parse the body of one ```file block: first line = JSON meta
     *  {name, mime}, the rest = the file content. Tolerant of CRLF. */
    private static FileBlock parseFileBlock(String body) {
        try {
            String b = body.replace("\r\n", "\n");
            int nl = b.indexOf('\n');
            if (nl <= 0) return null;
            JSONObject meta = new JSONObject(b.substring(0, nl));
            String name = meta.optString("name", "");
            if (name.isEmpty()) return null;
            FileBlock f = new FileBlock();
            f.name = name;
            f.mime = meta.optString("mime", "text/plain");
            f.content = b.substring(nl + 1).replaceFirst("\n$", "");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.1.8: a ```file block whose meta line didn't parse STILL becomes a
     *  file card — the owner must never see raw JSON soup because a model
     *  forgot the exact format. First line that looks like JSON is dropped
     *  as (broken) meta; everything else is the content. */
    private static FileBlock fallbackFileBlock(String body, int index) {
        String b = body.replace("\r\n", "\n");
        String content = b;
        int nl = b.indexOf('\n');
        if (nl > 0) {
            String first = b.substring(0, nl).trim();
            if (first.startsWith("{") && first.endsWith("}")) {
                content = b.substring(nl + 1);
            }
        }
        if (content.trim().isEmpty()) return null;
        FileBlock f = new FileBlock();
        f.name = "file-" + (index + 1) + ".txt";
        f.mime = "text/plain";
        f.content = content.replaceFirst("\n$", "");
        return f;
    }

    /** Parse one ```pdffile block: a single JSON line
     *  {name, title, prompt} — the server renders the actual PDF. */
    private static PdfBlock parsePdfBlock(String body) {
        try {
            JSONObject meta = new JSONObject(body.trim());
            String name = meta.optString("name", "");
            if (name.isEmpty()) return null;
            PdfBlock p = new PdfBlock();
            p.name = name;
            p.title = meta.optString("title", name);
            p.prompt = meta.optString("prompt", "");
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.1.8: parse one ```image block — JSON {"prompt":"..."}, or the
     *  whole body as the prompt when the model skipped the JSON. */
    private static ImageBlock parseImageBlock(String body) {
        String b = body.replace("\r\n", "\n").trim();
        if (b.isEmpty() || b.length() > 900) return null;
        try {
            JSONObject meta = new JSONObject(b);
            String p = meta.optString("prompt", "").trim();
            if (!p.isEmpty()) {
                ImageBlock ib = new ImageBlock();
                ib.prompt = p;
                return ib;
            }
        } catch (Throwable ignored) {}
        ImageBlock ib = new ImageBlock();
        ib.prompt = b;
        return ib;
    }

    private static final java.util.regex.Pattern BLOCK_RE =
            java.util.regex.Pattern.compile(
                    "```(pdffile|file|image)\\r?\\n([\\s\\S]*?)```");

    /** Pull every ```file / ```pdffile / ```image block out of an AI reply;
     *  the blocks land in `out`, the cleaned text (blocks removed) returns.
     *  Mirrors the website's extractMsgFiles(). v1.1.8: regex-based (CRLF
     *  safe), image blocks, and malformed ```file blocks still become cards
     *  via fallbackFileBlock instead of leaking raw JSON into the bubble. */
    static String stripFileBlocks(String text, List<Object> out) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = BLOCK_RE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String kind = m.group(1);
            String body = m.group(2);
            Object blk = "file".equals(kind) ? parseFileBlock(body)
                    : "pdffile".equals(kind) ? parsePdfBlock(body)
                    : parseImageBlock(body);
            if (blk == null && "file".equals(kind)) {
                blk = fallbackFileBlock(body, out.size());
            }
            if (blk == null) continue;   // unparseable pdf/image: leave the text
            out.add(blk);
            sb.append(text, last, m.start());
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** File cards under an AI reply — one per delivered file, plus one
     *  image card per ```image block. */
    private void addFileCards(View row, List<Object> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        LinearLayout box = row.findViewById(R.id.ai_files);
        if (box == null) return;
        box.setVisibility(View.VISIBLE);
        for (Object b : blocks) {
            box.addView(b instanceof ImageBlock
                    ? buildImageCard((ImageBlock) b)
                    : buildFileCard(b));
        }
    }

    /** The card itself: icon + name + meta, Preview + download — tap
     *  anywhere on it to preview (owner order v1.1.5). */
    private View buildFileCard(final Object block) {
        float dp = getResources().getDisplayMetrics().density;
        final boolean isPdf = block instanceof PdfBlock;
        final String name, mime;
        String meta;
        if (isPdf) {
            PdfBlock b = (PdfBlock) block;
            name = b.name;
            mime = "application/pdf";
            meta = "PDF document";
        } else {
            FileBlock f = (FileBlock) block;
            name = f.name;
            mime = f.mime == null || f.mime.isEmpty() ? "text/plain" : f.mime;
            meta = mime + " · " + Ui.size(f.content
                    .getBytes(StandardCharsets.UTF_8).length);
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int) (12 * dp), (int) (10 * dp),
                (int) (8 * dp), (int) (10 * dp));
        card.setBackground(getResources().getDrawable(R.drawable.row_card));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = (int) (8 * dp);
        card.setLayoutParams(cp);

        String kind = Ui.kind(mime, name);
        int iconRes = "pdf".equals(kind) ? R.drawable.ic_pdf
                : "image".equals(kind) ? R.drawable.ic_image
                : "doc".equals(kind) ? R.drawable.ic_doc : R.drawable.ic_file;
        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (30 * dp), (int) (30 * dp)));
        ic.setImageResource(iconRes);
        ic.setColorFilter(Fx.color(this, R.color.home_brand));
        card.addView(ic);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = (int) (10 * dp);
        txt.setLayoutParams(tp);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(13);
        nm.setTypeface(Typefaces.interMedium(this));
        nm.setTextColor(Fx.color(this, R.color.home_ink));
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        txt.addView(nm);
        TextView mt = new TextView(this);
        mt.setText(meta);
        mt.setTextSize(10.5f);
        mt.setTypeface(Typefaces.interRegular(this));
        mt.setTextColor(Fx.color(this, R.color.home_muted));
        txt.addView(mt);
        card.addView(txt);

        TextView preview = new TextView(this);
        preview.setText(R.string.ai_preview);
        preview.setTextSize(12);
        preview.setTypeface(Typefaces.interMedium(this));
        preview.setTextColor(Fx.color(this, R.color.home_brand));
        preview.setPadding((int) (10 * dp), (int) (6 * dp),
                (int) (10 * dp), (int) (6 * dp));
        preview.setBackgroundResource(R.drawable.text_btn_bg);
        card.addView(preview);
        preview.setOnClickListener(v -> previewFile(block));

        FrameLayout dl = new FrameLayout(this);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        dp2.leftMargin = (int) (6 * dp);
        dl.setLayoutParams(dp2);
        dl.setBackgroundResource(R.drawable.text_btn_bg);
        ImageView di = new ImageView(this);
        di.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (16 * dp), (int) (16 * dp), Gravity.CENTER));
        di.setImageResource(R.drawable.ic_download);
        di.setColorFilter(Fx.color(this, R.color.home_ink));
        dl.addView(di);
        dl.setOnClickListener(v -> downloadFile(block));
        card.addView(dl);

        card.setOnClickListener(v -> previewFile(block));
        return card;
    }

    // ══ v1.1.8: ```image block card ═════════════════════════════════

    /** The image URL for one ```image block. The seed is DERIVED FROM THE
     *  PROMPT so the same block always draws the same picture — reopening
     *  the chat (or another device) shows the identical image, exactly like
     *  a stored attachment would. */
    private static String imageUrlFor(String prompt) {
        int seed = Math.abs(prompt.trim().hashCode());
        return "https://image.pollinations.ai/prompt/"
                + URLEncoder.encode(prompt) + "?width=768&height=768"
                + "&nologo=true&seed=" + seed;
    }

    /** One ```image block rendered as a rounded card: "Generating image…"
     *  placeholder, swapped for the drawn bitmap on arrival; tap opens the
     *  fullscreen viewer (Download + Share work there). */
    private View buildImageCard(final ImageBlock b) {
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        card.setBackgroundResource(R.drawable.img_card_bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = (int) (10 * dp);
        cp.bottomMargin = (int) (2 * dp);
        card.setLayoutParams(cp);

        final ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        img.setAdjustViewBounds(true);
        img.setVisibility(View.GONE);
        card.addView(img);

        final TextView note = new TextView(this);
        note.setText(R.string.ai_image_card_working);
        note.setTextSize(12.5f);
        note.setTypeface(Typefaces.interMedium(this));
        note.setTextColor(Fx.color(this, R.color.home_muted));
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, (int) (26 * dp), 0, (int) (26 * dp));
        card.addView(note);

        drawImageInto(b, img, note, card, dp);
        card.setOnClickListener(v -> {
            Object tag = card.getTag();
            if (tag instanceof Bitmap) {
                showImageViewer((Bitmap) tag, null, "ai-image");
            } else if (card.getTag() instanceof String) {
                // failed — tap retries
                note.setText(R.string.ai_image_card_working);
                drawImageInto(b, img, note, card, dp);
            }
        });
        return card;
    }

    /** Fetch the image for one block on a worker thread and swap the card
     *  contents on arrival. The card tag carries the bitmap (or "failed"). */
    private void drawImageInto(final ImageBlock b, final ImageView img,
                               final TextView note, final View card, final float dp) {
        card.setTag(null);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(imageUrlFor(b.prompt)).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(90000);
                if (c.getResponseCode() == 200) {
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    bmp = BitmapFactory.decodeByteArray(
                            bos.toByteArray(), 0, bos.size());
                }
            } catch (Throwable ignored) {}
            final Bitmap out = bmp;
            h.post(() -> {
                if (isFinishing()) return;
                if (out != null) {
                    note.setVisibility(View.GONE);
                    img.setImageBitmap(out);
                    img.setVisibility(View.VISIBLE);
                    card.setTag(out);
                } else {
                    note.setText(R.string.ai_image_card_failed);
                    card.setTag("failed");
                }
            });
        }, "xd-ai-img").start();
    }

    /** What happens when a file card is tapped. */
    private void previewFile(Object block) {
        if (block instanceof PdfBlock) {
            fetchAndShowPdf((PdfBlock) block);
            return;
        }
        FileBlock f = (FileBlock) block;
        String n = f.name.toLowerCase(Locale.ROOT);
        String m = f.mime == null ? "" : f.mime.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm") || m.contains("html")) {
            showHtmlPreview(f.name, f.content);
        } else if (m.startsWith("image/") || n.endsWith(".png")
                || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            // an AI-emitted image arrives as raw base64 — Claude-style
            try {
                String b64 = f.content.trim()
                        .replaceFirst("^data:[^,]+,", "");
                byte[] bytes = android.util.Base64.decode(b64,
                        android.util.Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0,
                        bytes.length);
                if (bmp != null) {
                    showImageViewer(bmp, null, f.name);
                    return;
                }
            } catch (Throwable ignored) {}
            showTextPreview(f.name, f.content);
        } else {
            showTextPreview(f.name, f.content);
        }
    }

    /** Download button — saves into the device's Downloads. */
    private void downloadFile(final Object block) {
        new Thread(() -> {
            String err = null, path = null;
            try {
                String name, mime;
                byte[] bytes;
                if (block instanceof PdfBlock) {
                    PdfBlock b = (PdfBlock) block;
                    name = b.name.endsWith(".pdf") ? b.name : b.name + ".pdf";
                    mime = "application/pdf";
                    bytes = fetchPdfBytes(b);
                    if (bytes == null) throw new Exception("pdf unavailable");
                } else {
                    FileBlock f = (FileBlock) block;
                    name = f.name;
                    mime = f.mime == null || f.mime.isEmpty()
                            ? "text/plain" : f.mime;
                    bytes = f.content.getBytes(StandardCharsets.UTF_8);
                }
                path = Ui.saveToDownloads(AiActivity.this, bytes, name, mime);
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final String e = err, p = path;
            h.post(() -> {
                if (isFinishing()) return;
                if (e != null) {
                    Ui.toast(AiActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                } else {
                    Ui.toast(AiActivity.this,
                            getString(R.string.ai_saved_downloads) + " " + p);
                }
            });
        }, "xd-file-dl").start();
    }

    /** Ask the worker to render the PDF (the website's engine). */
    private static byte[] fetchPdfBytes(PdfBlock b) {
        try {
            JSONObject body = new JSONObject();
            body.put("prompt", b.prompt == null || b.prompt.isEmpty()
                    ? b.title : b.prompt);
            body.put("title", b.title == null ? b.name : b.title);
            return ApiClient.requestBytesReturn("/api/pdf/file", body);
        } catch (Throwable t) {
            return null;
        }
    }

    private void fetchAndShowPdf(final PdfBlock b) {
        Ui.toast(this, R.string.ai_prep);
        new Thread(() -> {
            byte[] pdf = fetchPdfBytes(b);
            final List<Bitmap> pages = new ArrayList<>();
            if (pdf != null) {
                try {
                    File tmp = new File(getCacheDir(),
                            "preview_" + System.currentTimeMillis() + ".pdf");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    fo.write(pdf);
                    fo.close();
                    RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(
                            raf.getFD());
                    PdfRenderer pr = new PdfRenderer(pfd);
                    int n = Math.min(pr.getPageCount(), 30);
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page pg = pr.openPage(i);
                        int w = Math.min(1080, pg.getWidth() * 2);
                        int hgt = Math.max(1, (int) ((long) pg.getHeight()
                                * w / Math.max(1, pg.getWidth())));
                        Bitmap bmp = Bitmap.createBitmap(w, hgt,
                                Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(android.graphics.Color.WHITE);
                        pg.render(bmp, null, null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        pages.add(bmp);
                    }
                    pr.close();
                    pfd.close();
                    raf.close();
                    tmp.delete();
                } catch (Throwable ignored) {}
            }
            h.post(() -> {
                if (isFinishing()) return;
                if (pages.isEmpty()) {
                    Ui.toast(AiActivity.this, R.string.ai_pdf_failed);
                    return;
                }
                showPagesPreview(b.name, pages);
            });
        }, "xd-pdf-view").start();
    }

    // ── fullscreen preview dialogs ───────────────────────────────

    /** A fullscreen black dialog — previews live here. */
    private Dialog fullDialog() {
        Dialog d = new Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.BLACK));
        }
        return d;
    }

    /** The little round ✕ at the top-right that closes a fullscreen
     *  preview (owner order: cross in the fullscreen preview too). */
    private View cross(final Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp), Gravity.TOP | Gravity.END);
        bp.topMargin = bp.rightMargin = (int) (14 * dp);
        b.setLayoutParams(bp);
        b.setBackgroundResource(R.drawable.att_remove_bg);
        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (15 * dp), (int) (15 * dp), Gravity.CENTER));
        ic.setImageResource(R.drawable.ic_close);
        ic.setColorFilter(Color.WHITE);
        b.addView(ic);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** A slim title strip so previews say which file they are. */
    private TextView previewTitle(String name) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding((int) (16 * dp), (int) (16 * dp),
                (int) (60 * dp), (int) (8 * dp));
        return t;
    }

    /** Fullscreen image (older call sites): the same viewer, no file. */
    private void showFullImage(Bitmap bmp) {
        showImageViewer(bmp, null, null);
    }

    /** Step 5: fullscreen image viewer — ✕ top-left; Download + a white
     *  Share pill top-right (Claude-app layout). Tap the picture to hide
     *  or show the bars. {@code src} (optional) is the original file so
     *  Download keeps full quality; otherwise the bitmap is re-encoded. */
    private void showImageViewer(final Bitmap bmp, final File src,
                                 final String name) {
        if (bmp == null || isFinishing()) return;
        final float dp = getResources().getDisplayMetrics().density;
        final Dialog d = fullDialog();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView img = new ImageView(this);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(bmp);
        root.addView(img);

        int sbId = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        int sb = sbId > 0 ? getResources().getDimensionPixelSize(sbId) : 0;

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (14 * dp), sb + (int) (10 * dp),
                (int) (14 * dp), (int) (10 * dp));
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        bar.addView(viewerCircle(R.drawable.ic_close,
                getString(R.string.ai_viewer_close_cd), d::dismiss));

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0, 1, 1f));
        bar.addView(spacer);

        View dl = viewerCircle(R.drawable.ic_download,
                getString(R.string.ai_viewer_download_cd),
                () -> saveImageToPictures(bmp, src, name));
        ((LinearLayout.LayoutParams) dl.getLayoutParams()).rightMargin =
                (int) (8 * dp);
        bar.addView(dl);

        TextView share = new TextView(this);
        share.setText(R.string.ai_viewer_share);
        share.setTextSize(15);
        share.setTypeface(Typefaces.interMedium(this));
        share.setTextColor(Color.BLACK);
        share.setGravity(Gravity.CENTER);
        share.setBackgroundResource(R.drawable.viewer_share_bg);
        share.setPadding((int) (22 * dp), 0, (int) (22 * dp), 0);
        share.setClickable(true);
        share.setOnClickListener(v -> shareImage(bmp, src, name));
        bar.addView(share, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * dp)));
        root.addView(bar);

        img.setOnClickListener(v -> bar.setVisibility(
                bar.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        d.setContentView(root);
        d.show();
    }

    /** A 44dp dark round icon button for the image viewer's top bar. */
    private View viewerCircle(int iconRes, String cd, final Runnable go) {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(this);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (44 * dp), (int) (44 * dp)));
        b.setBackgroundResource(R.drawable.viewer_btn_bg);
        b.setClickable(true);
        b.setContentDescription(cd);
        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
        ic.setImageResource(iconRes);
        ic.setColorFilter(Color.WHITE);
        b.addView(ic);
        b.setOnClickListener(v -> go.run());
        return b;
    }

    /** Bytes to save/share: the original file when we have one, else the
     *  bitmap re-encoded (PNG for .png names, JPEG otherwise). */
    private byte[] imageBytes(Bitmap bmp, File src, String name) {
        try {
            if (src != null && src.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return bos.toByteArray();
            }
        } catch (Throwable ignored) {}
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean png = name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".png");
        bmp.compress(png ? Bitmap.CompressFormat.PNG
                : Bitmap.CompressFormat.JPEG, 95, bos);
        return bos.toByteArray();
    }

    private static String imageMimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /** A friendly file name for a saved / shared image. */
    private static String imageFileName(String name) {
        boolean png = name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".png");
        return "XavierAI_" + System.currentTimeMillis()
                + (png ? ".png" : ".jpg");
    }

    /** Download button: save into Pictures/XavierDrive. */
    private void saveImageToPictures(final Bitmap bmp, final File src,
                                     final String name) {
        new Thread(() -> {
            String where = null, err = null;
            try {
                byte[] data = imageBytes(bmp, src, name);
                where = Ui.saveToPictures(AiActivity.this, data,
                        imageFileName(name), imageMimeOf(name));
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String w = where, e = err;
            h.post(() -> {
                if (isFinishing()) return;
                if (e != null) {
                    Ui.toast(AiActivity.this,
                            getString(R.string.ai_viewer_save_failed));
                } else {
                    Ui.toast(AiActivity.this,
                            getString(R.string.ai_viewer_saved, w));
                }
            });
        }, "xd-img-save").start();
    }

    /** Share button: write a temp copy in cache/aiimg (already exposed by
     *  the FileProvider — generated images open from there) and hand it
     *  to the system share sheet. */
    private void shareImage(final Bitmap bmp, final File src,
                            final String name) {
        try {
            File dir = new File(getCacheDir(), "aiimg");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, imageFileName(name));
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(imageBytes(bmp, src, name));
            fo.close();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".files", out);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(imageMimeOf(name));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(ClipData.newRawUri("", uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,
                    getString(R.string.ai_viewer_share)));
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.ai_viewer_share_failed));
        }
    }

    /** Fullscreen HTML — rendered in a WebView, like the website. */
    private void showHtmlPreview(String name, String html) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        WebView wv = new WebView(this);
        // SECURITY: this renders HTML written by the AI (and therefore by
        // whatever a student typed into the prompt). JS stays on so
        // interactive pages work, but the page is sealed in a sandbox:
        // no network of any kind, no file/content access, no popups, no
        // navigation, no JS bridges. Even a page carrying a malicious
        // script cannot read anything or send anything anywhere.
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setSupportMultipleWindows(false);
        ws.setGeolocationEnabled(false);
        ws.setBlockNetworkLoads(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return true;                      // never navigate away
            }
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("data:") || u.startsWith("about:")) return null;
                return new WebResourceResponse("text/plain", "UTF-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        wv.setBackgroundColor(android.graphics.Color.WHITE);
        // Content-Security-Policy first in the document: nothing may load
        // or connect except inline style/script and data: images/fonts
        final String csp = "<meta http-equiv=\"Content-Security-Policy\" "
                + "content=\"default-src 'none'; style-src 'unsafe-inline'; "
                + "script-src 'unsafe-inline'; img-src data:; font-src data:; "
                + "media-src data:; form-action 'none'; base-uri 'none'; "
                + "frame-src 'none'\">";
        wv.loadDataWithBaseURL(null, csp + (html == null ? "" : html),
                "text/html", "UTF-8", null);
        root.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        // the cross needs to float above the WebView — attach to the
        // dialog's decor once shown
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    /** Fullscreen plain-text/notes/code viewer. */
    private void showTextPreview(String name, String content) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        ScrollView sc = new ScrollView(this);
        TextView t = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        t.setText(content == null ? "" : content);
        t.setTextSize(12.5f);
        t.setTypeface(Typefaces.interRegular(this));
        t.setTextColor(android.graphics.Color.WHITE);
        t.setPadding((int) (16 * dp), (int) (10 * dp),
                (int) (16 * dp), (int) (24 * dp));
        sc.addView(t);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    /** Fullscreen PDF — server-rendered pages scroll top to bottom. */
    private void showPagesPreview(String name, List<Bitmap> pages) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF101014);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        for (Bitmap bmp : pages) {
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(bmp);
            LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams)
                    iv.getLayoutParams();
            ip.leftMargin = ip.rightMargin = (int) (10 * dp);
            ip.topMargin = (int) (10 * dp);
            iv.setLayoutParams(ip);
            list.addView(iv);
        }
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    // ═════════════════════════════════════ sending ═══════════════

    private void focusInput() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    /** v1.1.8: the server-side usage of THIS account, straight from the
     *  backend's durable daily counter — shown in the app bar chip the
     *  moment the AI screen opens and refreshed after every answer. */
    private void fetchQuota() {
        new Thread(() -> {
            try {
                ApiClient.Resp r = ApiClient.request("GET", "/api/quota");
                if (r.ok && r.json != null) {
                    final int used = r.json.optInt("used", -1);
                    final int limit = r.json.optInt("limit", -1);
                    if (used >= 0 && limit > 0) {
                        h.post(() -> {
                            if (!isFinishing()) showQuota(used, limit);
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }, "xd-quota").start();
    }

    /** v1.1.8: explicit image requests skip the chat model entirely and go
     *  straight to the image engine — the website's behaviour, ported so
     *  "draw me a cat" draws a cat instead of the model apologising. */
    private static final java.util.regex.Pattern IMG_T1 =
            java.util.regex.Pattern.compile(
                    "\\b(generate|create|draw|make|paint|illustrate|produce|render)\\s+"
                            + "(a\\s+|an\\s+|me\\s+a\\s+|me\\s+an\\s+)?"
                            + "(random\\s+|realistic\\s+|cute\\s+|detailed\\s+|simple\\s+|beautiful\\s+|pencil\\s+|watercolor\\s+|cartoon\\s+)?"
                            + "(image|picture|photo|illustration|drawing|artwork|sketch|painting|poster|portrait|scene|wallpaper|art)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern IMG_T2 =
            java.util.regex.Pattern.compile("^(draw|paint|illustrate|sketch)\\s+.{3}",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern IMG_T3 =
            java.util.regex.Pattern.compile(
                    "\\b(pencil\\s+sketch|pencil\\s+drawing|watercolor|pixel\\s+art|random\\s+sketch|random\\s+drawing|digital\\s+art)\\s+(of\\s+)?",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static boolean isImageRequest(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() < 4 || t.length() > 400) return false;
        return IMG_T1.matcher(t).find() || IMG_T2.matcher(t).find()
                || IMG_T3.matcher(t).find();
    }

    /** GREY when there is nothing to send, BLUE the moment there is. */
    private void refreshSendUi() {
        boolean ready = input.getText().toString().trim().length() > 0
                || !attach.isEmpty();
        ((ImageView) sendIcon).setImageResource(R.drawable.ic_arrow_up);
        send.setBackgroundResource(ready
                ? R.drawable.ai_send_ready : R.drawable.ai_send_idle);
        ((ImageView) sendIcon).setColorFilter(ready
                ? Color.WHITE
                : Fx.color(this, R.color.home_muted));
    }

    /** v1.1.8: the send button's WORKING state — a red STOP square. One tap
     *  on it ends the in-flight task immediately (owner order). */
    private void setBusyUi(boolean on) {
        if (on) {
            send.setBackgroundResource(R.drawable.ai_stop_bg);
            ((ImageView) sendIcon).setImageResource(R.drawable.ic_stop);
            ((ImageView) sendIcon).setColorFilter(Color.WHITE);
            send.setAlpha(1f);
            send.setContentDescription(getString(R.string.ai_stop_cd));
        } else {
            send.setContentDescription(null);
            refreshSendUi();
        }
    }

    /** The active cancellation handles (null when idle). */
    private ApiClient.Ctl streamCtl;
    private ApiClient.Ctl imgCtl;

    /** STOP — aborts whatever the AI is doing right now, with no further
     *  movement. The server-side quota charge stands (owner order: a stopped
     *  task still counts as usage). */
    private void stopGeneration() {
        XLog.i("ai", "STOP — user ended the task mid-work");
        if (streamCtl != null) streamCtl.abort();
        if (imgCtl != null) imgCtl.abort();
    }

    /** step 8: "12/30 today" — the server reports the used/limit pair on
     *  every answer (JSON response or the stream's done event). */
    private void showQuota(int used, int limit) {
        if (quotaView == null || limit <= 0) return;
        try {
            quotaView.setText(getString(R.string.ai_quota_today,
                    Math.min(used, limit), limit));
            quotaView.setVisibility(View.VISIBLE);
            quotaView.setTextColor(Fx.color(this, used >= limit
                    ? R.color.danger : R.color.home_muted));
        } catch (Throwable ignored) {}
    }

    /** v1.1.8: refreshSendUi + setBusyUi live in the sending section below. */

    private float composedFontSize() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat(AiSettingsActivity.KEY_FONT_SIZE,
                        AiSettingsActivity.DEFAULT_FONT_SIZE);
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (busy) return;
        if (imageMode) {
            if (text.isEmpty()) {
                Ui.toast(this, getString(R.string.ai_menu_image));
                return;
            }
            input.setText("");
            generateImage(text);
            return;
        }
        if (text.isEmpty() && attach.isEmpty()) return;

        // v1.1.8: log the exchange (never the message content itself)
        XLog.i("ai", "send: " + text.length() + " chars"
                + (attach.isEmpty() ? "" : ", " + attach.size() + " attachment(s)")
                + ", session=" + (currentId == null ? "?" : currentId));

        // v1.1.8: an explicit image request goes straight to the image
        // engine — never through the chat model (which cannot draw)
        if (!text.isEmpty() && attach.isEmpty() && isImageRequest(text)) {
            input.setText("");
            generateImage(text);
            return;
        }

        busy = true;
        input.setText("");
        input.setEnabled(false);
        setBusyUi(true);
        emptyState.setVisibility(View.GONE);

        // everything below belongs to THIS conversation — even if the
        // user switches sessions while the answer is in flight
        final String sid = currentId;
        final boolean firstExchange = histOf(sid).length() == 0;

        // visible text mirrors the website: attachments are announced
        StringBuilder userText = new StringBuilder(text);
        StringBuilder fileContents = new StringBuilder();
        JSONArray images = new JSONArray();
        final List<Att> sent = new ArrayList<>();
        if (!attach.isEmpty()) {
            sent.addAll(attach);
            StringBuilder names = new StringBuilder();
            for (Att a : attach) names.append(a.name).append(", ");
            String clean = names.toString().replaceAll(", $", "");
            if (text.isEmpty()) {
                userText = new StringBuilder("Summarise the attached file(s)");
            }
            userText.append("\n[Attached: ").append(clean).append("]");
            for (Att a : attach) {
                if (a.image) {
                    try {
                        JSONObject im = new JSONObject();
                        im.put("mimeType", "image/jpeg");
                        im.put("base64", rawBase64(a.bmp));
                        images.put(im);
                    } catch (Throwable ignored) {}
                } else if (a.text != null) {
                    fileContents.append("\n\n--- FILE: ").append(a.name)
                            .append(" ---\n")
                            .append(a.text.substring(0, Math.min(24000,
                                    a.text.length())))
                            .append("\n--- END FILE ---");
                }
            }
            attach.clear();
            refreshAttachStrip();
        }

        String modePrefix = deepResearch
                ? "[Research mode: research this thoroughly with multiple "
                        + "web searches, then answer with sources.]\n\n"
                : "";

        String time = Ui.now("h:mm a");
        String shown = userText.toString();
        addMessage(sid, "user", shown, time, null);
        appendBubble("user", shown, time, null, -1, true);

        // v1.1.5 (owner spec): every UPLOADED file lands in this chat's
        // artifacts folder — the AI's workspace — so the AI can work
        // with it in later turns and on other devices
        if (!sent.isEmpty()) {
            final ChatSync.Session up = findSession(sid);
            if (up != null) {
                new Thread(() -> {
                    for (Att a : sent) {
                        try {
                            byte[] bytes;
                            String mime;
                            if (a.image && a.bmp != null) {
                                ByteArrayOutputStream bo =
                                        new ByteArrayOutputStream();
                                a.bmp.compress(Bitmap.CompressFormat.JPEG,
                                        88, bo);
                                bytes = bo.toByteArray();
                                mime = "image/jpeg";
                            } else if (a.text != null) {
                                bytes = a.text.getBytes(StandardCharsets.UTF_8);
                                mime = "text/plain";
                            } else {
                                continue;
                            }
                            ChatSync.saveArtifact(this, up, a.name,
                                    bytes, mime);
                        } catch (Throwable ignored) {}
                    }
                }, "xd-ws-upload").start();
            }
        }

        JSONArray hist = histOf(sid);
        final JSONObject body = new JSONObject();
        try {
            body.put("message", modePrefix + shown
                    + (fileContents.length() > 0 ? "\n\n"
                        + fileContents.substring(0, Math.min(60000,
                            fileContents.length())) : ""));
            body.put("role", st.role == null || st.role.isEmpty()
                    ? "student" : st.role);
            body.put("email", st.email);
            if (st.klass != null && !st.klass.isEmpty()) {
                body.put("class", st.klass);
            }
            if (images.length() > 0) body.put("images", images);
            // v1.1.5: the chat's workspace files — the AI knows what it
            // has to work with (its own earlier files + user uploads)
            ChatSync.Session curS = findSession(sid);
            if (curS != null && !curS.ws.isEmpty()) {
                JSONArray ws = new JSONArray();
                for (String wn : curS.ws) ws.put(wn);
                body.put("workspace", ws);
            }
            // last 30 turns BEFORE the new message — the worker appends
            // `message` itself, so including it here would double it
            JSONArray histOut = new JSONArray();
            int from = Math.max(0, hist.length() - 1 - 30);
            for (int i = from; i < hist.length() - 1; i++) {
                JSONObject m = hist.optJSONObject(i);
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("role", m.optString("role", "user"));
                o.put("text", m.optString("text", ""));
                histOut.put(o);
            }
            body.put("history", histOut);
        } catch (Throwable ignored) {}

        showTyping();
        requestAnswer(sid, body, true, firstExchange);
    }

    /**
     * POST /api/chat and place the answer. `saveAfter` persists the
     * session to the Drive store (the website sees it immediately);
     * `firstExchange` asks the AI to name the chat (the website's flow).
     */
    private void requestAnswer(final String sid, final JSONObject body,
                               final boolean saveAfter,
                               final boolean firstExchange) {
        final long t0 = System.currentTimeMillis();
        new Thread(() -> {
            String answer = null, err = null;
            // step 6: stream typed agentic events (steps / summaries /
            // artifacts / text). If the stream gives nothing, fall back to
            // the plain JSON endpoint — same as the website. When the stream
            // FAILED EXPLICITLY (an error event) we do NOT fall back: the
            // failed request's quota was already refunded, and retrying
            // here would silently charge the message a second time.
            // v1.1.8: the same no-fallback rule applies when the USER taps
            // stop — the task ends right there, partial text is kept, and
            // the charge stands (owner order).
            final StringBuilder streamed = new StringBuilder();
            final String[] streamErr = {null};
            final int[] quotaUsed = {-1}, quotaLimit = {-1};
            final ApiClient.Ctl ctl = new ApiClient.Ctl();
            streamCtl = ctl;
            try { body.put("agentic", true); } catch (Throwable ignored) {}
            ApiClient.Resp sr = ApiClient.streamSse("/api/chat/stream", body,
                    ev -> {
                        final String t = ev.optString("t", "");
                        if ("text".equals(t)) {
                            streamed.append(ev.optString("delta", ""));
                        } else if ("done".equals(t)) {
                            if (ev.has("quotaLimit")) {
                                quotaUsed[0] = ev.optInt("quotaUsed", -1);
                                quotaLimit[0] = ev.optInt("quotaLimit", -1);
                            }
                        } else if ("error".equals(t)) {
                            if (streamErr[0] == null) {
                                streamErr[0] = ev.optString("error",
                                        "AI service temporarily unavailable");
                            }
                        } else if ("step".equals(t) || "summary".equals(t)
                                || "artifact".equals(t)) {
                            h.post(() -> onAgentEvent(sid, ev));
                        }
                    }, ctl);
            try { body.remove("agentic"); } catch (Throwable ignored) {}

            if (ctl.cancelled) {
                // user pressed stop: keep whatever streamed, never fall
                // back. A bare stop shows a quiet "Stopped" note; partial
                // answers keep their text with a small marker so the saved
                // history reads right. Nothing else moves after this.
                answer = streamed.length() > 0
                        ? streamed.toString() + "\n\n(" + getString(R.string.ai_stopped_note) + ")"
                        : getString(R.string.ai_stopped_note);
            } else if (streamed.length() > 0) {
                answer = streamed.toString();
            } else if (streamErr[0] != null) {
                err = streamErr[0];
            } else if (sr.code == 429) {
                err = sr.error();
            } else {
                ApiClient.Resp r = ApiClient.requestJson("POST", "/api/chat",
                        body);
                if (r.ok && r.json != null) {
                    answer = r.json.optString("response", "");
                    if (answer.isEmpty()) {
                        answer = null;
                        err = getString(R.string.ai_error);
                    }
                    if (r.json.has("quotaLimit")) {
                        quotaUsed[0] = r.json.optInt("quotaUsed", -1);
                        quotaLimit[0] = r.json.optInt("quotaLimit", -1);
                    }
                    if (answer != null
                            && r.json.optBoolean("quotaExhausted", false)) {
                        answer = getString(R.string.ai_quota_warn) + "\n\n"
                                + answer;
                    }
                } else if (r.code == 429) {
                    err = r.error();
                } else {
                    err = r.error().isEmpty()
                            ? getString(R.string.ai_error) : r.error();
                }
            }
            final String ans = answer, e = err;
            final int qUsed = quotaUsed[0], qLimit = quotaLimit[0];
            h.post(() -> {
                if (isFinishing()) return;
                if (qLimit > 0 && qUsed >= 0) showQuota(qUsed, qLimit);
                // step 6: keep the trail as a collapsed "N steps" chip
                final List<String[]> trailSnap = agentTrail == null
                        ? null : agentTrail.snapshot();
                if (agentTrail != null) agentTrail.stop();
                agentTrail = null;
                hideTyping();
                busy = false;
                input.setEnabled(true);
                setBusyUi(false);
                XLog.i("ai", "reply ready: "
                        + (ans == null ? "(error/none)" : ans.length() + " chars")
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
                String t = Ui.now("h:mm a");
                boolean showing = sid.equals(currentId);
                if (ans != null) {
                    addMessage(sid, "ai", ans, t, null);
                    if (showing) {
                        appendBubble("ai", ans, t, null,
                                histOf(sid).length() - 1, true);
                        if (AgentTrail.worthKeeping(trailSnap)
                                && msgs.getChildCount() > 0
                                && msgs.getChildAt(msgs.getChildCount() - 1)
                                        instanceof LinearLayout) {
                            ((LinearLayout) msgs.getChildAt(
                                    msgs.getChildCount() - 1)).addView(
                                    AgentTrail.collapsed(this, trailSnap), 0);
                        }
                    }
                    // v1.1.5: AI-created files (```file blocks) are stored
                    // into the chat's artifacts folder — its workspace
                    final List<Object> made = new ArrayList<>();
                    stripFileBlocks(ans, made);
                    final ChatSync.Session wsSession = findSession(sid);
                    if (!made.isEmpty() && wsSession != null) {
                        new Thread(() -> {
                            for (Object mb : made) {
                                if (mb instanceof FileBlock) {
                                    FileBlock fb = (FileBlock) mb;
                                    if (wsSession.ws.contains(fb.name)) {
                                        continue;   // already in the workspace
                                    }
                                    ChatSync.saveArtifact(this, wsSession,
                                            fb.name, fb.content.getBytes(
                                                    StandardCharsets.UTF_8),
                                            fb.mime);
                                }
                            }
                        }, "xd-ws-ai").start();
                    }
                    if (saveAfter) {
                        ChatSync.Session s = findSession(sid);
                        if (s != null) {
                            persistCache();
                            ChatSync.save(this, s, null);
                        }
                    }
                    // the AI names the chat after the first reply — but NOT
                    // when the user stopped the task (no further movement)
                    if (firstExchange && !ctl.cancelled) {
                        fetchAiTitle(sid, body.optString("message", ""), ans);
                    }
                } else if (showing) {
                    appendBubble("ai", e, t, null, -1, true);
                }
                if (showing) scrollDown();
                streamCtl = null;
            });
        }, "xd-ai-send").start();
    }

    /** POST /api/chat/title — the website's AI chat-naming engine. */
    private void fetchAiTitle(final String sid, final String userMsg,
                              final String reply) {
        ChatSync.Session s = findSession(sid);
        if (s == null) return;
        final String before = s.name;
        new Thread(() -> {
            String title = null;
            try {
                JSONObject body = new JSONObject();
                body.put("message",
                        userMsg.substring(0, Math.min(1500, userMsg.length())));
                body.put("reply",
                        reply.substring(0, Math.min(1500, reply.length())));
                ApiClient.Resp r = ApiClient.requestJson("POST",
                        "/api/chat/title", body);
                if (r.ok && r.json != null) {
                    title = r.json.optString("title", "");
                }
            } catch (Throwable ignored) {}
            final String t = title;
            h.post(() -> {
                if (isFinishing() || t == null || t.trim().isEmpty()) return;
                ChatSync.Session cur = findSession(sid);
                if (cur == null) return;
                cur.name = t.trim();
                persistCache();
                ChatSync.save(this, cur, null);
                sortSessions();
                if (sid.equals(currentId)) updateTitle();
                if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                    rebuildDrawer();
                }
                if (before == null || before.isEmpty()) {
                    // nothing — the title simply replaces "Untitled"
                }
            });
        }, "xd-ai-title").start();
    }

    /** Regenerate: drop this answer (and everything after), re-ask. */
    private void regenerate(int histIndex) {
        if (busy || histIndex < 0) return;
        ChatSync.Session s = findSession(currentId);
        if (s == null) return;
        JSONArray hist = s.history;
        if (histIndex >= hist.length()) return;

        // find the user message this answer belongs to
        int userIdx = histIndex;
        while (userIdx >= 0
                && !"user".equals(hist.optJSONObject(userIdx)
                        .optString("role", ""))) {
            userIdx--;
        }
        if (userIdx < 0) return;

        // truncate: keep through the user message, drop the rest
        JSONArray kept = new JSONArray();
        for (int i = 0; i <= userIdx; i++) kept.put(hist.optJSONObject(i));
        s.history = kept;
        persistCache();

        busy = true;
        input.setEnabled(false);
        setBusyUi(true);
        renderSession();
        showTyping();

        String lastUser = hist.optJSONObject(userIdx)
                .optString("text", "");
        final JSONObject body = new JSONObject();
        try {
            body.put("message", lastUser);
            body.put("role", st.role == null || st.role.isEmpty()
                    ? "student" : st.role);
            body.put("email", st.email);
            if (st.klass != null && !st.klass.isEmpty()) {
                body.put("class", st.klass);
            }
            // the regenerated answer knows the chat's workspace too — same
            // context as send() (step 8 consistency fix)
            ChatSync.Session regS = findSession(currentId);
            if (regS != null && !regS.ws.isEmpty()) {
                JSONArray ws = new JSONArray();
                for (String wn : regS.ws) ws.put(wn);
                body.put("workspace", ws);
            }
            JSONArray histOut = new JSONArray();
            // everything before the user message being re-asked
            int from = Math.max(0, kept.length() - 1 - 30);
            for (int i = from; i < kept.length() - 1; i++) {
                JSONObject m = kept.optJSONObject(i);
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("role", m.optString("role", "user"));
                o.put("text", m.optString("text", ""));
                histOut.put(o);
            }
            body.put("history", histOut);
        } catch (Throwable ignored) {}

        requestAnswer(currentId, body, true, false);
    }

    // ═════════════════════════════════════ image generation ════════════

    /** Pollinations picture, exactly like the website's generator.
     *  Available to everyone (owner order v1.1.7 — students too). */
    private void generateImage(final String prompt) {
        XLog.i("ai", "image request: " + prompt.length() + " chars");
        busy = true;
        input.setEnabled(false);
        setBusyUi(true);
        imageMode = false;
        input.setHint(R.string.ai_hint);
        refreshModeStrip();

        final String sid = currentId;
        String time = Ui.now("h:mm a");
        String caption = getString(R.string.ai_menu_image) + " · " + prompt;
        addMessage(sid, "user", prompt, time, null);
        appendBubble("user", prompt, time, null, -1, true);
        showTyping(getString(R.string.ai_image_working));

        new Thread(() -> {
            String err = null;
            File out = null;
            byte[] bytes = null;
            final ApiClient.Ctl ictl = new ApiClient.Ctl();
            imgCtl = ictl;
            try {
                String u = "https://image.pollinations.ai/prompt/"
                        + URLEncoder.encode(prompt, "UTF-8")
                        + "?width=768&height=768&nologo=true&seed="
                        + (int) (Math.random() * 999999);
                HttpURLConnection c = (HttpURLConnection)
                        new URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(90000);
                if (c.getResponseCode() != 200) {
                    throw new Exception("HTTP " + c.getResponseCode());
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (ictl.cancelled) throw new Exception("cancelled");
                    bos.write(buf, 0, n);
                }
                in.close();
                bytes = bos.toByteArray();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0,
                        bytes.length);
                if (bmp == null) throw new Exception("bad image");

                File dir = new File(getCacheDir(), "aiimg");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "ai_" + System.currentTimeMillis()
                        + ".jpg");
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(bytes);
                fo.close();
            } catch (Throwable t) {
                err = ictl.cancelled ? "cancelled"
                        : (t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage());
            }

            final File file = out;
            final byte[] imgBytes = bytes;
            final String error = err;
            final String t2 = Ui.now("h:mm a");
            h.post(() -> {
                if (isFinishing()) return;
                hideTyping();
                busy = false;
                input.setEnabled(true);
                setBusyUi(false);
                refreshSendUi();
                boolean showing = sid.equals(currentId);
                if (error != null && "cancelled".equals(error)) {
                    // user pressed stop — end right here, nothing further
                } else if (error != null) {
                    String msg = getString(R.string.ai_image_failed);
                    addMessage(sid, "ai", msg, t2, null);
                    if (showing) appendBubble("ai", msg, t2, null, -1, true);
                } else {
                    addMessage(sid, "ai", caption, t2,
                            file == null ? null : file.getAbsolutePath());
                    if (showing) {
                        appendBubble("ai", caption, t2,
                                file == null ? null : file.getAbsolutePath(),
                                histOf(sid).length() - 1, true);
                    }
                }
                ChatSync.Session s = findSession(sid);
                if (s != null) {
                    persistCache();
                    ChatSync.save(this, s, null);
                    // artifact parity: keep the picture in the Drive
                    // session folder, like the website's saveArtifact()
                    if (error == null && imgBytes != null) {
                        new Thread(() -> {
                            try {
                                if (s.folderId == null
                                        || s.folderId.isEmpty()) {
                                    ChatSync.saveBlocking(this, s);
                                }
                                if (s.folderId != null
                                        && !s.folderId.isEmpty()) {
                                    String arts = Drive.ensureFolder(
                                            "artifacts", s.folderId);
                                    String imgs = Drive.ensureFolder(
                                            "image", arts);
                                    Drive.upload("ai-image_"
                                            + System.currentTimeMillis()
                                            + ".jpg", imgs, null, imgBytes,
                                            "image/jpeg");
                                }
                            } catch (Throwable ignored) {}
                        }, "xd-ai-artifact").start();
                    }
                }
                if (showing) scrollDown();
                imgCtl = null;
            });
        }, "xd-ai-image").start();
    }

    // ═════════════════════════════════════ typing indicator ════════════

    /** Step 6: one typed event from the stream, on the UI thread. Builds
     *  the live action rows inside the typing row (replacing the cycling
     *  "Thinking…" label). Ignored when that chat is no longer on screen. */
    private void onAgentEvent(String sid, JSONObject ev) {
        if (isFinishing() || typingRow == null || !sid.equals(currentId)) {
            return;
        }
        LinearLayout body = typingRow.findViewById(R.id.bubble_body);
        if (body == null) return;
        if (agentTrail == null) {
            h.removeCallbacks(thinkCycle);
            View label = typingRow.findViewById(R.id.bubble_text);
            if (label != null) label.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
            agentTrail = new AgentTrail(this, body);
        }
        String t = ev.optString("t", "");
        if ("step".equals(t)) {
            agentTrail.step(ev.optInt("id", 0), ev.optString("title", ""),
                    ev.optString("detail", ""),
                    ev.optString("status", "running"));
        } else if ("summary".equals(t)) {
            agentTrail.summary(ev.optString("text", ""));
        } else if ("artifact".equals(t)) {
            agentTrail.artifact(ev.optString("name", "file"));
        }
        scrollDown();
    }

    private void showTyping() {
        showTyping(THINKING[0]);
    }

    private void showTyping(String label) {
        hideTyping();
        typingRow = LayoutInflater.from(this)
                .inflate(R.layout.item_msg_ai, msgs, false);
        TextView tv = typingRow.findViewById(R.id.bubble_text);
        tv.setText(label);
        TextView tt = typingRow.findViewById(R.id.bubble_time);
        if (tt != null) tt.setText("");
        View acts = typingRow.findViewById(R.id.ai_actions);
        if (acts != null) acts.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        typingRow.setLayoutParams(lp);
        msgs.addView(typingRow);
        scrollDown();
        h.post(thinkCycle);
    }

    private void hideTyping() {
        h.removeCallbacks(thinkCycle);
        if (agentTrail != null) { agentTrail.stop(); agentTrail = null; }
        if (typingRow != null) {
            try { msgs.removeView(typingRow); } catch (Throwable ignored) {}
            typingRow = null;
        }
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ═════════════════════════════════════ the plus menu ═══════════════

    /** The ChatGPT-style small menu: Camera · Photos · Files · Image
     *  (EVERYONE — owner order v1.1.7) · Deep research. NOT focusable — the
     *  keyboard stays open (the v1.1.2 popup stole focus and the
     *  keyboard visibly closed and reopened; owner-reported glitch). */
    /** v1.1.7: the + menu is a bottom sheet (icon tile + title +
     *  subtitle per row), like the Claude app. */
    private void showPlusMenu() {
        if (plusMenu != null) {
            try { plusMenu.dismiss(); } catch (Throwable ignored) {}
            plusMenu = null;
        }
        final float dp = getResources().getDisplayMetrics().density;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.sheet_bg);
        sheet.setPadding(0, (int) (10 * dp), 0, (int) (18 * dp));

        View handle = new View(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                (int) (40 * dp), (int) (5 * dp));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = (int) (10 * dp);
        handle.setLayoutParams(hp);
        handle.setBackgroundResource(R.drawable.sheet_handle);
        sheet.addView(handle);

        int icons[] = {R.drawable.ic_camera, R.drawable.ic_image,
                R.drawable.ic_file, R.drawable.ic_sparkle,
                R.drawable.ic_search};
        int labels[] = {R.string.ai_menu_camera, R.string.ai_menu_photos,
                R.string.ai_menu_files, R.string.ai_menu_image,
                R.string.ai_menu_research};
        int subs[] = {R.string.ai_sheet_camera_sub,
                R.string.ai_sheet_photos_sub, R.string.ai_sheet_files_sub,
                R.string.ai_sheet_image_sub, R.string.ai_sheet_research_sub};
        final Runnable actions[] = {
                () -> pickFromCamera(),
                () -> pickPhotos(),
                () -> pickFiles(),
                () -> {
                    imageMode = true;
                    input.setHint(R.string.ai_menu_image);
                    refreshModeStrip();
                    refreshSendUi();
                    focusInput();
                },
                () -> {
                    deepResearch = !deepResearch;
                    refreshModeStrip();
                }
        };

        for (int i = 0; i < labels.length; i++) {
            // v1.1.7 (owner order): Generate image is available to students
            // too — no power gate anymore.
            final int idx = i;
            boolean active = (idx == 3 && imageMode)
                    || (idx == 4 && deepResearch);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (20 * dp), (int) (10 * dp),
                    (int) (20 * dp), (int) (10 * dp));
            row.setBackground(getResources()
                    .getDrawable(R.drawable.home_ripple_circle));

            FrameLayout tile = new FrameLayout(this);
            tile.setBackgroundResource(R.drawable.sheet_icon_bg);
            row.addView(tile, new LinearLayout.LayoutParams(
                    (int) (44 * dp), (int) (44 * dp)));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(Fx.color(this, active
                    ? R.color.home_brand : R.color.home_tile_ink));
            tile.addView(ic, new FrameLayout.LayoutParams(
                    (int) (22 * dp), (int) (22 * dp), Gravity.CENTER));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = (int) (14 * dp);
            row.addView(col, cp);

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(15.5f);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, R.color.home_ink));
            col.addView(label);

            TextView sub = new TextView(this);
            sub.setText(subs[i]);
            sub.setTextSize(12.5f);
            sub.setTextColor(Fx.color(this, R.color.home_muted));
            col.addView(sub);

            if (active) {
                ImageView tick = new ImageView(this);
                tick.setImageResource(R.drawable.ic_check);
                tick.setColorFilter(Fx.color(this, R.color.home_brand));
                row.addView(tick, new LinearLayout.LayoutParams(
                        (int) (22 * dp), (int) (22 * dp)));
            }

            row.setOnClickListener(v -> {
                d.dismiss();
                actions[idx].run();
            });
            sheet.addView(row);
        }

        d.setContentView(sheet);
        android.view.Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setGravity(Gravity.BOTTOM);
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setDimAmount(0.42f);
            win.addFlags(android.view.WindowManager.LayoutParams
                    .FLAG_DIM_BEHIND);
            win.getAttributes().windowAnimations =
                    android.R.style.Animation_InputMethod;
        }
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(x -> plusMenu = null);
        plusMenu = d;
        d.show();
    }

    private Dialog plusMenu;

    private void refreshModeStrip() {
        boolean any = deepResearch || imageMode;
        modeStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        if (deepResearch) {
            modeChip.setText(R.string.ai_research_on);
        } else if (imageMode) {
            modeChip.setText(R.string.ai_menu_image);
        }
    }

    // ═════════════════════════════════════ attachments ═════════════════

    private void pickPhotos() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK_PHOTOS);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    private void pickFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.pick_file)),
                    PICK_FILES);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    private File cameraFile;

    private void pickFromCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            cameraFile = new File(dir, "shot_"
                    + System.currentTimeMillis() + ".jpg");
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".files", cameraFile);
            Intent i = new Intent(android.provider.MediaStore
                    .ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, TAKE_PHOTO);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.ai_menu_camera) + ": " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;

        if (req == TAKE_PHOTO) {
            if (cameraFile != null && cameraFile.exists()) {
                Bitmap in = BitmapFactory.decodeFile(
                        cameraFile.getAbsolutePath());
                if (in != null && attach.size() < 4) {
                    Att a = new Att();
                    a.name = "camera.jpg";
                    a.image = true;
                    a.bmp = ProfileSync.normalize(in);
                    attach.add(a);
                    refreshAttachStrip();
                } else if (attach.size() >= 4) {
                    Ui.toast(this, getString(R.string.ai_att_max));
                }
            }
            return;
        }
        if (data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                uris.add(cd.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        final boolean asImage = (req == PICK_PHOTOS);
        for (Uri u : uris) {
            if (attach.size() >= 4) {
                Ui.toast(this, getString(R.string.ai_att_max));
                break;
            }
            resolveAttachment(u, asImage);
        }
    }

    private void resolveAttachment(Uri u, boolean forceImage) {
        String name = uriName(u);
        String mime = null;
        try {
            mime = getContentResolver().getType(u);
        } catch (Throwable ignored) {}
        if (mime == null) mime = "";
        boolean image = forceImage || mime.startsWith("image/")
                || name.toLowerCase(Locale.ROOT).endsWith(".jpg")
                || name.toLowerCase(Locale.ROOT).endsWith(".jpeg")
                || name.toLowerCase(Locale.ROOT).endsWith(".png")
                || name.toLowerCase(Locale.ROOT).endsWith(".webp");

        // the 15 MB ceiling (owner order v1.2.0)
        long size = -1;
        try {
            android.database.Cursor c = getContentResolver()
                    .query(u, null, null, null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.SIZE);
                if (ix >= 0 && c.moveToFirst()) {
                    size = c.getLong(ix);
                }
                c.close();
            }
        } catch (Throwable ignored) {}
        if (size > MAX_ATT_BYTES) {
            Ui.toast(this, getString(R.string.ai_att_too_big,
                    Ui.size(MAX_ATT_BYTES)));
            return;
        }

        if (image) {
            // images: shrink to ≤ 1024px JPEG (server caps at 1 MB each)
            try {
                Bitmap in = BitmapFactory.decodeStream(
                        getContentResolver().openInputStream(u));
                if (in != null) {
                    Att a = new Att();
                    a.name = name;
                    a.image = true;
                    a.bmp = ProfileSync.normalize(in);
                    attach.add(a);
                    refreshAttachStrip();
                    return;
                }
            } catch (Throwable ignored) {}
            Ui.toast(this, getString(R.string.error_load));
        } else {
            // documents: read as text (the website's behaviour)
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                getContentResolver().openInputStream(u),
                                "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                r.close();
                Att a = new Att();
                a.name = name;
                a.image = false;
                a.text = sb.toString();
                attach.add(a);
                refreshAttachStrip();
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.error_load));
            }
        }
    }

    private String uriName(Uri u) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver()
                    .query(u, null, null, null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.moveToFirst()) name = c.getString(ix);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (name == null) {
            String p = u.getLastPathSegment();
            name = p == null ? "file" : p;
        }
        return name;
    }

    /** Step 5: composer attachments = a horizontal row of 64dp rounded
     *  square tiles (Claude-app style). A new one is appended on the
     *  right and scrolled into view; each has a ✕ badge top-right. Tap a
     *  photo → fullscreen viewer; tap a document tile → text preview. */
    private void refreshAttachStrip() {
        attachRow.removeAllViews();
        if (attach.isEmpty()) {
            attachStrip.setVisibility(View.GONE);
            refreshSendUi();
            return;
        }
        attachStrip.setVisibility(View.VISIBLE);
        final float dp = getResources().getDisplayMetrics().density;
        for (final Att a : attach) {
            FrameLayout sq = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (64 * dp), (int) (64 * dp));
            lp.rightMargin = (int) (8 * dp);
            sq.setLayoutParams(lp);

            if (a.image && a.bmp != null) {
                ImageView img = new ImageView(this);
                img.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageBitmap(a.bmp);
                android.graphics.drawable.GradientDrawable thumb =
                        new android.graphics.drawable.GradientDrawable();
                thumb.setCornerRadius(14 * dp);
                thumb.setColor(Fx.color(this, R.color.home_tile_ink));
                img.setBackground(thumb);
                img.setClipToOutline(true);
                img.setOnClickListener(v ->
                        showImageViewer(a.bmp, null, a.name));
                sq.addView(img);
            } else {
                // document tile: icon + extension + (short) name
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                tile.setBackgroundResource(R.drawable.att_tile_bg);
                tile.setPadding((int) (6 * dp), (int) (6 * dp),
                        (int) (6 * dp), (int) (6 * dp));

                ImageView ic = new ImageView(this);
                ic.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (22 * dp), (int) (22 * dp)));
                ic.setImageResource(R.drawable.ic_file);
                ic.setColorFilter(Fx.color(this, R.color.home_muted));
                tile.addView(ic);

                String nm = a.name == null ? "" : a.name;
                int dot = nm.lastIndexOf('.');
                String ext = dot >= 0 && dot < nm.length() - 1
                        ? nm.substring(dot + 1).toUpperCase(Locale.ROOT)
                        : "FILE";
                if (ext.length() > 4) ext = ext.substring(0, 4);
                TextView et = new TextView(this);
                et.setText(ext);
                et.setTextSize(10);
                et.setTypeface(Typefaces.interMedium(this));
                et.setTextColor(Fx.color(this, R.color.home_ink));
                et.setSingleLine(true);
                et.setGravity(Gravity.CENTER);
                tile.addView(et);

                TextView nt = new TextView(this);
                nt.setText(nm);
                nt.setTextSize(9);
                nt.setTypeface(Typefaces.interRegular(this));
                nt.setTextColor(Fx.color(this, R.color.home_muted));
                nt.setSingleLine(true);
                nt.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                nt.setGravity(Gravity.CENTER);
                tile.addView(nt);

                tile.setOnClickListener(v ->
                        showTextPreview(a.name, a.text));
                sq.addView(tile);
            }

            // the ✕ badge — top-right, inset so the scroll view never clips it
            FrameLayout x = new FrameLayout(this);
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(
                    (int) (22 * dp), (int) (22 * dp),
                    Gravity.TOP | Gravity.END);
            xp.topMargin = xp.rightMargin = (int) (4 * dp);
            x.setLayoutParams(xp);
            x.setBackgroundResource(R.drawable.att_badge_bg);
            x.setContentDescription(getString(R.string.ai_att_remove_cd));
            ImageView xi = new ImageView(this);
            xi.setLayoutParams(new FrameLayout.LayoutParams(
                    (int) (10 * dp), (int) (10 * dp), Gravity.CENTER));
            xi.setImageResource(R.drawable.ic_close);
            xi.setColorFilter(Color.WHITE);
            x.addView(xi);
            x.setOnClickListener(v -> {
                attach.remove(a);
                refreshAttachStrip();
            });
            sq.addView(x);
            attachRow.addView(sq);
        }
        // a freshly added tile sits on the right — bring it into view
        attachStrip.post(() -> {
            if (attachStrip instanceof HorizontalScrollView) {
                ((HorizontalScrollView) attachStrip)
                        .fullScroll(View.FOCUS_RIGHT);
            }
        });
        refreshSendUi();
    }

    // ═════════════════════════════════════ voice input ═════════════════

    private void toggleMic() {
        if (listening) {
            if (voiceInput != null) voiceInput.stop();
            setListeningUi(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms,
                                           int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC) {
            for (int i = 0; i < perms.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(perms[i])
                        && grants[i] == PackageManager.PERMISSION_GRANTED) {
                    startListening();
                    return;
                }
            }
        }
    }

    private void startListening() {
        if (voiceInput == null) {
            voiceInput = new VoiceInput(this, new VoiceInput.Events() {
                @Override public void onListening() {
                    voiceInserted = "";
                    setListeningUi(true);
                }
                @Override public void onText(String text, boolean isFinal) {
                    if (text == null || text.trim().isEmpty()) return;
                    // dictation is APPENDED to whatever is in the box
                    // right now (including text typed while recording);
                    // only the previous partial from this session is
                    // swapped out - the user's own text is never lost
                    String cur = input.getText().toString();
                    if (!voiceInserted.isEmpty()
                            && cur.endsWith(voiceInserted)) {
                        cur = cur.substring(0,
                                cur.length() - voiceInserted.length());
                    }
                    boolean glue = !cur.isEmpty()
                            && !Character.isWhitespace(
                                    cur.charAt(cur.length() - 1));
                    String add = text.trim();
                    input.setText(cur + (glue ? " " : "") + add);
                    input.setSelection(input.getText().length());
                    voiceInserted = isFinal ? "" : add;
                }
                @Override public void onDone() {
                    setListeningUi(false);
                }
            });
        }
        voiceInput.start();
    }

    private String voiceInserted = "";
    private WaveformView micWave;
    private Runnable micPulse;

    /** Plain mic by default; BLUE with a waveform + gentle pulse while
     *  the voice is being captured (owner order v1.2.0). */
    private void setListeningUi(boolean on) {
        listening = on;
        if (micPulse != null) {
            h.removeCallbacks(micPulse);
            micPulse = null;
        }
        if (on) {
            micIcon.setVisibility(View.INVISIBLE);
            if (micWave == null) {
                micWave = new WaveformView(this);
                float d = getResources().getDisplayMetrics().density;
                FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(
                        (int) (20 * d), (int) (18 * d), Gravity.CENTER);
                ((FrameLayout) micBtn).addView(micWave, wp);
            }
            micWave.setVisibility(View.VISIBLE);
            micWave.start();
            micBtn.setBackgroundResource(R.drawable.mic_listening_bg);
            input.setHint(R.string.ai_listening);
            micBtn.setScaleX(1f);
            micBtn.setScaleY(1f);
            micPulse = new Runnable() {
                @Override public void run() {
                    if (!listening) {
                        micBtn.setScaleX(1f);
                        micBtn.setScaleY(1f);
                        return;
                    }
                    micBtn.animate().scaleX(1.09f).scaleY(1.09f)
                            .setDuration(400L)
                            .withEndAction(() -> micBtn.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(400L)
                                    .withEndAction(this).start())
                            .start();
                }
            };
            h.post(micPulse);
        } else {
            if (micWave != null) {
                micWave.stop();
                micWave.setVisibility(View.GONE);
            }
            micIcon.setVisibility(View.VISIBLE);
            micIcon.setImageResource(R.drawable.ic_mic);
            micIcon.setColorFilter(Fx.color(this, R.color.home_tile_ink));
            micBtn.setBackgroundResource(0);
            micBtn.setScaleX(1f);
            micBtn.setScaleY(1f);
            input.setHint(imageMode
                    ? R.string.ai_menu_image : R.string.ai_hint);
        }
    }

    // ═════════════════════════════════════ drawer ═════════════════════

    private void openDrawer() {
        rebuildDrawer();
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.animate().alpha(1f).setDuration(180L).start();
        int panelW = drawerPanel.getWidth();
        drawerPanel.setTranslationX(panelW > 0 ? -panelW : -320f);
        drawerPanel.animate().translationX(0f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void closeDrawer() {
        drawer.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private boolean matches(ChatSync.Session s, String q) {
        if (q.isEmpty()) return true;
        if ((s.name == null ? "" : s.name).toLowerCase(Locale.ROOT)
                .contains(q)) return true;
        for (int i = 0; i < s.history.length(); i++) {
            JSONObject m = s.history.optJSONObject(i);
            if (m != null && m.optString("text", "")
                    .toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static final int SEC_PINNED = 0, SEC_TODAY = 1, SEC_YESTERDAY = 2,
            SEC_WEEK = 3, SEC_OLDER = 4;

    private int sectionOf(ChatSync.Session s) {
        if (pins.contains(s.id)) return SEC_PINNED;
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(s.created);
        now.set(java.util.Calendar.HOUR_OF_DAY, 0);
        now.set(java.util.Calendar.MINUTE, 0);
        now.set(java.util.Calendar.SECOND, 0);
        now.set(java.util.Calendar.MILLISECOND, 0);
        long startToday = now.getTimeInMillis();
        long day = 24L * 60L * 60L * 1000L;
        if (s.created <= 0) return SEC_OLDER;
        if (s.created >= startToday) return SEC_TODAY;
        if (s.created >= startToday - day) return SEC_YESTERDAY;
        if (s.created >= startToday - 7 * day) return SEC_WEEK;
        return SEC_OLDER;
    }

    private void addSectionHeader(int sec, float dp) {
        int res = sec == SEC_PINNED ? R.string.ai_section_pinned
                : sec == SEC_TODAY ? R.string.ai_section_today
                : sec == SEC_YESTERDAY ? R.string.ai_section_yesterday
                : sec == SEC_WEEK ? R.string.ai_section_week
                : R.string.ai_section_older;
        TextView h2 = new TextView(this);
        h2.setText(res);
        h2.setTextSize(11.5f);
        h2.setAllCaps(true);
        h2.setLetterSpacing(0.06f);
        h2.setTypeface(Typefaces.interMedium(this));
        h2.setTextColor(Fx.color(this, R.color.home_muted));
        h2.setPadding((int) (12 * dp), (int) (16 * dp),
                (int) (12 * dp), (int) (6 * dp));
        drawerList.addView(h2);
    }

    private void rebuildDrawer() {
        if (drawerList == null) return;
        drawerList.removeAllViews();
        final float dp = getResources().getDisplayMetrics().density;

        boolean any = false;
        int lastSec = -1;
        // sessions is kept sorted (pinned first, then newest); walk it
        // once and emit a header whenever the section changes
        for (final ChatSync.Session s : sessions) {
            if (s.history.length() == 0) continue;
            if (!matches(s, lastQuery)) continue;
            any = true;

            int sec = sectionOf(s);
            if (sec != lastSec) {
                addSectionHeader(sec, dp);
                lastSec = sec;
            }

            final boolean active = s.id.equals(currentId);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), 0, (int) (2 * dp), 0);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * dp));
            rp.topMargin = (int) (2 * dp);
            row.setLayoutParams(rp);

            TextView title = new TextView(this);
            String name = s.name == null || s.name.isEmpty()
                    ? getString(R.string.ai_untitled) : s.name;
            title.setText(name);
            title.setTextSize(14.5f);
            title.setTypeface(Typefaces.interMedium(this));
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextColor(Fx.color(this, active
                    ? R.color.home_brand : R.color.home_ink));
            row.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // the 3-dot: a real 40dp touch target at the far RIGHT
            FrameLayout dotsBox = new FrameLayout(this);
            dotsBox.setClickable(true);
            dotsBox.setFocusable(true);
            dotsBox.setForeground(getResources()
                    .getDrawable(R.drawable.home_ripple_circle));
            dotsBox.setContentDescription(getString(R.string.ai_more_cd));
            ImageView dots = new ImageView(this);
            dots.setImageResource(R.drawable.ic_more);
            dots.setColorFilter(Fx.color(this, R.color.home_muted));
            dotsBox.addView(dots, new FrameLayout.LayoutParams(
                    (int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
            dotsBox.setOnClickListener(v -> showChatMenu(s, v));
            row.addView(dotsBox, new LinearLayout.LayoutParams(
                    (int) (40 * dp), (int) (40 * dp)));

            row.setBackground(getResources().getDrawable(
                    active ? R.drawable.chat_row_on : R.drawable.chat_row_bg));
            row.setOnClickListener(v -> {
                closeDrawer();
                currentId = s.id;
                persistCache();
                renderSession();
            });
            row.setOnLongClickListener(v -> {
                showChatMenu(s, dotsBox);
                return true;
            });
            drawerList.addView(row);
        }

        if (!any) {
            TextView none = new TextView(this);
            none.setText(lastQuery.isEmpty()
                    ? R.string.ai_history_none : R.string.ai_search_none);
            none.setTextSize(12.5f);
            none.setTypeface(Typefaces.interRegular(this));
            none.setTextColor(Fx.color(this, R.color.home_muted));
            none.setPadding((int) (14 * dp), (int) (12 * dp),
                    (int) (14 * dp), (int) (12 * dp));
            drawerList.addView(none);
        }
    }

    /** Rename · Pin · Summarize (+ Delete for Teacher/Admin/Developer) —
     *  the per-chat menu, anchored right under the 3-dot. */
    private void showChatMenu(final ChatSync.Session s, View anchor) {
        if (chatMenu != null) {
            try { chatMenu.dismiss(); } catch (Throwable ignored) {}
            chatMenu = null;
        }
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));

        final boolean pinned = pins.contains(s.id);
        // Students never see Delete (server must enforce it too)
        final boolean canDelete = st.teacherPower();

        final java.util.ArrayList<Integer> icons = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> labels = new java.util.ArrayList<>();
        final java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();

        icons.add(R.drawable.ic_edit);
        labels.add(R.string.ai_menu_rename);
        actions.add(() -> askRename(s));

        icons.add(R.drawable.ic_pin);
        labels.add(pinned ? R.string.ai_menu_unpin : R.string.ai_menu_pin);
        actions.add(() -> {
            if (pinned) pins.remove(s.id); else pins.add(s.id);
            persistCache();
            sortSessions();
            rebuildDrawer();
        });

        icons.add(R.drawable.ic_sparkle);
        labels.add(R.string.ai_menu_summarize);
        actions.add(() -> {
            if (busy) {
                Ui.toast(this, getString(R.string.ai_busy_wait));
                return;
            }
            closeDrawer();
            currentId = s.id;
            persistCache();
            renderSession();
            input.setText(getString(R.string.ai_summarize_prompt));
            input.setSelection(input.getText().length());
            send();
        });

        if (canDelete) {
            icons.add(R.drawable.ic_trash);
            labels.add(R.string.ai_menu_delete);
            actions.add(() -> confirmDeleteSession(s));
        }

        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            final boolean danger = canDelete && idx == labels.size() - 1;
            if (danger) {
                View div = new View(this);
                div.setBackgroundColor(Fx.color(this, R.color.home_hairline));
                LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) dp);
                dv.topMargin = (int) (4 * dp);
                dv.bottomMargin = (int) (4 * dp);
                box.addView(div, dv);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
            row.setForeground(getResources()
                    .getDrawable(R.drawable.home_ripple_circle));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons.get(i));
            ic.setColorFilter(Fx.color(this, danger
                    ? R.color.danger : R.color.home_muted));
            row.addView(ic, new LinearLayout.LayoutParams(
                    (int) (18 * dp), (int) (18 * dp)));
            TextView label = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = (int) (14 * dp);
            label.setLayoutParams(lp);
            label.setText(labels.get(i));
            label.setTextSize(14.5f);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, danger
                    ? R.color.danger : R.color.home_ink));
            row.addView(label);
            row.setOnClickListener(v -> {
                if (chatMenu != null) {
                    try { chatMenu.dismiss(); } catch (Throwable ignored) {}
                    chatMenu = null;
                }
                actions.get(idx).run();
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * dp)));
        }

        final int popW = (int) (208 * dp);
        PopupWindow pop = new PopupWindow(box, popW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setOutsideTouchable(true);
        pop.setElevation(14 * dp);
        pop.setBackgroundDrawable(getResources()
                .getDrawable(R.drawable.popup_menu_bg));

        // right-align under the dots; flip ABOVE when there is no room
        box.measure(View.MeasureSpec.makeMeasureSpec(popW,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int popH = box.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean below = loc[1] + anchor.getHeight() + popH
                < screenH - (int) (16 * dp);
        int xOff = anchor.getWidth() - popW;
        int yOff = below ? 0 : -(anchor.getHeight() + popH);
        pop.showAsDropDown(anchor, xOff, yOff);
        chatMenu = pop;
    }

    private PopupWindow chatMenu;

    private void askRename(final ChatSync.Session s) {
        final EditText name = new EditText(this);
        name.setText(s.name == null ? "" : s.name);
        name.setHint(R.string.ai_menu_rename);
        float dp = getResources().getDisplayMetrics().density;
        name.setPadding((int) (14 * dp), (int) (10 * dp),
                (int) (14 * dp), (int) (10 * dp));
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.ai_menu_rename)
                .setView(name)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String t = name.getText().toString().trim();
                    if (t.isEmpty()) return;
                    s.name = t;
                    persistCache();
                    ChatSync.save(this, s, null);
                    sortSessions();
                    rebuildDrawer();
                    if (s.id.equals(currentId)) updateTitle();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteSession(final ChatSync.Session target) {
        // students never delete chats (owner order v1.2.0)
        if (!st.teacherPower()) {
            Ui.toast(this, getString(R.string.ai_delete_staff_only));
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(R.string.ai_delete_chat_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    sessions.remove(target);
                    pins.remove(target.id);
                    ChatSync.delete(this, target, null);
                    if (target.id.equals(currentId)) {
                        currentId = sessions.isEmpty()
                                ? ChatSync.newId() : sessions.get(0).id;
                    }
                    persistCache();
                    rebuildDrawer();
                    renderSession();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Compact relative time for the drawer ("2 h ago"). */
    private String whenText(long ts) {
        if (ts <= 0) return "";
        long mins = (System.currentTimeMillis() - ts) / 60000L;
        if (mins < 1) return getString(R.string.ai_time_now);
        if (mins < 60) return mins + " " + getString(R.string.ai_time_min);
        long hours = mins / 60;
        if (hours < 24) return hours + " " + getString(R.string.ai_time_hour);
        long days = hours / 24;
        if (days < 7) return days + " " + getString(R.string.ai_time_day);
        return Ui.longDate(new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new java.util.Date(ts)).substring(0, 10));
    }
}
