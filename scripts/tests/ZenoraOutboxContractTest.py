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
store = storage.split("public void storeZenoraTextMessage(", 1)[1].split("public void loadZenoraOutbox(", 1)[0]
assert "storageQueue.postRunnable" in store
assert "database.beginTransaction()" in store
assert "putMessagesInternal(messages, false, false" in store
assert "INSERT OR REPLACE INTO zenora_outbox" in store
assert "database.commitTransaction()" in store
assert "rollbackTransaction()" in store
assert '"zenora_outbox"' in storage

# Upgrade and backup/restore contract.
assert "if (version == 181)" in migration and "PRAGMA user_version = 182" in migration
assert "CREATE TABLE IF NOT EXISTS zenora_outbox" in migration
assert "zenora_outbox" in storage.split("DATABASE_TABLES", 1)[1].split("};", 1)[0]

# Durable rehydration, per-room FIFO and stable keys.
assert "restorePendingTextSends(int account)" in history
assert "record.zenoraUserId != currentUserId" in history
assert "enqueuePendingTextSend(restoredPending)" in history
assert "Collections.sort(ordered" in history
assert "pending.clientMessageId, pending.zenoraUserId" in history
assert "ZenoraOutboxPolicy.UNKNOWN" in history
assert "ZenoraOutboxPolicy.FAILED" in history
assert "AtomicBoolean(false)" in history and "compareAndSet(false, true)" in history
assert "reconcilePendingTextMessage" in history
assert "acknowledgeZenoraTextMessage(" in history

# 401 preserves its cookie; a queued request must match the user that owns the outbox record.
assert "getCurrentUserId() != expectedUserId" in api
assert "prefs().getLong(\"user_id\", 0L) != expectedUserId" in api
assert 'request("message_send.php", "POST", form, true, expectedUserId)' in api
assert "saveCookies(connection, preserveAuthCookieOnUnauthorized && status == 401, expectedUserId)" in api
assert '"CSRF_INVALID".equals(error.errorCode)' in api
assert "getMuteActionCsrfToken(expectedUserId)" in api
assert "refreshMuteActionCsrfToken(expectedUserId)" in api
assert 'request("mobile_auth_challenge.php", "GET", null, true, expectedUserId)' in api
assert "client_message_id=" in api

# Native Retry is consumed by the Zenora integration before Telegram's MTProto path.
assert "ZenoraHistoryController.retryTextMessage(currentAccount, messageObject)" in sender
assert "prepareZenoraOutboxRetry(" in history
assert "existing.failed" in history

# Dedicated fast ARM64 build and release metadata for the new branch/tag.
branch = "feat/zenora-durable-text-send-android-20261125"
tag = "zenora-durable-text-send-android-20261125"
assert branch in workflow
assert tag in workflow
assert "scripts/validate-apk.sh debug" in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
assert "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow

print("Zenora outbox source contract checks passed")
