package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

/**
 * Durable, per-account cache for the complete Zenora rooms.php snapshot.
 * A snapshot is only returned when its embedded user id matches the current
 * authenticated profile, preventing cross-account dialog leakage.
 */
public final class ZenoraDialogCache extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "zenora_dialogs.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "dialog_snapshots";
    private static volatile ZenoraDialogCache instance;

    private ZenoraDialogCache(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    private static ZenoraDialogCache getInstance() {
        ZenoraDialogCache local = instance;
        if (local == null) {
            synchronized (ZenoraDialogCache.class) {
                local = instance;
                if (local == null) {
                    if (ApplicationLoader.applicationContext == null) return null;
                    local = new ZenoraDialogCache(ApplicationLoader.applicationContext);
                    instance = local;
                }
            }
        }
        return local;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "account_id INTEGER PRIMARY KEY NOT NULL,"
                + "user_id INTEGER NOT NULL,"
                + "snapshot_version INTEGER NOT NULL,"
                + "payload TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations must preserve the previous valid snapshot until
        // the replacement schema and data have been fully created.
    }

    public static void write(int account, long userId, JSONObject snapshot) {
        if (account < 0 || userId <= 0 || snapshot == null
                || !snapshot.optBoolean("ok", false)) return;
        JSONObject me = snapshot.optJSONObject("me");
        if (me == null || me.optLong("id", 0) != userId
                || snapshot.optJSONArray("rooms") == null
                || snapshot.optJSONArray("dm_rooms") == null) return;
        ZenoraDialogCache cache = getInstance();
        if (cache == null) return;
        ContentValues values = new ContentValues();
        values.put("account_id", account);
        values.put("user_id", userId);
        values.put("snapshot_version", 1);
        values.put("payload", snapshot.toString());
        values.put("updated_at", System.currentTimeMillis());
        try {
            cache.getWritableDatabase().insertWithOnConflict(TABLE, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e("[ZenoraChat] unable to persist dialog cache: " + e.getMessage());
        }
    }

    public static JSONObject read(int account, long expectedUserId) {
        if (account < 0 || expectedUserId <= 0) return null;
        ZenoraDialogCache cache = getInstance();
        if (cache == null) return null;
        Cursor cursor = null;
        try {
            cursor = cache.getReadableDatabase().query(TABLE,
                    new String[] { "user_id", "payload" },
                    "account_id=?", new String[] { String.valueOf(account) },
                    null, null, null, "1");
            if (!cursor.moveToFirst() || cursor.getLong(0) != expectedUserId) return null;
            String payload = cursor.getString(1);
            JSONObject snapshot = new JSONObject(payload);
            JSONObject me = snapshot.optJSONObject("me");
            if (!snapshot.optBoolean("ok", false) || me == null
                    || me.optLong("id", 0) != expectedUserId
                    || snapshot.optJSONArray("rooms") == null
                    || snapshot.optJSONArray("dm_rooms") == null) {
                delete(account);
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            FileLog.e("[ZenoraChat] cached dialog snapshot is unreadable: " + e.getMessage());
            delete(account);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public static void delete(int account) {
        ZenoraDialogCache cache = getInstance();
        if (cache == null || account < 0) return;
        try {
            cache.getWritableDatabase().delete(TABLE, "account_id=?",
                    new String[] { String.valueOf(account) });
        } catch (Exception e) {
            FileLog.e("[ZenoraChat] unable to delete dialog cache: " + e.getMessage());
        }
    }
}
