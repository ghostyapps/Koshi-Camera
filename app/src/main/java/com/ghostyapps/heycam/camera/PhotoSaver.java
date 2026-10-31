package com.ghostyapps.heycam.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import com.ghostyapps.heycam.CubeLut;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes captures to MediaStore on its own thread, in shutter order, so encoding and disk I/O
 * never hold up the camera. JPEGs without a look are written byte-for-byte as the camera
 * produced them. RAW frames become DNGs with the exact capture result of their exposure.
 */
public final class PhotoSaver {

    private static final String TAG = "HeyCam/Saver";

    public interface Listener {
        void onSaved(Uri uri, boolean raw, long bytes, long tookMs);
        void onSaveFailed(boolean raw);
    }

    private static final String[] EXIF_TAGS_TO_KEEP = {
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "HeyCam-Saver"));
    private final AtomicInteger pending = new AtomicInteger();
    private volatile Listener listener;
    private volatile Uri lastUri;

    // Parsed LUT is cached; parsing a 33³ .cube file costs more than applying it.
    private String cachedLutKey;
    private CubeLut cachedLut;

    public PhotoSaver(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Uri lastUri() {
        return lastUri;
    }

    public int pending() {
        return pending.get();
    }

    /** Parses a LUT ahead of time so the first shot with it isn't slowed down. */
    public void preloadLut(String name) {
        if (name == null || name.isEmpty()) return;
        executor.execute(() -> lut(name));
    }

    void saveJpeg(byte[] jpeg, CaptureMeta meta) {
        pending.incrementAndGet();
        executor.execute(() -> {
            long t0 = SystemClock.uptimeMillis();
            Uri uri = null;
            long[] size = {0};
            try {
                byte[] out = meta.hasLook() ? applyLook(jpeg, meta) : jpeg;
                uri = write(meta, "jpg", "image/jpeg", o -> o.write(out), size);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "jpeg save failed", e);
            }
            finish(uri, false, size[0], t0);
        });
    }

    void saveDng(CameraController.RawFrame frame, TotalCaptureResult result, String physicalId,
                 CameraCharacteristics chars, CaptureMeta meta) {
        pending.incrementAndGet();
        executor.execute(() -> {
            long t0 = SystemClock.uptimeMillis();
            Uri uri = null;
            long[] size = {0};
            try (DngCreator dng = dngCreator(chars, result, physicalId)) {
                dng.setOrientation(exifOrientation(meta.orientation));
                uri = write(meta, "dng", "image/x-adobe-dng",
                        o -> dng.writeByteBuffer(o, frame.size, frame.data, 0), size);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "dng save failed", e);
            }
            finish(uri, true, size[0], t0);
        });
    }

    /**
     * Prefers the physical sensor's own result (its colour matrices, noise profile, shading map);
     * falls back to the logical result if the physical one is missing or incomplete.
     */
    private static DngCreator dngCreator(CameraCharacteristics chars, TotalCaptureResult result, String physicalId) {
        if (physicalId != null) {
            CaptureResult physical = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                physical = result.getPhysicalCameraTotalResults().get(physicalId);
            } else {
                physical = result.getPhysicalCameraResults().get(physicalId);
            }
            if (physical != null) {
                try {
                    return new DngCreator(chars, physical);
                } catch (RuntimeException e) {
                    Log.w(TAG, "physical result unusable for DNG, using logical", e);
                }
            }
        }
        return new DngCreator(chars, result);
    }

    private void finish(Uri uri, boolean raw, long bytes, long t0) {
        pending.decrementAndGet();
        long took = SystemClock.uptimeMillis() - t0;
        Log.d(TAG, (raw ? "DNG" : "JPG") + " saved in " + took + " ms, " + bytes + " bytes");
        Listener l = listener;
        if (uri != null) lastUri = uri;
        main.post(() -> {
            if (l == null) return;
            if (uri != null) l.onSaved(uri, raw, bytes, took);
            else l.onSaveFailed(raw);
        });
    }

    // ---------------------------------------------------------------- MediaStore

    private interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    private Uri write(CaptureMeta meta, String ext, String mime, Body body, long[] sizeOut) throws IOException {
        String name = "Koshi_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(meta.pressedAtMs)) + "." + ext;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.DATE_TAKEN, meta.pressedAtMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Koshi");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream raw = resolver.openOutputStream(uri)) {
            if (raw == null) throw new IOException("openOutputStream returned null");
            CountingStream out = new CountingStream(raw);
            body.writeTo(out);
            out.flush();
            sizeOut[0] = out.count;
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
        }
        return uri;
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    // ---------------------------------------------------------------- looks (LUT / frame)

    /** Decodes, applies LUT and frame, re-encodes, and carries the original EXIF over. */
    private byte[] applyLook(byte[] jpeg, CaptureMeta meta) throws IOException {
        ExifInterface srcExif = new ExifInterface(new ByteArrayInputStream(jpeg));
        int rotation;
        switch (srcExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
            case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
            case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            default: rotation = 0;
        }

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
        if (bmp == null) return jpeg;
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r.isMutable() ? r : r.copy(Bitmap.Config.ARGB_8888, true);
        }

        if (meta.lutName != null) {
            CubeLut lut = lut(meta.lutName);
            if (lut != null) lut.apply(bmp);
        }
        if (meta.frameName != null) drawFrame(bmp, meta.frameName);

        File tmp = File.createTempFile("look", ".jpg", context.getCacheDir());
        try {
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, fo);
            }
            bmp.recycle();
            ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
            for (String tag : EXIF_TAGS_TO_KEEP) {
                String value = srcExif.getAttribute(tag);
                if (value != null) dst.setAttribute(tag, value);
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Koshi");
            dst.saveAttributes();
            return readFully(tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private CubeLut lut(String name) {
        File f = new File(context.getFilesDir(), "luts/" + name);
        if (!f.exists()) return null;
        String key = f.getAbsolutePath() + ":" + f.lastModified();
        if (!key.equals(cachedLutKey)) {
            CubeLut lut = new CubeLut();
            cachedLut = lut.load(f) ? lut : null;
            cachedLutKey = key;
        }
        return cachedLut;
    }

    private void drawFrame(Bitmap bmp, String name) {
        File f = new File(context.getFilesDir(), "frames/" + name);
        if (!f.exists()) return;
        Bitmap frame = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (frame == null) return;
        // Frames are drawn portrait; turn them for landscape shots.
        if (bmp.getWidth() > bmp.getHeight() && frame.getHeight() > frame.getWidth()) {
            Matrix m = new Matrix();
            m.postRotate(-90);
            Bitmap r = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);
            if (r != frame) frame.recycle();
            frame = r;
        }
        new Canvas(bmp).drawBitmap(frame, null, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        frame.recycle();
    }

    private static byte[] readFully(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        return data;
    }

    static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}
