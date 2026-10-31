package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Small luminance histogram of the live preview. */
public class HistogramView extends View {

    private static final int BINS = 48;
    private final float[] bins = new float[BINS];
    private final Paint bar = new Paint();
    private final Paint frame = new Paint();
    private final Paint bg = new Paint();
    private int[] pixels = new int[0];

    public HistogramView(Context context) { this(context, null); }

    public HistogramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bar.setColor(0xD9FFFFFF);
        bg.setColor(0x59000000);
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(0x40FFFFFF);
        frame.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.75f));
    }

    public void update(Bitmap bmp) {
        int n = bmp.getWidth() * bmp.getHeight();
        if (pixels.length != n) pixels = new int[n];
        bmp.getPixels(pixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
        int[] counts = new int[BINS];
        for (int p : pixels) {
            int y = (((p >> 16) & 0xFF) * 54 + ((p >> 8) & 0xFF) * 183 + (p & 0xFF) * 19) >> 8;
            counts[Math.min(BINS - 1, y * BINS / 256)]++;
        }
        int max = 1;
        for (int i = 1; i < BINS - 1; i++) max = Math.max(max, counts[i]); // ignore clipped ends for scale
        for (int i = 0; i < BINS; i++) bins[i] = Math.min(1f, counts[i] / (float) max);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = 4 * getResources().getDisplayMetrics().density;
        c.drawRect(0, 0, w, h, bg);
        c.drawRect(0, 0, w, h, frame);
        float bw = (w - 2 * pad) / BINS, ih = h - 2 * pad;
        for (int i = 0; i < BINS; i++) {
            float bh = bins[i] * ih;
            if (bh < 0.5f) continue;
            c.drawRect(pad + i * bw, h - pad - bh, pad + (i + 1) * bw - Math.min(1f, bw * 0.2f), h - pad, bar);
        }
    }
}
