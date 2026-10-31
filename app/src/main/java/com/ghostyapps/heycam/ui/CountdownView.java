package com.ghostyapps.heycam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.ghostyapps.heycam.R;

/** Thin ring with a red progress arc, the seconds left, and the shot counter. */
public class CountdownView extends View {

    private final float d;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caps = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private ValueAnimator anim;

    private int seconds;
    private float progress;
    private String counter = "";

    public CountdownView(Context context) { this(context, null); }

    public CountdownView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, d * 0.75f));
        ring.setColor(0x80FFFFFF);
        fill.setColor(0x40000000);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(2 * d);
        arc.setColor(ContextCompat.getColor(context, R.color.hc_red));
        big.setColor(ContextCompat.getColor(context, R.color.hc_white));
        big.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_light));
        big.setTextSize(76 * sp);
        big.setTextAlign(Paint.Align.CENTER);
        big.setFontFeatureSettings("tnum");
        caps.set(big);
        caps.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_medium));
        caps.setTextSize(10 * sp);
        caps.setLetterSpacing(0.2f);
    }

    /** Shows {@code seconds} and sweeps the arc over the next second. */
    public void tick(int seconds, int shot, int total) {
        this.seconds = seconds;
        counter = shot + " / " + total;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    public void stop() {
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 2 * d;
        c.drawCircle(cx, cy, r, fill);
        c.drawCircle(cx, cy, r, ring);
        box.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(box, -90, 360 * progress, false, arc);
        c.drawText(String.valueOf(seconds), cx, cy - (big.ascent() + big.descent()) / 2 - 4 * d, big);
        c.drawText(counter, cx, cy + r - 24 * d, caps);
    }
}
