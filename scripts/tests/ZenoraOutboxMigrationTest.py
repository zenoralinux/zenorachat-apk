#!/usr/bin/env python3
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
migration_source = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
storage_source = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()

def capture(pattern, source, label):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError(f"missing {label} in Java source")
    return match.group(1)

legacy_migration_sql = capture(
    r'database\.executeFast\("(CREATE TABLE IF NOT EXISTS zenora_outbox[^"]+)"\)',
    migration_source, "v181->v182 legacy outbox table")
fresh_sql = capture(
    r'database\.executeFast\("(CREATE TABLE zenora_outbox[^"]+)"\)',
    storage_source, "fresh database outbox table")
room_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_outbox_room_order_idx[^"]+)"\)',
    migration_source, "outbox FIFO index")
retry_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_outbox_retry_idx[^"]+)"\)',
    migration_source, "outbox retry index")
reply_migration_sql = capture(
    r'database\.executeFast\("(ALTER TABLE zenora_outbox ADD COLUMN reply_to_id[^"]+)"\)',
    migration_source, "v183->v184 reply column migration")

assert "PRAGMA user_version = 182" in migration_source
assert "PRAGMA user_version = 183" in migration_source
assert "PRAGMA user_version = 184" in migration_source
assert "public final static int LAST_DB_VERSION = 188;" in storage_source
assert "PRAGMA user_version = 185" in migration_source
assert "PRAGMA user_version = 186" in migration_source
assert "PRAGMA user_version = 187" in migration_source
assert "PRAGMA user_version = 188" in migration_source
assert "CREATE TABLE IF NOT EXISTS zenora_edit_outbox" in migration_source
assert "CREATE TABLE IF NOT EXISTS zenora_edit_outbox" in storage_source
assert "if (version == 187)" in migration_source
assert "CREATE TABLE IF NOT EXISTS zenora_message_mutation_pending_edit" in migration_source
assert "CREATE TABLE zenora_message_mutation_cursor" in storage_source
assert "CREATE TABLE zenora_message_mutation_deleted" in storage_source
assert "CREATE TABLE zenora_message_mutation_pending_edit" in storage_source
assert "if (version == 183)" in migration_source
assert "if (!hasColumn(database, \"zenora_outbox\", \"reply_to_id\"))" in migration_source
assert '"zenora_outbox"' in storage_source, "backup/restore table list must include the outbox"
assert "reply_to_id INTEGER NOT NULL DEFAULT 0" in fresh_sql

# Upgrade an old populated Outbox through 183 -> 184. Existing rows survive.
old_db = sqlite3.connect(":memory:")
old_db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, PRIMARY KEY(mid, uid))")
old_db.execute("INSERT INTO messages_v2 VALUES(-17, 9001, 'legacy message')")
old_db.execute("PRAGMA user_version = 181")
old_db.execute(legacy_migration_sql)
old_db.execute(room_index)
old_db.execute(retry_index)
old_db.execute("PRAGMA user_version = 182")
old_db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('client-key-0001', 77, 9001, -17, 123, 9001, 'pending text', 1700000000, 'SENDING', 10)")
old_db.execute("CREATE TABLE zenora_history_state(room_id INTEGER, dialog_id INTEGER, zenora_user_id INTEGER, PRIMARY KEY(room_id,dialog_id,zenora_user_id))")
old_db.execute("PRAGMA user_version = 183")
old_db.execute(reply_migration_sql)
old_db.execute("PRAGMA user_version = 184")
assert old_db.execute("PRAGMA user_version").fetchone()[0] == 184
assert old_db.execute("SELECT body FROM messages_v2 WHERE mid=-17 AND uid=9001").fetchone()[0] == "legacy message"
assert old_db.execute("SELECT reply_to_id FROM zenora_outbox WHERE client_message_id='client-key-0001'").fetchone()[0] == 0
old_db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, reply_to_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('client-key-0002', 77, 321, 9001, -18, 124, 9001, 'reply text', 1700000001, 'SENDING', 11)")
assert old_db.execute("SELECT reply_to_id FROM zenora_outbox WHERE client_message_id='client-key-0002'").fetchone()[0] == 321
assert old_db.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 2

fresh_db = sqlite3.connect(":memory:")
fresh_db.execute(fresh_sql)
columns = {row[1] for row in fresh_db.execute("PRAGMA table_info(zenora_outbox)")}
required = {
    "room_id", "reply_to_id", "dialog_id", "local_message_id", "random_id",
    "client_message_id", "body", "status", "next_attempt_at", "attempt_count",
    "zenora_user_id",
}
assert required.issubset(columns), f"missing outbox columns: {required - columns}"

# Per-account databases remain isolated.
account_a = sqlite3.connect(":memory:")
account_b = sqlite3.connect(":memory:")
account_a.execute(fresh_sql)
account_b.execute(fresh_sql)
account_a.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, reply_to_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('account-a-key', 1, 91, 1, -1, 11, 101, 'A', 1, 'SENDING', 1)")
assert account_a.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 1
assert account_b.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 0

# Equal-time FIFO continues to use the existing descending local-ID tie-break.
old_db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, reply_to_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('client-key-0003', 77, 321, 9001, -19, 125, 9001, 'next reply', 1700000002, 'SENDING', 11)")
rows = old_db.execute(
    "SELECT client_message_id FROM zenora_outbox WHERE room_id=77 "
    "ORDER BY created_at ASC, local_message_id DESC").fetchall()
assert [row[0] for row in rows] == ["client-key-0001", "client-key-0002", "client-key-0003"]

print("Zenora outbox 183->184 non-destructive migration tests passed")
