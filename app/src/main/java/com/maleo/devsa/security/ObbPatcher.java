package com.maleo.devsa.security;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.maleo.devsa.util.AppConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ObbPatcher — Copy main OBB + extract DLC from user-selected files.
 * Patch OBB download is handled by ApiService.downloadFile() + ObbProtector.
 */
public class ObbPatcher {

    private static final String TAG = "BussidBD_Patcher";

    public interface PatchCallback {
        void onProgress(int percent);

        void onSuccess();

        void onError(String reason);
    }

    private final Context ctx;

    public ObbPatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── Main OBB copy ─────────────────────────────────────────────────────────

    /**
     * Copy user-selected OBB URI → /Android/obb/{pkg}/main.ver.pkg.obb
     * After copy: rename to OBB_MAIN_FILE_NAME (no temp needed — it's a local copy).
     */
    public void copyMainObb(Uri sourceUri, PatchCallback callback) {
        File obbDir = resolveObbDir();
        if (!obbDir.exists() && !obbDir.mkdirs()) {
            callback.onError("OBB ফোল্ডার তৈরি করা যায়নি");
            return;
        }
        String mainName = AppConfig.getMainObbName(ctx);
        File dest = new File(obbDir, mainName);
        Log.d(TAG, "copyMainObb → " + dest.getAbsolutePath());

        try (InputStream in = ctx.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                callback.onError("ফাইল খোলা যায়নি");
                return;
            }
            long size = getUriSize(sourceUri);
            copyStream(in, dest, size, callback);
            Log.d(TAG, "copyMainObb ← success: " + dest.length() + " bytes");
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "copyMainObb error: " + e.getMessage());
            if (dest.exists()) dest.delete();
            callback.onError("কপি ব্যর্থ");
        }
    }

    /**
     * Check if main OBB already exists.
     */
    public boolean isMainObbPresent() {
        return new File(resolveObbDir(), AppConfig.getMainObbName(ctx)).exists();
    }

    // ── DLC extraction ────────────────────────────────────────────────────────

    /**
     * Extract DLC zip from user-selected URI into getExternalFilesDir().
     */
    public void extractDlc(Uri zipUri, PatchCallback callback) {
        File outDir = ctx.getExternalFilesDir(null);
        if (outDir == null) {
            callback.onError("স্টোরেজ পাওয়া যায়নি");
            return;
        }
        Log.d(TAG, "extractDlc → " + outDir.getAbsolutePath());

        try (InputStream raw = ctx.getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(raw)) {

            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(outDir, entry.getName());
                // Zip-slip protection
                if (!outFile.getCanonicalPath().startsWith(outDir.getCanonicalPath() + File.separator)) {
                    Log.w(TAG, "zip-slip blocked: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                    copyStream(zis, outFile, -1, null);
                }
                zis.closeEntry();
                count++;
                callback.onProgress(Math.min(count * 5, 95));
            }
            Log.d(TAG, "extractDlc ← " + count + " entries");
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "extractDlc error: " + e.getMessage());
            callback.onError("এক্সট্র্যাক্ট ব্যর্থ");
        }
    }

    /**
     * Check if DLC zip marker exists.
     */
    public boolean isDlcPresent() {
        File dlcMarker = new File(ctx.getExternalFilesDir(null), AppConfig.DLC_ZIP_NAME + ".done");
        return dlcMarker.exists();
    }

    /**
     * Mark DLC as installed.
     */
    public void markDlcInstalled() {
        try {
            File marker = new File(ctx.getExternalFilesDir(null), AppConfig.DLC_ZIP_NAME + ".done");
            marker.createNewFile();
        } catch (IOException ignored) {
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File resolveObbDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new File(ctx.getObbDir().getParentFile(), AppConfig.getGamePackage(ctx));
        } else {
            return new File(Environment.getExternalStorageDirectory(),
                    "Android/obb/" + AppConfig.getGamePackage(ctx));
        }
    }

    private long getUriSize(Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void copyStream(InputStream in, File dest, long total, PatchCallback cb)
            throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long copied = 0;
            int read;
            int last = -1;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                copied += read;
                if (total > 0 && cb != null) {
                    int pct = (int) (copied * 100L / total);
                    if (pct != last) {
                        last = pct;
                        cb.onProgress(pct);
                    }
                }
            }
        }
    }
}
