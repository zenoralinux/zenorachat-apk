package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cookie-backed transport for the ZenoraChat PHP API. */
public final class ZenoraApiClient {
    private static final String API_BASE = "https://miladalizadw.ir/telegram/api/";
    private static final String PREFS = "zenora_api_session";
    private static final String COOKIE_KEY = "cookies";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object cookieLock = new Object();

    public interface Callback {
        void onComplete(JSONObject response, Exception error);
    }

    public static final class ApiException extends Exception {
        public final int statusCode;
        public final String errorCode;

        ApiException(int statusCode, String errorCode) {
            super((errorCode == null || errorCode.isEmpty() ? "API_REQUEST_FAILED" : errorCode)
                    + " (HTTP " + statusCode + ")");
            this.statusCode = statusCode;
            this.errorCode = errorCode == null ? "" : errorCode;
        }
    }

    private ZenoraApiClient() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isZenoraSessionEnabled() {
        if (ApplicationLoader.applicationContext == null
                || !prefs().getBoolean("enabled", false) || !hasAuthCookie()) {
            return false;
        }
        // Restore the native account shell if UserConfig was lost while the
        // long-lived Zenora session cookie and profile metadata survived.
        try {
            int account = UserConfig.selectedAccount;
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) {
                JSONObject profile = getStoredProfile();
                if (profile != null) ZenoraChatController.activateAccount(account, profile);
            }
        } catch (Exception ignored) {
            // The subsequent normal startup/login flow remains responsible for recovery.
        }
        return true;
    }

    private static boolean hasAuthCookie() {
        return hasAuthCookie(cookies());
    }

    private static boolean hasAuthCookie(String stored) {
        if (stored == null || stored.isEmpty()) return false;
        for (String part : stored.split(";")) {
            String item = part.trim();
            if (item.matches("zenorachat_auth=[a-fA-F0-9]{64}")) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSavedAuthCookie() {
        return hasAuthCookie();
    }

    public static void enableSession(JSONObject profile) {
        if (profile == null || profile.optLong("id", 0) <= 0) return;
        boolean saved = prefs().edit()
                .putBoolean("enabled", true)
                .putString("username", profile.optString("username", ""))
                .putString("display_name", profile.optString("display_name", ""))
                .putLong("user_id", profile.optLong("id", 0))
                .commit();
        if (!saved) {
            throw new IllegalStateException("Unable to persist the ZenoraChat account profile");
        }
    }

    public static JSONObject getStoredProfile() {
        if (ApplicationLoader.applicationContext == null
                || !prefs().getBoolean("enabled", false) || !hasAuthCookie()) return null;
        long userId = prefs().getLong("user_id", 0);
        if (userId <= 0) return null;
        JSONObject profile = new JSONObject();
        try {
            profile.put("id", userId);
            profile.put("username", prefs().getString("username", ""));
            profile.put("display_name", prefs().getString("display_name", ""));
        } catch (Exception ignored) {
            return null;
        }
        return profile;
    }

    public static void clearSession() {
        prefs().edit().clear().commit();
    }

    public static void login(String username, String password, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
                String csrf = challenge.optString("csrf", "");
                if (csrf.isEmpty()) throw new IllegalStateException("CSRF token missing");
                String form = "username=" + encode(username)
                        + "&password=" + encode(password)
                        + "&csrf=" + encode(csrf);
                JSONObject result = request("mobile_login.php", "POST", form);
                JSONObject me = result.optJSONObject("me");
                if (me == null || me.optLong("id", 0) <= 0) {
                    throw new IllegalStateException("Incomplete account profile");
                }
                if (!hasAuthCookie()) {
                    throw new IllegalStateException("The server did not issue a ZenoraChat session cookie.");
                }
                enableSession(me);
                dispatch(callback, result, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    public static void fetchRooms(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response;
                try {
                    // A short-lived edge/session race must not immediately log the
                    // user out. Keep the saved Zenora token for one retry after 401.
                    response = request("rooms.php", "GET", null, true);
                } catch (ApiException firstError) {
                    if (firstError.statusCode != 401 || !hasAuthCookie()) {
                        throw firstError;
                    }
                    FileLog.w("[ZenoraChat] rooms.php returned HTTP 401; retrying once with the saved session.");
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Room-list retry was interrupted", interrupted);
                    }
                    // The second response is authoritative and may clear a truly
                    // expired/revoked token through its Set-Cookie header.
                    response = request("rooms.php", "GET", null, false);
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static JSONObject request(String path, String method, String formBody) throws Exception {
        return request(path, method, formBody, false);
    }

    private static JSONObject request(String path, String method, String formBody,
                                      boolean preserveAuthCookieOnUnauthorized) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/1.0");
            String cookie = cookies();
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);

            if (formBody != null) {
                byte[] data = formBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }

            int status = connection.getResponseCode();
            saveCookies(connection, preserveAuthCookieOnUnauthorized && status == 401);
            FileLog.d("[ZenoraChat] API " + path + " HTTP " + status
                    + ", auth cookie sent=" + hasAuthCookie(cookie));
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String raw = readBody(stream);
            JSONObject json;
            try {
                json = new JSONObject(raw);
            } catch (Exception e) {
                throw new IllegalStateException("ZenoraChat returned an invalid JSON response (HTTP " + status + ")", e);
            }
            if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
                throw new ApiException(status, json.optString("error", "REQUEST_FAILED"));
            }
            return json;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String cookies() {
        synchronized (cookieLock) {
            return prefs().getString(COOKIE_KEY, "");
        }
    }

    private static void saveCookies(HttpURLConnection connection, boolean preserveAuthCookieOnClear) {
        synchronized (cookieLock) {
            Map<String, String> values = new LinkedHashMap<>();
            String current = prefs().getString(COOKIE_KEY, "");
            for (String part : current.split(";")) {
                String item = part.trim();
                int equals = item.indexOf('=');
                if (equals > 0) values.put(item.substring(0, equals).trim(), item.substring(equals + 1).trim());
            }
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
                for (String setCookie : header.getValue()) {
                    if (setCookie == null) continue;
                    String pair = setCookie.split(";", 2)[0].trim();
                    int equals = pair.indexOf('=');
                    if (equals <= 0) continue;
                    String name = pair.substring(0, equals).trim();
                    String value = pair.substring(equals + 1).trim();
                    if (value.isEmpty()) {
                        // Do not discard the long-lived auth token on the first
                        // unauthorized response; fetchRooms retries once before
                        // accepting a server-side session revocation.
                        if (!(preserveAuthCookieOnClear && "zenorachat_auth".equals(name))) {
                            values.remove(name);
                        }
                    } else {
                        values.put(name, value);
                    }
                }
            }
            StringBuilder joined = new StringBuilder();
            for (Map.Entry<String, String> item : values.entrySet()) {
                if (joined.length() > 0) joined.append("; ");
                joined.append(item.getKey()).append('=').append(item.getValue());
            }
            // Persist the auth cookie before the UI navigates away from the login screen.
            prefs().edit().putString(COOKIE_KEY, joined.toString()).commit();
        }
    }

    private static void dispatch(Callback callback, JSONObject response, Exception error) {
        if (callback != null) mainHandler.post(() -> callback.onComplete(response, error));
    }
}
