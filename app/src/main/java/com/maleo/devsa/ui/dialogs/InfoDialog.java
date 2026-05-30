package com.maleo.devsa.ui.dialogs;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.maleo.bussidbdhg.R;

/**
 * InfoDialog — Generic success/error/warning dialog.
 * Use the Builder to configure title, message, and button(s).
 */
public class InfoDialog extends BaseDialog {

    public interface OnInfoAction {
        void onClick(InfoDialog dialog);
    }

    private final String title;
    private final String message;
    private final String primaryBtnText;
    private final String secondaryBtnText;
    private final OnInfoAction primaryAction;
    private final OnInfoAction secondaryAction;

    private InfoDialog(Builder b) {
        super(b.context);
        this.title = b.title;
        this.message = b.message;
        this.primaryBtnText = b.primaryBtnText;
        this.secondaryBtnText = b.secondaryBtnText;
        this.primaryAction = b.primaryAction;
        this.secondaryAction = b.secondaryAction;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_info);
        applyDimensions();

        TextView tvTitle = findViewById(R.id.tvInfoTitle);
        TextView tvMessage = findViewById(R.id.tvInfoMessage);
        Button btnPri = findViewById(R.id.btnInfoPrimary);
        Button btnSec = findViewById(R.id.btnInfoSecondary);

        tvTitle.setText(title);
        tvMessage.setText(message);
        btnPri.setText(primaryBtnText);
        btnPri.setOnClickListener(v -> {
            if (primaryAction != null) primaryAction.onClick(this);
        });

        if (secondaryBtnText != null && secondaryAction != null) {
            btnSec.setVisibility(View.VISIBLE);
            btnSec.setText(secondaryBtnText);
            btnSec.setOnClickListener(v -> secondaryAction.onClick(this));
        }
    }

    public static class Builder {
        final Context context;
        String title = "";
        String message = "";
        String primaryBtnText;
        String secondaryBtnText;
        OnInfoAction primaryAction;
        OnInfoAction secondaryAction;

        /**
         * Default primary button label is R.string.btn_ok — "ঠিক আছে"
         * Resolved via context so no hardcoded Bangle string in Java code.
         */
        public Builder(Context context) {
            this.context = context;
            this.primaryBtnText = context.getString(R.string.btn_ok);
        }

        public Builder title(String t) {
            this.title = t;
            return this;
        }

        public Builder message(String m) {
            this.message = m;
            return this;
        }

        public Builder primaryButton(String t, OnInfoAction a) {
            primaryBtnText = t;
            primaryAction = a;
            return this;
        }

        public Builder secondaryButton(String t, OnInfoAction a) {
            secondaryBtnText = t;
            secondaryAction = a;
            return this;
        }

        public InfoDialog build() {
            return new InfoDialog(this);
        }
    }
}
