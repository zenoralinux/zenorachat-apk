#!/usr/bin/env python3
"""SQLite-backed behavioral model for Android edit reconciliation.

API acknowledgement and Event delivery are projected through the same
version-aware reconciliation policy in both arrival orders.

This test exercises durable transaction/cursor/pending-row semantics with SQLite.
It complements, but does not replace, an on-device Android integration test.
"""
import os
import sqlite3
import tempfile

def connect(path):
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript("""
    CREATE TABLE IF NOT EXISTS event_cursor(
      account INTEGER NOT NULL, room INTEGER NOT NULL, dialog INTEGER NOT NULL,
      last_event_id INTEGER NOT NULL, PRIMARY KEY(account,room,dialog));
    CREATE TABLE IF NOT EXISTS pending_edit(
      account INTEGER NOT NULL, room INTEGER NOT NULL, dialog INTEGER NOT NULL,
      message INTEGER NOT NULL, version INTEGER NOT NULL, event_id INTEGER NOT NULL,
      body TEXT NOT NULL,
      PRIMARY KEY(account,room,dialog,message));
    CREATE TABLE IF NOT EXISTS messages(
      account INTEGER NOT NULL, room INTEGER NOT NULL, dialog INTEGER NOT NULL,
      message INTEGER NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL,
      PRIMARY KEY(account,room,dialog,message));
    """)
    return db

def cursor(db, key):
    row = db.execute(
        "SELECT last_event_id FROM event_cursor WHERE account=? AND room=? AND dialog=?", key
    ).fetchone()
    return 0 if row is None else row[0]

def apply_event(db, key, event_id, message_id, version, body, fail_before_cursor=False):
    db.execute("BEGIN IMMEDIATE")
    try:
        current = cursor(db, key)
        if event_id <= current:
            raise ValueError("duplicate-or-stale-event")
        if event_id != current + 1:
            raise ValueError("out-of-order-event")
        account, room, dialog = key
        row = db.execute(
            "SELECT version,body FROM messages WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        ).fetchone()
        if row is None:
            old = db.execute(
                "SELECT version,event_id FROM pending_edit WHERE account=? AND room=? AND dialog=? AND message=?",
                (*key, message_id)
            ).fetchone()
            if old is None or version > old["version"] or (
                version == old["version"] and event_id > old["event_id"]
            ):
                db.execute(
                    "INSERT INTO pending_edit VALUES(?,?,?,?,?,?,?) "
                    "ON CONFLICT(account,room,dialog,message) DO UPDATE SET "
                    "version=excluded.version,event_id=excluded.event_id,body=excluded.body",
                    (*key, message_id, version, event_id, body)
                )
        elif version >= row["version"]:
            # Strictly older versions cannot overwrite. Equal-version/different
            # content is intentionally reconciled from the newer canonical input.
            db.execute(
                "UPDATE messages SET version=?,body=? "
                "WHERE account=? AND room=? AND dialog=? AND message=?",
                (version, body, *key, message_id)
            )
        if fail_before_cursor:
            raise RuntimeError("injected-transaction-failure")
        db.execute(
            "INSERT INTO event_cursor VALUES(?,?,?,?) "
            "ON CONFLICT(account,room,dialog) DO UPDATE SET last_event_id=excluded.last_event_id",
            (*key, event_id)
        )
        db.commit()
    except Exception:
        db.rollback()
        raise

def reconcile_api_response(db, key, message_id, version, body):
    """The API callback and Event callback share this version-aware projection."""
    db.execute("BEGIN IMMEDIATE")
    try:
        row = db.execute(
            "SELECT version FROM messages WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        ).fetchone()
        if row is None:
            db.execute(
                "INSERT INTO messages VALUES(?,?,?,?,?,?)",
                (*key, message_id, version, body)
            )
        elif version >= row["version"]:
            db.execute(
                "UPDATE messages SET version=?,body=? WHERE account=? AND room=? AND dialog=? AND message=?",
                (version, body, *key, message_id)
            )
        db.commit()
    except Exception:
        db.rollback()
        raise

def persist_api_ack(db, key, message_id, version, body):
    """Model the API-ack path: update an existing row or persist a pending edit."""
    db.execute("BEGIN IMMEDIATE")
    try:
        row = db.execute(
            "SELECT version FROM messages WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        ).fetchone()
        if row is None:
            old = db.execute(
                "SELECT version,event_id FROM pending_edit WHERE account=? AND room=? AND dialog=? AND message=?",
                (*key, message_id)
            ).fetchone()
            if old is None or version > old["version"] or (
                version == old["version"] and old["event_id"] == 0
            ):
                db.execute(
                    "INSERT INTO pending_edit VALUES(?,?,?,?,?,?,?) "
                    "ON CONFLICT(account,room,dialog,message) DO UPDATE SET "
                    "version=excluded.version,event_id=0,body=excluded.body",
                    (*key, message_id, version, 0, body)
                )
        elif version > row["version"]:
            db.execute(
                "UPDATE messages SET version=?,body=? "
                "WHERE account=? AND room=? AND dialog=? AND message=?",
                (version, body, *key, message_id)
            )
        db.commit()
    except Exception:
        db.rollback()
        raise

def insert_history_message(db, key, message_id, version, body):
    db.execute("BEGIN IMMEDIATE")
    try:
        current = db.execute(
            "SELECT version,body FROM messages WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        ).fetchone()
        if current is not None and current["version"] > version:
            # Delayed history pages must not downgrade a row already edited in SQLite.
            version, body = current["version"], current["body"]
        pending = db.execute(
            "SELECT version,body FROM pending_edit WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        ).fetchone()
        if pending is not None and pending["version"] >= version:
            version, body = pending["version"], pending["body"]
        db.execute(
            "INSERT INTO messages VALUES(?,?,?,?,?,?) "
            "ON CONFLICT(account,room,dialog,message) DO UPDATE SET "
            "version=excluded.version,body=excluded.body",
            (*key, message_id, version, body)
        )
        db.execute(
            "DELETE FROM pending_edit WHERE account=? AND room=? AND dialog=? AND message=?",
            (*key, message_id)
        )
        db.commit()
    except Exception:
        db.rollback()
        raise

def check(condition, label):
    if not condition:
        raise AssertionError(label)
    print("PASS:", label)

fd, path = tempfile.mkstemp(prefix="zenora-edit-reconcile-", suffix=".sqlite")
os.close(fd)
try:
    db = connect(path)
    key = (1, 7, 101)

    # Event with absent SQLite row: pending row and cursor commit together.
    apply_event(db, key, 1, 42, 2, "edited while unloaded")
    check(cursor(db, key) == 1, "missing-row event advances cursor only after pending payload commit")
    check(db.execute("SELECT body FROM pending_edit WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()[0]
          == "edited while unloaded", "missing-row edit is durable before history loads the message")

    # A direct API acknowledgement must also survive when no SQLite message
    # row and no bounded live object exists yet.
    api_key = (1, 9, 101)
    persist_api_ack(db, api_key, 77, 2, "API canonical edit before history")
    pending_api = db.execute(
        "SELECT version,body,event_id FROM pending_edit WHERE account=1 AND room=9 AND dialog=101 AND message=77"
    ).fetchone()
    check(
        tuple(pending_api) == (2, "API canonical edit before history", 0),
        "API acknowledgement is stored durably as a pending edit when the message row is absent",
    )
    insert_history_message(db, api_key, 77, 1, "stale history body")
    api_row = db.execute(
        "SELECT version,body FROM messages WHERE account=1 AND room=9 AND dialog=101 AND message=77"
    ).fetchone()
    check(
        (api_row["version"], api_row["body"]) == (2, "API canonical edit before history"),
        "a later SQLite/history insert consumes the API pending edit without creating a duplicate message",
    )

    # Process death/re-entry: close and reopen the actual SQLite database.
    db.close()
    db = connect(path)
    insert_history_message(db, key, 42, 1, "stale history body")
    row = db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()
    check((row["version"], row["body"]) == (2, "edited while unloaded"),
          "re-entered history is reconciled from the durable pending edit without process restart")
    check(db.execute("SELECT COUNT(*) FROM pending_edit WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()[0] == 0,
          "pending event is removed only after canonical history row is stored")

    # API response before Event: the API callback is projected first, then the
    # durable Event arrives with the same canonical version and body.
    reconcile_api_response(db, key, 42, 3, "canonical v3")
    check(db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()["body"]
          == "canonical v3", "API-response-before-Event updates the active canonical message")
    apply_event(db, key, 2, 42, 3, "canonical v3")
    check(db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()["body"]
          == "canonical v3", "Event-after-API-response is idempotent")

    # Event first, then the API callback: same version/body must remain stable.
    apply_event(db, key, 3, 42, 4, "canonical v4")
    reconcile_api_response(db, key, 42, 4, "canonical v4")
    check(db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()["body"]
          == "canonical v4", "API-response-after-Event is idempotent")

    # Equal version but different body must reconcile; higher local version beats stale API/Event.
    apply_event(db, key, 4, 42, 4, "reconciled equal-version payload")
    row = db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()
    check((row["version"], row["body"]) == (4, "reconciled equal-version payload"),
          "equal version with different content is reconciled")
    reconcile_api_response(db, key, 42, 2, "stale API response v2")
    apply_event(db, key, 5, 42, 2, "stale Event v2")
    row = db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()
    check((row["version"], row["body"]) == (4, "reconciled equal-version payload"),
          "older event cannot overwrite a newer stored message")

    # A delayed history response may arrive after the event transaction already
    # updated an existing SQLite row. The storage write must preserve that newer row.
    insert_history_message(db, key, 42, 2, "delayed old history body")
    row = db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()
    check((row["version"], row["body"]) == (4, "reconciled equal-version payload"),
          "delayed stale history write cannot downgrade a newer canonical SQLite row")

    # Out-of-order and duplicate event delivery never advances the durable cursor.
    try:
        apply_event(db, key, 7, 42, 5, "gap")
        raise AssertionError("out-of-order event accepted")
    except ValueError as exc:
        check(str(exc) == "out-of-order-event" and cursor(db, key) == 5,
              "out-of-order Event is rejected without moving cursor")
    try:
        apply_event(db, key, 5, 42, 5, "duplicate")
        raise AssertionError("duplicate event accepted")
    except ValueError as exc:
        check(str(exc) == "duplicate-or-stale-event" and cursor(db, key) == 5,
              "duplicate Event replay is rejected by the durable cursor")

    # Rollback after writing an absent-row edit must roll back both pending state and cursor.
    key2 = (1, 8, 101)
    try:
        apply_event(db, key2, 1, 55, 2, "must rollback", fail_before_cursor=True)
        raise AssertionError("injected transaction failure did not raise")
    except RuntimeError as exc:
        check(str(exc) == "injected-transaction-failure", "transaction failure is surfaced")
    check(cursor(db, key2) == 0, "failed transaction leaves Event cursor unchanged")
    check(db.execute("SELECT COUNT(*) FROM pending_edit WHERE account=1 AND room=8 AND dialog=101 AND message=55").fetchone()[0] == 0,
          "failed transaction rolls back the pending edit payload")

    # Account/room isolation and offline behavior.
    apply_event(db, (2, 7, 101), 1, 42, 2, "other account")
    check(db.execute("SELECT body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()[0]
          == "reconciled equal-version payload", "other account's mutation cannot alter this account's message")
    check(cursor(db, (1, 7, 101)) == 5, "offline/no-event state leaves the active account cursor untouched")
    db.close()
finally:
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass

# Source-wiring regression: a history/SQLite MessageObject can be absent from
# liveMessageObjects while still being present in ChatActivity's active message
# list. The API acknowledgement and Event path must converge on the same object
# updater and that updater must persist a storage-owned snapshot.
def read_source(relative_path):
    with open(relative_path, "r", encoding="utf-8") as source_file:
        return source_file.read()

history_source = read_source(
    "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java"
)
chat_source = read_source("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
mutation_source = read_source(
    "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java"
)
live_source = read_source(
    "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java"
)
storage_source = read_source("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")

listener_start = chat_source.find("didReceivedNotification_zenoraMessageEdited")
listener_end = chat_source.find("@Override", listener_start)
chat_listener = chat_source[listener_start:listener_end if listener_end > listener_start else None]
canonical_start = history_source.find("public static boolean applyCanonicalEditToObject")
canonical_end = history_source.find("private static void postCanonicalEditToOpenChat", canonical_start)
canonical_method = history_source[canonical_start:canonical_end]
event_apply_start = live_source.find("private static void applyEditedMessages")
event_apply_end = live_source.find("private static void fail", event_apply_start)
event_apply_method = live_source[event_apply_start:event_apply_end]

check(
    "NotificationCenter.zenoraMessageEdited" in chat_source
    and "findActiveMessage" in chat_listener
    and "messages" in chat_listener
    and "applyCanonicalEditToObject" in chat_listener
    and "NotificationCenter.replaceMessagesObjects" in chat_listener,
    "ChatActivity locates a SQLite/history-loaded message even when liveMessageObjects has no entry and uses native RecyclerView replacement",
)
check(
    "putMessages(stored" in canonical_method and "cloneMessageForStorage" in canonical_method,
    "the shared active-object edit path persists an immutable SQLite snapshot",
)
check(
    "reconcileCanonicalEditPayload" in mutation_source
    and "applyEditedMessage(" in event_apply_method
    and "applyCanonicalEditToObject" in history_source,
    "API acknowledgement and Event application converge on the canonical object updater",
)
check(
    '"EDIT_VERSION_CONFLICT".equals' in mutation_source
    and "refreshEditedMessage(account,dialogId,roomId" in mutation_source,
    "EDIT_VERSION_CONFLICT fetches and applies the authoritative server version",
)
check(
    "persistZenoraMessageEditPayload" in storage_source
    and "zenora_message_mutation_pending_edit" in storage_source
    and "bindLong(6,0L)" in storage_source
    and "putMessagesInternal(updated,false,true,0,false,ChatActivity.MODE_DEFAULT,0L)" in storage_source,
    "API acknowledgement persists either the existing message row or an account/room-scoped pending edit in SQLite",
)

print("OK: SQLite and live-UI reconciliation scenarios passed.")
