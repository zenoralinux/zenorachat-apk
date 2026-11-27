package org.telegram.messenger;

import android.os.Handler;
import android.text.TextUtils;
import android.os.Looper;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account- and room-scoped coordinator for Zenora message mutations.
 * This class deliberately contains no UI code and never routes mutations
 * through Telegram/MTProto.
 */
public final class ZenoraMessageMutationController {
    private static final int MAX_EDIT_METADATA = 1024;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, EditMetadata> editMetadata = new LinkedHashMap<>();
    private static final HashSet<String> inFlightEdits = new HashSet<>();
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
        if (object.messageOwner.zenoraEditVersion > 0L) return object.messageOwner.zenoraEditVersion;
        EditMetadata metadata = getMessageEditMetadata(account, roomId, object.getId());
        return metadata != null && metadata.editVersion > 0L ? metadata.editVersion : 1L;
    }

    public static void applyEditMetadata(TLRPC.Message message, String editedAt, long editVersion) {
        if (message == null || editVersion <= 0L || editVersion > Integer.MAX_VALUE
                || message.zenoraEditVersion > editVersion) return;
        message.zenoraEditVersion = editVersion;
        int editDate = TextUtils.isEmpty(editedAt) ? 0 : ZenoraChatController.parseHistoryDate(editedAt);
        message.edit_date = Math.max(0, editDate);
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
                || messageId <= 0L || expectedEditVersion <= 0L) {
            fail(callback, new IllegalStateException("Zenora account, room or edit version is invalid"));
            return false;
        }
        String key = metadataKey(account, roomId, messageId);
        synchronized (inFlightEdits) {
            if (!inFlightEdits.add(key)) {
                fail(callback, new IllegalStateException("A Zenora edit is already in progress for this message"));
                return false;
            }
        }
        final String operationId = UUID.randomUUID().toString();
        ZenoraApiClient.editMessage(roomId, messageId, text, expectedEditVersion, expectedUserId,
                operationId, (response, error) -> {
                    synchronized (inFlightEdits) {
                        inFlightEdits.remove(key);
                    }
                    if (error == null && response != null) {
                        JSONObject message = response.optJSONObject("message");
                        if (message != null) {
                            String canonicalBody = message.optString("text", "");
                            String canonicalEditedAt = message.optString("edited_at", "");
                            long canonicalVersion = message.optLong("edit_version", 0L);
                            rememberMessageEditMetadata(account, roomId, messageId,
                                    canonicalEditedAt, canonicalVersion, canonicalBody);
                            ZenoraHistoryController.reconcileCanonicalEditPayload(
                                    account, dialogId, roomId, (int) messageId,
                                    canonicalBody, canonicalEditedAt, canonicalVersion);
                        }
                    }
                    if (callback != null) callback.onComplete(response, error);
                });
        return true;
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
