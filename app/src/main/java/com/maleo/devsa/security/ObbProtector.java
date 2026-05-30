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
 * DAMAGE FLOW (onStop / BussidApp backup):
 * 1. Damage bytes at GUID + asset offsets (write zeros)
 * 2. If ENABLE_PATCH_OBB_ENCRYPTION → encrypt file
 * 3. Rename → .{encryptedSeed}  (dot-prefixed hidden name)
 * <p>
 * REPAIR FLOW (onStart — UnityPlayerActivity ONLY):
 * Case A — hidden file found (.{encryptedSeed}):
 * 1. Rename .hidden → patch_obb_name
 * 2. If ENABLE_PATCH_OBB_ENCRYPTION → decrypt file
 * 3. Repair bytes (write correct GUID + asset strings)
 * → returns RepairResult.SUCCESS
 * <p>
 * Case B — patch_obb_name found (damage was missed due to crash/force-kill):
 * → Skip ALL steps (file is already undamaged + unencrypted)
 * → returns RepairResult.ALREADY_OK
 * <p>
 * Case C — neither found:
 * → returns RepairResult.OBB_MISSING
 * → UnityPlayerActivity redirects to ActivationActivity for download
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
     * REPAIR: called from UnityPlayerActivity.onStart() only.
     * See class javadoc for Case A / B / C logic.
     */
    public void repairAndShow() {
        File hidden = getHiddenFile();
        File patch = getPatchFile();

        // Case A: hidden file found → normal repair flow
        if (hidden.exists()) {
            // Step 1: rename .hidden → patch name
            boolean renamed = hidden.renameTo(patch);
            if (!renamed) {
                Log.e(TAG, "repair: rename hidden→patch failed");
                ToastUtil.show(ctx, "OBB rename failed");
                return;
            }
            Log.d(TAG, "repair: renamed hidden→patch");

            // Step 2: decrypt if enabled
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
            Log.d(TAG, "repairAndShow: SUCCESS");
            return;
        }

        // Case B: patch file already visible (damage was missed — crash or force-kill)
        // Safe to proceed — file is already readable. Skip all steps.
        if (patch.exists()) {
            Log.d(TAG, "repairAndShow: ALREADY_OK — patch OBB undamaged from previous session");
            ToastUtil.show(ctx, "OBB ready");
            return;
        }

        // Case C: neither found
        Log.w(TAG, "repairAndShow: OBB_MISSING");
    }

    /**
     * DAMAGE: called from UnityPlayerActivity.onStop() and BussidApp backup.
     * Step 1: damage bytes
     * Step 2: encrypt if enabled
     * Step 3: rename patch → .hidden
     */
    public void damageAndHide() {
        File patch = getPatchFile();
        File hidden = getHiddenFile();

        // Only operate if patch file exists (if already hidden, nothing to do)
        if (!patch.exists()) {
            if (hidden.exists()) {
                Log.d(TAG, "damageAndHide: already hidden, skip");
            } else {
                Log.w(TAG, "damageAndHide: neither file found, skip");
            }
            return;
        }

        // Step 1: damage bytes
        damageBytes(patch);

        // Step 2: encrypt if enabled
        if (AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) {
            if (!cipher.encrypt(patch)) {
                Log.e(TAG, "damageAndHide: encrypt failed — still renaming for basic protection");
            }
        }

        // Step 3: rename patch → .hidden
        boolean renamed = patch.renameTo(hidden);
        Log.d(TAG, "damageAndHide: rename patch→hidden: " + renamed);

        ToastUtil.show(ctx, "OBB protected");
    }

    /**
     * Check if patch OBB exists under either name.
     */
    public boolean isPatchObbNeeded() {
        return !getPatchFile().exists() && !getHiddenFile().exists();
    }

    /**
     * Delete patch OBB under both names before re-downloading.
     */
    public void deletePatchObb() {
        File p = getPatchFile();
        File h = getHiddenFile();
        if (p.exists()) {
            p.delete();
            Log.d(TAG, "deleted: " + p.getName());
        }
        if (h.exists()) {
            h.delete();
            Log.d(TAG, "deleted: " + h.getName());
        }
    }

    public File getTempDownloadFile() {
        return new File(getObbDir(), AppConfig.getPatchObbName(ctx) + ".tmp");
    }

    public File getHiddenFile() {
        return new File(getObbDir(), AppConfig.getHiddenObbName());
    }

    public File getPatchFile() {
        return new File(getObbDir(), AppConfig.getPatchObbName(ctx));
    }

    // ── Byte operations ───────────────────────────────────────────────────────

    private void repairBytes(File file) {
        if (cannotOperate(file)) {
            Log.w(TAG, "repairBytes: cannot operate");
            return;
        }
        writeBytes(file, guidOffset, GUID_BYTES);
        writeBytes(file, assetOffset, ASSET_BYTES);
        Log.d(TAG, "bytes repaired");
    }

    private void damageBytes(File file) {
        if (cannotOperate(file)) {
            Log.w(TAG, "damageBytes: cannot operate");
            return;
        }
        writeBytes(file, guidOffset, ZERO_GUID);
        writeBytes(file, assetOffset, ZERO_ASSET);
        Log.d(TAG, "bytes damaged");
    }

    private boolean cannotOperate(File file) {
        return file == null || !file.exists() || guidOffset < 0 || assetOffset < 0;
    }

    private void writeBytes(File file, long offset, byte[] data) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(offset);
            raf.write(data);
        } catch (Exception e) {
            Log.e(TAG, "writeBytes @ " + offset + ": " + e.getMessage());
        }
    }

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
