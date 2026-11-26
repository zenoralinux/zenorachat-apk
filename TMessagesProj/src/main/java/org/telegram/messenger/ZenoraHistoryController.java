package org.telegram.messenger;

import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads the first bounded Zenora text-history page and hands it to Telegram's
 * existing MessagesController/ChatActivity pipeline. This class adds no UI.
 */
public final class ZenoraHistoryController {
    private static final Object lock = new Object();
    private static final Map<Integer, Map<Long, Snapshot>> cache = new HashMap<>();
    private static final Map<Integer, Map<Long, ArrayList<Target>>> inFlight = new HashMap<>();
    private static final Map<Integer, Map<Long, Long>> initialRequestIds = new HashMap<>();
    private static long nextInitialRequestId;
    private static final Map<Integer, Map<Long, OlderState>> olderStates = new HashMap<>();
    private static final Map<Integer, Map<Long, ArrayDeque<PendingTextSend>>> pendingTextQueues = new HashMap<>();
    private static final Map<Integer, HashSet<Long>> activeTextSendRooms = new HashMap<>();
    private static final Map<Integer, Map<Long, HashMap<String, PendingTextSend>>> pendingTextByClientId = new HashMap<>();
    private static final Map<Integer, Map<Long, HashMap<String, Integer>>> completedTextByClientId = new HashMap<>();
    private static final int MAX_PENDING_TEXT_SENDS_PER_ROOM = 64;
    private static final Map<Integer, Map<Long, HashSet<Long>>> sentMessageIds = new HashMap<>();
    private static final Map<Integer, Map<Long, HashSet<Long>>> pendingSentMessageIds = new HashMap<>();
    private static final Handler outboxHandler = new Handler(Looper.getMainLooper());
    private static final HashSet<Integer> outboxRestoreInFlight = new HashSet<>();
    private static final Map<Integer, Long> outboxRestoredUserIds = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000L;
    private static final long RETRY_COOLDOWN_MS = 1_200L;
    private static final int CACHE_MAX_DIALOGS = 24;

    public interface TextSendCallback {
        default void onQueued() {
        }
        void onComplete(boolean success, String message);
    }

    private static final class PendingTextSend {
        final int account;
        final long dialogId;
        final long roomId;
        final int localId;
        final long randomId;
        final long zenoraUserId;
        final String clientMessageId;
        final String body;
        final long replyToId;
        final long replyPreviewSenderId;
        final String replyPreviewBody;
        final MessageObject replyMessageObject;
        final long createdAt;
        final MessageObject messageObject;
        final TextSendCallback callback;
        final java.util.concurrent.atomic.AtomicBoolean resolved =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile boolean failed;
        volatile boolean started;
        volatile boolean retryScheduled;
        volatile String status = ZenoraOutboxPolicy.SENDING;
        volatile int attemptCount;
        volatile long nextAttemptAt;

        PendingTextSend(int account, long dialogId, long roomId, int localId, long randomId,
                        long zenoraUserId, String clientMessageId, String body,
                        long replyToId, MessageObject replyMessageObject,
                        MessageObject messageObject, TextSendCallback callback, long createdAt) {
            this.account = account;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.localId = localId;
            this.randomId = randomId;
            this.zenoraUserId = zenoraUserId;
            this.clientMessageId = clientMessageId;
            this.body = body;
            this.replyToId = replyToId;
            this.replyMessageObject = replyMessageObject;
            this.replyPreviewSenderId = replyMessageObject == null || replyMessageObject.messageOwner.from_id == null
                    ? 0L : MessageObject.getPeerId(replyMessageObject.messageOwner.from_id);
            String preview = replyMessageObject == null || replyMessageObject.messageOwner.message == null
                    ? "" : replyMessageObject.messageOwner.message;
            this.replyPreviewBody = limitReplyPreviewText(preview);
            this.messageObject = messageObject;
            this.callback = callback;
            this.createdAt = createdAt;
        }
    }

    private ZenoraHistoryController() {
    }

    private static long replyToIdFromRow(JSONObject row) {
        if (row == null || row.isNull("reply_to_id")) return 0L;
        return row.optLong("reply_to_id", -1L);
    }

    private static boolean isValidReplyPreviewText(String text) {
        return !TextUtils.isEmpty(text == null ? "" : text.trim())
                && text.codePointCount(0, text.length()) <= 140;
    }

    private static String limitReplyPreviewText(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        if (count <= 140) return text;
        return text.substring(0, text.offsetByCodePoints(0, 140));
    }

    private static boolean isValidReplyContract(JSONObject row) {
        long replyToId = replyToIdFromRow(row);
        if (replyToId < 0L || replyToId > Integer.MAX_VALUE) return false;
        if (replyToId == 0L) return row == null || row.isNull("reply_preview")
                || row.optJSONObject("reply_preview") == null;
        JSONObject preview = row.optJSONObject("reply_preview");
        if (preview == null) return false;
        String status = preview.optString("status", "");
        if ("unavailable".equals(status)) return true;
        String previewText = preview.optString("text", "");
        return "available".equals(status)
                && preview.optLong("message_id", 0L) == replyToId
                && preview.optLong("sender_id", 0L) > 0L
                && preview.optLong("sender_id", 0L) <= Integer.MAX_VALUE
                && isValidReplyPreviewText(previewText);
    }

    private static TLRPC.Message createReplyPreviewMessage(long dialogId, TLRPC.Peer peer,
                                                            int date, long replyToId,
                                                            JSONObject preview, long selfId) {
        if (replyToId <= 0L || replyToId > Integer.MAX_VALUE || preview == null
                || preview.optLong("message_id", replyToId) != replyToId) return null;
        TLRPC.TL_message parent = new TLRPC.TL_message();
        parent.id = (int) replyToId;
        parent.local_id = parent.id;
        parent.dialog_id = dialogId;
        parent.peer_id = peer;
        parent.date = date;
        parent.media = new TLRPC.TL_messageMediaEmpty();
        if ("unavailable".equals(preview.optString("status", ""))) {
            // Safe persistent placeholder: no original text, author, or private metadata.
            parent.message = "Message unavailable";
            return parent;
        }
        if (!"available".equals(preview.optString("status", ""))) return null;
        long senderId = preview.optLong("sender_id", 0L);
        String text = preview.optString("text", "");
        if (senderId <= 0L || senderId > Integer.MAX_VALUE
                || !isValidReplyPreviewText(text)) return null;
        parent.from_id = new TLRPC.TL_peerUser();
        parent.from_id.user_id = senderId;
        parent.message = text;
        parent.out = senderId == selfId;
        parent.flags |= 1 << 8;
        if (parent.out) parent.flags |= 1 << 1;
        return parent;
    }

    private static void applyReplyContractToMessage(TLRPC.Message message, JSONObject row,
                                                       long dialogId, TLRPC.Peer peer,
                                                       int date, long selfId) {
        long replyToId = replyToIdFromRow(row);
        if (replyToId <= 0L) return;
        TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
        header.flags |= 16;
        header.reply_to_msg_id = (int) replyToId;
        message.reply_to = header;
        JSONObject preview = row.optJSONObject("reply_preview");
        message.replyMessage = createReplyPreviewMessage(
                dialogId, peer, date, replyToId, preview, selfId);
        if (message.params == null) message.params = new HashMap<>();
        message.params.put("zenora_reply_preview_status",
                preview != null && "available".equals(preview.optString("status", ""))
                        && message.replyMessage != null ? "available" : "unavailable");
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
        boolean hasMoreKnown;
        boolean coverageValid;
        long oldestLocalMessageId;
        long newestLocalMessageId;
        boolean loading;
        int loadingClassGuid;

        OlderState(long roomId) {
            this.roomId = roomId;
        }

        OlderState(long roomId, MessagesStorage.ZenoraHistoryState persisted) {
            this.roomId = roomId;
            oldestMessageId = persisted.olderCursor;
            hasMoreOlder = persisted.hasMoreOlder == 1;
            hasMoreKnown = persisted.hasMoreOlder >= 0;
            coverageValid = persisted.coverageValid;
            oldestLocalMessageId = persisted.oldestLocalMessageId;
            newestLocalMessageId = persisted.newestLocalMessageId;
        }

        OlderState(Snapshot snapshot) {
            roomId = snapshot.roomId;
            users = snapshot.users;
            oldestMessageId = snapshot.oldestMessageId;
            hasMoreOlder = snapshot.hasMoreOlder;
            hasMoreKnown = true;
            coverageValid = true;
            rememberMessageIds(seenMessageIds, snapshot.messages);
        }
    }

    public static void clearAccount(int account) {
        synchronized (lock) {
            cache.remove(account);
            inFlight.remove(account);
            initialRequestIds.remove(account);
            olderStates.remove(account);
            pendingTextQueues.remove(account);
            activeTextSendRooms.remove(account);
            pendingTextByClientId.remove(account);
            outboxRestoreInFlight.remove(account);
            outboxRestoredUserIds.remove(account);
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
                if (waiters.isEmpty()) {
                    accountRequests.remove(dialogId);
                    Map<Long, Long> requestIds = initialRequestIds.get(account);
                    if (requestIds != null) requestIds.remove(dialogId);
                }
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
        final long zenoraUserId = ZenoraApiClient.getCurrentUserId();
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) {
            publish(account, dialogId, 0, target, null,
                    new IllegalStateException("Zenora dialog is no longer available"), limit);
            return;
        }

        // The caller invokes this only after MessagesStorage.getMessages() has
        // published the local page. Coalesce duplicate opens while restoring the
        // durable server cursor for this account/dialog.
        boolean startRequest = false;
        final long initialRequestId;
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests =
                    inFlight.computeIfAbsent(account, ignored -> new HashMap<>());
            ArrayList<Target> waiters = accountRequests.get(dialogId);
            if (waiters == null) {
                waiters = new ArrayList<>();
                accountRequests.put(dialogId, waiters);
                long requestId = ++nextInitialRequestId;
                if (requestId <= 0L) requestId = nextInitialRequestId = 1L;
                initialRequestIds.computeIfAbsent(account, ignored -> new HashMap<>())
                        .put(dialogId, requestId);
                startRequest = true;
            }
            Map<Long, Long> requestIds = initialRequestIds.get(account);
            initialRequestId = requestIds == null ? 0L : requestIds.getOrDefault(dialogId, 0L);
            waiters.add(target);
        }
        if (!startRequest) return;

        MessagesStorage.getInstance(account).loadZenoraHistoryState(
                roomId, dialogId, zenoraUserId, (persisted, stateError) -> {
            if (!hasInitialWaiters(account, dialogId, roomId, initialRequestId)) return;
            if (stateError != null) FileLog.e(stateError);
            OlderState restored = persisted == null
                    ? new OlderState(roomId) : new OlderState(roomId, persisted);
            synchronized (lock) {
                olderStates.computeIfAbsent(account, ignored -> new HashMap<>())
                        .put(dialogId, restored);
            }

            // Live sync can now start from the highest message ID already stored
            // locally, even when the history HTTP request below fails offline.
            ZenoraLiveMessageSyncController.onHistoryApplied(account, dialogId, roomId, classGuid);
            ZenoraApiClient.fetchBasicTextHistory(roomId, limit, (response, error) -> {
                if (!hasInitialWaiters(account, dialogId, roomId, initialRequestId)) return;
                if (error != null || response == null) {
                    complete(account, dialogId, roomId, initialRequestId, null,
                            error != null ? error : new IllegalStateException("History response is empty"),
                            limit);
                    return;
                }
                JSONArray messages = response.optJSONArray("messages");
                JSONObject pagination = response.optJSONObject("pagination");
                if (messages == null || !isValidBasicTextPage(messages, pagination, roomId, limit, 0L)) {
                    complete(account, dialogId, roomId, initialRequestId, null,
                            new IllegalStateException("Initial text history page is malformed"), limit);
                    return;
                }
                boolean hasMoreOlder = pagination.optBoolean("has_more_older", false);
                long oldestMessageId = pagination.optLong("oldest_message_id", 0L);
                ZenoraApiClient.fetchUsers((usersResponse, usersError) -> {
                    JSONArray users = usersResponse == null ? null : usersResponse.optJSONArray("users");
                    if (usersError != null) {
                        FileLog.w("[ZenoraChat] Sender directory unavailable: " + usersError.getMessage());
                    }
                    complete(account, dialogId, roomId, initialRequestId,
                            new Snapshot(roomId, messages, users, hasMoreOlder, oldestMessageId), null, limit);
                });
            });
        });
    }

    private static boolean hasInitialWaiters(int account, long dialogId, long roomId,
                                             long expectedRequestId) {
        if (ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId
                || !ZenoraChatController.isZenoraEnabled()) return false;
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests = inFlight.get(account);
            ArrayList<Target> targets = accountRequests == null ? null : accountRequests.get(dialogId);
            Map<Long, Long> requestIds = initialRequestIds.get(account);
            Long activeRequestId = requestIds == null ? null : requestIds.get(dialogId);
            return targets != null && !targets.isEmpty()
                    && activeRequestId != null && activeRequestId == expectedRequestId;
        }
    }

    /**
     * Starts one older-text page. Returning false means there is no eligible request;
     * callers should keep Telegram's native loading gate untouched.
     */
    public static boolean loadOlderHistory(int account, long dialogId, int classGuid,
                                           int loadIndex, int requestedLimit) {
        return loadOlderHistory(account, dialogId, classGuid, loadIndex, requestedLimit, 0L);
    }

    public static boolean loadOlderHistory(int account, long dialogId, int classGuid,
                                           int loadIndex, int requestedLimit,
                                           long localBeforeMessageId) {
        final long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) return false;

        final int limit = Math.max(1, Math.min(50, requestedLimit));
        final OlderState state;
        final long beforeMessageId;
        final long requestId;
        synchronized (lock) {
            Map<Long, OlderState> accountOlder =
                    olderStates.computeIfAbsent(account, ignored -> new HashMap<>());
            OlderState current = accountOlder.get(dialogId);
            if (current == null || current.roomId != roomId) {
                current = new OlderState(roomId);
                accountOlder.put(dialogId, current);
            }
            state = current;
            long now = SystemClock.elapsedRealtime();
            beforeMessageId = state.coverageValid && state.oldestMessageId > 0
                    && localBeforeMessageId > 0
                    ? Math.min(state.oldestMessageId, localBeforeMessageId)
                    : state.coverageValid && state.oldestMessageId > 0
                        ? state.oldestMessageId : localBeforeMessageId;
            int hasMore = state.hasMoreKnown ? (state.hasMoreOlder ? 1 : 0) : -1;
            if (state.loading || now < state.retryAfterMs) {
                // A second scroll edge must still complete its own native loading
                // index, but it must not start a parallel request for this dialog.
                publish(account, dialogId, roomId,
                        new Target(classGuid, loadIndex, true, beforeMessageId, true),
                        null, new IllegalStateException("Older history request is already active or cooling down"),
                        limit);
                return true;
            }
            if (state.hasMoreKnown && !state.hasMoreOlder) {
                // This is an authoritative server end, not an empty local cache.
                Snapshot end = new Snapshot(roomId, new JSONArray(), state.users, false, 0L);
                publish(account, dialogId, roomId,
                        new Target(classGuid, loadIndex, true, beforeMessageId, false),
                        end, null, limit);
                return true;
            }
            if (!ZenoraHistoryPolicy.canRequestOlder(hasMore, beforeMessageId)) {
                publish(account, dialogId, roomId,
                        new Target(classGuid, loadIndex, true, beforeMessageId, true),
                        null, new IllegalStateException("Older history cursor is not available yet"),
                        limit);
                return true;
            }
            if (state.oldestMessageId <= 0) state.oldestMessageId = beforeMessageId;
            state.loading = true;
            state.loadingClassGuid = classGuid;
            state.requestId++;
            requestId = state.requestId;
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
                if (oldestMessageId > 0) current.oldestMessageId = oldestMessageId;
                current.hasMoreOlder = hasMoreOlder;
                current.hasMoreKnown = true;
                current.coverageValid = true;
                current.loading = false;
                current.loadingClassGuid = 0;
                current.retryAfterMs = 0L;
                MessagesStorage.getInstance(account).saveZenoraHistoryState(
                        roomId, dialogId, ZenoraApiClient.getCurrentUserId(),
                        current.oldestMessageId, current.hasMoreOlder, current.coverageValid);
            }
            FileLog.d("[ZenoraChat] Older page committed; dialog=" + dialogId
                    + ", before=" + beforeMessageId + ", oldest=" + oldestMessageId
                    + ", count=" + messages.length() + ", more=" + hasMoreOlder);
        });
        return true;
    }


    /** Backward-compatible plain-text entry point. */
    public static boolean sendTextMessage(int account, long dialogId, long roomId,
                                          String body, TextSendCallback callback) {
        return sendTextMessage(account, dialogId, roomId, body, 0L, null, callback);
    }

    /** Persist the native optimistic text bubble and its stable Reply payload before publishing it. */
    public static boolean sendTextMessage(int account, long dialogId, long roomId,
                                          String body, long replyToId, MessageObject replyParent,
                                          TextSendCallback callback) {
        long zenoraUserId = ZenoraApiClient.getCurrentUserId();
        if (dialogId == 0 || roomId <= 0 || body == null || body.trim().isEmpty()
                || zenoraUserId <= 0 || !ZenoraChatController.isZenoraEnabled()
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) return false;
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.getDialog(dialogId);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (dialog == null || dialog.peer == null || selfId <= 0
                || selfId > Integer.MAX_VALUE || selfId != zenoraUserId) return false;
        if (replyToId < 0L || (replyToId == 0L && replyParent != null)) return false;
        if (replyToId > 0L) {
            if (replyParent == null || replyParent.messageOwner == null
                    || replyParent.messageOwner.id <= 0
                    || replyParent.messageOwner.id != replyToId
                    || replyParent.getId() <= 0
                    || replyParent.getDialogId() != dialogId
                    || !(replyParent.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)
                    || TextUtils.isEmpty(replyParent.messageOwner.message)
                    || TextUtils.isEmpty(replyParent.messageOwner.message.trim())
                    || replyParent.messageOwner.action != null
                    || replyParent.isEphemeral()
                    || replyParent.messageOwner.from_id == null
                    || MessageObject.getPeerId(replyParent.messageOwner.from_id) <= 0L) {
                return false;
            }
        }

        int localId = UserConfig.getInstance(account).getNewMessageId();
        long randomId = Utilities.random.nextLong();
        while (randomId == 0L) randomId = Utilities.random.nextLong();
        String clientMessageId = java.util.UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();

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
        if (replyToId > 0L) {
            TLRPC.TL_messageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
            replyHeader.flags |= 16;
            replyHeader.reply_to_msg_id = (int) replyToId;
            message.reply_to = replyHeader;
            message.replyMessage = replyParent.messageOwner;
        }

        MessageObject object = new MessageObject(account, message, false, true);
        object.wasJustSent = true;
        if (replyParent != null) object.replyMessageObject = replyParent;
        PendingTextSend pending = new PendingTextSend(account, dialogId, roomId, localId,
                randomId, zenoraUserId, clientMessageId, body, replyToId, replyParent,
                object, callback, createdAt);

        // The callback is invoked only after both messages_v2 and zenora_outbox commit.
        MessagesStorage.getInstance(account).storeZenoraTextMessage(
                message, roomId, zenoraUserId, clientMessageId, replyToId,
                pending.replyPreviewSenderId, pending.replyPreviewBody, error -> {
                    if (error != null) {
                        if (callback != null) callback.onComplete(false,
                                "Unable to save the message to the local outbox.");
                        return;
                    }
                    // A late storage callback may outlive Logout or an account switch.
                    // Keep the row durable, but never submit it with a different session.
                    if (ZenoraApiClient.getCurrentUserId() != zenoraUserId
                            || UserConfig.getInstance(account).getClientUserId() != zenoraUserId
                            || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) {
                        return;
                    }
                    boolean startImmediately = enqueuePendingTextSend(pending);
                    controller.updateInterfaceWithMessages(dialogId,
                            new ArrayList<>(Collections.singletonList(object)), 0);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.dialogsNeedReload);
                    UserConfig.getInstance(account).saveConfig(false);
                    if (callback != null) callback.onQueued();
                    if (startImmediately) startTextSendRequest(pending);
                });
        return true;
    }

    /** Native bubble retry stays on Zenora PHP and reuses the original idempotency key. */
    public static boolean retryTextMessage(int account, MessageObject object) {
        if (object == null || object.messageOwner == null) return false;
        TLRPC.Message message = object.messageOwner;
        long dialogId = object.getDialogId();
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        long zenoraUserId = ZenoraApiClient.getCurrentUserId();
        String clientId = message.params == null ? null : message.params.get("zenora_client_message_id");
        long replyToId = message.reply_to == null ? 0L : message.reply_to.reply_to_msg_id;
        MessageObject replyParent = object.replyMessageObject;
        if (replyParent == null && message.replyMessage != null) {
            replyParent = new MessageObject(account, message.replyMessage, false, false);
            object.replyMessageObject = replyParent;
        }
        if (roomId <= 0 || zenoraUserId <= 0 || zenoraUserId != UserConfig.getInstance(account).getClientUserId()
                || TextUtils.isEmpty(clientId) || TextUtils.isEmpty(message.message)
                || !(message.media instanceof TLRPC.TL_messageMediaEmpty)
                || message.action != null || object.scheduled
                || replyToId < 0L
                || message.reply_to != null && (replyToId <= 0L
                    || message.reply_to.reply_to_top_id != 0
                    || message.reply_to.reply_to_peer_id != null)
                || replyToId > 0L && (message.reply_to == null
                    || message.reply_to.reply_to_msg_id != replyToId)
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId) return false;

        long randomId = message.random_id;
        if (randomId == 0L) return false;
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        object.forceUpdate = true;
        long createdAt = Math.max(0L, (long) message.date * 1000L);

        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> pendingByDialog = pendingTextByClientId.get(account);
            HashMap<String, PendingTextSend> existingMap =
                    pendingByDialog == null ? null : pendingByDialog.get(dialogId);
            PendingTextSend existing = existingMap == null ? null : existingMap.get(clientId);
            if (existing != null && !existing.failed && !existing.resolved.get()) return false;
            if (existing != null) createdAt = existing.createdAt;
        }
        PendingTextSend pending = new PendingTextSend(account, dialogId, roomId, message.id,
                randomId, zenoraUserId, clientId, message.message, replyToId,
                replyParent, object, null, createdAt);

        MessagesStorage.getInstance(account).prepareZenoraOutboxRetry(
                message, roomId, zenoraUserId, clientId, error -> {
                    if (error != null) {
                        FileLog.e(error);
                        return;
                    }
                    boolean startImmediately = enqueuePendingTextSend(pending);
                    ArrayList<MessageObject> objects = new ArrayList<>();
                    objects.add(object);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.replaceMessagesObjects, dialogId, objects);
                    if (startImmediately) startTextSendRequest(pending);
                });
        return true;
    }

    private static boolean enqueuePendingTextSend(PendingTextSend pending) {
        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> byDialog =
                    pendingTextByClientId.computeIfAbsent(pending.account, ignored -> new HashMap<>());
            HashMap<String, PendingTextSend> byClient =
                    byDialog.computeIfAbsent(pending.dialogId, ignored -> new HashMap<>());
            PendingTextSend current = byClient.get(pending.clientMessageId);
            if (current != null && current != pending && !current.failed && !current.resolved.get()) {
                return false;
            }

            ArrayDeque<PendingTextSend> queue = pendingTextQueues
                    .computeIfAbsent(pending.account, ignored -> new HashMap<>())
                    .computeIfAbsent(pending.roomId, ignored -> new ArrayDeque<>());
            if (!queue.contains(pending)) {
                ArrayList<PendingTextSend> ordered = new ArrayList<>(queue);
                ordered.add(pending);
                Collections.sort(ordered, (left, right) -> {
                    int createdOrder = Long.compare(left.createdAt, right.createdAt);
                    return createdOrder != 0 ? createdOrder
                            : Integer.compare(right.localId, left.localId);
                });
                queue.clear();
                queue.addAll(ordered);
            }
            byClient.put(pending.clientMessageId, pending);
            return queue.peekFirst() == pending
                    && activeTextSendRooms.computeIfAbsent(pending.account, ignored -> new HashSet<>())
                    .add(pending.roomId);
        }
    }

    /**
     * Restore pending records after the current room snapshot has populated this
     * Telegram account's dialog->room map. Rows belonging to another API user are
     * deliberately left untouched and can never be sent with this session.
     */
    public static void restorePendingTextSends(int account) {
        long activeUserId = ZenoraApiClient.getCurrentUserId();
        if (activeUserId <= 0 || !ZenoraApiClient.isZenoraSessionEnabled()) return;

        synchronized (lock) {
            Long restoredUser = outboxRestoredUserIds.get(account);
            if (restoredUser != null && restoredUser == activeUserId) {
                resumeDueTextSends(account, activeUserId);
                return;
            }
            if (!outboxRestoreInFlight.add(account)) return;
        }

        MessagesStorage.getInstance(account).loadZenoraOutbox((records, error) -> {
            synchronized (lock) {
                outboxRestoreInFlight.remove(account);
            }
            if (error != null || records == null) return;
            long currentUserId = ZenoraApiClient.getCurrentUserId();
            if (currentUserId <= 0 || currentUserId != activeUserId) return;

            boolean waitingForDialogMap = false;
            ArrayList<PendingTextSend> restored = new ArrayList<>();
            for (MessagesStorage.ZenoraOutboxRecord record : records) {
                if (record.zenoraUserId != currentUserId) continue;
                long mappedRoomId = ZenoraChatController.getRoomIdForDialog(account, record.dialogId);
                if (mappedRoomId != record.roomId) {
                    waitingForDialogMap = true;
                    continue;
                }
                MessagesController controller = MessagesController.getInstance(account);
                TLRPC.Dialog dialog = controller.getDialog(record.dialogId);
                long selfId = UserConfig.getInstance(account).getClientUserId();
                if (dialog == null || dialog.peer == null || selfId != currentUserId
                        || record.localMessageId == 0 || TextUtils.isEmpty(record.clientMessageId)
                        || TextUtils.isEmpty(record.body) || record.replyToId < 0L
                        || record.replyToId > Integer.MAX_VALUE) {
                    waitingForDialogMap = true;
                    continue;
                }

                synchronized (lock) {
                    Map<Long, HashMap<String, PendingTextSend>> accountPending =
                            pendingTextByClientId.get(account);
                    HashMap<String, PendingTextSend> dialogPending =
                            accountPending == null ? null : accountPending.get(record.dialogId);
                    PendingTextSend existing = dialogPending == null ? null
                            : dialogPending.get(record.clientMessageId);
                    if (existing != null && !existing.resolved.get()) continue;
                }

                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = record.localMessageId;
                message.local_id = record.localMessageId;
                message.random_id = record.randomId;
                message.dialog_id = record.dialogId;
                message.peer_id = dialog.peer;
                message.from_id = new TLRPC.TL_peerUser();
                message.from_id.user_id = selfId;
                message.date = record.messageDate;
                message.message = record.body;
                message.media = new TLRPC.TL_messageMediaEmpty();
                message.out = true;
                message.unread = true;
                message.flags |= 1 << 8;
                message.flags |= 1 << 1;
                message.send_state = ZenoraOutboxPolicy.FAILED.equals(record.status)
                        ? MessageObject.MESSAGE_SEND_STATE_SEND_ERROR
                        : MessageObject.MESSAGE_SEND_STATE_SENDING;
                message.params = new HashMap<>();
                message.params.put("zenora_client_message_id", record.clientMessageId);
                message.params.put("zenora_room_id", String.valueOf(record.roomId));

                MessageObject replyParent = null;
                if (record.replyToId > 0L && record.replyToId <= Integer.MAX_VALUE) {
                    TLRPC.TL_messageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
                    replyHeader.flags |= 16;
                    replyHeader.reply_to_msg_id = (int) record.replyToId;
                    message.reply_to = replyHeader;
                    boolean hasVisiblePreview = record.replyPreviewSenderId > 0L
                            && record.replyPreviewSenderId <= Integer.MAX_VALUE
                            && !TextUtils.isEmpty(record.replyPreviewBody);
                    boolean hasUnavailablePreview = record.replyPreviewSenderId == 0L
                            && "Message unavailable".equals(record.replyPreviewBody);
                    if (hasVisiblePreview || hasUnavailablePreview) {
                        TLRPC.TL_message parent = new TLRPC.TL_message();
                        parent.id = (int) record.replyToId;
                        parent.local_id = parent.id;
                        parent.dialog_id = record.dialogId;
                        parent.peer_id = dialog.peer;
                        if (hasVisiblePreview) {
                            parent.from_id = new TLRPC.TL_peerUser();
                            parent.from_id.user_id = record.replyPreviewSenderId;
                            parent.out = record.replyPreviewSenderId == selfId;
                            parent.flags |= 1 << 8;
                            if (parent.out) parent.flags |= 1 << 1;
                        }
                        parent.date = record.messageDate;
                        parent.message = record.replyPreviewBody;
                        parent.media = new TLRPC.TL_messageMediaEmpty();
                        message.replyMessage = parent;
                        if (message.params == null) message.params = new HashMap<>();
                        message.params.put("zenora_reply_preview_status",
                                hasVisiblePreview ? "available" : "unavailable");
                        replyParent = new MessageObject(account, parent, false, false);
                    }
                }

                MessageObject object = new MessageObject(account, message, false, true);
                if (replyParent != null) object.replyMessageObject = replyParent;
                PendingTextSend restoredPending = new PendingTextSend(account, record.dialogId,
                        record.roomId, record.localMessageId, record.randomId, record.zenoraUserId,
                        record.clientMessageId, record.body, record.replyToId, replyParent,
                        object, null, record.createdAt);
                restoredPending.attemptCount = record.attemptCount;
                restoredPending.nextAttemptAt = record.nextAttemptAt;
                restoredPending.status = record.status;
                restoredPending.failed = ZenoraOutboxPolicy.FAILED.equals(record.status);

                synchronized (lock) {
                    pendingTextByClientId.computeIfAbsent(account, ignored -> new HashMap<>())
                            .computeIfAbsent(record.dialogId, ignored -> new HashMap<>())
                            .put(record.clientMessageId, restoredPending);
                }

                // Rehydrate the optimistic bubble itself, not just the network queue.
                // This also restores a FAILED bubble so the existing native Retry gesture remains usable.
                controller.updateInterfaceWithMessages(record.dialogId,
                        new ArrayList<>(Collections.singletonList(object)), 0);
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.dialogsNeedReload);
                if (restoredPending.failed) continue;

                // A process death leaves SENDING ambiguous: convert it to UNKNOWN and
                // replay using the exact persisted key and payload.
                if (ZenoraOutboxPolicy.SENDING.equals(restoredPending.status)) {
                    restoredPending.status = ZenoraOutboxPolicy.UNKNOWN;
                    restoredPending.nextAttemptAt = System.currentTimeMillis();
                    MessagesStorage.getInstance(account).updateZenoraOutboxState(
                            record.clientMessageId, ZenoraOutboxPolicy.UNKNOWN,
                            restoredPending.nextAttemptAt, false, false);
                }
                enqueuePendingTextSend(restoredPending);
                restored.add(restoredPending);
            }

            synchronized (lock) {
                if (!waitingForDialogMap) outboxRestoredUserIds.put(account, currentUserId);
            }
            for (PendingTextSend pending : restored) {
                if (pending.nextAttemptAt <= System.currentTimeMillis()) {
                    if (isRoomQueueHead(pending)) startTextSendRequest(pending);
                } else {
                    scheduleTextRetry(pending);
                }
            }
        });
    }

    private static boolean isRoomQueueHead(PendingTextSend pending) {
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = getTextQueueLocked(pending.account, pending.roomId);
            return queue != null && queue.peekFirst() == pending;
        }
    }

    private static void resumeDueTextSends(int account, long activeUserId) {
        ArrayList<PendingTextSend> heads = new ArrayList<>();
        synchronized (lock) {
            Map<Long, ArrayDeque<PendingTextSend>> queues = pendingTextQueues.get(account);
            if (queues == null) return;
            long now = System.currentTimeMillis();
            for (ArrayDeque<PendingTextSend> queue : queues.values()) {
                PendingTextSend head = queue.peekFirst();
                if (head != null && !head.failed && !head.started
                        && head.zenoraUserId == activeUserId) heads.add(head);
            }
        }
        for (PendingTextSend head : heads) {
            if (head.nextAttemptAt <= System.currentTimeMillis()) startTextSendRequest(head);
            else scheduleTextRetry(head);
        }
    }

    private static void scheduleTextRetry(PendingTextSend pending) {
        if (pending == null || pending.retryScheduled || pending.failed || pending.resolved.get()) return;
        long delay = Math.max(0L, pending.nextAttemptAt - System.currentTimeMillis());
        pending.retryScheduled = true;
        outboxHandler.postDelayed(() -> {
            pending.retryScheduled = false;
            if (pending.resolved.get() || pending.failed
                    || ZenoraApiClient.getCurrentUserId() != pending.zenoraUserId
                    || ZenoraChatController.getRoomIdForDialog(
                    pending.account, pending.dialogId) != pending.roomId) return;
            if (System.currentTimeMillis() >= pending.nextAttemptAt) {
                startTextSendRequest(pending);
            }
        }, delay);
    }

    private static ArrayDeque<PendingTextSend> getTextQueueLocked(int account, long roomId) {
        Map<Long, ArrayDeque<PendingTextSend>> queues = pendingTextQueues.get(account);
        return queues == null ? null : queues.get(roomId);
    }

    private static void startTextSendRequest(PendingTextSend pending) {
        if (pending == null || pending.resolved.get() || pending.failed
                || ZenoraApiClient.getCurrentUserId() != pending.zenoraUserId
                || ZenoraChatController.getRoomIdForDialog(
                pending.account, pending.dialogId) != pending.roomId) return;
        synchronized (lock) {
            ArrayDeque<PendingTextSend> queue = getTextQueueLocked(pending.account, pending.roomId);
            if (queue == null || queue.peekFirst() != pending || pending.started) return;
            pending.started = true;
            pending.retryScheduled = false;
            pending.status = ZenoraOutboxPolicy.SENDING;
        }
        MessagesStorage.getInstance(pending.account).updateZenoraOutboxState(
                pending.clientMessageId, ZenoraOutboxPolicy.SENDING, 0L, false, false);
        ZenoraApiClient.sendTextMessage(pending.roomId, pending.body,
                pending.clientMessageId, pending.replyToId, pending.zenoraUserId, (response, error) -> {
                    if (pending.resolved.get()) return;
                    if (error != null) {
                        handleTextSendFailure(pending, error);
                        return;
                    }
                    JSONObject sent = response == null ? null : response.optJSONObject("message");
                    long rawId = sent == null ? 0L : sent.optLong("message_id", 0L);
                    String returnedClientId = sent == null ? "" : sent.optString("client_message_id", "");
                    int serverDate = sent == null ? 0
                            : ZenoraChatController.parseHistoryDate(sent.optString("created_at", ""));
                    long ackReplyToId = replyToIdFromRow(sent);
                    if (rawId <= 0L || rawId > Integer.MAX_VALUE
                            || !pending.clientMessageId.equals(returnedClientId)
                            || sent.optLong("room_id", 0L) != pending.roomId
                            || !pending.body.equals(sent.optString("text", ""))
                            || ackReplyToId != pending.replyToId
                            || !isValidReplyContract(sent)
                            || serverDate <= 0) {
                        scheduleUnknownTextSend(pending,
                                new IllegalStateException("Zenora returned an ambiguous message acknowledgment."),
                                false);
                        return;
                    }
                    acknowledgePendingTextSend(pending, (int) rawId, serverDate,
                            ackReplyToId, sent.optJSONObject("reply_preview"),
                            pending.messageObject.messageOwner.peer_id, pending.zenoraUserId);
                });
    }

    private static void handleTextSendFailure(PendingTextSend pending, Exception error) {
        if (pending == null || pending.resolved.get()) return;
        if (error instanceof ZenoraApiClient.ApiException) {
            ZenoraApiClient.ApiException apiError = (ZenoraApiClient.ApiException) error;
            if (apiError.statusCode == 401) {
                scheduleUnknownTextSend(pending, error, true);
                return;
            }
            if (ZenoraOutboxPolicy.isDefinitiveHttpStatus(apiError.statusCode)
                    && !"CSRF_INVALID".equals(apiError.errorCode)) {
                failPendingTextSend(pending, error.getMessage());
                return;
            }
        }
        scheduleUnknownTextSend(pending, error, false);
    }

    private static void scheduleUnknownTextSend(PendingTextSend pending, Exception reason,
                                                 boolean authenticationFailure) {
        if (pending == null || pending.resolved.get() || pending.failed) return;
        long now = System.currentTimeMillis();
        pending.status = ZenoraOutboxPolicy.UNKNOWN;
        pending.started = false;
        pending.attemptCount++;
        int jitter = ThreadLocalRandom.current().nextInt(750, 1251);
        long nextAttempt = authenticationFailure ? now + 60_000L
                : ZenoraOutboxPolicy.nextAttemptAt(now, pending.attemptCount, jitter);
        pending.nextAttemptAt = nextAttempt;
        MessagesStorage.getInstance(pending.account).updateZenoraOutboxState(
                pending.clientMessageId, ZenoraOutboxPolicy.UNKNOWN, nextAttempt, true, false);
        if (reason != null) FileLog.w("[ZenoraChat] Text send remains durable and will retry: "
                + reason.getMessage());
        scheduleTextRetry(pending);
        if (pending.callback != null) {
            pending.callback.onComplete(false, authenticationFailure
                    ? "Zenora session needs to recover; the message is saved for retry."
                    : "The send result is unknown; the message is saved and will retry.");
        }
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
        pending.status = ZenoraOutboxPolicy.FAILED;
        pending.started = false;
        TLRPC.Message message = pending.messageObject.messageOwner;
        message.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
        pending.messageObject.forceUpdate = true;
        MessagesStorage.getInstance(pending.account).updateZenoraOutboxState(
                pending.clientMessageId, ZenoraOutboxPolicy.FAILED, 0L, false, true);
        NotificationCenter center = NotificationCenter.getInstance(pending.account);
        center.postNotificationName(NotificationCenter.messageSendError, pending.localId);
        ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(pending.messageObject);
        center.postNotificationName(NotificationCenter.replaceMessagesObjects, pending.dialogId, objects);
        finishTextQueue(pending);
        if (pending.callback != null) {
            pending.callback.onComplete(false, TextUtils.isEmpty(reason)
                    ? "ZenoraChat rejected the message." : reason);
        }
    }

    private static boolean reconcilePendingTextMessage(int account, long dialogId, long roomId,
                                                        String clientId, int serverId, int serverDate,
                                                        long replyToId, JSONObject replyPreview,
                                                        TLRPC.Peer peer, long selfId) {
        if (TextUtils.isEmpty(clientId) || serverId <= 0 || serverDate <= 0) return false;
        PendingTextSend pending;
        synchronized (lock) {
            Map<Long, HashMap<String, PendingTextSend>> pendingByDialog = pendingTextByClientId.get(account);
            HashMap<String, PendingTextSend> dialogPending =
                    pendingByDialog == null ? null : pendingByDialog.get(dialogId);
            pending = dialogPending == null ? null : dialogPending.get(clientId);
            if (pending == null || pending.roomId != roomId
                    || pending.replyToId != replyToId) return false;
            if (pending.resolved.get()) return true;
        }
        acknowledgePendingTextSend(pending, serverId, serverDate,
                replyToId, replyPreview, peer, selfId);
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

    private static void acknowledgePendingTextSend(PendingTextSend pending, int serverId, int serverDate,
                                                      long replyToId, JSONObject replyPreview,
                                                      TLRPC.Peer peer, long selfId) {
        if (pending == null || serverId <= 0 || serverDate <= 0
                || replyToId != pending.replyToId || !pending.resolved.compareAndSet(false, true)) return;

        TLRPC.Message message = pending.messageObject.messageOwner;
        boolean clearReplyPreview = false;
        if (replyToId > 0L) {
            TLRPC.TL_messageReplyHeader header = message.reply_to instanceof TLRPC.TL_messageReplyHeader
                    ? (TLRPC.TL_messageReplyHeader) message.reply_to
                    : new TLRPC.TL_messageReplyHeader();
            header.flags |= 16;
            header.reply_to_msg_id = (int) replyToId;
            message.reply_to = header;
            TLRPC.Message parent = createReplyPreviewMessage(
                    pending.dialogId, peer, serverDate, replyToId, replyPreview, selfId);
            message.replyMessage = parent;
            boolean previewAvailable = replyPreview != null
                    && "available".equals(replyPreview.optString("status", ""))
                    && parent != null && parent.from_id != null;
            if (message.params == null) message.params = new HashMap<>();
            message.params.put("zenora_reply_preview_status", previewAvailable ? "available" : "unavailable");
            pending.messageObject.replyMessageObject = parent == null
                    ? null : new MessageObject(pending.account, parent, false, false);
            clearReplyPreview = false;
        } else {
            message.reply_to = null;
            message.replyMessage = null;
            pending.messageObject.replyMessageObject = null;
        }
        int previousId = message.id;
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
        storage.acknowledgeZenoraTextMessage(
                pending.randomId, pending.dialogId, pending.localId, serverId,
                serverDate, pending.clientMessageId, message.replyMessage, clearReplyPreview, success -> {
                    if (!success) {
                        pending.resolved.set(false);
                        message.id = previousId;
                        message.local_id = pending.localId;
                        message.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                        pending.messageObject.forceUpdate = true;
                        scheduleUnknownTextSend(pending,
                                new IllegalStateException("Local message/outbox reconciliation failed."), false);
                        return;
                    }

                    synchronized (lock) {
                        Map<Long, HashMap<String, PendingTextSend>> pendingByDialog =
                                pendingTextByClientId.get(pending.account);
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
                        // ACK bookkeeping is not proof that the message exists in the
                        // current ChatActivity. Let history/live publication insert it;
                        // Telegram's native server-ID reconciliation handles duplicates.
                        Map<Long, Snapshot> accountCache = cache.get(pending.account);
                        if (accountCache != null) accountCache.remove(pending.dialogId);
                    }

                    center.postNotificationName(NotificationCenter.messageReceivedByServer,
                            pending.localId, serverId, null, pending.dialogId, 0L, -1, false);
                    center.postNotificationName(NotificationCenter.messageReceivedByServer2,
                            pending.localId, serverId, null, pending.dialogId, 0L, -1, false);
                    center.postNotificationName(NotificationCenter.dialogsNeedReload);
                    finishTextQueue(pending);
                    if (pending.callback != null) pending.callback.onComplete(true, "");
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
            long latest = state.newestLocalMessageId;
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
        ArrayList<Long> replyIds = new ArrayList<>();
        ArrayList<JSONObject> replyPreviews = new ArrayList<>();
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
            long replyToId = replyToIdFromRow(row);
            if (rawId <= previousId || rawId > Integer.MAX_VALUE
                    || senderId <= 0L || senderId > Integer.MAX_VALUE
                    || TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim()) || date <= 0
                    || !isValidReplyContract(row)
                    || !pageIds.add((int) rawId)) {
                return -1L;
            }
            ids.add((int) rawId);
            senderIds.add(senderId);
            bodies.add(body);
            clientMessageIds.add((row.isNull("client_message_id") ? "" : row.optString("client_message_id", "")));
            dates.add(date);
            replyIds.add(replyToId);
            replyPreviews.add(row.optJSONObject("reply_preview"));
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
        HashMap<Integer, MessageObject> liveObjectsById = new HashMap<>();
        HashMap<Integer, MessageObject> liveReplyParentsById = new HashMap<>();
        HashSet<Integer> committedIds = new HashSet<>();
        long committedCursor = afterMessageId;
        for (int i = 0; i < ids.size(); i++) {
            int messageId = ids.get(i);
            long senderId = senderIds.get(i);
            String clientMessageId = clientMessageIds.get(i);
            boolean reconciledClientMessage = false;
            if (!TextUtils.isEmpty(clientMessageId)) {
                // ACK and live history may race. Reconciliation is idempotent; do not
                // skip the server row just because the ACK was already committed.
                reconciledClientMessage = reconcilePendingTextMessage(account, dialogId, roomId,
                        clientMessageId, messageId, dates.get(i), replyIds.get(i),
                        replyPreviews.get(i), dialog.peer, selfId);
                reconciledClientMessage |= isCompletedTextMessage(
                        account, dialogId, clientMessageId, messageId);
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
            if (replyIds.get(i) > 0L) {
                JSONObject replyRow = new JSONObject();
                try {
                    replyRow.put("reply_to_id", replyIds.get(i));
                    replyRow.put("reply_preview", replyPreviews.get(i) == null
                            ? JSONObject.NULL : replyPreviews.get(i));
                } catch (Exception ignore) {
                }
                applyReplyContractToMessage(message, replyRow, dialogId, dialog.peer,
                        dates.get(i), selfId);
            }
            if (reconciledClientMessage && !TextUtils.isEmpty(clientMessageId)) {
                message.params = new HashMap<>();
                message.params.put("zenora_client_message_id", clientMessageId);
                message.params.put("zenora_room_id", String.valueOf(roomId));
            }
            MessageObject liveObject = new MessageObject(account, message, true, true);
            long liveReplyId = replyIds.get(i);
            if (liveReplyId > 0L && liveReplyId <= Integer.MAX_VALUE) {
                MessageObject replyObject = liveObjectsById.get((int) liveReplyId);
                if (replyObject == null) {
                    replyObject = liveReplyParentsById.get((int) liveReplyId);
                }
                if (replyObject == null && message.replyMessage != null) {
                    replyObject = new MessageObject(account, message.replyMessage, false, false);
                    liveReplyParentsById.put((int) liveReplyId, replyObject);
                }
                if (replyObject != null) {
                    liveObject.replyMessageObject = replyObject;
                    message.replyMessage = replyObject.messageOwner;
                }
            }
            liveObjectsById.put(messageId, liveObject);
            objects.add(liveObject);
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
                MessagesStorage.getInstance(account).putZenoraReplyPreviews(stored);
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
                    || ZenoraChatController.parseHistoryDate(row.optString("created_at", "")) <= 0
                    || !isValidReplyContract(row)) {
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

    private static void complete(int account, long dialogId, long roomId, long requestId,
                                 Snapshot snapshot, Exception error, int limit) {
        long currentRoomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        boolean stillCurrent = currentRoomId == roomId
                && ZenoraChatController.isZenoraEnabled();
        ArrayList<Target> targets;
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests = inFlight.get(account);
            Map<Long, Long> requestIds = initialRequestIds.get(account);
            Long activeRequestId = requestIds == null ? null : requestIds.get(dialogId);
            if (activeRequestId == null || activeRequestId != requestId) {
                FileLog.w("[ZenoraChat] Dropped stale initial history response for dialog " + dialogId);
                return;
            }
            targets = accountRequests == null ? null : accountRequests.remove(dialogId);
            if (requestIds != null) requestIds.remove(dialogId);
            if (requestIds != null && requestIds.isEmpty()) initialRequestIds.remove(account);
            if (accountRequests != null && accountRequests.isEmpty()) inFlight.remove(account);
            if (snapshot != null && stillCurrent) {
                Map<Long, Snapshot> accountCache =
                        cache.computeIfAbsent(account, ignored -> new HashMap<>());
                accountCache.put(dialogId, snapshot);
                Map<Long, OlderState> accountOlder =
                        olderStates.computeIfAbsent(account, ignored -> new HashMap<>());
                OlderState state = accountOlder.get(dialogId);
                if (state == null || state.roomId != roomId) {
                    state = new OlderState(snapshot);
                    accountOlder.put(dialogId, state);
                } else {
                    state.users = snapshot.users;
                    rememberMessageIds(state.seenMessageIds, snapshot.messages);
                    if (!state.hasMoreKnown) {
                        state.oldestMessageId = snapshot.oldestMessageId;
                        state.hasMoreOlder = snapshot.hasMoreOlder;
                        state.hasMoreKnown = true;
                        state.coverageValid = true;
                    } else if (snapshot.oldestMessageId > 0
                            && snapshot.oldestMessageId < state.oldestMessageId) {
                        state.oldestMessageId = snapshot.oldestMessageId;
                        state.hasMoreOlder = snapshot.hasMoreOlder;
                        state.coverageValid = true;
                    }
                    for (int i = 0; i < snapshot.messages.length(); i++) {
                        JSONObject row = snapshot.messages.optJSONObject(i);
                        if (row != null) {
                            state.newestLocalMessageId = Math.max(state.newestLocalMessageId,
                                    row.optLong("message_id", 0L));
                        }
                    }
                }
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
                boolean published = publish(account, dialogId, roomId, target, snapshot, error, limit);
                if (published && snapshot != null) {
                    OlderState state;
                    synchronized (lock) {
                        Map<Long, OlderState> accountOlder = olderStates.get(account);
                        state = accountOlder == null ? null : accountOlder.get(dialogId);
                    }
                    if (state != null) {
                        MessagesStorage.getInstance(account).saveZenoraHistoryState(
                                roomId, dialogId, ZenoraApiClient.getCurrentUserId(),
                                state.oldestMessageId, state.hasMoreOlder, state.coverageValid);
                    }
                }
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
                String clientMessageId = (row.isNull("client_message_id") ? "" : row.optString("client_message_id", ""));
                int serverDate = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
                boolean reconciledClientMessage = false;
                if (!TextUtils.isEmpty(clientMessageId)) {
                    // Reconcile any still-pending local bubble with the server identity.
                    // A completed client ID is historical metadata, not a reason to
                    // suppress a server row for a newly created ChatActivity.
                    reconciledClientMessage = reconcilePendingTextMessage(account, dialogId, roomId,
                            clientMessageId, messageId, serverDate,
                            replyToIdFromRow(row), row.optJSONObject("reply_preview"),
                            dialog.peer, selfId);
                    reconciledClientMessage |= isCompletedTextMessage(
                            account, dialogId, clientMessageId, messageId);
                }
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
                applyReplyContractToMessage(message, row, dialogId, dialog.peer, date, selfId);
                if (reconciledClientMessage && !TextUtils.isEmpty(clientMessageId)) {
                    message.params = new HashMap<>();
                    message.params.put("zenora_client_message_id", clientMessageId);
                    message.params.put("zenora_room_id", String.valueOf(roomId));
                }
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
        boolean isCache = error != null;
        if (error != null) {
            // A failed API request is a cache-only, non-terminal completion. This
            // releases ChatActivity's loading index without declaring server history ended.
            loadType = MessagesController.LOAD_BACKWARD;
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
                target.olderPage
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
                isCache,
                0,
                true,
                false,
                null
        );
        MessagesStorage.getInstance(account).putZenoraReplyPreviews(result.messages);
        if (!target.olderPage) {
            ZenoraLiveMessageSyncController.onHistoryApplied(account, dialogId, roomId, target.classGuid);
        }
        FileLog.d("[ZenoraChat] Native text history page published; dialog="
                + dialogId + ", count=" + result.messages.size()
                + ", older=" + target.olderPage + ", failed=" + (error != null));
        return true;
    }
}
