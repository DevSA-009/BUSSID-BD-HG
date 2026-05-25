package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * ActivationDialog — Separate dedicated dialog for activation key entry.
 * Does NOT reuse ResetDialog or any other dialog layout.
 */
public class ActivationDialog extends BaseDialog {

    public interface OnActivateListener {
        void onActivate(String key);
    }

    public interface OnResetRequestListener {
        void onResetRequested();
    }

    private final OnActivateListener activateListener;
    private final OnResetRequestListener resetListener;

    public ActivationDialog(Context context,
                            OnActivateListener activateListener,
                            OnResetRequestListener resetListener) {
        super(context);
        this.activateListener = activateListener;
        this.resetListener = resetListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_activation);
        applyDimensions(); // programmatic 75%w / 85%h centered

        EditText etKey = findViewById(R.id.etActivationKey);
        Button btnActivate = findViewById(R.id.btnActivate);
        TextView tvReset = findViewById(R.id.tvResetLink);

        btnActivate.setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (TextUtils.isEmpty(key)) {
                etKey.setError(getContext().getString(R.string.err_key_empty));
                return;
            }
            if (activateListener != null) activateListener.onActivate(key);
        });

        tvReset.setOnClickListener(v -> {
            if (resetListener != null) resetListener.onResetRequested();
        });
    }
}
