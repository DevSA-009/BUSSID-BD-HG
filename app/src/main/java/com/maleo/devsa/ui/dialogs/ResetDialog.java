package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;

import com.maleo.bussidbdhg.R;

/**
 * ResetDialog — Separate dedicated dialog for account reset.
 * Has its OWN layout (dialog_reset.xml) — does NOT reuse dialog_activation.xml.
 *
 * BUG FIXES vs previous version:
 *  - Cancel button ID changed from btnResetBack → btnResetCancel (matches layout)
 *  - Cancel button label is "বাতিল" set in strings.xml (was empty before)
 *  - Title in layout is "রিসেট কী দিন" — not repeated "অ্যাকাউন্ট রিসেট করুন"
 */
public class ResetDialog extends BaseDialog {

    public interface OnResetListener  { void onReset(String resetKey); }
    public interface OnCancelListener { void onCancel(); }

    private final OnResetListener  onResetListener;
    private final OnCancelListener onCancelListener;

    public ResetDialog(Context context,
                       OnResetListener onResetListener,
                       OnCancelListener onCancelListener) {
        super(context);
        this.onResetListener  = onResetListener;
        this.onCancelListener = onCancelListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_reset);   // own layout — not shared
        applyDimensions();

        EditText etKey       = findViewById(R.id.etResetKey);
        Button   btnCancel   = findViewById(R.id.btnResetCancel);   // fixed ID
        Button   btnSubmit   = findViewById(R.id.btnResetSubmit);

        // Cancel → go back to activation dialog
        btnCancel.setOnClickListener(v -> {
            if (onCancelListener != null) onCancelListener.onCancel();
        });

        btnSubmit.setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (TextUtils.isEmpty(key)) {
                etKey.setError(getContext().getString(R.string.err_reset_key_empty));
                return;
            }
            if (onResetListener != null) onResetListener.onReset(key);
        });
    }
}
