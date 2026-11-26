#!/usr/bin/env python3
"""Source contracts for Zenora Reply Navigation and the bounded around_id fetch."""
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

CHAT = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
HISTORY = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraHistoryController.java")
API = read("TMessagesProj/src/main/java/org/telegram/messenger/ZenoraApiClient.java")
MEDIA = read("TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java")
STORAGE = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")

# The preview touch handler delegates Zenora IDs only; Telegram's standard path remains intact.
tap = method_body(CHAT, "public void didPressReplyMessage(ChatMessageCell cell, int id")
zenora_start = tap.index("if (zenoraRoomId > 0L)")
telegram_start = tap.index("if (!canPerformActions())")
zenora_tap = tap[zenora_start:telegram_start]
assert "ZenoraHistoryController.navigateToReplyMessage(" in zenora_tap
assert "reply_to_msg_id != id" in zenora_tap
assert "reply_to_peer_id != null" in zenora_tap
assert "scrollToMessageId" not in zenora_tap
assert "if (longpress" in zenora_tap
assert "scrollToMessageId(id, messageObject.getId()" in tap

# The Activity is bound to account, room, dialog and classGuid; closed/replaced chats are rejected.
current = method_body(CHAT, "public boolean isZenoraReplyNavigationCurrent(")
for token in (
    "currentAccount == account", "UserConfig.selectedAccount == account",
    "dialog_id == expectedDialogId", "classGuid == expectedClassGuid",
    "getRoomIdForDialog(account, expectedDialogId) == expectedRoomId",
    "getCurrentUserId() == expectedUserId", "!paused", "fragmentOpened",
):
    assert token in current

# A target counts as loaded only if the same object is in Telegram's current message list and adapter.
in_adapter = method_body(CHAT, "public boolean hasZenoraParentInAdapter(")
assert "messagesDict[0].get(targetMessageId)" in in_adapter
assert "messages.contains(parent)" in in_adapter
assert "chatAdapter.getMessages().contains(parent)" in in_adapter
assert "chatAdapter.isFiltered" in in_adapter

# A locally persisted Reply Preview snapshot is itself a valid offline parent source.
preview_parent = method_body(CHAT, "public TLRPC.Message getZenoraReplyPreviewParent(")
for token in (
    "child.replyMessageObject", "repliesMessagesDict.get(targetMessageId)",
    "child.messageOwner.replyMessage", "parent.id != targetMessageId",
    "parent.dialog_id != dialog_id", "TL_messageMediaEmpty",
    "zenora_reply_preview_status", "parent.from_id == null",
):
    assert token in preview_parent

loaded_scroll = method_body(CHAT, "public void navigateToLoadedZenoraParent(")
assert loaded_scroll.index("hasZenoraParentInAdapter") < loaded_scroll.index("scrollToMessageId(")

# Around responses enter Telegram's native MessagesController/MessageObject pipeline.
publish = method_body(CHAT, "public void publishZenoraAroundMessages(")
assert "MessagesController.LOAD_AROUND_MESSAGE" in publish
assert "processLoadedMessages(" in publish
assert "waitingForLoad.add(loadIndex)" in publish
assert "zenoraAroundNavigationLoads.put(loadIndex, state)" in publish
assert "messagesDict[0].get(message.id) != null" in publish
assert "Collections.sort(unique.messages" in publish
assert "isZenoraReplyNavigationCurrent" in publish
assert "ConnectionsManager.sendRequest" not in publish
assert "TL_messages_getMessages" not in publish
assert "hasZenoraParentInAdapter" in publish

# Telegram's notification handler restores all history and unread pagination state for around-only loads.
state_start = CHAT.index("private final class ZenoraAroundNavigationState")
state_end = CHAT.index("public boolean isZenoraReplyNavigationCurrent", state_start)
around_state = CHAT[state_start:state_end]
for token in (
    "maxMessageId.clone()", "minMessageId.clone()", "endReached.clone()",
    "cacheEndReached.clone()", "forwardEndReached.clone()", "last_message_id",
    "first_unread_id", "loadsCount", "loadingForwardSnapshot",
    "firstUnreadSentSnapshot", "hasAllMentionsLocalSnapshot", "void restore()",
):
    assert token in around_state
load_handler = method_body(CHAT, "private void didReceivedNotification_messagesDidLoad(")
assert "ZenoraAroundNavigationState zenoraAroundState" in load_handler
assert "zenoraAroundState.capture()" in load_handler
assert "zenoraAroundState.restore()" in load_handler
assert "if (zenoraAroundState != null)" in load_handler
assert "checkScrollForLoad(false)" in load_handler
assert "scrollToMessageId(zenoraAroundState.targetMessageId" in load_handler
assert load_handler.index("hasZenoraParentInAdapter(zenoraAroundState.targetMessageId)") < load_handler.index(
    "scrollToMessageId(zenoraAroundState.targetMessageId")

# API request is account-bound and uses only basic_text&around_id.
around_request = method_body(API, "public static void fetchBasicTextMessagesAround(")
assert "roomId <= 0 || aroundMessageId <= 0" in around_request
assert "&format=basic_text&around_id=" in around_request
assert "request(path, \"GET\", null, false, expectedUserId)" in around_request
assert "getCurrentUserId() != expectedUserId" in around_request

# Resolution order is local adapter, SQLite, then online API; no MTProto fallback exists.
navigate = method_body(HISTORY, "public static void navigateToReplyMessage(")
assert "activity.hasZenoraParentInAdapter(targetMessageId)" in navigate
assert "loadReplyTargetFromStorage(" in navigate
active = method_body(HISTORY, "private static boolean isReplyNavigationRequestActive(")
assert "UserConfig.selectedAccount != account" in active
assert "request.expectedUserId == expectedUserId" in active
storage_load = method_body(HISTORY, "private static void loadReplyTargetFromStorage(")
assert "getStorageQueue().postRunnable" in storage_load
assert "SELECT data, mid, date FROM messages_v2 WHERE uid = ? AND mid = ? LIMIT 1" in storage_load
assert "ApplicationLoader.isNetworkOnline()" in storage_load
assert "fetchBasicTextMessagesAround(" in storage_load
assert "findCachedReplyPreviewFromWaiters(" in storage_load
assert storage_load.index("findCachedReplyPreviewFromWaiters(") < storage_load.index("ApplicationLoader.isNetworkOnline()")
assert "response.optBoolean(\"ok\", false)" in storage_load
assert "responseRoom.optLong(\"id\", 0L) != roomId" in storage_load
assert "ConnectionsManager.sendRequest" not in storage_load
assert "TL_messages_getMessages" not in storage_load
assert "TL_channels_getMessages" not in storage_load

# Destroyed Activities are detached from shared requests; other waiters are retained.
cancel = method_body(HISTORY, "public static void cancelDialogRequests(")
assert "replyNavigationInFlight.get(account)" in cancel
assert "request.dialogId != dialogId" in cancel
assert "waiter.classGuid == classGuid" in cancel
assert "request.waiters.isEmpty()" in cancel
assert "iterator.remove()" in cancel

# Responses are bounded, ascending, room-bound and must contain their exact target ID.
validate = method_body(HISTORY, "private static boolean isValidBasicTextAroundPage(")
for token in (
    "direction", '"around_id"', "around_message_id", "rows.length() > 50",
    "row.optLong(\"room_id\", 0L) != roomId", "messageId <= previousId",
    "foundTarget", "next_after_id",
):
    assert token in validate
finish = method_body(HISTORY, "private static void finishReplyNavigation(")
assert "putMessages(" in finish and "putZenoraReplyPreviews(" in finish
assert "publishZenoraAroundMessages(" in finish
assert "onHistoryApplied(" not in finish
assert "unavailableTargets.add(replyNavigationKey(" in finish

# The existing Zenora Reply-preview loader remains SQLite-only and never asks MTProto.
resolver = method_body(MEDIA, "private void loadZenoraLocalReplyMessages(")
assert "messages_v2 WHERE uid = ? AND mid = ?" in resolver
assert "sendRequest(" not in resolver
assert "TL_messages_getMessages" not in resolver
assert "TL_channels_getMessages" not in resolver
assert "putZenoraReplyPreviews" in STORAGE

print("Zenora Reply Navigation source contract checks passed")
