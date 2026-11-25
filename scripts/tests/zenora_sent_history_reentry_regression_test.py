#!/usr/bin/env python3
"""Regression guards for Zenora sent-message history re-entry.

These checks verify the production reconciliation control flow and API contract;
they are not a substitute for device-level lifecycle/SQLite integration tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HISTORY = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java").read_text()
API_HELPER = (ROOT.parent / "zenorachat" / "lib/basic_text_history.php")
# The companion API is validated separately in its own repository; this test
# verifies the Android contract does not regress to suppressing completed rows.

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

publish = method_body(HISTORY, "private static boolean publish(")
live = method_body(HISTORY, "public static long applyLiveTextMessages(")
ack = method_body(HISTORY, "private static void acknowledgePendingTextSend(")
reset = method_body(HISTORY, "private static void resetDialogSendDeduplication(")

# History must reconcile pending/completed identity without dropping a server
# message merely because its client ID was ACKed in a previous visit.
assert "reconcilePendingTextMessage(account, dialogId, roomId" in publish
assert "isCompletedTextMessage(account, dialogId, clientMessageId, messageId);" in publish
assert "|| isCompletedTextMessage" not in publish
assert "continue;" not in publish[publish.index("isCompletedTextMessage"):publish.index("if (!seenIds.add(messageId)")]

# Live polling must converge ACK/history races while still applying rows not
# currently represented in the active ChatActivity.
assert "reconcilePendingTextMessage(account, dialogId, roomId" in live
assert "isCompletedTextMessage(account, dialogId, clientMessageId, messageId);" in live
assert "|| isCompletedTextMessage" not in live
assert "committedIds.add(messageId);" not in live[live.index("isCompletedTextMessage"):live.index("boolean alreadyApplied;")]

# Existing ACK path still uses atomic resolved guard and SQLite transaction
# callback; completed IDs are recorded only after storage succeeds.
assert "pending.resolved.compareAndSet(false, true)" in ack
assert "storage.acknowledgeZenoraTextMessage(" in ack
assert "completed.put(pending.clientMessageId, serverId)" in ack
assert "sentMessageIds" in ack

# Reset the per-dialog applied-ID set for each new initial history publication;
# don't clear completed-ID reconciliation metadata (which is now non-suppressing).
assert "accountSent.remove(dialogId)" in reset
assert "completedTextByClientId" not in reset

print("Zenora sent-history re-entry regression guards: PASS")
