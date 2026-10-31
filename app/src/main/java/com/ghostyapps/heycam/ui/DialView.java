package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.ghostyapps.heycam.R;

/**
 * Horizontal ruler dial. One tick per third stop, labels on full stops, a red needle in the
 * middle. Drag or fling to change the value; tap a label to jump to it. Ticks give haptic clicks.
 */
public class DialView extends View {

    public interface OnChangeListener {
        void onDialChanged(int index);
    }

    public interface OnTouchStateListener {
        void onDialTouch(boolean down);
    }

    private final float step, tickMajor, tickMinor, tickBottom, labelTop, needleH, needleW, fadeW;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint();
    private final Paint fadePaint = new Paint();
    private final int white, grey, greyDim;

    private final OverScroller scroller;
    private final int touchSlop, minFling, maxFling;
    private VelocityTracker velocity;

    private ExposureScale scale;
    private float offset;          // pixels; offset / step = fractional index under the needle
    private int index;
    private float downX, lastX;
    private boolean dragging;
    private OnChangeListener listener;
    private OnTouchStateListener touchListener;

    public DialView(Context context) { this(context, null); }

    public DialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        step = 18 * d;
        tickMajor = 14 * d;
        tickMinor = 7 * d;
        tickBottom = 10 * d;
        labelTop = 10 * d;
        needleH = 22 * d;
        needleW = 2 * d;
        fadeW = 72 * d;
        white = ContextCompat.getColor(context, R.color.hc_white);
        grey = ContextCompat.getColor(context, R.color.hc_grey);
        greyDim = ContextCompat.getColor(context, R.color.hc_grey_dim);
        tickPaint.setStrokeWidth(Math.max(1f, d * 0.75f));
        labelPaint.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_medium));
        labelPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFontFeatureSettings("tnum");
        needlePaint.setColor(ContextCompat.getColor(context, R.color.hc_red));
        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
    }

    public void setOnChangeListener(OnChangeListener l) {
        listener = l;
    }

    public void setOnTouchStateListener(OnTouchStateListener l) {
        touchListener = l;
    }

    public void setScale(ExposureScale scale, int index) {
        this.scale = scale;
        scroller.forceFinished(true);
        this.index = clampIndex(index);
        offset = this.index * step;
        invalidate();
    }

    public ExposureScale getScale() {
        return scale;
    }

    public int getIndex() {
        return index;
    }

    /** Moves the needle without notifying the listener. */
    public void setIndex(int i, boolean animate) {
        i = clampIndex(i);
        index = i;
        if (animate) {
            scroller.forceFinished(true);
            scroller.startScroll((int) offset, 0, (int) (i * step - offset), 0, 220);
        } else {
            offset = i * step;
        }
        postInvalidateOnAnimation();
    }

    private int clampIndex(int i) {
        if (scale == null) return 0;
        return Math.max(0, Math.min(scale.size() - 1, i));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int black = 0xFF000000, clear = 0x00000000;
        fadePaint.setShader(null);
        leftFade = new LinearGradient(0, 0, fadeW, 0, black, clear, Shader.TileMode.CLAMP);
        rightFade = new LinearGradient(w - fadeW, 0, w, 0, clear, black, Shader.TileMode.CLAMP);
    }

    private Shader leftFade, rightFade;

    @Override
    protected void onDraw(Canvas c) {
        if (scale == null) return;
        float cx = getWidth() / 2f, h = getHeight();
        float baseY = h - tickBottom;
        float labelY = labelTop - labelPaint.ascent();
        int first = Math.max(0, (int) ((offset - cx) / step) - 1);
        int last = Math.min(scale.size() - 1, (int) ((offset + cx) / step) + 1);
        float pos = offset / step;
        for (int i = first; i <= last; i++) {
            ExposureScale.Stop s = scale.get(i);
            float x = cx + i * step - offset;
            tickPaint.setColor(s.major ? 0x99FFFFFF : 0x47FFFFFF);
            c.drawLine(x, baseY - (s.major ? tickMajor : tickMinor), x, baseY, tickPaint);
            if (s.dialLabel != null) {
                float dist = Math.abs(i - pos);
                labelPaint.setColor(dist < 0.5f ? white : dist < 3.5f ? grey : greyDim);
                c.drawText(s.dialLabel, x, labelY, labelPaint);
            }
        }
        if (!isEnabled()) return;
        c.drawRect(cx - needleW / 2, h - 6 * getResources().getDisplayMetrics().density - needleH,
                cx + needleW / 2, h - 6 * getResources().getDisplayMetrics().density, needlePaint);
        fadePaint.setShader(leftFade);
        c.drawRect(0, 0, fadeW, h, fadePaint);
        fadePaint.setShader(rightFade);
        c.drawRect(getWidth() - fadeW, 0, getWidth(), h, fadePaint);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (scale == null || !isEnabled()) return false;
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downX = lastX = e.getX();
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (touchListener != null) touchListener.onDialTouch(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - downX) > touchSlop) dragging = true;
                if (dragging) {
                    setOffset(offset - (e.getX() - lastX));
                }
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    float vx = velocity.getXVelocity();
                    if (Math.abs(vx) > minFling) {
                        scroller.fling((int) offset, 0, (int) -vx, 0, 0, (int) ((scale.size() - 1) * step), 0, 0);
                        postInvalidateOnAnimation();
                    } else {
                        snap();
                    }
                } else {
                    int target = clampIndex(index + Math.round((e.getX() - getWidth() / 2f) / step));
                    scroller.startScroll((int) offset, 0, (int) (target * step - offset), 0, 220);
                    postInvalidateOnAnimation();
                }
                recycleVelocity();
                if (touchListener != null) touchListener.onDialTouch(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                snap();
                recycleVelocity();
                if (touchListener != null) touchListener.onDialTouch(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    private boolean snapping;

    private void snap() {
        int target = clampIndex(Math.round(offset / step));
        snapping = true;
        scroller.startScroll((int) offset, 0, (int) (target * step - offset), 0, 160);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setOffset(scroller.getCurrX());
            if (scroller.isFinished() && !snapping) snap();
            else postInvalidateOnAnimation();
            if (scroller.isFinished()) snapping = false;
        } else {
            snapping = false;
        }
    }

    private void setOffset(float o) {
        float max = (scale.size() - 1) * step;
        offset = Math.max(0, Math.min(max, o));
        int i = clampIndex(Math.round(offset / step));
        if (i != index) {
            index = i;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onDialChanged(i);
        }
        invalidate();
    }
}
