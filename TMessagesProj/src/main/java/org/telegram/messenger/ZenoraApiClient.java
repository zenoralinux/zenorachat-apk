package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cookie-backed transport for the ZenoraChat PHP API. */
public final class ZenoraApiClient {
    private static final String SITE_BASE = ZenoraMediaPreviewUrl.SITE_BASE;
    private static final String API_BASE = SITE_BASE + "api/";
    private static final String PREFS = "zenora_api_session";
    private static final String COOKIE_KEY = "cookies";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final ExecutorService textSendExecutor = new java.util.concurrent.ThreadPoolExecutor(
            3, 3, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(128),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object cookieLock = new Object();
    // Accessed only from the serialized API executor.
    private static final Map<Long, Long> lastTypingUpdateMs = new HashMap<>();

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
        boolean saved;
        synchronized (cookieLock) {
            saved = prefs().edit()
                    .putBoolean("enabled", true)
                    .putString("username", profile.optString("username", ""))
                    .putString("display_name", profile.optString("display_name", ""))
                    .putLong("user_id", profile.optLong("id", 0))
                    .commit();
        }
        if (!saved) {
            throw new IllegalStateException("Unable to persist the ZenoraChat account profile");
        }
    }

    /** Identity attached to the active API cookie; used to isolate durable records across logins. */
    public static long getCurrentUserId() {
        if (ApplicationLoader.applicationContext == null) return 0L;
        synchronized (cookieLock) {
            if (!prefs().getBoolean("enabled", false)
                    || !hasAuthCookie(prefs().getString(COOKIE_KEY, ""))) return 0L;
            return prefs().getLong("user_id", 0L);
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
        synchronized (cookieLock) {
            prefs().edit().clear().commit();
        }
        executor.execute(lastTypingUpdateMs::clear);
        ZenoraChatController.clearSessionEphemeralState();
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
                String csrfToken = result.optString("csrf", "");
                if (csrfToken.isEmpty()) throw new IllegalStateException("Authenticated CSRF token missing");
                prefs().edit().putString("csrf", csrfToken).commit();
                dispatch(callback, result, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    public static String resolvePublicUrl(String path) {
        return ZenoraMediaPreviewUrl.resolvePublicUrl(path);
    }

    /** Change per-user mute state using Zenora PHP, without any MTProto request. */
    public static void setChatMute(long roomId, boolean muted, int muteSeconds, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomId <= 0) throw new IllegalArgumentException("Invalid room id");
                if (muteSeconds < 0 || muteSeconds > 31536000) {
                    throw new IllegalArgumentException("Invalid mute duration");
                }

                String action = muted ? "mute" : "unmute";
                String csrf = getMuteActionCsrfToken();
                String form = buildMuteActionForm(roomId, action, muteSeconds, csrf);
                JSONObject response;
                try {
                    response = request("chat_bulk_action.php", "POST", form);
                } catch (ApiException error) {
                    // The long-lived app session can outlive the PHP CSRF value.
                    // Refresh only for an explicit CSRF rejection and retry once.
                    if (!"CSRF_INVALID".equals(error.errorCode)) throw error;
                    FileLog.w("[ZenoraChat] mute request rejected by CSRF; refreshing token once.");
                    csrf = refreshMuteActionCsrfToken();
                    form = buildMuteActionForm(roomId, action, muteSeconds, csrf);
                    response = request("chat_bulk_action.php", "POST", form);
                }

                if (!action.equals(response.optString("action", ""))) {
                    throw new IllegalStateException("ZenoraChat returned an unexpected mute action");
                }
                if (response.optInt("processed_count", -1) != 1) {
                    throw new IllegalStateException("ZenoraChat did not confirm exactly one updated chat");
                }
                int expectedMuted = muted ? 1 : 0;
                if (response.optInt("is_muted", -1) != expectedMuted) {
                    throw new IllegalStateException("ZenoraChat returned an unexpected mute state");
                }
                long muteUntil = response.optLong("mute_until", -1);
                long serverTime = response.optLong("server_time", 0);
                if (muteUntil < 0 || serverTime <= 0
                        || (!muted && muteUntil != 0)
                        || (muted && muteSeconds == 0 && muteUntil != 0)
                        || (muted && muteSeconds > 0 && muteUntil == 0)) {
                    throw new IllegalStateException("ZenoraChat returned an invalid mute deadline");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /** Update server typing state with native composer throttling (2s; server expiry is 6s). */
    public static void updateTyping(long roomId, boolean typing, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomId <= 0) throw new IllegalArgumentException("Invalid room id");
                long now = System.currentTimeMillis();
                Long last = lastTypingUpdateMs.get(roomId);
                if (typing && last != null && now - last < 2_000L) {
                    JSONObject throttled = new JSONObject();
                    throttled.put("ok", true);
                    throttled.put("throttled", true);
                    dispatch(callback, throttled, null);
                    return;
                }
                String form = "room_id=" + encode(String.valueOf(roomId))
                        + "&is_typing=" + (typing ? "1" : "0");
                JSONObject response = postWithCsrfRetry("typing_update.php", form);
                if (typing) lastTypingUpdateMs.put(roomId, now);
                else lastTypingUpdateMs.remove(roomId);
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /** Mark only mention/reaction events inside the actual native read range. */
    public static void markRoomAttentionRead(long roomId, int maxMessageId, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomId <= 0 || maxMessageId <= 0) {
                    throw new IllegalArgumentException("Invalid room attention read range");
                }
                String form = "room_id=" + encode(String.valueOf(roomId))
                        + "&max_message_id=" + encode(String.valueOf(maxMessageId));
                JSONObject mentions = postWithCsrfRetry("mentions_read.php", form);
                JSONObject reactions = postWithCsrfRetry("reaction_mentions_read.php", form);
                JSONObject combined = new JSONObject();
                combined.put("ok", mentions.optBoolean("ok", false) && reactions.optBoolean("ok", false));
                combined.put("mentions", mentions);
                combined.put("reactions", reactions);
                if (!combined.optBoolean("ok", false)) {
                    throw new IllegalStateException("ZenoraChat did not confirm both attention read cursors");
                }
                dispatch(callback, combined, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /** Persist a real read cursor and clear the personal Marked Unread flag. */
    public static void markRoomRead(long roomId, int lastReadMessageId, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomId <= 0 || lastReadMessageId < 0) {
                    throw new IllegalArgumentException("Invalid room read cursor");
                }
                String form = "room_id=" + encode(String.valueOf(roomId))
                        + "&last_read_id=" + encode(String.valueOf(lastReadMessageId));
                JSONObject response = postWithCsrfRetry("room_read.php", form);
                if (!response.optBoolean("ok", false)) {
                    throw new IllegalStateException("ZenoraChat did not confirm the read operation");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /** Set the personal Marked Unread flag without changing the real read cursor. */
    public static void setRoomMarkedUnread(long roomId, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomId <= 0) throw new IllegalArgumentException("Invalid room id");
                String form = "room_id=" + encode(String.valueOf(roomId));
                JSONObject response = postWithCsrfRetry("room_mark_unread.php", form);
                if (!response.optBoolean("ok", false)) {
                    throw new IllegalStateException("ZenoraChat did not confirm the unread marker");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    private static JSONObject postWithCsrfRetry(String path, String formWithoutCsrf) throws Exception {
        String csrf = getMuteActionCsrfToken();
        String form = formWithoutCsrf + "&csrf=" + encode(csrf);
        try {
            return request(path, "POST", form);
        } catch (ApiException error) {
            if (!"CSRF_INVALID".equals(error.errorCode)) throw error;
            FileLog.w("[ZenoraChat] " + path + " rejected CSRF; refreshing and retrying once.");
            csrf = refreshMuteActionCsrfToken();
            return request(path, "POST", formWithoutCsrf + "&csrf=" + encode(csrf));
        }
    }

    private static String getMuteActionCsrfToken() throws Exception {
        String csrf = prefs().getString("csrf", "");
        return csrf.isEmpty() ? refreshMuteActionCsrfToken() : csrf;
    }

    private static String refreshMuteActionCsrfToken() throws Exception {
        JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
        String csrf = challenge.optString("csrf", "");
        if (csrf.isEmpty()) {
            throw new IllegalStateException("Authenticated CSRF token missing");
        }
        if (!prefs().edit().putString("csrf", csrf).commit()) {
            throw new IllegalStateException("Unable to persist authenticated CSRF token");
        }
        return csrf;
    }

    private static String buildMuteActionForm(long roomId, String action,
                                              int muteSeconds, String csrf) throws Exception {
        return "room_ids=" + encode("[" + roomId + "]")
                + "&action=" + encode(action)
                + "&mute_seconds=" + encode(String.valueOf(muteSeconds))
                + "&csrf=" + encode(csrf);
    }

    /** Archive or restore dialogs through Zenora PHP; never uses Telegram MTProto. */
    public static void setChatArchive(List<Long> roomIds, boolean archived,
                                      boolean restorePinnedOnUndo, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomIds == null || roomIds.isEmpty() || roomIds.size() > 500) {
                    throw new IllegalArgumentException("Invalid archive operation size");
                }
                JSONArray ids = new JSONArray();
                java.util.HashSet<Long> uniqueIds = new java.util.HashSet<>();
                for (Long roomId : roomIds) {
                    if (roomId == null || roomId <= 0 || !uniqueIds.add(roomId)) {
                        throw new IllegalArgumentException("Invalid or duplicate Zenora room id");
                    }
                    ids.put(roomId);
                }
                String action = archived ? "archive"
                        : (restorePinnedOnUndo ? "unarchive_restore" : "unarchive");
                String csrf = prefs().getString("csrf", "");
                if (csrf.isEmpty()) {
                    JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
                    csrf = challenge.optString("csrf", "");
                    if (csrf.isEmpty()) {
                        throw new IllegalStateException("Authenticated CSRF token missing");
                    }
                    prefs().edit().putString("csrf", csrf).commit();
                }
                String form = "room_ids=" + encode(ids.toString())
                        + "&action=" + encode(action)
                        + "&csrf=" + encode(csrf);
                JSONObject response = request("chat_bulk_action.php", "POST", form);
                if (response.optInt("processed_count", -1) != roomIds.size()) {
                    throw new IllegalStateException("ZenoraChat did not confirm every archive change");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }


    /** Delete/hide chats in Zenora; each DM can carry its own delete-for-both scope. */
    public static void deleteChats(List<Long> roomIds, Map<Long, String> deleteScopes, String operationId, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomIds == null || roomIds.isEmpty() || roomIds.size() > 500) {
                    throw new IllegalArgumentException("Invalid delete operation size");
                }
                if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{8,64}")) {
                    throw new IllegalArgumentException("Invalid delete operation id");
                }
                JSONArray ids = new JSONArray();
                JSONObject scopes = new JSONObject();
                java.util.HashSet<Long> uniqueIds = new java.util.HashSet<>();
                for (Long roomId : roomIds) {
                    if (roomId == null || roomId <= 0 || !uniqueIds.add(roomId)) {
                        throw new IllegalArgumentException("Invalid or duplicate Zenora room id");
                    }
                    ids.put(roomId);
                    String scope = deleteScopes == null ? "me" : deleteScopes.get(roomId);
                    if (scope == null) scope = "me";
                    if (!"me".equals(scope) && !"both".equals(scope)) {
                        throw new IllegalArgumentException("Invalid per-chat delete scope");
                    }
                    scopes.put(String.valueOf(roomId), scope);
                }

                String csrf = prefs().getString("csrf", "");
                if (csrf.isEmpty()) {
                    JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
                    csrf = challenge.optString("csrf", "");
                    if (csrf.isEmpty()) {
                        throw new IllegalStateException("Authenticated CSRF token missing");
                    }
                    prefs().edit().putString("csrf", csrf).commit();
                }

                String form = "room_ids=" + encode(ids.toString())
                        + "&action=delete"
                        + "&delete_scopes=" + encode(scopes.toString())
                        + "&operation_id=" + encode(operationId)
                        + "&csrf=" + encode(csrf);
                JSONObject response = requestDeleteWithRetry(form);
                if (response.optInt("processed_count", -1) != ids.length()) {
                    throw new IllegalStateException("ZenoraChat did not confirm every delete/leave operation");
                }
                JSONArray items = response.optJSONArray("items");
                if (items == null || items.length() != ids.length()) {
                    throw new IllegalStateException("ZenoraChat returned an incomplete delete result");
                }
                java.util.HashSet<Long> expectedIds = new java.util.HashSet<>();
                for (int i = 0; i < ids.length(); i++) expectedIds.add(ids.optLong(i, 0));
                java.util.HashSet<Long> returnedIds = new java.util.HashSet<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) throw new IllegalStateException("ZenoraChat returned an invalid delete item");
                    long returnedId = item.optLong("room_id", 0);
                    if (!expectedIds.contains(returnedId) || !returnedIds.add(returnedId)) {
                        throw new IllegalStateException("ZenoraChat returned mismatched delete items");
                    }
                }
                if (!returnedIds.equals(expectedIds)) {
                    throw new IllegalStateException("ZenoraChat did not confirm every selected chat");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /**
     * Retry a possibly committed delete request once with the same operation id.
     * The server caches the committed result by user + operation id.
     */
    private static JSONObject requestDeleteWithRetry(String form) throws Exception {
        try {
            return request("chat_bulk_action.php", "POST", form);
        } catch (ApiException error) {
            if (error.statusCode < 500) throw error;
            FileLog.w("[ZenoraChat] delete API returned HTTP " + error.statusCode + "; retrying idempotently.");
        } catch (IOException error) {
            FileLog.w("[ZenoraChat] delete API connection failed; retrying with the same operation id.");
        }

        try {
            Thread.sleep(400L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Delete retry was interrupted", interrupted);
        }
        return request("chat_bulk_action.php", "POST", form);
    }

    /** Block/unblock a DM peer using Zenora's authenticated API, not MTProto. */
    public static void setDmBlocked(long otherUserId, boolean blocked, Callback callback) {
        executor.execute(() -> {
            try {
                if (otherUserId <= 0) throw new IllegalArgumentException("Invalid peer id");
                String csrf = prefs().getString("csrf", "");
                if (csrf.isEmpty()) {
                    JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
                    csrf = challenge.optString("csrf", "");
                    if (csrf.isEmpty()) {
                        throw new IllegalStateException("Authenticated CSRF token missing");
                    }
                    prefs().edit().putString("csrf", csrf).commit();
                }
                String form = "other_user_id=" + encode(String.valueOf(otherUserId))
                        + "&action=" + encode(blocked ? "block" : "unblock")
                        + "&csrf=" + encode(csrf);
                JSONObject response = request("dm_block_toggle.php", "POST", form);
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    /** Persist native Telegram pin state in Zenora; no MTProto request is sent. */
    public static void setChatPinned(List<Long> roomIds, boolean pinned, Callback callback) {
        setChatPinAction(roomIds, pinned ? "pin" : "unpin", callback);
    }

    /** Persist the complete visible pin order for the current folder in Zenora. */
    public static void movePinnedChatsToTop(List<Long> orderedRoomIds, Callback callback) {
        setChatPinAction(orderedRoomIds, "move_pin_top", callback);
    }

    private static void setChatPinAction(List<Long> roomIds, String action, Callback callback) {
        executor.execute(() -> {
            try {
                if (roomIds == null || roomIds.isEmpty() || roomIds.size() > 500) {
                    throw new IllegalArgumentException("Invalid pin operation size");
                }
                JSONArray ids = new JSONArray();
                java.util.HashSet<Long> uniqueIds = new java.util.HashSet<>();
                for (Long roomId : roomIds) {
                    if (roomId == null || roomId <= 0 || !uniqueIds.add(roomId)) {
                        throw new IllegalArgumentException("Invalid or duplicate Zenora room id");
                    }
                    ids.put(roomId);
                }
                String csrf = prefs().getString("csrf", "");
                if (csrf.isEmpty()) {
                    JSONObject challenge = request("mobile_auth_challenge.php", "GET", null);
                    csrf = challenge.optString("csrf", "");
                    if (csrf.isEmpty()) {
                        throw new IllegalStateException("Authenticated CSRF token missing");
                    }
                    prefs().edit().putString("csrf", csrf).commit();
                }
                String form = "room_ids=" + encode(ids.toString())
                        + "&action=" + encode(action)
                        + "&csrf=" + encode(csrf);
                JSONObject response = request("chat_bulk_action.php", "POST", form);
                if (response.optInt("processed_count", -1) != ids.length()) {
                    throw new IllegalStateException("ZenoraChat did not confirm every pin change");
                }
                if ("move_pin_top".equals(action)
                        && response.optInt("reordered_count", -1) != ids.length()) {
                    throw new IllegalStateException("ZenoraChat did not confirm the complete pinned order");
                }
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }

    public static void fetchBasicTextHistory(long roomId, int limit, Callback callback) {
        fetchBasicTextHistory(roomId, limit, 0L, callback);
    }

    public static void fetchBasicTextHistory(long roomId, int limit, long beforeMessageId,
                                             Callback callback) {
        if (roomId <= 0 || beforeMessageId < 0) {
            dispatch(callback, null, new IllegalArgumentException("Invalid Zenora history cursor"));
            return;
        }
        final int safeLimit = Math.max(1, Math.min(50, limit));
        executor.execute(() -> {
            try {
                String path = "messages_fetch.php?room_id=" + roomId
                        + "&format=basic_text&limit=" + safeLimit;
                if (beforeMessageId > 0) {
                    path += "&before_message_id=" + beforeMessageId;
                }
                JSONObject response = request(path, "GET", null);
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }
    /** Fetches a bounded page of newly committed text messages. */
    public static void fetchBasicTextMessagesAfter(long roomId, long afterMessageId,
                                                    int limit, Callback callback) {
        if (roomId <= 0 || afterMessageId < 0) {
            dispatch(callback, null, new IllegalArgumentException("Invalid Zenora live-message cursor"));
            return;
        }
        final int safeLimit = Math.max(1, Math.min(50, limit));
        executor.execute(() -> {
            try {
                String path = "messages_fetch.php?room_id=" + roomId
                        + "&format=basic_text&after_id=" + afterMessageId
                        + "&limit=" + safeLimit;
                JSONObject response = request(path, "GET", null);
                dispatch(callback, response, null);
            } catch (Exception e) {
                dispatch(callback, null, e);
            }
        });
    }


    /** Backward-compatible wrapper. */
    public static void sendTextMessage(long roomId, String body, Callback callback) {
        sendTextMessage(roomId, body, UUID.randomUUID().toString(), callback);
    }

    /** Text POST transport is separate from history/live polling and uses the stable operation key. */
    public static void sendTextMessage(long roomId, String body, String clientMessageId, Callback callback) {
        sendTextMessage(roomId, body, clientMessageId, getCurrentUserId(), callback);
    }

    /**
     * The expected user id is captured with the durable outbox row. It prevents
     * work queued by a logged-out account from ever using a subsequent login cookie.
     */
    public static void sendTextMessage(long roomId, String body, String clientMessageId,
                                       long expectedUserId, Callback callback) {
        final String safeBody = body == null ? "" : body;
        if (roomId <= 0 || safeBody.trim().isEmpty()
                || clientMessageId == null || !clientMessageId.matches("[A-Za-z0-9._:-]{8,64}")
                || expectedUserId <= 0 || getCurrentUserId() != expectedUserId
                || !isZenoraSessionEnabled()) {
            dispatch(callback, null, new IllegalArgumentException("Invalid or stale Zenora text-send session"));
            return;
        }
        try {
            textSendExecutor.execute(() -> {
                try {
                    if (getCurrentUserId() != expectedUserId) {
                        throw new IllegalStateException("Zenora account changed before text send");
                    }
                    JSONObject response = sendTextMessageWithRetry(
                            roomId, safeBody, clientMessageId, expectedUserId);
                    JSONObject message = response.optJSONObject("message");
                    if (message == null) {
                        throw new IllegalStateException("Zenora returned an incomplete sent-message response");
                    }
                    long messageId = message.optLong("message_id", 0L);
                    String createdAt = message.optString("created_at", "");
                    if (messageId <= 0L || messageId > Integer.MAX_VALUE
                            || message.optLong("room_id", 0L) != roomId
                            || message.optLong("sender_id", 0L) != expectedUserId
                            || !safeBody.equals(message.optString("text", ""))
                            || !clientMessageId.equals(message.optString("client_message_id", ""))
                            || createdAt.isEmpty()) {
                        throw new IllegalStateException("Zenora returned a mismatched sent-message record");
                    }
                    response.put("id", messageId);
                    response.put("room_id", roomId);
                    dispatch(callback, response, null);
                } catch (Exception error) {
                    dispatch(callback, null, error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            dispatch(callback, null, new IllegalStateException(
                    "Zenora text-send queue is full; the durable message remains queued.", rejected));
        }
    }

    /**
     * Retries only network I/O failures and HTTP 5xx responses. The body and
     * client_message_id never change during this send operation.
     */
    private static JSONObject sendTextMessageWithRetry(long roomId, String body,
                                                       String clientMessageId,
                                                       long expectedUserId) throws Exception {
        String formWithoutCsrf = "room_id=" + encode(String.valueOf(roomId))
                + "&body=" + encode(body)
                + "&client_message_id=" + encode(clientMessageId);
        String csrf = getMuteActionCsrfToken();
        int transientRetries = 0;
        boolean csrfRetryUsed = false;

        while (true) {
            if (getCurrentUserId() != expectedUserId) {
                throw new IllegalStateException("Zenora account changed while retrying a text message");
            }
            String form = formWithoutCsrf + "&csrf=" + encode(csrf);
            try {
                return request("message_send.php", "POST", form, true, expectedUserId);
            } catch (ApiException error) {
                if ("CSRF_INVALID".equals(error.errorCode) && !csrfRetryUsed) {
                    FileLog.w("[ZenoraChat] message_send.php rejected CSRF; refreshing and retrying the same message key.");
                    csrf = refreshMuteActionCsrfToken();
                    csrfRetryUsed = true;
                    continue;
                }
                if (error.statusCode < 500 || transientRetries >= 2) {
                    throw error;
                }
                transientRetries++;
                FileLog.w("[ZenoraChat] message_send.php returned HTTP " + error.statusCode
                        + "; retry " + transientRetries + " with the same client_message_id.");
            } catch (IOException error) {
                if (transientRetries >= 2) {
                    throw error;
                }
                transientRetries++;
                FileLog.w("[ZenoraChat] message_send.php network failure; retry "
                        + transientRetries + " with the same client_message_id.");
            }

            try {
                Thread.sleep(350L * transientRetries);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Text-message retry was interrupted", interrupted);
            }
        }
    }

    public static void fetchUsers(Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject response = request("users_list.php", "GET", null);
                dispatch(callback, response, null);
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
        return request(path, method, formBody, false, 0L);
    }

    private static JSONObject request(String path, String method, String formBody,
                                      boolean preserveAuthCookieOnUnauthorized) throws Exception {
        return request(path, method, formBody, preserveAuthCookieOnUnauthorized, 0L);
    }

    private static JSONObject request(String path, String method, String formBody,
                                      boolean preserveAuthCookieOnUnauthorized,
                                      long expectedUserId) throws Exception {
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
            String cookie;
            synchronized (cookieLock) {
                if (expectedUserId > 0 && prefs().getLong("user_id", 0L) != expectedUserId) {
                    throw new IllegalStateException("Zenora account changed before HTTP request");
                }
                cookie = prefs().getString(COOKIE_KEY, "");
            }
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
            saveCookies(connection, preserveAuthCookieOnUnauthorized && status == 401, expectedUserId);
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
        saveCookies(connection, preserveAuthCookieOnClear, 0L);
    }

    private static void saveCookies(HttpURLConnection connection, boolean preserveAuthCookieOnClear,
                                    long expectedUserId) {
        synchronized (cookieLock) {
            if (expectedUserId > 0 && prefs().getLong("user_id", 0L) != expectedUserId) {
                return;
            }
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
