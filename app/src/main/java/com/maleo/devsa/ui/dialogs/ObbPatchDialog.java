package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * ObbPatchDialog — Unified dialog for ALL file operations:
 * Main OBB copy, DLC extract, Patch OBB download, Update download.
 * <p>
 * TWO MODES:
 * <p>
 * MODE_SELECT (default):
 * Shows: title + divider + message + select button
 * User taps button → triggers file picker
 * After picker → call showProcessing() to switch mode
 * <p>
 * MODE_PROCESSING (after file picked OR for server downloads):
 * Shows: spinner + main label (bold blue) + sub label (small white) + progress bar
 * Select button is GONE completely
 * Use showProcessing(step, sub) to enter this mode
 * Use updateProgress(percent) to update bar + sub label
 */
public class ObbPatchDialog extends BaseDialog {

    public interface OnSelectListener {
        void onSelect();
    }

    private LinearLayout layoutIdle;       // title + message + button
    private LinearLayout layoutProcessing; // spinner + two labels + progress bar
    private TextView tvPatchStep;
    private TextView tvPatchSubStep;
    private ProgressBar progressBar;

    // ── Config ────────────────────────────────────────────────────────────────
    private final String title;
    private final String message;
    private final String btnText;
    private final OnSelectListener listener;

    /**
     * @param title    Dialog title
     * @param message  Instruction message (shown in idle mode)
     * @param btnText  Select button label (pass null or "" for processing-only dialogs)
     * @param listener File picker trigger (pass null for processing-only dialogs)
     */
    public ObbPatchDialog(Context context, String title, String message,
                          String btnText, OnSelectListener listener) {
        super(context);
        this.title = title;
        this.message = message;
        this.btnText = btnText;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_obb_patch);
        applyDimensions();

        TextView tvTitle = findViewById(R.id.tvObbTitle);
        TextView tvMessage = findViewById(R.id.tvObbMessage);
        // ── View references ───────────────────────────────────────────────────────
        Button btnSelect = findViewById(R.id.btnObbSelect);
        layoutIdle = findViewById(R.id.layoutIdle);
        layoutProcessing = findViewById(R.id.layoutProcessing);
        tvPatchStep = findViewById(R.id.tvPatchStep);
        tvPatchSubStep = findViewById(R.id.tvPatchSubStep);
        progressBar = findViewById(R.id.progressPatch);

        tvTitle.setText(title);
        tvMessage.setText(message);

        if (btnText != null && !btnText.isEmpty()) {
            btnSelect.setText(btnText);
            btnSelect.setOnClickListener(v -> {
                if (listener != null) listener.onSelect();
            });
        } else {
            // No select button needed — switch to processing immediately
            layoutIdle.setVisibility(View.GONE);
            layoutProcessing.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Switch to processing mode.
     * Hides title/message/button. Shows spinner + two labels.
     *
     * @param stepText Main label — e.g. "ডাউনলোড হচ্ছে…"
     * @param subText  Sub label  — e.g. "অনুগ্রহ করে অপেক্ষা করুন"
     */
    public void showProcessing(String stepText, String subText) {
        if (layoutIdle == null) return;
        layoutIdle.setVisibility(View.GONE);
        layoutProcessing.setVisibility(View.VISIBLE);
        tvPatchStep.setText(stepText);
        tvPatchSubStep.setText(subText != null ? subText : "");
        progressBar.setVisibility(View.GONE);
        progressBar.setProgress(0);
    }

    /**
     * Update labels and progress bar during file operation.
     *
     * @param percent 0-100, pass -1 to hide bar
     * @param subText Updated sub label (e.g. "৪৫% সম্পন্ন")
     */
    public void updateProgress(int percent, String subText) {
        if (tvPatchSubStep == null) return;
        if (subText != null) tvPatchSubStep.setText(subText);
        if (percent >= 0) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(percent);
        } else {
            progressBar.setVisibility(View.GONE);
        }
    }
}
