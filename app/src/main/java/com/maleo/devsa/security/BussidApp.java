package com.maleo.devsa.security;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.maleo.devsa.storage.SecurePrefs;

/**
 * BussidApp — Custom Application class.
 * <p>
 * WHY THIS CLASS EXISTS:
 * Android creates the Application object BEFORE any Activity or Service.
 * It lives for the entire lifetime of the app process.
 * <p>
 * We use ProcessLifecycleOwner here as a BACKUP damage layer for OBB protection.
 * <p>
 * HOW ProcessLifecycleOwner WORKS:
 * It monitors whether ANY Activity in the app is in the foreground.
 * - onStart: at least one Activity became visible
 * - onStop:  ALL Activities went to background (home, swipe, navigation)
 * <p>
 * This is more reliable than Activity.onStop() alone because:
 * - Catches edge cases in singleTask navigation
 * - Fires even under memory pressure
 * - Acts as safety net if UnityPlayerActivity.onStop() is skipped
 * <p>
 * IMPORTANT: We only DAMAGE here (never repair).
 * Repair happens ONLY in UnityPlayerActivity.onStart() AFTER auth verification.
 * If we repaired in onStart here, an unauthorized user could bypass auth.
 */
public class BussidApp extends Application {

    private static final String TAG = "BussidBD_App";

    @Override
    public void onCreate() {
        super.onCreate();
        registerProcessLifecycle();
    }

    private void registerProcessLifecycle() {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {

            @Override
            public void onStart(LifecycleOwner owner) {
                Log.d(TAG, "Process → foreground (repair NOT done here — only in UnityPlayerActivity)");
            }

            @Override
            public void onStop(LifecycleOwner owner) {
                Log.d(TAG, "Process → background — backup damageAndHide");
                backupDamage();
            }
        });
    }

    private void backupDamage() {
        SecurePrefs prefs = new SecurePrefs(this);
        if (!prefs.isActivated()) {
            Log.d(TAG, "backup: no active key — skip");
            return;
        }
        String[] offsets = prefs.getObbOffsets();
        ObbProtector protector = new ObbProtector(this, offsets[0], offsets[1]);
        if (!protector.isPatchObbPresent()) {
            Log.d(TAG, "backup: patch OBB not present — skip");
            return;
        }
        protector.damageAndHide();
    }
}
