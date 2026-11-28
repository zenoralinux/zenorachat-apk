#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def read(p): return (ROOT/p).read_text()
storage=read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration=read("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
outbox=read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraEditOutboxController.java")
api=read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
composer=read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
history=read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
live=read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java")
chat=read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraChatController.java")
workflow=read(".github/workflows/build-debug.yml")
assert "LAST_DB_VERSION = 188" in storage
assert "CREATE TABLE zenora_edit_outbox" in storage and "zenora_edit_outbox" in storage
assert "if (version == 187)" in migration and "PRAGMA user_version = 188" in migration
assert "if (version < 188) excludeTables.add" in migration
store=storage.split("public void storeZenoraMessageEdit(",1)[1].split("public void loadZenoraEditOutbox(",1)[0]
assert "storageQueue.postRunnable" in store and "beginTransaction()" in store and "commitTransaction()" in store and "rollbackTransaction()" in store
assert "putMessagesInternal" in store and "INSERT OR REPLACE INTO zenora_edit_outbox" in store
assert "original_entities" in store and "new_entities" in store
assert "loadZenoraEditOutbox(" in storage and "updateZenoraEditOutboxState(" in storage
assert "acknowledgeZenoraMessageEdit(" in storage
assert "applyPendingZenoraOutgoingEdit(" in storage
assert "message.message = newBody" in outbox and "storeZenoraMessageEdit(" in outbox
assert outbox.index("storeZenoraMessageEdit(")<outbox.index("start(pending);")
assert "UUID.randomUUID()" in outbox and "pending.operationId" in outbox
assert "restorePendingEdits(" in outbox and "ZenoraOutboxPolicy.UNKNOWN" in outbox
assert "nextAttemptAt(" in outbox and "STATUS_CONFLICT" in outbox and "STATUS_FAILED" in outbox
assert "ZenoraEditOutboxController.submit(" in composer
editUi=composer.split("private boolean submitZenoraTextEdit(",1)[1].split("private boolean processZenoraTextMessage(",1)[0]
assert "setEditingMessageObject(null, null, false)" in editUi
assert "ZenoraMessageMutationController.editMessage(" not in editUi
assert "restorePendingEdits(" in chat
assert "reconcileMutationEvent(" in live and "reconcileHistoryMessage(" in history
assert "postMessageEditWithRetry(" in api and "operation_id=" in api
assert 'm.optLong("sender_id",0)!=expectedUserId' in api
assert "zenora-message-edit-durable-outbox-android-20261128" in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
print("PASS: durable optimistic edit Outbox contract")
