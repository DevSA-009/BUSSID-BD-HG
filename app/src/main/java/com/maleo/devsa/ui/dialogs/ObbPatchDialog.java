package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * ObbPatchDialog — File selection dialog for Main OBB and DLC.
 * <p>
 * States:
 * IDLE       : Select button visible, processing layout GONE
 * PROCESSING : Select button GONE, spinner + two text labels shown
 * <p>
 * Used for: Main OBB copy, DLC extract.
 * NOT used for patch OBB download — that uses ProgressDialog directly.
 */
public class ObbPatchDialog extends BaseDialog {

    public interface OnSelectListener {
        void onSelect();
    }

    private final String title;
    private final String message;
    private final String btnText;
    private final OnSelectListener listener;

    private Button btnSelect;
    private LinearLayout layoutProcessing;
    private TextView tvPatchStep;
    private TextView tvPatchSubStep;

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

        ((TextView) findViewById(R.id.tvObbTitle)).setText(title);
        ((TextView) findViewById(R.id.tvObbMessage)).setText(message);

        btnSelect = findViewById(R.id.btnObbSelect);
        layoutProcessing = findViewById(R.id.layoutProcessing);
        tvPatchStep = findViewById(R.id.tvPatchStep);
        tvPatchSubStep = findViewById(R.id.tvPatchSubStep);

        btnSelect.setText(btnText);
        btnSelect.setOnClickListener(v -> {
            if (listener != null) listener.onSelect();
        });
    }

    /**
     * Switch to processing state.
     * Hides select button completely.
     * Shows spinner + two labels.
     *
     * @param stepText Main label — e.g. "ফাইল কপি হচ্ছে…"
     * @param subText  Sub label  — e.g. "অনুগ্রহ করে অপেক্ষা করুন"
     */
    public void showProcessing(String stepText, String subText) {
        if (btnSelect == null) return;
        btnSelect.setVisibility(View.GONE);
        layoutProcessing.setVisibility(View.VISIBLE);
        tvPatchStep.setText(stepText);
        tvPatchSubStep.setText(subText != null ? subText : "");
    }

    /**
     * Update labels while processing (e.g. progress %).
     */
    public void updateProcessingText(String stepText, String subText) {
        if (tvPatchStep == null) return;
        tvPatchStep.setText(stepText);
        if (subText != null) tvPatchSubStep.setText(subText);
    }
}
