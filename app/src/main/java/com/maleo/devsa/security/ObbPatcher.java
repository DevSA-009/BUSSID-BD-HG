package com.maleo.devsa.security;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.maleo.bussidbdhg.R;
import com.maleo.devsa.util.AppConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ObbPatcher — Copy main OBB + extract DLC zip.
 * <p>
 * DLC PERSISTENCE:
 * After extraction, a reference file is created in getFilesDir().
 * Reference file name = StringEncryptor.encrypt("dlc_bussidbdhg.zip.done")  [obfuscated]
 * Reference file content = total bytes of getFilesDir()/assetpacks/ directory
 * <p>
 * CHECK (isDlcPresent):
 * 1. Find encrypted reference file name
 * 2. Read stored bytes value
 * 3. Calculate current assetpacks/ directory bytes
 * 4. current >= stored → DLC ok
 * current <  stored → DLC incomplete/corrupted → show DLC dialog
 */
public class ObbPatcher {

    private static final String TAG = "BussidBD_Patcher";

    /**
     * Seed for encrypted DLC reference filename.
     */
    private static final String DLC_REF_SEED = "dlc_bussidbdhg.zip.done";

    /**
     * DLC content directory inside getFilesDir().
     */
    private static final String ASSET_PACKS_DIR = "assetpacks";

    public interface PatchCallback {
        void onProgress(int percent);

        void onSuccess();

        void onError(String reason);
    }

    private final Context ctx;

    public ObbPatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── Main OBB ──────────────────────────────────────────────────────────────

    /**
     * Copy user-selected OBB URI → /Android/obb/{pkg}/main.ver.pkg.obb
     * Call on background thread.
     */
    public void copyMainObb(Uri sourceUri, PatchCallback callback) {
        File obbDir = resolveObbDir();
        if (!obbDir.exists() && !obbDir.mkdirs()) {
            callback.onError(ctx.getString(R.string.err_obb_folder_create_failed));
            return;
        }
        File dest = new File(obbDir, AppConfig.getMainObbName(ctx));
        Log.d(TAG, "copyMainObb → " + dest.getAbsolutePath());

        try (InputStream in = ctx.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                callback.onError(ctx.getString(R.string.err_file_open_failed));
                return;
            }
            long size = getUriSize(sourceUri);
            copyStream(in, dest, size, callback);
            Log.d(TAG, "copyMainObb ← " + dest.length() + " bytes");
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "copyMainObb: " + e.getMessage());
            if (dest.exists()) dest.delete();
            callback.onError(ctx.getString(R.string.obb_error));
        }
    }

    public boolean isMainObbPresent() {
        return new File(resolveObbDir(), AppConfig.getMainObbName(ctx)).exists();
    }

    // ── DLC ───────────────────────────────────────────────────────────────────

    /**
     * Extract DLC zip into getFilesDir().
     * After success: write encrypted reference file with assetpacks total bytes.
     * Call on background thread.
     */
    public void extractDlc(Uri zipUri, PatchCallback callback) {
        File outDir = ctx.getFilesDir();
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
                callback.onProgress(Math.min(count * 3, 95));
            }
            Log.d(TAG, "extractDlc ← " + count + " entries");

            // Write encrypted reference file with assetpacks bytes
            writeDlcReference();
            callback.onSuccess();
        } catch (IOException e) {
            Log.e(TAG, "extractDlc: " + e.getMessage());
            callback.onError(ctx.getString(R.string.dlc_error));
        }
    }

    /**
     * Check DLC integrity:
     * 1. Find encrypted reference file
     * 2. Read stored bytes
     * 3. Compare with current assetpacks/ bytes
     * current >= stored → ok; current < stored → needs re-patch
     */
    public boolean isDlcPatchNeeded() {
        File refFile = getDlcRefFile();
        if (!refFile.exists()) {
            Log.d(TAG, "isDlcPatchNeeded: reference file not found");
            return true;
        }

        long storedBytes = readDlcReference(refFile);
        if (storedBytes <= 0) {
            Log.w(TAG, "isDlcPatchNeeded: invalid stored bytes");
            return true;
        }

        File assetPacksDir = new File(ctx.getFilesDir(), ASSET_PACKS_DIR);
        if (!assetPacksDir.exists()) {
            Log.d(TAG, "isDlcPatchNeeded: assetpacks dir missing");
            return true;
        }

        long currentBytes = calculateDirBytes(assetPacksDir);
        Log.d(TAG, "isDlcPatchNeeded: stored=" + storedBytes + " current=" + currentBytes);

        return currentBytes < storedBytes;
    }

    // ── Reference file helpers ────────────────────────────────────────────────

    /**
     * Write encrypted reference file.
     * Filename = StringEncryptor.encrypt(DLC_REF_SEED)
     * Content  = total bytes of assetpacks/ directory as plain string
     */
    private void writeDlcReference() {
        File assetPacksDir = new File(ctx.getFilesDir(), ASSET_PACKS_DIR);
        long totalBytes = assetPacksDir.exists() ? calculateDirBytes(assetPacksDir) : 0;

        File refFile = getDlcRefFile();
        try (FileOutputStream fos = new FileOutputStream(refFile)) {
            fos.write(String.valueOf(totalBytes).getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "DLC reference written: " + totalBytes + " bytes → " + refFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "writeDlcReference: " + e.getMessage());
        }
    }

    private long readDlcReference(File refFile) {
        try (FileInputStream fis = new FileInputStream(refFile)) {
            byte[] buf = new byte[32];
            int read = fis.read(buf);
            if (read <= 0) return -1;
            String content = new String(buf, 0, read, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (Exception e) {
            Log.e(TAG, "readDlcReference: " + e.getMessage());
            return -1;
        }
    }

    private File getDlcRefFile() {
        // Encrypted filename so it's not obviously identifiable
        String encName = StringEncryptor.encrypt(DLC_REF_SEED);
        return new File(ctx.getFilesDir(), encName);
    }

    /**
     * Recursively calculate total bytes of all files in a directory.
     */
    private long calculateDirBytes(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) total += calculateDirBytes(f);
            else total += f.length();
        }
        return total;
    }

    // ── Storage helpers ───────────────────────────────────────────────────────

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
