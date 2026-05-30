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
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ApiService — All HTTP calls.
 * Logcat tag: BussidBD_API
 * <p>
 * Returns:
 * RESP_NO_INTERNET  — no network (UnknownHostException / timeout)
 * RESP_SERVER_ERROR — server returned "error" string or non-2xx
 * RESP_ERROR        — unexpected/parse failure
 */
public class ApiService {

    private static final String TAG = "BussidBD_API";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Response constants
    public static final String RESP_VALID = "valid";

    public static final String RESP_SUCC = "success";
    public static final String RESP_LIMIT = "limit";
    public static final String RESP_ACTIVATED = "activated";
    public static final String RESP_AACT = "aact";
    public static final String RESP_NE = "ne";
    public static final String RESP_EXP = "exp";
    public static final String RESP_INV = "inv";
    public static final String RESP_ERROR = "error";
    public static final String RESP_NO_INTERNET = "no_internet";
    public static final String RESP_SERVER_ERROR = "server_error";

    // ── Logging ───────────────────────────────────────────────────────────────
    private void logReq(String method, String url, String payload) {
        Log.d(TAG, "→ [" + method + "] " + url
                + (payload != null ? " | payload: " + payload : ""));
    }

    private void logResp(String url, String resp) {
        Log.d(TAG, "← " + url + " | response: " + resp);
    }

    private void logErr(String url, Exception e) {
        Log.e(TAG, "✗ " + url + " | " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // ── Device payload ────────────────────────────────────────────────────────
    private JSONObject devicePayload() {
        JSONObject root = new JSONObject();
        JSONObject dev = new JSONObject();
        try {
            dev.put("brand", Build.BRAND);
            dev.put("model", Build.MODEL);
            dev.put("androidVersion", Build.VERSION.RELEASE);
            dev.put("obbPatchVersion", "0.0");
            root.put("device", dev);
        } catch (JSONException ignored) {
        }
        return root;
    }

    /**
     * Detect if IOException means no internet.
     */
    private boolean isNoInternet(IOException e) {
        return e instanceof UnknownHostException || e instanceof SocketTimeoutException;
    }

    // ── 1. Check key ──────────────────────────────────────────────────────────

    /**
     * GET /user/ck/:key → "valid" | "invalid" | RESP_NO_INTERNET | RESP_SERVER_ERROR
     */
    public String checkKey(String key) {
        String url = AppConfig.BASE_URL + "/user/ck/" + key.trim();
        logReq("GET", url, null);
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String body = res.body() != null ? res.body().string().trim() : RESP_ERROR;
            logResp(url, body);
            if (!res.isSuccessful()) return RESP_SERVER_ERROR;
            if (RESP_ERROR.equals(body)) return RESP_SERVER_ERROR;
            return body;
        } catch (IOException e) {
            logErr(url, e);
            return isNoInternet(e) ? RESP_NO_INTERNET : RESP_SERVER_ERROR;
        }
    }

    // ── 2. Verify / Activate ──────────────────────────────────────────────────

    /**
     * POST /user/verify
     * body: { key, device{} }
     * → "activated"|"aact"|"ne"|"exp"|"inv"|RESP_NO_INTERNET|RESP_SERVER_ERROR
     */
    public String verifyKey(String key) {
        String url = AppConfig.BASE_URL + "/user/verify";
        JSONObject payload = devicePayload();
        try {
            payload.put("key", key.trim());
        } catch (JSONException ignored) {
        }
        logReq("POST", url, payload.toString());

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String resp = res.body() != null ? res.body().string().trim() : RESP_ERROR;
            logResp(url, resp);
            if (!res.isSuccessful()) return RESP_SERVER_ERROR;
            if (RESP_ERROR.equals(resp)) return RESP_SERVER_ERROR;
            return resp;
        } catch (IOException e) {
            logErr(url, e);
            return isNoInternet(e) ? RESP_NO_INTERNET : RESP_SERVER_ERROR;
        }
    }

    // ── 3. Reset ──────────────────────────────────────────────────────────────

    /**
     * POST /user/reset  body: { resetKey } → "success" | error message string
     */
    public String resetAccount(String resetKey) {
        String url = AppConfig.BASE_URL + "/user/reset";
        JSONObject json = new JSONObject();
        try {
            json.put("resetKey", resetKey.trim());
        } catch (JSONException ignored) {
        }
        logReq("POST", url, json.toString());

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResp(url, raw);
            JSONObject parsed = new JSONObject(raw);
            return parsed.optBoolean("success", false)
                    ? "success"
                    : parsed.optString("message", RESP_SERVER_ERROR);
        } catch (IOException e) {
            logErr(url, e);
            return isNoInternet(e) ? RESP_NO_INTERNET : RESP_SERVER_ERROR;
        } catch (JSONException e) {
            logErr(url, e);
            return RESP_SERVER_ERROR;
        }
    }

    // ── 4. Get Patch Info ─────────────────────────────────────────────────────

    /**
     * POST /user/patch
     * body: { key, device{} }
     * response (plain): PATCH_URL---#GUID_OFFSET--ASSET_OFFSET---#VERSION | error
     */
    public PatchInfo getPatchInfo(String key) {
        String url = AppConfig.BASE_URL + "/user/patch";
        JSONObject payload = devicePayload();
        try {
            payload.put("key", key.trim());
        } catch (JSONException ignored) {
        }
        logReq("POST", url, payload.toString());

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResp(url, raw);
            if (!res.isSuccessful() || raw.isEmpty() || RESP_ERROR.equals(raw)) return null;

            String[] parts = raw.split("---#");
            if (parts.length < 3) {
                Log.w(TAG, "patch: bad format: " + raw);
                return null;
            }

            String patchUrl = parts[0].trim();
            String[] offsets = parts[1].trim().split("--");
            String version = parts[2].trim();
            String guidOffset = offsets.length > 0 ? offsets[0].trim() : "";
            String assetOffset = offsets.length > 1 ? offsets[1].trim() : "";
            return new PatchInfo(patchUrl, guidOffset, assetOffset, version);
        } catch (IOException e) {
            logErr(url, e);
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

    // ── 5. Check Update ───────────────────────────────────────────────────────

    /**
     * GET /user/update/:key
     * response: { success, data:{ version, patchUrl, asset_offset, gui_offset, hasUpdate } }
     */
    public UpdateInfo checkUpdate(String key) {
        String url = AppConfig.BASE_URL + "/user/update/" + key.trim();
        logReq("GET", url, null);
        Request req = new Request.Builder().url(url).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResp(url, raw);
            if (!res.isSuccessful() || raw.isEmpty()) return null;

            JSONObject parsed = new JSONObject(raw);
            if (!parsed.optBoolean("success", false)) return null;

            JSONObject data = parsed.optJSONObject("data");
            if (data == null) return null;
            return new UpdateInfo(
                    data.optString("version", "unknown"),
                    data.optString("patchUrl", ""),
                    data.optString("gui_offset", ""),
                    data.optString("asset_offset", ""),
                    data.optBoolean("hasUpdate", false)
            );
        } catch (IOException | JSONException e) {
            logErr(url, e);
            return null;
        }
    }

    public static class UpdateInfo {
        public final String version, patchUrl, guidOffset, assetOffset;
        public final boolean hasUpdate;

        UpdateInfo(String v, String u, String g, String a, boolean h) {
            version = v;
            patchUrl = u;
            guidOffset = g;
            assetOffset = a;
            hasUpdate = h;
        }
    }

    // ── 6. Report Issue ───────────────────────────────────────────────────────

    /**
     * POST /user/report  body: { activationKey, message }
     */
    public void reportIssue(String activationKey, String message) {
        String url = AppConfig.BASE_URL + "/user/report";
        JSONObject json = new JSONObject();
        try {
            json.put("activationKey", activationKey.trim());
            json.put("message", message.trim());
        } catch (JSONException ignored) {
        }
        logReq("POST", url, json.toString());

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            String raw = res.body() != null ? res.body().string().trim() : "";
            logResp(url, raw);
            new JSONObject(raw).optBoolean("success", false);
        } catch (IOException | JSONException e) {
            logErr(url, e);
        }
    }

    // ── 7. Download File ──────────────────────────────────────────────────────

    /**
     * Download URL to a TEMP file. On success rename to finalFile.
     * On failure (IOException / incomplete) temp file is deleted.
     *
     * @return true if download + rename succeeded
     */
    public boolean downloadFile(String downloadUrl, File tempFile, File finalFile,
                                ProgressCallback cb) {
        logReq("GET(download)", downloadUrl, null);
        Request req = new Request.Builder().url(downloadUrl).get().build();
        try (Response res = ApiClient.get().newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) {
                Log.e(TAG, "✗ download HTTP " + res.code() + " | " + downloadUrl);
                return false;
            }

            long total = res.body().contentLength();
            InputStream in = res.body().byteStream();
            OutputStream out = new FileOutputStream(tempFile);
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
                        if (cb != null) cb.onProgress(pct);
                    }
                }
            }
            out.flush();
            out.close();
            in.close();

            // Validate: temp file must have content
            if (tempFile.length() == 0) {
                tempFile.delete();
                Log.e(TAG, "✗ download empty file: " + downloadUrl);
                return false;
            }

            // Rename temp → final
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete();
                Log.e(TAG, "✗ rename failed: " + tempFile + " → " + finalFile);
                return false;
            }

            Log.d(TAG, "← download ok: " + finalFile.getName() + " (" + downloaded + " bytes)");
            return true;
        } catch (IOException e) {
            logErr(downloadUrl, e);
            if (tempFile.exists()) tempFile.delete(); // cleanup partial download
            return false;
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
