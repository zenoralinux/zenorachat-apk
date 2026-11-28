#!/usr/bin/env python3
"""Source-level regression contract for durable optimistic Zenora message edits."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def read(path):
    return (ROOT / path).read_text()

controller = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java")
outbox = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraEditOutbox.java")
history = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
ui = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
message_object = read("TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java")

checks = {
    "SQLite outbox stores immutable operation ID and full payload": (
        "operation_id TEXT PRIMARY KEY NOT NULL" in outbox
        and 'values.put("body", body)' in outbox
        and 'values.put("expected_edit_version", expectedVersion)' in outbox
    ),
    "message body and operation row commit atomically": (
        "db.beginTransaction()" in outbox and "db.insertOrThrow(TABLE, null, values)" in outbox
        and "db.setTransactionSuccessful()" in outbox and "db.endTransaction()" in outbox
    ),
    "multiple edits receive increasing per-message versions and sequence numbers": (
        "sequence = latest.getLong(0) + 1L" in outbox
        and "latest.getLong(1) + 1L" in outbox
    ),
    "per-account FIFO is persisted and restored": (
        "loadPendingForUser" in outbox and "ORDER BY created_at ASC, rowid ASC" in outbox
        and "restorePendingEdits" in controller and "resetSending" in controller
    ),
    "stable IDs survive retries and conflict rebasing": (
        "record.operationId" in controller and "operationId" in api
        and "rebaseMessage" in controller
    ),
    "retry uses bounded exponential backoff and persists state": (
        "backoffDelayMs" in outbox and "markRetry" in controller
        and "nextAttemptAt" in outbox and "attemptCount" in outbox
    ),
    "version conflict keeps canonical server row and rebases whole chain": (
        'conflict.response.optJSONObject("message")' in controller
        and "rebaseMessage(account" in controller
        and "EDIT_VERSION_CONFLICT" in controller
    ),
    "API errors preserve structured server conflict payload": (
        "public final JSONObject response;" in api
        and "new ApiException(status, json.optString" in api
    ),
    "HTTP acknowledgment is not required for optimistic UI update": (
        'response.optBoolean("queued", false)' in ui
        and "ZenoraHistoryController.applyEditedMessage(" in ui
        and "record.operationId" in controller
    ),
    "UI submits edits through SQLite queue without direct HTTP dependency": (
        "ZenoraMessageMutationController.editMessage(" in ui
        and "ZenoraApiClient.editMessage(" not in ui
    ),
    "double tap is blocked only during local durable enqueue": (
        "Saving the previous edit locally" in ui and "zenoraEditInFlight = false" in ui
    ),
    "history and live sync prefer the newest pending local body": (
        "getLatestPendingEditMetadata" in history
        and "pendingEdit.body" in history
        and "applyPendingEditOverlay" in history
    ),
    "cold MessageObject materialization overlays a queued edit before text layout": (
        "applyPendingEditToMessage(accountNum, messageOwner)" in message_object
        and message_object.index("applyPendingEditToMessage(accountNum, messageOwner)")
            < message_object.index("updateMessageText(users, chats, sUsers, sChats)")
    ),
    "reply previews and dialog last-message preview use common update path": (
        "applyEditedMessageToCachedReplyPreview" in history
        and "replyPreviewOwners" in history
        and "updateZenoraDialogPreview(dialogId, target)" in history
    ),
    "account switch does not delete or send another account's edits": (
        "isEditIdentityActive(account, expectedUserId)" in controller
        and "loadPendingForUser(account, userId)" in controller
    ),
    "no additional edit polling loop is added": "POLL_INTERVAL_MS" not in controller,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit(1)
print(f"OK: {len(checks)} durable optimistic edit source-contract checks passed.")
