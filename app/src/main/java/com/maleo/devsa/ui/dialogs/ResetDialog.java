package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;

import com.maleo.bussidbdhg.R;

/**
 * Reset dialog — single resetKey field, cancel + submit buttons.
 */
public class ResetDialog extends BaseDialog {

    public interface OnResetListener {
        void onReset(String resetKey);
    }

    public interface OnCancelListener {
        void onCancel();
    }

    private final OnResetListener onResetListener;
    private final OnCancelListener onCancelListener;

    public ResetDialog(Context context, OnResetListener onReset, OnCancelListener onCancel) {
        super(context);
        this.onResetListener = onReset;
        this.onCancelListener = onCancel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_reset);
        applyDimensions();

        EditText etKey = findViewById(R.id.etResetKey);
        Button btnCancel = findViewById(R.id.btnResetCancel);
        Button btnSubmit = findViewById(R.id.btnResetSubmit);

        // Explicitly set label — prevents empty button if layout has no hardcoded text
        btnCancel.setText(R.string.btn_cancel);
        btnSubmit.setText(R.string.btn_reset);

        btnCancel.setOnClickListener(v -> {
            if (onCancelListener != null) onCancelListener.onCancel();
        });
        btnSubmit.setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (TextUtils.isEmpty(key)) {
                etKey.setError(getContext().getString(R.string.reset_dialog_title));
                return;
            }
            if (onResetListener != null) onResetListener.onReset(key);
        });
    }
}
