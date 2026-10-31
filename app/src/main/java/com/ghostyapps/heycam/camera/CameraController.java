package com.ghostyapps.heycam.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Camera2 session. Design goals, in order:
 * 1. Deliver what the sensor saw: per-request control over noise reduction, sharpening,
 *    tone curve and distortion correction instead of the still template's HIGH_QUALITY defaults.
 * 2. Be fast: no AF/AE sequences before a normal shot, no preview restarts after it,
 *    and image buffers are copied out and released immediately so the next shot is never blocked.
 * 3. Be correct: RAW frames are paired with their own capture result by sensor timestamp.
 *
 * All camera work runs on a dedicated thread; buffers are drained on a second thread;
 * files are written by {@link PhotoSaver} on a third.
 */
public final class CameraController {

    private static final String TAG = "HeyCam/Camera";

    public enum Flash { OFF, ON, TORCH }

    /** How much of the ISP's image processing is applied to JPEGs (and the preview). */
    public enum Processing {
        /** No noise reduction, sharpening, hot-pixel/aberration/distortion correction; neutral sRGB curve. */
        OFF,
        /** No sharpening, minimal noise reduction, no distortion correction; device tone curve. */
        MINIMAL,
        /** Whatever the device does by default for stills (HIGH_QUALITY everything). */
        DEVICE
    }

    public interface Listener {
        /** Characteristics are known; the session is not necessarily ready yet. */
        void onCameraCaps(CameraCaps caps);
        void onCameraError(String message);
        /** Exposure and focus distance (diopters) the camera is actually using, throttled to ~8 Hz. */
        void onLiveStatus(long exposureNs, int iso, float focusDiopters);
        void onFocusLocked(boolean success);
        /** The sensor has started exposing the still. */
        void onShutter(boolean raw);
        void onCaptureFailed();
    }

    private static final long LIVE_EXPOSURE_INTERVAL_MS = 120;
    private static final long FLASH_PRECAPTURE_TIMEOUT_MS = 1200;
    private static final int FLASH_IDLE = 0, FLASH_WAIT_PRECAPTURE = 1, FLASH_WAIT_CONVERGED = 2;

    private final Context context;
    private final Listener listener;
    private final PhotoSaver saver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureMatcher matcher;

    private HandlerThread cameraThread, imageThread;
    private Handler cameraHandler, imageHandler;

    private LensInventory inventory;
    private LensInventory.Lens lens;
    private SurfaceTexture texture;
    private CameraCaps caps;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private ImageReader jpegReader, rawReader;
    private volatile int generation;

    // Settings: written from the UI thread, read on the camera thread.
    private volatile boolean manual;
    private volatile int iso = 100;
    private volatile long exposureNs = 10_000_000L;
    private volatile int evIndex;
    private volatile Flash flash = Flash.OFF;
    private volatile Processing processing = Processing.MINIMAL;
    private volatile MeteringRectangle focusRegion;
    private volatile Float manualFocus;   // diopters; null = autofocus

    // Camera-thread state.
    private boolean awaitingFocus;
    private long focusTriggerFrame = Long.MAX_VALUE;
    private long lastLiveExposureAt;
    private int flashState = FLASH_IDLE;
    private CaptureMeta flashMeta;
    private Object flashStillTag;
    private long flashDeadline;

    public CameraController(Context context, Listener listener, PhotoSaver saver) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.saver = saver;
        this.matcher = new CaptureMatcher(saver);
    }

    public CameraCaps caps() {
        return caps;
    }

    // ---------------------------------------------------------------- lifecycle

    /** All back lenses that can be streamed from; the first call probes the camera ids. */
    public List<LensInventory.Lens> lenses() {
        if (inventory == null) inventory = new LensInventory(context);
        return inventory.lenses();
    }

    public LensInventory.Lens lens() {
        return lens;
    }

    /** Switches to another physical lens by reopening the camera on it. */
    public void setLens(LensInventory.Lens l) {
        if (l == null || l.sameAs(lens)) return;
        lens = l;
        if (cameraThread != null && texture != null) {
            SurfaceTexture t = texture;
            close();
            open(t);
        }
    }

    /** Opens the selected (default: main) back lens and renders into {@code texture}. Needs CAMERA permission. */
    @SuppressLint("MissingPermission")
    public void open(@NonNull SurfaceTexture texture) {
        if (cameraThread != null) return;
        this.texture = texture;
        cameraThread = new HandlerThread("HeyCam-Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        imageThread = new HandlerThread("HeyCam-Image");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        final int gen = ++generation;

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (lens == null) {
                lenses();
                lens = inventory.main();
            }
            if (lens == null) {
                postError("No back camera found");
                return;
            }
            String id = lens.parentId;
            caps = new CameraCaps(lens.sensorId(), manager.getCameraCharacteristics(lens.sensorId()),
                    manager.getCameraCharacteristics(id));
            matcher.setCharacteristics(caps.chars, lens.physicalId);
            clampSettingsToCaps();
            final CameraCaps c = caps;
            main.post(() -> listener.onCameraCaps(c));

            texture.setDefaultBufferSize(caps.previewSize.getWidth(), caps.previewSize.getHeight());
            previewSurface = new Surface(texture);

            jpegReader = ImageReader.newInstance(caps.jpegSize.getWidth(), caps.jpegSize.getHeight(), ImageFormat.JPEG, 3);
            jpegReader.setOnImageAvailableListener(this::onJpegAvailable, imageHandler);
            if (caps.raw) {
                rawReader = ImageReader.newInstance(caps.rawSize.getWidth(), caps.rawSize.getHeight(), ImageFormat.RAW_SENSOR, 3);
                rawReader.setOnImageAvailableListener(this::onRawAvailable, imageHandler);
            }

            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    if (gen != generation) { camera.close(); return; }
                    device = camera;
                    createSession(gen, rawReader != null);
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    if (device == camera) device = null;
                }
                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    if (device == camera) device = null;
                    postError("Camera error " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "open failed", e);
            postError("Camera unavailable");
        }
    }

    public void close() {
        generation++;
        if (cameraHandler == null) return;
        // Tear down on the camera thread so no callback can observe a half-closed state.
        final Object done = new Object();
        final boolean[] finished = {false};
        cameraHandler.post(() -> {
            try {
                if (session != null) session.close();
                if (device != null) device.close();
            } catch (Exception e) {
                Log.w(TAG, "close", e);
            }
            session = null;
            device = null;
            flashState = FLASH_IDLE;
            flashMeta = null;
            synchronized (done) { finished[0] = true; done.notifyAll(); }
        });
        synchronized (done) {
            long until = SystemClock.uptimeMillis() + 1500;
            while (!finished[0] && SystemClock.uptimeMillis() < until) {
                try { done.wait(100); } catch (InterruptedException ignored) { break; }
            }
        }
        quit(cameraThread);
        quit(imageThread);
        cameraThread = imageThread = null;
        cameraHandler = imageHandler = null;
        if (jpegReader != null) jpegReader.close();
        if (rawReader != null) rawReader.close();
        jpegReader = rawReader = null;
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
        matcher.clear();
    }

    private static void quit(HandlerThread t) {
        if (t == null) return;
        t.quitSafely();
        try { t.join(1000); } catch (InterruptedException ignored) { }
    }

    private OutputConfiguration output(Surface surface) {
        OutputConfiguration oc = new OutputConfiguration(surface);
        // Lock the stream to the chosen sensor so 3× is the telephoto lens, not a crop.
        if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            oc.setPhysicalCameraId(lens.physicalId);
        }
        return oc;
    }

    private void createSession(final int gen, final boolean withRaw) {
        List<OutputConfiguration> outputs = new ArrayList<>(3);
        outputs.add(output(previewSurface));
        outputs.add(output(jpegReader.getSurface()));
        if (withRaw) outputs.add(output(rawReader.getSurface()));
        try {
            device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                    cameraHandler::post, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                    if (gen != generation || device == null) { s.close(); return; }
                    session = s;
                    updateRepeating();
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                    if (gen != generation) return;
                    if (withRaw) {
                        // Some devices can't stream preview + JPEG + RAW together. Keep shooting JPEG.
                        Log.w(TAG, "RAW stream combination rejected, retrying without RAW");
                        rawReader.close();
                        rawReader = null;
                        createSession(gen, false);
                    } else {
                        postError("Camera session failed");
                    }
                }
            }));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "createSession", e);
            postError("Camera session failed");
        }
    }

    public boolean isRawAvailable() {
        return rawReader != null;
    }

    // ---------------------------------------------------------------- settings

    public void setManual(boolean manual) {
        this.manual = manual && caps != null && caps.manualSensor;
        refresh();
    }

    public boolean isManual() {
        return manual;
    }

    public void setIso(int iso) {
        this.iso = iso;
        if (caps != null && caps.isoRange != null) this.iso = caps.isoRange.clamp(iso);
        if (manual) refresh();
    }

    public void setExposureNs(long ns) {
        this.exposureNs = ns;
        if (caps != null && caps.exposureRange != null) this.exposureNs = caps.exposureRange.clamp(ns);
        if (manual) refresh();
    }

    /** Exposure compensation in device steps (see {@link CameraCaps#evStep}). */
    public void setEvIndex(int index) {
        this.evIndex = index;
        if (caps != null && caps.evRange != null) this.evIndex = caps.evRange.clamp(index);
        if (!manual) refresh();
    }

    public void setFlash(Flash flash) {
        this.flash = flash;
        refresh();
    }

    /** Manual focus distance in diopters (0 = infinity), or null to return to autofocus. */
    public void setManualFocus(Float diopters) {
        manualFocus = diopters;
        if (diopters != null) {
            focusRegion = null;
            awaitingFocus = false;
        }
        refresh();
    }

    public void setProcessing(Processing processing) {
        this.processing = processing;
        refresh();
    }

    private void clampSettingsToCaps() {
        if (caps.isoRange != null) iso = caps.isoRange.clamp(iso);
        if (caps.exposureRange != null) exposureNs = caps.exposureRange.clamp(exposureNs);
        if (caps.evRange != null) evIndex = caps.evRange.clamp(evIndex);
        if (!caps.manualSensor) manual = false;
        if (!caps.flash && flash != Flash.OFF) flash = Flash.OFF;
    }

    private final Runnable refreshRunnable = this::updateRepeating;

    /** Coalesces bursts of setting changes into one repeating-request update. */
    private void refresh() {
        Handler h = cameraHandler;
        if (h == null) return;
        h.removeCallbacks(refreshRunnable);
        h.post(refreshRunnable);
    }

    // ---------------------------------------------------------------- focus

    /**
     * Focus (and, in auto mode, meter) at a point given in normalized coordinates of the
     * upright portrait viewfinder. Focus stays locked there until {@link #resetFocus()}.
     */
    public void focusAt(float nx, float ny) {
        if (caps == null || caps.activeArray == null || !caps.afRegions) return;
        float sx, sy;
        switch (caps.sensorOrientation) {
            case 90:  sx = ny;      sy = 1f - nx; break;
            case 270: sx = 1f - ny; sy = nx;      break;
            case 180: sx = 1f - nx; sy = 1f - ny; break;
            default:  sx = nx;      sy = ny;      break;
        }
        Rect a = caps.activeArray;
        int half = (int) (Math.min(a.width(), a.height()) * 0.06f);
        int left = clamp((int) (sx * a.width()) - half, 0, a.width() - 2 * half);
        int top = clamp((int) (sy * a.height()) - half, 0, a.height() - 2 * half);
        focusRegion = new MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1);
        manualFocus = null;
        Handler h = cameraHandler;
        if (h != null) h.post(this::triggerFocus);
    }

    public void resetFocus() {
        focusRegion = null;
        Handler h = cameraHandler;
        if (h == null) return;
        h.post(() -> {
            if (session == null) return;
            try {
                CaptureRequest.Builder b = previewBuilder();
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(b.build(), null, cameraHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "resetFocus", e);
            }
            awaitingFocus = false;
            updateRepeating();
        });
    }

    private void triggerFocus() {
        if (session == null) return;
        try {
            CaptureRequest.Builder cancel = previewBuilder();
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(cancel.build(), null, cameraHandler);
            updateRepeating();
            CaptureRequest.Builder start = previewBuilder();
            start.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Only AF states from this trigger onwards count; earlier frames still carry the old lock.
            focusTriggerFrame = Long.MAX_VALUE;
            awaitingFocus = true;
            session.capture(start.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession s, @NonNull CaptureRequest r, long ts, long frame) {
                    focusTriggerFrame = frame;
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "triggerFocus", e);
        }
    }

    // ---------------------------------------------------------------- preview

    private CaptureRequest.Builder previewBuilder() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        applyCommon(b);
        return b;
    }

    private void updateRepeating() {
        if (session == null || device == null) return;
        try {
            session.setRepeatingRequest(previewBuilder().build(), previewCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "updateRepeating", e);
        }
    }

    /** Settings shared by preview and still so the viewfinder shows what will be recorded. */
    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

        MeteringRectangle region = focusRegion;
        Float mf = manualFocus;
        if (mf != null && caps.minFocusDiopters > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(mf, caps.minFocusDiopters));
        } else if (region != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{region});
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, caps.continuousAf
                    ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE : CameraMetadata.CONTROL_AF_MODE_AUTO);
        }

        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, flashState != FLASH_IDLE
                    ? CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH : CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex);
            if (region != null && caps.aeRegions) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{region});
            }
        }

        b.set(CaptureRequest.FLASH_MODE, flash == Flash.TORCH
                ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (caps.ois) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        applyProcessing(b);
    }

    private void applyProcessing(CaptureRequest.Builder b) {
        switch (processing) {
            case OFF:
                setMode(b, CaptureRequest.NOISE_REDUCTION_MODE, caps.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF);
                setMode(b, CaptureRequest.EDGE_MODE, caps.edgeModes, CameraMetadata.EDGE_MODE_OFF);
                setMode(b, CaptureRequest.HOT_PIXEL_MODE, caps.hotPixelModes, CameraMetadata.HOT_PIXEL_MODE_OFF);
                setMode(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, caps.aberrationModes,
                        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF);
                setMode(b, CaptureRequest.DISTORTION_CORRECTION_MODE, caps.distortionModes,
                        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF);
                if (caps.supports(caps.tonemapModes, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)) {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE);
                    b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB);
                }
                break;
            case MINIMAL:
                setMode(b, CaptureRequest.NOISE_REDUCTION_MODE, caps.nrModes,
                        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_OFF);
                setMode(b, CaptureRequest.EDGE_MODE, caps.edgeModes, CameraMetadata.EDGE_MODE_OFF);
                setMode(b, CaptureRequest.DISTORTION_CORRECTION_MODE, caps.distortionModes,
                        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF);
                break;
            case DEVICE:
                break;
        }
    }

    /** Sets the first candidate mode the device supports; leaves the template default otherwise. */
    private static void setMode(CaptureRequest.Builder b, CaptureRequest.Key<Integer> key, int[] available, int... candidates) {
        for (int c : candidates) {
            if (CameraCaps.contains(available, c)) {
                b.set(key, c);
                return;
            }
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            long now = SystemClock.uptimeMillis();
            if (now - lastLiveExposureAt >= LIVE_EXPOSURE_INTERVAL_MS) {
                Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer sens = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Float dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                if (exp != null && sens != null) {
                    lastLiveExposureAt = now;
                    float d = dist != null ? dist : -1f;
                    main.post(() -> listener.onLiveStatus(exp, sens, d));
                }
            }
            if (awaitingFocus && result.getFrameNumber() >= focusTriggerFrame) {
                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                if (af != null && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                    awaitingFocus = false;
                    boolean ok = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
                    main.post(() -> listener.onFocusLocked(ok));
                }
            }
            if (flashState != FLASH_IDLE) advanceFlashSequence(result);
        }
    };

    // ---------------------------------------------------------------- capture

    /** Takes a picture with the current settings. Returns immediately. */
    public void capture(CaptureMeta meta) {
        Handler h = cameraHandler;
        if (h != null) h.post(() -> startCapture(meta));
    }

    private void startCapture(CaptureMeta meta) {
        if (session == null) return;
        if (flash == Flash.ON && !manual) {
            startFlashSequence(meta);
        } else {
            fireStill(meta);
        }
    }

    private void fireStill(CaptureMeta meta) {
        if (session == null || device == null) return;
        if (meta.raw && rawReader == null) {
            meta = new CaptureMeta(false, meta.orientation, meta.lutName, meta.frameName);
        }
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            applyCommon(b);
            if (meta.raw) {
                b.addTarget(rawReader.getSurface());
                // DngCreator embeds the lens shading map so raw converters can correct vignetting themselves.
                if (caps.supports(caps.shadingMapModes, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                    b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON);
                }
            } else {
                b.addTarget(jpegReader.getSurface());
                b.set(CaptureRequest.JPEG_ORIENTATION, meta.orientation);
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            }

            if (flash == Flash.ON) {
                if (manual) {
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                } else {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                }
            } else if (flash == Flash.OFF && !manual) {
                // Let the HAL hand back the frame it already exposed when the shutter was pressed.
                // Manual mode skips this so a just-changed ISO/shutter is guaranteed to apply.
                b.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
            }

            b.setTag(meta);
            if (flashState != FLASH_IDLE) flashStillTag = meta;
            session.capture(b.build(), stillCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "capture", e);
            if (flashState != FLASH_IDLE) finishFlashSequence();
            main.post(listener::onCaptureFailed);
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            boolean raw = request.getTag() instanceof CaptureMeta && ((CaptureMeta) request.getTag()).raw;
            main.post(() -> listener.onShutter(raw));
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            matcher.putResult(result);
            if (request.getTag() == flashStillTag) finishFlashSequence();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            Log.w(TAG, "still failed, reason " + failure.getReason());
            if (request.getTag() == flashStillTag) finishFlashSequence();
            main.post(listener::onCaptureFailed);
        }
    };

    // --- Flash: run the AE precapture metering sequence on the preview stream, then fire.

    private void startFlashSequence(CaptureMeta meta) {
        if (flashState != FLASH_IDLE) return;
        flashMeta = meta;
        flashState = FLASH_WAIT_PRECAPTURE;
        flashDeadline = SystemClock.uptimeMillis() + FLASH_PRECAPTURE_TIMEOUT_MS;
        try {
            updateRepeating(); // preview switches to AE_MODE_ON_ALWAYS_FLASH
            CaptureRequest.Builder b = previewBuilder();
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(b.build(), previewCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "precapture", e);
            fireFlashStill();
        }
    }

    private void advanceFlashSequence(TotalCaptureResult result) {
        if (flashMeta == null) return;
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        boolean timedOut = SystemClock.uptimeMillis() > flashDeadline;
        if (flashState == FLASH_WAIT_PRECAPTURE) {
            if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                    || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                flashState = FLASH_WAIT_CONVERGED;
            } else if (timedOut) {
                fireFlashStill();
            }
        } else if (flashState == FLASH_WAIT_CONVERGED) {
            if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE || timedOut) {
                fireFlashStill();
            }
        }
    }

    private void fireFlashStill() {
        CaptureMeta meta = flashMeta;
        flashMeta = null;
        if (meta != null) fireStill(meta);
    }

    private void finishFlashSequence() {
        if (flashState == FLASH_IDLE) return;
        flashState = FLASH_IDLE;
        flashMeta = null;
        flashStillTag = null;
        if (session == null) return;
        try {
            CaptureRequest.Builder b = previewBuilder();
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            session.capture(b.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "precapture cancel", e);
        }
        updateRepeating();
    }

    // ---------------------------------------------------------------- image buffers

    private void onJpegAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            matcher.putJpeg(image.getTimestamp(), bytes);
        } catch (IllegalStateException e) {
            Log.w(TAG, "jpeg acquire", e);
        }
    }

    private void onRawAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            // Copy into a tightly packed buffer and release the Image right away so the reader
            // never runs dry during bursts and closing the camera can't invalidate a pending write.
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer src = plane.getBuffer();
            int w = image.getWidth(), h = image.getHeight();
            int rowBytes = w * 2, stride = plane.getRowStride();
            ByteBuffer dst = ByteBuffer.allocateDirect(rowBytes * h);
            if (stride == rowBytes) {
                src.limit(Math.min(src.capacity(), rowBytes * h));
                dst.put(src);
            } else {
                for (int y = 0; y < h; y++) {
                    src.limit(y * stride + rowBytes);
                    src.position(y * stride);
                    dst.put(src);
                }
            }
            dst.rewind();
            matcher.putRaw(image.getTimestamp(), new RawFrame(dst, new Size(w, h)));
        } catch (IllegalStateException | OutOfMemoryError e) {
            Log.e(TAG, "raw acquire", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void postError(String message) {
        main.post(() -> listener.onCameraError(message));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A RAW16 frame copied out of the camera buffer, rows tightly packed. */
    static final class RawFrame {
        final ByteBuffer data;
        final Size size;

        RawFrame(ByteBuffer data, Size size) {
            this.data = data;
            this.size = size;
        }
    }
}
