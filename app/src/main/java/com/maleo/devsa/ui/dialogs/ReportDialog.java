package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;

import com.maleo.bussidbdhg.R;

/** Bug report dialog — shown once per day. Cancel launches game without sending. */
public class ReportDialog extends BaseDialog {

    public interface OnReportListener  { void onReport(String message); }
    public interface OnCancelListener  { void onCancel(); }

    private final OnReportListener onReport;
    private final OnCancelListener onCancel;

    public ReportDialog(Context context, OnReportListener onReport, OnCancelListener onCancel) {
        super(context);
        this.onReport = onReport;
        this.onCancel = onCancel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_report);
        applyDimensions();

        EditText etMsg     = findViewById(R.id.etReportMessage);
        Button   btnCancel = findViewById(R.id.btnReportCancel);
        Button   btnSend   = findViewById(R.id.btnReportSend);

        btnCancel.setOnClickListener(v -> { if (onCancel != null) onCancel.onCancel(); });

        btnSend.setOnClickListener(v -> {
            String msg = etMsg.getText().toString().trim();
            if (TextUtils.isEmpty(msg) || msg.length() < 10) {
                etMsg.setError(getContext().getString(R.string.err_report_short));
                return;
            }
            if (onReport != null) onReport.onReport(msg);
        });
    }
}
