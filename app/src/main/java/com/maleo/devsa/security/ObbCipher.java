package com.maleo.devsa.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.maleo.devsa.util.AppConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ObbCipher — AES-256-CBC streaming file encryption/decryption.
 * <p>
 * KEY PRIORITY (when ENABLE_PATCH_OBB_ENCRYPTION = true):
 * USE_KEYSTORE_FOR_OBB = true  → Android Keystore key (hardware-backed, priority)
 * USE_KEYSTORE_FOR_OBB = false → OBB_ENCRYPT_KEY_HEX (your custom hex key)
 * <p>
 * When ENABLE_PATCH_OBB_ENCRYPTION = false: encrypt/decrypt are no-ops (return true).
 * <p>
 * FILE FORMAT (encrypted):
 * [16 bytes IV][CBC encrypted content]
 * <p>
 * Memory efficient: streams 16KB chunks — does NOT load entire OBB into RAM.
 */
public class ObbCipher {

    private static final String TAG = "BussidBD_ObbCipher";
    private static final String KEY_ALIAS = "BussidBDObbKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String AES_MODE = "AES/CBC/PKCS7Padding";
    private static final int IV_LEN = 16;
    private static final int BUFFER = 16384; // 16KB

    public ObbCipher(Context ctx) {
        ctx.getApplicationContext();

        if (AppConfig.ENABLE_PATCH_OBB_ENCRYPTION && AppConfig.USE_KEYSTORE_FOR_OBB) {
            ensureKeystoreKeyExists();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypt file in-place. Returns true on success or if encryption disabled.
     */
    public boolean encrypt(File file) {
        if (!AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) return true;
        if (!file.exists()) {
            Log.w(TAG, "encrypt: file not found: " + file.getName());
            return false;
        }

        File temp = new File(file.getParent(), file.getName() + ".enc_tmp");
        try {
            SecretKey key = resolveKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();

            try (FileInputStream fis = new FileInputStream(file);
                 FileOutputStream fos = new FileOutputStream(temp);
                 CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
                fos.write(iv); // prepend IV
                byte[] buf = new byte[BUFFER];
                int read;
                while ((read = fis.read(buf)) != -1) cos.write(buf, 0, read);
            }

            if (!file.delete() || !temp.renameTo(file)) {
                Log.e(TAG, "encrypt: replace failed");
                temp.delete();
                return false;
            }
            Log.d(TAG, "encrypted: " + file.getName() + " (keystore=" + AppConfig.USE_KEYSTORE_FOR_OBB + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "encrypt error: " + e.getMessage());
            temp.delete();
            return false;
        }
    }

    /**
     * Decrypt file in-place. Returns true on success or if encryption disabled.
     */
    public boolean decrypt(File file) {
        if (!AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) return true;
        if (!file.exists()) {
            Log.w(TAG, "decrypt: file not found: " + file.getName());
            return false;
        }

        File temp = new File(file.getParent(), file.getName() + ".dec_tmp");
        try {
            // Read IV from first 16 bytes
            byte[] iv = new byte[IV_LEN];
            try (FileInputStream fis = new FileInputStream(file)) {
                int total = 0;
                while (total < IV_LEN) {
                    int r = fis.read(iv, total, IV_LEN - total);
                    if (r == -1) throw new IOException("File too short for IV");
                    total += r;
                }
            }

            SecretKey key = resolveKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));

            try (FileInputStream fis = new FileInputStream(file);
                 CipherInputStream cis = new CipherInputStream(
                         new SkipInputStream(fis, IV_LEN), cipher);
                 FileOutputStream fos = new FileOutputStream(temp)) {
                byte[] buf = new byte[BUFFER];
                int read;
                while ((read = cis.read(buf)) != -1) fos.write(buf, 0, read);
            }

            if (!file.delete() || !temp.renameTo(file)) {
                Log.e(TAG, "decrypt: replace failed");
                temp.delete();
                return false;
            }
            Log.d(TAG, "decrypted: " + file.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "decrypt error: " + e.getMessage());
            temp.delete();
            return false;
        }
    }

    // ── Key resolution ────────────────────────────────────────────────────────

    /**
     * Resolve AES key based on flags:
     * USE_KEYSTORE_FOR_OBB = true  → Keystore key (priority)
     * USE_KEYSTORE_FOR_OBB = false → hex string key
     */
    private SecretKey resolveKey() throws Exception {
        if (AppConfig.USE_KEYSTORE_FOR_OBB) {
            return getKeystoreKey();
        } else {
            return hexToKey();
        }
    }

    private SecretKey hexToKey() {
        // Hex string → 32 bytes → AES-256 key
        StringBuilder clean = new StringBuilder(AppConfig.OBB_ENCRYPT_KEY_HEX.trim().toLowerCase());
        // Ensure exactly 64 hex chars (32 bytes) — pad or trim if needed
        if (clean.length() > 64) clean = new StringBuilder(clean.substring(0, 64));
        while (clean.length() < 64) clean.append("0");
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    // ── Android Keystore ──────────────────────────────────────────────────────

    private SecretKey getKeystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return kg.generateKey();
    }

    private void ensureKeystoreKeyExists() {
        try {
            getKeystoreKey();
        } catch (Exception e) {
            Log.e(TAG, "keystore init failed: " + e.getMessage());
        }
    }

    // ── Helper: InputStream that skips N bytes ────────────────────────────────

    private static class SkipInputStream extends java.io.FilterInputStream {
        private final long skipBytes;
        private long skipped = 0;

        SkipInputStream(java.io.InputStream in, long skipBytes) {
            super(in);
            this.skipBytes = skipBytes;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (skipped < skipBytes) {
                long s = in.skip(skipBytes - skipped);
                if (s <= 0) return -1;
                skipped += s;
            }
            return super.read(b, off, len);
        }
    }
}
