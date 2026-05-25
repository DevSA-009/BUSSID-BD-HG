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

/**
 * ActivationActivity — App entry point. AppCompatActivity for Material dialogs.
 * Fullscreen dark background (#131313). All UI via dialogs centered on screen.
 * <p>
 * MEMORY LEAK PREVENTION:
 * - WeakReference used in all background callbacks
 * - All dialogs dismissed in onDestroy
 * - ExecutorService.shutdownNow() in onDestroy
 * - Handler holds no static reference to Activity
 */
public class ActivationActivity extends AppCompatActivity {

    private SecurePrefs prefs;
    private ApiService api;
    private ObbPatcher patcher;
    private ObbProtector protector;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService bg;

    // Active dialogs — tracked for dismiss in onDestroy
    private ProgressDialog progressDialog;
    private ActivationDialog activationDialog;
    private ObbPatchDialog obbDialog;
    private InfoDialog infoDialog;

    private Uri pendingObbUri;
    private Uri pendingDlcUri;

    private final ActivityResultLauncher<String> obbPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingObbUri = uri;
                    handleObbPicked();
                }
            });

    private final ActivityResultLauncher<String> dlcPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingDlcUri = uri;
                    handleDlcPicked();
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
            showFatalError("এই ডিভাইসে অ্যাপ চালানো যাবে না।");
            return;
        }
        startAuthFlow();
    }

    @Override
    protected void onDestroy() {
        // Dismiss all dialogs to prevent WindowLeaked crash
        dismissAllDialogs();
        // Cancel running tasks — prevent callbacks on dead Activity
        if (bg != null) {
            bg.shutdownNow();
            bg = null;
        }
        super.onDestroy();
    }

    // ── Auth flow ─────────────────────────────────────────────────────────────

    private void startAuthFlow() {
        String key = prefs.getActivationKey();
        if (key != null) {
            doUpdateCheck(key);
        } else {
            showActivationDialog();
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────

    private void showActivationDialog() {
        dismissProgress();
        activationDialog = new ActivationDialog(this,
                key -> submitActivationKey(key),
                () -> showResetDialog());
        activationDialog.show();
    }

    private void submitActivationKey(String key) {
        dismissActivation();
        showProgress(getString(R.string.progress_checking_key), "ধাপ ১/৩");
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            String ck = api.checkKey(key);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;

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

            act.setProgressStep(getString(R.string.progress_verifying), "ধাপ ২/৩");
            String verify = api.verifyKey(act, key, prefs.getObbPatchVersion());

            // ✅ সমাধান: 'act' পরিবর্তন না করে নতুন 'currentAct' ভ্যারিয়েবল নিন
            ActivationActivity currentAct = ref.get();
            if (currentAct == null || currentAct.isFinishing()) return;

            // যেহেতু currentAct এর মান নিচে আর পরিবর্তন হবে না, তাই এটি এখন effectively final
            currentAct.ui.post(() -> {
                currentAct.dismissProgress();
                currentAct.handleVerifyResult(key, verify);
            });
        });
    }

    private void handleVerifyResult(String key, String result) {
        switch (result) {
            case ApiService.RESP_ACTIVATED:
                prefs.saveActivationKey(key);
                proceedToObbFlow();
                break;

            case ApiService.RESP_AACT:
                // Already activated — do NOT save key
                infoDialog = new InfoDialog.Builder(this)
                        .title(getString(R.string.success_already_active_title))
                        .message(getString(R.string.success_already_active_msg))
                        .primaryButton(getString(R.string.btn_ok), d -> {
                            d.dismiss();
                            showActivationDialog();
                        })
                        .secondaryButton(getString(R.string.btn_go_reset), d -> {
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
                resetKey -> submitResetKey(resetKey),
                () -> showActivationDialog()
        ).show();
    }

    private void submitResetKey(String resetKey) {
        showProgress(getString(R.string.progress_resetting), null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            String result = api.resetAccount(resetKey);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;
            act.ui.post(() -> {
                act.dismissProgress();
                if ("success".equals(result)) {
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
                } else {
                    boolean ip = result.toLowerCase().contains("ip") || result.toLowerCase().contains("block");
                    act.showErrorDialog(ip ? getString(R.string.err_ip_blocked)
                            : getString(R.string.err_reset_failed), false);
                }
            });
        });
    }

    // ── Update check ──────────────────────────────────────────────────────────

    private void doUpdateCheck(String key) {
        protector = buildProtector();

        // Patch OBB not present → skip update check, handle in OBB flow
        if (!protector.isPatchObbPresent()) {
            proceedToObbFlow();
            return;
        }
        // Interval not passed → skip
        if (!prefs.isIntervalPassed(SecurePrefs.TS_UPDATE_CHECK, AppConfig.UPDATE_CHECK_INTERVAL_MS)) {
            proceedToObbFlow();
            return;
        }

        showProgress(getString(R.string.progress_checking_update), null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);

        bg.execute(() -> {
            ApiService.UpdateInfo info = api.checkUpdate(key);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;
            act.ui.post(() -> {
                act.dismissProgress();
                if (info == null) {
                    act.proceedToObbFlow();
                } else if (info.hasUpdate) {
                    act.showUpdateDialog(key, info);
                } else {
                    prefs.saveTimestampNow(SecurePrefs.TS_UPDATE_CHECK);
                    act.proceedToObbFlow();
                }
            });
        });
    }

    private void showUpdateDialog(String key, ApiService.UpdateInfo info) {
        new UpdateDialog(this, info.version, () ->
                downloadPatchObb(key, info.patchUrl, info.guidOffset, info.assetOffset, info.version)
        ).show();
    }

    // ── OBB flow ──────────────────────────────────────────────────────────────

    private void proceedToObbFlow() {
        protector = buildProtector();

        if (!patcher.isMainObbPresent()) {
            showMainObbDialog();
            return;
        }
        if (!patcher.isDlcPresent()) {
            showDlcDialog();
            return;
        }
        if (!protector.isPatchObbPresent()) {
            downloadPatchObbFromServer(prefs.getActivationKey());
            return;
        }
        maybeShowReportDialog();
    }

    // ── Main OBB ──────────────────────────────────────────────────────────────

    private void showMainObbDialog() {
        obbDialog = new ObbPatchDialog(this,
                getString(R.string.obb_title),
                getString(R.string.obb_message),
                getString(R.string.obb_select_btn),
                () -> obbPicker.launch("*/*"));
        obbDialog.show();
    }

    private void handleObbPicked() {
        if (pendingObbUri == null) return;
        if (obbDialog != null)
            obbDialog.showProcessing("Main OBB কপি হচ্ছে…", "অনুগ্রহ করে অপেক্ষা করুন");

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> patcher.copyMainObb(pendingObbUri, new ObbPatcher.PatchCallback() {
            @Override
            public void onProgress(int p) {
                ActivationActivity act = ref.get();
                if (act == null) return;
                act.ui.post(() -> {
                    if (act.obbDialog != null)
                        act.obbDialog.updateProcessingText("Main OBB কপি হচ্ছে…", p + "% সম্পন্ন");
                });
            }

            @Override
            public void onSuccess() {
                ActivationActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                act.ui.post(() -> {
                    if (act.obbDialog != null) {
                        act.obbDialog.dismiss();
                        act.obbDialog = null;
                    }
                    if (!act.patcher.isDlcPresent()) act.showDlcDialog();
                    else if (!act.protector.isPatchObbPresent())
                        act.downloadPatchObbFromServer(act.prefs.getActivationKey());
                    else act.maybeShowReportDialog();
                });
            }

            @Override
            public void onError(String r) {
                ActivationActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                act.ui.post(() -> act.showErrorDialog("Main OBB কপি ব্যর্থ হয়েছে", true));
            }
        }));
    }

    // ── DLC ───────────────────────────────────────────────────────────────────

    private void showDlcDialog() {
        obbDialog = new ObbPatchDialog(this,
                getString(R.string.dlc_title),
                getString(R.string.dlc_message),
                getString(R.string.dlc_select_btn),
                () -> dlcPicker.launch("application/zip"));
        obbDialog.show();
    }

    private void handleDlcPicked() {
        if (pendingDlcUri == null) return;
        if (obbDialog != null)
            obbDialog.showProcessing("DLC এক্সট্র্যাক্ট হচ্ছে…", "অনুগ্রহ করে অপেক্ষা করুন");

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> patcher.extractDlc(pendingDlcUri, new ObbPatcher.PatchCallback() {
            @Override
            public void onProgress(int p) {
                ActivationActivity act = ref.get();
                if (act == null) return;
                act.ui.post(() -> {
                    if (act.obbDialog != null)
                        act.obbDialog.updateProcessingText("DLC এক্সট্র্যাক্ট হচ্ছে…", p + "% সম্পন্ন");
                });
            }

            @Override
            public void onSuccess() {
                ActivationActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                patcher.markDlcInstalled();
                act.ui.post(() -> {
                    if (act.obbDialog != null) {
                        act.obbDialog.dismiss();
                        act.obbDialog = null;
                    }
                    if (!act.protector.isPatchObbPresent())
                        act.downloadPatchObbFromServer(act.prefs.getActivationKey());
                    else act.maybeShowReportDialog();
                });
            }

            @Override
            public void onError(String r) {
                ActivationActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                act.ui.post(() -> {
                    if (act.obbDialog != null) {
                        act.obbDialog.dismiss();
                        act.obbDialog = null;
                    }
                    act.showErrorDialog("DLC এক্সট্র্যাক্ট ব্যর্থ হয়েছে", true);
                });
            }
        }));
    }

    // ── Patch OBB download ────────────────────────────────────────────────────

    private void downloadPatchObbFromServer(String key) {
        // Show ObbPatchDialog style — spinner + two labels, no button
        obbDialog = new ObbPatchDialog(this,
                "প্যাচ OBB ডাউনলোড", "",
                "", null); // btnText empty, no select button shown (processing starts immediately)
        obbDialog.show();
        obbDialog.showProcessing("সার্ভার থেকে ডাউনলোড হচ্ছে…", "অনুগ্রহ করে অপেক্ষা করুন");

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;
            ApiService.PatchInfo info = api.getPatchInfo(act, key, prefs.getObbPatchVersion());
            if (info == null) {
                act.ui.post(() -> {
                    if (act.obbDialog != null) {
                        act.obbDialog.dismiss();
                        act.obbDialog = null;
                    }
                    act.showPatchFailedDialog();
                });
                return;
            }
            act.downloadPatchObb(key, info.patchUrl, info.guidOffset, info.assetOffset, info.version);
        });
    }

    private void downloadPatchObb(String key, String patchUrl,
                                  String guidOffset, String assetOffset, String version) {
        prefs.saveObbOffsets(guidOffset, assetOffset);
        protector = buildProtector();
        protector.deletePatchObb();

        File tempFile = protector.getTempDownloadFile();
        File hiddenFile = protector.getHiddenFile();

        // Update processing dialog if not already shown
        ui.post(() -> {
            if (obbDialog == null || !obbDialog.isShowing()) {
                obbDialog = new ObbPatchDialog(this, "প্যাচ OBB ডাউনলোড", "", "", null);
                obbDialog.show();
            }
            obbDialog.showProcessing("ডাউনলোড হচ্ছে…", "অনুগ্রহ করে অপেক্ষা করুন");
        });

        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            boolean ok = api.downloadFile(patchUrl, tempFile, hiddenFile, percent -> {
                ActivationActivity act = ref.get();
                if (act == null) return;
                act.ui.post(() -> {
                    if (act.obbDialog != null)
                        act.obbDialog.updateProcessingText("ডাউনলোড হচ্ছে…", percent + "% সম্পন্ন");
                });
            });

            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;
            act.ui.post(() -> {
                if (act.obbDialog != null) {
                    act.obbDialog.dismiss();
                    act.obbDialog = null;
                }
                if (ok) {
                    prefs.saveObbPatchVersion(version);
                    prefs.saveTimestampNow(SecurePrefs.TS_UPDATE_CHECK);
                    act.maybeShowReportDialog();
                } else {
                    act.showPatchFailedDialog();
                }
            });
        });
    }

    // ── Report dialog ─────────────────────────────────────────────────────────

    private void maybeShowReportDialog() {
        if (!prefs.hasLaunchedBefore()) {
            launchGame();
            return;
        }
        if (!prefs.isIntervalPassed(SecurePrefs.TS_REPORT_SHOWN, AppConfig.REPORT_DIALOG_INTERVAL_MS)) {
            launchGame();
            return;
        }
        prefs.saveTimestampNow(SecurePrefs.TS_REPORT_SHOWN);
        String key = prefs.getActivationKey();
        new ReportDialog(this,
                message -> submitReport(key, message),
                () -> launchGame()
        ).show();
    }

    private void submitReport(String key, String message) {
        showProgress("রিপোর্ট পাঠানো হচ্ছে…", null);
        WeakReference<ActivationActivity> ref = new WeakReference<>(this);
        bg.execute(() -> {
            api.reportIssue(key, message);
            ActivationActivity act = ref.get();
            if (act == null || act.isFinishing()) return;
            act.ui.post(() -> {
                act.dismissProgress();
                act.launchGame();
            });
        });
    }

    // ── Launch game ───────────────────────────────────────────────────────────

    private void launchGame() {
        prefs.markFirstLaunched();
        startActivity(new Intent(this, UnityPlayerActivity.class));
        finish();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
            progressDialog.setStep(step, sub != null ? sub : "অনুগ্রহ করে অপেক্ষা করুন");
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
        if (obbDialog != null && obbDialog.isShowing()) {
            obbDialog.dismiss();
            obbDialog = null;
        }
        if (infoDialog != null && infoDialog.isShowing()) {
            infoDialog.dismiss();
            infoDialog = null;
        }
    }

    private void showNoInternetDialog(boolean canRetry) {
        infoDialog = new InfoDialog.Builder(this)
                .title("ইন্টারনেট নেই")
                .message(getString(R.string.err_no_internet))
                .primaryButton(canRetry ? getString(R.string.btn_ok) : getString(R.string.btn_exit_app),
                        d -> {
                            d.dismiss();
                            if (canRetry) showActivationDialog();
                            else finishAffinity();
                        })
                .build();
        infoDialog.show();
    }

    private void showErrorDialog(String message, boolean canRetry) {
        infoDialog = new InfoDialog.Builder(this)
                .title("সমস্যা হয়েছে").message(message)
                .primaryButton(canRetry ? getString(R.string.btn_ok) : getString(R.string.btn_exit_app),
                        d -> {
                            d.dismiss();
                            if (canRetry) showActivationDialog();
                            else finishAffinity();
                        })
                .build();
        infoDialog.show();
    }

    private void showPatchFailedDialog() {
        infoDialog = new InfoDialog.Builder(this)
                .title("প্যাচিং ব্যর্থ হয়েছে")
                .message("সার্ভার থেকে প্যাচ ডাউনলোড করা যায়নি। ইন্টারনেট সংযোগ চেক করুন এবং আবার চেষ্টা করুন।")
                .primaryButton(getString(R.string.btn_retry), d -> {
                    d.dismiss();
                    downloadPatchObbFromServer(prefs.getActivationKey());
                })
                .secondaryButton(getString(R.string.btn_exit_app), d -> {
                    d.dismiss();
                    finishAffinity();
                })
                .build();
        infoDialog.show();
    }

    private void showFatalError(String message) {
        infoDialog = new InfoDialog.Builder(this)
                .title("অ্যাক্সেস নিষিদ্ধ").message(message)
                .primaryButton(getString(R.string.btn_exit_app), d -> {
                    d.dismiss();
                    finishAffinity();
                })
                .build();
        infoDialog.show();
    }
}
