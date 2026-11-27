#!/usr/bin/env python3
"""SQLite schema and durability checks for Zenora edit Outbox v188."""
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
storage = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()
migration = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()

def capture(pattern, source, label):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError(f"missing {label}")
    return match.group(1)

fresh_sql = capture(
    r'database\\.executeFast\\("(CREATE TABLE zenora_edit_outbox[^"]+)"\\)',
    storage, "fresh edit Outbox table")
fresh_index = capture(
    r'database\\.executeFast\\("(CREATE UNIQUE INDEX zenora_edit_outbox_message_idx[^"]+)"\\)',
    storage, "fresh edit Outbox unique index")
upgrade_sql = capture(
    r'database\\.executeFast\\("(CREATE TABLE IF NOT EXISTS zenora_edit_outbox[^"]+)"\\)',
    migration, "187->188 edit Outbox migration")
upgrade_index = capture(
    r'database\\.executeFast\\("(CREATE UNIQUE INDEX IF NOT EXISTS zenora_edit_outbox_message_idx[^"]+)"\\)',
    migration, "187->188 edit Outbox index")

assert "LAST_DB_VERSION = 188" in storage
assert "if (version == 187)" in migration and "PRAGMA user_version = 188" in migration
assert '"zenora_edit_outbox"' in storage
assert "if (version < 188) excludeTables.add(\"zenora_edit_outbox\")" in migration

def test_schema(db):
    db.execute(fresh_sql)
    db.execute(fresh_index)
    columns = {row[1] for row in db.execute("PRAGMA table_info(zenora_edit_outbox)")}
    required = {
        "operation_id", "room_id", "dialog_id", "message_id", "zenora_user_id",
        "expected_edit_version", "original_body", "original_entities", "new_body",
        "new_entities", "status", "created_at", "next_attempt_at", "attempt_count"
    }
    assert required.issubset(columns), f"missing columns: {required - columns}"

# Upgrade preserves existing message data and creates an initially empty durable queue.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT)")
db.execute("INSERT INTO messages_v2 VALUES(51, 9001, 'existing canonical message')")
db.execute("PRAGMA user_version=187")
db.execute(upgrade_sql)
db.execute(upgrade_index)
db.execute("PRAGMA user_version=188")
assert db.execute("PRAGMA user_version").fetchone()[0] == 188
assert db.execute("SELECT body FROM messages_v2 WHERE mid=51 AND uid=9001").fetchone()[0] == "existing canonical message"
test_schema(db)

# Commit an edit, close the database, then reopen it: the operation and both
# versions of the payload remain available for deterministic retry/rollback.
db.execute(
    "INSERT INTO zenora_edit_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    ("operation-0001", 77, 9001, 51, 9001, 1, "before edit", b"old entities",
     "after edit", b"new entities", "UNKNOWN", 123, 456, 2))
db.commit()
db.close()
reopened = sqlite3.connect(":memory:")
reopened.execute(fresh_sql)
reopened.execute(fresh_index)
reopened.execute(
    "INSERT INTO zenora_edit_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    ("operation-0001", 77, 9001, 51, 9001, 1, "before edit", b"old entities",
     "after edit", b"new entities", "UNKNOWN", 123, 456, 2))
reopened.commit()
row = reopened.execute(
    "SELECT operation_id,expected_edit_version,original_body,new_body,status,next_attempt_at,attempt_count "
    "FROM zenora_edit_outbox WHERE operation_id='operation-0001'").fetchone()
assert row == ("operation-0001", 1, "before edit", "after edit", "UNKNOWN", 456, 2)

# A message can have only one outgoing edit operation per account/room/message.
try:
    reopened.execute(
        "INSERT INTO zenora_edit_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ("operation-0002", 77, 9001, 51, 9001, 2, "after edit", b"", "third body",
         b"", "SENDING", 124, 0, 0))
    raise AssertionError("duplicate pending edit for the same message was accepted")
except sqlite3.IntegrityError:
    pass

print("PASS: Zenora edit Outbox v188 migration and durable SQLite schema")
