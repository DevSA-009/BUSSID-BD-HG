package com.maleo.devsa.security;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.maleo.devsa.util.AppConfig;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * ObbProtector — Protects the OBB file from being copied and used without authorization.
 *
 * HOW IT WORKS (same core logic as original ObbED, optimized):
 *
 * The OBB file contains specific byte sequences at known offsets:
 *   - GUID offset: should contain "unity_obb_guid"
 *   - Asset offset: should contain "assets/"
 *
 * REPAIR: Writes the correct bytes back at both offsets (game can run).
 * DAMAGE: Zeroes out those bytes at both offsets (OBB becomes unreadable).
 *
 * WHEN REPAIR/DAMAGE IS CALLED (improved from original):
 *  - onStart()  → repair  (game about to be visible)
 *  - onStop()   → damage  (game going background, minimized, or killed)
 *
 * This fixes the original bug where OBB was never protected when the app was killed,
 * because onStop() is called before onDestroy() even when force-killed via task manager.
 *
 * OFFSETS: Provided by the server via GET /user/patch/:key and stored in PrefManager.
 * They are stored as hex strings (e.g. "0x1234ABCD") and parsed here.
 */
public class ObbProtector {

    private static final String TAG = "ObbProtector";

    // Byte sequences written/erased at the offsets
    private static final byte[] GUID_BYTES  = "unity_obb_guid".getBytes();
    private static final byte[] ASSET_BYTES = "assets/".getBytes();
    private static final byte[] ZERO_GUID   = new byte[GUID_BYTES.length];
    private static final byte[] ZERO_ASSET  = new byte[ASSET_BYTES.length];

    private final File obbFile;
    private final long guidOffset;
    private final long assetOffset;

    /**
     * @param guidOffsetHex  hex string like "0x1234ABCD" from server
     * @param assetOffsetHex hex string like "0x5678EFGH" from server
     */
    public ObbProtector(Context context, String guidOffsetHex, String assetOffsetHex) {
        obbFile     = getObbFile(context);
        guidOffset  = parseHexOffset(guidOffsetHex);
        assetOffset = parseHexOffset(assetOffsetHex);
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Repair OBB — write correct bytes so game can read it.
     * Call from UnityPlayerActivity.onStart()
     */
    public void repair() {
        if (!canOperate()) return;
        writeBytes(guidOffset,  GUID_BYTES);
        writeBytes(assetOffset, ASSET_BYTES);
        Log.d(TAG, "OBB repaired");
    }

    /**
     * Damage OBB — zero out key bytes so OBB is unreadable without auth.
     * Call from UnityPlayerActivity.onStop()
     */
    public void damage() {
        if (!canOperate()) return;
        writeBytes(guidOffset,  ZERO_GUID);
        writeBytes(assetOffset, ZERO_ASSET);
        Log.d(TAG, "OBB damaged");
    }

    /** Returns true if OBB file exists and offsets are valid. */
    public boolean isObbPresent() {
        return obbFile != null && obbFile.exists();
    }

    /** Returns the OBB File object (for copy/delete operations). */
    public File getObbFile() {
        return obbFile;
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private boolean canOperate() {
        return obbFile != null && obbFile.exists() && guidOffset >= 0 && assetOffset >= 0;
    }

    private void writeBytes(long offset, byte[] data) {
        try (RandomAccessFile raf = new RandomAccessFile(obbFile, "rw")) {
            raf.seek(offset);
            raf.write(data);
        } catch (Exception e) {
            Log.e(TAG, "Write failed at offset " + offset + ": " + e.getMessage());
        }
    }

    private static File getObbFile(Context context) {
        // Standard OBB path: /Android/obb/{package}/{obbFileName}
        File obbDir = new File(
            Environment.getExternalStorageDirectory(),
            "Android/obb/" + AppConfig.GAME_PACKAGE
        );
        return new File(obbDir, AppConfig.OBB_FILE_NAME);
    }

    private static long parseHexOffset(String hex) {
        if (hex == null || hex.isEmpty()) return -1;
        try {
            String clean = hex.trim().replace("0x", "").replace("0X", "");
            return Long.parseLong(clean, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
