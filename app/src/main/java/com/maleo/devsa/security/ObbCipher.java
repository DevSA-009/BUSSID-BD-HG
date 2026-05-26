package com.maleo.devsa.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

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

/**
 * ObbCipher — AES-256-CBC streaming file encryption/decryption.
 * <p>
 * WHY CBC (not GCM) for file encryption:
 * GCM requires the entire plaintext to generate the auth tag — not ideal for large files.
 * CBC streams the data, making it memory-efficient for multi-MB OBB files.
 * <p>
 * KEY: Stored in Android Keystore under alias "BussidBDObbKey" — separate from SecurePrefs key.
 * <p>
 * IV: 16 bytes, prepended to the encrypted file.
 * Encrypt: generate random IV → write IV to file → stream encrypt rest.
 * Decrypt: read first 16 bytes as IV → stream decrypt rest.
 * <p>
 * ONLY ACTIVE when AppConfig.ENABLE_PATCH_OBB_ENCRYPTION = true.
 * When false, encrypt/decrypt are no-ops (return true immediately).
 */
public class ObbCipher {

    private static final String TAG = "BussidBD_ObbCipher";
    private static final String KEY_ALIAS = "BussidBDObbKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String AES_MODE = "AES/CBC/PKCS7Padding";
    private static final int IV_LEN = 16;
    private static final int BUFFER = 16384; // 16KB chunks — memory efficient

    private final Context ctx;

    public ObbCipher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        ensureKeyExists();
    }

    /**
     * Encrypt file in-place using a temp file.
     * Input file is replaced by encrypted version.
     *
     * @return true on success
     */
    public boolean encrypt(File file) {
        if (!com.maleo.devsa.util.AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) return true;
        if (!file.exists()) return false;

        File temp = new File(file.getParent(), file.getName() + ".enc_tmp");
        try {
            SecretKey key = getKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();

            try (FileInputStream fis = new FileInputStream(file); FileOutputStream fos = new FileOutputStream(temp); CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
                // Write IV first (needed for decryption)
                fos.write(iv);
                byte[] buf = new byte[BUFFER];
                int read;
                while ((read = fis.read(buf)) != -1) cos.write(buf, 0, read);
            }

            // Replace original with encrypted temp
            if (!file.delete() || !temp.renameTo(file)) {
                Log.e(TAG, "encrypt: replace failed");
                temp.delete();
                return false;
            }
            Log.d(TAG, "encrypted: " + file.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "encrypt error: " + e.getMessage());
            temp.delete();
            return false;
        }
    }

    /**
     * Decrypt file in-place using a temp file.
     * Input file is replaced by decrypted version.
     *
     * @return true on success
     */
    public boolean decrypt(File file) {
        if (!com.maleo.devsa.util.AppConfig.ENABLE_PATCH_OBB_ENCRYPTION) return true;
        if (!file.exists()) return false;

        File temp = new File(file.getParent(), file.getName() + ".dec_tmp");
        try {
            // Read IV from beginning of file
            byte[] iv = new byte[IV_LEN];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = 0;
                while (read < IV_LEN) {
                    int r = fis.read(iv, read, IV_LEN - read);
                    if (r == -1) throw new IOException("File too short for IV");
                    read += r;
                }
            }

            SecretKey key = getKey();
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));

            try (FileInputStream fis = new FileInputStream(file); CipherInputStream cis = new CipherInputStream(fis, cipher); FileOutputStream fos = new FileOutputStream(temp)) {
                // Skip IV bytes
                long skipped = 0;
                while (skipped < IV_LEN) skipped += fis.skip(IV_LEN - skipped);

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

    // ── Keystore ─────────────────────────────────────────────────────────────

    private SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_CBC).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7).setKeySize(256).setUserAuthenticationRequired(false).build());
        return kg.generateKey();
    }

    private void ensureKeyExists() {
        try {
            getKey();
        } catch (Exception e) {
            Log.e(TAG, "key init failed: " + e.getMessage());
        }
    }
}
