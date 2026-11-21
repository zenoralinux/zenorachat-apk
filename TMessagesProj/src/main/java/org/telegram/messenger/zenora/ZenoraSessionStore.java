package org.telegram.messenger.zenora;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores only the Zenora session cookies, CSRF value and minimal profile.
 * The cookie values are encrypted at rest with a non-exportable Android Keystore
 * AES-GCM key. Passwords are never stored.
 */
public final class ZenoraSessionStore {
    private static final String PREFS = "zenora_native_session";
    private static final String KEY_ALIAS = "zenorachat_native_session_v1";
    private static final String K_COOKIES = "cookies";
    private static final String K_CSRF = "csrf";
    private static final String K_USER = "user";

    private ZenoraSessionStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.encodeToString(payload, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length <= 12) return "";
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[payload.length - 12];
            System.arraycopy(payload, 0, iv, 0, 12);
            System.arraycopy(payload, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized void saveCookies(Context context, String cookies) {
        prefs(context).edit().putString(K_COOKIES, encrypt(cookies)).apply();
    }

    public static synchronized String getCookies(Context context) {
        return decrypt(prefs(context).getString(K_COOKIES, ""));
    }

    public static synchronized void saveCsrf(Context context, String csrf) {
        prefs(context).edit().putString(K_CSRF, encrypt(csrf)).apply();
    }

    public static synchronized String getCsrf(Context context) {
        return decrypt(prefs(context).getString(K_CSRF, ""));
    }

    public static synchronized void saveAuthenticatedSession(Context context, String cookies, String csrf, JSONObject user) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(K_COOKIES, encrypt(cookies));
        editor.putString(K_CSRF, encrypt(csrf));
        editor.putString(K_USER, encrypt(user == null ? "{}" : user.toString()));
        editor.apply();
    }

    public static synchronized JSONObject getCachedUser(Context context) {
        try {
            String raw = decrypt(prefs(context).getString(K_USER, ""));
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized boolean hasSession(Context context) {
        String cookies = getCookies(context);
        return cookies.contains("zenorachat_auth=") && !getCsrf(context).isEmpty();
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    /**
     * UserConfig is used by Telegram's tab/profile UI as a local identity holder.
     * The session remains exclusively authenticated by Zenora cookies, not MTProto.
     */
    public static void applyCachedUser(Context context, int account) {
        JSONObject userData = getCachedUser(context);
        if (userData == null) return;
        long id = userData.optLong("id", 0);
        if (id <= 0) return;

        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.first_name = userData.optString("display_name", userData.optString("username", "ZenoraChat"));
        user.last_name = "";
        user.username = userData.optString("username", "");
        user.phone = "";
        user.self = true;

        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated() || config.getClientUserId() != id) {
            config.setCurrentUser(user);
            config.saveConfig(true);
        }
    }
}
