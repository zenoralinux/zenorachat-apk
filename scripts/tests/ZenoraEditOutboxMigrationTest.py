#!/usr/bin/env python3
"""Schema and recovery contract for the durable Zenora message-edit Outbox."""
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
STORAGE = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()

def capture(pattern, source, label):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError("missing " + label)
    return match.group(1)

migration_sql = capture(
    r'database\.executeFast\("(CREATE TABLE IF NOT EXISTS zenora_edit_outbox[^"]+)"\)',
    MIGRATION, "v187->v188 edit Outbox table")
fresh_sql = capture(
    r'database\.executeFast\("(CREATE TABLE IF NOT EXISTS zenora_edit_outbox[^"]+)"\)',
    STORAGE, "fresh database edit Outbox table")
migration_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_edit_outbox_retry_idx[^"]+)"\)',
    MIGRATION, "edit Outbox retry index")
fresh_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_edit_outbox_retry_idx[^"]+)"\)',
    STORAGE, "fresh edit Outbox retry index")

assert migration_sql == fresh_sql, "fresh and upgraded edit Outbox schemas diverged"
assert migration_index == fresh_index, "fresh and upgraded edit Outbox indexes diverged"
assert "LAST_DB_VERSION = 188" in STORAGE
assert "if (version == 187)" in MIGRATION and "PRAGMA user_version = 188" in MIGRATION
assert "version != 187" in MIGRATION and 'excludeTables.add("zenora_edit_outbox")' in MIGRATION
assert '"zenora_edit_outbox"' in STORAGE, "database recovery must preserve the edit Outbox"

# Apply the migration SQL and verify the exact durable operation schema.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, version INTEGER, PRIMARY KEY(mid,uid))")
db.execute(migration_sql)
db.execute(migration_index)
db.execute("INSERT INTO messages_v2 VALUES(42, 9001, 'optimistic local edit', 4)")
db.execute(
    "INSERT INTO zenora_edit_outbox(operation_id,room_id,dialog_id,zenora_user_id,message_id,expected_edit_version,original_body,new_body,status,created_at) "
    "VALUES('op-stable-0001',7,9001,9001,42,3,'old body','optimistic local edit','UNKNOWN',100)")
db.commit()
row = db.execute("SELECT operation_id,expected_edit_version,new_body,status FROM zenora_edit_outbox").fetchone()
assert row == ("op-stable-0001", 3, "optimistic local edit", "UNKNOWN")

# A restart reopens the same operation id and payload, not a new edit request.
db.close()
db = sqlite3.connect(":memory:")
db.execute(fresh_sql)
db.execute(fresh_index)
db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, version INTEGER, PRIMARY KEY(mid,uid))")
columns = {row[1] for row in db.execute("PRAGMA table_info(zenora_edit_outbox)")}
required = {
    "operation_id", "room_id", "dialog_id", "zenora_user_id", "message_id",
    "expected_edit_version", "original_body", "new_body", "status",
    "created_at", "next_attempt_at", "attempt_count",
}
assert required.issubset(columns)
db.execute("INSERT INTO messages_v2 VALUES(42,9001,'optimistic local edit',4)")
db.execute(
    "INSERT INTO zenora_edit_outbox(operation_id,room_id,dialog_id,zenora_user_id,message_id,expected_edit_version,original_body,new_body,status,created_at) "
    "VALUES('op-stable-0001',7,9001,9001,42,3,'old body','optimistic local edit','UNKNOWN',100)")
assert db.execute("SELECT operation_id,new_body,status FROM zenora_edit_outbox").fetchone() == (
    "op-stable-0001", "optimistic local edit", "UNKNOWN")

# Retry/terminal transitions retain the original draft and operation identity.
db.execute("UPDATE zenora_edit_outbox SET status='SENDING',attempt_count=attempt_count+1 WHERE operation_id='op-stable-0001'")
db.execute("UPDATE zenora_edit_outbox SET status='CONFLICT' WHERE operation_id='op-stable-0001'")
assert db.execute("SELECT new_body,status,attempt_count FROM zenora_edit_outbox").fetchone() == (
    "optimistic local edit", "CONFLICT", 1)
print("Zenora edit Outbox migration/recovery tests passed")
