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

# Local message and Outbox row commit in one storageQueue transaction.
assert "public void storeZenoraTextMessage(" in storage
store = storage.split("public void storeZenoraTextMessage(", 1)[1].split("public void loadZenoraOutbox(", 1)[0]
assert "storageQueue.postRunnable" in store and "database.beginTransaction()" in store
assert "putMessagesInternal(messages, false, false" in store
assert "INSERT OR REPLACE INTO zenora_outbox" in store and "reply_to_id" in store
assert "database.commitTransaction()" in store and "rollbackTransaction()" in store
load = storage.split("public void loadZenoraOutbox(",1)[1].split("public void updateZenoraOutboxState(",1)[0]
assert "reply_to_id" in load
retryStorage = storage.split("public void prepareZenoraOutboxRetry(",1)[1].split("public void acknowledgeZenoraTextMessage(",1)[0]
assert "reply_to_id" in retryStorage
ack = storage.split("public void acknowledgeZenoraTextMessage(",1)[1].split("public static final class ZenoraHistoryState",1)[0]
assert "storedReplyToId != replyToId" in ack and "AND reply_to_id = ?" in ack

# The reply column remains an additive 183->184 migration, followed by
# mutation-event cursor/tombstone migrations through the current v187 schema.
assert "if (version == 181)" in migration and "if (version == 182)" in migration
assert "if (version == 183)" in migration and "PRAGMA user_version = 184" in migration
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_to_id" in migration
assert "PRAGMA user_version = 185" in migration and "PRAGMA user_version = 186" in migration and "PRAGMA user_version = 187" in migration
assert "LAST_DB_VERSION = 188" in storage
assert "CREATE TABLE zenora_message_mutation_cursor" in storage
assert "CREATE TABLE zenora_message_mutation_deleted" in storage
assert "CREATE TABLE zenora_message_mutation_pending_edit" in storage

# Stable key + stable parent survive retry, network retry and process restore.
assert "final long replyToId;" in history and "record.replyToId" in history
assert "pending.replyToId" in history and "prepareZenoraOutboxRetry(" in history
assert "reply_to_id" in api and "&reply_to_id=" in api
assert "message.optLong(\"reply_to_id\", 0L) != replyToId" in api
assert "message.reply_to = new TLRPC.TL_messageReplyHeader()" in history
assert "message.reply_to.reply_to_msg_id" in history
assert "replyMessageObject" in history and "message.replyMessage" in history

# History and Live map the same reply contract into native MessageObjects.
assert "applyLiveTextMessages" in history and "cacheReplyPreviewFromRow" in history
assert "replyToIds.get(i)" in history and "isValidReplyContractRow(row)" in history
assert "loadReplyMessagesForZenora" in history

# Telegram native retry is intercepted by Zenora, not sent with MTProto.
assert "ZenoraHistoryController.retryTextMessage(currentAccount, messageObject)" in sender
media = source("TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java")
method = media.split("public void loadReplyMessagesForMessages",1)[1]
assert method.index("loadReplyMessagesForZenora") < method.index("final boolean scheduled")
assert "loadReplyMessagesForZenora(currentAccount, messages, dialogId, callback)" in method[:450]
assert "TL_messages_getMessages" not in method[:450] and "TL_channels_getMessages" not in method[:450]

branch = "fix/zenora-reply-send-validation-android-20261126"
tag = "zenora-reply-send-validation-android-20261126"
assert branch in workflow and tag in workflow
assert "ZENORA_ABIS" in workflow and "arm64-v8a" in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
assert "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow

print("Zenora outbox/reply source contract checks passed")
