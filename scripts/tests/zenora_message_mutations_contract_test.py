#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
api = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java").read_text()
controller = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraMessageMutationController.java").read_text()
history = (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java").read_text()
workflow = (ROOT / ".github/workflows/build-debug.yml").read_text()

checks = {
    "standalone edit API method": "void editMessage(" in api and '"message_edit.php"' in api,
    "standalone single-delete API method": "void deleteMessage(" in api and '"message_delete.php"' in api,
    "standalone bulk-delete API method": "void deleteMessagesBulk(" in api and '"messages_bulk_delete.php"' in api,
    "explicit delete scopes only": '"me".equals(deleteScope) || "both".equals(deleteScope)' in api,
    "bounded bulk deletion": "messageIds.size() > 50" in api,
    "account-bound cookie and CSRF": "getMuteActionCsrfToken(expectedUserId)" in api and "true, expectedUserId" in api,
    "CSRF challenge refresh retry": "refreshMuteActionCsrfToken(expectedUserId)" in api and '"CSRF_INVALID"' in api,
    "background mutation executor": "mutationExecutor.execute" in api,
    "edit response version validation": 'optLong("edit_version", 0L) != expectedEditVersion + 1L' in api,
    "confirmed delete IDs validated": "validateMutationMessageIds" in api,
    "separate account-room controller": "class ZenoraMessageMutationController" in controller and "isCurrentZenoraRoom" in controller,
    "controller has no Telegram send/delete request path": "ConnectionsManager" not in controller and "TL_" not in controller,
    "history parser validates edit metadata": "isValidEditMetadata" in history and 'optLong("edit_version", 0L)' in history,
    "history parser stores edit metadata": "rememberMessageEditMetadata" in history,
    "build branch registered": "feat/zenora-message-mutations-foundation-android-20261126" in workflow,
    "release tag registered": "zenora-message-mutations-foundation-android-20261126" in workflow,
    "native edit branch registered": "feat/zenora-message-edit-android-20261126" in workflow,
    "native edit tag registered": "zenora-message-edit-android-20261126" in workflow,
    "edit controller enforces sender and text-only policy": "canEditPlainTextMessage" in controller and "object.messageOwner.from_id.user_id != userId" in controller,
    "edit controller blocks duplicate requests": "inFlightEdits.add(key)" in controller,
    "SQLite persists edit version": "zenoraEditVersion" in (ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java").read_text() and "version >= 2" in (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/MessageCustomParamsHelper.java").read_text(),
    "native Composer submits Zenora edit": "submitZenoraTextEdit" in (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text(),
    "conflicts refresh canonical message": "refreshEditedMessage" in history and "EDIT_VERSION_CONFLICT" in (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text(),
    "Zenora start-edit bypasses MTProto getEditData": (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text().find("if (zenoraEditRoomId > 0L)") < (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text().find("TL_messages_getMessageEditData"),
    "Zenora Done path bypasses SendMessagesHelper edit": (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text().find("if (zenoraRoomId > 0L)") < (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text().find("SendMessagesHelper.getInstance(currentAccount).editMessage"),
    "pre-release assets retained": "dist/SHA256SUMS" in workflow and "dist/BUILD-INFO.txt" in workflow,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit(1)
print(f"OK: {len(checks)} Zenora message mutation Android contract checks passed.")
