package com.maleo.devsa.security;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import com.maleo.devsa.util.AppConfig;

import java.io.File;
import java.security.MessageDigest;

/**
 * SecurityGuard — Optional security checks at app launch.
 * <p>
 * Each check is controlled by a boolean flag in AppConfig.
 * Set the flag to true in AppConfig when you want to enable it.
 * <p>
 * Currently all flags are false — these methods do nothing until you enable them.
 * <p>
 * Usage in ActivationActivity.onCreate():
 * if (!SecurityGuard.runAllChecks(this)) { finish(); return; }
 */
public final class SecurityGuard {

    private SecurityGuard() {
    }

    /**
     * Run all enabled security checks.
     *
     * @return true if all checks pass (app should continue), false if any check fails (app should exit).
     */
    public static boolean runAllChecks(Context context) {
        if (AppConfig.ENABLE_ANTI_DEBUG && isDebuggerAttached()) return false;
        if (AppConfig.ENABLE_ROOT_DETECTION && isRooted()) return false;
        if (AppConfig.ENABLE_EMULATOR_DETECTION && isEmulator()) return false;
        if (AppConfig.ENABLE_SIGNATURE_CHECK && !isSignatureValid(context)) return false;
        return true;
    }

    // ─── Anti-Debug ──────────────────────────────────────────────────────────

    /**
     * Returns true if a debugger is connected.
     * When ENABLE_ANTI_DEBUG = true, the app exits if this is true.
     */
    private static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    // ─── Root Detection ───────────────────────────────────────────────────────

    /**
     * Basic root detection — checks for common root binaries and build tags.
     * Note: Magisk can hide root from this check. More advanced checks exist
     * but this covers most casual cases.
     */
    private static boolean isRooted() {
        // Check build tags
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) return true;

        // Check for su binary in common locations
        String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    // ─── Emulator Detection ───────────────────────────────────────────────────

    /**
     * Detects common Android emulators by checking build properties.
     */
    private static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    // ─── Signature Check ──────────────────────────────────────────────────────

    /**
     * Checks the APK's signing certificate SHA-256 hash against AppConfig.EXPECTED_SIGNATURE.
     * If the app was repackaged and signed with a different key, this returns false.
     */
    private static boolean isSignatureValid(Context context) {
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sigs = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
            } else {
                //noinspection deprecation
                sigs = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES)
                        .signatures;
            }
            if (sigs == null || sigs.length == 0) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());

            // Convert to lowercase hex string
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));

            return hex.toString().equals(AppConfig.EXPECTED_SIGNATURE.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }
}
