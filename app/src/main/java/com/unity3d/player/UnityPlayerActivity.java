package com.unity3d.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.maleo.bussidbdhg.R;
import com.maleo.devsa.network.ApiService;
import com.maleo.devsa.security.ObbProtector;
import com.maleo.devsa.storage.SecurePrefs;
import com.maleo.devsa.ui.ActivationActivity;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UnityPlayerActivity — MUST extend android.app.Activity (Unity requirement).
 * <p>
 * Welcome screen (activity_unity.xml) is shown briefly before Unity takes over.
 * After smali patching, Unity engine init code is added to onCreate().
 * <p>
 * OBB PROTECTION:
 * onStart → repairAndShow() : rename .hidden → patch, decrypt, repair bytes
 * onStop  → damageAndHide() : damage bytes, encrypt, rename patch → .hidden
 * <p>
 * MEMORY LEAK PREVENTION:
 * - ExecutorService.shutdownNow() in onDestroy
 * - Auth check uses WeakReference so background thread won't retain Activity
 * - All dialogs dismissed in onDestroy (N/A here — no dialogs in Unity Activity)
 */
public class UnityPlayerActivity extends Activity {

    private static final String TAG = "BussidBD_Unity";

    private ObbProtector protector;
    private SecurePrefs prefs;
    private ExecutorService bg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Welcome screen — Unity replaces this after smali patching
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = new SecurePrefs(this);
        bg = Executors.newSingleThreadExecutor();
        protector = buildProtector();

        Log.d(TAG, "onCreate");
        // SMALI PATCH NOTE: copy Unity mUnityPlayer init lines here
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Rename .hidden → patch, decrypt (if enabled), repair bytes
        protector.repairAndShow();
        Log.d(TAG, "onStart — repair done");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Damage bytes, encrypt (if enabled), rename patch → .hidden
        protector.damageAndHide();
        Log.d(TAG, "onStop — damage done");
    }

    @Override
    protected void onResume() {
        super.onResume();
        runAuthCheck();
    }

    @Override
    protected void onDestroy() {
        // Prevent memory leak — cancel any running background tasks
        if (bg != null) {
            bg.shutdownNow();
            bg = null;
        }
        super.onDestroy();
    }

    // ── Auth Re-check ─────────────────────────────────────────────────────────

    /**
     * Background auth check. Uses WeakReference so if Activity is destroyed
     * while check is running, the background thread won't retain it in memory.
     */
    private void runAuthCheck() {
        String key = prefs.getActivationKey();
        if (key == null) {
            redirectToAuth();
            return;
        }

        // WeakReference prevents memory leak if Activity finishes during network call
        WeakReference<UnityPlayerActivity> weakThis = new WeakReference<>(this);

        bg.execute(() -> {
            ApiService api = new ApiService();
            String result = api.verifyKey(key);

            UnityPlayerActivity act = weakThis.get();
            if (act == null || act.isFinishing()) return; // Activity gone — don't crash

            boolean revoked = ApiService.RESP_NE.equals(result)
                    || ApiService.RESP_EXP.equals(result);

            if (revoked) {
                Log.w(TAG, "Auth revoked — damaging OBB");
                protector.damageAndHide();
                act.runOnUiThread(act::redirectToAuth);
            } else {
                Log.d(TAG, "Auth ok: " + result);
            }
        });
    }

    private void redirectToAuth() {
        prefs.clearActivationKey();
        Intent i = new Intent(this, ActivationActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private ObbProtector buildProtector() {
        String[] off = prefs.getObbOffsets();
        return new ObbProtector(this, off[0], off[1]);
    }

    // ── Input stubs — filled during smali patch ───────────────────────────────
    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        return super.onKeyUp(k, e);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return super.onTouchEvent(e);
    }
}
