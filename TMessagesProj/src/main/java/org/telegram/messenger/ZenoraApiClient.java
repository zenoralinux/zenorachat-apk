package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ZenoraApiClient {
    private static final String API_BASE = "https://miladalizadw.ir/telegram/api/";
    private static final String PREFS = "zenora_mobile_session";
    private static final String COOKIE_KEY = "encrypted_cookie_jar";
    private static final String KEY_ALIAS = "zenora_mobile_cookie_key_v1";
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object COOKIE_LOCK = new Object();
    private static Map<String, String> cookies;

    private ZenoraApiClient() {}

    public interface LoginCallback {
        void onSuccess(JSONObject user, JSONObject rooms);
        void onError(String message);
    }

    public interface RoomsCallback {
        void onSuccess(JSONObject rooms);
        void onError(String message);
    }

    public static boolean hasSession() {
        synchronized (COOKIE_LOCK) {
            ensureCookiesLoaded();
            String token = cookies.get("zenorachat_auth");
            return token != null && !token.trim().isEmpty();
        }
    }

    public static void login(String username, String password, LoginCallback callback) {
        NETWORK.execute(() -> {
            try {
                JSONObject bootstrap = request("GET", "mobile_auth.php", null, null);
                String csrf = bootstrap.optString("csrf", "");
                if (csrf.isEmpty()) {
                    throw new Exception("پاسخ شروع نشست زنوراچت معتبر نیست.");
                }

                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject auth = request("POST", "mobile_auth.php", body, csrf);
                JSONObject me = auth.optJSONObject("me");
                if (!auth.optBoolean("authenticated", false) || me == null) {
                    throw new Exception("ورود انجام شد اما اطلاعات حساب دریافت نشد.");
                }

                JSONObject rooms = request("GET", "rooms.php", null, null);
                MAIN.post(() -> callback.onSuccess(me, rooms));
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "ارتباط با زنوراچت ناموفق بود." : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    public static void fetchRooms(RoomsCallback callback) {
        NETWORK.execute(() -> {
            try {
                if (!hasSession()) {
                    throw new Exception("نشست ذخیره‌شدهٔ زنوراچت پیدا نشد.");
                }
                JSONObject rooms = request("GET", "rooms.php", null, null);
                MAIN.post(() -> callback.onSuccess(rooms));
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "دریافت فهرست گفتگوها ناموفق بود." : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    public static void clearSession() {
        synchronized (COOKIE_LOCK) {
            cookies = new LinkedHashMap<>();
            saveCookiesLocked();
        }
    }

    private static JSONObject request(String method, String endpoint, JSONObject body, String csrf) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_BASE + endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isEmpty()) {
                connection.setRequestProperty("Cookie", cookieHeader);
            }
            if (csrf != null && !csrf.isEmpty()) {
                connection.setRequestProperty("X-CSRF-Token", csrf);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            updateCookies(connection);
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readAll(stream);
            JSONObject json;
            try {
                json = new JSONObject(response);
            } catch (Exception parseError) {
                throw new Exception("سرور زنوراچت پاسخ JSON معتبر برنگرداند (HTTP " + status + ").");
            }
            if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
                String code = json.optString("error", "");
                String message;
                if ("INVALID_CREDENTIALS".equals(code)) {
                    message = "نام کاربری یا رمز عبور نادرست است.";
                } else if ("SESSION_REVOKED".equals(code) || "AUTH_REQUIRED".equals(code)) {
                    message = "نشست شما منقضی شده است؛ دوباره وارد شوید.";
                } else if ("HTTPS_REQUIRED".equals(code)) {
                    message = "ارتباط امن HTTPS الزامی است.";
                } else if ("CSRF_INVALID".equals(code)) {
                    message = "اعتبار درخواست ورود منقضی شد؛ دوباره تلاش کنید.";
                } else {
                    message = json.optString("message", code.isEmpty() ? "درخواست ناموفق بود." : code);
                }
                throw new Exception(message + " (HTTP " + status + ")");
            }
            return json;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (output.size() > 2 * 1024 * 1024) {
                throw new Exception("پاسخ سرور بیش از اندازه بزرگ است.");
            }
        }
        return output.toString("UTF-8");
    }

    private static String cookieHeader() {
        synchronized (COOKIE_LOCK) {
            ensureCookiesLoaded();
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (result.length() > 0) result.append("; ");
                result.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return result.toString();
        }
    }

    private static void updateCookies(HttpURLConnection connection) {
        synchronized (COOKIE_LOCK) {
            ensureCookiesLoaded();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                for (String header : entry.getValue()) {
                    if (header == null) continue;
                    String pair = header.split(";", 2)[0].trim();
                    int separator = pair.indexOf('=');
                    if (separator <= 0) continue;
                    String name = pair.substring(0, separator).trim();
                    String value = pair.substring(separator + 1).trim();
                    if (value.isEmpty()) cookies.remove(name);
                    else cookies.put(name, value);
                }
            }
            saveCookiesLocked();
        }
    }

    private static void ensureCookiesLoaded() {
        if (cookies != null) return;
        cookies = new LinkedHashMap<>();
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) return;
            String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COOKIE_KEY, "");
            if (encoded.isEmpty()) return;
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length <= 12) return;
            byte[] iv = new byte[12];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            byte[] encrypted = new byte[packed.length - iv.length];
            System.arraycopy(packed, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            JSONObject saved = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                cookies.put(key, saved.optString(key, ""));
            }
        } catch (Exception e) {
            cookies.clear();
            FileLog.e(e);
        }
    }

    private static void saveCookiesLocked() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) return;
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[cipher.getIV().length + encrypted.length];
            System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
            System.arraycopy(encrypted, 0, packed, cipher.getIV().length, encrypted.length);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(COOKIE_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
