package com.maleo.devsa.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.maleo.devsa.security.StringEncryptor;

/**
 * Single source of truth for all persisted data.
 */
public class PrefManager {

    private static final String PREF_FILE = "bd_prefs";
    private static final String KEY_ACTIVATION_KEY = "ak";
    private static final String KEY_OBB_PATCH_VER = "opv";
    private static final String KEY_REPORT_LAST = "rls";
    private static final String KEY_DLC_INSTALLED = "dlci";
    private static final String KEY_OBB_GUID_OFFSET = "ogo";
    private static final String KEY_OBB_ASSET_OFFSET = "oao";

    private final SharedPreferences prefs;

    public PrefManager(Context context) {
        prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // Activation Key
    public void saveActivationKey(String key) {
        prefs.edit().putString(KEY_ACTIVATION_KEY, StringEncryptor.encrypt(key)).apply();
    }

    public String getActivationKey() {
        return prefs.getString(KEY_ACTIVATION_KEY, null);
    }

    public boolean isActivated() {
        return getActivationKey() != null;
    }

    public void clearActivationKey() {
        prefs.edit().remove(KEY_ACTIVATION_KEY).apply();
    }

    // OBB patch version
    public void saveObbPatchVersion(String v) {
        prefs.edit().putString(KEY_OBB_PATCH_VER, v).apply();
    }

    public String getObbPatchVersion() {
        return prefs.getString(KEY_OBB_PATCH_VER, "unknown");
    }

    // OBB protection offsets (received from server, stored for UnityPlayerActivity)
    public void saveObbOffsets(String guidOffset, String assetOffset) {
        prefs.edit()
                .putString(KEY_OBB_GUID_OFFSET, guidOffset)
                .putString(KEY_OBB_ASSET_OFFSET, assetOffset)
                .apply();
    }

    /**
     * Returns [guidOffset, assetOffset]. Both may be empty string if not yet set.
     */
    public String[] getObbOffsets() {
        return new String[]{
                prefs.getString(KEY_OBB_GUID_OFFSET, ""),
                prefs.getString(KEY_OBB_ASSET_OFFSET, "")
        };
    }

    // DLC
    public void setDlcInstalled(boolean v) {
        prefs.edit().putBoolean(KEY_DLC_INSTALLED, v).apply();
    }

    public boolean isDlcInstalled() {
        return prefs.getBoolean(KEY_DLC_INSTALLED, false);
    }

    // Report dialog — shown once per interval
    public void saveReportShownNow() {
        prefs.edit().putLong(KEY_REPORT_LAST, System.currentTimeMillis()).apply();
    }

    public boolean shouldShowReportDialog(int hours) {
        long last = prefs.getLong(KEY_REPORT_LAST, 0L);
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) >= ((long) hours * 3600000L);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
