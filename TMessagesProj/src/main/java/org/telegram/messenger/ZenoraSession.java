package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.tgnet.TLRPC;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ZenoraSession {
    private static final String PREFS = "zenora_native_session";
    private static final String KEY_ALIAS = "zenora.native.session.cookies.v1";
    private static final String COOKIE_VALUE = "cookie_blob";
    private static final String USER_ID = "user_id";
    private static final String USERNAME = "username";
    private static final String DISPLAY_NAME = "display_name";

    private static volatile ArrayList<DialogInfo> cachedDialogs = new ArrayList<>();

    private ZenoraSession() {}

    private static SharedPreferences preferences(Context context) {
        Context app = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
        if (app == null) {
            return null;
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAuthenticated() {
        SharedPreferences prefs = preferences(null);
        if (prefs == null || prefs.getLong(USER_ID, 0) <= 0) {
            return false;
        }
        return !getCookieHeader().isEmpty();
    }

    public static String getCookieHeader() {
        SharedPreferences prefs = preferences(null);
        if (prefs == null) {
            return "";
        }
        String encrypted = prefs.getString(COOKIE_VALUE, "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            FileLog.e(e);
            clear(null);
            return "";
        }
    }

    public static long getAccountUserId() {
        SharedPreferences prefs = preferences(null);
        return prefs == null ? 0 : prefs.getLong(USER_ID, 0);
    }

    public static String getUsername() {
        SharedPreferences prefs = preferences(null);
        return prefs == null ? "" : prefs.getString(USERNAME, "");
    }

    public static String getDisplayName() {
        SharedPreferences prefs = preferences(null);
        return prefs == null ? "" : prefs.getString(DISPLAY_NAME, "");
    }

    public static long getCompatUserId() {
        long id = getAccountUserId();
        return id > 0 ? 7000000000L + (id % 1000000000L) : 0;
    }

    public static TLRPC.User createCompatUser() {
        if (!isAuthenticated()) {
            return null;
        }
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = getCompatUserId();
        user.self = true;
        user.first_name = getDisplayName().isEmpty() ? getUsername() : getDisplayName();
        user.last_name = "";
        user.username = getUsername();
        user.phone = "";
        return user;
    }

    public static boolean saveSession(Context context, String cookieHeader, long userId, String username, String displayName) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty() || userId <= 0) {
            return false;
        }
        try {
            SharedPreferences prefs = preferences(context);
            if (prefs == null) {
                return false;
            }
            String encrypted = encrypt(cookieHeader.trim());
            return prefs.edit()
                    .putString(COOKIE_VALUE, encrypted)
                    .putLong(USER_ID, userId)
                    .putString(USERNAME, username == null ? "" : username)
                    .putString(DISPLAY_NAME, displayName == null ? "" : displayName)
                    .commit();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean updateCookieHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
            return false;
        }
        try {
            SharedPreferences prefs = preferences(null);
            if (prefs == null) {
                return false;
            }
            return prefs.edit().putString(COOKIE_VALUE, encrypt(cookieHeader.trim())).commit();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void updateProfile(long userId, String username, String displayName) {
        SharedPreferences prefs = preferences(null);
        if (prefs == null || userId <= 0) {
            return;
        }
        prefs.edit()
                .putLong(USER_ID, userId)
                .putString(USERNAME, username == null ? "" : username)
                .putString(DISPLAY_NAME, displayName == null ? "" : displayName)
                .apply();
    }

    public static void clear(Context context) {
        SharedPreferences prefs = preferences(context);
        if (prefs != null) {
            prefs.edit().clear().commit();
        }
        cachedDialogs = new ArrayList<>();
    }

    public static void setCachedDialogs(List<DialogInfo> dialogs) {
        cachedDialogs = dialogs == null ? new ArrayList<>() : new ArrayList<>(dialogs);
    }

    public static ArrayList<DialogInfo> getCachedDialogs() {
        return new ArrayList<>(cachedDialogs);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        if (Build.VERSION.SDK_INT < 23) {
            throw new IllegalStateException("Secure Android Keystore storage requires Android 6.0 or later.");
        }
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] decoded = Base64.decode(value, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) {
            throw new IllegalArgumentException("Invalid encrypted Zenora session payload.");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public static final class DialogInfo {
        public long roomId;
        public String type = "";
        public String title = "";
        public String message = "";
        public String avatarUrl = "";
        public String avatarPath = "";
        public int unreadCount;
        public int date;
        public boolean pinned;
        public boolean muted;
        public boolean verified;
        public boolean media;
        public int pinOrder;

        public boolean hasSameContent(DialogInfo other) {
            if (other == null) return false;
            return roomId == other.roomId
                    && unreadCount == other.unreadCount
                    && date == other.date
                    && pinned == other.pinned
                    && muted == other.muted
                    && verified == other.verified
                    && media == other.media
                    && pinOrder == other.pinOrder
                    && safe(title).equals(safe(other.title))
                    && safe(message).equals(safe(other.message))
                    && safe(avatarPath).equals(safe(other.avatarPath));
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
