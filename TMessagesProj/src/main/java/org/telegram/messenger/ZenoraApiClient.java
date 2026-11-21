package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZenoraApiClient {
    private static final String BASE_URL = "https://miladalizadw.ir";
    private static final String HOST = "miladalizadw.ir";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final ExecutorService AVATAR_EXECUTOR = Executors.newFixedThreadPool(3);

    private ZenoraApiClient() {}

    public interface LoginCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface DialogsCallback {
        void onSuccess(ArrayList<ZenoraSession.DialogInfo> dialogs);
        void onError(String message, boolean sessionExpired);
    }

    private static final class HttpResult {
        final int status;
        final String body;
        final Map<String, List<String>> headers;

        HttpResult(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? new HashMap<>() : headers;
        }
    }

    public static void login(Context context, String username, String password, LoginCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                String cleanUsername = username == null ? "" : username.trim();
                if (cleanUsername.isEmpty() || password == null || password.isEmpty()) {
                    postLoginError(callback, "نام کاربری و رمز عبور را وارد کنید.");
                    return;
                }

                LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
                HttpResult loginPage = request("GET", "/index.php", cookies, null, null);
                collectCookies(loginPage.headers, cookies);
                if (loginPage.status < 200 || loginPage.status >= 400) {
                    postLoginError(callback, "صفحه ورود زنورا در دسترس نیست. اتصال اینترنت را بررسی کنید.");
                    return;
                }

                Matcher csrfMatcher = Pattern.compile(
                        "(?is)<input[^>]*name\\s*=\\s*[\"']csrf[\"'][^>]*value\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"
                ).matcher(loginPage.body);
                if (!csrfMatcher.find()) {
                    postLoginError(callback, "دریافت توکن امنیتی ورود انجام نشد. دوباره تلاش کنید.");
                    return;
                }

                String csrf = csrfMatcher.group(1);
                String form = "csrf=" + encode(csrf)
                        + "&auth_mode=login"
                        + "&username=" + encode(cleanUsername)
                        + "&password=" + encode(password);
                HttpResult loginResult = request(
                        "POST",
                        "/index.php",
                        cookies,
                        "application/x-www-form-urlencoded; charset=UTF-8",
                        form.getBytes(StandardCharsets.UTF_8)
                );
                collectCookies(loginResult.headers, cookies);

                if (cookies.isEmpty()) {
                    postLoginError(callback, "ورود انجام نشد. نام کاربری یا رمز عبور را بررسی کنید.");
                    return;
                }

                HttpResult verification = request("GET", "/api/rooms.php", cookies, null, null);
                collectCookies(verification.headers, cookies);
                if (verification.status != HttpURLConnection.HTTP_OK) {
                    postLoginError(callback, verification.status == 419
                            ? "نشست امنیتی منقضی شد؛ دوباره تلاش کنید."
                            : "نام کاربری یا رمز عبور صحیح نیست.");
                    return;
                }

                JSONObject payload = new JSONObject(verification.body);
                if (!payload.optBoolean("ok", false)) {
                    postLoginError(callback, "ورود تأیید نشد. دوباره تلاش کنید.");
                    return;
                }
                JSONObject me = payload.optJSONObject("me");
                if (me == null || me.optLong("id", 0) <= 0) {
                    postLoginError(callback, "پاسخ حساب کاربری از سرور معتبر نیست.");
                    return;
                }

                String cookieHeader = toCookieHeader(cookies);
                boolean stored = ZenoraSession.saveSession(
                        appContext,
                        cookieHeader,
                        me.optLong("id", 0),
                        me.optString("username", cleanUsername),
                        me.optString("display_name", cleanUsername)
                );
                if (!stored) {
                    postLoginError(callback, "ذخیره امن نشست روی این دستگاه انجام نشد.");
                    return;
                }
                postLoginSuccess(callback);
            } catch (Exception e) {
                FileLog.e(e);
                postLoginError(callback, "ارتباط با سرور زنورا برقرار نشد. اتصال اینترنت را بررسی کنید.");
            }
        });
    }

    public static void fetchDialogs(Context context, DialogsCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                String cookieHeader = ZenoraSession.getCookieHeader();
                if (cookieHeader.isEmpty()) {
                    ZenoraSession.clear(appContext);
                    postDialogsError(callback, "نشست زنورا معتبر نیست. دوباره وارد شوید.", true);
                    return;
                }
                LinkedHashMap<String, String> cookies = parseCookieHeader(cookieHeader);
                HttpResult result = request("GET", "/api/rooms.php", cookies, null, null);
                collectCookies(result.headers, cookies);

                if (result.status == HttpURLConnection.HTTP_UNAUTHORIZED
                        || result.status == HttpURLConnection.HTTP_FORBIDDEN
                        || result.status == HttpURLConnection.HTTP_MOVED_TEMP
                        || result.status == HttpURLConnection.HTTP_MOVED_PERM
                        || result.status == HttpURLConnection.HTTP_SEE_OTHER) {
                    ZenoraSession.clear(appContext);
                    postDialogsError(callback, "نشست زنورا منقضی شده است. دوباره وارد شوید.", true);
                    return;
                }
                if (result.status != HttpURLConnection.HTTP_OK) {
                    postDialogsError(callback, "دریافت فهرست گفتگوها با خطا روبه‌رو شد.", false);
                    return;
                }

                JSONObject payload = new JSONObject(result.body);
                if (!payload.optBoolean("ok", false)) {
                    String error = payload.optString("error", "");
                    boolean expired = "AUTH_REQUIRED".equals(error) || "SESSION_REVOKED".equals(error);
                    if (expired) {
                        ZenoraSession.clear(appContext);
                    }
                    postDialogsError(callback, expired ? "نشست زنورا منقضی شده است. دوباره وارد شوید." : "سرور نتوانست فهرست گفتگوها را برگرداند.", expired);
                    return;
                }

                String updatedCookies = toCookieHeader(cookies);
                if (!updatedCookies.isEmpty()) {
                    ZenoraSession.updateCookieHeader(updatedCookies);
                }
                JSONObject me = payload.optJSONObject("me");
                if (me != null) {
                    ZenoraSession.updateProfile(
                            me.optLong("id", ZenoraSession.getAccountUserId()),
                            me.optString("username", ZenoraSession.getUsername()),
                            me.optString("display_name", ZenoraSession.getDisplayName())
                    );
                }

                ArrayList<ZenoraSession.DialogInfo> dialogs = parseDialogs(payload);

                ArrayList<Future<?>> avatarJobs = new ArrayList<>();
                int avatarCount = 0;
                for (ZenoraSession.DialogInfo dialog : dialogs) {
                    if (avatarCount >= 30) break;
                    if (dialog.avatarUrl == null || dialog.avatarUrl.isEmpty()) continue;
                    File cached = avatarFile(appContext, dialog.avatarUrl);
                    if (cached.isFile() && cached.length() > 0) {
                        dialog.avatarPath = cached.getAbsolutePath();
                        avatarCount++;
                        continue;
                    }
                    final ZenoraSession.DialogInfo target = dialog;
                    avatarJobs.add(AVATAR_EXECUTOR.submit(() -> {
                        File downloaded = downloadAvatar(appContext, target.avatarUrl, cookies);
                        if (downloaded != null && downloaded.isFile()) {
                            target.avatarPath = downloaded.getAbsolutePath();
                        }
                        return null;
                    }));
                    avatarCount++;
                }

                ZenoraSession.setCachedDialogs(dialogs);
                postDialogsSuccess(callback, dialogs);

                if (!avatarJobs.isEmpty()) {
                    EXECUTOR.execute(() -> {
                        for (Future<?> job : avatarJobs) {
                            try {
                                job.get();
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        }
                        ZenoraSession.setCachedDialogs(dialogs);
                        postDialogsSuccess(callback, dialogs);
                    });
                }
            } catch (Exception e) {
                FileLog.e(e);
                postDialogsError(callback, "ارتباط با سرور زنورا برقرار نشد.", false);
            }
        });
    }

    private static ArrayList<ZenoraSession.DialogInfo> parseDialogs(JSONObject payload) throws JSONException {
        ArrayList<ZenoraSession.DialogInfo> result = new ArrayList<>();
        JSONArray groups = payload.optJSONArray("rooms");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject row = groups.optJSONObject(i);
                if (row == null || row.optInt("is_archived", 0) == 1) continue;
                ZenoraSession.DialogInfo info = parseGroup(row);
                if (info != null) result.add(info);
            }
        }

        JSONArray dms = payload.optJSONArray("dm_rooms");
        if (dms != null) {
            for (int i = 0; i < dms.length(); i++) {
                JSONObject row = dms.optJSONObject(i);
                if (row == null || row.optInt("is_archived", 0) == 1) continue;
                ZenoraSession.DialogInfo info = parseDm(row);
                if (info != null) result.add(info);
            }
        }

        Collections.sort(result, new Comparator<ZenoraSession.DialogInfo>() {
            @Override
            public int compare(ZenoraSession.DialogInfo left, ZenoraSession.DialogInfo right) {
                if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
                if (left.pinned && right.pinned && left.pinOrder != right.pinOrder) {
                    return Integer.compare(left.pinOrder, right.pinOrder);
                }
                return Integer.compare(right.date, left.date);
            }
        });
        return result;
    }

    private static ZenoraSession.DialogInfo parseGroup(JSONObject row) {
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) return null;
        ZenoraSession.DialogInfo info = new ZenoraSession.DialogInfo();
        info.roomId = roomId;
        info.type = row.optString("type", "group");
        info.title = firstNonEmpty(row.optString("name", ""), "گفتگو");
        info.message = preview(row.optString("last_message_body", ""), row.optString("last_message_media_kind", ""));
        String sender = row.optString("last_message_sender_display_name", "");
        if (!sender.isEmpty() && !info.message.isEmpty()) {
            info.message = sender + ": " + info.message;
        }
        info.avatarUrl = normalizeAvatar(row.optString("avatar_path", ""));
        info.unreadCount = Math.max(0, row.optInt("unread_count", 0));
        info.date = parseDate(row.optString("last_message_time", ""));
        info.pinned = row.optInt("is_pinned", 0) == 1;
        info.pinOrder = row.optInt("pin_order", 0);
        info.muted = row.optInt("is_muted", 0) == 1;
        info.verified = row.optInt("is_verified", 0) == 1;
        info.media = !row.optString("last_message_media_kind", "").isEmpty();
        return info;
    }

    private static ZenoraSession.DialogInfo parseDm(JSONObject row) {
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) return null;
        ZenoraSession.DialogInfo info = new ZenoraSession.DialogInfo();
        info.roomId = roomId;
        info.type = "dm";
        info.title = firstNonEmpty(row.optString("other_display_name", ""), row.optString("other_username", ""), "کاربر زنورا");
        info.message = preview(row.optString("last_message_body", ""), row.optString("last_message_media_kind", ""));
        if (!info.message.isEmpty() && row.optString("last_message_sender_username", "").equals(ZenoraSession.getUsername())) {
            info.message = "شما: " + info.message;
        }
        info.avatarUrl = normalizeAvatar(row.optString("other_avatar_path", ""));
        info.unreadCount = Math.max(0, row.optInt("unread_count", 0));
        info.date = parseDate(row.optString("last_message_time", ""));
        info.pinned = row.optInt("is_pinned", 0) == 1;
        info.pinOrder = row.optInt("pin_order", 0);
        info.muted = row.optInt("is_muted", 0) == 1;
        info.verified = row.optInt("other_is_verified", 0) == 1;
        info.media = !row.optString("last_message_media_kind", "").isEmpty();
        return info;
    }

    private static String preview(String body, String mediaKind) {
        String value = body == null ? "" : body.trim();
        if (value.startsWith(":gif:")) {
            String caption = value.substring(5).trim();
            return caption.isEmpty() ? "GIF" : "GIF · " + caption;
        }
        if (value.startsWith(":sticker:")) return "استیکر";
        if (!value.isEmpty()) return value;
        switch (mediaKind == null ? "" : mediaKind) {
            case "voice": return "پیام صوتی";
            case "video_note": return "ویدیو مسیج";
            case "photo": return "تصویر";
            case "video": return "ویدیو";
            case "music": return "فایل صوتی";
            case "gif": return "GIF";
            case "sticker": return "استیکر";
            case "file": return "فایل";
            default: return "";
        }
    }

    private static int parseDate(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value);
            return date == null ? 0 : (int) (date.getTime() / 1000L);
        } catch (ParseException e) {
            return 0;
        }
    }

    private static String normalizeAvatar(String path) {
        if (path == null) return "";
        String value = path.trim();
        if (value.isEmpty()) return "";
        try {
            URL url = value.startsWith("http://") || value.startsWith("https://")
                    ? new URL(value)
                    : new URL(new URL(BASE_URL + "/"), value.startsWith("/") ? value.substring(1) : value);
            if (!HOST.equalsIgnoreCase(url.getHost()) || !"https".equalsIgnoreCase(url.getProtocol())) {
                return "";
            }
            return url.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static File avatarFile(Context context, String url) {
        File directory = new File(context.getCacheDir(), "zenora-avatars");
        if (!directory.exists() && !directory.mkdirs()) return new File(directory, "unavailable");
        return new File(directory, sha256(url) + ".jpg");
    }

    private static File downloadAvatar(Context context, String url, Map<String, String> cookies) {
        HttpURLConnection connection = null;
        File temporary = null;
        try {
            URL target = new URL(url);
            if (!"https".equalsIgnoreCase(target.getProtocol()) || !HOST.equalsIgnoreCase(target.getHost())) return null;
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/1.0");
            String cookieHeader = toCookieHeader(cookies);
            if (!cookieHeader.isEmpty()) connection.setRequestProperty("Cookie", cookieHeader);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.US).startsWith("image/")) return null;
            int declaredSize = connection.getContentLength();
            if (declaredSize > 2 * 1024 * 1024) return null;
            File output = avatarFile(context, url);
            temporary = new File(output.getParentFile(), output.getName() + ".tmp");
            try (InputStream input = connection.getInputStream(); FileOutputStream stream = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 2 * 1024 * 1024) {
                        temporary.delete();
                        return null;
                    }
                    stream.write(buffer, 0, count);
                }
                stream.flush();
            }
            if (temporary.length() <= 0 || !temporary.renameTo(output)) {
                temporary.delete();
                return null;
            }
            return output;
        } catch (Exception e) {
            if (temporary != null) temporary.delete();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static HttpResult request(String method, String path, Map<String, String> cookies, String contentType, byte[] body) throws Exception {
        URL url = new URL(BASE_URL + path);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !HOST.equalsIgnoreCase(url.getHost())) {
            throw new IllegalArgumentException("Zenora API must use the pinned HTTPS origin.");
        }
        HttpsConnectionHolder holder = new HttpsConnectionHolder(url);
        HttpURLConnection connection = holder.connection;
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, text/html;q=0.9");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/1.0");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            String cookieHeader = toCookieHeader(cookies);
            if (!cookieHeader.isEmpty()) connection.setRequestProperty("Cookie", cookieHeader);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                    output.flush();
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readLimited(input, 6 * 1024 * 1024);
            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null) headers.put(entry.getKey(), entry.getValue());
            }
            return new HttpResult(status, response, headers);
        } finally {
            connection.disconnect();
        }
    }

    private static final class HttpsConnectionHolder {
        final HttpURLConnection connection;
        HttpsConnectionHolder(URL url) throws Exception {
            this.connection = (HttpURLConnection) url.openConnection();
        }
    }

    private static String readLimited(InputStream input, int maximumBytes) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IllegalStateException("Zenora response exceeded the size limit.");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static void collectCookies(Map<String, List<String>> headers, Map<String, String> cookies) {
        if (headers == null) return;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String setCookie : entry.getValue()) {
                if (setCookie == null) continue;
                String[] pieces = setCookie.split(";", 2);
                int equals = pieces[0].indexOf('=');
                if (equals <= 0) continue;
                String name = pieces[0].substring(0, equals).trim();
                String value = pieces[0].substring(equals + 1).trim();
                if (name.isEmpty()) continue;
                if (setCookie.toLowerCase(Locale.US).contains("max-age=0")) cookies.remove(name);
                else cookies.put(name, value);
            }
        }
    }

    private static LinkedHashMap<String, String> parseCookieHeader(String header) {
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        if (header == null) return cookies;
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return cookies;
    }

    private static String toCookieHeader(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        if (cookies == null) return "";
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : second;
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        String result = firstNonEmpty(first, second);
        return result == null || result.trim().isEmpty() ? fallback : result;
    }

    private static void postLoginSuccess(LoginCallback callback) {
        if (callback != null) MAIN.post(callback::onSuccess);
    }

    private static void postLoginError(LoginCallback callback, String message) {
        if (callback != null) MAIN.post(() -> callback.onError(message));
    }

    private static void postDialogsSuccess(DialogsCallback callback, ArrayList<ZenoraSession.DialogInfo> dialogs) {
        if (callback != null) MAIN.post(() -> callback.onSuccess(new ArrayList<>(dialogs)));
    }

    private static void postDialogsError(DialogsCallback callback, String message, boolean expired) {
        if (callback != null) MAIN.post(() -> callback.onError(message, expired));
    }
}
