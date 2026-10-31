package com.ghostyapps.heycam.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Log;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pairs each image buffer with the capture result of the same exposure. Camera2 delivers the two
 * independently and in no guaranteed order; both carry the sensor timestamp, which is the key.
 */
final class CaptureMatcher {

    private static final String TAG = "HeyCam/Matcher";
    private static final int MAX_PENDING = 8;

    private final PhotoSaver saver;
    private final Map<Long, TotalCaptureResult> results = new LinkedHashMap<>();
    private final Map<Long, Object> payloads = new LinkedHashMap<>();
    private volatile CameraCharacteristics chars;
    private volatile String physicalId;

    CaptureMatcher(PhotoSaver saver) {
        this.saver = saver;
    }

    void setCharacteristics(CameraCharacteristics chars, String physicalId) {
        this.chars = chars;
        this.physicalId = physicalId;
    }

    void putResult(TotalCaptureResult result) {
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null) return;
        Object payload;
        synchronized (this) {
            payload = payloads.remove(ts);
            if (payload == null) {
                results.put(ts, result);
                trim(results);
                return;
            }
        }
        dispatch(payload, result);
    }

    void putJpeg(long timestamp, byte[] jpeg) {
        putPayload(timestamp, jpeg);
    }

    void putRaw(long timestamp, CameraController.RawFrame frame) {
        putPayload(timestamp, frame);
    }

    private void putPayload(long ts, Object payload) {
        TotalCaptureResult result;
        synchronized (this) {
            result = results.remove(ts);
            if (result == null) {
                payloads.put(ts, payload);
                trim(payloads);
                return;
            }
        }
        dispatch(payload, result);
    }

    synchronized void clear() {
        results.clear();
        payloads.clear();
    }

    private void dispatch(Object payload, TotalCaptureResult result) {
        Object tag = result.getRequest().getTag();
        if (!(tag instanceof CaptureMeta)) return;
        CaptureMeta meta = (CaptureMeta) tag;
        if (payload instanceof byte[]) {
            saver.saveJpeg((byte[]) payload, meta);
        } else if (payload instanceof CameraController.RawFrame) {
            saver.saveDng((CameraController.RawFrame) payload, result, physicalId, chars, meta);
        }
    }

    private static <V> void trim(Map<Long, V> map) {
        Iterator<Long> it = map.keySet().iterator();
        while (map.size() > MAX_PENDING && it.hasNext()) {
            Log.w(TAG, "dropping unmatched capture " + it.next());
            it.remove();
        }
    }
}
