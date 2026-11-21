package org.telegram.messenger.zenora;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JSON/HTTPS transport for ZenoraChat. It is intentionally separate from
 * ConnectionsManager/MTProto so no Telegram auth request reaches Telegram.
 */
public final class ZenoraApiClient {
    private static final String API_BASE = "https://miladalizadw.ir/telegram/api/";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public interface Callback {
        void onResult(JSONObject result, String error);
    }

    private static final class HttpResult {
        final int status;
        final JSONObject json;
        HttpResult(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    private ZenoraApiClient() {}

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void mergeCookies(Context context, Map<String, List<String>> headers) {
        Map<String, String> values = new LinkedHashMap<>();
        String current = ZenoraSessionStore.getCookies(context);
        if (!current.isEmpty()) {
            for (String part : current.split(";")) {
                int index = part.indexOf('=');
                if (index > 0) values.put(part.substring(0, index).trim(), part.substring(index + 1).trim());
            }
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String setCookie : entry.getValue()) {
                if (setCookie == null) continue;
                String pair = setCookie.split(";", 2)[0].trim();
                int index = pair.indexOf('=');
                if (index <= 0) continue;
                String name = pair.substring(0, index).trim();
                String value = pair.substring(index + 1).trim();
                if (value.isEmpty()) values.remove(name);
                else if ("PHPSESSID".equals(name) || "zenorachat_auth".equals(name)) values.put(name, value);
            }
        }
        StringBuilder cookieHeader = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (cookieHeader.length() > 0) cookieHeader.append("; ");
            cookieHeader.append(entry.getKey()).append('=').append(entry.getValue());
        }
        ZenoraSessionStore.saveCookies(context, cookieHeader.toString());
    }

    private static HttpResult request(Context context, String method, String endpoint, JSONObject body, String csrf) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("User-Agent", "ZenoraChat-Android/Stage1");
        String cookies = ZenoraSessionStore.getCookies(context);
        if (!cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        if (csrf != null && !csrf.isEmpty()) connection.setRequestProperty("X-CSRF-Token", csrf);

        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }
        }

        int status = connection.getResponseCode();
        mergeCookies(context, connection.getHeaderFields());
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseText = readAll(stream);
        connection.disconnect();

        JSONObject json;
        try {
            json = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
        } catch (Exception invalidJson) {
            json = new JSONObject();
            json.put("ok", false);
            json.put("error", status >= 300 && status < 400 ? "AUTH_REDIRECT" : "INVALID_SERVER_RESPONSE");
        }
        return new HttpResult(status, json);
    }

    private static JSONObject requireOk(HttpResult result) throws Exception {
        if (result.status < 200 || result.status >= 300 || !result.json.optBoolean("ok", false)) {
            throw new Exception(result.json.optString("error", "HTTP_" + result.status));
        }
        return result.json;
    }

    public static void login(Context context, String username, String password, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject csrfResponse = requireOk(request(app, "GET", "mobile_csrf.php", null, null));
                String initialCsrf = csrfResponse.optString("csrf", "");
                if (initialCsrf.isEmpty()) throw new Exception("CSRF_UNAVAILABLE");
                ZenoraSessionStore.saveCsrf(app, initialCsrf);

                JSONObject credentials = new JSONObject();
                credentials.put("username", username);
                credentials.put("password", password);
                JSONObject loggedIn = requireOk(request(app, "POST", "mobile_login.php", credentials, initialCsrf));
                String newCsrf = loggedIn.optString("csrf", "");
                JSONObject user = loggedIn.optJSONObject("user");
                if (newCsrf.isEmpty() || user == null) throw new Exception("INVALID_LOGIN_RESPONSE");
                ZenoraSessionStore.saveAuthenticatedSession(app, ZenoraSessionStore.getCookies(app), newCsrf, user);
                callback.onResult(loggedIn, null);
            } catch (Exception e) {
                // Do not retain a temporary CSRF-only session after a failed login.
                if (!ZenoraSessionStore.hasSession(app)) ZenoraSessionStore.clear(app);
                callback.onResult(null, e.getMessage() == null ? "NETWORK_ERROR" : e.getMessage());
            }
        });
    }

    public static void checkSession(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                HttpResult raw = request(app, "GET", "mobile_session.php", null, null);
                JSONObject result = requireOk(raw);
                String csrf = result.optString("csrf", "");
                JSONObject user = result.optJSONObject("user");
                if (csrf.isEmpty() || user == null) throw new Exception("INVALID_SESSION_RESPONSE");
                ZenoraSessionStore.saveAuthenticatedSession(app, ZenoraSessionStore.getCookies(app), csrf, user);
                callback.onResult(result, null);
            } catch (Exception e) {
                callback.onResult(null, e.getMessage() == null ? "SESSION_CHECK_FAILED" : e.getMessage());
            }
        });
    }

    public static void fetchRooms(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                JSONObject result = requireOk(request(app, "GET", "rooms.php", null, null));
                callback.onResult(result, null);
            } catch (Exception e) {
                callback.onResult(null, e.getMessage() == null ? "ROOMS_FETCH_FAILED" : e.getMessage());
            }
        });
    }

    public static void logout(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                HttpResult raw = request(app, "POST", "mobile_logout.php", new JSONObject(), ZenoraSessionStore.getCsrf(app));
                JSONObject result = requireOk(raw);
                ZenoraSessionStore.clear(app);
                callback.onResult(result, null);
            } catch (Exception e) {
                // Revoke locally even when the network is unavailable. The server-side
                // session may expire by policy or can be revoked through the web account.
                ZenoraSessionStore.clear(app);
                callback.onResult(null, e.getMessage() == null ? "LOGOUT_FAILED" : e.getMessage());
            }
        });
    }
}
