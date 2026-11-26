package org.telegram.messenger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps Zenora peers to public thumbnail URLs for Telegram's native image pipeline. */
public final class ZenoraAvatarRegistry {
    private static final Map<Integer, ConcurrentHashMap<String, String>> AVATARS = new ConcurrentHashMap<>();
    private ZenoraAvatarRegistry() {}

    public static void beginSnapshot(int account) {
        AVATARS.put(account, new ConcurrentHashMap<>());
    }
    public static void replaceSnapshot(int account, Map<String, String> snapshot) {
        ConcurrentHashMap<String, String> replacement = new ConcurrentHashMap<>();
        if (snapshot != null) replacement.putAll(snapshot);
        AVATARS.put(account, replacement);
    }
    public static void setUserAvatar(int account, long userId, String url) {
        put(account, "u:" + userId, url);
    }
    public static void setChatAvatar(int account, long chatId, String url) {
        put(account, "c:" + chatId, url);
    }
    public static String getUserAvatar(int account, long userId) {
        return get(account, "u:" + userId);
    }
    public static String getChatAvatar(int account, long chatId) {
        return get(account, "c:" + chatId);
    }
    private static void put(int account, String key, String url) {
        ConcurrentHashMap<String, String> values =
                AVATARS.computeIfAbsent(account, ignored -> new ConcurrentHashMap<>());
        if (url == null || url.trim().isEmpty()) values.remove(key);
        else values.put(key, url.trim());
    }
    private static String get(int account, String key) {
        Map<String, String> values = AVATARS.get(account);
        return values == null ? null : values.get(key);
    }
}
