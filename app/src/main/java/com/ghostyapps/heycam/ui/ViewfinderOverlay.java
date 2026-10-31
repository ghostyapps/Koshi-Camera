package com.ghostyapps.heycam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.ghostyapps.heycam.R;

/** Frame lines, focal length, optional grid, and the focus box drawn over the live image. */
public class ViewfinderOverlay extends View {

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String focal = "", aperture = "";
    private boolean magnified;
    private boolean showGrid;
    private float fx = -1, fy = -1;
    private boolean locked, lockOk;
    private boolean crossVisible = true;

    public ViewfinderOverlay(Context context) { this(context, null); }

    public ViewfinderOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        line.setColor(0xD9FFFFFF);
        line.setStrokeWidth(Math.max(1f, d * 0.75f));
        grid.setColor(0x38FFFFFF);
        grid.setStrokeWidth(Math.max(1f, d * 0.5f));
        focus.setColor(ContextCompat.getColor(context, R.color.hc_white));
        focus.setStrokeWidth(Math.max(1f, d * 0.75f));
        red.setColor(ContextCompat.getColor(context, R.color.hc_red));
        text.setColor(ContextCompat.getColor(context, R.color.hc_white));
        text.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_medium));
        text.setTextSize(13 * sp);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setShadowLayer(6 * d, 0, 0, 0x99000000);
        small.set(text);
        small.setTextSize(9.5f * sp);
        small.setLetterSpacing(0.14f);
        small.setTextAlign(Paint.Align.LEFT);
    }

    public void setLensInfo(int mm35, float fNumber) {
        focal = mm35 > 0 ? String.valueOf(mm35) : "";
        aperture = fNumber > 0 ? String.format(java.util.Locale.US, "f/%.1f", fNumber) : "";
        invalidate();
    }

    /** Marks the punched-in focus aid view. */
    public void setMagnified(boolean on) {
        if (magnified == on) return;
        magnified = on;
        invalidate();
    }

    public void setGrid(boolean on) {
        showGrid = on;
        invalidate();
    }

    /** Shows the focus box at a view position; it stays until {@link #clearFocus()}. */
    public void showFocus(float x, float y) {
        fx = x;
        fy = y;
        locked = false;
        invalidate();
    }

    public void setFocusLocked(boolean ok) {
        locked = true;
        lockOk = ok;
        invalidate();
    }

    /** Hides the centre cross, e.g. while the countdown ring sits on top of it. */
    public void setCrossVisible(boolean visible) {
        crossVisible = visible;
        invalidate();
    }

    public void clearFocus() {
        fx = fy = -1;
        locked = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (showGrid) {
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, grid);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, grid);
            }
        }

        float in = 16 * d, len = 20 * d;
        corner(c, in, in, len, 1, 1, line);
        corner(c, w - in, in, len, -1, 1, line);
        corner(c, in, h - in, len, 1, -1, line);
        corner(c, w - in, h - in, len, -1, -1, line);
        c.drawText(focal, w - 22 * d, h - 24 * d, text);
        small.setTextAlign(Paint.Align.RIGHT);
        c.drawText(aperture, w - 22 * d, h - 24 * d - text.getTextSize() - 2 * d, small);
        small.setTextAlign(Paint.Align.LEFT);
        if (magnified) c.drawText("FOCUS AID", 22 * d, h - 24 * d, small);

        if (fx < 0) {
            if (!crossVisible) return;
            float s = 7 * d;
            c.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, line);
            c.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, line);
        } else {
            float half = 36 * d, k = 10 * d;
            focus.setColor(locked && !lockOk ? 0xFF8C8C8A : 0xFFF2F2F0);
            corner(c, fx - half, fy - half, k, 1, 1, focus);
            corner(c, fx + half, fy - half, k, -1, 1, focus);
            corner(c, fx - half, fy + half, k, 1, -1, focus);
            corner(c, fx + half, fy + half, k, -1, -1, focus);
            if (locked) {
                c.drawCircle(fx, fy, 2 * d, lockOk ? red : focus);
                c.drawText("AF-L", fx + half + 6 * d, fy - half + small.getTextSize(), small);
            }
        }
    }

    private static void corner(Canvas c, float x, float y, float len, int dx, int dy, Paint p) {
        c.drawLine(x, y, x + dx * len, y, p);
        c.drawLine(x, y, x, y + dy * len, p);
    }
}
