#!/usr/bin/env python3
"""Static regression checks for the native Zenora optimistic text-send contract."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MESSENGER = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger"
ENTER = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text()
HISTORY = (MESSENGER / "ZenoraHistoryController.java").read_text()
API = (MESSENGER / "ZenoraApiClient.java").read_text()
LIVE = (MESSENGER / "ZenoraLiveMessageSyncController.java").read_text()
SEND = (MESSENGER / "SendMessagesHelper.java").read_text()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("FAIL: " + message)

# Local identity and durable optimistic publication.
for fragment in (
    "UserConfig.getInstance(account).getNewMessageId()",
    "message.id = localId;",
    "message.local_id = localId;",
    "message.random_id = randomId;",
    'message.params.put("zenora_client_message_id", clientMessageId);',
    "new MessageObject(account, message, false, true)",
    # The durable integration stores the message and outbox atomically before publication.
    "MessagesStorage.getInstance(account).storeZenoraTextMessage(",
    "controller.updateInterfaceWithMessages(dialogId,",
):
    require(fragment in HISTORY, "missing optimistic local publish contract: " + fragment)

# Bounded per-room FIFO, independent rooms, separate bounded transport executor.
require("Map<Long, ArrayDeque<PendingTextSend>>" in HISTORY, "per-room FIFO queue is missing")
require("queue.peekFirst() != pending" in HISTORY, "only the room queue head may be sent")
require("MAX_PENDING_TEXT_SENDS_PER_ROOM" in HISTORY, "pending queue must remain bounded")
require("new java.util.concurrent.ArrayBlockingQueue<>(128)" in API, "text transport queue is not bounded")
require("textSendExecutor.execute" in API, "text POST is not isolated from history/polling executor")
require("pending.clientMessageId" in HISTORY, "the same client_message_id is not passed to POST")

# ACK converts the same local MessageObject and emits native ID-remap notifications.
for fragment in (
    "message.local_id = pending.localId;",
    "pending.messageObject",
    # ID remap and outbox deletion now share one storage transaction.
    "storage.acknowledgeZenoraTextMessage(",
    "NotificationCenter.messageReceivedByServer,",
    "NotificationCenter.messageReceivedByServer2,",
):
    require(fragment in HISTORY, "missing native ACK/reconcile contract: " + fragment)

# Both live polling and initial history reconcile by client_message_id.
require("reconcilePendingTextMessage(account, dialogId, roomId," in HISTORY,
        "initial history does not reconcile a pending client_message_id")
require("clientMessageIds.add(" in HISTORY, "live polling does not parse client_message_id")
require("reconcilePendingTextMessage(account, dialogId, roomId," in HISTORY,
        "live polling does not reconcile a pending client_message_id")
require("appliedCursor != lastId" in LIVE, "poll cursor is not gated by successful native page application")

# Retry is Zenora-routed by persisted message marker even before room mappings load.
require('containsKey("zenora_client_message_id")' in SEND
        or '!TextUtils.isEmpty(owner.params.get("zenora_client_message_id"))' in SEND,
        "MTProto retry guard does not recognize persisted Zenora messages")
require("retryTextMessage(currentAccount, messageObject)" in SEND,
        "Zenora retry is not routed to the PHP transport")

# The composer and selected Reply are cleared only after the optimistic message
# and durable Outbox row have committed in the same SQLite transaction.
require("if (onPersisted != null) onPersisted.run();" in HISTORY,
        "Composer persistence callback does not run after local Outbox commit")
STORAGE = (MESSENGER / "MessagesStorage.java").read_text()
store = STORAGE.split("public void storeZenoraTextMessage(", 1)[1].split("public void loadZenoraOutbox(", 1)[0]
require("database.beginTransaction()" in store and "database.commitTransaction()" in store,
        "optimistic message and Outbox are not stored in one transaction")
require("zenoraTextSendPersisting" in ENTER and "sameDraft && sameReply" in ENTER,
        "Composer does not protect a draft or selected Reply while saving")
require("if (selectedReply != null) setReplyingMessageObject(null, null, null);" in ENTER,
        "successful local persistence does not clear the selected native Reply")
require("Your draft and Reply selection were kept." in ENTER,
        "local persistence/send failure does not preserve Composer state")
require("!zenoraOptimisticText" in ENTER,
        "delayed send animation can still clear text typed after an optimistic send")

print("PASS: Zenora optimistic text-send source contract")
