#!/usr/bin/env python3
"""Core Reply contracts: Telegram UI, API mapping, local persistence and no-MTProto policy."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(path):
    return (ROOT / path).read_text()

def method_body(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:i]
    raise AssertionError(f"unclosed method: {signature}")

ENTER = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
CHAT = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
HISTORY = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
API = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
STORAGE = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
MIGRATION = read("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
MEDIA = read("TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java")
LIVE = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java")

# Native Telegram Composer is reused. Only the standard positive-id text Reply is enabled.
assert "setReplyingMessageObject(replyParent" not in ENTER
assert "replyingQuote != null" in ENTER and "replyingTopMessage != null" in ENTER
assert "replyToId <= 0L" in ENTER and "replyParent.getDialogId() != dialog_id" in ENTER
assert "replyParent.messageOwner.action != null" in ENTER
assert "ZenoraHistoryController.sendTextMessage(" in ENTER
assert "body, replyToId, replyParent" in ENTER
assert "public void onQueued()" in ENTER
assert "setReplyingMessageObject(null, null, null)" in ENTER
assert "Your text and Reply state were kept." in ENTER

# Composer clear and Reply reset are gated on the accepted local storage transaction callback.
send_callback = ENTER.split("new ZenoraHistoryController.TextSendCallback()", 1)[1].split("});", 1)[0]
assert send_callback.index("public void onQueued()") < send_callback.index("messageEditText.setText(\"\")")
assert "if (!queued)" in ENTER
assert "return false;" in ENTER

# Optimistic TL header, parent identity, outbox identity and durable restore.
assert "TLRPC.TL_messageReplyHeader" in HISTORY
assert "replyHeader.reply_to_msg_id = (int) replyToId" in HISTORY
assert "message.replyMessage = replyParent.messageOwner" in HISTORY
assert "object.replyMessageObject = replyParent" in HISTORY
assert "final long replyToId;" in HISTORY
assert "record.replyToId" in HISTORY
assert "pending.replyToId != replyToId" in HISTORY
assert "pending.clientMessageId, pending.replyToId, pending.zenoraUserId" in HISTORY
assert "replyToId > 0L &&" in HISTORY and "reply_to_top_id != 0" in HISTORY

# POST and ACK carry the exact same target across retry and history/live reconciliation.
assert '"&reply_to_id="' in API
assert "acknowledgedReplyToId != replyToId" in API
assert "pending.replyToId, pending.zenoraUserId" in HISTORY
assert "ackReplyToId != pending.replyToId" in HISTORY
assert "acknowledgePendingTextSend(pending, (int) rawId, serverDate," in HISTORY
assert "applyReplyContractToMessage(message, row" in HISTORY
assert "applyReplyContractToMessage(message, replyRow" in HISTORY
live_apply = method_body(HISTORY, "public static long applyLiveTextMessages(")
assert "liveObject.replyMessageObject = replyObject" in live_apply
assert "liveObjectsById.get((int) liveReplyId)" in live_apply
assert "liveReplyParentsById" in live_apply

# v184 Outbox stores stable reply id plus safe text preview; old rows get neutral defaults.
assert "LAST_DB_VERSION = 184" in STORAGE
assert "reply_to_id INTEGER NOT NULL DEFAULT 0" in STORAGE
assert "reply_preview_sender_id INTEGER NOT NULL DEFAULT 0" in STORAGE
assert "reply_preview_body TEXT NOT NULL DEFAULT ''" in STORAGE
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_to_id" in MIGRATION
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_preview_sender_id" in MIGRATION
assert "ALTER TABLE zenora_outbox ADD COLUMN reply_preview_body" in MIGRATION
assert "putZenoraReplyPreviews" in STORAGE and "replydata = ?" in STORAGE
assert "replyPreviewMessage != null" in STORAGE
assert "UPDATE messages_v2 SET replydata = ?" in STORAGE
assert '"Message unavailable"' in HISTORY
assert "zenora_reply_preview_status" in HISTORY and "previewStatus" in MEDIA
assert '"Message unavailable".equals(unavailable.message)' in MEDIA
assert 'SELECT replydata FROM messages_v2 WHERE uid = ? AND mid = ? LIMIT 1' in MEDIA

# Initial History and incremental Live use the same contract and the native replydata store.
assert "replyIds.add(replyToId)" in HISTORY
assert "replyPreviews.add(row.optJSONObject(\"reply_preview\"))" in HISTORY
assert "MessagesStorage.getInstance(account).putZenoraReplyPreviews(stored)" in HISTORY
assert "MessagesStorage.getInstance(account).putZenoraReplyPreviews(result.messages)" in HISTORY
assert "applyLiveTextMessages" in LIVE

# All Zenora reply resolution is local-only; no network request in the guarded resolver.
resolver = method_body(MEDIA, "private void loadZenoraLocalReplyMessages(")
assert "messages_v2 WHERE uid = ? AND mid = ?" in resolver
assert "sendRequest(" not in resolver
assert "TL_messages_getMessages" not in resolver
assert "TL_channels_getMessages" not in resolver
loader_start = MEDIA.index("public void loadReplyMessagesForMessages(")
loader = MEDIA[loader_start:MEDIA.index("final boolean scheduled", loader_start)]
zenora_guard = loader.index("if (ZenoraChatController.getRoomIdForDialog")
assert zenora_guard >= 0 and "loadZenoraLocalReplyMessages(messages, dialogId, callback)" in loader

# Zenora preview taps are routed to the dedicated no-MTProto Reply Navigation path.
tap = method_body(CHAT, "public void didPressReplyMessage(ChatMessageCell cell, int id")
assert "getRoomIdForDialog(currentAccount, dialog_id)" in tap
assert tap.index("getRoomIdForDialog") < tap.index("scrollToMessageId")
assert "ZenoraHistoryController.navigateToReplyMessage(" in tap
assert "Core Reply intentionally does not include parent-message navigation yet." not in tap

# Non-Zenora Telegram paths remain present.
assert "scrollToMessageId(id, messageObject.getId()" in CHAT
assert "loadZenoraLocalReplyMessages(messages, dialogId, callback)" in loader
assert "getMediaDataController().loadReplyMessagesForMessages(objects, dialogId, mode" in read(
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")

print("Zenora Core Reply source contract checks passed")
