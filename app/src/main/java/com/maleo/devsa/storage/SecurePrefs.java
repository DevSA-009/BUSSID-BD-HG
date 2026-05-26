package com.maleo.devsa.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SecurePrefs — AES-256-GCM encrypted SharedPreferences backed by Android Keystore.
 * <p>
 * HOW IT WORKS:
 * - A symmetric AES-256 key is generated inside the Android Keystore hardware.
 * - The key never leaves the secure hardware — it cannot be extracted even on rooted devices.
 * - Every value is encrypted with AES/GCM/NoPadding before storing in SharedPreferences.
 * - A fresh random 12-byte IV is generated per encryption operation.
 * - Stored format: Base64(IV + ciphertext)
 * <p>
 * STORED DATA:
 * ak   — activation key (encrypted)
 * opv  — OBB patch version (encrypted)
 * ogo  — GUID offset hex string (encrypted)
 * oao  — asset offset hex string (encrypted)
 * dlci — DLC installed flag (encrypted boolean as string)
 * luct — last update check timestamp (encrypted long as string)
 * lrst — last report shown timestamp (encrypted long as string)
 * flg  — first game launched flag (encrypted boolean as string)
 */
public class SecurePrefs {

    private static final String TAG = "BussidBD_SecurePrefs";
    private static final String PREF_FILE = "bd_sec_prefs";
    private static final String KEY_ALIAS = "BussidBDKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LEN = 128;
    private static final int IV_LEN = 12;

    // SharedPreferences keys — short to not hint at content
    private static final String KEY_ACT_KEY = "ak";
    private static final String KEY_OBB_PATCH_VER = "opv";
    private static final String KEY_GUID_OFFSET = "ogo";
    private static final String KEY_ASSET_OFFSET = "oao";
    private static final String KEY_DLC_INSTALLED = "dlci";
    private static final String KEY_LAST_UPD_CHECK = "luct";
    private static final String KEY_LAST_RPT_SHOWN = "lrst";
    private static final String KEY_FIRST_LAUNCHED = "flg";

    private final SharedPreferences prefs;

    public SecurePrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        ensureKeyExists();
    }

    // ── Activation Key ────────────────────────────────────────────────────────
    public void saveActivationKey(String key) {
        putEncrypted(KEY_ACT_KEY, key);
    }

    public String getActivationKey() {
        return getDecrypted(KEY_ACT_KEY, null);
    }

    public boolean isActivated() {
        return getActivationKey() != null;
    }

    public void clearActivationKey() {
        prefs.edit().remove(KEY_ACT_KEY).apply();
    }

    // ── OBB patch version ─────────────────────────────────────────────────────
    public void saveObbPatchVersion(String v) {
        putEncrypted(KEY_OBB_PATCH_VER, v);
    }

    public String getObbPatchVersion() {
        return getDecrypted(KEY_OBB_PATCH_VER, "unknown");
    }

    // ── OBB offsets ───────────────────────────────────────────────────────────
    public void saveObbOffsets(String guid, String asset) {
        putEncrypted(KEY_GUID_OFFSET, guid);
        putEncrypted(KEY_ASSET_OFFSET, asset);
    }

    public String[] getObbOffsets() {
        return new String[]{
                getDecrypted(KEY_GUID_OFFSET, ""),
                getDecrypted(KEY_ASSET_OFFSET, "")
        };
    }

    // ── DLC ───────────────────────────────────────────────────────────────────
    public void setDlcInstalled(boolean v) {
        putEncrypted(KEY_DLC_INSTALLED, v ? "1" : "0");
    }

    public boolean isDlcInstalled() {
        return "1".equals(getDecrypted(KEY_DLC_INSTALLED, "0"));
    }

    // ── First game launch flag ─────────────────────────────────────────────────
    public void markFirstLaunched() {
        putEncrypted(KEY_FIRST_LAUNCHED, "1");
    }

    public boolean hasLaunchedBefore() {
        return "1".equals(getDecrypted(KEY_FIRST_LAUNCHED, "0"));
    }

    // ── Timestamp helpers (shared logic) ──────────────────────────────────────

    /**
     * Returns true if the interval has passed since the stored timestamp,
     * or if no timestamp is stored yet (first time).
     */
    public boolean isIntervalPassed(String key, long intervalMs) {
        String raw = getDecrypted(key, "0");
        long last = 0;
        try {
            last = Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) >= intervalMs;
    }

    /**
     * Save current time as the timestamp for the given key.
     */
    public void saveTimestampNow(String key) {
        putEncrypted(key, String.valueOf(System.currentTimeMillis()));
    }

    // ── Timestamp keys (public so callers can pass them in) ───────────────────
    public static final String TS_UPDATE_CHECK = "luct";
    public static final String TS_REPORT_SHOWN = "lrst";

    // ── Clear all ─────────────────────────────────────────────────────────────
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    // ── AES-256-GCM Encryption ────────────────────────────────────────────────

    private void putEncrypted(String key, String value) {
        if (value == null) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] iv = cipher.getIV();             // random 12 bytes
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            // Store as Base64(iv + ciphertext)
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            prefs.edit()
                    .putString(key, Base64.encodeToString(combined, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Encrypt failed for key " + key + ": " + e.getMessage());
        }
    }

    private String getDecrypted(String key, String defaultValue) {
        String stored = prefs.getString(key, null);
        if (stored == null) return defaultValue;
        try {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length <= IV_LEN) return defaultValue;

            byte[] iv = new byte[IV_LEN];
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            System.arraycopy(combined, IV_LEN, ciphertext, 0, ciphertext.length);

            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Decrypt failed for key " + key + ": " + e.getMessage());
            return defaultValue;
        }
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        // Key doesn't exist yet — generate it
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // no fingerprint required
                .build());
        return kg.generateKey();
    }

    private void ensureKeyExists() {
        try {
            getSecretKey();
        } catch (Exception e) {
            Log.e(TAG, "Key init failed: " + e.getMessage());
        }
    }
}
