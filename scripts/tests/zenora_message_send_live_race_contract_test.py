#!/usr/bin/env python3
"""Regression guards for ACK/Live Sync races that used to drop sent bubbles."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MESSENGER = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger"
HISTORY = (MESSENGER / "ZenoraHistoryController.java").read_text()
CHAT = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text()

def method_body(source: str, signature: str) -> str:
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
    raise AssertionError(f"Unclosed method: {signature}")

ack = method_body(HISTORY, "private static void acknowledgePendingTextSend(")
live = method_body(HISTORY, "public static long applyLiveTextMessages(")
reconcile = method_body(HISTORY, "private static boolean reconcilePendingTextMessage(")

# ACK must keep the optimistic object's local key until Telegram's native remap.
assert "message.id = serverId;" not in ack
assert "message.local_id = pending.localId;" in ack
storage_ack = ack.index("storage.acknowledgeZenoraTextMessage(")
native_ack = ack.index("center.postNotificationName(NotificationCenter.messageReceivedByServer,")
assert storage_ack < native_ack
success_start = ack.index("synchronized (lock)", ack.index("if (!success)"))
success_projection = ack[success_start:native_ack]
assert "replaceMessagesObjects" not in success_projection, (
    "successful ACK must not replace the optimistic row before native ID remapping"
)
assert "pending.localId, serverId, null, pending.dialogId" in ack

# A failed local ACK still refreshes the local bubble's error state.
failure_start = ack.index("if (!success)")
failure_end = ack.index("synchronized (lock)", failure_start)
failure = ack[failure_start:failure_end]
assert "message.id = pending.localId;" in failure
assert "NotificationCenter.messageSendError" in failure
assert "NotificationCenter.replaceMessagesObjects" in failure

# Live/history publication reconciles client_message_id before constructing
# the canonical server row, regardless of which network response arrives first.
assert "reconcilePendingTextMessage(account, dialogId, roomId," in live
assert "reconciledClientMessage |= isCompletedTextMessage(" in live
assert live.index("reconcilePendingTextMessage(") < live.index("new TLRPC.TL_message()")
assert "pending.resolved.compareAndSet(false, true)" in reconcile
assert "if (pending.resolved.get()) return true;" in reconcile

# The native duplicate path preserves the already indexed canonical server row
# and tolerates the optimistic object already having left the adapter array.
ack_branch_start = CHAT.index("} else if (id == NotificationCenter.messageReceivedByServer) {")
ack_branch_end = CHAT.index("} else if (id == NotificationCenter.messageReceivedByAck)", ack_branch_start)
ack_branch = CHAT[ack_branch_start:ack_branch_end]
canonical_check = ack_branch.index("messagesDict[0].indexOfKey(newMsgId) >= 0")
old_key_removal = ack_branch.index("messagesDict[0].remove(msgId)")
assert canonical_check < old_key_removal
duplicate_branch = ack_branch[canonical_check:ack_branch.index("return;", canonical_check)]
assert "messagesDict[0].remove(newMsgId)" not in duplicate_branch
assert "if (index >= 0)" in duplicate_branch

print("PASS: Zenora ACK/Live Sync race reconciliation contract")
