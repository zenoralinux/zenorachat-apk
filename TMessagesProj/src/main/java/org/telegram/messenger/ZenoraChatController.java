package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;
import android.util.LongSparseArray;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts ZenoraChat API rooms into Telegram's native TL dialog model.
 * The native Telegram dialog list and its cells remain untouched.
 */
public final class ZenoraChatController {
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<Integer> loadingAccounts = Collections.synchronizedSet(new HashSet<>());
    private static final Map<Integer, Integer> retryAttempts = new ConcurrentHashMap<>();

    private static volatile Map<Long, String> userAvatarUrls = Collections.emptyMap();
    private static volatile Map<Long, String> chatAvatarUrls = Collections.emptyMap();

    private ZenoraChatController() {
    }

    private static final class StagedDialogs {
        final LongSparseArray<TLRPC.Dialog> dialogs = new LongSparseArray<>();
        final LongSparseArray<ArrayList<MessageObject>> dialogMessages = new LongSparseArray<>();
        final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
        final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
        final ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>();
        final Map<Long, String> userAvatars = new HashMap<>();
        final Map<Long, String> chatAvatars = new HashMap<>();
    }

    public static boolean isZenoraEnabled() {
        return ZenoraApiClient.isZenoraSessionEnabled();
    }

    public static String getUserAvatarUrl(long userId) {
        return userAvatarUrls.get(userId);
    }

    public static String getChatAvatarUrl(long chatId) {
        return chatAvatarUrls.get(chatId);
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
        retryAttempts.remove(account);
        String avatar = toAvatarThumbnailUrl(profile.optString("avatar_path", ""));
        if (TextUtils.isEmpty(avatar)) {
            Map<Long, String> next = new HashMap<>(userAvatarUrls);
            next.remove(self.id);
            userAvatarUrls = Collections.unmodifiableMap(next);
        } else {
            Map<Long, String> next = new HashMap<>(userAvatarUrls);
            next.put(self.id, avatar);
            userAvatarUrls = Collections.unmodifiableMap(next);
        }
    }

    public static void loadDialogs(AccountInstance accountInstance) {
        final int account = accountInstance.getCurrentAccount();
        synchronized (loadingAccounts) {
            if (!loadingAccounts.add(account)) {
                return;
            }
        }

        ZenoraApiClient.fetchRooms((response, error) -> {
            try {
                if (error != null) {
                    DialogsActivity.resetZenoraLoadState(account);
                    if (error instanceof ZenoraApiClient.ApiException
                            && ((ZenoraApiClient.ApiException) error).statusCode == 401) {
                        retryAttempts.remove(account);
                        ZenoraApiClient.clearSession();
                        LaunchActivity.requestZenoraLogin();
                        return;
                    }
                    FileLog.e(error);
                    scheduleRetry(accountInstance);
                    return;
                }

                applyRooms(accountInstance, response);
                retryAttempts.remove(account);
            } catch (Exception e) {
                // Keep the last committed dialog snapshot intact. applyRooms stages
                // and validates the entire response before replacing controller data.
                DialogsActivity.resetZenoraLoadState(account);
                FileLog.e(e);
                scheduleRetry(accountInstance);
            } finally {
                loadingAccounts.remove(account);
            }
        });
    }

    private static void scheduleRetry(AccountInstance accountInstance) {
        final int account = accountInstance.getCurrentAccount();
        int attempt = retryAttempts.getOrDefault(account, 0);
        if (attempt >= 3) {
            Toast.makeText(ApplicationLoader.applicationContext,
                    "دریافت گفتگوها ناموفق بود. اتصال اینترنت را بررسی و دوباره تلاش کنید.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        retryAttempts.put(account, attempt + 1);
        long delayMs = 1000L << attempt; // 1s, 2s, 4s
        mainHandler.postDelayed(() -> {
            if (isZenoraEnabled()) {
                DialogsActivity.loadDialogs(accountInstance);
            }
        }, delayMs);
    }

    private static void applyRooms(AccountInstance accountInstance, JSONObject response) throws Exception {
        if (response == null || !response.optBoolean("ok", false)) {
            throw new IllegalStateException("ZenoraChat returned an unsuccessful chat-list response");
        }

        JSONObject meJson = response.optJSONObject("me");
        JSONArray rooms = response.optJSONArray("rooms");
        JSONArray directRooms = response.optJSONArray("dm_rooms");
        if (meJson == null || meJson.optLong("id", 0) <= 0 || rooms == null || directRooms == null) {
            throw new IllegalStateException("ZenoraChat chat-list response is incomplete");
        }

        int account = accountInstance.getCurrentAccount();
        UserConfig userConfig = accountInstance.getUserConfig();
        MessagesController controller = accountInstance.getMessagesController();
        TLRPC.User currentUser = userConfig.getCurrentUser();
        long selfId = meJson.optLong("id", 0);

        TLRPC.User self = currentUser;
        if (self == null || self.id != selfId) {
            self = makeUser(selfId, meJson.optString("username", ""),
                    meJson.optString("display_name", ""), true);
        } else {
            self.self = true;
            self.username = meJson.optString("username", self.username);
            String displayName = meJson.optString("display_name", "");
            if (!TextUtils.isEmpty(displayName)) self.first_name = displayName;
        }
        if (self == null) throw new IllegalStateException("ZenoraChat profile is missing");

        StagedDialogs staged = new StagedDialogs();
        staged.users.put(self.id, self);
        putAvatar(staged.userAvatars, self.id, meJson.optString("avatar_path", ""));

        for (int i = 0; i < rooms.length(); i++) {
            JSONObject row = rooms.optJSONObject(i);
            if (row == null) throw new IllegalStateException("Invalid room entry at index " + i);
            addDialog(account, staged, self, row, false);
        }
        for (int i = 0; i < directRooms.length(); i++) {
            JSONObject row = directRooms.optJSONObject(i);
            if (row == null) throw new IllegalStateException("Invalid direct-chat entry at index " + i);
            addDialog(account, staged, self, row, true);
        }

        // A complete empty list is valid; a missing list is not. Only commit
        // after every row and its native MessageObject have been constructed.
        userConfig.setCurrentUser(self);
        userConfig.saveConfig(true);
        controller.dialogs_dict.clear();
        controller.dialogMessage.clear();
        controller.dialogsByFolder.clear();
        controller.allDialogs.clear();
        controller.getUsers().clear();
        controller.getChats().clear();

        for (int i = 0; i < staged.users.size(); i++) {
            controller.getUsers().put(staged.users.keyAt(i), staged.users.valueAt(i));
        }
        for (int i = 0; i < staged.chats.size(); i++) {
            controller.getChats().put(staged.chats.keyAt(i), staged.chats.valueAt(i));
        }
        for (int i = 0; i < staged.dialogs.size(); i++) {
            controller.dialogs_dict.put(staged.dialogs.keyAt(i), staged.dialogs.valueAt(i));
        }
        for (int i = 0; i < staged.dialogMessages.size(); i++) {
            controller.dialogMessage.put(staged.dialogMessages.keyAt(i), staged.dialogMessages.valueAt(i));
        }
        controller.allDialogs.addAll(staged.allDialogs);

        userAvatarUrls = Collections.unmodifiableMap(new HashMap<>(staged.userAvatars));
        chatAvatarUrls = Collections.unmodifiableMap(new HashMap<>(staged.chatAvatars));

        controller.sortDialogs(null);
        DialogsActivity.markZenoraLoadSuccess(account);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void addDialog(int account, StagedDialogs staged, TLRPC.User self,
                                  JSONObject row, boolean fromDmList) throws Exception {
        String type = row.optString("type", fromDmList ? "dm" : "group");
        boolean saved = "saved".equals(type);
        boolean direct = fromDmList && !saved;
        boolean channel = "channel".equals(type);
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) throw new IllegalStateException("A chat entry has an invalid ID");

        long dialogId;
        TLRPC.Peer peer;
        TLRPC.User directUser = null;

        if (saved) {
            TLRPC.TL_chat savedChat = new TLRPC.TL_chat();
            savedChat.id = roomId;
            savedChat.title = nonEmpty(row.optString("name", ""), "پیام‌های ذخیره‌شده");
            savedChat.photo = new TLRPC.TL_chatPhotoEmpty();
            savedChat.date = parseDate(row.optString("last_message_time", ""));
            staged.chats.put(roomId, savedChat);
            TLRPC.TL_peerChat savedPeer = new TLRPC.TL_peerChat();
            savedPeer.chat_id = roomId;
            peer = savedPeer;
            dialogId = -roomId;
        } else if (direct) {
            long otherId = row.optLong("other_user_id", 0);
            if (otherId <= 0) throw new IllegalStateException("A direct chat has no other user ID");
            directUser = makeUser(otherId, row.optString("other_username", ""),
                    row.optString("other_display_name", ""), false);
            if (directUser == null) throw new IllegalStateException("A direct chat has an invalid user");
            staged.users.put(otherId, directUser);
            putAvatar(staged.userAvatars, otherId, row.optString("other_avatar_path", ""));
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
            staged.chats.put(roomId, chat);
            putAvatar(staged.chatAvatars, roomId, row.optString("avatar_path", ""));

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

        if (staged.dialogs.get(dialogId) != null) {
            throw new IllegalStateException("Duplicate dialog ID in ZenoraChat response: " + dialogId);
        }

        long messageIdLong = row.optLong("last_message_id", 0);
        int messageId = (int) Math.max(0, Math.min(Integer.MAX_VALUE, messageIdLong));
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
        if (channel) dialog.flags |= 1;

        staged.dialogs.put(dialogId, dialog);
        staged.allDialogs.add(dialog);

        if (messageId > 0) {
            String senderUsername = row.optString("last_message_sender_username", "");
            String senderDisplayName = row.optString("last_message_sender_display_name", "");
            long senderId = row.optLong("last_message_sender_id", 0);
            if (senderId <= 0) {
                if (!TextUtils.isEmpty(self.username) && self.username.equalsIgnoreCase(senderUsername)) {
                    senderId = self.id;
                } else if (direct && directUser != null) {
                    senderId = directUser.id;
                }
            }

            if (senderId > 0) {
                TLRPC.User sender = staged.users.get(senderId);
                if (sender == null) {
                    sender = makeUser(senderId, senderUsername, senderDisplayName, senderId == self.id);
                    if (sender != null) staged.users.put(senderId, sender);
                }
                if (senderId == self.id) sender = self;
                putAvatar(staged.userAvatars, senderId, row.optString("last_message_sender_avatar", ""));
            }

            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = messageId;
            message.date = date;
            message.peer_id = peer;
            message.dialog_id = dialogId;
            message.message = messagePreview(row);
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = senderId == self.id;
            if (senderId > 0) {
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = senderId;
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
            }

            MessageObject object = new MessageObject(account, message,
                    staged.users, staged.chats, false, false);
            ArrayList<MessageObject> messages = new ArrayList<>();
            messages.add(object);
            staged.dialogMessages.put(dialogId, messages);
        }
    }

    private static void putAvatar(Map<Long, String> target, long id, String path) {
        if (id <= 0) return;
        String url = toAvatarThumbnailUrl(path);
        if (!TextUtils.isEmpty(url)) target.put(id, url);
    }

    /**
     * ZenoraChat stores profile avatar paths such as /telegram/uploads/<stored-name>.
     * Use its authenticated-independent, server-generated small thumbnail endpoint.
     */
    private static String toAvatarThumbnailUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String value = path.trim().replace('\\', '/');
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) value = value.substring(0, queryIndex);
        int slash = value.lastIndexOf('/');
        String file = slash >= 0 ? value.substring(slash + 1) : value;
        if (!file.matches("(?i)[a-f0-9]{32}(?:\\.[a-z0-9]{1,10})?")) return "";
        return "https://miladalizadw.ir/telegram/api/avatar_thumbnail.php?file=" + file;
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
