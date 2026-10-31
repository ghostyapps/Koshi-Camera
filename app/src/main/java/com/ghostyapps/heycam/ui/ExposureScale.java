package com.ghostyapps.heycam.ui;

import android.util.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Third-stop scales for shutter, ISO and exposure compensation, limited to what the camera supports. */
public final class ExposureScale {

    /** One position on a dial. */
    public static final class Stop {
        public final double value;     // seconds, ISO, or EV
        public final String label;     // readout text, e.g. "1/125", "400", "+⅔"
        public final String dialLabel; // shown under full stops only, null for thirds
        public final boolean major;

        Stop(double value, String label, boolean major) {
            this.value = value;
            this.label = label;
            this.major = major;
            this.dialLabel = major ? label : null;
        }
    }

    public final List<Stop> stops;

    private ExposureScale(List<Stop> stops) {
        this.stops = stops;
    }

    public int size() {
        return stops.size();
    }

    public Stop get(int i) {
        return stops.get(i);
    }

    /** Index of the stop closest to {@code v} on a logarithmic (ratio) scale, or linear for EV. */
    public int nearest(double v, boolean linear) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            double s = stops.get(i).value;
            double d = linear ? Math.abs(s - v) : Math.abs(Math.log(s / v));
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ shutter

    private static final String[] SHUTTER = {
            "1/16000", "1/12800", "1/10000",
            "1/8000", "1/6400", "1/5000", "1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250",
            "1/1000", "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200", "1/160",
            "1/125", "1/100", "1/80", "1/60", "1/50", "1/40", "1/30", "1/25", "1/20",
            "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4", "0.3\"", "0.4\"",
            "0.5\"", "0.6\"", "0.8\"", "1\"", "1.3\"", "1.6\"", "2\"", "2.5\"", "3.2\"",
            "4\"", "5\"", "6\"", "8\"", "10\"", "13\"", "15\"", "20\"", "25\"", "30\"",
    };
    private static final String[] SHUTTER_MAJOR = {
            "1/16000", "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60",
            "1/30", "1/15", "1/8", "1/4", "0.5\"", "1\"", "2\"", "4\"", "8\"", "15\"", "30\"",
    };

    /** Slowest on the left, fastest on the right. */
    public static ExposureScale shutter(Range<Long> rangeNs) {
        List<Stop> out = new ArrayList<>();
        for (int i = SHUTTER.length - 1; i >= 0; i--) {
            String s = SHUTTER[i];
            double sec = parseShutter(s);
            long ns = (long) (sec * 1e9);
            if (rangeNs != null && (ns < rangeNs.getLower() * 0.97 || ns > rangeNs.getUpper() * 1.03)) continue;
            out.add(new Stop(sec, s, contains(SHUTTER_MAJOR, s)));
        }
        if (out.isEmpty()) out.add(new Stop(0.01, "1/100", true));
        return new ExposureScale(out);
    }

    private static double parseShutter(String s) {
        if (s.startsWith("1/")) return 1.0 / Double.parseDouble(s.substring(2));
        return Double.parseDouble(s.replace("\"", ""));
    }

    /** Readout text for an arbitrary exposure time (e.g. what auto exposure chose). */
    public static String formatShutter(long ns) {
        double sec = ns / 1e9;
        if (sec >= 0.3) {
            String v = String.format(Locale.US, sec >= 10 ? "%.0f" : "%.1f", sec);
            if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
            return v + "\"";
        }
        return "1/" + Math.round(1.0 / sec);
    }

    // ------------------------------------------------------------------ ISO

    private static final int[] ISO = {
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
            1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200, 102400,
    };
    private static final int[] ISO_MAJOR = {25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400};

    public static ExposureScale iso(Range<Integer> range) {
        List<Stop> out = new ArrayList<>();
        for (int v : ISO) {
            if (range != null && !range.contains(v)) continue;
            boolean major = false;
            for (int m : ISO_MAJOR) major |= m == v;
            out.add(new Stop(v, String.valueOf(v), major));
        }
        if (out.isEmpty() && range != null) {
            out.add(new Stop(range.getLower(), String.valueOf(range.getLower()), true));
            out.add(new Stop(range.getUpper(), String.valueOf(range.getUpper()), true));
        }
        return new ExposureScale(out);
    }

    // ------------------------------------------------------------------ EV

    /** Exposure compensation in thirds within the device range. {@code value} is in stops. */
    public static ExposureScale ev(Range<Integer> deviceRange, float deviceStep) {
        float min = deviceRange != null ? deviceRange.getLower() * deviceStep : -2f;
        float max = deviceRange != null ? deviceRange.getUpper() * deviceStep : 2f;
        List<Stop> out = new ArrayList<>();
        for (int k = -15; k <= 15; k++) {
            double ev = k / 3.0;
            if (ev < min - 1e-3 || ev > max + 1e-3) continue;
            out.add(new Stop(ev, formatEv(k), k % 3 == 0));
        }
        if (out.isEmpty()) out.add(new Stop(0, "0", true));
        return new ExposureScale(out);
    }

    /** Device compensation steps for an EV value in stops. */
    public static int evToDeviceIndex(double ev, float deviceStep) {
        return (int) Math.round(ev / deviceStep);
    }

    private static String formatEv(int thirds) {
        if (thirds == 0) return "0";
        String sign = thirds > 0 ? "+" : "−";
        int a = Math.abs(thirds), whole = a / 3, frac = a % 3;
        String f = frac == 1 ? "⅓" : frac == 2 ? "⅔" : "";
        return sign + (whole > 0 ? String.valueOf(whole) : "") + f;
    }

    // ------------------------------------------------------------------ focus

    private static final double[] FOCUS_M = {
            0.10, 0.12, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0, 1.2, 1.5, 2, 2.5, 3, 4, 5, 7, 10, 15,
    };
    private static final double[] FOCUS_MAJOR = {0.1, 0.2, 0.3, 0.5, 0.7, 1.0, 1.5, 2, 3, 5, 10};

    /**
     * Focus distances from the closest the lens can do to infinity, near on the left like a lens
     * barrel. {@code value} is in diopters (1/m), which is what the camera takes; 0 is infinity.
     */
    public static ExposureScale focus(float minFocusDiopters) {
        List<Stop> out = new ArrayList<>();
        double closest = minFocusDiopters > 0 ? 1.0 / minFocusDiopters : Double.MAX_VALUE;
        for (double m : FOCUS_M) {
            if (m < closest - 1e-3) continue;
            boolean major = false;
            for (double mj : FOCUS_MAJOR) major |= Math.abs(mj - m) < 1e-6;
            out.add(new Stop(1.0 / m, formatDistance(m), major));
        }
        out.add(new Stop(0, "∞", true));
        return new ExposureScale(out);
    }

    /** Distance in metres for a focus value in diopters, e.g. "0.35", "1.2", "10", "∞". */
    public static String formatFocus(float diopters) {
        if (diopters <= 0.05f) return "∞";
        return formatDistance(1.0 / diopters);
    }

    private static String formatDistance(double m) {
        if (m >= 20) return "∞";
        if (m >= 10) return String.valueOf(Math.round(m));
        String v = String.format(Locale.US, m < 1 ? "%.2f" : "%.1f", m);
        if (v.endsWith("0") && v.contains(".") && m < 1) v = v.substring(0, v.length() - 1);
        if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
        return v;
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equals(s)) return true;
        return false;
    }
}
