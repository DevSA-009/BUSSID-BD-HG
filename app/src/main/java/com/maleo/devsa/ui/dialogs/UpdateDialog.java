package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/** Update available dialog — user must update, cannot dismiss. */
public class UpdateDialog extends BaseDialog {

    public interface OnUpdateListener { void onUpdate(); }

    private final String           version;
    private final OnUpdateListener listener;

    public UpdateDialog(Context context, String version, OnUpdateListener listener) {
        super(context);
        this.version  = version;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_update);
        applyDimensions();

        ((TextView) findViewById(R.id.tvUpdateVersion)).setText("নতুন ভার্সন: " + version);
        ((Button)   findViewById(R.id.btnUpdate)).setOnClickListener(v -> {
            if (listener != null) listener.onUpdate();
        });
    }
}
