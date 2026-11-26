#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def source(path):
    return (ROOT / path).read_text()

storage = source("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration = source("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
history = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
api = source("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
sender = source("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
workflow = source(".github/workflows/build-debug.yml")

# Crash boundary: one storageQueue transaction writes both the local TL message and its outbox key.
assert "public void storeZenoraTextMessage(" in storage
store = storage.split("public void storeZenoraTextMessage(", 1)[1].split("private void updateZenoraReplyPreviewInternal", 1)[0]
assert "storageQueue.postRunnable" in store
assert "database.beginTransaction()" in store
assert "putMessagesInternal(messages, false, false" in store
assert "INSERT OR REPLACE INTO zenora_outbox" in store
assert "reply_to_id" in store and "reply_preview_body" in store
assert "updateZenoraReplyPreviewInternal(message)" in store
assert "database.commitTransaction()" in store
assert "rollbackTransaction()" in store
assert '"zenora_outbox"' in storage

# Upgrade and backup/restore contract.
assert "if (version == 181)" in migration and "PRAGMA user_version = 182" in migration
assert "if (version == 183)" in migration and "PRAGMA user_version = 184" in migration
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_to_id" in migration
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_preview_sender_id" in migration
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_preview_body" in migration
assert "INSERT OR IGNORE INTO zenora_outbox(" in migration and "FROM old.zenora_outbox" in migration
assert '"zenora_outbox"' in storage.split("DATABASE_TABLES", 1)[1].split("};", 1)[0]

# Durable rehydration, per-room FIFO and stable keys.
assert "restorePendingTextSends(int account)" in history
assert "record.zenoraUserId != currentUserId" in history
assert "enqueuePendingTextSend(restoredPending)" in history
assert "record.replyToId" in history
assert "Collections.sort(ordered" in history
assert "pending.clientMessageId, pending.replyToId, pending.zenoraUserId" in history
assert "ZenoraOutboxPolicy.UNKNOWN" in history
assert "ZenoraOutboxPolicy.FAILED" in history
assert "AtomicBoolean(false)" in history and "compareAndSet(false, true)" in history
assert "reconcilePendingTextMessage" in history
assert "acknowledgeZenoraTextMessage(" in history
assert "pending.replyToId != replyToId" in history

# 401 preserves its cookie; a queued request must match the user that owns the outbox record.
assert "getCurrentUserId() != expectedUserId" in api
assert "prefs().getLong(\"user_id\", 0L) != expectedUserId" in api
assert 'request("message_send.php", "POST", form, true, expectedUserId)' in api
assert "saveCookies(connection, preserveAuthCookieOnUnauthorized && status == 401, expectedUserId)" in api
assert '"CSRF_INVALID".equals(error.errorCode)' in api
assert "getMuteActionCsrfToken(expectedUserId)" in api
assert "refreshMuteActionCsrfToken(expectedUserId)" in api
assert '"mobile_auth_challenge.php", "GET", null,' in api
assert "client_message_id=" in api
assert '"&reply_to_id="' in api and "acknowledgedReplyToId != replyToId" in api

# Native Retry is consumed by the Zenora integration before Telegram's MTProto path.
assert "ZenoraHistoryController.retryTextMessage(currentAccount, messageObject)" in sender
assert "prepareZenoraOutboxRetry(" in history
assert "existing.failed" in history

# Dedicated fast ARM64 build and release metadata for the new branch/tag.
branch = "feat/zenora-native-reply-android-20261126"
tag = "zenora-native-reply-android-20261126"
assert branch in workflow
assert tag in workflow
assert "scripts/validate-apk.sh debug" in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
assert "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow

print("Zenora outbox source contract checks passed")
