#!/usr/bin/env python3
"""SQLite regression checks for the non-destructive Zenora outbox v183->v184 Reply migration."""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
STORAGE = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()

def capture(pattern, source, label):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError(f"missing {label}")
    return match.group(1)

legacy_create = capture(
    r'database\.executeFast\("(CREATE TABLE IF NOT EXISTS zenora_outbox[^"]+)"\)',
    MIGRATION, "legacy v181->v182 Outbox table")
fresh_sql = capture(
    r'database\.executeFast\("(CREATE TABLE zenora_outbox[^"]+)"\)',
    STORAGE, "fresh v184 Outbox table")
room_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_outbox_room_order_idx[^"]+)"\)',
    MIGRATION, "Outbox room-order index")
retry_index = capture(
    r'database\.executeFast\("(CREATE INDEX IF NOT EXISTS zenora_outbox_retry_idx[^"]+)"\)',
    MIGRATION, "Outbox retry index")
reply_columns = re.findall(
    r'database\.executeFast\("(ALTER TABLE zenora_outbox ADD COLUMN [^"]+)"\)',
    MIGRATION)

assert len(reply_columns) == 3, f"expected three additive Reply columns, got {reply_columns}"
assert "if (version == 183)" in MIGRATION
assert "PRAGMA user_version = 184" in MIGRATION
assert "public final static int LAST_DB_VERSION = 184;" in STORAGE
assert "CREATE TABLE zenora_history_state" in MIGRATION
assert '"zenora_outbox"' in STORAGE.split("DATABASE_TABLES", 1)[1].split("};", 1)[0]
assert 'excludeTables.add("zenora_outbox")' in MIGRATION
assert "INSERT OR IGNORE INTO zenora_outbox(" in MIGRATION
assert "FROM old.zenora_outbox" in MIGRATION
assert "if (version == 182 || version == 183)" in MIGRATION
assert all(column in fresh_sql for column in (
    "reply_to_id INTEGER NOT NULL DEFAULT 0",
    "reply_preview_sender_id INTEGER NOT NULL DEFAULT 0",
    "reply_preview_body TEXT NOT NULL DEFAULT ''",
))

# A realistic populated v181 DB is migrated forward to v184 without dropping messages or Outbox rows.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, body TEXT, PRIMARY KEY(mid, uid))")
db.execute("INSERT INTO messages_v2 VALUES(-17, 9001, 'legacy message')")
db.execute(legacy_create)
db.execute(room_index)
db.execute(retry_index)
db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at) "
    "VALUES('legacy-client-key', 77, 9001, -17, 123, 9001, 'legacy pending text', 1700000000, 'UNKNOWN', 10)")
db.execute("PRAGMA user_version = 182")
db.execute("CREATE TABLE zenora_history_state(room_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, "
           "zenora_user_id INTEGER NOT NULL, oldest_local_message_id INTEGER NOT NULL DEFAULT 0, "
           "newest_local_message_id INTEGER NOT NULL DEFAULT 0, older_cursor INTEGER NOT NULL DEFAULT 0, "
           "has_more_older INTEGER NOT NULL DEFAULT -1, coverage_valid INTEGER NOT NULL DEFAULT 0, "
           "updated_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(room_id, dialog_id, zenora_user_id))")
db.execute("PRAGMA user_version = 183")
for sql in reply_columns:
    db.execute(sql)
db.execute("PRAGMA user_version = 184")
assert db.execute("PRAGMA user_version").fetchone()[0] == 184
assert db.execute("SELECT body FROM messages_v2 WHERE mid=-17 AND uid=9001").fetchone()[0] == "legacy message"
legacy = db.execute(
    "SELECT body, reply_to_id, reply_preview_sender_id, reply_preview_body "
    "FROM zenora_outbox WHERE client_message_id='legacy-client-key'").fetchone()
assert legacy == ("legacy pending text", 0, 0, ""), f"legacy row was not preserved with safe defaults: {legacy}"

# New Reply operations persist their stable parent and bounded preview and survive a DB reopen.
db.execute(
    "INSERT INTO zenora_outbox(client_message_id, room_id, dialog_id, local_message_id, random_id, "
    "zenora_user_id, body, message_date, status, created_at, reply_to_id, reply_preview_sender_id, reply_preview_body) "
    "VALUES('reply-client-key', 77, 9001, -18, 124, 9001, 'reply child', 1700000001, 'SENDING', 11, 91, 9002, 'parent preview')")
db.commit()
serialized = db.iterdump()
dump = "\n".join(serialized)
db.close()
reopened = sqlite3.connect(":memory:")
reopened.executescript(dump)
assert reopened.execute(
    "SELECT reply_to_id, reply_preview_sender_id, reply_preview_body FROM zenora_outbox "
    "WHERE client_message_id='reply-client-key'").fetchone() == (91, 9002, "parent preview")

# Fresh installs start directly on the same v184 schema, without a follow-up migration.
fresh = sqlite3.connect(":memory:")
fresh.execute(fresh_sql)
fresh_columns = {row[1] for row in fresh.execute("PRAGMA table_info(zenora_outbox)")}
required = {
    "room_id", "dialog_id", "local_message_id", "random_id", "client_message_id",
    "body", "status", "next_attempt_at", "attempt_count", "zenora_user_id",
    "reply_to_id", "reply_preview_sender_id", "reply_preview_body",
}
assert required.issubset(fresh_columns), f"fresh schema missing columns: {required - fresh_columns}"
defaults = fresh.execute(
    "INSERT INTO zenora_outbox(client_message_id,room_id,dialog_id,local_message_id,random_id,"
    "zenora_user_id,body,message_date,status,created_at) VALUES('plain-key',1,1,-1,1,1,'plain',1,'SENDING',1)")
assert fresh.execute(
    "SELECT reply_to_id, reply_preview_sender_id, reply_preview_body FROM zenora_outbox "
    "WHERE client_message_id='plain-key'").fetchone() == (0, 0, "")

# Migration and fresh-create definitions must stay aligned on indexes and reply fields.
assert "zenora_outbox_room_order_idx" in MIGRATION and "zenora_outbox_retry_idx" in MIGRATION
assert "reply_to_id" in STORAGE and "reply_preview_body" in STORAGE
print("Zenora Outbox v183->v184 Reply migration tests passed")
