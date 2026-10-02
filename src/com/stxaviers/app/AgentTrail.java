package com.stxaviers.app;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 6: the agentic trail — the Claude-app pattern of small action rows
 * ("Searching the web ›") with a one-line summary between them, shown live
 * while the AI works. Rows are built straight into a LinearLayout (the
 * typing row's body). After the answer lands, {@link #collapsed} builds a
 * tappable "N steps" chip that replays the same rows inside the reply.
 * Colours are the home_* resources (values-night versions exist).
 */
public final class AgentTrail {

    private final Context ctx;
    private final LinearLayout box;
    private final float dp;
    private final Map<Integer, Row> rows = new HashMap<>();
    /** [kind, title, detail] — kind = "step" | "summary" | "artifact". */
    private final List<String[]> log = new ArrayList<>();

    private static final class Row {
        View root;
        ImageView icon;
        TextView title, detail;
        ObjectAnimator pulse;
    }

    public AgentTrail(Context c, LinearLayout box) {
        this.ctx = c;
        this.box = box;
        this.dp = c.getResources().getDisplayMetrics().density;
    }

    /** A step started ("running") or finished ("done"). */
    public void step(int id, String title, String detail, String status) {
        Row r = rows.get(id);
        if (r == null) {
            r = makeRow(title, detail, iconFor(title));
            rows.put(id, r);
            box.addView(r.root);
            log.add(new String[]{"step", title, detail});
        }
        if ("done".equals(status)) {
            if (r.pulse != null) { r.pulse.cancel(); r.pulse = null; }
            r.icon.setAlpha(1f);
        } else if (r.pulse == null) {
            r.pulse = ObjectAnimator.ofFloat(r.icon, "alpha", 1f, 0.3f);
            r.pulse.setDuration(700);
            r.pulse.setRepeatMode(ObjectAnimator.REVERSE);
            r.pulse.setRepeatCount(ObjectAnimator.INFINITE);
            r.pulse.start();
        }
    }

    /** The short line between two actions. */
    public void summary(String text) {
        if (text == null || text.trim().isEmpty()) return;
        box.addView(makeSummary(text));
        log.add(new String[]{"summary", text, ""});
    }

    /** "Created notes.md" — a finished row for a delivered file. */
    public void artifact(String name) {
        Row r = makeRow("Created " + name, "", R.drawable.ic_file);
        box.addView(r.root);
        log.add(new String[]{"artifact", "Created " + name, ""});
    }

    /** Stop every pulse (call when the answer lands or the chat closes). */
    public void stop() {
        for (Row r : rows.values()) {
            if (r.pulse != null) { r.pulse.cancel(); r.pulse = null; }
        }
    }

    public List<String[]> snapshot() {
        return new ArrayList<>(log);
    }

    /** True when the trail says something worth keeping under the reply
     *  (a search / read / file — not just "thinking" + "composing"). */
    public static boolean worthKeeping(List<String[]> steps) {
        if (steps == null) return false;
        for (String[] s : steps) {
            if (!"step".equals(s[0])) return true;
            String t = s[1].toLowerCase(Locale.ROOT);
            if (t.contains("search") || t.contains("read")) return true;
        }
        return false;
    }

    // ── row builders ────────────────────────────────────────────────

    private int iconFor(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.contains("search")) return R.drawable.ic_search;
        if (t.contains("read")) return R.drawable.ic_doc;
        return R.drawable.ic_sparkle;
    }

    private Row makeRow(String title, String detail, int iconRes) {
        final Row r = new Row();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * dp);
        row.setLayoutParams(lp);
        row.setMinimumHeight((int) (28 * dp));

        ImageView ic = new ImageView(ctx);
        ic.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (15 * dp), (int) (15 * dp)));
        ic.setImageResource(iconRes);
        ic.setImageTintList(ColorStateList.valueOf(
                Fx.color(ctx, R.color.home_muted)));
        row.addView(ic);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = (int) (9 * dp);
        col.setLayoutParams(cp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(13f);
        t.setTypeface(Typefaces.interMedium(ctx));
        t.setTextColor(Fx.color(ctx, R.color.home_muted));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);

        final TextView d = new TextView(ctx);
        d.setTextSize(11.5f);
        d.setTypeface(Typefaces.interRegular(ctx));
        d.setTextColor(Fx.color(ctx, R.color.home_muted));
        d.setAlpha(0.8f);
        d.setSingleLine(true);
        d.setEllipsize(TextUtils.TruncateAt.END);
        if (detail == null || detail.isEmpty()) {
            d.setVisibility(View.GONE);
        } else {
            d.setText(detail);
        }
        col.addView(d);
        row.addView(col);

        // tap a row to open / close its full detail line (the "›" of the app)
        if (detail != null && !detail.isEmpty()) {
            ImageView chev = new ImageView(ctx);
            chev.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (14 * dp), (int) (14 * dp)));
            chev.setImageResource(R.drawable.ic_chevron);
            chev.setImageTintList(ColorStateList.valueOf(
                    Fx.color(ctx, R.color.home_muted)));
            row.addView(chev);
            row.setOnClickListener(v -> {
                boolean open = !d.isSingleLine();
                d.setSingleLine(open);
                d.setMaxLines(open ? 1 : 6);
                if (!open) d.setEllipsize(null);
                else d.setEllipsize(TextUtils.TruncateAt.END);
            });
        }
        r.root = row;
        r.icon = ic;
        r.title = t;
        r.detail = d;
        return r;
    }

    private View makeSummary(String text) {
        TextView s = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (4 * dp);
        lp.leftMargin = (int) (24 * dp);
        s.setLayoutParams(lp);
        s.setText(text);
        s.setTextSize(13.5f);
        s.setTypeface(Typefaces.interRegular(ctx));
        s.setTextColor(Fx.color(ctx, R.color.home_ink));
        s.setLineSpacing(2 * dp, 1f);
        return s;
    }

    // ── the collapsed chip under a finished reply ───────────────────

    /** "Worked through N steps ›" — tap to show / hide the rows. */
    public static View collapsed(final Context c, List<String[]> steps) {
        final float dp = c.getResources().getDisplayMetrics().density;
        int n = 0;
        for (String[] s : steps) if (!"summary".equals(s[0])) n++;

        final LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.bottomMargin = (int) (4 * dp);
        wrap.setLayoutParams(wp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight((int) (32 * dp));
        head.setClickable(true);
        TextView t = new TextView(c);
        t.setText(n + (n == 1 ? " step" : " steps"));
        t.setTextSize(12.5f);
        t.setTypeface(Typefaces.interMedium(c));
        t.setTextColor(Fx.color(c, R.color.home_muted));
        head.addView(t);
        final ImageView chev = new ImageView(c);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                (int) (14 * dp), (int) (14 * dp));
        cp.leftMargin = (int) (4 * dp);
        chev.setLayoutParams(cp);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(
                Fx.color(c, R.color.home_muted)));
        head.addView(chev);
        wrap.addView(head);

        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        AgentTrail replay = new AgentTrail(c, body);
        int id = 0;
        for (String[] s : steps) {
            if ("summary".equals(s[0])) replay.summary(s[1]);
            else if ("artifact".equals(s[0])) {
                replay.artifact(s[1].replaceFirst("^Created ", ""));
            } else {
                replay.step(++id, s[1], s[2], "done");
            }
        }
        wrap.addView(body);
        head.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chev.setRotation(show ? 90f : 0f);
        });
        return wrap;
    }
}
