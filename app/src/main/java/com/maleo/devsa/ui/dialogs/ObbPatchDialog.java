package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * ObbPatchDialog — Used for BOTH OBB and DLC file selection.
 * Title, message, button text injected via constructor.
 */
public class ObbPatchDialog extends BaseDialog {

    public interface OnSelectListener { void onSelect(); }

    private final String           title;
    private final String           message;
    private final String           btnText;
    private final OnSelectListener listener;

    private TextView    tvPercent;
    private ProgressBar progressBar;
    private Button      btnSelect;

    public ObbPatchDialog(Context context, String title, String message,
                          String btnText, OnSelectListener listener) {
        super(context);
        this.title    = title;
        this.message  = message;
        this.btnText  = btnText;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_obb_patch);
        applyDimensions();

        ((TextView) findViewById(R.id.tvObbTitle)).setText(title);
        ((TextView) findViewById(R.id.tvObbMessage)).setText(message);

        btnSelect   = findViewById(R.id.btnObbSelect);
        tvPercent   = findViewById(R.id.tvObbDownloadPercent);
        progressBar = findViewById(R.id.progressObbDownload);

        btnSelect.setText(btnText);
        btnSelect.setOnClickListener(v -> { if (listener != null) listener.onSelect(); });
    }

    public void setDownloadProgress(int percent) {
        if (tvPercent == null) return;
        btnSelect.setEnabled(false);
        tvPercent.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        tvPercent.setText("ডাউনলোড হচ্ছে… " + percent + "%");
        progressBar.setProgress(percent);
    }
}
