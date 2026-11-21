package org.telegram.messenger.zenora;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP transport for the ZenoraChat PHP API.
 *
 * All methods are synchronous by design: callers MUST invoke them from a
 * background executor/thread, never from Android's main thread.
 */
public final class ZenoraApiClient {
    public static final String API_BASE = "https://miladalizadw.ir/Telegram/api/";
    private static final String PREFS = "zenora_api_session";
    private static final String COOKIE_KEY = "cookies";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private final SharedPreferences preferences;
    private String csrfToken;

    public ZenoraApiClient(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public JSONObject fetchCsrfToken() throws Exception {
        JSONObject response = request("mobile_auth.php", "GET", null, false);
        csrfToken = response.optString("csrf", "");
        if (!response.optBoolean("ok") || csrfToken.isEmpty()) {
            throw new JSONException("ZenoraChat did not return a CSRF token");
        }
        return response;
    }

    public JSONObject login(String username, String password) throws Exception {
        if (csrfToken == null || csrfToken.isEmpty()) {
            fetchCsrfToken();
        }
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        JSONObject response = request("mobile_auth.php", "POST", body, true);
        if (!response.optBoolean("ok")) {
            throw new ZenoraApiException(response.optString("error", "LOGIN_FAILED"));
        }
        return response;
    }

    public JSONArray fetchRooms() throws Exception {
        JSONObject response = request("rooms.php", "GET", null, true);
        if (!response.optBoolean("ok")) {
            throw new ZenoraApiException(response.optString("error", "ROOMS_FETCH_FAILED"));
        }
        JSONArray rooms = response.optJSONArray("rooms");
        JSONArray directMessages = response.optJSONArray("dm_rooms");
        JSONArray combined = new JSONArray();
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) combined.put(rooms.optJSONObject(i));
        }
        if (directMessages != null) {
            for (int i = 0; i < directMessages.length(); i++) combined.put(directMessages.optJSONObject(i));
        }
        return combined;
    }

    public void clearSession() {
        preferences.edit().remove(COOKIE_KEY).apply();
        csrfToken = null;
    }

    private JSONObject request(String path, String method, JSONObject body,
                               boolean includeCsrf) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(API_BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");

        String cookies = preferences.getString(COOKIE_KEY, "");
        if (!cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }
        if (includeCsrf && csrfToken != null && !csrfToken.isEmpty()) {
            connection.setRequestProperty("X-CSRF-Token", csrfToken);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        persistResponseCookies(connection);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder result = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
            }
        }
        connection.disconnect();

        JSONObject json = new JSONObject(result.toString());
        if (status == 401 && "AUTH_REQUIRED".equals(json.optString("error"))) {
            clearSession();
        }
        return json;
    }

    private void persistResponseCookies(HttpURLConnection connection) {
        Map<String, String> values = new LinkedHashMap<>();
        String existing = preferences.getString(COOKIE_KEY, "");
        for (String part : existing.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator).trim(),
                        part.substring(separator + 1).trim());
            }
        }
        for (Map.Entry<String, java.util.List<String>> header
                : connection.getHeaderFields().entrySet()) {
            if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())
                    || header.getValue() == null) continue;
            for (String cookie : header.getValue()) {
                String pair = cookie.split(";", 2)[0];
                int separator = pair.indexOf('=');
                if (separator > 0) {
                    values.put(pair.substring(0, separator).trim(),
                            pair.substring(separator + 1).trim());
                }
            }
        }
        StringBuilder serialized = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (serialized.length() > 0) serialized.append("; ");
            serialized.append(value.getKey()).append('=').append(value.getValue());
        }
        if (serialized.length() > 0) {
            preferences.edit().putString(COOKIE_KEY, serialized.toString()).apply();
        }
    }

    public static final class ZenoraApiException extends Exception {
        public final String errorCode;

        ZenoraApiException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }
    }
}
