package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable, optimistic sender for ordinary Zenora text-message edits.
 *
 * The edited messages_v2 row and its operation are committed together in
 * SQLite. HTTP and Live Sync acknowledgements converge on the same operation
 * key, so a lost response or process death never loses the user's edit.
 */
public final class ZenoraEditOutboxController {
    private static final Object lock = new Object();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Map<Integer, HashMap<String, PendingEdit>> byOperation = new HashMap<>();
    private static final Map<Integer, HashMap<String, PendingEdit>> byMessage = new HashMap<>();
    private static final Map<Integer, Long> restoredUsers = new HashMap<>();
    private static final java.util.HashSet<Integer> restoreInFlight = new java.util.HashSet<>();

    private static final String STATUS_CONFLICT = "CONFLICT";
    private static final String STATUS_FAILED = "FAILED";

    public interface SubmitCallback {
        void onPersisted(boolean success, String errorMessage);
    }

    private static final class PendingEdit {
        final int account;
        final long roomId;
        final long dialogId;
        final int messageId;
        final long userId;
        final long expectedVersion;
        final String operationId;
        final String originalBody;
        final ArrayList<TLRPC.MessageEntity> originalEntities;
        final String newBody;
        final ArrayList<TLRPC.MessageEntity> newEntities;
        final long createdAt;
        final MessageObject target;
        final AtomicBoolean resolved = new AtomicBoolean(false);
        volatile String status;
        volatile int attemptCount;
        volatile long nextAttemptAt;
        volatile boolean started;
        volatile boolean retryScheduled;

        PendingEdit(int account, long roomId, long dialogId, int messageId, long userId,
                    long expectedVersion, String operationId, String originalBody,
                    ArrayList<TLRPC.MessageEntity> originalEntities, String newBody,
                    ArrayList<TLRPC.MessageEntity> newEntities, String status, long createdAt,
                    long nextAttemptAt, int attemptCount, MessageObject target) {
            this.account = account;
            this.roomId = roomId;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.userId = userId;
            this.expectedVersion = expectedVersion;
            this.operationId = operationId;
            this.originalBody = originalBody;
            this.originalEntities = originalEntities == null ? new ArrayList<>() : new ArrayList<>(originalEntities);
            this.newBody = newBody;
            this.newEntities = newEntities == null ? new ArrayList<>() : new ArrayList<>(newEntities);
            this.status = status;
            this.createdAt = createdAt;
            this.nextAttemptAt = nextAttemptAt;
            this.attemptCount = attemptCount;
            this.target = target;
        }

        long optimisticVersion() {
            return expectedVersion + 1L;
        }
    }

    private ZenoraEditOutboxController() {
    }

    private static String messageKey(int account, long roomId, int messageId) {
        return account + ":" + roomId + ":" + messageId;
    }

    private static boolean isCurrentSession(int account, long dialogId, long roomId, long userId) {
        return account >= 0 && dialogId != 0L && roomId > 0L && userId > 0L
                && ZenoraChatController.isZenoraDialog(account, dialogId)
                && ZenoraChatController.getRoomIdForDialog(account, dialogId) == roomId
                && ZenoraApiClient.isZenoraSessionEnabled()
                && ZenoraApiClient.getCurrentUserId() == userId
                && UserConfig.getInstance(account).getClientUserId() == userId;
    }

    private static void dispatchSubmit(SubmitCallback callback, boolean success, String error) {
        if (callback != null) handler.post(() -> callback.onPersisted(success, error));
    }

    /**
     * Applies the new text immediately, then commits the changed message and
     * durable operation in one SQLite transaction before starting network I/O.
     */
    public static boolean submit(int account, long dialogId, long roomId, MessageObject target,
                                 String newBody, ArrayList<TLRPC.MessageEntity> newEntities,
                                 long expectedVersion, long expectedUserId,
                                 SubmitCallback callback) {
        if (!isCurrentSession(account, dialogId, roomId, expectedUserId)
                || target == null || target.messageOwner == null
                || !ZenoraMessageMutationController.canEditPlainTextMessage(account, dialogId, target)
                || target.getId() <= 0 || expectedVersion <= 0L
                || expectedVersion >= Integer.MAX_VALUE
                || TextUtils.isEmpty(newBody) || TextUtils.isEmpty(newBody.trim())) {
            dispatchSubmit(callback, false, "The Zenora edit could not be validated.");
            return false;
        }

        String key = messageKey(account, roomId, target.getId());
        synchronized (lock) {
            HashMap<String, PendingEdit> accountPending = byMessage.get(account);
            PendingEdit existing = accountPending == null ? null : accountPending.get(key);
            if (existing != null && !existing.resolved.get()
                    && !STATUS_FAILED.equals(existing.status)
                    && !STATUS_CONFLICT.equals(existing.status)) {
                dispatchSubmit(callback, false, "This message already has a pending edit.");
                return false;
            }
        }

        TLRPC.Message message = target.messageOwner;
        final String originalBody = message.message == null ? "" : message.message;
        final ArrayList<TLRPC.MessageEntity> originalEntities = message.entities == null
                ? new ArrayList<>() : new ArrayList<>(message.entities);
        final int originalEditDate = message.edit_date;
        final long originalEditVersion = message.zenoraEditVersion;
        final int originalFlags = message.flags;
        final boolean originalEditHide = message.edit_hide;
        final HashMap<String, String> originalParams = message.params == null
                ? null : new HashMap<>(message.params);
        final String operationId = UUID.randomUUID().toString();
        final long optimisticVersion = expectedVersion + 1L;
        final ArrayList<TLRPC.MessageEntity> safeEntities = newEntities == null
                ? new ArrayList<>() : new ArrayList<>(newEntities);

        // Optimistic in-memory projection. On a local SQLite failure it is rolled back.
        message.message = newBody;
        message.entities = new ArrayList<>(safeEntities);
        if (message.entities.isEmpty()) message.flags &= ~(1 << 7);
        else message.flags |= 1 << 7;
        message.flags |= 1 << 8;
        message.edit_date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        message.edit_hide = false;
        message.zenoraEditVersion = optimisticVersion;
        if (message.params == null) message.params = new HashMap<>();
        message.params.put("zenora_room_id", String.valueOf(roomId));
        ZenoraMessageMutationController.applyEditMetadata(message, null, optimisticVersion);
        target.updateMessageText();
        ArrayList<MessageObject> immediate = new ArrayList<>();
        immediate.add(target);
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.replaceMessagesObjects, dialogId, immediate);

        MessagesStorage.getInstance(account).storeZenoraMessageEdit(
                message, roomId, expectedUserId, expectedVersion, operationId,
                originalBody, originalEntities, newBody, safeEntities, error -> {
                    if (error != null) {
                        message.message = originalBody;
                        message.entities = new ArrayList<>(originalEntities);
                        message.edit_date = originalEditDate;
                        message.zenoraEditVersion = originalEditVersion;
                        message.flags = originalFlags;
                        message.edit_hide = originalEditHide;
                        message.params = originalParams == null ? null : new HashMap<>(originalParams);
                        target.updateMessageText();
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.replaceMessagesObjects, dialogId,
                                new ArrayList<>(java.util.Collections.singletonList(target)));
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.dialogsNeedReload);
                        dispatchSubmit(callback, false, "Could not save the edit to the local database.");
                        return;
                    }

                    PendingEdit pending = new PendingEdit(account, roomId, dialogId, target.getId(),
                            expectedUserId, expectedVersion, operationId, originalBody,
                            originalEntities, newBody, safeEntities, ZenoraOutboxPolicy.SENDING,
                            System.currentTimeMillis(), 0L, 0, target);
                    synchronized (lock) {
                        byOperation.computeIfAbsent(account, ignored -> new HashMap<>())
                                .put(operationId, pending);
                        byMessage.computeIfAbsent(account, ignored -> new HashMap<>()).put(key, pending);
                    }

                    ZenoraHistoryController.applyEditedMessage(account, dialogId, roomId, target,
                            newBody, safeEntities, null, optimisticVersion);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.dialogsNeedReload);
                    dispatchSubmit(callback, true, null);

                    // The durable row survives account changes, but is only sent by
                    // the same authenticated Zenora identity that created it.
                    if (isCurrentSession(account, dialogId, roomId, expectedUserId)) {
                        start(pending);
                    }
                });
        return true;
    }

    /** Rebuild pending edit operations after the account's dialog->room map is ready. */
    public static void restorePendingEdits(int account) {
        long activeUserId = ZenoraApiClient.getCurrentUserId();
        if (activeUserId <= 0L || !ZenoraApiClient.isZenoraSessionEnabled()) return;
        synchronized (lock) {
            Long restoredUser = restoredUsers.get(account);
            if (restoredUser != null && restoredUser == activeUserId) {
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
                if (record.messageId <= 0 || record.expectedEditVersion <= 0L
                        || TextUtils.isEmpty(record.operationId) || TextUtils.isEmpty(record.newBody)) {
                    FileLog.w("[ZenoraChat] Ignoring malformed durable edit operation.");
                    continue;
                }
                String key = messageKey(account, record.roomId, record.messageId);
                PendingEdit pending = new PendingEdit(account, record.roomId, record.dialogId,
                        record.messageId, record.zenoraUserId, record.expectedEditVersion,
                        record.operationId, record.originalBody, record.originalEntities,
                        record.newBody, record.newEntities, record.status, record.createdAt,
                        record.nextAttemptAt, record.attemptCount, null);
                synchronized (lock) {
                    HashMap<String, PendingEdit> accountPending = byMessage.computeIfAbsent(
                            account, ignored -> new HashMap<>());
                    PendingEdit existing = accountPending.get(key);
                    if (existing != null && !existing.resolved.get()) continue;
                    byOperation.computeIfAbsent(account, ignored -> new HashMap<>())
                            .put(record.operationId, pending);
                    accountPending.put(key, pending);
                }

                // An app death during SENDING has an unknown server outcome. Replay
                // with the same operation_id so the API's idempotency guard can ACK it.
                if (ZenoraOutboxPolicy.SENDING.equals(pending.status)) {
                    pending.status = ZenoraOutboxPolicy.UNKNOWN;
                    pending.nextAttemptAt = System.currentTimeMillis();
                    MessagesStorage.getInstance(account).updateZenoraEditOutboxState(
                            pending.operationId, ZenoraOutboxPolicy.UNKNOWN,
                            pending.nextAttemptAt, false);
                }
                if (!STATUS_FAILED.equals(pending.status)
                        && !STATUS_CONFLICT.equals(pending.status)) {
                    restored.add(pending);
                }
            }
            synchronized (lock) {
                if (!waitingForRoomMap) restoredUsers.put(account, activeUserId);
            }
            for (PendingEdit pending : restored) {
                if (pending.nextAttemptAt <= System.currentTimeMillis()) start(pending);
                else scheduleRetry(pending);
            }
        });
    }

    private static void resumeDue(int account, long activeUserId) {
        ArrayList<PendingEdit> due = new ArrayList<>();
        synchronized (lock) {
            HashMap<String, PendingEdit> values = byOperation.get(account);
            if (values == null) return;
            for (PendingEdit pending : values.values()) {
                if (pending.userId == activeUserId && !pending.resolved.get()
                        && !STATUS_FAILED.equals(pending.status)
                        && !STATUS_CONFLICT.equals(pending.status)) {
                    due.add(pending);
                }
            }
        }
        for (PendingEdit pending : due) {
            if (pending.nextAttemptAt <= System.currentTimeMillis()) start(pending);
            else scheduleRetry(pending);
        }
    }

    private static void start(PendingEdit pending) {
        if (pending == null || pending.resolved.get()
                || STATUS_FAILED.equals(pending.status) || STATUS_CONFLICT.equals(pending.status)
                || !isCurrentSession(pending.account, pending.dialogId, pending.roomId, pending.userId)) {
            return;
        }
        synchronized (lock) {
            if (pending.started || pending.resolved.get()) return;
            pending.started = true;
            pending.retryScheduled = false;
            pending.status = ZenoraOutboxPolicy.SENDING;
        }
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, ZenoraOutboxPolicy.SENDING, 0L, false);
        ZenoraApiClient.editMessage(pending.roomId, pending.messageId, pending.newBody,
                pending.expectedVersion, pending.userId, pending.operationId, (response, error) -> {
                    if (pending.resolved.get()) return;
                    if (error != null) {
                        handleFailure(pending, error);
                        return;
                    }
                    JSONObject edited = response == null ? null : response.optJSONObject("message");
                    String body = edited == null ? "" : edited.optString("text", "");
                    String editedAt = edited == null ? "" : edited.optString("edited_at", "");
                    long version = edited == null ? 0L : edited.optLong("edit_version", 0L);
                    long messageId = edited == null ? 0L : edited.optLong("message_id", 0L);
                    long responseRoom = edited == null ? 0L : edited.optLong("room_id", 0L);
                    long senderId = edited == null ? 0L : edited.optLong("sender_id", 0L);
                    if (messageId != pending.messageId || responseRoom != pending.roomId
                            || senderId != pending.userId || !pending.newBody.equals(body)
                            || TextUtils.isEmpty(editedAt)
                            || version != pending.optimisticVersion()) {
                        scheduleUnknown(pending,
                                new IllegalStateException("Zenora returned a mismatched edit acknowledgement."),
                                false);
                        return;
                    }
                    acknowledge(pending, editedAt, version);
                });
    }

    private static void handleFailure(PendingEdit pending, Exception error) {
        if (pending == null || pending.resolved.get()) return;
        if (error instanceof ZenoraApiClient.ApiException) {
            ZenoraApiClient.ApiException apiError = (ZenoraApiClient.ApiException) error;
            if ("EDIT_VERSION_CONFLICT".equals(apiError.errorCode) || apiError.statusCode == 409) {
                markTerminal(pending, STATUS_CONFLICT, error);
                return;
            }
            if (apiError.statusCode == 401) {
                scheduleUnknown(pending, error, true);
                return;
            }
            if (ZenoraOutboxPolicy.isDefinitiveHttpStatus(apiError.statusCode)
                    && !"CSRF_INVALID".equals(apiError.errorCode)) {
                markTerminal(pending, STATUS_FAILED, error);
                return;
            }
        }
        scheduleUnknown(pending, error, false);
    }

    private static void markTerminal(PendingEdit pending, String status, Exception error) {
        pending.started = false;
        pending.status = status;
        pending.attemptCount++;
        pending.nextAttemptAt = 0L;
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, status, 0L, true);
        FileLog.w("[ZenoraChat] Durable edit retained as " + status + " for message "
                + pending.messageId + ": " + (error == null ? "" : error.getMessage()));
    }

    private static void scheduleUnknown(PendingEdit pending, Exception reason,
                                        boolean authenticationFailure) {
        if (pending == null || pending.resolved.get()) return;
        pending.started = false;
        pending.status = ZenoraOutboxPolicy.UNKNOWN;
        pending.attemptCount++;
        int jitter = ThreadLocalRandom.current().nextInt(750, 1251);
        pending.nextAttemptAt = authenticationFailure
                ? System.currentTimeMillis() + 60_000L
                : ZenoraOutboxPolicy.nextAttemptAt(System.currentTimeMillis(),
                        pending.attemptCount, jitter);
        MessagesStorage.getInstance(pending.account).updateZenoraEditOutboxState(
                pending.operationId, ZenoraOutboxPolicy.UNKNOWN,
                pending.nextAttemptAt, true);
        if (reason != null) {
            FileLog.w("[ZenoraChat] Edit remains durable and will retry: " + reason.getMessage());
        }
        scheduleRetry(pending);
    }

    private static void scheduleRetry(PendingEdit pending) {
        if (pending == null || pending.retryScheduled || pending.resolved.get()
                || STATUS_FAILED.equals(pending.status) || STATUS_CONFLICT.equals(pending.status)) return;
        long delay = Math.max(0L, pending.nextAttemptAt - System.currentTimeMillis());
        pending.retryScheduled = true;
        handler.postDelayed(() -> {
            pending.retryScheduled = false;
            if (pending.resolved.get() || STATUS_FAILED.equals(pending.status)
                    || STATUS_CONFLICT.equals(pending.status)
                    || !isCurrentSession(pending.account, pending.dialogId, pending.roomId, pending.userId)) {
                return;
            }
            if (System.currentTimeMillis() >= pending.nextAttemptAt) start(pending);
        }, delay);
    }

    private static void acknowledge(PendingEdit pending, String editedAt, long editVersion) {
        if (pending == null || !pending.resolved.compareAndSet(false, true)) return;
        MessagesStorage.getInstance(pending.account).acknowledgeZenoraMessageEdit(
                pending.operationId, pending.roomId, pending.dialogId, pending.messageId,
                pending.userId, pending.expectedVersion, success -> {
                    if (!success) {
                        pending.resolved.set(false);
                        scheduleUnknown(pending,
                                new IllegalStateException("Local edit Outbox acknowledgement failed."), false);
                        return;
                    }
                    removePending(pending);
                    ZenoraHistoryController.reconcileCanonicalEditPayload(
                            pending.account, pending.dialogId, pending.roomId,
                            pending.messageId, pending.newBody, editedAt, editVersion);
                    FileLog.d("[ZenoraChat] Durable edit acknowledged; message="
                            + pending.messageId + ", version=" + editVersion);
                });
    }

    private static void removePending(PendingEdit pending) {
        synchronized (lock) {
            HashMap<String, PendingEdit> operations = byOperation.get(pending.account);
            if (operations != null) {
                operations.remove(pending.operationId);
                if (operations.isEmpty()) byOperation.remove(pending.account);
            }
            HashMap<String, PendingEdit> messages = byMessage.get(pending.account);
            if (messages != null) {
                String key = messageKey(pending.account, pending.roomId, pending.messageId);
                if (messages.get(key) == pending) messages.remove(key);
                if (messages.isEmpty()) byMessage.remove(pending.account);
            }
        }
    }

    /** Match a committed Live Sync edit event to this account's pending operation. */
    public static void reconcileMutationEvent(int account, long dialogId, long roomId,
                                              long actorUserId, int messageId,
                                              String body, String editedAt, long editVersion,
                                              String eventOperationId) {
        PendingEdit pending;
        synchronized (lock) {
            HashMap<String, PendingEdit> messages = byMessage.get(account);
            pending = messages == null ? null : messages.get(messageKey(account, roomId, messageId));
        }
        if (pending == null || pending.resolved.get() || pending.dialogId != dialogId
                || pending.userId != actorUserId || editVersion != pending.optimisticVersion()
                || !pending.newBody.equals(body)
                || (!TextUtils.isEmpty(eventOperationId)
                    && !pending.operationId.equals(eventOperationId))) {
            return;
        }
        acknowledge(pending, editedAt, editVersion);
    }

    /** Basic-history/live rows can also prove a lost HTTP acknowledgement. */
    public static void reconcileHistoryMessage(int account, long dialogId, long roomId,
                                               long senderId, int messageId, String body,
                                               String editedAt, long editVersion) {
        reconcileMutationEvent(account, dialogId, roomId, senderId, messageId,
                body, editedAt, editVersion, null);
    }

    public static void clearAccount(int account) {
        synchronized (lock) {
            byOperation.remove(account);
            byMessage.remove(account);
            restoredUsers.remove(account);
            restoreInFlight.remove(account);
        }
    }
}
