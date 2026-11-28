package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Durable, local-first queue for ordinary Zenora text-message edits.
 *
 * The edited message row and this operation are committed by MessagesStorage
 * in one transaction. Network failures keep the same operation_id and payload;
 * only an acknowledged edit retires the row.
 */
public final class ZenoraEditOutboxController {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();
    private static final Map<Integer, Map<Long, ArrayDeque<PendingEdit>>> queues = new HashMap<>();
    private static final Map<Integer, HashSet<Long>> activeRooms = new HashMap<>();
    private static final Map<Integer, Long> restoredUsers = new HashMap<>();
    private static final HashSet<Integer> restoreInFlight = new HashSet<>();

    private static final class PendingEdit {
        final int account;
        final long dialogId;
        final long roomId;
        final long userId;
        final int messageId;
        final long expectedVersion;
        final String operationId;
        final String body;
        final long createdAt;
        final MessageObject target;
        final ZenoraApiClient.Callback callback;
        int attemptCount;
        long nextAttemptAt;
        boolean started;
        boolean retryScheduled;
        boolean terminal;

        PendingEdit(int account, long dialogId, long roomId, long userId, int messageId,
                    long expectedVersion, String operationId, String body, long createdAt,
                    MessageObject target, ZenoraApiClient.Callback callback) {
            this.account = account;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.userId = userId;
            this.messageId = messageId;
            this.expectedVersion = expectedVersion;
            this.operationId = operationId;
            this.body = body;
            this.createdAt = createdAt;
            this.target = target;
            this.callback = callback;
        }
    }

    private ZenoraEditOutboxController() {
    }

    public static String createOperationId(int account, long userId, long roomId,
                                           long messageId, long expectedVersion, String body) {
        String seed = "zenora-edit:" + account + ":" + userId + ":" + roomId + ":"
                + messageId + ":" + expectedVersion + ":" + (body == null ? "" : body);
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public static boolean enqueue(int account, long dialogId, long roomId, MessageObject target,
                                  String body, ArrayList<TLRPC.MessageEntity> entities,
                                  long expectedVersion, long expectedUserId, Runnable onPersisted,
                                  ZenoraApiClient.Callback callback) {
        if (account < 0 || dialogId == 0L || roomId <= 0L || expectedUserId <= 0L
                || expectedUserId != UserConfig.getInstance(account).getClientUserId()
                || ZenoraApiClient.getCurrentUserId() != expectedUserId
                || !ZenoraChatController.isZenoraDialog(account, dialogId)
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId
                || target == null || target.messageOwner == null || target.getId() <= 0
                || target.getDialogId() != dialogId || expectedVersion <= 0L
                || expectedVersion >= Integer.MAX_VALUE || TextUtils.isEmpty(body)
                || TextUtils.isEmpty(body.trim())) {
            dispatch(callback, null, new IllegalArgumentException("Invalid Zenora optimistic edit"));
            return false;
        }

        final TLRPC.Message message = target.messageOwner;
        final String originalBody = message.message == null ? "" : message.message;
        final ArrayList<TLRPC.MessageEntity> originalEntities = message.entities == null
                ? new ArrayList<>() : new ArrayList<>(message.entities);
        final long originalVersion = Math.max(0L, message.zenoraEditVersion);
        final int originalEditDate = message.edit_date;
        final int originalFlags = message.flags;
        final boolean originalEditHide = message.edit_hide;
        final String operationId = createOperationId(account, expectedUserId, roomId,
                target.getId(), expectedVersion, body);
        final long createdAt = System.currentTimeMillis();
        final long optimisticVersion = expectedVersion + 1L;

        // Publish the new payload in the existing MessageObject. It is not
        // visible until the same object and its Outbox row commit to SQLite.
        message.message = body;
        message.entities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
        if (message.entities.isEmpty()) message.flags &= ~(1 << 7);
        else message.flags |= 1 << 7;
        message.flags |= 1 << 8;
        message.zenoraEditVersion = optimisticVersion;
        message.edit_date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.edit_hide = false;
        if (message.params == null) message.params = new HashMap<>();
        message.params.put("zenora_room_id", String.valueOf(roomId));
        // Keep the cached text projection unchanged until the SQLite transaction commits.

        MessagesStorage.getInstance(account).storeZenoraMessageEdit(
                message, roomId, expectedUserId, operationId, expectedVersion,
                originalBody, body, error -> {
                    if (error != null) {
                        message.message = originalBody;
                        message.entities = new ArrayList<>(originalEntities);
                        message.zenoraEditVersion = originalVersion;
                        message.edit_date = originalEditDate;
                        message.flags = originalFlags;
                        message.edit_hide = originalEditHide;
                        target.updateMessageText();
                        target.forceUpdate = true;
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.replaceMessagesObjects, dialogId,
                                new ArrayList<>(java.util.Collections.singletonList(target)));
                        dispatch(callback, null, error);
                        return;
                    }

                    // Reuse the canonical edit projector for Reply previews,
                    // dialog previews, and ChatActivity's visible row.
                    ZenoraHistoryController.applyEditedMessage(account, dialogId, roomId,
                            target, body, entities, null, optimisticVersion);

                    PendingEdit pending = new PendingEdit(account, dialogId, roomId,
                            expectedUserId, target.getId(), expectedVersion, operationId,
                            body, createdAt, target, callback);
                    boolean start = enqueuePersisted(pending);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.dialogsNeedReload);
                    if (onPersisted != null) onPersisted.run();
                    if (start) start(pending);
                });
        return true;
    }

    private static boolean enqueuePersisted(PendingEdit pending) {
        synchronized (lock) {
            ArrayDeque<PendingEdit> queue = queues
                    .computeIfAbsent(pending.account, ignored -> new HashMap<>())
                    .computeIfAbsent(pending.roomId, ignored -> new ArrayDeque<>());
            for (PendingEdit existing : queue) {
                if (existing.messageId == pending.messageId && !existing.terminal) return false;
            }
            queue.addLast(pending);
            return queue.peekFirst() == pending
                    && activeRooms.computeIfAbsent(pending.account, ignored -> new HashSet<>())
                    .add(pending.roomId);
        }
    }

    public static void restorePendingMessageEdits(int account) {
        long activeUserId = ZenoraApiClient.getCurrentUserId();
        if (activeUserId <= 0L || !ZenoraApiClient.isZenoraSessionEnabled()
                || activeUserId != UserConfig.getInstance(account).getClientUserId()) return;
        synchronized (lock) {
            Long restored = restoredUsers.get(account);
            if (restored != null && restored == activeUserId) {
                resumeDue(account, activeUserId);
                return;
            }
            if (!restoreInFlight.add(account)) return;
        }

        MessagesStorage.getInstance(account).loadZenoraEditOutbox((records, error) -> {
            synchronized (lock) {
                restoreInFlight.remove(account);
            }
            if (error != null || records == null
                    || ZenoraApiClient.getCurrentUserId() != activeUserId) return;
            boolean waitingForRoomMap = false;
            ArrayList<PendingEdit> restored = new ArrayList<>();
            for (MessagesStorage.ZenoraEditOutboxRecord record : records) {
                if (record.zenoraUserId != activeUserId) continue;
                if (ZenoraChatController.getRoomIdForDialog(account, record.dialogId) != record.roomId) {
                    waitingForRoomMap = true;
                    continue;
                }
                if ("CONFLICT".equals(record.status) || "FAILED".equals(record.status)) continue;

                PendingEdit pending = new PendingEdit(account, record.dialogId, record.roomId,
                        record.zenoraUserId, record.messageId, record.expectedEditVersion,
                        record.operationId, record.newBody, record.createdAt, null, null);
                pending.attemptCount = record.attemptCount;
                pending.nextAttemptAt = record.nextAttemptAt;
                if ("SENDING".equals(record.status)) {
                    pending.nextAttemptAt = System.currentTimeMillis();
                    MessagesStorage.getInstance(account).updateZenoraEditOutboxState(
                            record.operationId, "UNKNOWN", pending.nextAttemptAt, false);
                }
                if (enqueuePersisted(pending)) restored.add(pending);
            }
            synchronized (lock) {
                if (!waitingForRoomMap) restoredUsers.put(account, activeUserId);
            }
            for (PendingEdit pending : restored) {
                if (pending.nextAttemptAt <= System.currentTimeMillis()) {
                    if (isHead(pending)) start(pending);
                } else {
                    scheduleRetry(pending);
                }
            }
        });
    }

    private static boolean isHead(PendingEdit pending) {
        synchronized (lock) {
            ArrayDeque<PendingEdit> queue = getQueueLocked(pending.account, pending.roomId);
            return queue != null && queue.peekFirst() == pending;
        }
    }

    private static void resumeDue(int account, long activeUserId) {
        ArrayList<PendingEdit> heads = new ArrayList<>();
        synchronized (lock) {
            Map<Long, ArrayDeque<PendingEdit>> byRoom = queues.get(account);
            if (byRoom == null) return;
            for (ArrayDeque<PendingEdit> queue : byRoom.values()) {
                PendingEdit head = queue.peekFirst();
                if (head != null && !head.started && !head.terminal
                        && head.userId == activeUserId) heads.add(head);
            }
        }
        for (PendingEdit pending : heads) {
            if (pending.nextAttemptAt <= System.currentTimeMillis()) start(pending);
            else scheduleRetry(pending);
        }
    }

    private static void start(PendingEdit pending) {
        if (pending == null || pending.terminal || pending.started
                || ZenoraApiClient.getCurrentUserId() != pending.userId
                || UserConfig.getInstance(pending.account).getClientUserId() != pending.userId
                || ZenoraChatController.getRoomIdForDialog(pending.account, pending.dialogId) != pending.roomId) {
            return;
        }
        synchronized (lock) {
            ArrayDeque<PendingEdit> queue = getQueueLocked(pending.account, pending.roomId);
            if (queue == null || queue.peekFirst() != pending || pending.started || pending.terminal) return;
            pending.started = true;
            pending.retryScheduled = false;
        }
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, "SENDING", 0L, false);
        ZenoraApiClient.editMessage(pending.roomId, pending.messageId, pending.body,
                pending.expectedVersion, pending.userId, pending.operationId,
                (response, error) -> {
                    if (pending.terminal) return;
                    if (error != null) {
                        handleFailure(pending, error);
                        return;
                    }
                    JSONObject edited = response == null ? null : response.optJSONObject("message");
                    if (edited == null || edited.optLong("message_id", 0L) != pending.messageId
                            || edited.optLong("room_id", 0L) != pending.roomId
                            || !pending.body.equals(edited.optString("text", ""))
                            || edited.optLong("edit_version", 0L) != pending.expectedVersion + 1L) {
                        handleFailure(pending, new IllegalStateException(
                                "Zenora returned an ambiguous edit acknowledgement"));
                        return;
                    }
                    MessagesStorage.getInstance(pending.account).completeZenoraMessageEdit(
                            pending.operationId, success -> {
                                if (!success) {
                                    scheduleUnknown(pending, new IllegalStateException(
                                            "Edit was accepted but its local ACK could not be committed"));
                                    return;
                                }
                                String editedAt = edited.isNull("edited_at")
                                        ? "" : edited.optString("edited_at", "");
                                ZenoraHistoryController.reconcileCanonicalEditPayload(
                                        pending.account, pending.dialogId, pending.roomId,
                                        pending.messageId, pending.body, editedAt,
                                        edited.optLong("edit_version", 0L));
                                finish(pending, response, null);
                            });
                });
    }

    private static void handleFailure(PendingEdit pending, Exception error) {
        if (error instanceof ZenoraApiClient.ApiException) {
            ZenoraApiClient.ApiException api = (ZenoraApiClient.ApiException) error;
            if ("EDIT_VERSION_CONFLICT".equals(api.errorCode) || api.statusCode == 409) {
                markTerminal(pending, "CONFLICT", error);
                return;
            }
            if (api.statusCode == 401 || "CSRF_INVALID".equals(api.errorCode)
                    || ZenoraOutboxPolicy.isTransientHttpStatus(api.statusCode)) {
                scheduleUnknown(pending, error);
                return;
            }
            if (ZenoraOutboxPolicy.isDefinitiveHttpStatus(api.statusCode)) {
                markTerminal(pending, "FAILED", error);
                return;
            }
        }
        if (error instanceof IOException || error instanceof java.util.concurrent.RejectedExecutionException) {
            scheduleUnknown(pending, error);
            return;
        }
        // Unclassified transport/runtime failures are treated as ambiguous. The
        // stable operation_id makes replay safe even if the server committed.
        scheduleUnknown(pending, error);
    }

    private static void scheduleUnknown(PendingEdit pending, Exception reason) {
        if (pending == null || pending.terminal) return;
        long now = System.currentTimeMillis();
        pending.started = false;
        pending.attemptCount++;
        int jitter = ThreadLocalRandom.current().nextInt(750, 1251);
        long next = ZenoraOutboxPolicy.nextAttemptAt(now, pending.attemptCount, jitter);
        pending.nextAttemptAt = next;
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, "UNKNOWN", next, true);
        if (reason != null) FileLog.w("[ZenoraChat] Edit remains durable and will retry: "
                + reason.getMessage());
        scheduleRetry(pending);
    }

    private static void scheduleRetry(PendingEdit pending) {
        if (pending == null || pending.terminal || pending.retryScheduled) return;
        pending.retryScheduled = true;
        long delay = Math.max(0L, pending.nextAttemptAt - System.currentTimeMillis());
        handler.postDelayed(() -> {
            pending.retryScheduled = false;
            if (pending.terminal) return;
            if (ZenoraApiClient.getCurrentUserId() != pending.userId
                    || UserConfig.getInstance(pending.account).getClientUserId() != pending.userId) return;
            start(pending);
        }, delay);
    }

    private static void markTerminal(PendingEdit pending, String status, Exception error) {
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, status, 0L, false);
        if (pending.target != null) {
            ZenoraHistoryController.refreshEditedMessage(pending.account, pending.dialogId,
                    pending.roomId, pending.target, (updated, refreshError) -> {
                        if (!updated) FileLog.w("[ZenoraChat] Could not refresh canonical message after edit "
                                + status + ": " + (refreshError == null ? "unknown" : refreshError.getMessage()));
                    });
        }
        FileLog.w("[ZenoraChat] Optimistic edit entered " + status + " state: "
                + (error == null ? "unknown" : error.getMessage()));
        finish(pending, null, error);
    }

    private static void finish(PendingEdit pending, JSONObject response, Exception error) {
        if (pending == null || pending.terminal) return;
        pending.terminal = true;
        PendingEdit next = null;
        synchronized (lock) {
            ArrayDeque<PendingEdit> queue = getQueueLocked(pending.account, pending.roomId);
            if (queue != null) {
                boolean wasHead = queue.peekFirst() == pending;
                if (wasHead) queue.removeFirst();
                else queue.remove(pending);
                if (queue.isEmpty()) {
                    Map<Long, ArrayDeque<PendingEdit>> byRoom = queues.get(pending.account);
                    if (byRoom != null) {
                        byRoom.remove(pending.roomId);
                        if (byRoom.isEmpty()) queues.remove(pending.account);
                    }
                    HashSet<Long> active = activeRooms.get(pending.account);
                    if (active != null) {
                        active.remove(pending.roomId);
                        if (active.isEmpty()) activeRooms.remove(pending.account);
                    }
                } else if (wasHead) {
                    next = queue.peekFirst();
                    if (next != null) next.started = false;
                }
            }
        }
        if (pending.callback != null) pending.callback.onComplete(response, error);
        if (next != null) start(next);
    }

    private static ArrayDeque<PendingEdit> getQueueLocked(int account, long roomId) {
        Map<Long, ArrayDeque<PendingEdit>> byRoom = queues.get(account);
        return byRoom == null ? null : byRoom.get(roomId);
    }

    private static void dispatch(ZenoraApiClient.Callback callback, JSONObject response, Exception error) {
        if (callback != null) handler.post(() -> callback.onComplete(response, error));
    }

    public static void clearAccount(int account) {
        synchronized (lock) {
            queues.remove(account);
            activeRooms.remove(account);
            restoredUsers.remove(account);
            restoreInFlight.remove(account);
        }
    }
}
