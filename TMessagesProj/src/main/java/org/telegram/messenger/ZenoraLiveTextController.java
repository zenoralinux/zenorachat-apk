package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Short-polls only the ChatActivity whose native initial text history is active.
 * Delivery is performed through Telegram's existing in-memory and storage models.
 */
public final class ZenoraLiveTextController {
    private static final Object lock = new Object();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Map<Integer, State> activeByAccount = new HashMap<>();
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long BACKLOG_INTERVAL_MS = 150L;
    private static final int PAGE_LIMIT = 50;
    private static boolean foreground = true;

    private ZenoraLiveTextController() {
    }

    private static final class State {
        final int account;
        final long dialogId;
        final long roomId;
        final Runnable pollTask;
        long cursor;
        long generation;
        boolean inFlight;

        State(int account, long dialogId, long roomId, long cursor) {
            this.account = account;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.cursor = Math.max(0L, cursor);
            pollTask = () -> poll(this);
        }
    }

    public static void start(int account, long dialogId, long roomId, long newestMessageId) {
        if (account < 0 || dialogId == 0 || roomId <= 0
                || !ZenoraChatController.isZenoraEnabled()
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
            return;
        }
        synchronized (lock) {
            State previous = activeByAccount.remove(account);
            if (previous != null) {
                previous.generation++;
                handler.removeCallbacks(previous.pollTask);
            }
            State state = new State(account, dialogId, roomId, newestMessageId);
            activeByAccount.put(account, state);
            if (foreground) handler.postDelayed(state.pollTask, POLL_INTERVAL_MS);
        }
        FileLog.d("[ZenoraChat] Live text polling started; dialog=" + dialogId
                + ", after=" + newestMessageId);
    }

    /** Called by ChatActivity.onFragmentDestroy through the existing history hook. */
    public static void stop(int account, long dialogId) {
        synchronized (lock) {
            State state = activeByAccount.get(account);
            if (state != null && state.dialogId == dialogId) {
                activeByAccount.remove(account);
                state.generation++;
                handler.removeCallbacks(state.pollTask);
            }
        }
    }

    public static void stopAccount(int account) {
        synchronized (lock) {
            State state = activeByAccount.remove(account);
            if (state != null) {
                state.generation++;
                handler.removeCallbacks(state.pollTask);
            }
        }
    }

    public static void pause() {
        synchronized (lock) {
            foreground = false;
            for (State state : activeByAccount.values()) {
                state.generation++;
                state.inFlight = false;
                handler.removeCallbacks(state.pollTask);
            }
        }
    }

    public static void resume() {
        synchronized (lock) {
            foreground = true;
            for (State state : activeByAccount.values()) {
                if (!state.inFlight) {
                    handler.removeCallbacks(state.pollTask);
                    handler.post(state.pollTask);
                }
            }
        }
    }

    private static void poll(State state) {
        final long requestGeneration;
        synchronized (lock) {
            if (activeByAccount.get(state.account) != state || !foreground || state.inFlight) return;
            if (!ZenoraChatController.isZenoraEnabled()
                    || ZenoraChatController.getRoomIdForDialog(state.account, state.dialogId) != state.roomId) {
                activeByAccount.remove(state.account);
                state.generation++;
                return;
            }
            state.inFlight = true;
            requestGeneration = ++state.generation;
        }

        ZenoraApiClient.fetchBasicTextMessagesAfter(
                state.roomId, PAGE_LIMIT, state.cursor, (response, error) -> {
                    synchronized (lock) {
                        if (activeByAccount.get(state.account) != state
                                || state.generation != requestGeneration || !foreground) {
                            return;
                        }
                        state.inFlight = false;
                    }

                    if (error != null || response == null || !response.optBoolean("ok", false)) {
                        if (error != null) FileLog.w("[ZenoraChat] Live text polling failed: " + error.getMessage());
                        schedule(state, POLL_INTERVAL_MS);
                        return;
                    }

                    JSONArray messages = response.optJSONArray("messages");
                    JSONObject pagination = response.optJSONObject("pagination");
                    if (messages == null || !isValidPage(messages, pagination, state.roomId, state.cursor)) {
                        FileLog.w("[ZenoraChat] Invalid live text page rejected; dialog=" + state.dialogId);
                        schedule(state, POLL_INTERVAL_MS);
                        return;
                    }

                    long latest;
                    try {
                        latest = appendNativeMessages(state, messages);
                    } catch (Exception applyError) {
                        FileLog.e(applyError);
                        schedule(state, POLL_INTERVAL_MS);
                        return;
                    }

                    synchronized (lock) {
                        if (activeByAccount.get(state.account) != state
                                || state.generation != requestGeneration || !foreground) return;
                        state.cursor = Math.max(state.cursor, latest);
                    }
                    boolean hasMore = pagination.optBoolean("has_more_newer", false);
                    schedule(state, hasMore ? BACKLOG_INTERVAL_MS : POLL_INTERVAL_MS);
                });
    }

    private static boolean isValidPage(JSONArray messages, JSONObject pagination,
                                       long roomId, long afterId) {
        if (pagination == null || messages.length() > PAGE_LIMIT) return false;
        boolean hasMore = pagination.optBoolean("has_more_newer", false);
        long newest = pagination.optLong("newest_message_id", 0L);
        if (messages.length() == 0) return !hasMore && newest <= 0;
        long previous = afterId;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || row.optLong("room_id", 0L) != roomId || row.isNull("text")) return false;
            long id = row.optLong("message_id", 0L);
            long senderId = row.optLong("sender_id", 0L);
            if (id <= previous || id > Integer.MAX_VALUE || senderId <= 0 || senderId > Integer.MAX_VALUE
                    || TextUtils.isEmpty(row.optString("text", "").trim())
                    || ZenoraChatController.parseHistoryDate(row.optString("created_at", "")) <= 0) {
                return false;
            }
            previous = id;
        }
        return newest == previous && (!hasMore || messages.length() == PAGE_LIMIT);
    }

    private static long appendNativeMessages(State state, JSONArray rows) {
        MessagesController controller = MessagesController.getInstance(state.account);
        TLRPC.Dialog dialog = controller.getDialog(state.dialogId);
        if (dialog == null || dialog.peer == null) {
            throw new IllegalStateException("Native dialog peer is unavailable");
        }
        long selfId = UserConfig.getInstance(state.account).getClientUserId();
        if (selfId <= 0 || selfId > Integer.MAX_VALUE) {
            throw new IllegalStateException("Zenora user identity is unavailable");
        }

        ArrayList<MessageObject> objects = new ArrayList<>();
        ArrayList<TLRPC.Message> stored = new ArrayList<>();
        long newestSeen = state.cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw new IllegalStateException("Null live message row");
            int messageId = (int) row.optLong("message_id", 0L);
            long senderId = row.optLong("sender_id", 0L);
            String body = row.optString("text", "");
            int date = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
            if (messageId <= 0 || senderId <= 0 || TextUtils.isEmpty(body.trim()) || date <= 0) {
                throw new IllegalStateException("Invalid live message model");
            }
            newestSeen = Math.max(newestSeen, messageId);

            // A send response can race this poll. Existing native IDs are authoritative:
            // skip them and advance the cursor rather than publishing a second bubble.
            if (controller.dialogMessagesByIds.get(messageId) != null) continue;

            TLRPC.User sender = controller.getUser(senderId);
            if (sender == null) {
                sender = ZenoraChatController.makeHistorySenderUser(
                        senderId, "", "Zenora user " + senderId, false);
            }
            if (sender == null) throw new IllegalStateException("Could not map live sender");
            controller.putUser(sender, false, true);

            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = messageId;
            message.date = date;
            message.dialog_id = state.dialogId;
            message.peer_id = dialog.peer;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = senderId;
            message.message = body;
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = senderId == selfId;
            message.unread = false;
            message.flags |= 1 << 8;
            if (message.out) message.flags |= 1 << 1;
            objects.add(new MessageObject(state.account, message, true, true));
            stored.add(message);
        }

        if (!objects.isEmpty()) {
            controller.updateInterfaceWithMessages(state.dialogId, objects, 0);
            MessagesStorage.getInstance(state.account).putMessages(
                    stored, false, true, false, 0, 0, 0);
            ZenoraHistoryController.invalidateCachedSnapshot(state.account, state.dialogId);
            FileLog.d("[ZenoraChat] Native live text messages appended; dialog=" + state.dialogId
                    + ", count=" + objects.size());
        }
        return newestSeen;
    }

    private static void schedule(State state, long delayMs) {
        synchronized (lock) {
            if (activeByAccount.get(state.account) != state || !foreground || state.inFlight) return;
            handler.removeCallbacks(state.pollTask);
            handler.postDelayed(state.pollTask, Math.max(0L, delayMs));
        }
    }
}
