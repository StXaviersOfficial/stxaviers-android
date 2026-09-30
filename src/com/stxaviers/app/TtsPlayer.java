package com.stxaviers.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Listen-to-a-response — TWO engines, one button (v1.2.0), now with
 * PAUSE / RESUME and a LIVE "current word" callback (v1.1.7 step 4).
 *
 *  1. CLOUD: /api/tts → Groq Orpheus WAV in the website's six voices.
 *     Word position is ESTIMATED from the playback clock, proportional
 *     to word length (the audio carries no timing data).
 *  2. ON-DEVICE: Android's own TextToSpeech. On API 26+ the engine
 *     reports the exact word range (onRangeStart); on older phones the
 *     position is estimated like the cloud voice. Android's TTS cannot
 *     pause, so pause = stop + remember the word, resume = speak the
 *     rest of the text from that word.
 *
 * One response speaks at a time: speak() stops whatever is running.
 * Everything here runs on the UI thread except the WAV download.
 * All offsets handed to Listener.onWord() are relative to the exact
 * String the caller passed to speak() (leading blanks are accounted for).
 */
public final class TtsPlayer {

    // ── public API ──────────────────────────────────────────────────────

    public static final int IDLE = 0, LOADING = 1, PLAYING = 2, PAUSED = 3;

    /** Full listener: state changes + the word being spoken. */
    public interface Listener {
        void onStateChanged(int state);
        /** start/end offsets into the text given to speak(); (-1,-1) = clear. */
        void onWord(int start, int end);
        void onError(String message);
    }

    /** Legacy two-callback listener (voice preview in AI settings). */
    public interface State {
        void onState(boolean playing);
        void onError(String message);
    }

    /** The six website voices, labels + descriptions. */
    public static final String[] VOICES = {
            "austin", "daniel", "troy", "autumn", "diana", "hannah"
    };
    public static final String[] VOICE_LABELS = {
            "Austin — clear & friendly",
            "Daniel — warm & conversational",
            "Troy — deep & authoritative",
            "Autumn — smooth & natural",
            "Diana — calm & professional",
            "Hannah — bright & expressive",
    };

    // ── state (UI thread only) ──────────────────────────────────────────

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final int MAX_CHARS = 1400;

    private static MediaPlayer player;
    private static File lastFile;
    private static String lastKey = "";

    private static TextToSpeech deviceTts;
    private static boolean deviceTtsReady;

    private static Listener cur;
    private static int state = IDLE;
    private static int gen = 0;              // bumped by every speak()/stop()
    private static String curText = "";      // exactly what is being read
    private static int curLead = 0;          // offset of curText in the caller's text
    private static boolean onDevice = false;

    // device engine bookkeeping
    private static String uttId = "";
    private static int uttSeq = 0;
    private static int devBase = 0;          // absolute char offset the utterance started at
    private static int devPos = 0;           // absolute offset of the last spoken word
    private static boolean gotRange = false; // engine delivers exact ranges
    private static long estElapsedMs = 0, estLastTick = 0;

    // word table of curText (for estimation)
    private static int[] wStart = new int[0], wEnd = new int[0];
    private static double[] wCum = new double[0];

    private TtsPlayer() {}

    public static boolean isPlaying() { return state == PLAYING; }
    public static int getState() { return state; }
    /** True when `l` is the listener of the speech that is running/paused. */
    public static boolean isCurrent(Listener l) { return l != null && l == cur && state != IDLE; }

    /** Stop everything and tell the owner of the speech it is idle. */
    public static void stop() {
        gen++;
        H.removeCallbacks(ticker);
        try {
            if (player != null) { player.stop(); player.release(); }
        } catch (Throwable ignored) {}
        player = null;
        try {
            uttId = "";                      // late engine callbacks are ignored
            if (deviceTts != null && deviceTtsReady) deviceTts.stop();
        } catch (Throwable ignored) {}
        finish();
    }

    /** Pause (cloud: MediaPlayer.pause; device: stop + remember the word). */
    public static void pause() {
        if (state != PLAYING) return;
        H.removeCallbacks(ticker);
        try {
            if (onDevice) {
                uttId = "";
                if (deviceTts != null) deviceTts.stop();
            } else if (player != null) {
                player.pause();
            }
        } catch (Throwable ignored) {}
        setState(PAUSED);
    }

    /** Continue after pause() from the same word. */
    public static void resume() {
        if (state != PAUSED) return;
        try {
            if (onDevice) {
                int from = Math.max(0, Math.min(devPos, curText.length()));
                utterFrom(from);
            } else if (player != null) {
                player.start();
                setState(PLAYING);
                estLastTick = System.currentTimeMillis();
                H.postDelayed(ticker, 60L);
            }
        } catch (Throwable t) {
            stop();
        }
    }

    /** Legacy entry point (voice preview): true/false callbacks only. */
    public static void speak(final Context c, final String text,
                             final String voice, final State cb) {
        speak(c, text, voice, new Listener() {
            boolean started = false;
            @Override public void onStateChanged(int s) {
                if (cb == null) return;
                if (s == LOADING && !started) { started = true; cb.onState(true); }
                else if (s == IDLE && started) { started = false; cb.onState(false); }
            }
            @Override public void onWord(int start, int end) {}
            @Override public void onError(String message) {
                if (cb != null) cb.onError(message);
            }
        });
    }

    /**
     * Speak `text` with `voice`. The WAV is downloaded once per text+voice
     * (replays reuse the cached file); when the cloud voice is
     * unavailable the phone's own engine reads it instead.
     */
    public static void speak(final Context c, final String text,
                             final String voice, final Listener l) {
        if (text == null || text.trim().isEmpty()) return;
        stop();
        final int myGen = gen;

        int lead = 0;
        while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) lead++;
        int end = Math.min(text.length(), lead + MAX_CHARS);
        final String trimmed = text.substring(lead, end);
        curText = trimmed;
        curLead = lead;
        cur = l;
        onDevice = false;
        buildWords(trimmed);
        setState(LOADING);

        final String v = voice == null || voice.isEmpty() ? "austin" : voice;
        final String key = v + "::" + Integer.toHexString(trimmed.hashCode());
        final Context app = c.getApplicationContext();

        new Thread(() -> {
            File wav = null;
            if (key.equals(lastKey) && lastFile != null && lastFile.exists()) {
                wav = lastFile;
            } else {
                try {
                    JSONObject body = ApiClient.obj("text", trimmed, "voice", v);
                    byte[] bytes = ApiClient.requestBytesReturn("/api/tts", body);
                    if (bytes == null || bytes.length == 0) throw new Exception("empty audio");
                    File dir = new File(app.getCacheDir(), "tts");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, key.replace(':', '_') + ".wav");
                    FileOutputStream fo = new FileOutputStream(out);
                    fo.write(bytes);
                    fo.close();
                    wav = out;
                    lastFile = out;
                    lastKey = key;
                } catch (Throwable ignored) { /* → on-device fallback */ }
            }
            final File play = wav;
            H.post(() -> {
                if (gen != myGen) return;            // cancelled while downloading
                if (play == null) { speakOnDevice(app, myGen); return; }
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(play.getAbsolutePath());
                    mp.setOnCompletionListener(x -> { if (gen == myGen) stop(); });
                    mp.setOnErrorListener((x, what, extra) -> {
                        if (gen == myGen) { Listener o = cur; stop(); if (o != null) o.onError("playback"); }
                        return true;
                    });
                    mp.prepare();
                    player = mp;
                    mp.start();
                    setState(PLAYING);
                    estLastTick = System.currentTimeMillis();
                    H.postDelayed(ticker, 60L);
                } catch (Throwable t) {
                    speakOnDevice(app, myGen);        // last resort
                }
            });
        }, "xd-tts").start();
    }

    // ── on-device engine ────────────────────────────────────────────────

    private static void speakOnDevice(final Context app, final int myGen) {
        onDevice = true;
        try {
            if (deviceTts == null) {
                deviceTts = new TextToSpeech(app, status -> {
                    deviceTtsReady = status == TextToSpeech.SUCCESS;
                    if (gen != myGen) return;
                    if (deviceTtsReady) startDevice(); else fail();
                });
            } else if (deviceTtsReady) {
                startDevice();
            } else {
                fail();
            }
        } catch (Throwable t) {
            fail();
        }
    }

    private static void startDevice() {
        try {
            deviceTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(final String id) {
                    H.post(() -> { if (id != null && id.equals(uttId)) stop(); });
                }
                @Override public void onError(final String id) {
                    H.post(() -> {
                        if (id != null && id.equals(uttId)) { Listener o = cur; stop(); if (o != null) o.onError("tts"); }
                    });
                }
                // API 26+: exact word range. (No @Override: older android.jar
                // stubs do not declare it; devices <26 simply never call it.)
                public void onRangeStart(final String id, final int start, final int end, int frame) {
                    H.post(() -> {
                        if (id == null || !id.equals(uttId) || state != PLAYING) return;
                        gotRange = true;
                        int s = devBase + start, e = devBase + end;
                        devPos = s;
                        emitWord(s, e);
                    });
                }
            });
        } catch (Throwable ignored) {}
        utterFrom(0);
    }

    /** Speak curText from absolute offset `from` (start / resume). */
    private static void utterFrom(int from) {
        try {
            devBase = from;
            devPos = from;
            gotRange = false;
            uttId = "xd-" + (++uttSeq);
            String rest = curText.substring(from);
            if (rest.trim().isEmpty()) { stop(); return; }
            int r = deviceTts.speak(rest, TextToSpeech.QUEUE_FLUSH, null, uttId);
            if (r != TextToSpeech.SUCCESS) { fail(); return; }
            setState(PLAYING);
            // estimation fallback when the engine gives no word ranges
            estElapsedMs = 0;
            estLastTick = System.currentTimeMillis();
            H.postDelayed(ticker, 400L);
        } catch (Throwable t) {
            fail();
        }
    }

    private static void fail() {
        Listener o = cur;
        stop();
        if (o != null) o.onError("tts unavailable");
    }

    // ── word tracking ───────────────────────────────────────────────────

    /** ~60 ms tick: cloud = MediaPlayer clock; device without ranges = own clock. */
    private static final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (state != PLAYING || wStart.length == 0) return;
            try {
                double frac;
                if (!onDevice && player != null) {
                    int dur = player.getDuration();
                    if (dur <= 0) { H.postDelayed(this, 120L); return; }
                    frac = player.getCurrentPosition() / (double) dur;
                } else if (onDevice && !gotRange) {
                    long now = System.currentTimeMillis();
                    estElapsedMs += now - estLastTick;
                    estLastTick = now;
                    double totalMs = (wCum[wCum.length - 1] - cumBefore(devBase)) * 62.0;
                    if (totalMs <= 0) { H.postDelayed(this, 120L); return; }
                    // fraction of the REMAINING text, mapped back to absolute
                    double f = Math.min(1.0, estElapsedMs / totalMs);
                    frac = (cumBefore(devBase) + f * (wCum[wCum.length - 1] - cumBefore(devBase)))
                            / wCum[wCum.length - 1];
                } else {
                    return;                    // device with exact ranges: no ticking
                }
                int w = wordAt(Math.max(0, Math.min(1.0, frac)));
                emitWord(wStart[w], wEnd[w]);
                H.postDelayed(this, 60L);
            } catch (Throwable t) {
                // player released underneath us — stop() handles the rest
            }
        }
    };

    private static double cumBefore(int absOffset) {
        double c = 0;
        for (int i = 0; i < wStart.length; i++) {
            if (wStart[i] >= absOffset) break;
            c = wCum[i];
        }
        return c;
    }

    private static int wordAt(double frac) {
        double target = frac * wCum[wCum.length - 1];
        int lo = 0, hi = wCum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (wCum[mid] >= target) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    /** Word table + cumulative "speaking time" weights (length + pauses). */
    private static void buildWords(String t) {
        int n = t.length(), count = 0;
        int[] s = new int[n / 2 + 2], e = new int[n / 2 + 2];
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(t.charAt(i))) i++;
            if (i >= n) break;
            int st = i;
            while (i < n && !Character.isWhitespace(t.charAt(i))) i++;
            s[count] = st; e[count] = i; count++;
        }
        wStart = new int[count]; wEnd = new int[count]; wCum = new double[count];
        double acc = 0;
        for (int k = 0; k < count; k++) {
            wStart[k] = s[k]; wEnd[k] = e[k];
            double w = (e[k] - s[k]) + 1.5;
            char last = t.charAt(e[k] - 1);
            if (last == ',' || last == ';' || last == ':') w += 3;
            else if (last == '.' || last == '!' || last == '?') w += 6;
            if (k + 1 < count && t.substring(e[k], s[k + 1]).indexOf('\n') >= 0) w += 5;
            acc += w;
            wCum[k] = acc;
        }
    }

    private static int lastS = -2, lastE = -2;

    private static void emitWord(int s, int e) {
        if (s == lastS && e == lastE) return;
        lastS = s; lastE = e;
        Listener l = cur;
        if (l != null) l.onWord(curLead + s, curLead + e);
    }

    // ── state plumbing ──────────────────────────────────────────────────

    private static void setState(int s) {
        if (state == s) return;
        state = s;
        Listener l = cur;
        if (l != null) l.onStateChanged(s);
    }

    /** Back to idle: clear highlight, notify, forget the listener. */
    private static void finish() {
        Listener l = cur;
        boolean wasActive = state != IDLE;
        state = IDLE;
        cur = null;
        onDevice = false;
        lastS = -2; lastE = -2;
        if (l != null && wasActive) {
            l.onWord(-1, -1);
            l.onStateChanged(IDLE);
        }
    }
}
