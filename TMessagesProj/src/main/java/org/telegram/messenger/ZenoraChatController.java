package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts ZenoraChat API rooms into Telegram's native TL dialog model.
 * The existing DialogsActivity and DialogCell views are not replaced.
 */
public final class ZenoraChatController {
    private static final Object loadLock = new Object();
    private static final Set<Integer> loadingAccounts = new HashSet<>();
    private static final Map<Integer, Integer> retryAttempts = new HashMap<>();
    private static final Map<Integer, Long> lastSuccessfulLoad = new HashMap<>();
    private static final Handler retryHandler = new Handler(Looper.getMainLooper());
    private static final int MAX_AUTOMATIC_RETRIES = 3;

    private ZenoraChatController() {
    }

    public static boolean isZenoraEnabled() {
        return ZenoraApiClient.isZenoraSessionEnabled();
    }

    public static void activateAccount(int account, JSONObject profile) {
        TLRPC.User self = makeUser(profile.optLong("id", 0),
                profile.optString("username", ""),
                profile.optString("display_name", ""), true);
        if (self == null) return;
        UserConfig config = UserConfig.getInstance(account);
        config.setCurrentUser(self);
        config.saveConfig(true);
        MessagesController.getInstance(account).putUser(self, false, true);
    }

    public static void loadDialogs(AccountInstance accountInstance) {
        if (accountInstance == null) return;
        final int account = accountInstance.getCurrentAccount();
        synchronized (loadLock) {
            if (!loadingAccounts.add(account)) return;
        }
        ZenoraApiClient.fetchRooms((response, error) -> {
            synchronized (loadLock) {
                loadingAccounts.remove(account);
            }
            if (error != null) {
                if (error instanceof ZenoraApiClient.ApiException
                        && ((ZenoraApiClient.ApiException) error).statusCode == 401) {
                    synchronized (loadLock) {
                        retryAttempts.remove(account);
                        lastSuccessfulLoad.remove(account);
                    }
                    FileLog.e("[ZenoraChat] rooms.php rejected the saved session after the one-time retry: "
                            + error.getMessage());
                    DialogsActivity.resetZenoraLoadState(account);
                    ZenoraApiClient.clearSession();
                    LaunchActivity.requestZenoraLogin();
                    return;
                }
                DialogsActivity.resetZenoraLoadState(account);
                Toast.makeText(ApplicationLoader.applicationContext,
                        "اتصال به زنوراچت ناموفق بود. فهرست قبلی حفظ شد و تلاش مجدد انجام می‌شود.",
                        Toast.LENGTH_LONG).show();
                scheduleRetry(accountInstance);
                return;
            }
            try {
                applyRooms(accountInstance, response);
                synchronized (loadLock) {
                    retryAttempts.remove(account);
                    lastSuccessfulLoad.put(account, SystemClock.elapsedRealtime());
                }
                DialogsActivity.markZenoraLoadComplete(account);
            } catch (Exception e) {
                FileLog.e(e);
                DialogsActivity.resetZenoraLoadState(account);
                Toast.makeText(ApplicationLoader.applicationContext,
                        "پاسخ فهرست گفتگوها معتبر نبود؛ فهرست قبلی حفظ شد.",
                        Toast.LENGTH_LONG).show();
                scheduleRetry(accountInstance);
            }
        });
    }

    public static void refreshDialogs(AccountInstance accountInstance) {
        if (accountInstance == null) return;
        int account = accountInstance.getCurrentAccount();
        synchronized (loadLock) {
            Long lastLoaded = lastSuccessfulLoad.get(account);
            if (lastLoaded != null
                    && SystemClock.elapsedRealtime() - lastLoaded < 30_000L) {
                return;
            }
        }
        loadDialogs(accountInstance);
    }

    private static void scheduleRetry(AccountInstance accountInstance) {
        final int account = accountInstance.getCurrentAccount();
        final int retryIndex;
        synchronized (loadLock) {
            retryIndex = retryAttempts.containsKey(account) ? retryAttempts.get(account) : 0;
            if (retryIndex >= MAX_AUTOMATIC_RETRIES) return;
            retryAttempts.put(account, retryIndex + 1);
        }
        retryHandler.postDelayed(() -> loadDialogs(accountInstance), 2_000L << retryIndex);
    }

    private static void validateRows(JSONArray rows, boolean directList) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optLong("id", 0) <= 0) {
                throw new IllegalStateException("Invalid room row at index " + i);
            }
            String type = row.optString("type", directList ? "dm" : "group");
            if (directList && !"saved".equals(type) && row.optLong("other_user_id", 0) <= 0) {
                throw new IllegalStateException("Direct room is missing its peer at index " + i);
            }
        }
    }

    private static void applyRooms(AccountInstance accountInstance, JSONObject response) throws Exception {
        if (response == null || !response.optBoolean("ok", false)) {
            throw new IllegalStateException("Rooms API did not return success");
        }
        JSONObject meJson = response.optJSONObject("me");
        JSONArray rooms = response.optJSONArray("rooms");
        JSONArray directRooms = response.optJSONArray("dm_rooms");
        if (meJson == null || meJson.optLong("id", 0) <= 0 || rooms == null || directRooms == null) {
            throw new IllegalStateException("Incomplete rooms API payload");
        }
        // Validate every row before clearing the currently displayed dialogs.
        validateRows(rooms, false);
        validateRows(directRooms, true);

        int account = accountInstance.getCurrentAccount();
        // Keep the local profile metadata synchronized with the authenticated
        // server response so the account shell can be rebuilt after process death.
        ZenoraApiClient.enableSession(meJson);
        UserConfig userConfig = accountInstance.getUserConfig();
        MessagesController controller = accountInstance.getMessagesController();
        TLRPC.User self = userConfig.getCurrentUser();

        if (meJson != null && meJson.optLong("id", 0) > 0
                && (self == null || self.id != meJson.optLong("id", 0))) {
            self = makeUser(meJson.optLong("id", 0), meJson.optString("username", ""),
                    meJson.optString("display_name", ""), true);
            if (self != null) {
                userConfig.setCurrentUser(self);
                userConfig.saveConfig(true);
            }
        }
        if (self == null) throw new IllegalStateException("ZenoraChat profile is missing");

        controller.dialogs_dict.clear();
        controller.dialogMessage.clear();
        controller.dialogsByFolder.clear();
        controller.allDialogs.clear();
        controller.getUsers().clear();
        controller.getChats().clear();
        controller.putUser(self, false, true);

        for (int i = 0; i < rooms.length(); i++) {
            addDialog(account, controller, self, rooms.getJSONObject(i), false);
        }
        for (int i = 0; i < directRooms.length(); i++) {
            addDialog(account, controller, self, directRooms.getJSONObject(i), true);
        }

        // Zenora dialogs arrive as a complete API snapshot, not through
        // Telegram's paginated loadDialogs pipeline. Remove the native paging
        // cutoff so old-but-valid rooms are not filtered out during sorting.
        controller.dialogsLoadedTillDate = Integer.MIN_VALUE;
        controller.sortDialogs(null);
        ArrayList<TLRPC.Dialog> visible = controller.dialogsByFolder.get(0);
        if (visible == null) {
            visible = new ArrayList<>();
            controller.dialogsByFolder.put(0, visible);
        }
        if (visible.isEmpty() && !controller.allDialogs.isEmpty()) {
            ArrayList<TLRPC.Dialog> archived = controller.dialogsByFolder.get(1);
            if (archived == null) {
                archived = new ArrayList<>();
                controller.dialogsByFolder.put(1, archived);
            }
            for (TLRPC.Dialog dialog : controller.allDialogs) {
                if (dialog.folder_id == 1) archived.add(dialog);
                else visible.add(dialog);
            }
            java.util.Comparator<TLRPC.Dialog> order = (left, right) -> {
                if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
                if (left.pinned) return Integer.compare(right.pinnedNum, left.pinnedNum);
                return Integer.compare(right.last_message_date, left.last_message_date);
            };
            Collections.sort(visible, order);
            Collections.sort(archived, order);
        }
        FileLog.d("[ZenoraChat] applied room snapshot: groups=" + rooms.length()
                + ", direct=" + directRooms.length()
                + ", nativeDialogs=" + controller.allDialogs.size());
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void addDialog(int account, MessagesController controller, TLRPC.User self,
                                  JSONObject row, boolean fromDmList) throws Exception {
        String type = row.optString("type", fromDmList ? "dm" : "group");
        boolean saved = "saved".equals(type);
        boolean direct = fromDmList && !saved;
        boolean channel = "channel".equals(type);
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) return;

        long dialogId;
        TLRPC.Peer peer;
        if (saved) {
            TLRPC.TL_chat savedChat = new TLRPC.TL_chat();
            savedChat.id = roomId;
            savedChat.title = nonEmpty(row.optString("name", ""), "پیام‌های ذخیره‌شده");
            savedChat.photo = new TLRPC.TL_chatPhotoEmpty();
            savedChat.date = parseDate(row.optString("last_message_time", ""));
            controller.putChat(savedChat, false);
            TLRPC.TL_peerChat savedPeer = new TLRPC.TL_peerChat();
            savedPeer.chat_id = roomId;
            peer = savedPeer;
            dialogId = -roomId;
        } else if (direct) {
            long otherId = row.optLong("other_user_id", 0);
            if (otherId <= 0) return;
            TLRPC.User other = makeUser(otherId,
                    row.optString("other_username", ""),
                    row.optString("other_display_name", ""), false);
            if (other == null) return;
            controller.putUser(other, false, true);
            TLRPC.TL_peerUser userPeer = new TLRPC.TL_peerUser();
            userPeer.user_id = otherId;
            peer = userPeer;
            dialogId = otherId;
        } else {
            TLRPC.Chat chat;
            if (channel) {
                TLRPC.TL_channel channelChat = new TLRPC.TL_channel();
                channelChat.broadcast = true;
                channelChat.megagroup = false;
                channelChat.flags |= 1 << 5;
                chat = channelChat;
            } else {
                chat = new TLRPC.TL_chat();
            }
            chat.id = roomId;
            chat.title = nonEmpty(row.optString("name", ""), " ");
            chat.photo = new TLRPC.TL_chatPhotoEmpty();
            chat.date = parseDate(row.optString("last_message_time", ""));
            chat.participants_count = row.optInt("member_count", 0);
            controller.putChat(chat, false);

            if (channel) {
                TLRPC.TL_peerChannel channelPeer = new TLRPC.TL_peerChannel();
                channelPeer.channel_id = roomId;
                peer = channelPeer;
            } else {
                TLRPC.TL_peerChat chatPeer = new TLRPC.TL_peerChat();
                chatPeer.chat_id = roomId;
                peer = chatPeer;
            }
            dialogId = -roomId;
        }

        int messageId = (int) Math.max(0, Math.min(Integer.MAX_VALUE, row.optLong("last_message_id", 0)));
        int readId = (int) Math.max(0, Math.min(Integer.MAX_VALUE, row.optLong("last_read_id", 0)));
        int date = parseDate(row.optString("last_message_time", ""));
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = dialogId;
        dialog.peer = peer;
        dialog.top_message = messageId;
        dialog.read_inbox_max_id = readId;
        dialog.unread_count = Math.max(0, row.optInt("unread_count", 0));
        dialog.unread_mark = row.optInt("is_marked_unread", 0) == 1;
        dialog.pinned = row.optInt("is_pinned", 0) == 1;
        dialog.pinnedNum = 100000 - Math.max(0, row.optInt("pin_order", 0));
        dialog.folder_id = row.optInt("is_archived", 0) == 1 ? 1 : 0;
        dialog.last_message_date = date;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();

        controller.dialogs_dict.put(dialogId, dialog);
        controller.allDialogs.add(dialog);

        if (messageId > 0) {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = messageId;
            message.date = date;
            message.peer_id = peer;
            message.dialog_id = dialogId;
            message.message = messagePreview(row);
            message.media = new TLRPC.TL_messageMediaEmpty();
            String sender = row.optString("last_message_sender_username", "");
            message.out = !TextUtils.isEmpty(self.username) && self.username.equalsIgnoreCase(sender);
            if (message.out) {
                message.flags |= 1 << 1;
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = self.id;
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
            } else if (direct) {
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = row.optLong("other_user_id", 0);
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
            }

            MessageObject object = new MessageObject(account, message,
                    controller.getUsers(), controller.getChats(), false, false);
            ArrayList<MessageObject> messages = new ArrayList<>();
            messages.add(object);
            controller.dialogMessage.put(dialogId, messages);
        }
    }

    private static TLRPC.User makeUser(long id, String username, String displayName, boolean self) {
        if (id <= 0) return null;
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = self;
        user.username = username == null ? "" : username;
        user.first_name = nonEmpty(displayName, nonEmpty(user.username, "ZenoraChat"));
        user.last_name = "";
        user.phone = "";
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.status = new TLRPC.TL_userStatusEmpty();
        user.flags |= (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 6);
        if (self) user.flags |= 1 << 10;
        return user;
    }

    private static String messagePreview(JSONObject row) {
        String body = row.optString("last_message_body", "");
        if (!TextUtils.isEmpty(body)) return body;
        switch (row.optString("last_message_media_kind", "")) {
            case "photo": return "Photo";
            case "video": return "Video";
            case "video_note": return "Video message";
            case "voice": return "Voice message";
            case "music": return "Music";
            case "gif": return "GIF";
            case "file": return "File";
            default: return "";
        }
    }

    private static String nonEmpty(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int parseDate(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            long numeric = Long.parseLong(value);
            if (numeric > 100000000000L) numeric /= 1000L;
            if (numeric > 0) return (int) Math.min(Integer.MAX_VALUE, numeric);
        } catch (NumberFormatException ignored) {
        }
        String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                parser.setLenient(false);
                Date parsed = parser.parse(value);
                if (parsed != null) return (int) Math.min(Integer.MAX_VALUE, parsed.getTime() / 1000L);
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}
