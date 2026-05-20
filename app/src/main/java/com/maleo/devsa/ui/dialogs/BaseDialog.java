package com.maleo.devsa.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import com.maleo.bussidbdhg.R;

/**
 * BaseDialog — Parent for all dialogs.
 *
 * SIZE (programmatic — not static):
 *   Width  = 75% of screen width
 *   Height = 85% of screen height (WRAP_CONTENT capped — dialog won't exceed this)
 *   Gravity = CENTER (horizontal + vertical)
 *
 * Other enforcements:
 *   - Outside click DISABLED (setCancelable false)
 *   - No title bar
 *   - Transparent window background so card drawable (#282828 + rounded corners) shows correctly
 */
public abstract class BaseDialog extends Dialog {

    public BaseDialog(Context context) {
        super(context, R.style.Theme_BUSSIDAuth);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }

    /**
     * Call at the end of every subclass onCreate() AFTER setContentView().
     * Applies programmatic 75% width / 85% height sizing and centers the dialog.
     */
    protected void applyDimensions() {
        Window window = getWindow();
        if (window == null) return;

        // Transparent window — our layout's own background drawable handles the card look
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Get real screen dimensions
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        int dialogW = (int) (screenW * 0.75f);
        int dialogH = (int) (screenH * 0.85f);

        // Apply size
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width   = dialogW;
        lp.height  = dialogH;          // fixed max height — dialog scrolls internally if needed
        lp.gravity = Gravity.CENTER;   // center both horizontally AND vertically
        window.setAttributes(lp);
    }
}
