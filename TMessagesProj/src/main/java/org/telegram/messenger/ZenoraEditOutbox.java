package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable per-account edit queue. Each row contains both the optimistic body
 * and its immutable operation key, committed in one SQLite transaction.
 */
public final class ZenoraEditOutbox extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "zenora_edit_outbox.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "zenora_edit_outbox";
    private static volatile ZenoraEditOutbox instance;

    public static final class Record {
        public final String operationId;
        public final int account;
        public final long zenoraUserId;
        public final long roomId;
        public final long dialogId;
        public final long messageId;
        public final long sequence;
        public final long expectedEditVersion;
        public final String body;
        public final int attemptCount;
        public final long nextAttemptAt;
        public final String state;
        public final long createdAt;

        Record(String operationId, int account, long userId, long roomId, long dialogId,
               long messageId, long sequence, long expectedVersion, String body,
               int attempts, long nextAttemptAt, String state, long createdAt) {
            this.operationId = operationId;
            this.account = account;
            this.zenoraUserId = userId;
            this.roomId = roomId;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.sequence = sequence;
            this.expectedEditVersion = expectedVersion;
            this.body = body;
            this.attemptCount = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.state = state;
            this.createdAt = createdAt;
        }
    }

    private ZenoraEditOutbox(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    public static ZenoraEditOutbox getInstance(Context context) {
        if (context == null) throw new IllegalStateException("Zenora application context is unavailable");
        ZenoraEditOutbox value = instance;
        if (value == null) {
            synchronized (ZenoraEditOutbox.class) {
                value = instance;
                if (value == null) instance = value = new ZenoraEditOutbox(context);
            }
        }
        return value;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "operation_id TEXT PRIMARY KEY NOT NULL,"
                + "account INTEGER NOT NULL,"
                + "zenora_user_id INTEGER NOT NULL,"
                + "room_id INTEGER NOT NULL,"
                + "dialog_id INTEGER NOT NULL,"
                + "message_id INTEGER NOT NULL,"
                + "sequence INTEGER NOT NULL,"
                + "expected_edit_version INTEGER NOT NULL,"
                + "body TEXT NOT NULL,"
                + "attempt_count INTEGER NOT NULL DEFAULT 0,"
                + "next_attempt_at INTEGER NOT NULL DEFAULT 0,"
                + "state TEXT NOT NULL DEFAULT 'PENDING',"
                + "created_at INTEGER NOT NULL,"
                + "last_error TEXT,"
                + "UNIQUE(account, zenora_user_id, room_id, dialog_id, message_id, sequence)"
                + ")");
        db.execSQL("CREATE INDEX zenora_edit_outbox_order_idx ON " + TABLE
                + "(account, zenora_user_id, created_at, sequence)");
        db.execSQL("CREATE INDEX zenora_edit_outbox_message_idx ON " + TABLE
                + "(account, zenora_user_id, room_id, dialog_id, message_id, sequence)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema version 1 is append-only. Future upgrades must preserve queued bodies.
    }

    /** Atomically allocate the next sequence/version and persist the full payload. */
    public synchronized Record enqueue(String operationId, int account, long userId,
                                       long roomId, long dialogId, long messageId,
                                       long requestedVersion, String body) {
        if (operationId == null || operationId.isEmpty() || account < 0 || userId <= 0
                || roomId <= 0 || dialogId == 0 || messageId <= 0
                || requestedVersion <= 0 || requestedVersion >= Integer.MAX_VALUE
                || body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid durable Zenora edit");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long sequence = 1L;
            long expectedVersion = requestedVersion;
            Cursor latest = db.rawQuery("SELECT sequence, expected_edit_version FROM " + TABLE
                            + " WHERE account=? AND zenora_user_id=? AND room_id=? AND dialog_id=? AND message_id=?"
                            + " ORDER BY sequence DESC LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(userId), String.valueOf(roomId),
                            String.valueOf(dialogId), String.valueOf(messageId)});
            try {
                if (latest.moveToFirst()) {
                    sequence = latest.getLong(0) + 1L;
                    expectedVersion = Math.max(requestedVersion, latest.getLong(1) + 1L);
                }
            } finally {
                latest.close();
            }
            if (expectedVersion >= Integer.MAX_VALUE) {
                throw new IllegalStateException("Zenora edit version limit reached");
            }

            long createdAt = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("operation_id", operationId);
            values.put("account", account);
            values.put("zenora_user_id", userId);
            values.put("room_id", roomId);
            values.put("dialog_id", dialogId);
            values.put("message_id", messageId);
            values.put("sequence", sequence);
            values.put("expected_edit_version", expectedVersion);
            values.put("body", body);
            values.put("attempt_count", 0);
            values.put("next_attempt_at", 0L);
            values.put("state", "PENDING");
            values.put("created_at", createdAt);
            values.putNull("last_error");
            db.insertOrThrow(TABLE, null, values);
            db.setTransactionSuccessful();
            return new Record(operationId, account, userId, roomId, dialogId, messageId,
                    sequence, expectedVersion, body, 0, 0L, "PENDING", createdAt);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Record> loadPendingForUser(int account, long userId) {
        ArrayList<Record> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM " + TABLE
                        + " WHERE account=? AND zenora_user_id=? ORDER BY created_at ASC, rowid ASC",
                new String[]{String.valueOf(account), String.valueOf(userId)});
        try {
            while (cursor.moveToNext()) result.add(read(cursor));
        } finally {
            cursor.close();
        }
        return result;
    }

    public synchronized Record firstPendingForUser(int account, long userId) {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM " + TABLE
                        + " WHERE account=? AND zenora_user_id=? ORDER BY created_at ASC, rowid ASC LIMIT 1",
                new String[]{String.valueOf(account), String.valueOf(userId)});
        try {
            return cursor.moveToFirst() ? read(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized Record latestForMessage(int account, long userId, long roomId,
                                                long dialogId, long messageId) {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM " + TABLE
                        + " WHERE account=? AND zenora_user_id=? AND room_id=? AND dialog_id=? AND message_id=?"
                        + " ORDER BY sequence DESC LIMIT 1",
                new String[]{String.valueOf(account), String.valueOf(userId), String.valueOf(roomId),
                        String.valueOf(dialogId), String.valueOf(messageId)});
        try {
            return cursor.moveToFirst() ? read(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized void markSending(String operationId) {
        ContentValues values = new ContentValues();
        values.put("state", "SENDING");
        getWritableDatabase().update(TABLE, values, "operation_id=?", new String[]{operationId});
    }

    public synchronized void resetSending(int account, long userId) {
        ContentValues values = new ContentValues();
        values.put("state", "PENDING");
        values.put("next_attempt_at", 0L);
        getWritableDatabase().update(TABLE, values,
                "account=? AND zenora_user_id=? AND state='SENDING'",
                new String[]{String.valueOf(account), String.valueOf(userId)});
    }

    public synchronized void markRetry(String operationId, int attempts, long nextAttemptAt,
                                        String error) {
        ContentValues values = new ContentValues();
        values.put("state", "PENDING");
        values.put("attempt_count", Math.max(0, attempts));
        values.put("next_attempt_at", Math.max(0L, nextAttemptAt));
        values.put("last_error", error == null ? "" : error.substring(0, Math.min(256, error.length())));
        getWritableDatabase().update(TABLE, values, "operation_id=?", new String[]{operationId});
    }

    /** Rebase a complete per-message chain without changing any operation IDs or bodies. */
    public synchronized void rebaseMessage(int account, long userId, long roomId,
                                           long dialogId, long messageId, long serverVersion) {
        if (serverVersion <= 0 || serverVersion >= Integer.MAX_VALUE) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ArrayList<String> ids = new ArrayList<>();
            Cursor cursor = db.rawQuery("SELECT operation_id FROM " + TABLE
                            + " WHERE account=? AND zenora_user_id=? AND room_id=? AND dialog_id=? AND message_id=?"
                            + " ORDER BY sequence ASC",
                    new String[]{String.valueOf(account), String.valueOf(userId), String.valueOf(roomId),
                            String.valueOf(dialogId), String.valueOf(messageId)});
            try {
                while (cursor.moveToNext()) ids.add(cursor.getString(0));
            } finally {
                cursor.close();
            }
            long expected = serverVersion;
            for (String id : ids) {
                ContentValues values = new ContentValues();
                values.put("expected_edit_version", expected++);
                values.put("attempt_count", 0);
                values.put("next_attempt_at", 0L);
                values.put("state", "PENDING");
                values.putNull("last_error");
                db.update(TABLE, values, "operation_id=?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean delete(String operationId) {
        return getWritableDatabase().delete(TABLE, "operation_id=?",
                new String[]{operationId}) == 1;
    }

    public static long backoffDelayMs(int attempt) {
        int exponent = Math.min(8, Math.max(0, attempt - 1));
        return Math.min(300_000L, 1_000L << exponent);
    }

    private static Record read(Cursor cursor) {
        return new Record(
                cursor.getString(cursor.getColumnIndexOrThrow("operation_id")),
                cursor.getInt(cursor.getColumnIndexOrThrow("account")),
                cursor.getLong(cursor.getColumnIndexOrThrow("zenora_user_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("room_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("dialog_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("message_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("sequence")),
                cursor.getLong(cursor.getColumnIndexOrThrow("expected_edit_version")),
                cursor.getString(cursor.getColumnIndexOrThrow("body")),
                cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
                cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("state")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        );
    }
}
