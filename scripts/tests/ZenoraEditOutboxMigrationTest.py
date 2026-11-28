#!/usr/bin/env python3
import re,sqlite3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
storage=(ROOT/"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()
migration=(ROOT/"TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
def capture(p,s,n):
 m=re.search(p,s)
 if not m: raise AssertionError("missing "+n)
 return m.group(1)
fresh=capture(r'database\.executeFast\("(CREATE TABLE zenora_edit_outbox[^"]+)"\)',storage,"fresh table")
fresh_idx=capture(r'database\.executeFast\("(CREATE UNIQUE INDEX zenora_edit_outbox_message_idx[^"]+)"\)',storage,"fresh unique index")
upgrade=capture(r'database\.executeFast\("(CREATE TABLE IF NOT EXISTS zenora_edit_outbox[^"]+)"\)',migration,"migration table")
upgrade_idx=capture(r'database\.executeFast\("(CREATE UNIQUE INDEX IF NOT EXISTS zenora_edit_outbox_message_idx[^"]+)"\)',migration,"migration index")
assert "LAST_DB_VERSION = 188" in storage and "version == 187" in migration and "user_version = 188" in migration
assert 'if (version < 188) excludeTables.add("zenora_edit_outbox")' in migration
def check(db,table,index):
 db.execute(table);db.execute(index)
 cols={r[1] for r in db.execute("PRAGMA table_info(zenora_edit_outbox)")}
 assert {"operation_id","room_id","dialog_id","message_id","zenora_user_id","expected_edit_version","original_body","original_entities","new_body","new_entities","status","created_at","next_attempt_at","attempt_count"}<=cols
db=sqlite3.connect(":memory:")
db.execute("CREATE TABLE prior_messages(id INTEGER PRIMARY KEY,text TEXT)")
db.execute("INSERT INTO prior_messages VALUES(1,'existing row')")
db.execute("PRAGMA user_version=187")
db.execute(upgrade);db.execute(upgrade_idx);db.execute("PRAGMA user_version=188")
assert db.execute("PRAGMA user_version").fetchone()[0]==188
assert db.execute("SELECT text FROM prior_messages").fetchone()[0]=="existing row"
check(db,fresh,fresh_idx)
row=("op-id-0001",50,1001,12,1001,3,"old text",b"old ents","new text",b"new ents","UNKNOWN",10,20,2)
db.execute("INSERT INTO zenora_edit_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",row);db.commit()
saved=db.execute("SELECT operation_id,expected_edit_version,original_body,new_body,status,next_attempt_at,attempt_count FROM zenora_edit_outbox").fetchone()
assert saved==("op-id-0001",3,"old text","new text","UNKNOWN",20,2)
try:
 db.execute("INSERT INTO zenora_edit_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",("op-id-0002",50,1001,12,1001,4,"new text",b"","third",b"","SENDING",11,0,0))
 raise AssertionError("same message accepted two pending edits")
except sqlite3.IntegrityError: pass
print("PASS: SQLite edit Outbox schema v188 and uniqueness/durability")
