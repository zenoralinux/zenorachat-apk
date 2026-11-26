package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account- and room-scoped coordinator for Zenora message mutations.
 * This class deliberately contains no UI code and never routes mutations
 * through Telegram/MTProto.
 */
public final class ZenoraMessageMutationController {
    private static final int MAX_EDIT_METADATA = 1024;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, EditMetadata> editMetadata = new LinkedHashMap<>();

    public static final class EditMetadata {
        public final String editedAt;
        public final long editVersion;

        EditMetadata(String editedAt, long editVersion) {
            this.editedAt = editedAt;
            this.editVersion = editVersion;
        }
    }

    private ZenoraMessageMutationController() {
    }

    private static String metadataKey(int account, long roomId, long messageId) {
        return account + ":" + roomId + ":" + messageId;
    }

    public static void rememberMessageEditMetadata(int account, long roomId, long messageId,
                                                   String editedAt, long editVersion) {
        if (account < 0 || roomId <= 0L || messageId <= 0L
                || editVersion <= 0L || editVersion > Integer.MAX_VALUE) return;
        synchronized (editMetadata) {
            editMetadata.put(metadataKey(account, roomId, messageId),
                    new EditMetadata(editedAt, editVersion));
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

    public static void editMessage(int account, long dialogId, long roomId, long messageId,
                                   String text, long expectedEditVersion, long expectedUserId,
                                   ZenoraApiClient.Callback callback) {
        if (!isCurrentZenoraRoom(account,dialogId,roomId,expectedUserId)) {
            fail(callback,new IllegalStateException("Zenora account or room is no longer active"));
            return;
        }
        ZenoraApiClient.editMessage(roomId,messageId,text,expectedEditVersion,expectedUserId,
                (response,error)->{
                    if (error == null && response != null) {
                        JSONObject message = response.optJSONObject("message");
                        if (message != null) {
                            rememberMessageEditMetadata(account,roomId,messageId,
                                    message.optString("edited_at", ""),
                                    message.optLong("edit_version", 0L));
                        }
                    }
                    if (callback != null) callback.onComplete(response,error);
                });
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
