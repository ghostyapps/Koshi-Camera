package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Holds the viewfinder at 3:4 (portrait view of the 4:3 sensor). Full width when there is room;
 * otherwise as tall as possible while leaving {@code reserve} pixels for the controls below.
 */
public class ViewfinderFrame extends FrameLayout {

    private int reserve;

    public ViewfinderFrame(Context context) { this(context, null); }

    public ViewfinderFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        reserve = (int) (280 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = w * 4 / 3;
        int avail = MeasureSpec.getSize(heightSpec);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED && h > avail - reserve) {
            h = Math.max(0, avail - reserve);
            w = h * 3 / 4;
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
