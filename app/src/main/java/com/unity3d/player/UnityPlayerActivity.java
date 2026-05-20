package com.unity3d.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.maleo.devsa.network.ApiService;
import com.maleo.devsa.security.ObbProtector;
import com.maleo.devsa.storage.PrefManager;
import com.maleo.devsa.ui.ActivationActivity;
import com.maleo.devsa.util.AppConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UnityPlayerActivity — The game's main Activity.
 *
 * IMPORTANT: This MUST extend android.app.Activity (NOT AppCompatActivity).
 * Unity requires plain Activity. If you change this, the game will crash.
 *
 * RESPONSIBILITIES:
 *  1. OBB Protection — repair on start, damage on stop.
 *  2. Background auth check — periodic server re-validation.
 *  3. Report dialog trigger — shown once per day after game session.
 *
 * OBB PROTECTION LOGIC (improved from original):
 *  - onStart()  → repair OBB  (game visible, OBB must be readable)
 *  - onStop()   → damage OBB  (game hidden for ANY reason — home, recent, swipe kill)
 *
 * WHY onStop() instead of onWindowFocusChanged():
 *  onWindowFocusChanged() fires on keyboard popup, dialog show, etc — too sensitive.
 *  onStop() fires exactly when the Activity is no longer visible to the user:
 *    - Home button pressed
 *    - Recent apps (swipe to kill)
 *    - Navigation back button
 *    - Another app comes to foreground
 *    - Device screen locked
 *  Android guarantees onStop() is called before the process is eligible to be killed.
 *  So even force-kill from task manager triggers onStop() first.
 *
 * NOTE: UnityPlayer reference is intentionally left as a stub because
 * the real UnityPlayer class comes from the game APK during smali patching.
 * This file compiles without it — just provides the Activity shell.
 */
public class UnityPlayerActivity extends Activity {

    private ObbProtector    obbProtector;
    private PrefManager     prefs;
    private ExecutorService bg;

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while game runs
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = new PrefManager(this);
        bg    = Executors.newSingleThreadExecutor();

        // Build ObbProtector using offsets saved during activation/update flow
        String[] offsets = prefs.getObbOffsets();
        obbProtector     = new ObbProtector(this, offsets[0], offsets[1]);

        /*
         * NOTE FOR SMALI PATCHING:
         * After decompiling the game APK, you will find UnityPlayer initialization code
         * in the original UnityPlayerActivity.smali.
         * Copy those lines (mUnityPlayer = new UnityPlayer(this), setContentView, etc.)
         * into this onCreate() after the lines above.
         * This file provides the auth + OBB protection shell only.
         */
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Game about to become visible — OBB must be readable
        obbProtector.repair();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Game no longer visible — protect OBB immediately.
        // This covers: home button, recent apps swipe, navigation back, screen lock.
        obbProtector.damage();

        // Show report dialog next time ActivationActivity runs (once per day)
        // We trigger it by checking the flag in ActivationActivity on next launch.
        // Nothing needed here — PrefManager.shouldShowReportDialog() handles timing.
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Periodic auth re-check (once per interval defined by server)
        runPeriodicAuthCheck();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bg != null) bg.shutdown();
    }

    // ─── Auth Re-Check ───────────────────────────────────────────────────────

    /**
     * Re-validates the activation key with the server.
     * If key is revoked/expired, OBB is damaged and user is sent back to auth screen.
     * Runs silently in background — does NOT block the game.
     */
    private void runPeriodicAuthCheck() {
        String key = prefs.getActivationKey();
        if (key == null) {
            // No key — should not happen here, but safety net
            redirectToAuth();
            return;
        }

        bg.execute(() -> {
            ApiService api    = new ApiService();
            String     result = api.verifyKey(key, prefs.getObbPatchVersion());

            // Only act if server explicitly says key is invalid/expired/blocked
            boolean keyRevoked = ApiService.RESP_NE.equals(result)
                              || ApiService.RESP_EXP.equals(result);

            if (keyRevoked) {
                // Damage OBB and kick user back to auth screen
                obbProtector.damage();
                runOnUiThread(this::redirectToAuth);
            }
            // On server error or offline: do nothing — game continues
        });
    }

    private void redirectToAuth() {
        prefs.clearActivationKey();
        Intent intent = new Intent(this, ActivationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ─── Unity Input Forwarding (stub — filled during smali patch) ───────────

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }
}
