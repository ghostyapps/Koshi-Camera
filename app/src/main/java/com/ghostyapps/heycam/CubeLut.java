package com.ghostyapps.heycam;

import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 3D LUT in Adobe .cube format, applied with trilinear interpolation.
 * (Nearest-neighbour lookup would quantize every channel to the LUT size, e.g. 33 levels,
 * which bands smooth gradients like skies.)
 */
public class CubeLut {

    private int size;
    /** RGB triplets in 0..1, red changing fastest, as stored in the file. */
    private float[] table;

    public boolean load(File cubeFile) {
        if (!cubeFile.exists()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(cubeFile))) {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                char c0 = line.charAt(0);
                if (Character.isLetter(c0)) {
                    if (line.startsWith("LUT_3D_SIZE")) {
                        String[] parts = line.split("\\s+");
                        size = Integer.parseInt(parts[parts.length - 1]);
                        table = new float[size * size * size * 3];
                    } else if (line.startsWith("LUT_1D_SIZE")) {
                        return false;
                    }
                    continue; // TITLE, DOMAIN_MIN/MAX, ...
                }
                if (table == null || i + 3 > table.length) continue;
                if (parseTriplet(line, table, i)) i += 3;
            }
            return table != null && i == table.length;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Parses "r g b" into dst[at..at+2] without regex; returns false for malformed lines. */
    private static boolean parseTriplet(String line, float[] dst, int at) {
        int n = line.length(), pos = 0;
        for (int k = 0; k < 3; k++) {
            while (pos < n && Character.isWhitespace(line.charAt(pos))) pos++;
            int start = pos;
            while (pos < n && !Character.isWhitespace(line.charAt(pos))) pos++;
            if (start == pos) return false;
            dst[at + k] = Float.parseFloat(line.substring(start, pos));
        }
        return true;
    }

    /** Applies the LUT in place; {@code bmp} must be mutable. */
    public Bitmap apply(Bitmap bmp) {
        if (table == null) return bmp;
        final int w = bmp.getWidth(), h = bmp.getHeight();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            int band = (h + threads - 1) / threads;
            for (int t = 0; t < threads; t++) {
                final int y0 = t * band, y1 = Math.min(h, y0 + band);
                if (y0 >= y1) break;
                jobs.add(pool.submit(() -> {
                    int[] row = new int[w];
                    for (int y = y0; y < y1; y++) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1);
                        for (int x = 0; x < w; x++) row[x] = map(row[x]);
                        bmp.setPixels(row, 0, w, 0, y, w, 1);
                    }
                }));
            }
            for (Future<?> f : jobs) f.get();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.shutdown();
        }
        return bmp;
    }

    private int map(int argb) {
        final int n = size, n1 = n - 1;
        final float[] t = table;
        float r = ((argb >> 16) & 0xFF) * n1 / 255f;
        float g = ((argb >> 8) & 0xFF) * n1 / 255f;
        float b = (argb & 0xFF) * n1 / 255f;
        int r0 = (int) r, g0 = (int) g, b0 = (int) b;
        int r1 = Math.min(r0 + 1, n1), g1 = Math.min(g0 + 1, n1), b1 = Math.min(b0 + 1, n1);
        float fr = r - r0, fg = g - g0, fb = b - b0;

        int i000 = 3 * (r0 + n * (g0 + n * b0)), i100 = 3 * (r1 + n * (g0 + n * b0));
        int i010 = 3 * (r0 + n * (g1 + n * b0)), i110 = 3 * (r1 + n * (g1 + n * b0));
        int i001 = 3 * (r0 + n * (g0 + n * b1)), i101 = 3 * (r1 + n * (g0 + n * b1));
        int i011 = 3 * (r0 + n * (g1 + n * b1)), i111 = 3 * (r1 + n * (g1 + n * b1));

        int out = argb & 0xFF000000;
        for (int c = 0; c < 3; c++) {
            float c00 = t[i000 + c] + (t[i100 + c] - t[i000 + c]) * fr;
            float c10 = t[i010 + c] + (t[i110 + c] - t[i010 + c]) * fr;
            float c01 = t[i001 + c] + (t[i101 + c] - t[i001 + c]) * fr;
            float c11 = t[i011 + c] + (t[i111 + c] - t[i011 + c]) * fr;
            float c0 = c00 + (c10 - c00) * fg;
            float c1 = c01 + (c11 - c01) * fg;
            int v = Math.round((c0 + (c1 - c0) * fb) * 255f);
            v = v < 0 ? 0 : Math.min(v, 255);
            out |= v << (16 - 8 * c);
        }
        return out;
    }
}
