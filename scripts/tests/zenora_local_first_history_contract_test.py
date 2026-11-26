#!/usr/bin/env python3
"""Source-level safety contracts for local-first Zenora history loading."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAT = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text()
CONTROLLER = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java").read_text()
STORAGE = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java").read_text()
HISTORY = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java").read_text()
POLICY = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryPolicy.java").read_text()
MIGRATION = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java").read_text()
WORKFLOW = (ROOT / ".github/workflows/build-debug.yml").read_text()
OUTBOX_REGRESSION = ROOT / "scripts/tests/zenora_sent_history_reentry_regression_test.py"

def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)

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
    raise AssertionError(f"unclosed method: {signature}")

first_load = method_body(CHAT, "public void firstLoadMessages()")
expect("getMessagesController().loadMessages(" in first_load,
       "Zenora first load must enter Telegram's native message loader")
expect("MessagesController.LOAD_BACKWARD" in first_load and "true, 0, classGuid" in first_load,
       "initial Zenora history must use non-blocking local cache mode")
expect("-zenoraLoadIndex" in first_load,
       "initial local cache load must retain a completion index for the API merge")
expect("ZenoraHistoryController.loadInitialHistory(" not in first_load,
       "ChatActivity must not bypass SQLite with a direct initial HTTP request")

expect("MessagesController.LOAD_BACKWARD" in CHAT,
       "older Zenora pages must use native backward cache loading")
expect("ZenoraHistoryController.loadOlderHistory(" not in CHAT,
       "ChatActivity scroll must not directly replace native local pagination")
expect("waitingForLoad.isEmpty()" in CHAT,
       "older local/cache/API loads must not overlap")
expect("keepZenoraHistoryIndex" in CHAT,
       "short local cache pages must preserve their loading index until API merge")

expect("getMessagesStorage().getMessages(" in CONTROLLER,
       "native cache path must read through MessagesStorage.getMessages")
expect("loadIndex < 0" in CONTROLLER,
       "only marked native SQLite callbacks may start the Zenora HTTP request")
expect("ZenoraHistoryPolicy.reachedLocalCacheBoundary(resCount, count)" in CONTROLLER,
       "HTTP pagination must begin only at the local cache boundary")
expect("&& !ZenoraChatController.isZenoraDialog(currentAccount, dialogId)" in CONTROLLER,
       "Telegram MTProto history fallback must be disabled for Zenora dialogs")
expect(CONTROLLER.index("postNotificationName(NotificationCenter.messagesDidLoad") <
       CONTROLLER.index("ZenoraHistoryController.loadInitialHistory("),
       "the local page must be notified before its background HTTP refresh")

expect("public void getMessages(long dialogId" in STORAGE,
       "native getMessages overload must remain available")
expect("storageQueue.postRunnable(() -> {" in STORAGE,
       "SQLite work must remain on MessagesStorage's queue")
expect("getMessagesInternal(dialogId" in STORAGE,
       "the native SQLite history reader must remain the message source")
expect("PRAGMA user_version = 183" in MIGRATION,
       "182->183 durable history migration must be present")
expect("CREATE TABLE zenora_history_state" in MIGRATION and
       "CREATE TABLE zenora_history_state" in STORAGE,
       "migration and fresh database must both create history state")
expect("loadZenoraHistoryState(" in STORAGE and "saveZenoraHistoryState(" in STORAGE,
       "per-room/account coverage state must be durable")
expect("ZenoraHistoryPolicy.mergeOlderCursor" in STORAGE,
       "newer refreshes must not move the older cursor past cached pages")
expect("ZenoraHistoryPolicy.isLocalCoverageIntact" in STORAGE,
       "a pruned local cache must invalidate its previous coverage bound")
expect("newestLocalMessageId" in HISTORY and
       "state.newestLocalMessageId" in HISTORY,
       "latest live cursor must recover from local SQLite message bounds")
expect("initialRequestIds" in HISTORY and "activeRequestId != requestId" in HISTORY,
       "late initial API responses must not complete a newer ChatActivity request")
expect("Math.min(state.oldestMessageId, localBeforeMessageId)" in HISTORY,
       "server cursor must never cross the oldest locally cached page boundary")
expect("hasMoreKnown" in HISTORY and "hasMoreOlder" in HISTORY,
       "server end state must remain distinct from unknown local coverage")
expect("boolean isCache = error != null;" in HISTORY and "isEnd = false;" in HISTORY,
       "API errors must release loading with a non-terminal cache-only completion")

# The stage-1 completedTextByClientId reconciliation guard must be preserved.
expect("completedTextByClientId" in HISTORY,
       "stage-1 completed client-id reconciliation metadata must remain")
expect("reconciledClientMessage |= isCompletedTextMessage(" in HISTORY,
       "completed IDs must reconcile, not suppress, the server history row")
expect(OUTBOX_REGRESSION.exists(),
       "stage-1 re-entry regression test must remain in the repository")
expect("zenora_outbox" in STORAGE and "loadZenoraOutbox(" in STORAGE,
       "persistent Outbox storage and restore must remain intact")
tables = STORAGE.split("DATABASE_TABLES", 1)[1].split("};", 1)[0]
expect(tables.count('"zenora_outbox"') == 1 and tables.count('"zenora_history_state"') == 1,
       "recovery table list must include each Zenora table exactly once")
expect('version != MessagesStorage.LAST_DB_VERSION && version != 183 && version != 182' in MIGRATION,
       "database recovery must accept the previous version-182 schema")
expect('excludeTables.add("zenora_history_state")' in MIGRATION,
       "version-182 recovery must skip only the newly introduced table")

branch = "fix/zenora-offline-history-cache-first-android-20261126"
tag = "zenora-offline-history-cache-first-android-20261126"
expect(branch in WORKFLOW and tag in WORKFLOW,
       "new branch and release tag must be wired into the existing workflow")
expect("ZENORA_ABIS" in WORKFLOW and "'arm64-v8a'" in WORKFLOW,
       "fast Debug build must target arm64-v8a")
expect(":TMessagesProj_App:assembleAfatDebug" in WORKFLOW,
       "existing AFAT Debug build task must be retained")
expect("scripts/validate-apk.sh" in WORKFLOW,
       "APK validation must remain enabled")
expect("ZenoraChat Offline Local History (ARM64)" in WORKFLOW,
       "release display name must match this feature")
expect("ZenoraHistoryMigrationTest.py" in WORKFLOW and
       "zenora_local_first_history_contract_test.py" in WORKFLOW,
       "new local-first and migration tests must run in CI")

print("Zenora local-first history source contracts passed")
