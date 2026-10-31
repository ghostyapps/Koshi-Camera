package com.ghostyapps.heycam.camera;

/** Everything about a shot that has to be frozen at the moment the shutter is pressed. */
public final class CaptureMeta {
    public final boolean raw;
    /** Clockwise rotation (0/90/180/270) needed to display the sensor image upright. */
    public final int orientation;
    public final String lutName;
    public final String frameName;
    public final long pressedAtMs;

    public CaptureMeta(boolean raw, int orientation, String lutName, String frameName) {
        this.raw = raw;
        this.orientation = orientation;
        this.lutName = raw ? null : emptyToNull(lutName);
        this.frameName = raw ? null : emptyToNull(frameName);
        this.pressedAtMs = System.currentTimeMillis();
    }

    public boolean hasLook() {
        return lutName != null || frameName != null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
