#!/usr/bin/env python3
"""SQLite behavioral regression model for the durable optimistic edit queue."""
import hashlib
import json
import os
import sqlite3
import tempfile

def connect(path):
    db = sqlite3.connect(path, timeout=5)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript("""
      CREATE TABLE IF NOT EXISTS server_messages(
        room_id INTEGER NOT NULL, message_id INTEGER NOT NULL,
        body TEXT NOT NULL, edit_version INTEGER NOT NULL,
        PRIMARY KEY(room_id,message_id));
      CREATE TABLE IF NOT EXISTS edit_operations(
        operation_id TEXT PRIMARY KEY, account INTEGER NOT NULL, user_id INTEGER NOT NULL,
        room_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL,
        sequence INTEGER NOT NULL, expected_version INTEGER NOT NULL, body TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,
        state TEXT NOT NULL DEFAULT 'PENDING', created_at INTEGER NOT NULL,
        UNIQUE(account,user_id,room_id,dialog_id,message_id,sequence));
      CREATE TABLE IF NOT EXISTS server_operation_ledger(
        operation_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, room_id INTEGER NOT NULL,
        message_id INTEGER NOT NULL, expected_version INTEGER NOT NULL,
        body_hash TEXT NOT NULL, response_json TEXT NOT NULL);
    """)
    return db

def enqueue(db, op_id, account, user, room, dialog, message, requested_version, body, now):
    db.execute("BEGIN IMMEDIATE")
    try:
        latest = db.execute(
            "SELECT sequence,expected_version FROM edit_operations "
            "WHERE account=? AND user_id=? AND room_id=? AND dialog_id=? AND message_id=? "
            "ORDER BY sequence DESC LIMIT 1",
            (account,user,room,dialog,message)
        ).fetchone()
        sequence = 1 if latest is None else latest["sequence"] + 1
        expected = requested_version if latest is None else max(requested_version, latest["expected_version"] + 1)
        db.execute(
            "INSERT INTO edit_operations(operation_id,account,user_id,room_id,dialog_id,message_id,"
            "sequence,expected_version,body,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (op_id,account,user,room,dialog,message,sequence,expected,body,now)
        )
        db.commit()
        return sequence, expected
    except Exception:
        db.rollback()
        raise

def first_pending(db, account, user):
    return db.execute(
        "SELECT * FROM edit_operations WHERE account=? AND user_id=? "
        "ORDER BY created_at ASC,rowid ASC LIMIT 1", (account,user)
    ).fetchone()

def server_edit(db, user, op_id, room, message, expected, body):
    digest = hashlib.sha256(body.encode()).hexdigest()
    db.execute("BEGIN IMMEDIATE")
    try:
        old = db.execute("SELECT * FROM server_operation_ledger WHERE operation_id=?", (op_id,)).fetchone()
        if old is not None:
            if (old["user_id"],old["room_id"],old["message_id"],old["expected_version"],old["body_hash"]) != (
                user,room,message,expected,digest):
                raise ValueError("OPERATION_ID_REUSED")
            response = json.loads(old["response_json"])
            db.commit()
            return response
        row = db.execute("SELECT * FROM server_messages WHERE room_id=? AND message_id=?", (room,message)).fetchone()
        if row is None:
            raise ValueError("NOT_FOUND")
        if row["edit_version"] != expected:
            raise ValueError("EDIT_VERSION_CONFLICT")
        version = expected + 1
        db.execute("UPDATE server_messages SET body=?,edit_version=? WHERE room_id=? AND message_id=?",
                   (body,version,room,message))
        response = {"message_id":message,"room_id":room,"text":body,"edit_version":version}
        db.execute("INSERT INTO server_operation_ledger VALUES(?,?,?,?,?,?,?)",
                   (op_id,user,room,message,expected,digest,json.dumps(response)))
        db.commit()
        return response
    except Exception:
        db.rollback()
        raise

def rebase(db, account, user, room, dialog, message, server_version):
    db.execute("BEGIN IMMEDIATE")
    try:
        rows = db.execute(
            "SELECT operation_id FROM edit_operations WHERE account=? AND user_id=? AND room_id=? "
            "AND dialog_id=? AND message_id=? ORDER BY sequence ASC",
            (account,user,room,dialog,message)
        ).fetchall()
        expected = server_version
        for row in rows:
            db.execute("UPDATE edit_operations SET expected_version=?,attempts=0,next_attempt_at=0,state='PENDING' "
                       "WHERE operation_id=?", (expected,row["operation_id"]))
            expected += 1
        db.commit()
    except Exception:
        db.rollback()
        raise

def project_body(db, account, user, room, dialog, message, server_body, server_version):
    latest = db.execute(
        "SELECT body,expected_version FROM edit_operations WHERE account=? AND user_id=? AND room_id=? "
        "AND dialog_id=? AND message_id=? ORDER BY sequence DESC LIMIT 1",
        (account,user,room,dialog,message)
    ).fetchone()
    if latest is not None and latest["expected_version"] + 1 >= server_version:
        return latest["body"], latest["expected_version"] + 1
    return server_body, server_version

def backoff(attempt):
    return min(300_000, 1_000 << min(8,max(0,attempt-1)))

def check(condition, label):
    if not condition:
        raise AssertionError(label)
    print("PASS:", label)

fd,path = tempfile.mkstemp(prefix="zenora-edit-", suffix=".sqlite")
os.close(fd)
try:
    db = connect(path)
    db.execute("INSERT INTO server_messages VALUES(7,42,'original',1)")
    db.commit()
    key = (1,101,7,7001,42)
    s1,v1 = enqueue(db,"edit-operation-0001",*key,1,"first offline edit",100)
    s2,v2 = enqueue(db,"edit-operation-0002",*key,v1+1,"second offline edit",101)
    check((s1,v1,s2,v2)==(1,1,2,2),"sequential edits receive stable FIFO order and expected versions")
    db.execute("UPDATE edit_operations SET state='SENDING' WHERE operation_id='edit-operation-0001'")
    db.close()

    # Process restart reopens the database, resets interrupted sends, and restores latest local text.
    db = connect(path)
    db.execute("UPDATE edit_operations SET state='PENDING',next_attempt_at=0 WHERE state='SENDING'")
    db.commit()
    latest = db.execute("SELECT body FROM edit_operations WHERE account=1 AND user_id=101 "
                         "AND room_id=7 AND dialog_id=7001 AND message_id=42 ORDER BY sequence DESC LIMIT 1").fetchone()
    check(latest["body"]=="second offline edit","process restart restores latest optimistic text from SQLite")
    check(first_pending(db,1,101)["operation_id"]=="edit-operation-0001",
          "interrupted operation resumes first with its original operation ID")

    # A lost acknowledgement is safe: operation ID replay returns the stored response.
    ack1 = server_edit(db,101,"edit-operation-0001",7,42,1,"first offline edit")
    retry1 = server_edit(db,101,"edit-operation-0001",7,42,1,"first offline edit")
    check(ack1==retry1 and db.execute("SELECT edit_version FROM server_messages WHERE room_id=7 AND message_id=42").fetchone()[0]==2,
          "server idempotency prevents duplicate mutation after a lost HTTP response")
    db.execute("DELETE FROM edit_operations WHERE operation_id='edit-operation-0001'")
    db.commit()
    check(first_pending(db,1,101)["operation_id"]=="edit-operation-0002",
          "successful acknowledgement removes only the completed head operation")
    ack2 = server_edit(db,101,"edit-operation-0002",7,42,2,"second offline edit")
    check(ack2["edit_version"]==3,"second queued edit executes only after the first acknowledgement")
    db.execute("DELETE FROM edit_operations WHERE operation_id='edit-operation-0002'")
    db.commit()

    # External device edit creates a conflict. The same operation key can be rebased
    # because the server rolled its provisional idempotency row back with the conflict.
    s3,v3 = enqueue(db,"edit-operation-0003",*key,3,"third user edit",102)
    db.execute("UPDATE server_messages SET body='remote device edit',edit_version=4 WHERE room_id=7 AND message_id=42")
    db.commit()
    try:
        server_edit(db,101,"edit-operation-0003",7,42,3,"third user edit")
        raise AssertionError("stale edit version accepted")
    except ValueError as error:
        check(str(error)=="EDIT_VERSION_CONFLICT","stale expected version reports a conflict")
    check(db.execute("SELECT COUNT(*) FROM server_operation_ledger WHERE operation_id='edit-operation-0003'").fetchone()[0]==0,
          "conflict does not consume the operation ID")
    rebase(db,1,101,7,7001,42,4)
    rebased=db.execute("SELECT expected_version FROM edit_operations WHERE operation_id='edit-operation-0003'").fetchone()[0]
    check(rebased==4,"conflict rebases queued edit against canonical server version")
    ack3=server_edit(db,101,"edit-operation-0003",7,42,4,"third user edit")
    check(ack3["edit_version"]==5,"rebased operation retries with the same stable key")
    db.execute("DELETE FROM edit_operations WHERE operation_id='edit-operation-0003'")
    db.commit()

    # A late ACK/history page may not overwrite a newer optimistic local edit.
    s4,v4=enqueue(db,"edit-operation-0004",*key,5,"fourth latest edit",103)
    projected=project_body(db,1,101,7,7001,42,"stale history",5)
    check(projected==("fourth latest edit",6),"stale History or HTTP response cannot replace the latest pending body")

    # Account isolation: another user cannot project or drain the first user's work.
    other=first_pending(db,2,202)
    check(other is None,"outbox rows are isolated by authenticated user and app account")
    s5,v5=enqueue(db,"other-user-operation",2,202,7,7002,42,5,"other user text",104)
    check(first_pending(db,1,101)["operation_id"]=="edit-operation-0004",
          "account switch leaves original user's queued operation untouched")

    check([backoff(i) for i in range(1,7)]==[1000,2000,4000,8000,16000,32000],
          "retry backoff grows exponentially")
    check(backoff(20)==256000,"retry backoff stops increasing at its bounded maximum")
    db.close()
finally:
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass

print("OK: durable optimistic edit SQLite behavior tests passed.")
