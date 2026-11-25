package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Loads the first bounded Zenora text-history page and hands it to Telegram's
 * existing MessagesController/ChatActivity pipeline. This class adds no UI.
 */
public final class ZenoraHistoryController {
    private static final Object lock = new Object();
    private static final Map<Integer, Map<Long, Snapshot>> cache = new HashMap<>();
    private static final Map<Integer, Map<Long, ArrayList<Target>>> inFlight = new HashMap<>();
    private static final Map<Integer, Map<Long, OlderState>> olderStates = new HashMap<>();
    private static final Map<Integer, HashSet<Long>> pendingTextSends = new HashMap<>();
    private static final Map<Integer, Map<Long, HashSet<Long>>> sentMessageIds = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000L;
    private static final long RETRY_COOLDOWN_MS = 1_200L;
    private static final int CACHE_MAX_DIALOGS = 24;

    public interface TextSendCallback {
        void onComplete(boolean success, String message);
    }

    private ZenoraHistoryController() {
    }

    private static final class Target {
        final int classGuid;
        final int loadIndex;
        final boolean olderPage;
        final long beforeMessageId;
        final boolean hasMoreOlder;

        Target(int classGuid, int loadIndex) {
            this(classGuid, loadIndex, false, 0L, false);
        }

        Target(int classGuid, int loadIndex, boolean olderPage,
               long beforeMessageId, boolean hasMoreOlder) {
            this.classGuid = classGuid;
            this.loadIndex = loadIndex;
            this.olderPage = olderPage;
            this.beforeMessageId = beforeMessageId;
            this.hasMoreOlder = hasMoreOlder;
        }
    }

    private static final class Snapshot {
        final long roomId;
        final JSONArray messages;
        final JSONArray users;
        final boolean hasMoreOlder;
        final long oldestMessageId;
        final long fetchedAt;

        Snapshot(long roomId, JSONArray messages, JSONArray users,
                 boolean hasMoreOlder, long oldestMessageId) {
            this.roomId = roomId;
            this.messages = messages;
            this.users = users;
            this.hasMoreOlder = hasMoreOlder;
            this.oldestMessageId = oldestMessageId;
            this.fetchedAt = SystemClock.elapsedRealtime();
        }
    }

    private static final class OlderState {
        final long roomId;
        final HashSet<Integer> seenMessageIds = new HashSet<>();
        JSONArray users;
        long oldestMessageId;
        long requestId;
        long retryAfterMs;
        boolean hasMoreOlder;
        boolean loading;
        int loadingClassGuid;

        OlderState(Snapshot snapshot) {
            roomId = snapshot.roomId;
            users = snapshot.users;
            oldestMessageId = snapshot.oldestMessageId;
            hasMoreOlder = snapshot.hasMoreOlder;
            rememberMessageIds(seenMessageIds, snapshot.messages);
        }
    }

    public static void clearAccount(int account) {
        synchronized (lock) {
            cache.remove(account);
            inFlight.remove(account);
            olderStates.remove(account);
            pendingTextSends.remove(account);
            sentMessageIds.remove(account);
        }
    }

    /**
     * Invalidates callbacks owned by a ChatActivity that is being destroyed.
     * Other waiters for a coalesced initial request are left intact.
     */
    public static void cancelDialogRequests(int account, long dialogId, int classGuid) {
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests = inFlight.get(account);
            ArrayList<Target> waiters = accountRequests == null ? null : accountRequests.get(dialogId);
            if (waiters != null) {
                for (int i = waiters.size() - 1; i >= 0; i--) {
                    if (waiters.get(i).classGuid == classGuid) waiters.remove(i);
                }
                if (waiters.isEmpty()) accountRequests.remove(dialogId);
                if (accountRequests.isEmpty()) inFlight.remove(account);
            }

            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState state = accountOlder == null ? null : accountOlder.get(dialogId);
            if (state != null && state.loading && state.loadingClassGuid == classGuid) {
                state.requestId++;
                state.loading = false;
                state.loadingClassGuid = 0;
            }
        }
    }

    public static void loadInitialHistory(int account, long dialogId, int classGuid,
                                          int loadIndex, int requestedLimit) {
        final Target target = new Target(classGuid, loadIndex);
        final int limit = Math.max(1, Math.min(50, requestedLimit));
        final long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) {
        synchronized (lock) {
            Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
            if (accountSent != null) accountSent.remove(dialogId);
        }
            publish(account, dialogId, 0, target, null,
                    new IllegalStateException("Zenora dialog is no longer available"), limit);
            return;
        }

        Snapshot cachedSnapshot;
        boolean startRequest = false;
        synchronized (lock) {
            Map<Long, Snapshot> accountCache = cache.get(account);
            Snapshot candidate = accountCache == null ? null : accountCache.get(dialogId);
            if (candidate != null && candidate.roomId == roomId
                    && SystemClock.elapsedRealtime() - candidate.fetchedAt <= CACHE_TTL_MS) {
                cachedSnapshot = candidate;
            } else {
                if (candidate != null && accountCache != null) accountCache.remove(dialogId);
                Map<Long, OlderState> accountOlder = olderStates.get(account);
                if (accountOlder != null) accountOlder.remove(dialogId);
                cachedSnapshot = null;
                Map<Long, ArrayList<Target>> accountRequests =
                        inFlight.computeIfAbsent(account, ignored -> new HashMap<>());
                ArrayList<Target> waiters = accountRequests.get(dialogId);
                if (waiters == null) {
                    waiters = new ArrayList<>();
                    accountRequests.put(dialogId, waiters);
                    startRequest = true;
                }
                waiters.add(target);
            }
        }

        if (cachedSnapshot != null) {
            synchronized (lock) {
                Map<Long, OlderState> accountOlder =
                        olderStates.computeIfAbsent(account, ignored -> new HashMap<>());
                // A fresh ChatActivity receives the cached initial page, so its cursor
                // must also restart at that page's oldest ID (not a prior visit's cursor).
                accountOlder.put(dialogId, new OlderState(cachedSnapshot));
            }
            publish(account, dialogId, roomId, target, cachedSnapshot, null, limit);
            return;
        }
        if (!startRequest) return;

        ZenoraApiClient.fetchBasicTextHistory(roomId, limit, (response, error) -> {
            if (error != null || response == null) {
                complete(account, dialogId, roomId, null,
                        error != null ? error : new IllegalStateException("History response is empty"),
                        limit);
                return;
            }
            JSONArray messages = response.optJSONArray("messages");
            JSONObject pagination = response.optJSONObject("pagination");
            if (messages == null || !isValidBasicTextPage(messages, pagination, roomId, limit, 0L)) {
                complete(account, dialogId, roomId, null,
                        new IllegalStateException("Initial text history page is malformed"), limit);
                return;
            }
            boolean hasMoreOlder = pagination.optBoolean("has_more_older", false);
            long oldestMessageId = pagination.optLong("oldest_message_id", 0L);

            // Names are fetched separately because the stable text-history contract
            // intentionally returns sender IDs only. Failure here does not lose messages.
            ZenoraApiClient.fetchUsers((usersResponse, usersError) -> {
                JSONArray users = usersResponse == null ? null : usersResponse.optJSONArray("users");
                if (usersError != null) {
                    FileLog.w("[ZenoraChat] Sender directory unavailable: " + usersError.getMessage());
                }
                complete(account, dialogId, roomId,
                        new Snapshot(roomId, messages, users, hasMoreOlder, oldestMessageId), null, limit);
            });
        });
    }

    /**
     * Starts one older-text page. Returning false means there is no eligible request;
     * callers should keep Telegram's native loading gate untouched.
     */
    public static boolean loadOlderHistory(int account, long dialogId, int classGuid,
                                           int loadIndex, int requestedLimit) {
        final long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) return false;

        final int limit = Math.max(1, Math.min(50, requestedLimit));
        final OlderState state;
        final long beforeMessageId;
        final long requestId;
        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            state = accountOlder == null ? null : accountOlder.get(dialogId);
            long now = SystemClock.elapsedRealtime();
            if (state == null || state.roomId != roomId || !state.hasMoreOlder
                    || state.loading || now < state.retryAfterMs
                    || state.oldestMessageId <= 0) {
                return false;
            }
            state.loading = true;
            state.loadingClassGuid = classGuid;
            state.requestId++;
            requestId = state.requestId;
            beforeMessageId = state.oldestMessageId;
        }

        ZenoraApiClient.fetchBasicTextHistory(roomId, limit, beforeMessageId, (response, error) -> {
            if (!isCurrentOlderRequest(account, dialogId, roomId, state, requestId)) return;
            if (error != null || response == null) {
                finishOlderFailure(account, dialogId, roomId, classGuid, loadIndex,
                        beforeMessageId, state, requestId,
                        error != null ? error : new IllegalStateException("Older history response is empty"),
                        limit);
                return;
            }

            JSONArray messages = response.optJSONArray("messages");
            JSONObject pagination = response.optJSONObject("pagination");
            if (messages == null || !isValidBasicTextPage(
                    messages, pagination, roomId, limit, beforeMessageId)) {
                finishOlderFailure(account, dialogId, roomId, classGuid, loadIndex,
                        beforeMessageId, state, requestId,
                        new IllegalStateException("Older history page or cursor metadata is invalid"),
                        limit);
                return;
            }

            if (pageOverlapsLoadedState(account, dialogId, state, messages)) {
                finishOlderFailure(account, dialogId, roomId, classGuid, loadIndex,
                        beforeMessageId, state, requestId,
                        new IllegalStateException("Older history page overlaps previously loaded message IDs"),
                        limit);
                return;
            }
            boolean hasMoreOlder = pagination.optBoolean("has_more_older", false);
            long oldestMessageId = pagination.optLong("oldest_message_id", 0L);
            Snapshot page = new Snapshot(roomId, messages, state.users,
                    hasMoreOlder, oldestMessageId);
            Target target = new Target(classGuid, loadIndex, true, beforeMessageId, hasMoreOlder);
            boolean published = publish(account, dialogId, roomId, target, page, null, limit);
            if (!published || !isCurrentOlderRequest(account, dialogId, roomId, state, requestId)) {
                finishOlderFailure(account, dialogId, roomId, classGuid, loadIndex,
                        beforeMessageId, state, requestId,
                        new IllegalStateException("Older history page could not be applied to native history"),
                        limit);
                return;
            }

            synchronized (lock) {
                Map<Long, OlderState> accountOlder = olderStates.get(account);
                OlderState current = accountOlder == null ? null : accountOlder.get(dialogId);
                if (current != state || current.requestId != requestId) return;
                rememberMessageIds(current.seenMessageIds, messages);
                if (messages.length() > 0) {
                    current.oldestMessageId = oldestMessageId;
                }
                current.hasMoreOlder = hasMoreOlder;
                current.loading = false;
                current.loadingClassGuid = 0;
                current.retryAfterMs = 0L;
            }
            FileLog.d("[ZenoraChat] Older page committed; dialog=" + dialogId
                    + ", before=" + beforeMessageId + ", oldest=" + oldestMessageId
                    + ", count=" + messages.length() + ", more=" + hasMoreOlder);
        });
        return true;
    }


    /**
     * Sends plain text to the mapped Zenora room. Publishes only the real server ID
     * and never advances the before_message_id cursor used for older history.
     */
    public static boolean sendTextMessage(int account, long dialogId, long roomId,
                                          String body, TextSendCallback callback) {
        if (dialogId == 0 || roomId <= 0 || body == null || body.trim().isEmpty()
                || !ZenoraChatController.isZenoraEnabled()
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
            return false;
        }
        synchronized (lock) {
            HashSet<Long> pending = pendingTextSends.computeIfAbsent(
                    account, ignored -> new HashSet<>());
            if (!pending.add(dialogId)) return false;
        }

        ZenoraApiClient.sendTextMessage(roomId, body, (response, error) -> {
            boolean success = false;
            String resultMessage = "";
            try {
                if (error != null) {
                    resultMessage = error.getMessage() == null
                            ? "ZenoraChat could not confirm the message send." : error.getMessage();
                } else {
                    long messageId = response == null ? 0L : response.optLong("id", 0L);
                    if (messageId <= 0L || messageId > Integer.MAX_VALUE) {
                        resultMessage = "ZenoraChat returned an invalid message ID.";
                    } else if (!ZenoraChatController.isZenoraEnabled()
                            || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
                        resultMessage = "The Zenora conversation changed before the response arrived.";
                    } else {
                        boolean published = publishSentTextMessage(
                                account, dialogId, roomId, (int) messageId, body);
                        success = true; // the API has committed the message
                        if (!published) {
                            resultMessage = "Message sent. Reopen the conversation to refresh its display.";
                        }
                    }
                }
            } catch (Exception publishError) {
                FileLog.e(publishError);
                resultMessage = "Message sent, but the native conversation could not refresh.";
                success = true;
            } finally {
                synchronized (lock) {
                    HashSet<Long> pending = pendingTextSends.get(account);
                    if (pending != null) {
                        pending.remove(dialogId);
                        if (pending.isEmpty()) pendingTextSends.remove(account);
                    }
                }
            }
            if (callback != null) callback.onComplete(success, resultMessage);
        });
        return true;
    }

    private static boolean publishSentTextMessage(int account, long dialogId, long roomId,
                                                  int messageId, String body) {
        if (messageId <= 0 || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
            return false;
        }
        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = messagesController.getDialog(dialogId);
        if (dialog == null || dialog.peer == null) {
            FileLog.w("[ZenoraChat] Sent text message has no native dialog: " + dialogId);
            return false;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId <= 0 || selfId > Integer.MAX_VALUE) return false;

        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState state = accountOlder == null ? null : accountOlder.get(dialogId);
            if (state != null && state.roomId == roomId
                    && state.seenMessageIds.contains(messageId)) {
                sentMessageIds.computeIfAbsent(account, ignored -> new HashMap<>())
                        .computeIfAbsent(dialogId, ignored -> new HashSet<>()).add((long) messageId);
                Map<Long, Snapshot> accountCache = cache.get(account);
                if (accountCache != null) accountCache.remove(dialogId);
                return true; // history already published this server ID
            }

            HashSet<Long> appliedIds = sentMessageIds
                    .computeIfAbsent(account, ignored -> new HashMap<>())
                    .computeIfAbsent(dialogId, ignored -> new HashSet<>());
            if (!appliedIds.add((long) messageId)) return true;
            if (appliedIds.size() > 512) {
                appliedIds.clear();
                appliedIds.add((long) messageId);
            }
            if (state != null && state.roomId == roomId) {
                state.seenMessageIds.add(messageId);
                // Keep oldestMessageId/hasMoreOlder unchanged; only older pages own that cursor.
            }
            Map<Long, Snapshot> accountCache = cache.get(account);
            if (accountCache != null) accountCache.remove(dialogId);
        }

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = messageId;
        message.date = (int) (System.currentTimeMillis() / 1000L);
        message.dialog_id = dialogId;
        message.peer_id = dialog.peer;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = selfId;
        message.message = body;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = true;
        message.unread = false;
        message.flags |= 1 << 8;
        message.flags |= 1 << 1;

        MessageObject object = new MessageObject(account, message, true, true);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(object);
        messagesController.updateInterfaceWithMessages(dialogId, objects, 0);

        ArrayList<TLRPC.Message> stored = new ArrayList<>();
        stored.add(message);
        MessagesStorage.getInstance(account).putMessages(
                stored, false, true, false, 0, 0, 0);
        FileLog.d("[ZenoraChat] Text send committed; dialog=" + dialogId
                + ", room=" + roomId + ", message=" + messageId);
        return true;
    }

    /**
     * Returns the newest message ID already loaded or published for this dialog.
     * -1 means that the initial history request has not produced a usable state yet.
     */
    public static long getLatestMessageId(int account, long dialogId, long roomId) {
        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState state = accountOlder == null ? null : accountOlder.get(dialogId);
            if (state == null || state.roomId != roomId) return -1L;
            long latest = 0L;
            for (Integer messageId : state.seenMessageIds) {
                if (messageId != null && messageId > latest) latest = messageId;
            }
            Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
            HashSet<Long> sent = accountSent == null ? null : accountSent.get(dialogId);
            if (sent != null) {
                for (Long messageId : sent) {
                    if (messageId != null && messageId > latest) latest = messageId;
                }
            }
            return latest;
        }
    }

    /**
     * Applies a validated live text page through Telegram's native in-memory
     * update path and queues the same TLRPC messages in MessagesStorage.
     * -1 means validation or native publication failed.
     */
    public static long applyLiveTextMessages(int account, long dialogId, long roomId,
                                             JSONArray messages, long afterMessageId) {
        if (messages == null || messages.length() > 50 || afterMessageId < 0
                || roomId <= 0 || dialogId == 0
                || !ZenoraChatController.isZenoraEnabled()
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
            return -1L;
        }

        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = messagesController.getDialog(dialogId);
        if (dialog == null || dialog.peer == null) return -1L;
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId <= 0 || selfId > Integer.MAX_VALUE) return -1L;

        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<Long> senderIds = new ArrayList<>();
        ArrayList<String> bodies = new ArrayList<>();
        ArrayList<Integer> dates = new ArrayList<>();
        HashSet<Integer> pageIds = new HashSet<>();
        long previousId = afterMessageId;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || row.optLong("room_id", 0L) != roomId || row.isNull("text")) {
                return -1L;
            }
            long rawId = row.optLong("message_id", 0L);
            long senderId = row.optLong("sender_id", 0L);
            String body = row.optString("text", "");
            int date = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
            if (rawId <= previousId || rawId > Integer.MAX_VALUE
                    || senderId <= 0L || senderId > Integer.MAX_VALUE
                    || TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim()) || date <= 0
                    || !pageIds.add((int) rawId)) {
                return -1L;
            }
            ids.add((int) rawId);
            senderIds.add(senderId);
            bodies.add(body);
            dates.add(date);
            previousId = rawId;
        }
        if (ids.isEmpty()) return afterMessageId;
        if (ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId
                || !ZenoraChatController.isZenoraEnabled()) {
            return -1L;
        }

        OlderState state;
        synchronized (lock) {
            Map<Long, OlderState> accountOlder =
                    olderStates.computeIfAbsent(account, ignored -> new HashMap<>());
            state = accountOlder.get(dialogId);
            if (state == null || state.roomId != roomId) {
                state = new OlderState(new Snapshot(roomId, new JSONArray(), null, false, 0L));
                accountOlder.put(dialogId, state);
            }
        }

        HashMap<Long, TLRPC.User> directory = new HashMap<>();
        if (state.users != null) {
            for (int i = 0; i < state.users.length(); i++) {
                JSONObject userRow = state.users.optJSONObject(i);
                if (userRow == null) continue;
                long userId = userRow.optLong("id", 0L);
                if (userId <= 0L || userId == selfId) continue;
                TLRPC.User user = messagesController.getUser(userId);
                if (user == null) {
                    user = ZenoraChatController.makeHistorySenderUser(
                            userId, userRow.optString("username", ""),
                            userRow.optString("display_name", ""),
                            userRow.optInt("is_verified", 0) == 1);
                }
                if (user != null) directory.put(userId, user);
            }
        }

        ArrayList<TLRPC.User> usersToStore = new ArrayList<>();
        HashSet<Long> includedUsers = new HashSet<>();
        ArrayList<MessageObject> objects = new ArrayList<>();
        ArrayList<TLRPC.Message> stored = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int messageId = ids.get(i);
            long senderId = senderIds.get(i);
            boolean alreadyApplied;
            synchronized (lock) {
                alreadyApplied = state.seenMessageIds.contains(messageId);
                Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
                HashSet<Long> sent = accountSent == null ? null : accountSent.get(dialogId);
                alreadyApplied |= sent != null && sent.contains((long) messageId);
            }
            if (alreadyApplied) continue;

            TLRPC.User sender = messagesController.getUser(senderId);
            if (sender == null) sender = directory.get(senderId);
            if (sender == null) {
                sender = ZenoraChatController.makeHistorySenderUser(
                        senderId, "", "Zenora user " + senderId, false);
            }
            if (sender != null && includedUsers.add(senderId)) usersToStore.add(sender);

            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = messageId;
            message.date = dates.get(i);
            message.dialog_id = dialogId;
            message.peer_id = dialog.peer;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = senderId;
            message.message = bodies.get(i);
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = senderId == selfId;
            message.unread = false;
            message.flags |= 1 << 8;
            if (message.out) message.flags |= 1 << 1;
            objects.add(new MessageObject(account, message, true, true));
            stored.add(message);
        }

        try {
            if (!usersToStore.isEmpty()) messagesController.putUsers(usersToStore, false);
            if (!objects.isEmpty()) {
                messagesController.updateInterfaceWithMessages(dialogId, objects, 0);
                MessagesStorage.getInstance(account).putMessages(
                        stored, false, true, false, 0, 0, 0);
            }
        } catch (Exception error) {
            FileLog.e(error);
            return -1L;
        }

        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState current = accountOlder == null ? null : accountOlder.get(dialogId);
            if (current != state || current.roomId != roomId) return -1L;
            for (int i = 0; i < ids.size(); i++) {
                int messageId = ids.get(i);
                current.seenMessageIds.add(messageId);
                if (senderIds.get(i) == selfId) {
                    sentMessageIds.computeIfAbsent(account, ignored -> new HashMap<>())
                            .computeIfAbsent(dialogId, ignored -> new HashSet<>())
                            .add((long) messageId);
                }
            }
        }
        FileLog.d("[ZenoraChat] Live text page applied; dialog=" + dialogId
                + ", room=" + roomId + ", cursor=" + previousId
                + ", returned=" + ids.size() + ", displayed=" + objects.size());
        return previousId;
    }
    private static boolean isCurrentOlderRequest(int account, long dialogId, long roomId,
                                                 OlderState expectedState, long requestId) {
        if (ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId
                || !ZenoraChatController.isZenoraEnabled()) {
            return false;
        }
        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState current = accountOlder == null ? null : accountOlder.get(dialogId);
            return current == expectedState && current.roomId == roomId
                    && current.requestId == requestId && current.loading;
        }
    }

    private static void finishOlderFailure(int account, long dialogId, long roomId,
                                           int classGuid, int loadIndex, long beforeMessageId,
                                           OlderState state, long requestId, Exception error,
                                           int limit) {
        if (!isCurrentOlderRequest(account, dialogId, roomId, state, requestId)) return;
        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState current = accountOlder == null ? null : accountOlder.get(dialogId);
            if (current != state || current.requestId != requestId) return;
            current.loading = false;
            current.loadingClassGuid = 0;
            current.retryAfterMs = SystemClock.elapsedRealtime() + RETRY_COOLDOWN_MS;
        }
        FileLog.e(error);
        // Use a non-terminal native history completion so Telegram clears its loading
        // flag but does not mark the oldest boundary as exhausted.
        publish(account, dialogId, roomId,
                new Target(classGuid, loadIndex, true, beforeMessageId, true),
                null, error, limit);
        FileLog.w("[ZenoraChat] Older history request can be retried; dialog=" + dialogId);
    }

    private static boolean pageOverlapsLoadedState(int account, long dialogId,
                                                    OlderState expectedState, JSONArray messages) {
        synchronized (lock) {
            Map<Long, OlderState> accountOlder = olderStates.get(account);
            OlderState current = accountOlder == null ? null : accountOlder.get(dialogId);
            if (current != expectedState) return true;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null) return true;
                long rawId = row.optLong("message_id", 0L);
                if (rawId > 0 && rawId <= Integer.MAX_VALUE
                        && current.seenMessageIds.contains((int) rawId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean isValidBasicTextPage(JSONArray messages, JSONObject pagination,
                                                long roomId, int limit, long beforeMessageId) {
        if (pagination == null || messages.length() > limit) return false;
        boolean hasMoreOlder = pagination.optBoolean("has_more_older", false);
        long oldestMessageId = pagination.optLong("oldest_message_id", 0L);
        if (messages.length() == 0) {
            return !hasMoreOlder && oldestMessageId <= 0;
        }
        long previousId = 0L;
        long firstId = 0L;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || row.optLong("room_id", 0L) != roomId
                    || row.isNull("text")) return false;
            long messageId = row.optLong("message_id", 0L);
            if (messageId <= previousId || messageId > Integer.MAX_VALUE
                    || beforeMessageId > 0 && messageId >= beforeMessageId
                    || row.optLong("sender_id", 0L) <= 0
                    || row.optLong("sender_id", 0L) > Integer.MAX_VALUE
                    || TextUtils.isEmpty(row.optString("text", "").trim())
                    || ZenoraChatController.parseHistoryDate(row.optString("created_at", "")) <= 0) {
                return false;
            }
            if (i == 0) firstId = messageId;
            previousId = messageId;
        }
        return oldestMessageId == firstId
                && (!hasMoreOlder || messages.length() == limit);
    }

    private static boolean isSentMessageAlreadyApplied(int account, long dialogId, int messageId) {
        synchronized (lock) {
            Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
            HashSet<Long> ids = accountSent == null ? null : accountSent.get(dialogId);
            return ids != null && ids.contains((long) messageId);
        }
    }

    private static void rememberMessageIds(HashSet<Integer> destination, JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null) continue;
            long rawId = row.optLong("message_id", 0L);
            if (rawId > 0 && rawId <= Integer.MAX_VALUE) {
                destination.add((int) rawId);
            }
        }
    }

    private static void complete(int account, long dialogId, long roomId, Snapshot snapshot,
                                 Exception error, int limit) {
        long currentRoomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        boolean stillCurrent = currentRoomId == roomId
                && ZenoraChatController.isZenoraEnabled();
        ArrayList<Target> targets;
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests = inFlight.get(account);
            targets = accountRequests == null ? null : accountRequests.remove(dialogId);
            if (accountRequests != null && accountRequests.isEmpty()) inFlight.remove(account);
            if (snapshot != null && stillCurrent) {
                Map<Long, Snapshot> accountCache =
                        cache.computeIfAbsent(account, ignored -> new HashMap<>());
                accountCache.put(dialogId, snapshot);
                Map<Long, OlderState> accountOlder =
                        olderStates.computeIfAbsent(account, ignored -> new HashMap<>());
                accountOlder.put(dialogId, new OlderState(snapshot));
                while (accountCache.size() > CACHE_MAX_DIALOGS) {
                    Long firstKey = accountCache.keySet().iterator().next();
                    accountCache.remove(firstKey);
                    accountOlder.remove(firstKey);
                }
            }
        }
        if (targets == null) return;

        for (Target target : targets) {
            if (!stillCurrent) {
                FileLog.w("[ZenoraChat] Dropped stale history response for dialog " + dialogId);
            } else {
                publish(account, dialogId, roomId, target, snapshot, error, limit);
            }
        }
    }

    private static boolean publish(int account, long dialogId, long roomId, Target target,
                                   Snapshot snapshot, Exception error, int limit) {
        if (snapshot != null && (snapshot.roomId != roomId
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId)) {
            FileLog.w("[ZenoraChat] Refusing cross-dialog history payload for " + dialogId);
            return false;
        }

        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = messagesController.getDialog(dialogId);
        if (dialog == null || dialog.peer == null) {
            FileLog.w("[ZenoraChat] Native dialog peer is unavailable for " + dialogId);
            return false;
        }
        if (error != null) FileLog.e(error);

        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        HashSet<Long> includedUsers = new HashSet<>();
        HashMap<Long, TLRPC.User> directory = new HashMap<>();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.User self = messagesController.getUser(selfId);
        if (self != null && includedUsers.add(selfId)) result.users.add(self);

        if (snapshot != null && snapshot.users != null) {
            for (int i = 0; i < snapshot.users.length(); i++) {
                JSONObject row = snapshot.users.optJSONObject(i);
                if (row == null) continue;
                long userId = row.optLong("id", 0);
                if (userId <= 0 || userId == selfId) continue;
                TLRPC.User user = messagesController.getUser(userId);
                if (user == null) {
                    user = ZenoraChatController.makeHistorySenderUser(
                            userId,
                            row.optString("username", ""),
                            row.optString("display_name", ""),
                            row.optInt("is_verified", 0) == 1);
                }
                if (user != null) directory.put(userId, user);
            }
        }

        if (dialogId < 0) {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat != null) result.chats.add(chat);
        } else {
            TLRPC.User peer = messagesController.getUser(dialogId);
            if (peer != null && includedUsers.add(peer.id)) result.users.add(peer);
        }

        if (snapshot != null) {
            HashSet<Integer> seenIds = new HashSet<>();
            ArrayList<TLRPC.Message> mapped = new ArrayList<>();
            for (int i = 0; i < snapshot.messages.length(); i++) {
                JSONObject row = snapshot.messages.optJSONObject(i);
                if (row == null || row.optLong("room_id", 0) != roomId || row.isNull("text")) continue;

                long rawId = row.optLong("message_id", 0);
                long senderId = row.optLong("sender_id", 0);
                if (rawId <= 0 || rawId > Integer.MAX_VALUE
                        || senderId <= 0 || senderId > Integer.MAX_VALUE) continue;
                int messageId = (int) rawId;
                if (!seenIds.add(messageId) || isSentMessageAlreadyApplied(account, dialogId, messageId)) continue;

                String body = row.optString("text", "");
                if (TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())) continue;
                int date = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
                if (date <= 0) continue;

                TLRPC.User sender = messagesController.getUser(senderId);
                if (sender == null) sender = directory.get(senderId);
                if (sender == null) {
                    sender = ZenoraChatController.makeHistorySenderUser(
                            senderId, "", "Zenora user " + senderId, false);
                }
                if (sender != null && includedUsers.add(senderId)) result.users.add(sender);

                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = messageId;
                message.date = date;
                message.dialog_id = dialogId;
                message.peer_id = dialog.peer;
                message.message = body;
                message.media = new TLRPC.TL_messageMediaEmpty();
                message.out = senderId == selfId;
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = senderId;
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
                if (message.out) message.flags |= 1 << 1;
                mapped.add(message);
            }

            // ChatActivity's normal reverse-layout adapter expects newest messages first.
            Collections.sort(mapped, (left, right) -> Integer.compare(right.id, left.id));
            result.messages.addAll(mapped);
        }

        result.count = result.messages.size();
        int loadType;
        int nativeCount;
        boolean isEnd;
        if (target.olderPage && error != null) {
            // A failed older-page request must release ChatActivity's native loading
            // gate without setting endReached; the scroll edge can retry after cooldown.
            loadType = MessagesController.LOAD_FROM_UNREAD;
            nativeCount = Math.max(1, Math.min(50, limit));
            isEnd = false;
        } else if (target.olderPage) {
            loadType = MessagesController.LOAD_BACKWARD;
            nativeCount = target.hasMoreOlder
                    ? Math.max(1, Math.min(50, limit))
                    : result.messages.size() + 1;
            isEnd = false;
        } else {
            loadType = MessagesController.LOAD_FROM_UNREAD;
            nativeCount = Math.max(1, Math.min(50, limit));
            isEnd = true;
        }
        messagesController.processLoadedMessages(
                result,
                result.messages.size(),
                dialogId,
                0,
                nativeCount,
                target.olderPage && error == null
                    ? (int) Math.min(Integer.MAX_VALUE, target.beforeMessageId) : 0,
                0,
                false,
                target.classGuid,
                0,
                0,
                target.olderPage ? 0 : Math.max(0, dialog.unread_count),
                target.olderPage ? 0 : dialog.last_message_date,
                loadType,
                isEnd,
                0,
                0L,
                target.loadIndex,
                false,
                0,
                true,
                false,
                null
        );
        if (!target.olderPage) {
            ZenoraLiveMessageSyncController.onHistoryApplied(account, dialogId, roomId, target.classGuid);
        }
        FileLog.d("[ZenoraChat] Native text history page published; dialog="
                + dialogId + ", count=" + result.messages.size()
                + ", older=" + target.olderPage + ", failed=" + (error != null));
        return true;
    }
}
