package com.ghostyapps.heycam;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies bundled sample LUTs and frames into app storage, off the main thread. Each bundled file is
 * copied once: files added in later app versions still arrive, samples the user deleted stay gone,
 * and samples later removed from the app are cleaned up.
 */
final class AssetInstaller {

    private AssetInstaller() { }

    static void install(Context context, SharedPreferences prefs, String assetDir, String destDir,
                        String extension, String prefKey) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String[] files = app.getAssets().list(assetDir);
                if (files == null) return;
                File dest = new File(app.getFilesDir(), destDir);
                if (!dest.exists() && !dest.mkdirs()) return;

                String setKey = prefKey + "_files";
                Set<String> installed = new HashSet<>(prefs.getStringSet(setKey, new HashSet<>()));
                if (installed.isEmpty() && prefs.getBoolean(prefKey, false)) {
                    // Installed by an older version that only kept a flag: treat what is on disk as installed.
                    String[] present = dest.list();
                    if (present != null) for (String p : present) installed.add(p);
                }

                // Samples dropped from the app are removed again, but only files this installer put there.
                Set<String> bundled = new HashSet<>();
                for (String name : files) bundled.add(name);
                for (String name : new HashSet<>(installed)) {
                    if (bundled.contains(name) || !prefs.getStringSet(setKey, new HashSet<>()).contains(name)) continue;
                    //noinspection ResultOfMethodCallIgnored
                    new File(dest, name).delete();
                    installed.remove(name);
                }

                byte[] buf = new byte[64 * 1024];
                for (String name : files) {
                    if (!name.toLowerCase().endsWith(extension) || installed.contains(name)) continue;
                    File out = new File(dest, name);
                    if (!out.exists()) {
                        try (InputStream in = app.getAssets().open(assetDir + "/" + name);
                             OutputStream o = new FileOutputStream(out)) {
                            int n;
                            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
                        }
                    }
                    installed.add(name);
                }
                prefs.edit().putStringSet(setKey, installed).putBoolean(prefKey, true).apply();
            } catch (Exception e) {
                Log.w("Koshi", "asset install " + assetDir, e);
            }
        }, "Koshi-Assets").start();
    }
}
