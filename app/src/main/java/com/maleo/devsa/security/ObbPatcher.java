package com.maleo.devsa.security;

import android.content.Context;
import android.net.Uri;
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
 * ObbPatcher — Copies OBB and extracts DLC zip.
 *
 * STORAGE APPROACH (modern Android, no deprecated permissions):
 *
 *  OBB directory:
 *    Android 10+ (API 29+): Use getObbDir() — app-specific path, no permission required.
 *    Android 9 and below  : Fall back to Environment.getExternalStorageDirectory() path.
 *    WRITE_EXTERNAL_STORAGE is NOT used. It's deprecated on API 29+ and removed on 33+.
 *
 *  Reading user-selected files:
 *    Via ContentResolver.openInputStream(Uri) — works with SAF (Storage Access Framework).
 *    No READ_EXTERNAL_STORAGE needed because the user explicitly granted access via the picker.
 *
 *  DLC extraction:
 *    Written to getExternalFilesDir() — app-private external directory.
 *    No permission required on any Android version.
 */
public class ObbPatcher {

    private static final String TAG = "BussidBD_Patcher";

    public interface PatchCallback {
        void onProgress(int percent);
        void onSuccess();
        void onError(String reason);
    }

    private final Context context;

    public ObbPatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── OBB Copy ────────────────────────────────────────────────────────────

    /**
     * Copy OBB from user-selected URI to the game's OBB directory.
     * Call on a background thread.
     *
     * Why no WRITE_EXTERNAL_STORAGE:
     *   getObbDir() returns a path the system grants this app without any permission.
     *   On API < 29 we use the legacy path but still don't need the manifest permission
     *   because Android grants write access to /Android/obb/{packageName}/ automatically.
     */
    public void copyObb(Uri sourceUri, PatchCallback callback) {
        File obbDir = resolveObbDirectory();
        if (!obbDir.exists() && !obbDir.mkdirs()) {
            callback.onError("OBB ফোল্ডার তৈরি করা যায়নি: " + obbDir.getAbsolutePath());
            return;
        }

        File destination = new File(obbDir, AppConfig.OBB_FILE_NAME);
        Log.d(TAG, "copyObb → destination: " + destination.getAbsolutePath());

        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) { callback.onError("ফাইল খোলা যায়নি"); return; }

            // Get approximate size for progress (may return -1 for some URIs)
            long size = getUriFileSize(sourceUri);
            copyStream(in, destination, size, callback);
            Log.d(TAG, "copyObb ← success: " + destination.length() + " bytes");
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "copyObb error: " + e.getMessage());
            callback.onError("কপি ব্যর্থ: " + e.getMessage());
        }
    }

    // ─── DLC Extraction ──────────────────────────────────────────────────────

    /**
     * Extract DLC zip into the game's external files directory.
     * Call on a background thread.
     * getExternalFilesDir() requires NO permission on any Android version.
     */
    public void extractDlc(Uri zipUri, PatchCallback callback) {
        File outDir = context.getExternalFilesDir(null);
        if (outDir == null) {
            callback.onError("এক্সটার্নাল স্টোরেজ পাওয়া যায়নি");
            return;
        }
        Log.d(TAG, "extractDlc → outDir: " + outDir.getAbsolutePath());

        try (InputStream   raw = context.getContentResolver().openInputStream(zipUri);
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
                if (callback != null) callback.onProgress(Math.min(count * 5, 95));
            }

            Log.d(TAG, "extractDlc ← success: " + count + " entries");
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "extractDlc error: " + e.getMessage());
            callback.onError("এক্সট্র্যাক্ট ব্যর্থ: " + e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolve OBB directory using the recommended modern approach.
     *
     * API 29+ : context.getObbDir() returns /Android/obb/{packageName}/  (no permission needed)
     * API < 29: legacy path via Environment (write permission auto-granted for own package dir)
     */
    private File resolveObbDirectory() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Modern: use app's own OBB dir — no storage permission required
            // Note: we need the GAME's OBB path, not this app's. Build it manually.
            File base = context.getObbDir(); // e.g. /sdcard/Android/obb/com.maleo.bussimulatorsa/
            // Navigate up two levels (obb/) then into game package folder
            return new File(base.getParentFile(), AppConfig.GAME_PACKAGE);
        } else {
            // Legacy path for Android 9 and below
            return new File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/" + AppConfig.GAME_PACKAGE
            );
        }
    }

    private long getUriFileSize(Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0) return cursor.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void copyStream(InputStream in, File dest, long totalBytes, PatchCallback callback)
            throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf     = new byte[8192];
            long   copied  = 0;
            int    read;
            int    lastPct = -1;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                copied += read;
                if (totalBytes > 0 && callback != null) {
                    int pct = (int) (copied * 100L / totalBytes);
                    if (pct != lastPct) { lastPct = pct; callback.onProgress(pct); }
                }
            }
        }
    }
}
