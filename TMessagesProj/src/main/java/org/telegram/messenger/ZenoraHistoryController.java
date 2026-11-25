package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
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
    private static final Map<Integer, Map<Long, ArrayDeque<PendingTextSend>>> pendingTextQueues = new HashMap<>();
    private static final Map<Integer, HashSet<Long>> activeTextSendRooms = new HashMap<>();
    private static final Map<Integer, Map<Long, HashMap<String, PendingTextSend>>> pendingTextByClientId = new HashMap<>();
    private static final Map<Integer, Map<Long, HashMap<String, Integer>>> completedTextByClientId = new HashMap<>();
    private static final int MAX_PENDING_TEXT_SENDS_PER_ROOM = 64;
    private static final Map<Integer, Map<Long, HashSet<Long>>> sentMessageIds = new HashMap<>();
    private static final Map<Integer, Map<Long, HashSet<Long>>> pendingSentMessageIds = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000L;
    private static final long RETRY_COOLDOWN_MS = 1_200L;
    private static final int CACHE_MAX_DIALOGS = 24;

    public interface TextSendCallback {
        void onComplete(boolean success, String message);
    }

    private static final class PendingTextSend {
        final int account;
        final long dialogId;
        final long roomId;
        final int localId;
        final long randomId;
        final String clientMessageId;
        final String body;
        final MessageObject messageObject;
        final TextSendCallback callback;
        final java.util.concurrent.atomic.AtomicBoolean resolved =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile boolean failed;
        volatile boolean started;

        PendingTextSend(int account, long dialogId, long roomId, int localId, long randomId,
                        String clientMessageId, String body, MessageObject messageObject,
                        TextSendCallback callback) {
            this.account = account;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.localId = localId;
            this.randomId = randomId;
            this.clientMessageId = clientMessageId;
            this.body = body;
            this.messageObject = messageObject;
            this.callback = callback;
        }
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
            pendingTextQueues.remove(account);
            activeTextSendRooms.remove(account);
            pendingTextByClientId.remove(account);
            completedTextByClientId.remove(account);
            sentMessageIds.remove(account);
            pendingSentMessageIds.remove(account);
        }
    }

    /**
     * Starts a fresh ChatActivity history session for this dialog. IDs published
     * locally in a previous activity must not suppress the server's history page.
     */
    private static void resetDialogSendDeduplication(int account, long dialogId) {
        synchronized (lock) {
            Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
            if (accountSent != null) {
                accountSent.remove(dialogId);
                if (accountSent.isEmpty()) sentMessageIds.remove(account);
            }
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
        resetDialogSendDeduplication(account, dialogId);
        final Target target = new Target(classGuid, loadIndex);
        final int limit = Math.max(1, Math.min(50, requestedLimit));
        final long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) {
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


    /** Optimistic local publish followed by a bounded per-room FIFO PHP send queue. */
    public static boolean sendTextMessage(int account, long dialogId, long roomId,
                                          String body, TextSendCallback callback) {
        if (dialogId == 0 || roomId <= 0 || body == null || body.trim().isEmpty()
                || !ZenoraChatController.isZenoraEnabled()
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) return false;
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.getDialog(dialogId);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (dialog == null || dialog.peer == null || selfId <= 0 || selfId > Integer.MAX_VALUE) return false;

        int localId = UserConfig.getInstance(account).getNewMessageId();
        long randomId = Utilities.random.nextLong();
        while (randomId == 0L) randomId = Utilities.random.nextLong();
        String clientMessageId = java.util.UUID.randomUUID().toString();

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = localId;
        message.local_id = localId;
        message.random_id = randomId;
        message.dialog_id = dialogId;
        message.peer_id = dialog.peer;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = selfId;
        message.date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.message = body;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = true;
        message.unread = true;
        message.flags |= 1 << 8;
        message.flags |= 1 << 1;
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        message.params = new HashMap<>();
        message.params.put("zenora_client_message_id", clientMessageId);
        message.params.put("zenora_room_id", String.valueOf(roomId));

        MessageObject object = new MessageObject(account, message, false, true);
        object.wasJustSent = true;
        PendingTextSend pending = new PendingTextSend(account, dialogId, roomId, localId,
                randomId, clientMessageId, body, object, callback);
        boolean startImmediately;
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = pendingTextQueues
                    .computeIfAbsent(account, ignored -> new HashMap<>())
                    .computeIfAbsent(roomId, ignored -> new ArrayDeque<>());
            if (queue.size() >= MAX_PENDING_TEXT_SENDS_PER_ROOM) return false;
            queue.addLast(pending);
            pendingTextByClientId.computeIfAbsent(account, ignored -> new HashMap<>())
                    .computeIfAbsent(dialogId, ignored -> new HashMap<>()).put(clientMessageId, pending);
            startImmediately = activeTextSendRooms.computeIfAbsent(account, ignored -> new HashSet<>())
                    .add(roomId);
        }
        try {
            ArrayList<TLRPC.Message> stored = new ArrayList<>();
            stored.add(message);
            MessagesStorage.getInstance(account).putMessages(stored, false, true, false, 0, 0, 0);
            ArrayList<MessageObject> objects = new ArrayList<>();
            objects.add(object);
            controller.updateInterfaceWithMessages(dialogId, objects, 0);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            UserConfig.getInstance(account).saveConfig(false);
        } catch (Exception error) {
            FileLog.e(error);
            removeQueuedTextSend(pending);
            return false;
        }
        if (startImmediately) startTextSendRequest(pending);
        return true;
    }

    /** Intercepts Telegram's native retry gesture; Zenora messages never enter MTProto. */
    public static boolean retryTextMessage(int account, MessageObject object) {
        if (object == null || object.messageOwner == null) return false;
        TLRPC.Message message = object.messageOwner;
        long dialogId = object.getDialogId();
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        String clientId = message.params == null ? null : message.params.get("zenora_client_message_id");
        if (roomId <= 0 || TextUtils.isEmpty(clientId) || TextUtils.isEmpty(message.message)
                || !(message.media instanceof TLRPC.TL_messageMediaEmpty)
                || message.action != null || message.reply_to != null || object.scheduled) return false;
        long randomId = message.random_id;
        if (randomId == 0L) {
            randomId = Utilities.random.nextLong();
            while (randomId == 0L) randomId = Utilities.random.nextLong();
            message.random_id = randomId;
        }
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        object.forceUpdate = true;
        PendingTextSend pending = new PendingTextSend(account, dialogId, roomId, message.id,
                randomId, clientId, message.message, object, null);
        boolean startImmediately;
        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> pendingByDialog = pendingTextByClientId.get(account);
            HashMap<String, PendingTextSend> existingMap =
                    pendingByDialog == null ? null : pendingByDialog.get(dialogId);
            PendingTextSend existing = existingMap == null ? null : existingMap.get(clientId);
            if (existing != null && !existing.failed && !existing.resolved.get()) return false;
            ArrayDeque<PendingTextSend> queue = pendingTextQueues
                    .computeIfAbsent(account, ignored -> new HashMap<>())
                    .computeIfAbsent(roomId, ignored -> new ArrayDeque<>());
            if (queue.size() >= MAX_PENDING_TEXT_SENDS_PER_ROOM) return false;
            queue.addLast(pending);
            pendingTextByClientId.computeIfAbsent(account, ignored -> new HashMap<>())
                    .computeIfAbsent(dialogId, ignored -> new HashMap<>()).put(clientId, pending);
            startImmediately = activeTextSendRooms.computeIfAbsent(account, ignored -> new HashSet<>())
                    .add(roomId);
        }
        ArrayList<TLRPC.Message> stored = new ArrayList<>();
        stored.add(message);
        MessagesStorage.getInstance(account).putMessages(stored, false, true, false, 0, 0, 0);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(object);
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.replaceMessagesObjects, dialogId, objects);
        if (startImmediately) startTextSendRequest(pending);
        return true;
    }

    private static ArrayDeque<PendingTextSend> getTextQueueLocked(int account, long roomId) {
        Map<Long, ArrayDeque<PendingTextSend>> queues = pendingTextQueues.get(account);
        return queues == null ? null : queues.get(roomId);
    }

    private static void startTextSendRequest(PendingTextSend pending) {
        if (pending == null || pending.resolved.get()) return;
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = getTextQueueLocked(pending.account, pending.roomId);
            if (queue == null || queue.peekFirst() != pending || pending.started) return;
            pending.started = true;
        }
        ZenoraApiClient.sendTextMessage(pending.roomId, pending.body, pending.clientMessageId,
                (response, error) -> {
                    if (pending.resolved.get()) return;
                    if (error != null) {
                        failPendingTextSend(pending, error.getMessage());
                        return;
                    }
                    JSONObject sent = response == null ? null : response.optJSONObject("message");
                    long rawId = sent == null ? 0L : sent.optLong("message_id", 0L);
                    String returnedClientId = sent == null ? "" : sent.optString("client_message_id", "");
                    int serverDate = sent == null ? 0
                            : ZenoraChatController.parseHistoryDate(sent.optString("created_at", ""));
                    if (rawId <= 0L || rawId > Integer.MAX_VALUE
                            || !pending.clientMessageId.equals(returnedClientId)
                            || sent.optLong("room_id", 0L) != pending.roomId
                            || !pending.body.equals(sent.optString("text", ""))
                            || serverDate <= 0) {
                        failPendingTextSend(pending, "Zenora returned an invalid message acknowledgment.");
                        return;
                    }
                    acknowledgePendingTextSend(pending, (int) rawId, serverDate);
                });
    }

    private static void removeQueuedTextSend(PendingTextSend pending) {
        PendingTextSend next = null;
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = getTextQueueLocked(pending.account, pending.roomId);
            if (queue != null) {
                boolean wasHead = queue.peekFirst() == pending;
                queue.remove(pending);
                if (queue.isEmpty()) {
                    Map<Long, ArrayDeque<PendingTextSend>> queues = pendingTextQueues.get(pending.account);
                    if (queues != null) {
                        queues.remove(pending.roomId);
                        if (queues.isEmpty()) pendingTextQueues.remove(pending.account);
                    }
                    HashSet<Long> active = activeTextSendRooms.get(pending.account);
                    if (active != null) {
                        active.remove(pending.roomId);
                        if (active.isEmpty()) activeTextSendRooms.remove(pending.account);
                    }
                } else if (wasHead) next = queue.peekFirst();
            }
        }
        if (next != null) startTextSendRequest(next);
    }

    private static void finishTextQueue(PendingTextSend pending) {
        PendingTextSend next = null;
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = getTextQueueLocked(pending.account, pending.roomId);
            if (queue == null) return;
            boolean wasHead = queue.peekFirst() == pending;
            if (wasHead) queue.removeFirst();
            else queue.remove(pending);
            if (queue.isEmpty()) {
                Map<Long, ArrayDeque<PendingTextSend>> queues = pendingTextQueues.get(pending.account);
                if (queues != null) {
                    queues.remove(pending.roomId);
                    if (queues.isEmpty()) pendingTextQueues.remove(pending.account);
                }
                HashSet<Long> active = activeTextSendRooms.get(pending.account);
                if (active != null) {
                    active.remove(pending.roomId);
                    if (active.isEmpty()) activeTextSendRooms.remove(pending.account);
                }
            } else if (wasHead) {
                next = queue.peekFirst();
                if (next != null) next.started = false;
            }
        }
        if (next != null) startTextSendRequest(next);
    }

    private static void failPendingTextSend(PendingTextSend pending, String reason) {
        if (pending == null || pending.resolved.get() || pending.failed) return;
        pending.failed = true;
        TLRPC.Message message = pending.messageObject.messageOwner;
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
        pending.messageObject.forceUpdate = true;
        MessagesStorage.getInstance(pending.account).markMessageAsSendError(message, 0);
        NotificationCenter center = NotificationCenter.getInstance(pending.account);
        center.postNotificationName(NotificationCenter.messageSendError, pending.localId);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(pending.messageObject);
        center.postNotificationName(NotificationCenter.replaceMessagesObjects, pending.dialogId, objects);
        finishTextQueue(pending);
        if (pending.callback != null) {
            pending.callback.onComplete(false, TextUtils.isEmpty(reason)
                    ? "ZenoraChat could not confirm the message send." : reason);
        }
    }

    private static boolean reconcilePendingTextMessage(int account, long dialogId, long roomId,
                                                        String clientId, int serverId, int serverDate) {
        if (TextUtils.isEmpty(clientId) || serverId <= 0 || serverDate <= 0) return false;
        PendingTextSend pending;
        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> pendingByDialog = pendingTextByClientId.get(account);
            HashMap<String, PendingTextSend> dialogPending =
                    pendingByDialog == null ? null : pendingByDialog.get(dialogId);
            pending = dialogPending == null ? null : dialogPending.get(clientId);
            if (pending == null || pending.roomId != roomId || pending.resolved.get()) return false;
        }
        acknowledgePendingTextSend(pending, serverId, serverDate);
        return true;
    }

    private static boolean isCompletedTextMessage(int account, long dialogId, String clientId, int serverId) {
        if (TextUtils.isEmpty(clientId)) return false;
        synchronized (lock) {
            Map<Long, HashMap<String, Integer>> accountCompleted = completedTextByClientId.get(account);
            HashMap<String, Integer> completed =
                    accountCompleted == null ? null : accountCompleted.get(dialogId);
            Integer knownId = completed == null ? null : completed.get(clientId);
            return knownId != null && knownId == serverId;
        }
    }

    private static void acknowledgePendingTextSend(PendingTextSend pending, int serverId, int serverDate) {
        if (pending == null || serverId <= 0 || serverDate <= 0
                || !pending.resolved.compareAndSet(false, true)) return;
        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> pendingByDialog = pendingTextByClientId.get(pending.account);
            HashMap<String, PendingTextSend> dialogPending =
                    pendingByDialog == null ? null : pendingByDialog.get(pending.dialogId);
            if (dialogPending != null && dialogPending.get(pending.clientMessageId) == pending) {
                dialogPending.remove(pending.clientMessageId);
                if (dialogPending.isEmpty()) pendingByDialog.remove(pending.dialogId);
                if (pendingByDialog.isEmpty()) pendingTextByClientId.remove(pending.account);
            }
            HashMap<String, Integer> completed = completedTextByClientId
                    .computeIfAbsent(pending.account, ignored -> new HashMap<>())
                    .computeIfAbsent(pending.dialogId, ignored -> new HashMap<>());
            completed.put(pending.clientMessageId, serverId);
            if (completed.size() > 512) completed.remove(completed.keySet().iterator().next());
            sentMessageIds.computeIfAbsent(pending.account, ignored -> new HashMap<>())
                    .computeIfAbsent(pending.dialogId, ignored -> new HashSet<>()).add((long) serverId);
            Map<Long, OlderState> accountOlder = olderStates.get(pending.account);
            OlderState state = accountOlder == null ? null : accountOlder.get(pending.dialogId);
            if (state != null && state.roomId == pending.roomId) state.seenMessageIds.add(serverId);
            Map<Long, Snapshot> accountCache = cache.get(pending.account);
            if (accountCache != null) accountCache.remove(pending.dialogId);
        }

        TLRPC.Message message = pending.messageObject.messageOwner;
        message.id = serverId;
        message.local_id = pending.localId;
        message.date = serverDate;
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
        pending.messageObject.forceUpdate = true;
        NotificationCenter center = NotificationCenter.getInstance(pending.account);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(pending.messageObject);
        center.postNotificationName(NotificationCenter.replaceMessagesObjects, pending.dialogId, objects);

        MessagesStorage storage = MessagesStorage.getInstance(pending.account);
        storage.getStorageQueue().postRunnable(() -> {
            long[] ids = storage.updateMessageStateAndId(
                    pending.randomId, pending.dialogId, pending.localId, serverId, serverDate, false, 0, 0);
            AndroidUtilities.runOnUIThread(() -> {
                if (ids != null) {
                    center.postNotificationName(NotificationCenter.messageReceivedByServer,
                            pending.localId, serverId, null, pending.dialogId, 0L, -1, false);
                    center.postNotificationName(NotificationCenter.messageReceivedByServer2,
                            pending.localId, serverId, null, pending.dialogId, 0L, -1, false);
                } else {
                    FileLog.w("[ZenoraChat] Local row missing during ID remap: "
                            + pending.localId + " -> " + serverId);
                }
                center.postNotificationName(NotificationCenter.dialogsNeedReload);
                finishTextQueue(pending);
                if (pending.callback != null) pending.callback.onComplete(true, "");
            });
        });
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
        ArrayList<String> clientMessageIds = new ArrayList<>();
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
            clientMessageIds.add(row.optString("client_message_id", ""));
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
        HashSet<Integer> committedIds = new HashSet<>();
        long committedCursor = afterMessageId;
        for (int i = 0; i < ids.size(); i++) {
            int messageId = ids.get(i);
            long senderId = senderIds.get(i);
            String clientMessageId = clientMessageIds.get(i);
            if (!TextUtils.isEmpty(clientMessageId)
                    && (reconcilePendingTextMessage(account, dialogId, roomId,
                    clientMessageId, messageId, dates.get(i))
                    || isCompletedTextMessage(account, dialogId, clientMessageId, messageId))) {
                committedIds.add(messageId);
                committedCursor = messageId;
                continue;
            }
            boolean alreadyApplied;
            boolean publishingLocally;
            synchronized (lock) {
                alreadyApplied = state.seenMessageIds.contains(messageId);
                Map<Long, HashSet<Long>> accountSent = sentMessageIds.get(account);
                HashSet<Long> sent = accountSent == null ? null : accountSent.get(dialogId);
                alreadyApplied |= sent != null && sent.contains((long) messageId);

                Map<Long, HashSet<Long>> accountPending = pendingSentMessageIds.get(account);
                HashSet<Long> pending = accountPending == null ? null : accountPending.get(dialogId);
                publishingLocally = pending != null && pending.contains((long) messageId);
            }
            if (alreadyApplied) {
                committedIds.add(messageId);
                committedCursor = messageId;
                continue;
            }
            if (publishingLocally) {
                // Do not move after_id past this ID. If local publication fails, the next
                // poll must be able to receive the server-committed message again.
                break;
            }

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
            committedIds.add(messageId);
            committedCursor = messageId;
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
                if (!committedIds.contains(messageId)) continue;
                current.seenMessageIds.add(messageId);
                if (senderIds.get(i) == selfId) {
                    sentMessageIds.computeIfAbsent(account, ignored -> new HashMap<>())
                            .computeIfAbsent(dialogId, ignored -> new HashSet<>())
                            .add((long) messageId);
                }
            }
        }
        FileLog.d("[ZenoraChat] Live text page applied; dialog=" + dialogId
                + ", room=" + roomId + ", returned=" + ids.size()
                + ", displayed=" + objects.size() + ", cursor=" + committedCursor);
        return committedCursor;
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
                String clientMessageId = row.optString("client_message_id", "");
                int serverDate = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
                if (!TextUtils.isEmpty(clientMessageId)
                        && (reconcilePendingTextMessage(account, dialogId, roomId,
                        clientMessageId, messageId, serverDate)
                        || isCompletedTextMessage(account, dialogId, clientMessageId, messageId))) continue;
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
