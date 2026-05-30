package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * Step-based progress dialog with optional download progress bar.
 */
public class ProgressDialog extends BaseDialog {

    private TextView tvStep;
    private TextView tvSubStep;
    private TextView tvPercent;
    private ProgressBar barDownload;

    public ProgressDialog(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_progress);
        applyDimensions();

        tvStep = findViewById(R.id.tvProgressStep);
        tvSubStep = findViewById(R.id.tvProgressSubStep);
        tvPercent = findViewById(R.id.tvDownloadPercent);
        barDownload = findViewById(R.id.progressBarDownload);
    }

    public void setStep(String stepText, String subStep) {
        if (tvStep == null) return;
        tvStep.setText(stepText);
        tvSubStep.setText(subStep != null ? subStep : "");
    }

}
