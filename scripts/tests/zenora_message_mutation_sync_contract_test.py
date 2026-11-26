#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
def read(path):
    return (ROOT / path).read_text()

storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
migration = read("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
live = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java")
history = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
workflow = read(".github/workflows/build-debug.yml")
mutation = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java")

apply_start = storage.index("public void applyZenoraMessageMutationEvents")
apply_end = storage.index("public void putMessages(", apply_start)
apply = storage[apply_start:apply_end]
poll_start = live.index("private static void poll(")
poll_end = live.index("private static void handleResponse(", poll_start)
poll = live[poll_start:poll_end]

checks = {
    "durable cursor is migrated in SQLite v185": "user_version = 185" in migration and "zenora_message_mutation_cursor" in migration,
    "cursor is isolated by room, dialog and Zenora account": "PRIMARY KEY(room_id, dialog_id, zenora_user_id)" in migration,
    "cursor load is persisted and not memory-only": "loadZenoraMessageMutationCursor" in storage and "SELECT last_event_id FROM zenora_message_mutation_cursor" in storage,
    "event apply validates expected durable cursor": "storedCursor != expectedAfterEventId" in apply,
    "local changes and cursor share one transaction": apply.index("database.beginTransaction()") < apply.index("REPLACE INTO zenora_message_mutation_cursor") < apply.index("database.commitTransaction()"),
    "failure rolls back local state and cursor": "database.rollbackTransaction()" in apply,
    "edited event validates text, date and version": "message.edited" in apply and "editVersion <= 0L" in apply and "parseHistoryDate(editedAt)" in apply,
    "edit version is persisted to serialized message custom params": "MessageCustomParamsHelper.writeLocalParams(message)" in apply and "ZenoraMessageMutationController.applyEditMetadata" in apply,
    "stale edit event cannot downgrade local message": "message.zenoraEditVersion > editVersion" in apply and "editedMessages.add(message)" in apply,
    "personal and global deletes remove SQLite cache rows": "message.deleted_for_me" in apply and "message.deleted_for_all" in apply and "DELETE FROM messages_v2 WHERE uid = ? AND mid = ?" in apply,
    "dialog last-message pointer is recalculated after deletion": "UPDATE dialogs SET (last_mid, last_mid_group)" in apply,
    "same API request fetches message and mutation pages": "message_mutation_events.php?room_id=" in api and "mutation_events_page" in api,
    "API request is bound to the expected account": "getCurrentUserId() != expectedUserId" in api and "true, expectedUserId" in api,
    "existing live polling interval remains two seconds": "POLL_INTERVAL_MS = 2000L" in live,
    "single existing poll sends both message and event cursors": "fetchBasicTextMessagesAfter(session.roomId, requestedMessageCursor," in poll and "requestedEventCursor" in poll,
    "no additional polling service or websocket is introduced": live.count("new Handler(") == 1 and "WebSocket" not in live,
    "event page validates room, IDs, actor and event type": "validateMutationEventPage" in live and "event.optLong(\"room_id\", 0L) != roomId" in live and "actorId <= 0L" in live,
    "invisible events can advance cursor without looping": "scannedCount" in live and "next_after_event_id" in live,
    "event cursor is advanced only after successful SQLite commit": live.index("applyZenoraMessageMutationEvents(") < live.index("session.eventCursor = committedCursor"),
    "account switch and room mismatch stop event processing": "ZenoraApiClient.getCurrentUserId() != session.userId" in live and "getRoomIdForDialog(session.account, session.dialogId) != session.roomId" in live,
    "edited reply previews use canonical body and version": "applyEditedMessageToCachedReplyPreview" in live and "applyEditedMessage(" in history,
    "delete events use existing native cache/UI cleanup": "applyZenoraMessageDeletionLocally(session.dialogId, deletedIds)" in live,
    "new Android branch and release tag are registered": "feat/zenora-message-mutation-sync-android-20261126" in workflow and "zenora-message-mutation-sync-android-20261126" in workflow,
    "fast ARM64 release assets remain validated": "EXPECTED_ABIS=\"$ZENORA_ABIS\" bash scripts/validate-apk.sh debug" in workflow and "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow,
    "event sync is not routed through Telegram MTProto": "ConnectionsManager" not in apply and "TL_messages_" not in apply,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit(1)
print(f"OK: {len(checks)} Zenora message mutation event synchronization contract checks passed.")
