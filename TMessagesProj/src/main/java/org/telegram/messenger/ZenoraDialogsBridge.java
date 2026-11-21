package org.telegram.messenger;

import android.util.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ZenoraDialogsBridge {
    private ZenoraDialogsBridge() {}

    public static void holdNativeDialogLoad(int account) {
        try {
            Class<?> activity = Class.forName("org.telegram.ui.DialogsActivity");
            Field loaded = activity.getDeclaredField("dialogsLoaded");
            loaded.setAccessible(true);
            boolean[] values = (boolean[]) loaded.get(null);
            if (values != null && account >= 0 && account < values.length) values[account] = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void apply(int account, JSONObject me, JSONObject response) throws Exception {
        if (me == null || response == null || !response.optBoolean("ok", false)) {
            throw new IllegalArgumentException("پاسخ حساب یا فهرست گفتگوها معتبر نیست.");
        }

        UserConfig config = UserConfig.getInstance(account);
        TLRPC.User self = makeUser(me, true);
        config.setCurrentUser(self);
        config.saveConfig(true);

        MessagesController controller = MessagesController.getInstance(account);
        storeUser(controller, self);

        ArrayList<JSONObject> rows = new ArrayList<>();
        JSONArray dmRooms = response.optJSONArray("dm_rooms");
        JSONArray rooms = response.optJSONArray("rooms");
        if (dmRooms != null) {
            for (int i = 0; i < dmRooms.length(); i++) {
                JSONObject row = dmRooms.optJSONObject(i);
                if (row != null) rows.add(row);
            }
        }
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject row = rooms.optJSONObject(i);
                if (row != null) rows.add(row);
            }
        }

        Collections.sort(rows, (left, right) -> {
            boolean leftPinned = left.optInt("is_pinned", 0) == 1;
            boolean rightPinned = right.optInt("is_pinned", 0) == 1;
            if (leftPinned != rightPinned) return leftPinned ? -1 : 1;
            if (leftPinned) {
                int order = Integer.compare(left.optInt("pin_order", 0), right.optInt("pin_order", 0));
                if (order != 0) return order;
            }
            return Long.compare(dateMillis(right.optString("last_message_time", "")),
                    dateMillis(left.optString("last_message_time", "")));
        });

        ArrayList<TLRPC.Dialog> nativeDialogs = new ArrayList<>();
        for (JSONObject row : rows) {
            String type = row.optString("type", "");
            long roomId = row.optLong("id", 0);
            if (roomId <= 0) continue;

            TLRPC.Dialog dialog;
            if ("dm".equals(type) || "direct".equals(type)) {
                long otherId = row.optLong("other_user_id", 0);
                if (otherId <= 0 || otherId == self.id) continue;
                JSONObject userData = new JSONObject();
                userData.put("id", otherId);
                userData.put("username", row.optString("other_username", ""));
                userData.put("display_name", row.optString("other_display_name", row.optString("other_username", "")));
                userData.put("is_bot", row.optInt("other_is_bot", 0));
                userData.put("is_verified", row.optInt("other_is_verified", 0));
                TLRPC.User peerUser = makeUser(userData, false);
                storeUser(controller, peerUser);
                Object peer = newObject("org.telegram.tgnet.TLRPC$TL_peerUser");
                set(peer, "user_id", otherId);
                dialog = makeDialog(otherId, peer, row);
            } else if ("group".equals(type)) {
                Object peer = newObject("org.telegram.tgnet.TLRPC$TL_peerChat");
                set(peer, "chat_id", roomId);
                TLRPC.Chat chat = makeGroup(roomId, row);
                storeChat(controller, chat);
                dialog = makeDialog(-roomId, peer, row);
            } else if ("channel".equals(type)) {
                Object peer = newObject("org.telegram.tgnet.TLRPC$TL_peerChannel");
                set(peer, "channel_id", roomId);
                TLRPC.Chat channel = makeChannel(roomId, row);
                storeChat(controller, channel);
                dialog = makeDialog(-roomId, peer, row);
            } else {
                continue;
            }
            nativeDialogs.add(dialog);
        }

        ArrayList<TLRPC.Dialog> target = controller.getDialogs(0);
        target.clear();
        target.addAll(nativeDialogs);
        replaceDialogsDictionary(controller, nativeDialogs);
        replaceOptionalList(controller, "dialogsServerOnly", nativeDialogs);
        holdNativeDialogLoad(account);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static TLRPC.User makeUser(JSONObject data, boolean self) throws Exception {
        TLRPC.User user = (TLRPC.User) newObject("org.telegram.tgnet.TLRPC$TL_user");
        long id = data.optLong("id", 0);
        String display = data.optString("display_name", data.optString("username", ""));
        String first = display.trim();
        String last = "";
        int space = first.indexOf(' ');
        if (space > 0) {
            last = first.substring(space + 1).trim();
            first = first.substring(0, space).trim();
        }
        set(user, "id", id);
        set(user, "first_name", first.isEmpty() ? data.optString("username", "Zenora") : first);
        set(user, "last_name", last);
        set(user, "username", data.optString("username", ""));
        set(user, "phone", "");
        set(user, "self", self);
        set(user, "bot", data.optInt("is_bot", 0) == 1);
        set(user, "verified", data.optInt("is_verified", 0) == 1);
        return user;
    }

    private static TLRPC.Chat makeGroup(long id, JSONObject row) throws Exception {
        TLRPC.Chat chat = (TLRPC.Chat) newObject("org.telegram.tgnet.TLRPC$TL_chat");
        set(chat, "id", id);
        set(chat, "title", row.optString("name", "گروه زنوراچت"));
        set(chat, "participants_count", row.optInt("member_count", 0));
        set(chat, "date", dateSeconds(row.optString("last_message_time", "")));
        return chat;
    }

    private static TLRPC.Chat makeChannel(long id, JSONObject row) throws Exception {
        TLRPC.Chat chat = (TLRPC.Chat) newObject("org.telegram.tgnet.TLRPC$TL_channel");
        set(chat, "id", id);
        set(chat, "title", row.optString("name", "کانال زنوراچت"));
        set(chat, "access_hash", 0L);
        set(chat, "broadcast", true);
        set(chat, "megagroup", false);
        set(chat, "verified", row.optInt("is_verified", 0) == 1);
        set(chat, "participants_count", row.optInt("member_count", 0));
        set(chat, "date", dateSeconds(row.optString("last_message_time", "")));
        return chat;
    }

    private static TLRPC.Dialog makeDialog(long dialogId, Object peer, JSONObject row) throws Exception {
        TLRPC.Dialog dialog = (TLRPC.Dialog) newObject("org.telegram.tgnet.TLRPC$TL_dialog");
        set(dialog, "id", dialogId);
        set(dialog, "peer", peer);
        set(dialog, "top_message", 0);
        set(dialog, "date", dateSeconds(row.optString("last_message_time", "")));
        set(dialog, "unread_count", row.optInt("unread_count", 0));
        set(dialog, "read_inbox_max_id", row.optInt("last_read_id", 0));
        set(dialog, "pinned", row.optInt("is_pinned", 0) == 1);
        set(dialog, "pinnedNum", row.optInt("pin_order", 0));
        set(dialog, "folder_id", 0);
        return dialog;
    }

    private static void storeUser(MessagesController controller, TLRPC.User user) throws Exception {
        Method method = findStoreMethod(controller.getClass(), "putUser", user.getClass());
        if (method != null) {
            method.invoke(controller, user, true);
            return;
        }
        putIntoSparseField(controller, "users", user.id, user);
    }

    private static void storeChat(MessagesController controller, TLRPC.Chat chat) throws Exception {
        Method method = findStoreMethod(controller.getClass(), "putChat", chat.getClass());
        if (method != null) {
            method.invoke(controller, chat, true);
            return;
        }
        putIntoSparseField(controller, "chats", chat.id, chat);
    }

    private static Method findStoreMethod(Class<?> type, String name, Class<?> valueType) {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2
                    && parameters[0].isAssignableFrom(valueType)
                    && (parameters[1] == boolean.class || parameters[1] == Boolean.class)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void replaceDialogsDictionary(MessagesController controller, List<TLRPC.Dialog> dialogs) {
        try {
            Field field = findField(controller.getClass(), "dialogs_dict");
            if (field == null) return;
            field.setAccessible(true);
            Object dictionary = field.get(controller);
            if (dictionary == null) return;
            dictionary.getClass().getMethod("clear").invoke(dictionary);
            Method put = null;
            for (Method method : dictionary.getClass().getMethods()) {
                if (method.getName().equals("put") && method.getParameterTypes().length == 2
                        && (method.getParameterTypes()[0] == long.class || method.getParameterTypes()[0] == Long.class)) {
                    put = method;
                    break;
                }
            }
            if (put == null) return;
            for (TLRPC.Dialog dialog : dialogs) {
                put.invoke(dictionary, dialog.id, dialog);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void replaceOptionalList(MessagesController controller, String name, List<TLRPC.Dialog> dialogs) {
        try {
            Field field = findField(controller.getClass(), name);
            if (field == null) return;
            field.setAccessible(true);
            Object value = field.get(controller);
            if (value instanceof List) {
                List list = (List) value;
                list.clear();
                list.addAll(dialogs);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void putIntoSparseField(Object owner, String name, long key, Object value) throws Exception {
        Field field = findField(owner.getClass(), name);
        if (field == null) return;
        field.setAccessible(true);
        Object sparse = field.get(owner);
        if (sparse == null) return;
        Method put = sparse.getClass().getMethod("put", long.class, Object.class);
        put.invoke(sparse, key, value);
    }

    private static Object newObject(String className) throws Exception {
        Class<?> type = Class.forName(className);
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) return;
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == long.class || type == Long.class) {
                field.set(target, value instanceof Number ? ((Number) value).longValue() : 0L);
            } else if (type == int.class || type == Integer.class) {
                field.set(target, value instanceof Number ? ((Number) value).intValue() : 0);
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(target, value instanceof Boolean && (Boolean) value);
            } else if (type == String.class) {
                field.set(target, value == null ? "" : String.valueOf(value));
            } else if (value == null || type.isInstance(value)) {
                field.set(target, value);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static long dateMillis(String value) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value);
            return date == null ? 0 : date.getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int dateSeconds(String value) {
        long millis = dateMillis(value);
        return millis <= 0 ? 0 : (int) (millis / 1000L);
    }
}
