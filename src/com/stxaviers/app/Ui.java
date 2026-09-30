package com.stxaviers.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small shared UI helpers for the feature screens (v1.1.0): mime-type
 * mapping, human sizes/dates, file opening through FileProvider, a minimal
 * markdown-flavour formatter for AI answers, and safe toasts.
 */
public final class Ui {

    private Ui() {}

    // ── mime kinds ──────────────────────────────────────────────────────

    /** One of: pdf, doc, image, video, audio, file. */
    public static String kind(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (m.contains("pdf") || n.endsWith(".pdf")) return "pdf";
        if (m.contains("word") || m.contains("document")
                || m.contains("presentation") || m.contains("sheet")
                || n.endsWith(".doc") || n.endsWith(".docx")
                || n.endsWith(".ppt") || n.endsWith(".pptx")
                || n.endsWith(".txt") || n.endsWith(".xls")
                || n.endsWith(".xlsx") || n.endsWith(".csv")) return "doc";
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("audio/")) return "audio";
        return "file";
    }

    /** Icon drawable for a mime kind. */
    public static int iconFor(String kind) {
        switch (kind) {
            case "pdf":   return R.drawable.ic_pdf;
            case "doc":   return R.drawable.ic_doc;
            case "image": return R.drawable.ic_image;
            case "video": return R.drawable.ic_video;
            case "audio": return R.drawable.ic_audio;
            default:      return R.drawable.ic_file;
        }
    }

    /** Tint color for a mime kind (tonal, meaning = category). */
    public static int tintFor(Context c, String kind) {
        switch (kind) {
            case "pdf":   return Fx.color(c, R.color.kind_pdf);
            case "doc":   return Fx.color(c, R.color.kind_doc);
            case "image": return Fx.color(c, R.color.kind_image);
            case "video": return Fx.color(c, R.color.kind_video);
            case "audio": return Fx.color(c, R.color.kind_audio);
            default:      return Fx.color(c, R.color.kind_file);
        }
    }

    // ── formatting ──────────────────────────────────────────────────────

    /** 1234567 -> "1.2 MB". */
    public static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    /** Drive ISO time "2026-09-24T10:30:00.000Z" -> "24 Sep". */
    public static String dateOf(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            String s = iso.length() > 10 ? iso.substring(0, 10) : iso;
            String[] ymd = s.split("-");
            if (ymd.length == 3) {
                String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
                int m = Integer.parseInt(ymd[1]);
                return Integer.parseInt(ymd[2]) + " "
                        + (m >= 1 && m <= 12 ? MON[m - 1] : ymd[1]);
            }
            return s;
        } catch (Throwable t) {
            return iso;
        }
    }

    /** "2026-09-24" -> "24 Sep 2026". */
    public static String longDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            String[] ymd = (iso.length() > 10 ? iso.substring(0, 10) : iso)
                    .split("-");
            if (ymd.length != 3) return iso;
            String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
            int m = Integer.parseInt(ymd[1]);
            return Integer.parseInt(ymd[2]) + " "
                    + (m >= 1 && m <= 12 ? MON[m - 1] : ymd[1])
                    + " " + ymd[0];
        } catch (Throwable t) {
            return iso;
        }
    }

    public static String now(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }

    // ── toasts ──────────────────────────────────────────────────────────

    public static void toast(Context c, int res) {
        try { Toast.makeText(c, res, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    public static void toast(Context c, String msg) {
        try { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    // ── minimal markdown flavour for AI answers ─────────────────────────

    /** One piece of an AI reply: prose, or a fenced block (code / copy box). */
    public static final class Seg {
        public final boolean code;
        public final String lang;   // "" | "copy" | "java" | ...
        public final String body;
        Seg(boolean code, String lang, String body) {
            this.code = code; this.lang = lang; this.body = body;
        }
    }

    /**
     * Split a reply on ``` fences (step 4). Odd blocks are fenced; the first
     * line of a fence is its tag when it looks like one (```copy, ```java).
     * An unclosed fence (cut-off reply) is still treated as a block.
     * ```chart comes back as a code Seg with lang "chart" (step 6: the
     * activity draws it with ChartView). ```file / ```pdffile are cut out
     * by AiActivity.stripFileBlocks first; any left over become one bold
     * note line so nothing raw ever shows.
     */
    public static java.util.List<Seg> segments(String text) {
        java.util.List<Seg> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] blocks = text.split("```", -1);
        for (int bi = 0; bi < blocks.length; bi++) {
            String b = blocks[bi];
            if (bi % 2 == 0) {
                b = trimNewlines(b);
                if (!b.trim().isEmpty()) out.add(new Seg(false, "", b));
                continue;
            }
            String lang = "";
            String body = b;
            int nl = b.indexOf('\n');
            if (nl >= 0) {
                String first = b.substring(0, nl).trim();
                if (first.length() <= 16 && first.matches("[A-Za-z0-9_+#.-]*")) {
                    lang = first.toLowerCase(Locale.ROOT);
                    body = b.substring(nl + 1);
                }
            }
            body = trimNewlines(body);
            if (lang.equals("chart")) {
                // step 6: drawn natively (ChartView) — keep it as a segment
                if (!body.trim().isEmpty()) out.add(new Seg(true, "chart", body));
                continue;
            }
            if (lang.equals("file") || lang.equals("pdffile")) {
                String label = lang.equals("pdffile") ? "PDF attached"
                        : "File attached";
                out.add(new Seg(false, "", "**· " + label
                        + " — open on the website ·**"));
                continue;
            }
            if (body.trim().isEmpty()) continue;
            out.add(new Seg(true, lang, body));
        }
        return out;
    }

    /** Strip blank lines at both ends (keeps indentation of the first line). */
    private static String trimNewlines(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) a++;
        while (b > a && (s.charAt(b - 1) == '\n' || s.charAt(b - 1) == '\r')) b--;
        return s.substring(a, b);
    }

    /**
     * Renders **bold** spans and strips light markdown (single *, `, and
     * heading #) into a SpannableString; "* item" / "- item" lines become
     * real bullets. Any ``` fence still present (streaming partials, or a
     * caller that did not split with segments()) is shown as plain code
     * text, never as a raw fence. One pass, index-safe, no span limit.
     */
    public static CharSequence formatAi(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        java.util.ArrayList<int[]> bold = new java.util.ArrayList<>();

        String[] blocks = text.split("```", -1);
        for (int bi = 0; bi < blocks.length; bi++) {
            if (bi % 2 == 1) {
                String head = blocks[bi].trim();
                if (head.startsWith("pdffile") || head.startsWith("file")
                        || head.startsWith("chart")) {
                    String label = head.startsWith("pdffile") ? "PDF attached"
                            : head.startsWith("file") ? "File attached"
                            : "Chart attached";
                    int st = out.length();
                    out.append("· ").append(label)
                            .append(" — open on the website ·");
                    bold.add(new int[]{st, out.length()});
                    out.append('\n');
                } else {
                    String body = blocks[bi];
                    int nl = body.indexOf('\n');
                    if (nl >= 0 && body.substring(0, nl).trim()
                            .matches("[A-Za-z0-9_+#.-]{0,16}")) {
                        body = body.substring(nl + 1);
                    }
                    out.append(trimNewlines(body)).append('\n');
                }
                continue;
            }
            String src = blocks[bi];
            int n = src.length();
            for (int i = 0; i < n; i++) {
                char ch = src.charAt(i);
                boolean lineStart = out.length() == 0
                        || out.charAt(out.length() - 1) == '\n';
                if (ch == '*' && i + 1 < n && src.charAt(i + 1) == '*') {
                    int close = src.indexOf("**", i + 2);
                    if (close > i + 1) {
                        int st = out.length();
                        for (int k = i + 2; k < close; k++) {
                            char c2 = src.charAt(k);
                            if (c2 != '`') out.append(c2);
                        }
                        bold.add(new int[]{st, out.length()});
                        i = close + 1;
                        continue;
                    }
                    i++;                       // stray ** — drop both stars
                    continue;
                }
                if (lineStart && (ch == '*' || ch == '-') && i + 1 < n
                        && src.charAt(i + 1) == ' ') {
                    out.append("\u2022");       // bullet; the space follows
                    continue;
                }
                if (ch == '*' || ch == '`') continue;   // light markers
                if (ch == '#' && lineStart) {
                    int j = i;
                    while (j < n && src.charAt(j) == '#') j++;
                    if (j < n && src.charAt(j) == ' ') {
                        int st = out.length();
                        int eol = src.indexOf('\n', j);
                        if (eol < 0) eol = n;
                        for (int k = j + 1; k < eol; k++) {
                            char c2 = src.charAt(k);
                            if (c2 != '*' && c2 != '`') out.append(c2);
                        }
                        bold.add(new int[]{st, out.length()});
                        i = eol - 1;
                        continue;
                    }
                }
                out.append(ch);
            }
        }

        SpannableString ss = new SpannableString(out.toString());
        for (int[] b : bold) {
            if (b[1] > b[0] && b[1] <= ss.length()) {
                ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        b[0], b[1], 0);
            }
        }
        return ss;
    }

    // ── file open / download ────────────────────────────────────────────

    /**
     * Download a Drive file through the Worker (session cookie) into the
     * app's external files dir, then open it with the system viewer via
     * FileProvider. Runs on the CALLING thread (blocking) — returns a
     * human result string.
     */
    public static String downloadAndOpen(final Activity a, String id,
                                         String name, String mime) {
        try {
            File base = a.getExternalFilesDir(null);
            if (base == null) base = a.getCacheDir();
            File dir = new File(base, "downloads");
            if (!dir.exists()) dir.mkdirs();
            String safe = name == null || name.trim().isEmpty()
                    ? "file" : name.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
            File out = uniqueFile(dir, safe);
            InputStream in = ApiClient.openStream(
                    "/drive/media?id=" + ApiClient.enc(id), null);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
            openFile(a, out, mime);
            return "ok";
        } catch (Throwable t) {
            return t.getMessage() == null ? String.valueOf(t) : t.getMessage();
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (f.exists()) {
            f = new File(dir, base + " (" + i + ")" + ext);
            i++;
        }
        return f;
    }

    /** Open a local file with the system viewer (FileProvider). */
    public static void openFile(Activity a, File f, String mime) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(a,
                    a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null || mime.isEmpty()
                    ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (Throwable t) {
            Ui.toast(a, a.getString(R.string.no_viewer));
        }
    }

    /** v1.1.5: save bytes into the device's shared Downloads — the AI's
     *  delivered files (HTML/PDF/notes) land where every download goes.
     *  MediaStore on API 29+, the app's own external folder on older
     *  devices (scoped storage can't be bypassed there). Returns a
     *  friendly path for the toast; throws when the write fails. */
    public static String saveToDownloads(Activity a, byte[] data,
                                         String name, String mime)
            throws Exception {
        if (data == null || data.length == 0) throw new Exception("empty");
        String safe = (name == null || name.trim().isEmpty()
                ? "file" : name.trim())
                .replaceAll("[/\\\\?%*:|\"<>]", "_");
        String type = mime == null || mime.isEmpty()
                ? "application/octet-stream" : mime;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
            cv.put(MediaStore.Downloads.MIME_TYPE, type);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = a.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("insert failed");
            OutputStream os = a.getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("open failed");
            os.write(data);
            os.flush();
            os.close();
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            a.getContentResolver().update(uri, cv, null, null);
            return "Downloads/" + safe;
        }
        File dir = a.getExternalFilesDir(null);
        if (dir == null) dir = a.getFilesDir();
        File out = uniqueFile(dir, safe);
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(data);
        fo.close();
        return out.getAbsolutePath();
    }

    /** Step 5: save an image into the shared Pictures/XavierDrive album
     *  (MediaStore on API 29+; the app's own Pictures folder on older
     *  devices, where scoped storage cannot be bypassed). Returns a
     *  friendly path for the toast; throws when the write fails. */
    public static String saveToPictures(Activity a, byte[] data,
                                        String name, String mime)
            throws Exception {
        if (data == null || data.length == 0) throw new Exception("empty");
        String safe = (name == null || name.trim().isEmpty()
                ? "image.jpg" : name.trim())
                .replaceAll("[/\\\\?%*:|\"<>]", "_");
        String type = mime == null || mime.isEmpty() ? "image/jpeg" : mime;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, safe);
            cv.put(MediaStore.Images.Media.MIME_TYPE, type);
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/XavierDrive");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = a.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("insert failed");
            OutputStream os = a.getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("open failed");
            os.write(data);
            os.flush();
            os.close();
            cv.clear();
            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            a.getContentResolver().update(uri, cv, null, null);
            return "Pictures/XavierDrive";
        }
        File dir = a.getExternalFilesDir(
                android.os.Environment.DIRECTORY_PICTURES);
        if (dir == null) dir = a.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        File out = uniqueFile(dir, safe);
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(data);
        fo.close();
        return out.getAbsolutePath();
    }

    /** Play a YouTube video via the app, falling back to the browser. */
    public static void openYouTube(Activity a, String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        try {
            Intent app = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("vnd.youtube:" + videoId));
            a.startActivity(app);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v="
                            + videoId));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(web);
        } catch (Throwable ignored) {
            Ui.toast(a, a.getString(R.string.no_viewer));
        }
    }

    /** Simple view-fade helper (professional, one restrained move). */
    public static void reveal(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(14f);
        v.animate().alpha(1f).translationY(0f).setDuration(300L)
                .setInterpolator(
                        new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /**
     * TRUE circular crop (owner order v1.2.0): whatever bitmap the view
     * holds — uploaded profile photos included — clips to a circle.
     * outline clipping is hardware-accelerated and works on any View size.
     */
    public static void circle(final View v) {
        if (v == null) return;
        v.setClipToOutline(true);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view,
                                   android.graphics.Outline outline) {
                int s = Math.min(view.getWidth(), view.getHeight());
                if (s <= 0) s = Math.max(view.getWidth(),
                        view.getHeight());
                if (s <= 0) s = (int) (40 * view.getResources()
                        .getDisplayMetrics().density);
                outline.setOval(0, 0, s, s);
            }
        });
    }

    // ── theme override (v1.1.2) ─────────────────────────────────────────
    //
    // The app ships light + dark resource sets (values-night). The system
    // already follows the OS setting; the in-app selector (Settings) lets
    // the user force Light or Dark. Every activity wraps its base context
    // through themed() (via XdActivity), and a stamp counter recreates any
    // live activity the moment the choice changes.

    public static final String THEME_PREFS = "xd_prefs";
    public static final String KEY_THEME = "theme";   // light|dark|system
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    private static int sThemeStamp = 1;

    public static String themeMode(Context c) {
        try {
            String m = c.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_THEME, THEME_SYSTEM);
            return m == null ? THEME_SYSTEM : m;
        } catch (Throwable t) {
            return THEME_SYSTEM;
        }
    }

    public static void setThemeMode(Context c, String mode) {
        try {
            c.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_THEME, mode).apply();
        } catch (Throwable ignored) {}
        sThemeStamp++;
    }

    /** Stamp at attach time — compared in onResume to detect a change. */
    public static int themeStamp() { return sThemeStamp; }

    /** Wrap a base context with the chosen night mode (system = as-is). */
    public static Context themed(Context base) {
        String mode = themeMode(base);
        if (THEME_SYSTEM.equals(mode)) return base;
        try {
            Configuration cfg = new Configuration();
            cfg.uiMode = THEME_DARK.equals(mode)
                    ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO;
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }
}
