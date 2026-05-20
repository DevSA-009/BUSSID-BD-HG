package com.maleo.devsa.network;

import com.maleo.devsa.util.AppConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** OkHttp singleton. One client shared across all API calls. */
public final class ApiClient {
    private static OkHttpClient instance;
    private ApiClient() {}
    public static OkHttpClient get() {
        if (instance == null) {
            instance = new OkHttpClient.Builder()
                .connectTimeout(AppConfig.CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(AppConfig.READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(AppConfig.WRITE_TIMEOUT, TimeUnit.SECONDS)
                .build();
        }
        return instance;
    }
}
