package com.maleo.devsa.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.maleo.bussidbdhg.R;
import com.maleo.devsa.network.ApiService;
import com.maleo.devsa.security.ObbPatcher;
import com.maleo.devsa.security.ObbProtector;
import com.maleo.devsa.security.SecurityGuard;
import com.maleo.devsa.storage.SecurePrefs;
import com.maleo.devsa.ui.dialogs.ActivationDialog;
import com.maleo.devsa.ui.dialogs.InfoDialog;
import com.maleo.devsa.ui.dialogs.ObbPatchDialog;
import com.maleo.devsa.ui.dialogs.ProgressDialog;
import com.maleo.devsa.ui.dialogs.ReportDialog;
import com.maleo.devsa.ui.dialogs.ResetDialog;
import com.maleo.devsa.ui.dialogs.UpdateDialog;
import com.maleo.devsa.util.AppConfig;
import com.unity3d.player.UnityPlayerActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActivationActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SecurePrefs prefs;
    private ApiService api;
    private ObbPatcher patcher;
    private ObbProtector protector;
    private ExecutorService bg;

    // Active dialogs — all dismissed in onDestroy to prevent WindowLeaked
    private ProgressDialog progressDialog;
    private ActivationDialog activationDialog;
    private ObbPatchDialog fileOpDialog;
    private InfoDialog infoDialog;

    private Uri pendingObbUri;
    private Uri pendingDlcUri;
    private final ActivityResultLauncher<String> dlcPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingDlcUri = uri;
                    handleDlcPicked();
                }
            });
    private final ActivityResultLauncher<String> obbPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingObbUri = uri;
                    handleObbPicked();
                }
            });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_empty);

        bg = Executors.newSingleThreadExecutor();
        prefs = new SecurePrefs(this);
        api = new ApiService();
        patcher = new ObbPatcher(this);
        protector = buildProtector();

        if (!SecurityGuard.runAllChecks(this)) {
            showFatalError(getString(R.string.err_device_not_supported));
            return;
        }
        startAuthFlow();
    }

    @Override
    protected void onDestroy() {
        dismissAllDialogs();
        if (bg != null) {
            bg.shutdownNow();
            bg = null;
        }
        super.onDestroy();
    }

    // ── Auth flow entry ───────────────────────────────────────────────────────

    private void startAuthFlow() {
        String key = prefs.getActivationKey();
        if (key != null) {
            runPeriodicAuthCheck(key);
        } else {
            showActivationDialog();
        }
    }

    // ── Periodic auth check (24h interval) ───────────────────────────────────

    /**
     * Re-validates key with GET /user/ck/:key every AUTH_CHECK_INTERVAL_MS.
     * On failure: delete patch OBB, clearActivationData(), show activation dialog.
     * On success: save timestamp, proceed to update check.
     */
    private void runPeriodicAuthCheck(String key) {
        if (prefs.isIntervalNotPassed(SecurePrefs.TS_AUTH_CHECK, AppConfig.AUTH_CHECK_INTERVAL_MS)) {
            // Interval not passed — skip check
            doUpdateCheck(key, false);
            return;
        }

        showProgress(getString(R.string.progress_verifying_account), getString(R.string.progress_please_wait));
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            String result = api.checkKey(key);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing())
                return;

            act.ui.post(() -> {
                act.dismissProgress();

                if (ApiService.RESP_NO_INTERNET.equals(result)) {
                    // Offline — skip auth check silently, proceed
                    act.doUpdateCheck(key, true);
                    return;
                }

                if (ApiService.RESP_VALID.equals(result)) {
                    // Key still valid
                    prefs.saveTimestampNow(SecurePrefs.TS_AUTH_CHECK);
                    act.doUpdateCheck(key, false);
                } else if (ApiService.RESP_EXP.equals(result)) {
                    // Key revoked/invalid — clear activation data + delete patch OBB
                    act.clearAndRevokeAccess();
                    act.showErrorDialog(getString(R.string.err_key_expired), false);
                } else {
                    // Key revoked/invalid — clear data + delete patch OBB
                    act.clearAndRevokeAccess();
                    act.showErrorDialog(
                            getString(R.string.err_auth_check),
                            true);
                }
            });
        });
    }

    // ── Activation ────────────────────────────────────────────────────────────

    private void showActivationDialog() {
        dismissProgress();
        activationDialog = new ActivationDialog(this,
                this::submitActivationKey,
                this::showResetDialog);
        activationDialog.show();
    }

    private void submitActivationKey(String key) {
        dismissActivation();
        showProgress(getString(R.string.progress_checking_key), getString(R.string.progress_step_1));
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            String ck = api.checkKey(key);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing())
                return;

            if (ApiService.RESP_NO_INTERNET.equals(ck)) {
                act.ui.post(() -> {
                    act.dismissProgress();
                    act.showNoInternetDialog(true);
                });
                return;
            }
            if (!ApiService.RESP_VALID.equals(ck)) {
                act.ui.post(() -> {
                    act.dismissProgress();
                    act.showErrorDialog(getString(R.string.err_key_invalid), true);
                });
                return;
            }

            act.setProgressStep(getString(R.string.progress_verifying), getString(R.string.progress_step_2));
            String verify = api.verifyKey(key);
            ActivationActivity act2 = ref.get();
            if (act2 == null || act2.isFinishing())
                return;
            act2.ui.post(() -> {
                act2.dismissProgress();
                act2.handleVerifyResult(key, verify);
            });
        });
    }

    private void handleVerifyResult(String key, String result) {
        switch (result) {
            case ApiService.RESP_ACTIVATED:
                prefs.saveActivationKey(key);
                prefs.saveTimestampNow(SecurePrefs.TS_AUTH_CHECK);
                proceedToObbFlow();
                break;
            case ApiService.RESP_AACT:
                // Already activated — do NOT save key
                infoDialog = new InfoDialog.Builder(this)
                        .title(getString(R.string.success_already_active_title))
                        .message(getString(R.string.success_already_active_msg))
                        .primaryButton(getString(R.string.btn_ok),
                                d -> {
                                    d.dismiss();
                                    showActivationDialog();
                                })
                        .secondaryButton(getString(R.string.btn_reset),
                                d -> {
                                    d.dismiss();
                                    showResetDialog();
                                })
                        .build();
                infoDialog.show();
                break;
            case ApiService.RESP_NE:
                showErrorDialog(getString(R.string.err_key_invalid), true);
                break;
            case ApiService.RESP_EXP:
                clearAndRevokeAccess();
                showErrorDialog(getString(R.string.err_key_expired), false);
                break;
            case ApiService.RESP_INV:
                showErrorDialog(getString(R.string.err_key_old_session), true);
                break;
            case ApiService.RESP_NO_INTERNET:
                showNoInternetDialog(true);
                break;
            default:
                showErrorDialog(getString(R.string.err_server), true);
                break;
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private void showResetDialog() {
        dismissProgress();
        new ResetDialog(this,
                this::submitResetKey,
                this::showActivationDialog).show();
    }

    private void submitResetKey(String resetKey) {
        showProgress(getString(R.string.progress_resetting), null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            String result = api.resetAccount(resetKey);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing())
                return;
            act.ui.post(() -> {
                act.dismissProgress();
                if (ApiService.RESP_SUCC.equals(result)) {
                    prefs.clearActivationKey();
                    act.infoDialog = new InfoDialog.Builder(act)
                            .title(getString(R.string.success_reset_title))
                            .message(getString(R.string.success_reset_msg))
                            .primaryButton(getString(R.string.btn_exit_app),
                                    d -> {
                                        d.dismiss();
                                        finishAffinity();
                                    })
                            .build();
                    act.infoDialog.show();
                } else if (ApiService.RESP_NO_INTERNET.equals(result)) {
                    act.showNoInternetDialog(false);
                    return;
                }
                clearAndRevokeAccess();
                if (ApiService.RESP_LIMIT.equals(result)) {
                    act.showErrorDialog(getString(R.string.err_ip_blocked), false);
                    return;
                }
                if (ApiService.RESP_EXP.equals(result)) {
                    showErrorDialog(getString(R.string.err_key_expired), false);
                    return;
                }
                if (ApiService.RESP_INV.equals(result)) {
                    showErrorDialog(getString(R.string.err_key_old_session), false);
                    return;
                }
                act.showErrorDialog(getString(R.string.err_reset_failed), false);
            });
        });
    }

    // ── Update check (2.5h interval, skip if no patch OBB) ───────────────────

    private void doUpdateCheck(String key, boolean noInternet) {
        protector = buildProtector();
        if (protector.isPatchObbNeeded()) {
            proceedToObbFlow();
            return;
        }
        if (prefs.isIntervalNotPassed(SecurePrefs.TS_UPDATE_CHECK, AppConfig.UPDATE_CHECK_INTERVAL_MS)) {
            proceedToObbFlow();
            return;
        }
        if (noInternet) {
            proceedToObbFlow();
            return;
        }

        showProgress(getString(R.string.progress_checking_update), null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            ApiService.UpdateInfo info = api.checkUpdate(key);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing())
                return;
            act.ui.post(() -> {
                act.dismissProgress();
                if (info == null) {
                    act.proceedToObbFlow();
                } else if (info.hasUpdate) {
                    act.showUpdateDialog(info);
                } else {
                    prefs.saveTimestampNow(SecurePrefs.TS_UPDATE_CHECK);
                    act.proceedToObbFlow();
                }
            });
        });
    }

    private void showUpdateDialog(ApiService.UpdateInfo info) {
        new UpdateDialog(this, info.version,
                () -> downloadPatchObb(info.patchUrl, info.guidOffset, info.assetOffset, info.version)).show();
    }

    // ── OBB flow ──────────────────────────────────────────────────────────────

    private void proceedToObbFlow() {
        protector = buildProtector();

        if (!patcher.isMainObbPresent()) {
            showMainObbDialog();
            return;
        }
        if (patcher.isDlcPatchNeeded()) {
            showDlcDialog();
            return;
        }
        if (protector.isPatchObbNeeded()) {
            downloadPatchObbFromServer(prefs.getActivationKey());
            return;
        }
        maybeShowReportDialog();
    }

    // ── Main OBB ──────────────────────────────────────────────────────────────

    private void showMainObbDialog() {
        fileOpDialog = new ObbPatchDialog(this,
                getString(R.string.obb_title),
                getString(R.string.obb_message),
                getString(R.string.obb_select_btn),
                () -> obbPicker.launch("*/*"));
        fileOpDialog.show();
    }

    private void handleObbPicked() {
        if (pendingObbUri == null)
            return;
        if (fileOpDialog != null)
            fileOpDialog.showProcessing(getString(R.string.obb_processing), getString(R.string.progress_please_wait));

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> patcher.copyMainObb(pendingObbUri, new ObbPatcher.PatchCallback() {
            @Override
            public void onProgress(int p) {
                ActivationActivity a = ref.get();
                if (a == null)
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null)
                        a.fileOpDialog.updateProgress(p, p + getString(R.string.progress_percent_done));
                });
            }

            @Override
            public void onSuccess() {
                ActivationActivity a = ref.get();
                if (a == null || a.isFinishing())
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null) {
                        a.fileOpDialog.dismiss();
                        a.fileOpDialog = null;
                    }
                    if (a.patcher.isDlcPatchNeeded())
                        a.showDlcDialog();
                    else if (a.protector.isPatchObbNeeded())
                        a.downloadPatchObbFromServer(a.prefs.getActivationKey());
                    else
                        a.maybeShowReportDialog();
                });
            }

            @Override
            public void onError(String r) {
                ActivationActivity a = ref.get();
                if (a == null || a.isFinishing())
                    return;
                a.ui.post(() -> a.showErrorDialog(getString(R.string.obb_error), true));
            }
        }));
    }

    // ── DLC ───────────────────────────────────────────────────────────────────

    private void showDlcDialog() {
        fileOpDialog = new ObbPatchDialog(this,
                getString(R.string.dlc_title),
                getString(R.string.dlc_message),
                getString(R.string.dlc_select_btn),
                () -> dlcPicker.launch("application/zip"));
        fileOpDialog.show();
    }

    private void handleDlcPicked() {
        if (pendingDlcUri == null)
            return;
        if (fileOpDialog != null)
            fileOpDialog.showProcessing(getString(R.string.dlc_processing), getString(R.string.progress_please_wait));

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> patcher.extractDlc(pendingDlcUri, new ObbPatcher.PatchCallback() {
            @Override
            public void onProgress(int p) {
                ActivationActivity a = ref.get();
                if (a == null)
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null)
                        a.fileOpDialog.updateProgress(p, p + getString(R.string.progress_percent_done));
                });
            }

            @Override
            public void onSuccess() {
                ActivationActivity a = ref.get();
                if (a == null || a.isFinishing())
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null) {
                        a.fileOpDialog.dismiss();
                        a.fileOpDialog = null;
                    }
                    if (a.protector.isPatchObbNeeded())
                        a.downloadPatchObbFromServer(a.prefs.getActivationKey());
                    else
                        a.maybeShowReportDialog();
                });
            }

            @Override
            public void onError(String r) {
                ActivationActivity a = ref.get();
                if (a == null || a.isFinishing())
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null) {
                        a.fileOpDialog.dismiss();
                        a.fileOpDialog = null;
                    }
                    a.showErrorDialog(getString(R.string.dlc_error), true);
                });
            }
        }));
    }

    // ── Patch OBB download ────────────────────────────────────────────────────

    private void downloadPatchObbFromServer(String key) {
        // Processing-only dialog — no select button
        fileOpDialog = new ObbPatchDialog(this, getString(R.string.patch_title), "", null, null);
        fileOpDialog.show();
        fileOpDialog.showProcessing(getString(R.string.progress_fetching_info), getString(R.string.progress_please_wait));

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            ActivationActivity a = ref.get();
            if (a == null || a.isFinishing())
                return;
            ApiService.PatchInfo info = api.getPatchInfo(key);
            if (info == null) {
                a.ui.post(() -> {
                    if (a.fileOpDialog != null) {
                        a.fileOpDialog.dismiss();
                        a.fileOpDialog = null;
                    }
                    a.showPatchFailedDialog();
                });
                return;
            }
            a.downloadPatchObb(info.patchUrl, info.guidOffset, info.assetOffset, info.version);
        });
    }

    private void downloadPatchObb(String patchUrl,
                                  String guidOffset, String assetOffset, String version) {
        prefs.saveObbOffsets(guidOffset, assetOffset);
        protector = buildProtector();
        protector.deletePatchObb();

        File tempFile = protector.getTempDownloadFile();
        File hiddenFile = protector.getHiddenFile();

        // Update existing dialog or create new one
        ui.post(() -> {
            if (fileOpDialog == null || !fileOpDialog.isShowing()) {
                fileOpDialog = new ObbPatchDialog(this, getString(R.string.patch_title), "", null, null);
                fileOpDialog.show();
            }
            fileOpDialog.showProcessing(getString(R.string.progress_finding), getString(R.string.progress_please_wait));
        });

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            boolean ok = api.downloadFile(patchUrl, tempFile, hiddenFile, percent -> {
                ActivationActivity a = ref.get();
                if (a == null)
                    return;
                a.ui.post(() -> {
                    if (a.fileOpDialog != null)
                        a.fileOpDialog.updateProgress(percent, percent + getString(R.string.progress_percent_done));
                });
            });

            ActivationActivity a = ref.get();
            if (a == null || a.isFinishing())
                return;
            a.ui.post(() -> {
                if (a.fileOpDialog != null) {
                    a.fileOpDialog.dismiss();
                    a.fileOpDialog = null;
                }
                if (ok) {
                    // Update offsets, version, timestamp — all from server response
                    prefs.saveObbOffsets(guidOffset, assetOffset);
                    prefs.saveObbPatchVersion(version);
                    prefs.saveTimestampNow(SecurePrefs.TS_UPDATE_CHECK);
                    // Rebuild protector with new offsets so damage/repair uses correct positions
                    protector = buildProtector();
                    a.maybeShowReportDialog();
                } else {
                    a.showPatchFailedDialog();
                }
            });
        });
    }

    // ── Report dialog (24h interval) ──────────────────────────────────────────

    private void maybeShowReportDialog() {
        if (!prefs.hasLaunchedBefore()) {
            launchGame();
            return;
        }
        if (prefs.isIntervalNotPassed(SecurePrefs.TS_REPORT_SHOWN, AppConfig.REPORT_DIALOG_INTERVAL_MS)) {
            launchGame();
            return;
        }

        // No internet — skip report dialog silently
        if (!isNetworkAvailable()) {
            launchGame();
            return;
        }

        prefs.saveTimestampNow(SecurePrefs.TS_REPORT_SHOWN);
        new ReportDialog(this,
                msg -> submitReport(prefs.getActivationKey(), msg),
                this::launchGame).show();
    }

    private void submitReport(String key, String message) {
        showProgress(getString(R.string.progress_report_sending), null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            api.reportIssue(key, message);
            ActivationActivity a = ref.get();
            if (a == null || a.isFinishing())
                return;
            a.ui.post(() -> {
                a.dismissProgress();
                a.launchGame();
            });
        });
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private void launchGame() {
        prefs.markFirstLaunched();
        startActivity(new Intent(this, UnityPlayerActivity.class));
        finish();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isNetworkAvailable() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
}

    /**
     * Clears all activation-related data and deletes patch OBB.
     * Keeps: DLC reference file, main OBB, first-launched flag.
     */
    private void clearAndRevokeAccess() {
        protector = buildProtector();
        protector.deletePatchObb();
        prefs.clearActivationData();
    }

    private ObbProtector buildProtector() {
        String[] off = prefs.getObbOffsets();
        return new ObbProtector(this, off[0], off[1]);
    }

    private void showProgress(String step, String sub) {
        ui.post(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) {
                progressDialog = new ProgressDialog(this);
                progressDialog.show();
            }
            progressDialog.setStep(step, sub != null ? sub : getString(R.string.progress_please_wait));
        });
    }

    private void setProgressStep(String step, String sub) {
        ui.post(() -> {
            if (progressDialog != null && progressDialog.isShowing())
                progressDialog.setStep(step, sub);
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

    private void dismissAllDialogs() {
        dismissProgress();
        dismissActivation();
        if (fileOpDialog != null && fileOpDialog.isShowing()) {
            fileOpDialog.dismiss();
            fileOpDialog = null;
        }
        if (infoDialog != null && infoDialog.isShowing()) {
            infoDialog.dismiss();
            infoDialog = null;
        }
    }

    private void showNoInternetDialog(boolean canRetry) {
        infoDialog = new InfoDialog.Builder(this)
                .title(getString(R.string.err_connection_failed)).message(getString(R.string.err_no_internet))
                .primaryButton(canRetry ? getString(R.string.btn_ok) : getString(R.string.btn_exit_app),
                        d -> {
                            d.dismiss();
                            if (canRetry)
                                showActivationDialog();
                            else
                                finishAffinity();
                        })
                .build();
        infoDialog.show();
    }

    private void showErrorDialog(String message, boolean canRetry) {
        infoDialog = new InfoDialog.Builder(this)
                .title(getString(R.string.err_generic)).message(message)
                .primaryButton(canRetry ? getString(R.string.btn_ok) : getString(R.string.btn_exit_app),
                        d -> {
                            d.dismiss();
                            if (canRetry)
                                showActivationDialog();
                            else
                                finishAffinity();
                        })
                .build();
        infoDialog.show();
    }

    private void showPatchFailedDialog() {
        infoDialog = new InfoDialog.Builder(this)
                .title(getString(R.string.patch_search_failed))
                .message(getString(R.string.patch_failed_msg))
                .primaryButton(getString(R.string.btn_retry),
                        d -> {
                            d.dismiss();
                            downloadPatchObbFromServer(prefs.getActivationKey());
                        })
                .secondaryButton(getString(R.string.btn_exit_app),
                        d -> {
                            d.dismiss();
                            finishAffinity();
                        })
                .build();
        infoDialog.show();
    }

    private void showFatalError(String msg) {
        infoDialog = new InfoDialog.Builder(this).title(getString(R.string.err_access_denied)).message(msg)
                .primaryButton(getString(R.string.btn_exit_app), d -> {
                    d.dismiss();
                    finishAffinity();
                })
                .build();
        infoDialog.show();
    }
}
