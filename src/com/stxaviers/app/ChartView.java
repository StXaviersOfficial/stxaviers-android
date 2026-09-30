package com.stxaviers.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 6: native chart for the AI's ```chart blocks (bar / line / pie).
 * Drawn with Canvas — no WebView. Spec (same as the website):
 * {"type":"bar|line|pie","title":"..","labels":[..],
 *  "datasets"|"series":[{"label":"..","data":[..]}]}
 * Colours come from the home_* resources, so light and dark both work.
 */
public final class ChartView extends View {

    private static final int[] PALETTE = {
            0xFF3B82F6, 0xFFF59E0B, 0xFF10B981, 0xFFEF4444,
            0xFF8B5CF6, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16};
    private static final int MAX_LABELS = 12;
    private static final int MAX_SERIES = 4;

    private String type = "bar";
    private String title = "";
    private final List<String> labels = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<double[]> data = new ArrayList<>();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float dp;
    private final int ink, muted, grid;

    private ChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ink = Fx.color(c, R.color.home_ink);
        muted = Fx.color(c, R.color.home_muted);
        grid = Fx.color(c, R.color.home_hairline);
    }

    /** Parse a ```chart body. Returns null when it is not a drawable chart. */
    public static ChartView from(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json.trim());
            ChartView v = new ChartView(c);
            String t = o.optString("type", "bar").toLowerCase(Locale.ROOT);
            v.type = t.equals("line") || t.equals("pie") ? t : "bar";
            v.title = o.optString("title", "");
            JSONArray lb = o.optJSONArray("labels");
            JSONArray ds = o.optJSONArray("datasets");
            if (ds == null) ds = o.optJSONArray("series");
            if (lb == null || ds == null) return null;
            int n = Math.min(lb.length(), MAX_LABELS);
            for (int i = 0; i < n; i++) v.labels.add(String.valueOf(lb.opt(i)));
            for (int s = 0; s < Math.min(ds.length(), MAX_SERIES); s++) {
                JSONObject d = ds.optJSONObject(s);
                if (d == null) continue;
                JSONArray arr = d.optJSONArray("data");
                if (arr == null) continue;
                double[] vals = new double[n];
                for (int i = 0; i < n; i++) vals[i] = arr.optDouble(i, 0);
                v.names.add(d.optString("label", ""));
                v.data.add(vals);
            }
            if (n == 0 || v.data.isEmpty()) return null;
            if (v.type.equals("pie")) {
                for (double x : v.data.get(0)) if (x < 0) return null;
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    public String getTitle() { return title; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (type.equals("pie") ? 230 * dp : 220 * dp);
        setMeasuredDimension(w, h);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9
                ? String.valueOf((long) v)
                : String.format(Locale.US, "%.1f", v);
    }

    private String clip(String s, float maxPx) {
        if (tp.measureText(s) <= maxPx) return s;
        while (s.length() > 1 && tp.measureText(s + "…") > maxPx) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        tp.setTextSize(sp(10.5f));
        if (type.equals("pie")) { drawPie(c, w, h); return; }

        double max = 0;
        for (double[] s : data) for (double x : s) max = Math.max(max, x);
        double min = 0;
        for (double[] s : data) for (double x : s) min = Math.min(min, x);
        if (max <= min) max = min + 1;
        double top = niceCeil(max);

        float left = 34 * dp, right = 8 * dp, topPad = 8 * dp;
        float legendH = names.size() > 1 || !names.get(0).isEmpty()
                ? 18 * dp : 0;
        float bottom = 26 * dp + legendH;
        float x0 = left, x1 = w - right, y0 = topPad, y1 = h - bottom;
        float span = (float) (top - Math.min(0, min));

        // grid + y labels
        p.setStrokeWidth(1f * dp);
        tp.setColor(muted);
        tp.setTextAlign(Paint.Align.RIGHT);
        for (int g = 0; g <= 4; g++) {
            float y = y1 - (y1 - y0) * g / 4f;
            p.setColor(grid);
            c.drawLine(x0, y, x1, y, p);
            double val = Math.min(0, min) + span * g / 4.0;
            c.drawText(num(val), x0 - 5 * dp, y + 3.5f * dp, tp);
        }
        int n = labels.size();
        float slot = (x1 - x0) / n;
        float zeroY = y1 - (float) ((0 - Math.min(0, min)) / span) * (y1 - y0);

        // x labels
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(muted);
        for (int i = 0; i < n; i++) {
            c.drawText(clip(labels.get(i), slot - 2 * dp),
                    x0 + slot * (i + 0.5f), y1 + 14 * dp, tp);
        }

        int ns = data.size();
        if (type.equals("bar")) {
            float gap = Math.min(10 * dp, slot * 0.25f);
            float bw = (slot - gap) / ns;
            for (int s = 0; s < ns; s++) {
                p.setColor(PALETTE[s % PALETTE.length]);
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    float bx = x0 + slot * i + gap / 2f + bw * s;
                    float by = y1 - (float) ((data.get(s)[i]
                            - Math.min(0, min)) / span) * (y1 - y0);
                    rect.set(bx, Math.min(by, zeroY), bx + bw - dp,
                            Math.max(by, zeroY));
                    c.drawRoundRect(rect, 3 * dp, 3 * dp, p);
                }
            }
        } else {   // line
            for (int s = 0; s < ns; s++) {
                int col = PALETTE[s % PALETTE.length];
                path.reset();
                for (int i = 0; i < n; i++) {
                    float px = x0 + slot * (i + 0.5f);
                    float py = y1 - (float) ((data.get(s)[i]
                            - Math.min(0, min)) / span) * (y1 - y0);
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * dp);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(col);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    float px = x0 + slot * (i + 0.5f);
                    float py = y1 - (float) ((data.get(s)[i]
                            - Math.min(0, min)) / span) * (y1 - y0);
                    c.drawCircle(px, py, 3.2f * dp, p);
                }
            }
        }
        p.setStyle(Paint.Style.FILL);

        // legend
        if (legendH > 0) {
            float lx = x0, ly = h - 6 * dp;
            tp.setTextAlign(Paint.Align.LEFT);
            for (int s = 0; s < ns; s++) {
                String nm = names.get(s).isEmpty() ? "Series " + (s + 1)
                        : names.get(s);
                p.setColor(PALETTE[s % PALETTE.length]);
                c.drawCircle(lx + 4 * dp, ly - 3.5f * dp, 4 * dp, p);
                tp.setColor(ink);
                nm = clip(nm, 110 * dp);
                c.drawText(nm, lx + 12 * dp, ly, tp);
                lx += 12 * dp + tp.measureText(nm) + 14 * dp;
            }
        }
    }

    private void drawPie(Canvas c, float w, float h) {
        double[] v = data.get(0);
        double sum = 0;
        for (double x : v) sum += x;
        if (sum <= 0) return;
        float r = Math.min(w * 0.36f, h / 2f - 10 * dp);
        float cx = r + 12 * dp, cy = h / 2f;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        float start = -90f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < v.length; i++) {
            float sweep = (float) (v[i] / sum * 360.0);
            p.setColor(PALETTE[i % PALETTE.length]);
            c.drawArc(rect, start, sweep, true, p);
            start += sweep;
        }
        // legend on the right: colour dot, label, share
        tp.setTextAlign(Paint.Align.LEFT);
        float lx = cx + r + 18 * dp, ly = Math.max(20 * dp,
                cy - Math.min(v.length, 10) * 9 * dp);
        for (int i = 0; i < v.length && i < 10; i++) {
            p.setColor(PALETTE[i % PALETTE.length]);
            c.drawCircle(lx + 4 * dp, ly - 3.5f * dp, 4 * dp, p);
            tp.setColor(ink);
            String t = labels.get(i) + " · "
                    + String.format(Locale.US, "%.0f%%", v[i] / sum * 100);
            c.drawText(clip(t, w - lx - 20 * dp), lx + 13 * dp, ly, tp);
            ly += 18 * dp;
        }
    }

    private static double niceCeil(double v) {
        if (v <= 0) return 1;
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / e;
        double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5
                : f <= 5 ? 5 : 10;
        return nf * e;
    }
}
