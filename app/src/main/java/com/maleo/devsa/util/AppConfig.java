package com.maleo.devsa.util;

/**
 * AppConfig — Central configuration file.
 *
 * HOW TO USE:
 * - To enable/disable extra security features, change the boolean flags below to true/false.
 * - To change server URL, update BASE_URL.
 * - Never hardcode these values anywhere else in the project.
 */
public final class AppConfig {

    private AppConfig() {}

    // ─────────────────────────────────────────────
    // SERVER
    // ─────────────────────────────────────────────

    /** Base URL of the backend server. Change this when you deploy. */
    public static final String BASE_URL = "http://10.0.2.2:5000";

    // ─────────────────────────────────────────────
    // NETWORK TIMEOUTS (seconds)
    // ─────────────────────────────────────────────

    public static final int CONNECT_TIMEOUT = 15;
    public static final int READ_TIMEOUT    = 30;
    public static final int WRITE_TIMEOUT   = 15;

    // ─────────────────────────────────────────────
    // OBB PROTECTION
    // ─────────────────────────────────────────────

    /**
     * The package name of the game whose OBB we protect.
     * Used to build OBB directory path: /Android/obb/{GAME_PACKAGE}/
     * Change this to match the actual game package name.
     */
    public static final String GAME_PACKAGE = "com.maleo.bussimulatorid";

    /**
     * Name of the OBB file inside the OBB directory.
     * Usually: main.{versionCode}.{packageName}.obb
     */
    public static final String OBB_FILE_NAME = "main.1.com.maleo.bussimulatorid.obb";

    /**
     * Name of the DLC zip file placed by the user.
     * This is extracted into the game's files directory.
     */
    public static final String DLC_ZIP_NAME = "dlc_bussidbd.zip";

    // ─────────────────────────────────────────────
    // SECURITY FEATURE FLAGS
    // ─────────────────────────────────────────────
    // Set these to true when ready to enable.
    // Each flag is checked at runtime, so no recompile needed — just change and rebuild.

    /**
     * ROOT DETECTION
     * If true: app checks if device is rooted. If rooted, shows warning and exits.
     * Protects against users who modify app behavior with root tools (e.g. Lucky Patcher).
     * Set to true when ready. Currently false — does nothing.
     */
    public static final boolean ENABLE_ROOT_DETECTION = false;

    /**
     * EMULATOR DETECTION
     * If true: app blocks running on Android emulators.
     * Emulators are often used to bypass license checks.
     * Set to true when ready. Currently false — does nothing.
     */
    public static final boolean ENABLE_EMULATOR_DETECTION = false;

    /**
     * ANTI-DEBUG
     * If true: app checks if a debugger is attached at launch.
     * If a debugger is found, the app exits immediately.
     * Prevents reverse engineers from stepping through auth code.
     * Set to true when ready. Currently false — does nothing.
     */
    public static final boolean ENABLE_ANTI_DEBUG = false;

    /**
     * OBB INTEGRITY CHECK
     * If true: before launching the game, the first 16 bytes of the OBB file
     * are verified against expected values. Prevents tampered OBB files from running.
     * Set to true when ready. Currently false — does nothing.
     */
    public static final boolean ENABLE_OBB_INTEGRITY_CHECK = false;

    /**
     * APK SIGNATURE CHECK
     * If true: at launch, the app verifies its own APK signing certificate.
     * If someone repacks and signs the APK with a different key, the app exits.
     * Protects against repackaged/cracked versions.
     * Set to true and fill EXPECTED_SIGNATURE below when ready.
     */
    public static final boolean ENABLE_SIGNATURE_CHECK = false;

    /**
     * Expected APK signature SHA-256 hash (hex string, lowercase, no colons).
     * Run: keytool -printcert -jarfile your-release.apk
     * and copy the SHA-256 value here (remove spaces and colons).
     * Only used when ENABLE_SIGNATURE_CHECK = true.
     */
    public static final String EXPECTED_SIGNATURE = "YOUR_APK_SIGNATURE_SHA256_HERE";

    // ─────────────────────────────────────────────
    // REPORT DIALOG — show once per day
    // ─────────────────────────────────────────────

    /**
     * How many hours after first game launch to show the report dialog.
     * Default: 24 hours (1 day). User sees it once per day.
     */
    public static final int REPORT_DIALOG_INTERVAL_HOURS = 24;
}
