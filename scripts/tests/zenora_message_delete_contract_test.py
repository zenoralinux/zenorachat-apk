#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def read(path):
    return (ROOT / path).read_text()

mutation = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java")
history = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
messages = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
message_object = read("TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java")
alerts = read("TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java")
chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
workflow = read(".github/workflows/build-debug.yml")

local_start = messages.index("public boolean applyZenoraMessageDeletionLocally")
local_end = messages.index("public void deleteMessages(ArrayList<Integer> messages", local_start)
local_delete = messages[local_start:local_end]
checks = {
    "single delete uses message_delete.php": '"message_delete.php"' in api and "ZenoraApiClient.deleteMessage(roomId" in mutation,
    "bulk delete uses messages_bulk_delete.php": '"messages_bulk_delete.php"' in api and "ZenoraApiClient.deleteMessagesBulk(roomId" in mutation,
    "explicit me/both scopes": '"me".equals(deleteScope) || "both".equals(deleteScope)' in api,
    "bulk cap checked before submission": "messageIds.size() > 50" in api and "objects.size() > 50" in mutation,
    "selection scoped to one dialog and room": "object.getDialogId() != dialogId" in mutation and "getRoomIdForDialog" in mutation,
    "mixed pending and delivered selection rejected": "return !(hasPending && hasDelivered);" in mutation,
    "exact response set checked before local cleanup": "confirmedIds.equals(new HashSet<>(serverIds))" in mutation,
    "local delete only after success": "if (resultError == null)" in mutation and "applyZenoraMessageDeletionLocally" in mutation,
    "local-only helper never invokes MTProto delete": ".deleteMessages(" not in local_delete and "ConnectionsManager" not in local_delete and "TL_messages_" not in local_delete,
    "MessagesController has a hard block for Zenora MTProto deletes": 'Blocked Telegram/MTProto deleteMessages call for Zenora dialog' in messages and "ZenoraChatController.isZenoraDialog(currentAccount, dialogId)" in messages[messages.index("public void deleteMessages(ArrayList<Integer> messages, ArrayList<Long> randoms, TLRPC.EncryptedChat encryptedChat, long dialogId, boolean forAll, int mode, boolean cacheOnly, long taskId, TLObject taskRequest, int topicId, boolean movedToScheduled, int movedToScheduledMessageId)")-20:],
    "ChatActivity cancel-sending accepts only negative pending IDs": "onlyPendingLocalMessages" in chat and "pendingObject.getId() >= 0" in chat,
    "local cleanup updates SQLite/dialog preview": "markMessagesAsDeleted(dialogId, ids" in local_delete and "updateDialogsWithDeletedMessages(dialogId" in local_delete,
    "pending cancellation uses durable Outbox": "cancelPendingTextMessages" in mutation and "removeZenoraOutboxMessages" in history and "DELETE FROM zenora_outbox" in storage,
    "unknown and in-flight pending sends are refused": "pending.started || pending.resolved.get()" in history and "ZenoraOutboxPolicy.FAILED.equals(pending.status)" in history,
    "pending cancellation avoids retry race": "pending.cancelRequested" in history and "pending.cancelRequested = true" in history,
    "reply previews invalidated": "invalidateReplyPreviews" in local_delete and "Original message unavailable" in history,
    "MessageObject uses Zenora delete eligibility": "ZenoraMessageMutationController.canDeleteMessage" in message_object,
    "Zenora confirmation occurs before MTProto participant lookup": alerts.index("if (hasZenoraSelection)") < alerts.index("TL_channels_getParticipant"),
    "native scope checkbox maps to me/both": "R.string.DeleteForAll" in alerts and 'deleteForAll[0] ? "both" : "me"' in alerts,
    "ChatActivity pending cancel bypasses Telegram helper": "ZenoraMessageMutationController.deleteSelection" in chat and chat.index('if (cancelDialogId != 0L && ZenoraChatController.isZenoraDialog') < chat.index("getSendMessagesHelper().cancelSendingMessage(selectedObject)"),
}
failed=[name for name,ok in checks.items() if not ok]
for name,ok in checks.items():
    print(("PASS" if ok else "FAIL")+": "+name)
if failed: raise SystemExit(1)
print(f"OK: {len(checks)} Zenora native message-delete contract checks passed.")
