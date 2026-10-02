package com.stxaviers.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * WaveformView (v1.1.7) - the animated voice bars shown inside the mic
 * button while dictation is active (replaces the static audio glyph).
 * Pure canvas, no assets. start()/stop() drive the animation.
 */
public final class WaveformView extends View {

    private static final int BARS = 5;
    private static final float[] PHASE = {0f, 1.3f, 2.6f, 0.7f, 1.9f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean running;
    private long t0;

    public WaveformView(Context c) {
        super(c);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setBarColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void start() {
        if (running) return;
        running = true;
        t0 = System.nanoTime();
        postInvalidateOnAnimation();
    }

    public void stop() {
        running = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (System.nanoTime() - t0) / 1e9f;
        float barW = w / (BARS * 2f - 1f);
        float minH = h * 0.22f, maxH = h * 0.92f;
        for (int i = 0; i < BARS; i++) {
            float k = running
                    ? 0.5f + 0.5f * (float) Math.sin(t * 7.5f + PHASE[i])
                    : 0.35f;
            float bh = minH + (maxH - minH) * k;
            float left = i * barW * 2f;
            float top = (h - bh) / 2f;
            rect.set(left, top, left + barW, top + bh);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }
        if (running) postInvalidateOnAnimation();
    }
}
