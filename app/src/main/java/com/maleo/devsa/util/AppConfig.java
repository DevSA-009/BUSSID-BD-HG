package com.maleo.devsa.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.maleo.devsa.security.StringEncryptor;

public final class AppConfig {

    private static final String TAG = "BussidBD_Config";

    private AppConfig() {
    }

    // ── Server ────────────────────────────────────────────────────────────────
//    public static final String BASE_URL = "http://10.0.2.2:5000";
    public static final String BASE_URL = "https://bussid-bd-hexagon.vercel.app";
    public static final int CONNECT_TIMEOUT = 15;
    public static final int READ_TIMEOUT = 30;
    public static final int WRITE_TIMEOUT = 15;

    // ── DLC ───────────────────────────────────────────────────────────────────
    public static final String DLC_ZIP_NAME = "dlc_bussidbdhg.zip";

    // ── Hidden patch OBB seed (encrypted + dot-prefixed at runtime) ───────────
    public static final String HIDDEN_OBB_SEED = "BUSSID_BD_HEXAGON_DEVSA";

    // ── Intervals ─────────────────────────────────────────────────────────────
    public static final long UPDATE_CHECK_INTERVAL_MS = (long) (2.5 * 60 * 60 * 1000);
    public static final long REPORT_DIALOG_INTERVAL_MS = 24L * 60 * 60 * 1000;

    // ── Security flags ────────────────────────────────────────────────────────
    public static final boolean ENABLE_ROOT_DETECTION = false;
    public static final boolean ENABLE_EMULATOR_DETECTION = false;
    public static final boolean ENABLE_ANTI_DEBUG = false;
    public static final boolean ENABLE_OBB_INTEGRITY_CHECK = false;
    public static final boolean ENABLE_SIGNATURE_CHECK = false;
    public static final String EXPECTED_SIGNATURE = "YOUR_APK_SIGNATURE_SHA256_HERE";

    /**
     * PATCH OBB FILE ENCRYPTION
     * When true: patch OBB is AES-256 encrypted after damage, decrypted before repair.
     * Key is stored in Android Keystore (hardware-backed).
     * Set to true when ready — requires ENABLE_PATCH_OBB_ENCRYPTION = true at build time.
     */
    public static final boolean ENABLE_PATCH_OBB_ENCRYPTION = false;

    // ── Dynamic OBB helpers ───────────────────────────────────────────────────

    public static String getGamePackage(Context ctx) {
        return ctx.getPackageName();
    }

    public static long getVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getVersionCode: " + e.getMessage());
            return 1;
        }
    }

    public static String getMainObbName(Context ctx) {
        return "main." + getVersionCode(ctx) + "." + getGamePackage(ctx) + ".obb";
    }

    public static String getPatchObbName(Context ctx) {
        return "patch." + getVersionCode(ctx) + "." + getGamePackage(ctx) + ".obb";
    }

    /**
     * Hidden patch OBB filename — dot-prefixed encrypted string.
     * Example: ".4275535349445f42445f484558..."
     * Dot prefix makes it hidden in file managers (Linux/Android convention).
     */
    public static String getHiddenObbName() {
        return "." + StringEncryptor.encrypt(HIDDEN_OBB_SEED);
    }
}
