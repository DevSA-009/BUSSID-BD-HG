package com.maleo.devsa.network;

import android.os.Build;
import android.util.Log;

import com.maleo.devsa.util.AppConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ApiService — All HTTP calls in one class.
 * <p>
 * Every call logs to Logcat:
 * Tag: "BussidBD_API"
 * Format: → [METHOD] url | payload: {...}
 * ← response: {...}  (or ERROR: ...)
 * <p>
 * Filter in Logcat with tag: BussidBD_API
 */
public class ApiService {

    private static final String TAG = "BussidBD_API";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Server response constants
    public static final String RESP_VALID = "valid";
    public static final String RESP_INVALID = "invalid";
    public static final String RESP_ACTIVATED = "activated";
    public static final String RESP_AACT = "aact";
    public static final String RESP_NE = "ne";
    public static final String RESP_EXP = "exp";
    public static final String RESP_INV = "inv";
    public static final String RESP_ERROR = "error";

    // ─── Logging helpers ────────────────────────────────────────────────────

    private void logRequest(String method, String url, String payload) {
        Log.d(TAG, "→ [" + method + "] " + url
                + (payload != null && !payload.isEmpty() ? " | payload: " + payload : ""));
    }

    private void logResponse(String url, String response) {
        Log.d(TAG, "← " + url + " | response: " + response);
    }

    private void logError(String url, Exception e) {
        Log.e(TAG, "✗ " + url + " | ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // ─── Build device info JSON ──────────────────────────────────────────────

    private JSONObject buildDevicePayload(String key, String obbPatchVersion) {
        JSONObject root = new JSONObject();
        JSONObject device = new JSONObject();
        try {
            device.put("brand", Build.BRAND);
            device.put("model", Build.MODEL);
            device.put("androidVersion", Build.VERSION.RELEASE);
            device.put("obbPatchVersion", obbPatchVersion);
            root.put("device", device);
            root.put("key", key);
        } catch (JSONException ignored) {
        }
        return root;
    }

    // ─── Check Key ───────────────────────────────────────────────────────────

    /**
     * GET /user/ck/:key → "valid" | "invalid" | "error"
     */
    public String checkKey(String key) {
        String url = AppConfig.BASE_URL + "/user/ck/" + key.trim();
        logRequest("GET", url, null);

        Request req = new Request.Builder().url(url).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String body = res.body() != null ? res.body().string().trim() : RESP_ERROR;
            logResponse(url, body);
            return res.isSuccessful() ? body : RESP_ERROR;
        } catch (IOException e) {
            logError(url, e);
            return RESP_ERROR;
        }
    }

    // ─── Verify / Activate ───────────────────────────────────────────────────

    /**
     * GET /user/verify/:key + device body → "activated"|"aact"|"ne"|"exp"|"inv"|"error"
     */
    public String verifyKey(String key, String obbPatchVersion) {
        String url = AppConfig.BASE_URL + "/user/verify/";
        JSONObject payload = buildDevicePayload(key.trim(), obbPatchVersion);
        logRequest("POST", url, payload.toString());

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder().url(url).method("POST", body).build();

        try (Response res = ApiClient.get().newCall(req).execute()) {
            String resp = res.body() != null ? res.body().string().trim() : RESP_ERROR;
            logResponse(url, resp);
            return res.isSuccessful() ? resp : RESP_ERROR;
        } catch (IOException e) {
            logError(url, e);
            return RESP_ERROR;
        }
    }

    // ─── Reset ───────────────────────────────────────────────────────────────

    /**
     * POST /user/reset  body: {"resetKey":"..."} → "success" | server error message
     */
    public String resetAccount(String resetKey) {
        String url = AppConfig.BASE_URL + "/user/reset";
        JSONObject json = new JSONObject();
        try {
            json.put("resetKey", resetKey.trim());
        } catch (JSONException ignored) {
        }
        logRequest("POST", url, json.toString());

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();

        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResponse(url, raw);

            JSONObject parsed = new JSONObject(raw);
            boolean ok = parsed.optBoolean("success", false);
            return ok ? "success" : parsed.optString("message", RESP_ERROR);
        } catch (IOException | JSONException e) {
            logError(url, e);
            return RESP_ERROR;
        }
    }

    // ─── Patch Info ───────────────────────────────────────────────────────────

    /**
     * GET /user/patch/:key + device body
     * Plain text response: "url---#guidOffset--assetOffset---#version"
     */
    public PatchInfo getPatchInfo(String key, String obbPatchVersion) {
        String url = AppConfig.BASE_URL + "/user/patch/";
        JSONObject payload = buildDevicePayload(key.trim(), obbPatchVersion);
        logRequest("GET", url, payload.toString());

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder().url(url).method("GET", body).build();

        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResponse(url, raw);

            if (!res.isSuccessful() || raw.isEmpty() || raw.equals(RESP_ERROR)) return null;

            String[] parts = raw.split("---#");
            if (parts.length < 3) {
                Log.w(TAG, "getPatchInfo: unexpected format: " + raw);
                return null;
            }
            String patchUrl = parts[0].trim();
            String[] offsets = parts[1].trim().split("--");
            String version = parts[2].trim();
            String guidOffset = offsets.length > 0 ? offsets[0].trim() : "";
            String assetOffset = offsets.length > 1 ? offsets[1].trim() : "";
            return new PatchInfo(patchUrl, guidOffset, assetOffset, version);
        } catch (IOException e) {
            logError(url, e);
            return null;
        }
    }

    public static class PatchInfo {
        public final String patchUrl, guidOffset, assetOffset, version;

        PatchInfo(String u, String g, String a, String v) {
            patchUrl = u;
            guidOffset = g;
            assetOffset = a;
            version = v;
        }
    }

    // ─── Check Update ─────────────────────────────────────────────────────────

    /**
     * GET /user/update/:key → UpdateInfo or null
     */
    public UpdateInfo checkUpdate(String key) {
        String url = AppConfig.BASE_URL + "/user/update/" + key.trim();
        logRequest("GET", url, null);

        Request req = new Request.Builder().url(url).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResponse(url, raw);

            if (!res.isSuccessful() || raw.isEmpty()) return null;

            JSONObject parsed = new JSONObject(raw);
            if (!parsed.optBoolean("success", false)) return null;

            JSONObject data = parsed.optJSONObject("data");
            if (data == null) return null;

            return new UpdateInfo(
                    data.optString("currentVersion", "unknown"),
                    data.optString("patchUrl", ""),
                    data.optBoolean("hasUpdate", false)
            );
        } catch (IOException | JSONException e) {
            logError(url, e);
            return null;
        }
    }

    public static class UpdateInfo {
        public final String currentVersion, patchUrl;
        public final boolean hasUpdate;

        UpdateInfo(String v, String u, boolean h) {
            currentVersion = v;
            patchUrl = u;
            hasUpdate = h;
        }
    }

    // ─── Report Issue ─────────────────────────────────────────────────────────

    /**
     * POST /user/report  body: {"activationKey":"...","message":"..."} → true/false
     */
    public boolean reportIssue(String activationKey, String message) {
        String url = AppConfig.BASE_URL + "/user/report";
        JSONObject json = new JSONObject();
        try {
            json.put("activationKey", activationKey.trim());
            json.put("message", message.trim());
        } catch (JSONException ignored) {
        }
        logRequest("POST", url, json.toString());

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();

        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResponse(url, raw);

            JSONObject parsed = new JSONObject(raw);
            return parsed.optBoolean("success", false);
        } catch (IOException | JSONException e) {
            logError(url, e);
            return false;
        }
    }

    // ─── Download File ────────────────────────────────────────────────────────

    /**
     * Download file from URL to destination File, reporting progress via callback.
     * Logs start, completion, and any errors.
     */
    public boolean downloadFile(String downloadUrl, File destination, ProgressCallback callback) {
        logRequest("GET (download)", downloadUrl, null);

        Request req = new Request.Builder().url(downloadUrl).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) {
                Log.e(TAG, "✗ download failed, HTTP " + res.code() + " | " + downloadUrl);
                return false;
            }

            long total = res.body().contentLength();
            InputStream in = res.body().byteStream();
            OutputStream out = new FileOutputStream(destination);
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int read;
            int lastPct = -1;

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                if (total > 0) {
                    int pct = (int) (downloaded * 100L / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        if (callback != null) callback.onProgress(pct);
                    }
                }
            }
            out.flush();
            out.close();
            in.close();

            Log.d(TAG, "← download complete: " + destination.getName()
                    + " (" + downloaded + " bytes)");
            return true;
        } catch (IOException e) {
            logError(downloadUrl, e);
            return false;
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
