#!/usr/bin/env python3
"""Regression contract for incoming Zenora message edits in an open chat."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(path):
    return (ROOT / path).read_text()

chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
api = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
live = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraLiveMessageSyncController.java")
workflow = read(".github/workflows/build-debug.yml")

start = chat.index("private void replaceMessageObjects(ArrayList<MessageObject> messageObjects, int loadIndex, boolean remove, boolean ignoreDateCheckBeforeRemove)")
end = chat.index("updatePinnedTopicStarterMessage();", start)
replace = chat[start:end]

checks = {
    "visible primary chat list is searched by canonical message ID":
        "candidate.getId() == messageObject.getId()" in replace,
    "cross-dialog candidates are rejected":
        "candidate.getDialogId() == dialog_id" in replace,
    "stale messagesDict entry is repaired from the visible live object":
        "messagesDict[loadIndex].put(messageObject.getId(), visibleMessage)" in replace,
    "repair happens before missing-target early exit":
        replace.index("messagesDict[loadIndex].put(messageObject.getId(), visibleMessage)")
        < replace.index("if (old == null ||"),
    "missing live replacement target is logged":
        "Live message replacement target missing" in replace,
    "mutation endpoint failures are visible to the sync coordinator":
        "mutation_events_error" in api and "mutation_events_error" in live,
    "event cursor is only advanced after successful SQLite application":
        live.index("applyZenoraMessageMutationEvents(")
        < live.index("session.eventCursor = committedCursor"),
    "successful message delta is retained when the event poll fails":
        "dispatch(callback, response, null)" in api,
    "new branch is configured for ARM64 release":
        "refs/heads/fix/zenora-incoming-message-edit-live-ui-android-20261128" in workflow
        and "arm64-v8a" in workflow
        and "zenora-incoming-message-edit-live-ui-android-20261128" in workflow,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit(1)
print(f"OK: {len(checks)} incoming-edit UI contract checks passed.")
