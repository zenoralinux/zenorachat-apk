package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
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
    private static final String CSRF_KEY = "csrf";
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
        return ApplicationLoader.applicationContext != null
                && prefs().getBoolean("enabled", false)
                && hasAuthCookie();
    }

    private static boolean hasAuthCookie() {
        String stored = cookies();
        for (String part : stored.split(";")) {
            String item = part.trim();
            if (item.startsWith("zenorachat_auth=") && item.length() > "zenorachat_auth=".length()) {
                return true;
            }
        }
        return false;
    }

    public static void enableSession(JSONObject profile, String csrf) {
        if (profile == null || profile.optLong("id", 0) <= 0) return;
        prefs().edit()
                .putBoolean("enabled", true)
                .putString("username", profile.optString("username", ""))
                .putLong("user_id", profile.optLong("id", 0))
                .putString(CSRF_KEY, csrf == null ? "" : csrf)
                .apply();
    }

    public static void clearSession() {
        if (ApplicationLoader.applicationContext != null) {
            prefs().edit().clear().commit();
        }
    }

    private static String csrfToken() {
        return ApplicationLoader.applicationContext == null ? "" : prefs().getString(CSRF_KEY, "");
    }

    public static void performChatBulkAction(String action, long[] roomIds, Callback callback) {
        executor.execute(() -> {
            try {
                if (!isZenoraSessionEnabled()) throw new IllegalStateException("ZenoraChat session is not active");
                String csrf = csrfToken();
                if (csrf.isEmpty()) throw new IllegalStateException("ZenoraChat CSRF token is missing");
                JSONArray ids = new JSONArray();
                if (roomIds != null) {
                    for (long roomId : roomIds) {
                        if (roomId > 0) ids.put(roomId);
                    }
                }
                if (ids.length() == 0) throw new IllegalArgumentException("No valid chat identifiers");
                String form = "action=" + encode(action)
                        + "&room_ids=" + encode(ids.toString())
                        + "&csrf=" + encode(csrf);
                dispatch(callback, request("chat_bulk_action.php", "POST", form), null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    public static void markRoomRead(long roomId, long lastReadId, Callback callback) {
        executor.execute(() -> {
            try {
                if (!isZenoraSessionEnabled()) throw new IllegalStateException("ZenoraChat session is not active");
                String csrf = csrfToken();
                if (csrf.isEmpty()) throw new IllegalStateException("ZenoraChat CSRF token is missing");
                String form = "room_id=" + encode(Long.toString(roomId))
                        + "&last_read_id=" + encode(Long.toString(Math.max(0, lastReadId)))
                        + "&csrf=" + encode(csrf);
                dispatch(callback, request("room_read.php", "POST", form), null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
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
                enableSession(me, result.optString("csrf", ""));
                dispatch(callback, result, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    public static void fetchRooms(Callback callback) {
        executor.execute(() -> {
            try {
                dispatch(callback, request("rooms.php", "GET", null), null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static JSONObject request(String path, String method, String formBody) throws Exception {
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
            saveCookies(connection);
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

    private static void saveCookies(HttpURLConnection connection) {
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
                    if (value.isEmpty()) values.remove(name);
                    else values.put(name, value);
                }
            }
            StringBuilder joined = new StringBuilder();
            for (Map.Entry<String, String> item : values.entrySet()) {
                if (joined.length() > 0) joined.append("; ");
                joined.append(item.getKey()).append('=').append(item.getValue());
            }
            prefs().edit().putString(COOKIE_KEY, joined.toString()).apply();
        }
    }

    private static void dispatch(Callback callback, JSONObject response, Exception error) {
        if (callback != null) mainHandler.post(() -> callback.onComplete(response, error));
    }
}
