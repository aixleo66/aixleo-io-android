package dev.xr.rayneo.probe;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.security.MessageDigest;

/** Loads the same pinned, unmodified payload as the initialization baseline. */
final class VendorRuntime {
    final ClassLoader loader;
    final Object core;

    VendorRuntime(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File payload = new File(app.getFilesDir(), "vendor-payload.jar");
        if (payload.exists() && !payload.delete()) throw new IOException("old payload delete");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = app.getAssets().open("vendor-payload.jar"); FileOutputStream out = new FileOutputStream(payload)) {
            if (!payload.setReadOnly()) throw new IOException("payload read-only failed");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); sha.update(buffer, 0, n); }
            out.getFD().sync();
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : sha.digest()) hash.append(String.format("%02x", b & 255));
        if (!PayloadInfo.SHA256.equals(hash.toString())) throw new IOException("payload integrity failed");
        loader = new DexClassLoader(payload.getAbsolutePath(), app.getCodeCacheDir().getAbsolutePath(), null, app.getClassLoader());
        Class<?> entry = type("com.rayneo.rayneo_venus_sdk_plugin.h$b");
        entry.getMethod("c", Context.class, type("G7.H1")).invoke(entry.getField("a").get(null), app, null);
        Class<?> coreType = type("I3.v");
        if (coreType.getField("i").get(null) != app || type("c4.g").getField("b").get(null) != app)
            throw new IllegalStateException("vendor Context is not our application");
        Object companion = coreType.getField("g").get(null);
        core = companion.getClass().getMethod("a").invoke(companion);
    }

    Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass(name); }
}
