package com.maleo.devsa.security;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.maleo.devsa.util.AppConfig;
import com.maleo.devsa.util.ToastUtil;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * ObbProtector — Damage/repair bytes + rename + optional encrypt/decrypt patch OBB.
 * <p>
 * DAMAGE FLOW (called from onStop / BussidApp backup):
 * 1. Damage bytes at GUID and asset offsets (write zeros)
 * 2. If ENABLE_PATCH_OBB_ENCRYPTION=true → encrypt file with AES-256
 * 3. Rename → .{encryptedSeed}  (dot-prefixed hidden name)
 * <p>
 * REPAIR FLOW (called from onStart — ONLY from UnityPlayerActivity):
 * 1. Rename .{encryptedSeed} → patch.ver.pkg.obb
 * 2. If ENABLE_PATCH_OBB_ENCRYPTION=true → decrypt file with AES-256
 * 3. Repair bytes (write correct GUID and asset strings)
 * <p>
 * IMPORTANT: damageAndHide() / repairAndShow() are ONLY called from:
 * - UnityPlayerActivity.onStart() / onStop()
 * - BussidApp.onStop() (backup)
 * Never called after download — download leaves file in hidden state as-is.
 */
public class ObbProtector {

    private static final String TAG = "BussidBD_ObbProtector";

    private static final byte[] GUID_BYTES = "unity_obb_guid".getBytes();
    private static final byte[] ASSET_BYTES = "assets/".getBytes();
    private static final byte[] ZERO_GUID = new byte[GUID_BYTES.length];
    private static final byte[] ZERO_ASSET = new byte[ASSET_BYTES.length];

    private final Context ctx;
    private final long guidOffset;
    private final long assetOffset;
    private final ObbCipher cipher;

    public ObbProtector(Context ctx, String guidOffsetHex, String assetOffsetHex) {
        this.ctx = ctx.getApplicationContext();
        this.guidOffset = parseHex(guidOffsetHex);
        this.assetOffset = parseHex(assetOffsetHex);
        this.cipher = new ObbCipher(ctx);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * REPAIR: rename hidden → patch name, decrypt if enabled, write correct bytes.
     * Called ONLY from UnityPlayerActivity.onStart().
     */
    public void repairAndShow() {
        File hidden = getHiddenFile();
        File patch = getPatchFile();

        // Step 1: rename .hidden → patch name
        if (!patch.exists() && hidden.exists()) {
            boolean ok = hidden.renameTo(patch);
            Log.d(TAG, "repair: rename hidden→patch: " + ok);
            if (!ok) {
                ToastUtil.show(ctx, "OBB rename failed");
                return;
            }
        }

        // Step 2: decrypt if encryption enabled
        if (AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) {
            if (!cipher.decrypt(patch)) {
                Log.e(TAG, "repair: decrypt failed");
                ToastUtil.show(ctx, "OBB decrypt failed");
                return;
            }
        }

        // Step 3: repair bytes
        repairBytes(patch);
        ToastUtil.show(ctx, "OBB ready");
        Log.d(TAG, "repairAndShow complete");
    }

    /**
     * DAMAGE: write zero bytes, encrypt if enabled, rename → .hidden name.
     * Called from UnityPlayerActivity.onStop() and BussidApp backup.
     */
    public void damageAndHide() {
        File patch = getPatchFile();
        File hidden = getHiddenFile();

        // Step 1: damage bytes
        damageBytes(patch);

        // Step 2: encrypt if enabled
        if (AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) {
            if (!cipher.encrypt(patch)) {
                Log.e(TAG, "damage: encrypt failed");
                ToastUtil.show(ctx, "OBB encrypt failed");
                // Still rename even if encrypt failed — partial protection
            }
        }

        // Step 3: rename patch → .hidden
        if (patch.exists()) {
            boolean ok = patch.renameTo(hidden);
            Log.d(TAG, "damage: rename patch→hidden: " + ok);
        }

        ToastUtil.show(ctx, "OBB protected");
        Log.d(TAG, "damageAndHide complete");
    }

    /**
     * Check if patch OBB exists under either name.
     * Checks both: patch.ver.pkg.obb  AND  .{encryptedSeed}
     */
    public boolean isPatchObbPresent() {
        return getPatchFile().exists() || getHiddenFile().exists();
    }

    /**
     * Delete patch OBB (both names) before re-downloading.
     */
    public void deletePatchObb() {
        File p = getPatchFile();
        File h = getHiddenFile();
        if (p.exists()) {
            p.delete();
            Log.d(TAG, "deleted patch obb");
        }
        if (h.exists()) {
            h.delete();
            Log.d(TAG, "deleted hidden obb");
        }
    }

    /**
     * Temp file — download writes here, renamed to hidden on success.
     */
    public File getTempDownloadFile() {
        return new File(getObbDir(), AppConfig.getPatchObbName(ctx) + ".tmp");
    }

    /**
     * Final resting name after download — dot-prefixed hidden file.
     */
    public File getHiddenFile() {
        return new File(getObbDir(), AppConfig.getHiddenObbName());
    }

    public File getPatchFile() {
        return new File(getObbDir(), AppConfig.getPatchObbName(ctx));
    }

    // ── Byte operations ───────────────────────────────────────────────────────

    private void repairBytes(File file) {
        if (!canOperate(file)) {
            Log.w(TAG, "repairBytes: cannot operate");
            return;
        }
        writeBytes(file, guidOffset, GUID_BYTES);
        writeBytes(file, assetOffset, ASSET_BYTES);
        Log.d(TAG, "bytes repaired: " + file.getName());
    }

    private void damageBytes(File file) {
        if (!canOperate(file)) {
            Log.w(TAG, "damageBytes: cannot operate");
            return;
        }
        writeBytes(file, guidOffset, ZERO_GUID);
        writeBytes(file, assetOffset, ZERO_ASSET);
        Log.d(TAG, "bytes damaged: " + file.getName());
    }

    private boolean canOperate(File file) {
        return file != null && file.exists() && guidOffset >= 0 && assetOffset >= 0;
    }

    private void writeBytes(File file, long offset, byte[] data) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(offset);
            raf.write(data);
        } catch (Exception e) {
            Log.e(TAG, "writeBytes @ " + offset + ": " + e.getMessage());
        }
    }

    // ── Directory ─────────────────────────────────────────────────────────────

    private File getObbDir() {
        File dir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dir = new File(ctx.getObbDir().getParentFile(), AppConfig.getGamePackage(ctx));
        } else {
            dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/obb/" + AppConfig.getGamePackage(ctx));
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static long parseHex(String hex) {
        if (hex == null || hex.isEmpty()) return -1;
        try {
            return Long.parseLong(hex.trim().replaceAll("(?i)^0x", ""), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
