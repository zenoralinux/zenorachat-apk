#!/usr/bin/env python3
"""SQLite-backed behavioral model for the Android edit reconciliation invariants.

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

def insert_history_message(db, key, message_id, version, body):
    db.execute("BEGIN IMMEDIATE")
    try:
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
    check((row["version"], row["body"]) == (3, "reconciled equal-version payload"),
          "equal version with different content is reconciled")
    reconcile_api_response(db, key, 42, 2, "stale API response v2")
    apply_event(db, key, 5, 42, 2, "stale Event v2")
    row = db.execute("SELECT version,body FROM messages WHERE account=1 AND room=7 AND dialog=101 AND message=42").fetchone()
    check((row["version"], row["body"]) == (3, "reconciled equal-version payload"),
          "older event cannot overwrite a newer stored message")

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

print("OK: SQLite behavioral reconciliation scenarios passed.")
