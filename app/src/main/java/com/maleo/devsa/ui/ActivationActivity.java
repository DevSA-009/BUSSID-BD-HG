package com.maleo.devsa.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.maleo.bussidbdhg.R;
import com.maleo.devsa.network.ApiService;
import com.maleo.devsa.security.ObbPatcher;
import com.maleo.devsa.security.ObbProtector;
import com.maleo.devsa.security.SecurityGuard;
import com.maleo.devsa.storage.PrefManager;
import com.maleo.devsa.ui.dialogs.ActivationDialog;
import com.maleo.devsa.ui.dialogs.InfoDialog;
import com.maleo.devsa.ui.dialogs.ObbPatchDialog;
import com.maleo.devsa.ui.dialogs.ProgressDialog;
import com.maleo.devsa.ui.dialogs.ReportDialog;
import com.maleo.devsa.ui.dialogs.ResetDialog;
import com.maleo.devsa.ui.dialogs.UpdateDialog;
import com.maleo.devsa.util.AppConfig;
import com.unity3d.player.UnityPlayerActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ActivationActivity — App entry point. AppCompatActivity so Material dialogs work.
 *
 * WHY AppCompatActivity HERE IS SAFE:
 * Unity needs plain Activity only for UnityPlayerActivity.
 * This is a separate pre-game screen — no conflict whatsoever.
 *
 * The Activity is TRANSPARENT (Theme.BUSSIDAuth.Transparent in Manifest).
 * All visible UI is inside dialog windows. Activity itself is invisible.
 *
 * FULL FLOW:
 *  Security checks → Already activated? → Update check → OBB present? → Launch game
 *                 ↘ Not activated?   → ActivationDialog → ... → OBB → Launch game
 */
public class ActivationActivity extends AppCompatActivity {

    private PrefManager     prefs;
    private ApiService      api;
    private ObbPatcher      obbPatcher;
    private ObbProtector    obbProtector;

    private final Handler         ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // Active dialog references (so we can dismiss them cleanly)
    private ProgressDialog   progressDialog;
    private ActivationDialog activationDialog;
    private ObbPatchDialog   obbPatchDialog;

    // File picker results
    private Uri pendingObbUri;
    private Uri pendingDlcUri;

    // File pickers — must be registered before Activity starts
    private final ActivityResultLauncher<String> obbPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) { pendingObbUri = uri; handleObbPicked(); }
        });

    private final ActivityResultLauncher<String> dlcPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) { pendingDlcUri = uri; handleDlcPicked(); }
        });

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_empty);

        prefs      = new PrefManager(this);
        api        = new ApiService();
        obbPatcher = new ObbPatcher(this);

        // Security checks (disabled by default — enable in AppConfig)
        if (!SecurityGuard.runAllChecks(this)) {
            new InfoDialog.Builder(this)
                .title("অ্যাক্সেস নিষিদ্ধ")
                .message("এই ডিভাইসে অ্যাপ চালানো যাবে না।")
                .primaryButton(getString(R.string.btn_exit_app), d -> { d.dismiss(); finishAffinity(); })
                .build().show();
            return;
        }

        startAuthFlow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }

    // ─── Auth Flow Entry ─────────────────────────────────────────────────────

    private void startAuthFlow() {
        String key = prefs.getActivationKey();
        if (key != null) {
            // Already activated — check for OBB update, then proceed
            doUpdateCheck(key);
        } else {
            // First time / after reset — show activation dialog
            showActivationDialog();
        }
    }

    // ─── Activation ──────────────────────────────────────────────────────────

    private void showActivationDialog() {
        dismissProgress();
        activationDialog = new ActivationDialog(
            this,
            key -> submitActivationKey(key),
            ()  -> showResetDialog()
        );
        activationDialog.show();
    }

    private void submitActivationKey(String key) {
        dismissActivation();
        showProgress(getString(R.string.progress_checking_key), "ধাপ ১/৩");

        bg.execute(() -> {
            // Step 1 — quick existence check
            String ck = api.checkKey(key);
            if (!ApiService.RESP_VALID.equals(ck)) {
                ui.post(() -> {
                    dismissProgress();
                    showInfoError(getString(R.string.err_key_invalid), true);
                });
                return;
            }

            // Step 2 — full verification
            setProgressStep(getString(R.string.progress_verifying), "ধাপ ২/৩");
            String verify = api.verifyKey(key, prefs.getObbPatchVersion());

            ui.post(() -> {
                dismissProgress();
                handleVerifyResult(key, verify);
            });
        });
    }

    private void handleVerifyResult(String key, String result) {
        switch (result) {
            case ApiService.RESP_ACTIVATED:
                prefs.saveActivationKey(key);
                proceedToObbFlow(key);
                break;

            case ApiService.RESP_AACT:
                // Key already activated — still usable, save and continue
                // But show info so user knows; offer reset option
                prefs.saveActivationKey(key);
                new InfoDialog.Builder(this)
                    .title(getString(R.string.success_already_active_title))
                    .message(getString(R.string.success_already_active_msg))
                    .primaryButton(getString(R.string.btn_ok), d -> {
                        d.dismiss();
                        proceedToObbFlow(key);
                    })
                    .secondaryButton(getString(R.string.btn_go_reset), d -> {
                        d.dismiss();
                        prefs.clearActivationKey();
                        showResetDialog();
                    })
                    .build().show();
                break;

            case ApiService.RESP_NE:
                showInfoError(getString(R.string.err_key_invalid), true);
                break;

            case ApiService.RESP_EXP:
                showInfoError(getString(R.string.err_key_expired), false);
                break;

            case ApiService.RESP_INV:
                showInfoError(getString(R.string.err_key_old_session), true);
                break;

            default:
                showInfoError(getString(R.string.err_server), true);
                break;
        }
    }

    // ─── Reset ───────────────────────────────────────────────────────────────

    private void showResetDialog() {
        dismissProgress();
        new ResetDialog(
            this,
            resetKey -> submitResetKey(resetKey),
            ()       -> showActivationDialog()
        ).show();
    }

    private void submitResetKey(String resetKey) {
        showProgress(getString(R.string.progress_resetting), null);

        bg.execute(() -> {
            String result = api.resetAccount(resetKey);
            ui.post(() -> {
                dismissProgress();
                if ("success".equals(result)) {
                    prefs.clearActivationKey();
                    new InfoDialog.Builder(this)
                        .title(getString(R.string.success_reset_title))
                        .message(getString(R.string.success_reset_msg))
                        .primaryButton(getString(R.string.btn_exit_app), d -> {
                            d.dismiss();
                            finishAffinity();
                        })
                        .build().show();
                } else {
                    // result may contain IP block message from server
                    boolean ipBlocked = result.toLowerCase().contains("ip") || result.toLowerCase().contains("block");
                    String  msg       = ipBlocked ? getString(R.string.err_ip_blocked) : getString(R.string.err_reset_failed);
                    new InfoDialog.Builder(this)
                        .title("রিসেট ব্যর্থ")
                        .message(msg)
                        .primaryButton(getString(R.string.btn_ok), d -> {
                            d.dismiss();
                            showResetDialog();
                        })
                        .build().show();
                }
            });
        });
    }

    // ─── Update Check ────────────────────────────────────────────────────────

    private void doUpdateCheck(String key) {
        showProgress(getString(R.string.progress_checking_update), null);

        bg.execute(() -> {
            ApiService.UpdateInfo update = api.checkUpdate(key);
            ui.post(() -> {
                dismissProgress();
                if (update != null && update.hasUpdate) {
                    showUpdateDialog(key, update.currentVersion);
                } else {
                    // No update or offline — go straight to OBB flow
                    proceedToObbFlow(key);
                }
            });
        });
    }

    private void showUpdateDialog(String key, String version) {
        new UpdateDialog(this, version, () -> {
            startPatchDownload(key);
        }).show();
    }

    private void startPatchDownload(String key) {
        showProgress(getString(R.string.progress_downloading), null);

        bg.execute(() -> {
            ApiService.PatchInfo info = api.getPatchInfo(key, prefs.getObbPatchVersion());
            if (info == null) {
                ui.post(() -> {
                    dismissProgress();
                    showInfoError(getString(R.string.err_download_failed), true);
                });
                return;
            }

            // Save offsets now so ObbProtector can be built
            prefs.saveObbOffsets(info.guidOffset, info.assetOffset);
            buildObbProtector();

            // Delete old OBB before writing new one
            if (obbProtector.getObbFile() != null && obbProtector.getObbFile().exists()) {
                obbProtector.getObbFile().delete();
            }

            boolean ok = api.downloadFile(info.patchUrl, obbProtector.getObbFile(), percent ->
                ui.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing())
                        progressDialog.setDownloadProgress(percent);
                })
            );

            ui.post(() -> {
                dismissProgress();
                if (ok) {
                    prefs.saveObbPatchVersion(info.version);
                    proceedToObbFlow(key);
                } else {
                    showInfoError(getString(R.string.err_download_failed), true);
                }
            });
        });
    }

    // ─── OBB & DLC Flow ──────────────────────────────────────────────────────

    private void proceedToObbFlow(String key) {
        buildObbProtector();

        if (!obbProtector.isObbPresent()) {
            showObbDialog(key);
        } else if (!prefs.isDlcInstalled()) {
            showDlcDialog();
        } else {
            maybeShowReportDialog();
        }
    }

    private void showObbDialog(String key) {
        obbPatchDialog = new ObbPatchDialog(
            this,
            getString(R.string.obb_title),
            getString(R.string.obb_message),
            getString(R.string.obb_select_btn),
            () -> obbPicker.launch("*/*")
        );
        obbPatchDialog.show();
    }

    private void handleObbPicked() {
        if (pendingObbUri == null) return;
        if (obbPatchDialog != null) obbPatchDialog.setDownloadProgress(0);

        bg.execute(() -> obbPatcher.copyObb(pendingObbUri, new ObbPatcher.PatchCallback() {
            @Override public void onProgress(int p) {
                ui.post(() -> { if (obbPatchDialog != null) obbPatchDialog.setDownloadProgress(p); });
            }
            @Override public void onSuccess() {
                ui.post(() -> {
                    if (obbPatchDialog != null) { obbPatchDialog.dismiss(); obbPatchDialog = null; }
                    if (!prefs.isDlcInstalled()) showDlcDialog();
                    else maybeShowReportDialog();
                });
            }
            @Override public void onError(String reason) {
                ui.post(() -> showInfoError("OBB কপি ব্যর্থ: " + reason, true));
            }
        }));
    }

    private void showDlcDialog() {
        new ObbPatchDialog(
            this,
            getString(R.string.dlc_title),
            getString(R.string.dlc_message),
            getString(R.string.dlc_select_btn),
            () -> dlcPicker.launch("application/zip")
        ).show();
    }

    private void handleDlcPicked() {
        if (pendingDlcUri == null) return;
        showProgress(getString(R.string.progress_applying), null);

        bg.execute(() -> obbPatcher.extractDlc(pendingDlcUri, new ObbPatcher.PatchCallback() {
            @Override public void onProgress(int p) {
                ui.post(() -> { if (progressDialog != null) progressDialog.setDownloadProgress(p); });
            }
            @Override public void onSuccess() {
                prefs.setDlcInstalled(true);
                ui.post(() -> { dismissProgress(); maybeShowReportDialog(); });
            }
            @Override public void onError(String reason) {
                ui.post(() -> { dismissProgress(); showInfoError("DLC এক্সট্র্যাক্ট ব্যর্থ: " + reason, true); });
            }
        }));
    }

    // ─── Report Dialog (once per day) ────────────────────────────────────────

    private void maybeShowReportDialog() {
        if (prefs.shouldShowReportDialog(AppConfig.REPORT_DIALOG_INTERVAL_HOURS)) {
            prefs.saveReportShownNow();
            String key = prefs.getActivationKey();
            new ReportDialog(
                this,
                message -> submitReport(key, message),
                ()      -> launchGame()
            ).show();
        } else {
            launchGame();
        }
    }

    private void submitReport(String key, String message) {
        showProgress("রিপোর্ট পাঠানো হচ্ছে…", null);

        bg.execute(() -> {
            boolean ok = api.reportIssue(key, message);
            ui.post(() -> {
                dismissProgress();
                if (ok) {
                    new InfoDialog.Builder(this)
                        .title(getString(R.string.success_report_title))
                        .message(getString(R.string.success_report_msg))
                        .primaryButton(getString(R.string.btn_ok), d -> { d.dismiss(); launchGame(); })
                        .build().show();
                } else {
                    // Report failed silently — still launch game
                    launchGame();
                }
            });
        });
    }

    // ─── Launch Game ─────────────────────────────────────────────────────────

    private void launchGame() {
        startActivity(new Intent(this, UnityPlayerActivity.class));
        finish();
    }

    // ─── ObbProtector Builder ────────────────────────────────────────────────

    private void buildObbProtector() {
        String[] offsets = prefs.getObbOffsets();
        obbProtector = new ObbProtector(this, offsets[0], offsets[1]);
    }

    // ─── Progress Helpers ────────────────────────────────────────────────────

    private void showProgress(String step, String subStep) {
        ui.post(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) {
                progressDialog = new ProgressDialog(this);
                progressDialog.show();
            }
            progressDialog.setStep(step, subStep);
        });
    }

    private void setProgressStep(String step, String subStep) {
        ui.post(() -> {
            if (progressDialog != null && progressDialog.isShowing())
                progressDialog.setStep(step, subStep);
        });
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void dismissActivation() {
        if (activationDialog != null && activationDialog.isShowing()) {
            activationDialog.dismiss();
            activationDialog = null;
        }
    }

    // ─── Error Helper ─────────────────────────────────────────────────────────

    private void showInfoError(String message, boolean canRetry) {
        InfoDialog.Builder b = new InfoDialog.Builder(this)
            .title("সমস্যা হয়েছে")
            .message(message);
        if (canRetry) {
            b.primaryButton(getString(R.string.btn_ok), d -> {
                d.dismiss();
                showActivationDialog();
            });
        } else {
            b.primaryButton(getString(R.string.btn_exit_app), d -> {
                d.dismiss();
                finishAffinity();
            });
        }
        b.build().show();
    }
}
