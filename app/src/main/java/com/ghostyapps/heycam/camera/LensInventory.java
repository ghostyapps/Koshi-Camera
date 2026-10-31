package com.ghostyapps.heycam.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the back lenses a third-party app can actually stream from.
 *
 * Some OEMs hide the real logical multi-camera from {@code getCameraIdList()}. On the Nothing
 * Phone (3) the list shows only a simple back camera "0", while hidden id "4" is a logical camera
 * over physical sensors 0 (main), 2 (ultra-wide) and 3 (tele). Probing ids directly finds it, and
 * {@code OutputConfiguration.setPhysicalCameraId} (API 29) then streams straight from one sensor:
 * switching to 3× uses the telephoto lens instead of cropping the main sensor.
 */
public final class LensInventory {

    private static final String TAG = "HeyCam/Lenses";

    public static final class Lens {
        /** Camera to open. */
        public final String parentId;
        /** Physical sensor to stream from, or null to let the camera decide. */
        public final String physicalId;
        public final int focal35mm;
        /** Magnification relative to the main lens, e.g. 0.6, 1, 3. */
        public final float zoom;

        Lens(String parentId, String physicalId, int focal35mm, float zoom) {
            this.parentId = parentId;
            this.physicalId = physicalId;
            this.focal35mm = focal35mm;
            this.zoom = zoom;
        }

        /** Characteristics describing the sensor that produces the images. */
        public String sensorId() {
            return physicalId != null ? physicalId : parentId;
        }

        public String label() {
            if (Math.abs(zoom - Math.round(zoom)) < 0.15f) return Math.round(zoom) + "×";
            return String.format(Locale.US, "%.1f×", zoom).replace("0.", ".");
        }

        public boolean sameAs(Lens o) {
            return o != null && parentId.equals(o.parentId)
                    && (physicalId == null ? o.physicalId == null : physicalId.equals(o.physicalId));
        }
    }

    private final List<Lens> lenses = new ArrayList<>();
    private int mainIndex;

    public LensInventory(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            Set<String> candidates = new LinkedHashSet<>();
            Collections.addAll(candidates, manager.getCameraIdList());
            for (int i = 0; i < 16; i++) candidates.add(String.valueOf(i));

            String firstBack = null, multi = null;
            Set<String> multiPhysical = Collections.emptySet();
            for (String id : candidates) {
                CameraCharacteristics c;
                try {
                    c = manager.getCameraCharacteristics(id);
                } catch (Throwable notThere) {
                    continue;
                }
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                if (firstBack == null) firstBack = id;
                Set<String> phys = c.getPhysicalCameraIds();
                if (phys.size() > multiPhysical.size()) {
                    multi = id;
                    multiPhysical = phys;
                }
            }
            if (firstBack == null) return;

            if (multi != null && multiPhysical.size() > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<float[]> found = new ArrayList<>(); // {physicalId, eq focal}
                List<String> ids = new ArrayList<>();
                for (String pid : multiPhysical) {
                    try {
                        float eq = equivalentFocal(manager.getCameraCharacteristics(pid));
                        if (eq > 0) { ids.add(pid); found.add(new float[]{ids.size() - 1, eq}); }
                    } catch (Throwable t) {
                        Log.w(TAG, "physical " + pid + " unreadable", t);
                    }
                }
                if (found.size() > 1) {
                    found.sort((a, b) -> Float.compare(a[1], b[1]));
                    int main = 0;
                    for (int i = 1; i < found.size(); i++) {
                        if (Math.abs(found.get(i)[1] - 24f) < Math.abs(found.get(main)[1] - 24f)) main = i;
                    }
                    float mainEq = found.get(main)[1];
                    for (float[] f : found) {
                        lenses.add(new Lens(multi, ids.get((int) f[0]), Math.round(f[1]), f[1] / mainEq));
                    }
                    mainIndex = main;
                    Log.d(TAG, "multi-camera " + multi + " lenses " + lenses.size());
                    return;
                }
            }
            CameraCharacteristics c = manager.getCameraCharacteristics(firstBack);
            lenses.add(new Lens(firstBack, null, Math.round(equivalentFocal(c)), 1f));
        } catch (Throwable t) {
            Log.w(TAG, "lens scan failed", t);
        }
    }

    public List<Lens> lenses() {
        return Collections.unmodifiableList(lenses);
    }

    public Lens main() {
        return lenses.isEmpty() ? null : lenses.get(mainIndex);
    }

    private static float equivalentFocal(CameraCharacteristics c) {
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl == null || fl.length == 0 || s == null) return 0f;
        double diag = Math.hypot(s.getWidth(), s.getHeight());
        return diag > 0 ? (float) (fl[0] * 43.27 / diag) : 0f;
    }
}
