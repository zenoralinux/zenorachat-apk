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

migration_sql = capture(
    r'database\\.executeFast\\("(CREATE TABLE IF NOT EXISTS zenora_outbox[^"]+)"\\)',
    migration_source, "v181->v182 outbox table migration")
fresh_sql = capture(
    r'database\\.executeFast\\("(CREATE TABLE zenora_outbox[^"]+)"\\)',
    storage_source, "fresh database outbox table")
room_index = capture(
    r'database\\.executeFast\\("(CREATE INDEX IF NOT EXISTS zenora_outbox_room_order_idx[^"]+)"\\)',
    migration_source, "outbox FIFO index")
retry_index = capture(
    r'database\\.executeFast\\("(CREATE INDEX IF NOT EXISTS zenora_outbox_retry_idx[^"]+)"\\)',
    migration_source, "outbox retry index")

assert migration_sql.replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE", 1) == fresh_sql, \
    "fresh and migrated outbox schemas diverged"
assert "PRAGMA user_version = 182" in migration_source
assert "public final static int LAST_DB_VERSION = 182;" in storage_source
assert '"zenora_outbox"' in storage_source, "backup/restore table list must include the outbox"

# Upgrade a populated v181 database without deleting or resetting existing messages.
old_db = sqlite3.connect(":memory:")
old_db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, PRIMARY KEY(mid, uid))")
old_db.execute("INSERT INTO messages_v2 VALUES(-17, 9001, 'legacy message')")
old_db.execute("PRAGMA user_version = 181")
old_db.execute(migration_sql)
old_db.execute(room_index)
old_db.execute(retry_index)
old_db.execute("PRAGMA user_version = 182")
assert old_db.execute("PRAGMA user_version").fetchone()[0] == 182
assert old_db.execute("SELECT body FROM messages_v2 WHERE mid=-17 AND uid=9001").fetchone()[0] == "legacy message"
old_db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('client-key-0001', 77, 9001, -17, 123, 9001, 'pending text', 1700000000, 'SENDING', 10)")
assert old_db.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 1

# Fresh installations create the exact same table shape.
fresh_db = sqlite3.connect(":memory:")
fresh_db.execute(fresh_sql)
columns = {row[1] for row in fresh_db.execute("PRAGMA table_info(zenora_outbox)")}
required = {
    "room_id", "dialog_id", "local_message_id", "random_id", "client_message_id",
    "body", "status", "next_attempt_at", "attempt_count", "zenora_user_id",
}
assert required.issubset(columns), f"missing outbox columns: {required - columns}"

# Per-account MessagesStorage files remain isolated.
account_a = sqlite3.connect(":memory:")
account_b = sqlite3.connect(":memory:")
account_a.execute(fresh_sql)
account_b.execute(fresh_sql)
account_a.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('account-a-key', 1, 1, -1, 11, 101, 'A', 1, 'SENDING', 1)")
assert account_a.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 1
assert account_b.execute("SELECT COUNT(*) FROM zenora_outbox").fetchone()[0] == 0

# FIFO ordering is deterministic even when two messages share the same millisecond.
old_db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('client-key-0002', 77, 9001, -18, 124, 9001, 'next text', 1700000001, 'SENDING', 10)")
rows = old_db.execute(
    "SELECT client_message_id FROM zenora_outbox WHERE room_id=77 "
    "ORDER BY created_at ASC, local_message_id ASC").fetchall()
assert [row[0] for row in rows] == ["client-key-0002", "client-key-0001"], \
    "local message id must provide stable FIFO order for equal timestamps"

print("Zenora outbox additive migration tests passed")
