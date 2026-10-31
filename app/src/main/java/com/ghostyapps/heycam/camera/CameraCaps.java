package com.ghostyapps.heycam.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

/** Immutable snapshot of what the selected camera can do. Read once when the camera opens. */
public final class CameraCaps {

    private static final int MAX_PREVIEW_AREA = 1920 * 1440;

    public final String id;
    /** The sensor producing the images (physical camera when one is selected); used for DNG. */
    public final CameraCharacteristics chars;
    public final int sensorOrientation;
    /** Coordinate space for AF/AE regions: the active array of the camera that receives requests. */
    public final Rect activeArray;

    public final Range<Integer> isoRange;
    public final Range<Long> exposureRange;
    public final Range<Integer> evRange;
    public final Rational evStep;

    public final boolean manualSensor;
    public final boolean raw;
    public final boolean flash;
    public final boolean ois;
    public final boolean afRegions;
    public final boolean aeRegions;
    public final boolean continuousAf;
    /** Closest focus in diopters (1/m); 0 means a fixed-focus lens. */
    public final float minFocusDiopters;
    /** Hyperfocal distance in diopters, 0 if unknown. */
    public final float hyperfocalDiopters;

    public final int[] nrModes;
    public final int[] edgeModes;
    public final int[] hotPixelModes;
    public final int[] aberrationModes;
    public final int[] tonemapModes;
    public final int[] distortionModes;
    public final int[] shadingMapModes;

    public final float focalLength;
    public final float aperture;
    public final int focal35mm;

    public final Size previewSize;
    public final Size jpegSize;
    public final Size rawSize;

    /**
     * @param c sensor characteristics (sizes, ranges, optics)
     * @param p characteristics of the opened camera, which handles requests (3A, processing modes)
     */
    CameraCaps(String id, CameraCharacteristics c, CameraCharacteristics p) {
        this.id = id;
        this.chars = c;
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so != null ? so : 90;
        activeArray = p.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        isoRange = either(c, p, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        exposureRange = either(c, p, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        evRange = either(p, c, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        evStep = either(p, c, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);

        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        manualSensor = contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && isoRange != null && exposureRange != null;
        boolean rawCap = contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);

        Boolean f = p.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Boolean fs = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        flash = (f != null && f) || (fs != null && fs);
        ois = contains(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        Integer maxAf = p.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = p.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        afRegions = maxAf != null && maxAf > 0;
        aeRegions = maxAe != null && maxAe > 0;
        continuousAf = contains(p.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        Float minFocus = either(c, p, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Integer calibration = either(c, p, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        boolean calibrated = calibration != null
                && calibration != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;
        // Uncalibrated lenses report arbitrary units; distances in metres would be fiction.
        minFocusDiopters = calibrated && minFocus != null ? minFocus : 0f;
        Float hyper = either(c, p, CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
        hyperfocalDiopters = hyper != null ? hyper : 0f;

        nrModes = orEmpty(p.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES));
        edgeModes = orEmpty(p.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES));
        hotPixelModes = orEmpty(p.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES));
        aberrationModes = orEmpty(p.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES));
        tonemapModes = orEmpty(p.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES));
        distortionModes = orEmpty(p.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES));
        shadingMapModes = orEmpty(p.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES));

        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        focalLength = focals != null && focals.length > 0 ? focals[0] : 0f;
        aperture = apertures != null && apertures.length > 0 ? apertures[0] : 0f;
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (phys != null && focalLength > 0) {
            double diag = Math.hypot(phys.getWidth(), phys.getHeight());
            focal35mm = (int) Math.round(focalLength * 43.27 / diag);
        } else {
            focal35mm = 0;
        }

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        previewSize = map == null ? new Size(1440, 1080)
                : largest43(map.getOutputSizes(SurfaceTexture.class), MAX_PREVIEW_AREA, new Size(1440, 1080));
        jpegSize = map == null ? new Size(640, 480)
                : largest43(map.getOutputSizes(ImageFormat.JPEG), Long.MAX_VALUE, new Size(640, 480));
        Size rawLargest = null;
        if (rawCap && map != null) {
            Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (rawSizes != null) {
                for (Size s : rawSizes) {
                    if (rawLargest == null || area(s) > area(rawLargest)) rawLargest = s;
                }
            }
        }
        rawSize = rawLargest;
        raw = rawLargest != null;
    }

    public boolean supports(int[] modes, int mode) {
        return contains(modes, mode);
    }

    /** One EV step of the device, in stops (e.g. 1/6 or 1/3). */
    public float evStepStops() {
        return evStep == null ? 1f / 3f : evStep.floatValue();
    }

    private static Size largest43(Size[] sizes, long maxArea, Size fallback) {
        Size best = null;
        if (sizes != null) {
            for (Size s : sizes) {
                float ratio = (float) s.getWidth() / s.getHeight();
                if (Math.abs(ratio - 4f / 3f) > 0.02f || area(s) > maxArea) continue;
                if (best == null || area(s) > area(best)) best = s;
            }
        }
        return best != null ? best : fallback;
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private static <T> T either(CameraCharacteristics first, CameraCharacteristics second, CameraCharacteristics.Key<T> key) {
        T v = first.get(key);
        return v != null ? v : second.get(key);
    }

    private static int[] orEmpty(int[] a) {
        return a != null ? a : new int[0];
    }
}
