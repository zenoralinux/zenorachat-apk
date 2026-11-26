#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def read(path):
    return (ROOT / path).read_text()

history = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration = read("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
media = read("TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java")
validator = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraReplyValidator.java")
workflow = read(".github/workflows/build-debug.yml")

assert "ZenoraReplyValidator.validate(" in enter, "Composer must call the real MessageObject validator"
assert "replyingMessageObject != null" not in enter.split("private boolean processZenoraTextMessage",1)[1].split("public boolean processSendingText",1)[0], "ordinary reply must not be blanket-rejected"
assert "message.reply_to = new TLRPC.TL_messageReplyHeader()" in history
assert "message.reply_to.reply_to_msg_id" in history
assert "pending.replyToId" in history and "reply_to_id" in api
assert "reply_to_id" in storage and "record.replyToId" in history
assert "replyMessageObject" in history and "message.replyMessage" in history
assert "loadReplyMessagesForZenora" in media, "Zenora reply lookups must be intercepted before MTProto"
gate = media.split("public void loadReplyMessagesForMessages",1)[1].split("}",1)[0]
assert "loadReplyMessagesForZenora" in gate
assert "TL_messages_getMessages" not in gate and "TL_channels_getMessages" not in gate
assert "reply_to_id" in history, "History, Live and Outbox must carry the reply parent"
assert "if (version == 183)" in migration and "PRAGMA user_version = 184" in migration
assert "PRAGMA user_version = 185" in migration and "PRAGMA user_version = 186" in migration
assert "LAST_DB_VERSION = 186" in storage
assert "CREATE TABLE zenora_message_mutation_cursor" in storage
assert "CREATE TABLE zenora_message_mutation_deleted" in storage
assert "zenora-reply-send-validation-android-20261126" in workflow
assert "ZENORA_ABIS" in workflow and "arm64-v8a" in workflow
assert "dist/ZenoraChat-debug-arm64.apk" in workflow
print("Zenora Reply source integration contract tests passed")
