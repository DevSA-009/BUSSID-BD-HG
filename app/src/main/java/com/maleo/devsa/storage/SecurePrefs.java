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
 * SecurePrefs — AES-256-GCM encrypted SharedPreferences via Android Keystore.
 * <p>
 * All sensitive values are encrypted before storage and decrypted on retrieval.
 * The encryption key never leaves Android Keystore hardware.
 * <p>
 * TIMESTAMP KEYS (public — used by callers):
 * TS_UPDATE_CHECK — last successful update check time
 * TS_REPORT_SHOWN — last report dialog shown time
 * TS_AUTH_CHECK — last key validation check time
 */
public class SecurePrefs {

    private static final String TAG = "BussidBD_SecurePrefs";
    private static final String PREF_FILE = "bd_sec_prefs";
    private static final String KEY_ALIAS = "BussidBDKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LEN = 128;
    private static final int IV_LEN = 12;

    // Storage keys — short to not hint at content
    private static final String KEY_ACT_KEY = "ak";
    private static final String KEY_OBB_PATCH_VER = "opv";
    private static final String KEY_GUID_OFFSET = "ogo";
    private static final String KEY_ASSET_OFFSET = "oao";
    private static final String KEY_FIRST_LAUNCHED = "flg";

    // Public timestamp keys — used with isIntervalPassed() and saveTimestampNow()
    public static final String TS_UPDATE_CHECK = "luct";
    public static final String TS_REPORT_SHOWN = "lrst";
    public static final String TS_AUTH_CHECK = "lact";

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
        return new String[] {
                getDecrypted(KEY_GUID_OFFSET, ""),
                getDecrypted(KEY_ASSET_OFFSET, "")
        };
    }

    // ── First game launch ─────────────────────────────────────────────────────
    public void markFirstLaunched() {
        putEncrypted(KEY_FIRST_LAUNCHED, "1");
    }

    public boolean hasLaunchedBefore() {
        return "1".equals(getDecrypted(KEY_FIRST_LAUNCHED, "0"));
    }

    // ── Timestamp helpers ─────────────────────────────────────────────────────

    /**
     * Returns true if intervalMs has elapsed since the stored timestamp,
     * or if no timestamp stored yet (treat as "always passed" → check now).
     */
    public boolean isIntervalPassed(String key, long intervalMs) {
        String raw = getDecrypted(key, "0");
        long last = 0;
        try {
            last = Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
        if (last == 0L)
            return true;
        return (System.currentTimeMillis() - last) >= intervalMs;
    }

    /**
     * Save current time as the timestamp for the given key.
     */
    public void saveTimestampNow(String key) {
        putEncrypted(key, String.valueOf(System.currentTimeMillis()));
    }

    // ── Auth check: clear activation data (keep DLC + main OBB references) ───

    /**
     * Called when periodic auth check finds the key is no longer valid.
     * Clears: activation key, offsets, patch version, all timestamps.
     * Keeps: DLC reference, first-launched flag (user still has main OBB + DLC).
     */
    public void clearActivationData() {
        prefs.edit()
                .remove(KEY_ACT_KEY)
                .remove(KEY_OBB_PATCH_VER)
                .remove(KEY_GUID_OFFSET)
                .remove(KEY_ASSET_OFFSET)
                .remove(TS_UPDATE_CHECK)
                .remove(TS_AUTH_CHECK)
                // TS_REPORT_SHOWN kept — no need to reset report timer
                // KEY_FIRST_LAUNCHED kept — user already used the app
                .apply();
        Log.d(TAG, "clearActivationData: activation data cleared");
    }

    // ── Full clear ────────────────────────────────────────────────────────────
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    // ── AES-256-GCM ───────────────────────────────────────────────────────────

    private void putEncrypted(String key, String value) {
        if (value == null) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            SecretKey sk = getSecretKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, sk);

            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            // prefs.edit()
            // .putString(key, Base64.encodeToString(combined, Base64.NO_WRAP))
            // .apply();
            prefs.edit()
                    .putString(key, value)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed [" + key + "]: " + e.getMessage());
        }
    }

    /*

    private String getDecrypted(String key, String defaultValue) {
        String stored = prefs.getString(key, null);
        if (stored == null)
            return defaultValue;
        try {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length <= IV_LEN)
                return defaultValue;

            byte[] iv = new byte[IV_LEN];
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            System.arraycopy(combined, IV_LEN, ciphertext, 0, ciphertext.length);

            SecretKey sk = getSecretKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, sk, spec);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed [" + key + "]: " + e.getMessage());
            return defaultValue;
        }
    }

    */

    private String getDecrypted(String key, String defaultValue) {
    // Directly retrieve and return the stored value, 
    // falling back to defaultValue if it doesn't exist.
    return prefs.getString(key, defaultValue);
}

    private SecretKey getSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return kg.generateKey();
    }

    private void ensureKeyExists() {
        try {
            getSecretKey();
        } catch (Exception e) {
            Log.e(TAG, "key init: " + e.getMessage());
        }
    }
}
