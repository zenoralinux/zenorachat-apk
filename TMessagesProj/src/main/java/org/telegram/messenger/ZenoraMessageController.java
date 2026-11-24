package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded, account-scoped Zenora message history state.
 *
 * This class deliberately keeps the transport/cache independent from Telegram's
 * rendering classes. ChatActivity integration must consume HistoryListener
 * snapshots and map the resulting records into Telegram's native message/storage
 * pipeline; a dialog preview is never treated as a message history.
 */
public final class ZenoraMessageController {
    public interface HistoryListener {
        void onHistoryChanged(List<JSONObject> messages, boolean initialLoad,
                             boolean hasOlder, boolean hasNewer,
                             JSONObject pinnedMessage, long syncTime,
                             long anchorMessageId, int anchorOffsetPx,
                             String error);
    }

    private static final int PAGE_SIZE = 80;
    private static final int MAX_CACHE_MESSAGES = 400;
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final String PREFS = "zenora_message_history_v1";

    private final int account;
    private final long roomId;
    private final long dialogId;
    private final long userId;
    private final HistoryListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TreeMap<Long, JSONObject> messages = new TreeMap<>();

    private boolean loadingInitial;
    private boolean loadingOlder;
    private boolean polling;
    private boolean visible;
    private boolean destroyed;
    private boolean hasOlder = true;
    private boolean hasNewer;
    private long oldestId;
    private long newestId;
    private long syncTime;
    private long anchorMessageId;
    private int anchorOffsetPx;
    private JSONObject pinnedMessage;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !visible || !polling || !ZenoraApiClient.isZenoraSessionEnabled()) return;
            pollNewMessages();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public ZenoraMessageController(int account, long dialogId, HistoryListener listener) {
        if (account < 0 || dialogId == 0) throw new IllegalArgumentException("Invalid account or dialog");
        this.account = account;
        this.dialogId = dialogId;
        this.listener = listener;
        this.roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        JSONObject profile = ZenoraApiClient.getStoredProfile();
        this.userId = profile == null ? 0 : profile.optLong("id", 0);
        if (roomId <= 0 || userId <= 0) throw new IllegalStateException("Zenora room or account mapping is unavailable");
    }

    public void restoreAndLoad() {
        if (destroyed) return;
        restoreCache();
        if (messages.isEmpty()) loadInitial();
        else {
            notifyListener(false, null);
            pollNewMessages();
        }
    }

    public void loadInitial() {
        if (destroyed || loadingInitial) return;
        loadingInitial = true;
        ZenoraApiClient.fetchMessages(roomId, null, "older", PAGE_SIZE,
                null, null, 0, true, (response, error) -> {
                    loadingInitial = false;
                    if (destroyed) return;
                    if (error != null) {
                        notifyListener(true, error.getMessage());
                        return;
                    }
                    mergeResponse(response, true);
                    notifyListener(true, null);
                    persistCache();
                });
    }

    public void loadOlder() {
        if (destroyed || loadingInitial || loadingOlder || !hasOlder || oldestId <= 0) return;
        loadingOlder = true;
        ZenoraApiClient.fetchMessages(roomId, oldestId, "older", PAGE_SIZE,
                null, null, 0, false, (response, error) -> {
                    loadingOlder = false;
                    if (destroyed) return;
                    if (error != null) {
                        notifyListener(false, error.getMessage());
                        return;
                    }
                    mergeResponse(response, false);
                    notifyListener(false, null);
                    persistCache();
                });
    }

    /** Poll only while this conversation is the visible foreground screen. */
    public void setVisible(boolean value) {
        if (destroyed || visible == value) return;
        visible = value;
        if (visible) {
            polling = true;
            mainHandler.removeCallbacks(pollTask);
            mainHandler.post(pollTask);
        } else {
            polling = false;
            mainHandler.removeCallbacks(pollTask);
        }
    }

    public void pollNewMessages() {
        if (destroyed || !visible || pollingRequestInFlight || newestId <= 0) return;
        pollingRequestInFlight = true;
        ZenoraApiClient.fetchMessages(roomId, null, "newer", 100,
                newestId, null, syncTime, false, (response, error) -> {
                    pollingRequestInFlight = false;
                    if (destroyed) return;
                    if (error != null) {
                        FileLog.w("[ZenoraChat] message history poll failed: " + error.getMessage());
                        return;
                    }
                    mergeResponse(response, false);
                    notifyListener(false, null);
                    persistCache();
                });
    }

    private boolean pollingRequestInFlight;

    public void jumpToMessage(long messageId) {
        if (destroyed || messageId <= 0) return;
        ZenoraApiClient.fetchMessages(roomId, null, "older", PAGE_SIZE,
                null, messageId, 0, false, (response, error) -> {
                    if (destroyed) return;
                    if (error != null) {
                        notifyListener(false, error.getMessage());
                        return;
                    }
                    mergeResponse(response, false);
                    anchorMessageId = messageId;
                    anchorOffsetPx = 0;
                    notifyListener(false, null);
                    persistCache();
                });
    }

    public void saveViewport(long firstVisibleMessageId, int topOffsetPx) {
        if (destroyed) return;
        anchorMessageId = Math.max(0, firstVisibleMessageId);
        anchorOffsetPx = topOffsetPx;
        persistCache();
    }

    public long getAnchorMessageId() { return anchorMessageId; }
    public int getAnchorOffsetPx() { return anchorOffsetPx; }
    public long getOldestMessageId() { return oldestId; }
    public long getNewestMessageId() { return newestId; }

    public void destroy() {
        destroyed = true;
        visible = false;
        polling = false;
        mainHandler.removeCallbacks(pollTask);
    }

    private void mergeResponse(JSONObject response, boolean replaceHistory) {
        if (replaceHistory) messages.clear();
        JSONArray rows = response.optJSONArray("messages");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                long id = row.optLong("id", 0);
                if (id <= 0) continue;
                if (row.optBoolean("is_update_only", false)) {
                    if (row.has("deleted_at") && !row.isNull("deleted_at")
                            && !row.optString("deleted_at", "").isEmpty()) {
                        messages.remove(id);
                    } else if (messages.containsKey(id)) {
                        JSONObject previous = messages.get(id);
                        try {
                            if (row.has("body")) previous.put("body", row.optString("body", ""));
                            if (row.has("edited_at")) previous.put("edited_at", row.optString("edited_at", ""));
                        } catch (Exception ignored) { }
                    }
                } else {
                    messages.put(id, row);
                }
            }
        }

        JSONObject pagination = response.optJSONObject("pagination");
        if (pagination != null) {
            hasOlder = pagination.optBoolean("has_older",
                    pagination.optBoolean("has_more", false));
            hasNewer = pagination.optBoolean("has_newer", false);
        }
        JSONObject pinned = response.optJSONObject("pinned_message");
        if (pinned != null) pinnedMessage = pinned;
        syncTime = Math.max(syncTime, response.optLong("sync_time", 0));
        trimCache();
        oldestId = messages.isEmpty() ? 0 : messages.firstKey();
        newestId = messages.isEmpty() ? 0 : messages.lastKey();
    }

    private void trimCache() {
        while (messages.size() > MAX_CACHE_MESSAGES) {
            messages.pollFirstEntry();
        }
        oldestId = messages.isEmpty() ? 0 : messages.firstKey();
        newestId = messages.isEmpty() ? 0 : messages.lastKey();
    }

    private List<JSONObject> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(messages.values()));
    }

    private void notifyListener(boolean initial, String error) {
        if (listener != null) {
            listener.onHistoryChanged(snapshot(), initial, hasOlder, hasNewer,
                    pinnedMessage, syncTime, anchorMessageId, anchorOffsetPx, error);
        }
    }

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String cacheKey() {
        return "account_" + account + "_user_" + userId + "_room_" + roomId;
    }

    private void persistCache() {
        if (ApplicationLoader.applicationContext == null || userId <= 0) return;
        try {
            JSONObject state = new JSONObject();
            JSONArray rows = new JSONArray();
            for (JSONObject message : messages.values()) rows.put(message);
            state.put("user_id", userId);
            state.put("room_id", roomId);
            state.put("messages", rows);
            state.put("has_older", hasOlder);
            state.put("has_newer", hasNewer);
            state.put("oldest_id", oldestId);
            state.put("newest_id", newestId);
            state.put("sync_time", syncTime);
            state.put("anchor_message_id", anchorMessageId);
            state.put("anchor_offset_px", anchorOffsetPx);
            if (pinnedMessage != null) state.put("pinned_message", pinnedMessage);
            preferences().edit().putString(cacheKey(), state.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void restoreCache() {
        if (ApplicationLoader.applicationContext == null || userId <= 0) return;
        String raw = preferences().getString(cacheKey(), "");
        if (raw.isEmpty()) return;
        try {
            JSONObject state = new JSONObject(raw);
            if (state.optLong("user_id", 0) != userId
                    || state.optLong("room_id", 0) != roomId) return;
            JSONArray rows = state.optJSONArray("messages");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && row.optLong("id", 0) > 0) {
                        messages.put(row.optLong("id"), row);
                    }
                }
            }
            hasOlder = state.optBoolean("has_older", true);
            hasNewer = state.optBoolean("has_newer", false);
            syncTime = state.optLong("sync_time", 0);
            anchorMessageId = state.optLong("anchor_message_id", 0);
            anchorOffsetPx = state.optInt("anchor_offset_px", 0);
            pinnedMessage = state.optJSONObject("pinned_message");
            trimCache();
        } catch (Exception e) {
            FileLog.w("[ZenoraChat] invalid message history cache; discarding it: " + e.getMessage());
            preferences().edit().remove(cacheKey()).apply();
            messages.clear();
        }
    }
}
