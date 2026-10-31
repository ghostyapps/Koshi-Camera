package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.ghostyapps.heycam.R;

/** Vertical number picker: large current value with a red underline, neighbours fading out. */
public class WheelPicker extends View {

    private final float d, sp, rowH;
    private final Paint cur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint near = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint far = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint red = new Paint();
    private int min = 1, max = 10, value = 3;
    private float downY, acc;
    private boolean moved;
    private Runnable onChange;

    public WheelPicker(Context context) { this(context, null); }

    public WheelPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        rowH = 34 * d;
        cur.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_light));
        cur.setTextAlign(Paint.Align.CENTER);
        cur.setFontFeatureSettings("tnum");
        cur.setColor(ContextCompat.getColor(context, R.color.hc_white));
        cur.setTextSize(64 * sp);
        near.set(cur);
        near.setTextSize(22 * sp);
        near.setColor(ContextCompat.getColor(context, R.color.hc_grey_dim));
        far.set(cur);
        far.setTextSize(16 * sp);
        far.setColor(0xFF2C2C2B);
        red.setColor(ContextCompat.getColor(context, R.color.hc_red));
    }

    public void setRange(int min, int max, int value) {
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, value));
        invalidate();
    }

    public int getValue() {
        return value;
    }

    public void setOnChange(Runnable r) {
        onChange = r;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = cy - (cur.ascent() + cur.descent()) / 2;
        c.drawText(String.valueOf(value), cx, base, cur);
        c.drawRect(cx - 13 * d, base + 8 * d, cx + 13 * d, base + 10 * d, red);
        float top = base + cur.ascent();
        float bottom = base + 10 * d;
        drawIf(c, value - 1, cx, top - 10 * d, near);
        drawIf(c, value - 2, cx, top - 10 * d - rowH, far);
        drawIf(c, value + 1, cx, bottom + 10 * d - near.ascent(), near);
        drawIf(c, value + 2, cx, bottom + 10 * d - near.ascent() + rowH, far);
    }

    private void drawIf(Canvas c, int v, float x, float y, Paint p) {
        if (v >= min && v <= max) c.drawText(String.valueOf(v), x, y, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                acc = 0;
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                acc += downY - e.getY();
                downY = e.getY();
                while (Math.abs(acc) >= rowH) {
                    moved = true;
                    step(acc > 0 ? 1 : -1);
                    acc -= Math.signum(acc) * rowH;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) step(e.getY() < getHeight() / 2f ? -1 : 1);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void step(int dir) {
        int v = Math.max(min, Math.min(max, value + dir));
        if (v == value) return;
        value = v;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (onChange != null) onChange.run();
        invalidate();
    }
}
