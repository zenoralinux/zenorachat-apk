#!/usr/bin/env python3
"""Static contract for local-first Zenora message edits and durable retry."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def read(path):
    return (ROOT / path).read_text()

storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration = read("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
queue = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraEditOutboxController.java")
mutation = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java")
ui = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
chat = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraChatController.java")
history = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")

store = storage.split("public void storeZenoraMessageEdit(", 1)[1].split("public void loadZenoraEditOutbox(", 1)[0]
assert "database.beginTransaction()" in store
assert "putMessagesInternal(messages" in store
assert "INSERT OR REPLACE INTO zenora_edit_outbox" in store
assert store.index("putMessagesInternal(messages") < store.index("INSERT OR REPLACE INTO zenora_edit_outbox") < store.index("database.commitTransaction()")
assert "rollbackTransaction()" in store
assert "completeZenoraMessageEdit(" in storage and "DELETE FROM zenora_edit_outbox" in storage

assert "LAST_DB_VERSION = 188" in storage
assert "if (version == 187)" in migration and "PRAGMA user_version = 188" in migration
assert "zenora_edit_outbox" in migration and 'excludeTables.add("zenora_edit_outbox")' in migration

assert "message.message = body" in queue
assert "message.zenoraEditVersion = optimisticVersion" in queue
assert "storeZenoraMessageEdit(" in queue
assert "createOperationId(" in queue and "pending.operationId" in queue
assert "ZenoraOutboxPolicy.nextAttemptAt" in queue and '"UNKNOWN"' in queue
assert 'markTerminal(pending, "CONFLICT"' in queue
assert 'updateZenoraEditOutboxState(record.operationId, "UNKNOWN"' in queue
assert "loadZenoraEditOutbox(" in queue
assert "completeZenoraMessageEdit(" in queue
assert "reconcileCanonicalEditPayload(" in queue

assert "ZenoraEditOutboxController.enqueue(" in mutation
assert "inFlightEdits.add(key)" in mutation
assert "ZenoraEditOutboxController.restorePendingMessageEdits(instance.getCurrentAccount())" in chat
assert "ZenoraEditOutboxController.clearAccount(account)" in chat
assert "ZenoraMessageMutationController.editMessage(" in ui
assert "Edit saved on this device" in ui
assert "refreshEditedMessage(" in ui
assert "applyEditedMessage(" in history
assert "public static void editMessage(long roomId, long messageId, String body," in api
assert '"message_edit.php"' in api and '"EDIT_VERSION_CONFLICT"' in queue
print("Zenora optimistic/offline edit Outbox source contract passed")
