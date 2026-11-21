package org.telegram.messenger.zenora;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTPS transport for ZenoraChat's native Bearer API. No account credential,
 * cookie or protocol request is sent to Telegram's MTProto endpoints.
 */
public final class ZenoraApiClient {
    private static final String API_BASE = "https://miladalizadw.ir/telegram/api/";
    private static final String API_HOST = "miladalizadw.ir";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    public interface Callback {
        void onResult(JSONObject result, String error);
    }

    private static final class HttpResult {
        final int status;
        final JSONObject json;

        HttpResult(int status, JSONObject json) {
            this.status = status;
            this.json = json == null ? new JSONObject() : json;
        }
    }

    private ZenoraApiClient() {}

    private static String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("RESPONSE_TOO_LARGE");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static HttpResult request(String method, String endpoint, JSONObject body, String accessToken) throws Exception {
        URL url = new URL(API_BASE + endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !API_HOST.equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IllegalArgumentException("INVALID_API_ORIGIN");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/Stage1");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (accessToken != null && !accessToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                    output.flush();
                }
            }

            int status = connection.getResponseCode();
            String responseText;
            try {
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                responseText = readLimited(stream);
            } catch (Exception e) {
                if (status >= 300 && status < 400) {
                    JSONObject redirect = new JSONObject();
                    redirect.put("ok", false);
                    redirect.put("error", "API_REDIRECT");
                    return new HttpResult(status, redirect);
                }
                throw e;
            }

            if (status >= 300 && status < 400) {
                JSONObject redirect = new JSONObject();
                redirect.put("ok", false);
                redirect.put("error", "API_REDIRECT");
                return new HttpResult(status, redirect);
            }

            String trimmed = responseText == null ? "" : responseText.trim();
            String contentType = connection.getContentType() == null
                    ? "" : connection.getContentType().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("<") || contentType.contains("text/html")) {
                JSONObject invalid = new JSONObject();
                invalid.put("ok", false);
                invalid.put("error", "API_NON_JSON_RESPONSE");
                return new HttpResult(status, invalid);
            }

            JSONObject json;
            try {
                json = trimmed.isEmpty() ? new JSONObject() : new JSONObject(trimmed);
            } catch (Exception invalidJson) {
                json = new JSONObject();
                json.put("ok", false);
                json.put("error", "INVALID_SERVER_RESPONSE");
            }
            return new HttpResult(status, json);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject requireOk(HttpResult result) throws Exception {
        if (result.status < 200 || result.status >= 300 || !result.json.optBoolean("ok", false)) {
            throw new ApiException(result.json.optString("error", "HTTP_" + result.status), result.status);
        }
        return result.json;
    }

    private static JSONObject refreshSession(Context context) throws Exception {
        String refreshToken = ZenoraSessionStore.getRefreshToken(context);
        if (refreshToken.isEmpty()) {
            ZenoraSessionStore.clear(context);
            throw new ApiException("INVALID_REFRESH_TOKEN", 401);
        }

        JSONObject body = new JSONObject();
        body.put("refresh_token", refreshToken);
        HttpResult raw = request("POST", "native/refresh.php", body, null);
        JSONObject response;
        try {
            response = requireOk(raw);
        } catch (Exception e) {
            String code = errorCode(e);
            if (raw.status == 401 || raw.status == 403
                    || "INVALID_REFRESH_TOKEN".equals(code)
                    || "ACCOUNT_UNAVAILABLE".equals(code)) {
                ZenoraSessionStore.clear(context);
            }
            throw e;
        }

        JSONObject tokens = response.optJSONObject("tokens");
        JSONObject user = response.optJSONObject("user");
        if (tokens == null || user == null) {
            ZenoraSessionStore.clear(context);
            throw new ApiException("INVALID_REFRESH_RESPONSE", 502);
        }

        String access = tokens.optString("access_token", "");
        String rotatedRefresh = tokens.optString("refresh_token", "");
        if (!ZenoraSessionStore.updateTokens(context, access, rotatedRefresh)) {
            ZenoraSessionStore.clear(context);
            throw new ApiException("SESSION_STORAGE_FAILED", 500);
        }
        ZenoraSessionStore.updateUser(context, user);
        return response;
    }

    private static JSONObject authorizedRequest(Context context, String method, String endpoint, JSONObject body) throws Exception {
        String accessToken = ZenoraSessionStore.getAccessToken(context);
        if (accessToken.isEmpty() || ZenoraSessionStore.getRefreshToken(context).isEmpty()) {
            ZenoraSessionStore.clear(context);
            throw new ApiException("AUTH_REQUIRED", 401);
        }

        HttpResult result = request(method, endpoint, body, accessToken);
        String error = result.json.optString("error", "");
        if (result.status == 401 && "ACCESS_TOKEN_EXPIRED".equals(error)) {
            try {
                refreshSession(context);
            } catch (Exception refreshError) {
                String refreshCode = errorCode(refreshError);
                if ("INVALID_REFRESH_TOKEN".equals(refreshCode)
                        || "ACCOUNT_UNAVAILABLE".equals(refreshCode)) {
                    ZenoraSessionStore.clear(context);
                    throw new ApiException("AUTH_REQUIRED", 401);
                }
                throw refreshError;
            }
            accessToken = ZenoraSessionStore.getAccessToken(context);
            result = request(method, endpoint, body, accessToken);
        }

        try {
            return requireOk(result);
        } catch (Exception e) {
            String code = errorCode(e);
            if (result.status == 401 || result.status == 403
                    || "INVALID_ACCESS_TOKEN".equals(code)
                    || "ACCOUNT_UNAVAILABLE".equals(code)
                    || "AUTHENTICATION_REQUIRED".equals(code)) {
                ZenoraSessionStore.clear(context);
                throw new ApiException("AUTH_REQUIRED", 401);
            }
            throw e;
        }
    }

    private static String errorCode(Exception e) {
        if (e instanceof ApiException) return ((ApiException) e).code;
        return e.getMessage() == null ? "" : e.getMessage();
    }

    private static void deliver(Callback callback, JSONObject result, String error) {
        if (callback != null) callback.onResult(result, error);
    }

    public static void login(Context context, String username, String password, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String cleanUsername = username == null ? "" : username.trim();
                if (cleanUsername.isEmpty() || password == null || password.isEmpty()) {
                    throw new ApiException("INVALID_CREDENTIALS", 400);
                }

                JSONObject credentials = new JSONObject();
                credentials.put("username", cleanUsername);
                credentials.put("password", password);
                credentials.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                credentials.put("platform", "Android");
                credentials.put("app_version", "Stage1");

                JSONObject response = requireOk(request("POST", "native/login.php", credentials, null));
                JSONObject tokens = response.optJSONObject("tokens");
                JSONObject user = response.optJSONObject("user");
                if (tokens == null || user == null) {
                    throw new ApiException("INVALID_LOGIN_RESPONSE", 502);
                }

                String access = tokens.optString("access_token", "");
                String refresh = tokens.optString("refresh_token", "");
                if (!ZenoraSessionStore.saveAuthenticatedSession(app, access, refresh, user)) {
                    ZenoraSessionStore.clear(app);
                    throw new ApiException("SESSION_STORAGE_FAILED", 500);
                }

                JSONObject result = new JSONObject(response.toString());
                result.put("authenticated", true);
                deliver(callback, result, null);
            } catch (Exception e) {
                deliver(callback, null, errorCode(e).isEmpty() ? "NETWORK_ERROR" : errorCode(e));
            }
        });
    }

    public static void checkSession(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject result = authorizedRequest(app, "GET", "native/me.php", null);
                JSONObject user = result.optJSONObject("user");
                if (user == null || user.optLong("id", 0) <= 0) {
                    throw new ApiException("INVALID_SESSION_RESPONSE", 502);
                }
                ZenoraSessionStore.updateUser(app, user);
                result.put("authenticated", true);
                deliver(callback, result, null);
            } catch (Exception e) {
                deliver(callback, null, errorCode(e).isEmpty() ? "SESSION_CHECK_FAILED" : errorCode(e));
            }
        });
    }

    public static void fetchRooms(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject result = authorizedRequest(app, "GET", "native/chats.php", null);
                deliver(callback, result, null);
            } catch (Exception e) {
                deliver(callback, null, errorCode(e).isEmpty() ? "ROOMS_FETCH_FAILED" : errorCode(e));
            }
        });
    }

    public static void logout(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            JSONObject response = null;
            String error = null;
            try {
                response = authorizedRequest(app, "POST", "native/logout.php", new JSONObject());
            } catch (Exception e) {
                error = errorCode(e).isEmpty() ? "LOGOUT_FAILED" : errorCode(e);
            } finally {
                // Always end the local session, including when the device is offline.
                ZenoraSessionStore.clear(app);
            }
            deliver(callback, response, error);
        });
    }

    private static final class ApiException extends Exception {
        final String code;
        final int status;

        ApiException(String code, int status) {
            super(code);
            this.code = code;
            this.status = status;
        }
    }
}
