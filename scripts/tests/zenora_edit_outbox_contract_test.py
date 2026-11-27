#!/usr/bin/env python3
"""Source-contract guards for optimistic, restart-safe Zenora message edits."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def source(path):
    return (ROOT / path).read_text()

storage = source("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration = source("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
outbox = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraEditOutboxController.java")
api = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
history = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
live = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java")
chat = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraChatController.java")
composer = source("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
workflow = source(".github/workflows/build-debug.yml")

store = storage.split("public void storeZenoraMessageEdit(", 1)[1].split(
    "public void loadZenoraEditOutbox(", 1)[0]
assert "storageQueue.postRunnable" in store
assert "database.beginTransaction()" in store and "database.commitTransaction()" in store
assert "database.rollbackTransaction()" in store
assert "putMessagesInternal(messages" in store
assert "INSERT INTO zenora_edit_outbox" in store
assert "original_entities" in store and "new_entities" in store
assert "DELETE FROM zenora_edit_outbox WHERE room_id" in store

load = storage.split("public void loadZenoraEditOutbox(", 1)[1].split(
    "public void updateZenoraEditOutboxState(", 1)[0]
assert "ORDER BY room_id ASC,created_at ASC,message_id ASC" in load
assert "operation_id" in load and "expected_edit_version" in load

ack = storage.split("public void acknowledgeZenoraMessageEdit(", 1)[1].split(
    "public static final class ZenoraHistoryState", 1)[0]
assert "operation_id=?" in ack and "expected_edit_version" in ack
assert "database.beginTransaction()" in ack and "database.commitTransaction()" in ack

# SQLite v188 migration, fresh databases, and disaster-recovery table allowlist.
assert "LAST_DB_VERSION = 188" in storage
assert "CREATE TABLE zenora_edit_outbox" in storage
assert '"zenora_edit_outbox"' in storage
assert "if (version == 187)" in migration
assert "PRAGMA user_version = 188" in migration
assert "if (version < 188) excludeTables.add(\"zenora_edit_outbox\")" in migration
assert "version != 187" in migration

# The composer uses the durable path and clears only after local persistence.
submit_ui = composer.split("private boolean submitZenoraTextEdit(", 1)[1].split(
    "private boolean processZenoraTextMessage(", 1)[0]
assert "ZenoraEditOutboxController.submit(" in submit_ui
assert "setEditingMessageObject(null, null, false)" in submit_ui
assert "could not be saved locally" in submit_ui
assert "ZenoraMessageMutationController.editMessage(" not in submit_ui

# Optimistic text is committed before the request starts; a stable operation ID is reused.
assert "message.message = newBody;" in outbox
assert "storeZenoraMessageEdit(" in outbox
assert outbox.index("storeZenoraMessageEdit(") < outbox.index("start(pending);")
assert "pending.operationId" in outbox
assert "loadZenoraEditOutbox(" in outbox
assert "restorePendingEdits(" in outbox
assert "ZenoraOutboxPolicy.UNKNOWN" in outbox
assert "ZenoraOutboxPolicy.nextAttemptAt(" in outbox
assert "STATUS_CONFLICT" in outbox and "STATUS_FAILED" in outbox
assert "expectedVersion + 1L" in outbox
assert "reconcileMutationEvent(" in outbox
assert "reconcileHistoryMessage(" in outbox

# Stale history must consult the outgoing durable edit row before serialization.
pending_apply = storage.split("private boolean applyPendingZenoraMessageEdit(", 1)[1].split(
    "private void deletePendingZenoraMessageEdit(", 1)[0]
assert "FROM zenora_edit_outbox" in pending_apply
assert "new_body" in pending_apply and "expected_edit_version" in pending_apply
assert "message.zenoraEditVersion <= expectedVersion" in pending_apply

# API retries preserve the same operation_id; sender is checked against the initiating user.
assert "postMessageEditWithRetry(" in api
assert "same operation_id" in api
assert 'operation_id=' in api and '&operation_id=' in api
assert 'message.optLong("sender_id", 0L) != expectedUserId' in api
assert "executeMessageMutation(\"message_edit.php\"" in api

# The current Live Sync and initial/live history response can acknowledge the edit.
assert "ZenoraEditOutboxController.reconcileMutationEvent(" in live
assert "ZenoraEditOutboxController.reconcileHistoryMessage(" in history
assert "ZenoraEditOutboxController.restorePendingEdits(" in chat

# The dedicated branch must produce and validate ARM64 artifacts in the release workflow.
branch = "feat/zenora-message-edit-durable-outbox-android-20261128"
tag = "zenora-message-edit-durable-outbox-android-20261128"
assert branch in workflow and tag in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
assert "EXPECTED_ABIS=\"$ZENORA_ABIS\" bash scripts/validate-apk.sh debug" in workflow
assert "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow

print("PASS: Zenora durable optimistic edit Outbox source contract")
