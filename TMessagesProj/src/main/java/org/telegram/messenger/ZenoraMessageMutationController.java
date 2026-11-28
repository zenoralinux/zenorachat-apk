package org.telegram.messenger;

import android.os.Handler;
import android.text.TextUtils;
import android.os.Looper;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Account- and room-scoped coordinator for Zenora message mutations.
 * This class deliberately contains no UI code and never routes mutations
 * through Telegram/MTProto.
 */
public final class ZenoraMessageMutationController {
    private static final int MAX_EDIT_METADATA = 1024;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, EditMetadata> editMetadata = new LinkedHashMap<>();
    private static final ExecutorService editOutboxExecutor = Executors.newSingleThreadExecutor();
    private static final HashSet<Integer> drainingEditAccounts = new HashSet<>();
    private static final HashSet<String> restoringEditUsers = new HashSet<>();
    private static final HashSet<String> inFlightDeletes = new HashSet<>();

    public static final class EditMetadata {
        public final String editedAt;
        public final long editVersion;
        public final String body;

        EditMetadata(String editedAt, long editVersion, String body) {
            this.editedAt = editedAt;
            this.editVersion = editVersion;
            this.body = body;
        }
    }

    private ZenoraMessageMutationController() {
    }

    private static String metadataKey(int account, long roomId, long messageId) {
        return account + ":" + roomId + ":" + messageId;
    }

    public static void rememberMessageEditMetadata(int account, long roomId, long messageId,
                                                   String editedAt, long editVersion) {
        rememberMessageEditMetadata(account, roomId, messageId, editedAt, editVersion, null);
    }

    public static void rememberMessageEditMetadata(int account, long roomId, long messageId,
                                                   String editedAt, long editVersion, String body) {
        if (account < 0 || roomId <= 0L || messageId <= 0L
                || editVersion <= 0L || editVersion > Integer.MAX_VALUE) return;
        synchronized (editMetadata) {
            String key = metadataKey(account, roomId, messageId);
            EditMetadata current = editMetadata.get(key);
            if (current != null && current.editVersion > editVersion) return;
            String canonicalBody = body != null ? body
                    : current != null && current.editVersion == editVersion ? current.body : null;
            editMetadata.put(key, new EditMetadata(editedAt, editVersion, canonicalBody));
            while (editMetadata.size() > MAX_EDIT_METADATA) {
                Iterator<Map.Entry<String, EditMetadata>> iterator = editMetadata.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    public static EditMetadata getMessageEditMetadata(int account, long roomId, long messageId) {
        synchronized (editMetadata) {
            return editMetadata.get(metadataKey(account, roomId, messageId));
        }
    }

    /** Includes the durable latest local edit, even before the process has rebuilt its memory cache. */
    public static EditMetadata getLatestPendingEditMetadata(int account, long dialogId,
                                                            long roomId, long messageId) {
        long userId = ZenoraApiClient.getCurrentUserId();
        if (userId <= 0L || roomId <= 0L || dialogId == 0L || messageId <= 0L) return null;
        try {
            ZenoraEditOutbox.Record pending = ZenoraEditOutbox.getInstance(
                    ApplicationLoader.applicationContext).latestForMessage(
                    account, userId, roomId, dialogId, messageId);
            if (pending != null && pending.expectedEditVersion < Integer.MAX_VALUE) {
                long version = pending.expectedEditVersion + 1L;
                rememberMessageEditMetadata(account, roomId, messageId, null, version, pending.body);
            }
        } catch (Exception error) {
            FileLog.e(error);
        }
        return getMessageEditMetadata(account, roomId, messageId);
    }

    /** Apply a durable queued body while a local SQLite message is being materialized. */
    public static void applyPendingEditToMessage(int account, TLRPC.Message message) {
        if (message == null || message.id <= 0 || message.dialog_id == 0L
                || message.params == null || message.action != null) return;
        long roomId;
        try {
            roomId = Long.parseLong(message.params.get("zenora_room_id"));
        } catch (Exception ignored) {
            return;
        }
        long userId = ZenoraApiClient.getCurrentUserId();
        if (roomId <= 0L || userId <= 0L
                || UserConfig.getInstance(account).getClientUserId() != userId) return;
        EditMetadata pending = getLatestPendingEditMetadata(
                account, message.dialog_id, roomId, message.id);
        if (pending == null || pending.body == null
                || pending.editVersion < Math.max(1L, message.zenoraEditVersion)) return;
        message.message = pending.body;
        message.entities = MediaDataController.getInstance(account).getEntities(
                new CharSequence[]{pending.body}, true);
        if (message.entities == null || message.entities.isEmpty()) {
            message.entities = new ArrayList<>();
            message.flags &= ~(1 << 7);
        } else {
            message.flags |= 1 << 7;
        }
        message.flags |= 1 << 8;
        applyEditMetadata(message, pending.editedAt, pending.editVersion);
    }

    public static boolean canEditPlainTextMessage(int account, long dialogId, MessageObject object) {
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        long userId = ZenoraApiClient.getCurrentUserId();
        if (roomId <= 0L || userId <= 0L || !ZenoraChatController.isZenoraEnabled()
                || object == null || object.messageOwner == null || object.getDialogId() != dialogId
                || object.getId() <= 0 || object.scheduled || object.messageOwner.from_scheduled
                || object.type != MessageObject.TYPE_TEXT || object.messageOwner.action != null
                || object.messageOwner.send_state != MessageObject.MESSAGE_SEND_STATE_SENT
                || !(object.messageOwner.from_id instanceof TLRPC.TL_peerUser)
                || object.messageOwner.from_id.user_id != userId || !object.isOut()) {
            return false;
        }
        return object.messageOwner.media == null
                || object.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty;
    }

    public static long getExpectedEditVersion(int account, long roomId, MessageObject object) {
        if (object == null || object.messageOwner == null) return 0L;
        long version = Math.max(0L, object.messageOwner.zenoraEditVersion);
        EditMetadata metadata = getMessageEditMetadata(account, roomId, object.getId());
        if (metadata != null) version = Math.max(version, metadata.editVersion);
        return version > 0L ? version : 1L;
    }

    public static void applyEditMetadata(TLRPC.Message message, String editedAt, long editVersion) {
        if (message == null || editVersion <= 0L || editVersion > Integer.MAX_VALUE
                || message.zenoraEditVersion > editVersion) return;
        message.zenoraEditVersion = editVersion;
        if (!TextUtils.isEmpty(editedAt)) {
            int editDate = ZenoraChatController.parseHistoryDate(editedAt);
            if (editDate > 0) message.edit_date = editDate;
        }
        if (message.edit_date > 0) {
            message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
            message.edit_hide = false;
        }
    }

    private static boolean isCurrentZenoraRoom(int account, long dialogId, long roomId,
                                               long expectedUserId) {
        return account >= 0 && dialogId != 0L && roomId > 0L && expectedUserId > 0L
                && ZenoraChatController.isZenoraDialog(account, dialogId)
                && ZenoraChatController.getRoomIdForDialog(account, dialogId) == roomId
                && ZenoraApiClient.getCurrentUserId() == expectedUserId;
    }

    private static void fail(ZenoraApiClient.Callback callback, Exception error) {
        if (callback != null) mainHandler.post(() -> callback.onComplete(null, error));
    }

    public static boolean editMessage(int account, long dialogId, long roomId, long messageId,
                                      String text, long expectedEditVersion, long expectedUserId,
                                      ZenoraApiClient.Callback callback) {
        if (!isCurrentZenoraRoom(account, dialogId, roomId, expectedUserId)
                || messageId <= 0L || messageId > Integer.MAX_VALUE
                || expectedEditVersion <= 0L || expectedEditVersion >= Integer.MAX_VALUE
                || TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            fail(callback, new IllegalStateException("Zenora account, room, message or edit version is invalid"));
            return false;
        }

        // Allocate a fresh immutable key for this edit. SQLite commits the exact
        // text and operation record together before the UI is told it is queued.
        final String operationId = UUID.randomUUID().toString();
        try {
            editOutboxExecutor.execute(() -> {
                try {
                    if (!isEditIdentityActive(account, expectedUserId)) {
                        throw new IllegalStateException("Zenora account changed before the edit could be saved");
                    }
                    ZenoraEditOutbox.Record record = ZenoraEditOutbox.getInstance(
                            ApplicationLoader.applicationContext).enqueue(
                            operationId, account, expectedUserId, roomId, dialogId,
                            messageId, expectedEditVersion, text);
                    JSONObject queued = new JSONObject();
                    queued.put("queued", true);
                    queued.put("operation_id", record.operationId);
                    queued.put("expected_edit_version", record.expectedEditVersion);
                    queued.put("edit_version", record.expectedEditVersion + 1L);
                    queued.put("message_id", record.messageId);
                    mainHandler.post(() -> {
                        if (isEditIdentityActive(account, expectedUserId)) {
                            if (callback != null) callback.onComplete(queued, null);
                            requestEditDrain(account);
                        } else {
                            // Keep the committed row isolated for its original user.
                            // It will be restored when that account signs in again.
                            FileLog.d("[ZenoraChat] Durable edit retained for an inactive account");
                        }
                    });
                } catch (Exception error) {
                    fail(callback, error);
                }
            });
        } catch (RuntimeException rejected) {
            fail(callback, new IllegalStateException("Zenora edit queue is unavailable", rejected));
            return false;
        }
        return true;
    }

    private static boolean isEditIdentityActive(int account, long userId) {
        return userId > 0L && ZenoraApiClient.getCurrentUserId() == userId
                && UserConfig.getInstance(account).getClientUserId() == userId
                && ZenoraApiClient.isZenoraSessionEnabled();
    }

    /** Rebuild optimistic edit projections and resume durable work after process restart. */
    public static void restorePendingEdits(int account) {
        final long userId = ZenoraApiClient.getCurrentUserId();
        if (userId <= 0L || !isEditIdentityActive(account, userId)) return;
        final String restoreKey = account + ":" + userId;
        synchronized (restoringEditUsers) {
            if (!restoringEditUsers.add(restoreKey)) {
                requestEditDrain(account);
                return;
            }
        }
        editOutboxExecutor.execute(() -> {
            List<ZenoraEditOutbox.Record> records;
            try {
                ZenoraEditOutbox outbox = ZenoraEditOutbox.getInstance(ApplicationLoader.applicationContext);
                outbox.resetSending(account, userId);
                records = outbox.loadPendingForUser(account, userId);
            } catch (Exception error) {
                FileLog.e(error);
                synchronized (restoringEditUsers) { restoringEditUsers.remove(restoreKey); }
                return;
            }
            HashMap<String, ZenoraEditOutbox.Record> latest = new HashMap<>();
            for (ZenoraEditOutbox.Record record : records) {
                String key = record.roomId + ":" + record.dialogId + ":" + record.messageId;
                ZenoraEditOutbox.Record previous = latest.get(key);
                if (previous == null || previous.sequence < record.sequence) latest.put(key, record);
            }
            mainHandler.post(() -> {
                try {
                    if (isEditIdentityActive(account, userId)) {
                        for (ZenoraEditOutbox.Record record : latest.values()) {
                            long optimisticVersion = record.expectedEditVersion + 1L;
                            rememberMessageEditMetadata(account, record.roomId, record.messageId,
                                    null, optimisticVersion, record.body);
                            ZenoraHistoryController.applyPendingEditOverlay(account, record.dialogId,
                                    record.roomId, (int) record.messageId, record.body, optimisticVersion);
                        }
                        requestEditDrain(account);
                    }
                } finally {
                    // Allow the same account to restore again after a later logout/login cycle.
                    synchronized (restoringEditUsers) {
                        restoringEditUsers.remove(restoreKey);
                    }
                }
            });
        });
    }

    private static void requestEditDrain(int account) {
        synchronized (drainingEditAccounts) {
            if (!drainingEditAccounts.add(account)) return;
        }
        try {
            editOutboxExecutor.execute(() -> drainNextEdit(account));
        } catch (RuntimeException rejected) {
            synchronized (drainingEditAccounts) { drainingEditAccounts.remove(account); }
            FileLog.e(rejected);
        }
    }

    private static void finishEditDrain(int account, long delayMs) {
        synchronized (drainingEditAccounts) { drainingEditAccounts.remove(account); }
        if (delayMs >= 0L) mainHandler.postDelayed(() -> requestEditDrain(account), delayMs);
    }

    private static void drainNextEdit(int account) {
        long activeUserId = ZenoraApiClient.getCurrentUserId();
        if (activeUserId <= 0L || !isEditIdentityActive(account, activeUserId)) {
            finishEditDrain(account, -1L);
            return;
        }
        final ZenoraEditOutbox outbox;
        final ZenoraEditOutbox.Record record;
        try {
            outbox = ZenoraEditOutbox.getInstance(ApplicationLoader.applicationContext);
            record = outbox.firstPendingForUser(account, activeUserId);
        } catch (Exception error) {
            FileLog.e(error);
            finishEditDrain(account, 10_000L);
            return;
        }
        if (record == null) {
            finishEditDrain(account, -1L);
            return;
        }
        long waitMs = record.nextAttemptAt - System.currentTimeMillis();
        if (waitMs > 0L) {
            finishEditDrain(account, waitMs);
            return;
        }
        try {
            outbox.markSending(record.operationId);
            ZenoraApiClient.editMessage(record.roomId, record.messageId, record.body,
                    record.expectedEditVersion, record.zenoraUserId, record.operationId,
                    (response, error) -> {
                        try {
                            editOutboxExecutor.execute(() -> handleEditResponse(account, record, response, error));
                        } catch (RuntimeException rejected) {
                            FileLog.e(rejected);
                            finishEditDrain(account, 5_000L);
                        }
                    });
        } catch (Exception error) {
            retryEditRecord(account, outbox, record, error);
        }
    }

    private static void handleEditResponse(int account, ZenoraEditOutbox.Record record,
                                           JSONObject response, Exception error) {
        if (!isEditIdentityActive(account, record.zenoraUserId)) {
            finishEditDrain(account, -1L);
            return;
        }
        if (error instanceof ZenoraApiClient.ApiException
                && "EDIT_VERSION_CONFLICT".equals(((ZenoraApiClient.ApiException) error).errorCode)) {
            // The conflict response carries the exact row locked by PHP while it
            // compared edit_version, so even old messages can be rebased safely.
            ZenoraApiClient.ApiException conflict = (ZenoraApiClient.ApiException) error;
            JSONObject serverRow = conflict.response == null
                    ? null : conflict.response.optJSONObject("message");
            long serverVersion = serverRow == null ? 0L
                    : serverRow.optLong("edit_version", 0L);
            if (serverRow == null
                    || serverRow.optLong("message_id", 0L) != record.messageId
                    || serverRow.optLong("room_id", 0L) != record.roomId
                    || serverVersion <= 0L || serverVersion >= Integer.MAX_VALUE) {
                retryEditRecord(account, ZenoraEditOutbox.getInstance(
                        ApplicationLoader.applicationContext), record,
                        new IllegalStateException("Canonical conflict row was missing or mismatched"));
                return;
            }
            ZenoraEditOutbox outbox = ZenoraEditOutbox.getInstance(
                    ApplicationLoader.applicationContext);
            outbox.rebaseMessage(account, record.zenoraUserId, record.roomId,
                    record.dialogId, record.messageId, serverVersion);
            ZenoraEditOutbox.Record latest = outbox.latestForMessage(account,
                    record.zenoraUserId, record.roomId, record.dialogId, record.messageId);
            if (latest != null) {
                long optimisticVersion = latest.expectedEditVersion + 1L;
                rememberMessageEditMetadata(account, record.roomId, record.messageId,
                        null, optimisticVersion, latest.body);
                ZenoraHistoryController.applyPendingEditOverlay(account, record.dialogId,
                        record.roomId, (int) record.messageId, latest.body, optimisticVersion);
            }
            finishEditDrain(account, 0L);
            return;
        }
        if (error != null || response == null) {
            retryEditRecord(account, ZenoraEditOutbox.getInstance(ApplicationLoader.applicationContext),
                    record, error != null ? error : new IllegalStateException("Empty edit response"));
            return;
        }

        JSONObject canonical = response.optJSONObject("message");
        String body = canonical == null ? "" : canonical.optString("text", "");
        String editedAt = canonical == null || canonical.isNull("edited_at")
                ? null : canonical.optString("edited_at", "");
        long version = canonical == null ? 0L : canonical.optLong("edit_version", 0L);
        if (canonical == null || canonical.optLong("message_id", 0L) != record.messageId
                || canonical.optLong("room_id", 0L) != record.roomId
                || version != record.expectedEditVersion + 1L || !record.body.equals(body)) {
            retryEditRecord(account, ZenoraEditOutbox.getInstance(ApplicationLoader.applicationContext),
                    record, new IllegalStateException("Canonical edit acknowledgement did not match its durable operation"));
            return;
        }

        // The next queued edit may already be visible at a higher local version.
        // Shared reconciliation rejects this older acknowledgement in that case.
        ZenoraHistoryController.reconcileCanonicalEditPayload(account, record.dialogId,
                record.roomId, (int) record.messageId, body, editedAt, version);
        ZenoraEditOutbox.getInstance(ApplicationLoader.applicationContext).delete(record.operationId);
        finishEditDrain(account, 0L);
    }

    private static void retryEditRecord(int account, ZenoraEditOutbox outbox,
                                        ZenoraEditOutbox.Record record, Exception error) {
        int attempts = record.attemptCount + 1;
        long delay = ZenoraEditOutbox.backoffDelayMs(attempts);
        outbox.markRetry(record.operationId, attempts,
                System.currentTimeMillis() + delay,
                error == null ? "request failed" : error.getMessage());
        finishEditDrain(account, delay);
    }


    /**
     * Delete is available for ordinary messages in a mapped Zenora room only.
     * Pending negative IDs are accepted only when they are backed by our durable
     * text Outbox record; they are cancelled locally and never sent to the API.
     */
    public static boolean canDeleteMessage(int account, long dialogId, MessageObject object,
                                           boolean scheduledMode) {
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        long userId = ZenoraApiClient.getCurrentUserId();
        if (roomId <= 0L || userId <= 0L
                || userId != UserConfig.getInstance(account).getClientUserId()
                || !ZenoraChatController.isZenoraDialog(account, dialogId)
                || DialogObject.isEncryptedDialog(dialogId)
                || object == null || object.messageOwner == null
                || object.getDialogId() != dialogId
                || scheduledMode || object.scheduled || object.messageOwner.from_scheduled
                || object.isEphemeral() || object.isStory()
                || object.eventId != 0 || object.sponsoredId != null
                || object.messageOwner.action != null) {
            return false;
        }
        int id = object.getId();
        if (id > 0) {
            return true;
        }
        if (id >= 0 || object.type != MessageObject.TYPE_TEXT
                || !(object.messageOwner.media == null
                    || object.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)
                || object.messageOwner.params == null) {
            return false;
        }
        String clientMessageId = object.messageOwner.params.get("zenora_client_message_id");
        String pendingRoomId = object.messageOwner.params.get("zenora_room_id");
        return !TextUtils.isEmpty(clientMessageId)
                && String.valueOf(roomId).equals(pendingRoomId);
    }

    public static boolean canDeleteSelection(int account, long dialogId, long roomId,
                                             List<MessageObject> objects) {
        if (!isCurrentZenoraRoom(account, dialogId, roomId, ZenoraApiClient.getCurrentUserId())
                || objects == null || objects.isEmpty() || objects.size() > 50) {
            return false;
        }
        HashSet<Integer> ids = new HashSet<>();
        boolean hasPending = false;
        boolean hasDelivered = false;
        for (MessageObject object : objects) {
            if (!canDeleteMessage(account, dialogId, object, false)
                    || !ids.add(object.getId())) {
                return false;
            }
            if (object.getId() < 0) hasPending = true;
            else hasDelivered = true;
        }
        return !(hasPending && hasDelivered);
    }

    /**
     * The only UI-facing delete entry point for Zenora messages. A request is
     * strictly room-scoped, bounded to 50 entries and committed locally only
     * after server confirmation. Mixed pending/sent selections are rejected to
     * avoid a partly completed operation across two different persistence paths.
     */
    public static boolean deleteSelection(int account, long dialogId, long roomId,
                                          List<MessageObject> objects, String deleteScope,
                                          long expectedUserId, ZenoraApiClient.Callback callback) {
        if (!isCurrentZenoraRoom(account, dialogId, roomId, expectedUserId)
                || !("me".equals(deleteScope) || "both".equals(deleteScope))) {
            fail(callback, new IllegalStateException("Zenora account, room or delete scope is invalid"));
            return false;
        }
        if (!canDeleteSelection(account, dialogId, roomId, objects)) {
            fail(callback, new IllegalArgumentException("Zenora delete selection is unsupported or exceeds 50 messages"));
            return false;
        }

        ArrayList<MessageObject> pending = new ArrayList<>();
        ArrayList<Long> serverIds = new ArrayList<>();
        ArrayList<Integer> localIds = new ArrayList<>();
        for (MessageObject object : objects) {
            int id = object.getId();
            localIds.add(id);
            if (id < 0) {
                pending.add(object);
            } else {
                serverIds.add((long) id);
            }
        }
        if (!pending.isEmpty() && !serverIds.isEmpty()) {
            fail(callback, new IllegalArgumentException(
                    "Pending messages must be cancelled separately from delivered messages"));
            return false;
        }

        StringBuilder keyBuilder = new StringBuilder(metadataKey(account, roomId, 0L))
                .append(':').append(deleteScope);
        for (Integer id : localIds) keyBuilder.append(':').append(id);
        String requestKey = keyBuilder.toString();
        synchronized (inFlightDeletes) {
            if (!inFlightDeletes.add(requestKey)) {
                fail(callback, new IllegalStateException("A Zenora deletion is already in progress"));
                return false;
            }
        }

        if (!pending.isEmpty()) {
            ZenoraHistoryController.cancelPendingTextMessages(account, dialogId, pending,
                    (success, errorMessage) -> {
                        if (success) {
                            success = MessagesController.getInstance(account)
                                    .applyZenoraMessageDeletionLocally(dialogId, localIds);
                        }
                        synchronized (inFlightDeletes) {
                            inFlightDeletes.remove(requestKey);
                        }
                        if (!success) {
                            fail(callback, new IllegalStateException(TextUtils.isEmpty(errorMessage)
                                    ? "Unable to cancel the pending Zenora Outbox message"
                                    : errorMessage));
                        } else if (callback != null) {
                            callback.onComplete(null, null);
                        }
                    });
            return true;
        }

        ZenoraApiClient.Callback completion = (response, error) -> {
            Exception resultError = error;
            if (resultError == null) {
                if (response == null || response.optJSONArray("deleted_message_ids") == null
                        || response.optJSONArray("deleted_message_ids").length() != serverIds.size()) {
                    resultError = new IllegalStateException(
                            "Zenora did not confirm deletion of every selected message");
                } else {
                    org.json.JSONArray confirmed = response.optJSONArray("deleted_message_ids");
                    HashSet<Long> confirmedIds = new HashSet<>();
                    for (int i = 0; i < confirmed.length(); i++) {
                        confirmedIds.add(confirmed.optLong(i, 0L));
                    }
                    if (!confirmedIds.equals(new HashSet<>(serverIds))) {
                        resultError = new IllegalStateException(
                                "Zenora returned an incomplete or mismatched deletion result");
                    }
                }
            }
            if (resultError == null) {
                if (!isCurrentZenoraRoom(account, dialogId, roomId, expectedUserId)) {
                    resultError = new IllegalStateException("Zenora session changed before local deletion could be applied");
                } else if (!MessagesController.getInstance(account)
                        .applyZenoraMessageDeletionLocally(dialogId, localIds)) {
                    resultError = new IllegalStateException("The server deleted the messages but the local cache could not be updated");
                }
            }
            synchronized (inFlightDeletes) {
                inFlightDeletes.remove(requestKey);
            }
            if (callback != null) callback.onComplete(response, resultError);
        };
        if (serverIds.size() == 1) {
            ZenoraApiClient.deleteMessage(roomId, serverIds.get(0), deleteScope,
                    expectedUserId, completion);
        } else {
            ZenoraApiClient.deleteMessagesBulk(roomId, serverIds, deleteScope,
                    expectedUserId, completion);
        }
        return true;
    }

    public static void deleteMessage(int account, long dialogId, long roomId, long messageId,
                                     String deleteScope, long expectedUserId,
                                     ZenoraApiClient.Callback callback) {
        if (!isCurrentZenoraRoom(account,dialogId,roomId,expectedUserId)) {
            fail(callback,new IllegalStateException("Zenora account or room is no longer active"));
            return;
        }
        ZenoraApiClient.deleteMessage(roomId,messageId,deleteScope,expectedUserId,callback);
    }

    public static void deleteMessagesBulk(int account, long dialogId, long roomId,
                                          List<Long> messageIds, String deleteScope,
                                          long expectedUserId, ZenoraApiClient.Callback callback) {
        if (!isCurrentZenoraRoom(account,dialogId,roomId,expectedUserId)) {
            fail(callback,new IllegalStateException("Zenora account or room is no longer active"));
            return;
        }
        if (messageIds == null || messageIds.isEmpty() || messageIds.size() > 50) {
            fail(callback,new IllegalArgumentException("Zenora bulk delete accepts 1 to 50 messages"));
            return;
        }
        ZenoraApiClient.deleteMessagesBulk(roomId,new ArrayList<>(messageIds),
                deleteScope,expectedUserId,callback);
    }
}
