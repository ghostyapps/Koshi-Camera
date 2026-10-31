package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.ghostyapps.heycam.R;

/** Outline battery with a fill for the charge level; the fill turns red below 15 %. */
public class BatteryView extends View {

    private final float d;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int white, red;
    private float level = 1f;

    public BatteryView(Context context) { this(context, null); }

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        white = ContextCompat.getColor(context, R.color.hc_white);
        red = ContextCompat.getColor(context, R.color.hc_red);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, d * 0.8f));
        stroke.setColor(white);
    }

    public void setLevel(float level) {
        this.level = Math.max(0f, Math.min(1f, level));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() - 3 * d, h = getHeight(), s = stroke.getStrokeWidth() / 2;
        c.drawRoundRect(s, s, w - s, h - s, 1.5f * d, 1.5f * d, stroke);
        fill.setColor(white);
        c.drawRect(w + d * 0.5f, h * 0.3f, w + 2 * d, h * 0.7f, fill);
        float pad = 2 * d;
        fill.setColor(level < 0.15f ? red : white);
        c.drawRect(pad, pad, pad + (w - 2 * pad) * level, h - pad, fill);
    }
}
