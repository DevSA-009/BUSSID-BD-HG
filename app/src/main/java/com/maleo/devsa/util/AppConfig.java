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
//    public static final String BASE_URL        = "http://10.0.2.2:5000";
    public static final String BASE_URL = "https://bussid-bd-hexagon.vercel.app";
    public static final int CONNECT_TIMEOUT = 15;
    public static final int READ_TIMEOUT = 30;
    public static final int WRITE_TIMEOUT = 15;

    /**
     * Seed string for hidden patch OBB filename (encrypted + dot-prefixed at runtime).
     */
    public static final String HIDDEN_OBB_SEED = "BUSSID_BD_HEXAGON_DEVSA";

    // ── Time intervals (milliseconds) ─────────────────────────────────────────

    /**
     * How often to check for OBB update: 2.5 hours.
     */
    public static final long UPDATE_CHECK_INTERVAL_MS = (long) (2.5 * 60 * 60 * 1000);

    /**
     * How often to show the report dialog: 24 hours.
     */
    public static final long REPORT_DIALOG_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * How often to re-validate the activation key with the server: 24 hours.
     * Uses GET /user/ck/:key — if response is not RESP_VALID, clears activation data.
     */
    public static final long AUTH_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    // ── Patch OBB encryption ──────────────────────────────────────────────────

    /**
     * Master switch for patch OBB file encryption.
     * When false: no encryption applied, all other encryption flags are ignored.
     * When true: encryption is applied using either Keystore or HEX key (see below).
     */
    public static final boolean ENABLE_PATCH_OBB_ENCRYPTION = true;

    /**
     * Your custom AES-256 key as a hex string (64 hex chars = 32 bytes).
     * Used when ENABLE_PATCH_OBB_ENCRYPTION=true AND USE_KEYSTORE_FOR_OBB=false.
     * Replace with your own generated key.
     * Example generation: openssl rand -hex 32
     */
    public static final String OBB_ENCRYPT_KEY_HEX = "ba1766b9f777a557afbf0e9c3d2a1b4c5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";

    /**
     * When true: use Android Keystore for OBB encryption (hardware-backed, more secure).
     * When false: use OBB_ENCRYPT_KEY_HEX instead.
     * Keystore takes PRIORITY over HEX key when this is true.
     */
    public static final boolean USE_KEYSTORE_FOR_OBB = false;

    // ── Other security flags ──────────────────────────────────────────────────
    public static final boolean ENABLE_ROOT_DETECTION = false;
    public static final boolean ENABLE_EMULATOR_DETECTION = false;
    public static final boolean ENABLE_ANTI_DEBUG = false;
    public static final boolean ENABLE_SIGNATURE_CHECK = false;
    public static final String EXPECTED_SIGNATURE = "YOUR_APK_SIGNATURE_SHA256_HERE";

    // ── Dynamic OBB name helpers ──────────────────────────────────────────────

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

    /**
     * main.{versionCode}.{packageName}.obb
     */
    public static String getMainObbName(Context ctx) {
        return "main." + getVersionCode(ctx) + "." + getGamePackage(ctx) + ".obb";
    }

    /**
     * patch.{versionCode}.{packageName}.obb
     */
    public static String getPatchObbName(Context ctx) {
        return "patch." + getVersionCode(ctx) + "." + getGamePackage(ctx) + ".obb";
    }

    /**
     * Hidden patch OBB filename: "." + StringEncryptor.encrypt(HIDDEN_OBB_SEED)
     * Dot prefix hides it in file managers (Linux/Android convention).
     */
    public static String getHiddenObbName() {
        return "." + StringEncryptor.encrypt(HIDDEN_OBB_SEED);
    }
}
