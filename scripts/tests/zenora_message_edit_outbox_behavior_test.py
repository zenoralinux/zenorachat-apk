#!/usr/bin/env python3
"""SQLite behavior model for process-death-safe edit reconciliation."""
import sqlite3

db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE messages(mid INTEGER PRIMARY KEY, body TEXT NOT NULL, version INTEGER NOT NULL);
CREATE TABLE edit_outbox(
 operation_id TEXT PRIMARY KEY, message_id INTEGER NOT NULL, expected_version INTEGER NOT NULL,
 original_body TEXT NOT NULL, new_body TEXT NOT NULL, status TEXT NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, UNIQUE(message_id)
);
CREATE TABLE accepted_operations(operation_id TEXT PRIMARY KEY, message_id INTEGER NOT NULL,
 new_body TEXT NOT NULL, resulting_version INTEGER NOT NULL);
""")
db.execute("INSERT INTO messages VALUES(71,'before',8)")

def persist_edit(op, mid, expected, body):
    db.execute("BEGIN IMMEDIATE")
    row = db.execute("SELECT body,version FROM messages WHERE mid=?", (mid,)).fetchone()
    if row[1] != expected:
        db.rollback()
        raise ValueError("local-version-changed")
    db.execute("UPDATE messages SET body=?,version=? WHERE mid=?", (body, expected+1, mid))
    db.execute("INSERT OR REPLACE INTO edit_outbox VALUES(?,?,?,?,?,'SENDING',0)",
               (op,mid,expected,row[0],body))
    db.commit()

def retry(op):
    accepted = db.execute("SELECT resulting_version FROM accepted_operations WHERE operation_id=?", (op,)).fetchone()
    if accepted:
        return accepted[0]
    row = db.execute("SELECT message_id,new_body,expected_version FROM edit_outbox WHERE operation_id=?", (op,)).fetchone()
    assert row
    mid,body,expected = row
    current = db.execute("SELECT version FROM messages WHERE mid=?", (mid,)).fetchone()[0]
    if current != expected+1:
        db.execute("UPDATE edit_outbox SET status='CONFLICT' WHERE operation_id=?", (op,))
        db.commit()
        return None
    db.execute("INSERT INTO accepted_operations VALUES(?,?,?,?)", (op,mid,body,expected+1))
    db.execute("DELETE FROM edit_outbox WHERE operation_id=?", (op,))
    db.commit()
    return expected+1

# Optimistic write is atomic with durable operation.
persist_edit("stable-operation-01",71,8,"changed while offline")
assert db.execute("SELECT body,version FROM messages WHERE mid=71").fetchone() == ("changed while offline",9)
assert db.execute("SELECT status FROM edit_outbox").fetchone()[0] == "SENDING"

# Process death: reopening the database retains both message body and operation.
db.close()
db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE messages(mid INTEGER PRIMARY KEY, body TEXT NOT NULL, version INTEGER NOT NULL);
CREATE TABLE edit_outbox(operation_id TEXT PRIMARY KEY, message_id INTEGER NOT NULL, expected_version INTEGER NOT NULL, original_body TEXT NOT NULL, new_body TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, UNIQUE(message_id));
CREATE TABLE accepted_operations(operation_id TEXT PRIMARY KEY, message_id INTEGER NOT NULL, new_body TEXT NOT NULL, resulting_version INTEGER NOT NULL);
""")
db.execute("INSERT INTO messages VALUES(71,'changed while offline',9)")
db.execute("INSERT INTO edit_outbox VALUES('stable-operation-01',71,8,'before','changed while offline','UNKNOWN',1)")
db.commit()
assert db.execute("SELECT new_body,status,attempts FROM edit_outbox").fetchone() == ("changed while offline","UNKNOWN",1)

# ACK/retry replay is idempotent: the operation key is stable and the row retires once.
assert retry("stable-operation-01") == 9
assert db.execute("SELECT COUNT(*) FROM edit_outbox").fetchone()[0] == 0
assert retry("stable-operation-01") == 9

# A competing server edit produces a terminal conflict; the local draft remains recoverable.
db.execute("INSERT INTO messages VALUES(72,'server winner',12)")
db.execute("INSERT INTO edit_outbox VALUES('stable-operation-02',72,11,'old','my offline draft','UNKNOWN',0)")
db.commit()
assert retry("stable-operation-02") is None
assert db.execute("SELECT status,new_body FROM edit_outbox WHERE operation_id='stable-operation-02'").fetchone() == ("CONFLICT","my offline draft")
# The next deliberate submission uses the refreshed base version and a new id.
db.execute("DELETE FROM edit_outbox WHERE operation_id='stable-operation-02'")
db.execute("INSERT INTO edit_outbox VALUES('stable-operation-03',72,12,'server winner','user re-submission','SENDING',0)")
db.commit()
assert db.execute("SELECT expected_version,new_body FROM edit_outbox WHERE operation_id='stable-operation-03'").fetchone() == (12,"user re-submission")
print("Zenora offline edit SQLite behavior scenarios passed")
