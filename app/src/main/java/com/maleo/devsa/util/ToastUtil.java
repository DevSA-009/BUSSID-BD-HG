package com.maleo.devsa.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * ToastUtil — Static helper to show Toast from any thread (including background threads).
 * Always posts to main thread so it's safe to call from ExecutorService callbacks.
 */
public final class ToastUtil {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ToastUtil() {
    }

    public static void show(Context ctx, String message) {
        // Use application context to avoid Activity leak
        final Context appCtx = ctx.getApplicationContext();
        MAIN.post(() -> Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show());
    }
}
