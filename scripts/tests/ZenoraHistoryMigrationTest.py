#!/usr/bin/env python3
"""Regression test for Zenora durable history coverage migration."""
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
STORAGE = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()

def capture(pattern, source, label):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError(f"missing {label}")
    return match.group(1)

migration_sql = capture(
    r'database\.executeFast\("(CREATE TABLE zenora_history_state[^"]+)"\)',
    MIGRATION, "182->183 history state table")
fresh_sql = capture(
    r'database\.executeFast\("(CREATE TABLE zenora_history_state[^"]+)"\)',
    STORAGE, "fresh history state table")
index_sql = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_history_dialog_idx[^"]+)"\)',
    MIGRATION, "history state index")

assert migration_sql == fresh_sql, "fresh and upgraded history-state schemas diverged"
assert "public final static int LAST_DB_VERSION = 183;" in STORAGE
assert "PRAGMA user_version = 183" in MIGRATION
assert '"zenora_outbox"' in STORAGE and '"zenora_history_state"' in STORAGE

# Upgrade a populated v182 database. Existing message and Outbox rows must survive.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, PRIMARY KEY(mid, uid))")
db.execute("CREATE TABLE zenora_outbox(client_message_id TEXT PRIMARY KEY, status TEXT)")
db.execute("INSERT INTO messages_v2 VALUES(101, 77, 'cached')")
db.execute("INSERT INTO zenora_outbox VALUES('client-key', 'UNKNOWN')")
db.execute("PRAGMA user_version = 182")
db.execute(migration_sql)
db.execute(index_sql)
db.execute("PRAGMA user_version = 183")
assert db.execute("PRAGMA user_version").fetchone()[0] == 183
assert db.execute("SELECT body FROM messages_v2 WHERE mid=101 AND uid=77").fetchone()[0] == "cached"
assert db.execute("SELECT status FROM zenora_outbox WHERE client_message_id='client-key'").fetchone()[0] == "UNKNOWN"

db.execute(
    "INSERT INTO zenora_history_state(room_id, dialog_id, zenora_user_id, oldest_local_message_id, "
    "newest_local_message_id, older_cursor, has_more_older, coverage_valid, updated_at) "
    "VALUES(5, 77, 77, 40, 101, 40, 1, 1, 1000)")
state = db.execute(
    "SELECT older_cursor, has_more_older, coverage_valid FROM zenora_history_state "
    "WHERE room_id=5 AND dialog_id=77 AND zenora_user_id=77").fetchone()
assert state == (40, 1, 1)

# A fresh install must create exactly the same table and index.
fresh = sqlite3.connect(":memory:")
fresh.execute(fresh_sql)
columns = {row[1] for row in fresh.execute("PRAGMA table_info(zenora_history_state)")}
required = {
    "room_id", "dialog_id", "zenora_user_id", "oldest_local_message_id",
    "newest_local_message_id", "older_cursor", "has_more_older", "coverage_valid", "updated_at",
}
assert required.issubset(columns)
fresh.execute(index_sql)
print("Zenora history coverage migration tests passed")
