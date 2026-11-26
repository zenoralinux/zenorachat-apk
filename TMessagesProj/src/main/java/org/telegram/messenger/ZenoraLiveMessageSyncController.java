package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Lifecycle-bound polling for new Zenora messages and mutation events in the
 * same existing two-second chat sync cycle. No socket or second poller is used.
 */
public final class ZenoraLiveMessageSyncController {
    private static final Object lock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final long MAX_BACKOFF_MS = 30000L;
    private static final int PAGE_SIZE = 50;
    private static Session activeSession;

    private ZenoraLiveMessageSyncController() {
    }

    private static final class Session {
        final int account;
        final long userId;
        final long dialogId;
        final long roomId;
        final int classGuid;
        final Runnable pollRunnable;
        long cursor = -1L;
        long eventCursor = -1L;
        int failures;
        boolean eventCursorLoading;
        boolean inFlight;
        boolean active = true;

        Session(int account, long userId, long dialogId, long roomId, int classGuid) {
            this.account = account;
            this.userId = userId;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.classGuid = classGuid;
            pollRunnable = () -> poll(this);
        }
    }

    private static final class MutationPage {
        final JSONArray events;
        final long nextCursor;
        final boolean hasMore;

        MutationPage(JSONArray events, long nextCursor, boolean hasMore) {
            this.events = events;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }
    }

    public static void onChatResumed(int account, long dialogId, int classGuid) {
        if (dialogId == 0 || !ZenoraChatController.isZenoraEnabled()
                || !ZenoraChatController.isZenoraDialog(account, dialogId)) {
            return;
        }
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        long userId = ZenoraApiClient.getCurrentUserId();
        if (roomId <= 0L || userId <= 0L
                || UserConfig.getInstance(account).getClientUserId() != userId) {
            return;
        }

        Session session;
        synchronized (lock) {
            if (activeSession != null && activeSession.account == account
                    && activeSession.userId == userId
                    && activeSession.dialogId == dialogId && activeSession.roomId == roomId
                    && activeSession.classGuid == classGuid) {
                session = activeSession;
                if (session.cursor < 0L) {
                    long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
                    if (latest >= 0L) session.cursor = latest;
                }
            } else {
                stopLocked(activeSession);
                session = new Session(account, userId, dialogId, roomId, classGuid);
                activeSession = session;
                long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
                if (latest >= 0L) session.cursor = latest;
                FileLog.d("[ZenoraChat] Live sync attached; dialog=" + dialogId
                        + ", room=" + roomId + ", cursor=" + session.cursor);
            }
        }
        ensureMutationCursorLoaded(session);
        synchronized (lock) {
            if (isCurrentLocked(session)) scheduleLocked(session, 0L);
        }
    }

    /** Called after initial native history has been applied. */
    public static void onHistoryApplied(int account, long dialogId, long roomId, int classGuid) {
        Session session;
        synchronized (lock) {
            session = activeSession;
            if (!isCurrentLocked(session) || session.account != account
                    || session.dialogId != dialogId || session.roomId != roomId
                    || session.classGuid != classGuid) {
                return;
            }
            if (session.cursor < 0L) {
                long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
                session.cursor = Math.max(0L, latest);
            }
        }
        ensureMutationCursorLoaded(session);
        synchronized (lock) {
            if (isCurrentLocked(session)) scheduleLocked(session, 0L);
        }
    }

    public static void onChatPaused(int account, long dialogId, int classGuid) {
        synchronized (lock) {
            Session session = activeSession;
            if (session != null && session.account == account
                    && session.dialogId == dialogId && session.classGuid == classGuid) {
                stopLocked(session);
                activeSession = null;
                FileLog.d("[ZenoraChat] Live sync detached; dialog=" + dialogId);
            }
        }
    }

    private static void ensureMutationCursorLoaded(Session session) {
        synchronized (lock) {
            if (!isCurrentLocked(session) || session.eventCursor >= 0L
                    || session.eventCursorLoading) return;
            if (ZenoraApiClient.getCurrentUserId() != session.userId
                    || UserConfig.getInstance(session.account).getClientUserId() != session.userId) {
                stopLocked(session);
                activeSession = null;
                return;
            }
            session.eventCursorLoading = true;
        }
        MessagesStorage.getInstance(session.account).loadZenoraMessageMutationCursor(
                session.roomId, session.dialogId, session.userId, (cursor, error) -> {
                    long retryDelay = 0L;
                    synchronized (lock) {
                        if (!isCurrentLocked(session)) return;
                        session.eventCursorLoading = false;
                        if (error == null && cursor >= 0L) {
                            session.eventCursor = cursor;
                            session.failures = 0;
                        } else {
                            session.failures = Math.min(session.failures + 1, 8);
                            retryDelay = Math.min(MAX_BACKOFF_MS,
                                    POLL_INTERVAL_MS << Math.min(4, session.failures - 1));
                        }
                    }
                    if (error == null) {
                        synchronized (lock) {
                            if (isCurrentLocked(session)) scheduleLocked(session, 0L);
                        }
                    } else {
                        FileLog.w("[ZenoraChat] Mutation cursor load failed; room=" + session.roomId
                                + ", reason=" + error.getMessage());
                        final long delay = retryDelay;
                        mainHandler.postDelayed(() -> ensureMutationCursorLoaded(session), delay);
                    }
                });
    }

    private static void stopLocked(Session session) {
        if (session == null) return;
        session.active = false;
        mainHandler.removeCallbacks(session.pollRunnable);
        session.inFlight = false;
        session.eventCursorLoading = false;
    }

    private static boolean isCurrentLocked(Session session) {
        return session != null && session.active && activeSession == session;
    }

    private static void scheduleLocked(Session session, long delayMs) {
        if (!isCurrentLocked(session) || session.inFlight
                || session.cursor < 0L || session.eventCursor < 0L) return;
        mainHandler.removeCallbacks(session.pollRunnable);
        mainHandler.postDelayed(session.pollRunnable, Math.max(0L, delayMs));
    }

    private static void poll(Session session) {
        final long requestedMessageCursor;
        final long requestedEventCursor;
        synchronized (lock) {
            if (!isCurrentLocked(session) || session.inFlight
                    || session.cursor < 0L || session.eventCursor < 0L) return;
            if (!ZenoraChatController.isZenoraEnabled()
                    || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId
                    || ZenoraApiClient.getCurrentUserId() != session.userId
                    || UserConfig.getInstance(session.account).getClientUserId() != session.userId) {
                stopLocked(session);
                activeSession = null;
                return;
            }
            session.inFlight = true;
            requestedMessageCursor = session.cursor;
            requestedEventCursor = session.eventCursor;
        }
        ZenoraApiClient.fetchBasicTextMessagesAfter(session.roomId, requestedMessageCursor,
                requestedEventCursor, PAGE_SIZE, session.userId,
                (response, error) -> handleResponse(session, requestedMessageCursor,
                        requestedEventCursor, response, error));
    }

    private static void handleResponse(Session session, long requestedMessageCursor,
                                       long requestedEventCursor, JSONObject response,
                                       Exception error) {
        synchronized (lock) {
            if (!isCurrentLocked(session)) return;
            if (!ZenoraChatController.isZenoraEnabled()
                    || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId
                    || ZenoraApiClient.getCurrentUserId() != session.userId) {
                stopLocked(session);
                activeSession = null;
                return;
            }
        }
        if (error != null || response == null || !response.optBoolean("ok", false)) {
            fail(session, error != null ? error : new IllegalStateException("Invalid live-sync response"));
            return;
        }

        JSONArray messages = response.optJSONArray("messages");
        JSONObject pagination = response.optJSONObject("pagination");
        if (messages == null || pagination == null || messages.length() > PAGE_SIZE
                || pagination.optInt("returned", -1) != messages.length()
                || !"asc".equals(pagination.optString("order", ""))
                || !"after_id".equals(pagination.optString("direction", ""))) {
            fail(session, new IllegalStateException("Malformed live-message page metadata"));
            return;
        }

        boolean hasMoreMessages = pagination.optBoolean("has_more_newer", false);
        long appliedCursor = requestedMessageCursor;
        if (messages.length() == 0) {
            if (hasMoreMessages || !pagination.isNull("next_after_id")) {
                fail(session, new IllegalStateException("Empty live-message page has an invalid cursor"));
                return;
            }
        } else {
            long previousId = requestedMessageCursor;
            long lastId = 0L;
            HashSet<Long> pageIds = new HashSet<>();
            HashSet<String> pageClientMessageIds = new HashSet<>();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null || row.optLong("room_id", 0L) != session.roomId || row.isNull("text")) {
                    fail(session, new IllegalStateException("Live message does not belong to the active room"));
                    return;
                }
                long id = row.optLong("message_id", 0L);
                long senderId = row.optLong("sender_id", 0L);
                String body = row.optString("text", "");
                String clientMessageId = row.isNull("client_message_id")
                        ? "" : row.optString("client_message_id", "");
                if (id <= previousId || id > Integer.MAX_VALUE || !pageIds.add(id)
                        || senderId <= 0L || senderId > Integer.MAX_VALUE
                        || TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())
                        || ZenoraChatController.parseHistoryDate(row.optString("created_at", "")) <= 0
                        || !TextUtils.isEmpty(clientMessageId)
                        && (!clientMessageId.matches("[A-Za-z0-9._:-]{8,64}")
                        || !pageClientMessageIds.add(clientMessageId))) {
                    fail(session, new IllegalStateException("Live message failed validation"));
                    return;
                }
                previousId = id;
                lastId = id;
            }
            long responseCursor = pagination.optLong("next_after_id", -1L);
            if (responseCursor != lastId || (hasMoreMessages && messages.length() != PAGE_SIZE)) {
                fail(session, new IllegalStateException("Live-message cursor does not match the returned page"));
                return;
            }
            appliedCursor = ZenoraHistoryController.applyLiveTextMessages(
                    session.account, session.dialogId, session.roomId, messages, requestedMessageCursor);
            if (appliedCursor != lastId) {
                fail(session, new IllegalStateException("Native message application did not commit the page"));
                return;
            }
        }

        synchronized (lock) {
            if (!isCurrentLocked(session) || session.cursor != requestedMessageCursor) return;
            session.cursor = appliedCursor;
        }

        final MutationPage page;
        try {
            page = validateMutationEventPage(
                    response.optJSONObject("mutation_events_page"), session.roomId,
                    requestedEventCursor, PAGE_SIZE);
        } catch (Exception invalidPage) {
            fail(session, invalidPage);
            return;
        }

        final HashMap<Integer, ArrayList<TLRPC.MessageEntity>> editEntitiesByMessageId;
        try {
            editEntitiesByMessageId = prepareEditedMessageEntities(session, page.events);
        } catch (Exception errorPreparingEntities) {
            fail(session, errorPreparingEntities);
            return;
        }
        MessagesStorage.getInstance(session.account).applyZenoraMessageMutationEvents(
                session.roomId, session.dialogId, session.userId, requestedEventCursor,
                page.nextCursor, page.events, editEntitiesByMessageId,
                (committedCursor, editedMessages, deletedIds, suppressedEditIds, applyError) -> {
                    if (applyError != null || committedCursor != page.nextCursor) {
                        fail(session, applyError != null ? applyError
                                : new IllegalStateException("Mutation cursor was not committed"));
                        return;
                    }
                    synchronized (lock) {
                        if (!isCurrentLocked(session)
                                || ZenoraApiClient.getCurrentUserId() != session.userId
                                || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId) {
                            return;
                        }
                    }

                    applyEditedMessages(session, page.events, editedMessages, suppressedEditIds);
                    if (deletedIds != null && !deletedIds.isEmpty()) {
                        TLRPC.Dialog dialogBeforeDelete = MessagesController.getInstance(session.account)
                                .getDialog(session.dialogId);
                        int previousTopMessageId = dialogBeforeDelete == null ? 0 : dialogBeforeDelete.top_message;
                        boolean deletedPreviousTop = deletedIds.contains(previousTopMessageId);
                        boolean removed = MessagesController.getInstance(session.account)
                                .applyZenoraMessageDeletionLocally(session.dialogId, deletedIds);
                        if (!removed) {
                            FileLog.w("[ZenoraChat] Mutation event deletion already absent from active cache; dialog="
                                    + session.dialogId);
                        } else if (deletedPreviousTop) {
                            // The replacement can be older than the live message cursor
                            // and absent from SQLite. Fetch only the current server tail.
                            refreshLastServerPreview(session, appliedCursor, deletedIds);
                        }
                    }

                    synchronized (lock) {
                        if (!isCurrentLocked(session) || session.eventCursor != requestedEventCursor) return;
                        session.eventCursor = committedCursor;
                        session.failures = 0;
                        session.inFlight = false;
                        scheduleLocked(session, hasMoreMessages || page.hasMore ? 0L : POLL_INTERVAL_MS);
                    }
                });
    }

    private static void refreshLastServerPreview(Session session, long expectedMessageCursor,
                                                   ArrayList<Integer> deletedMessageIds) {
        final long expectedUserId = session.userId;
        ZenoraApiClient.fetchBasicTextHistoryForUser(session.roomId, 1, expectedUserId, (response, error) -> {
            synchronized (lock) {
                if (!isCurrentLocked(session) || session.cursor != expectedMessageCursor
                        || ZenoraApiClient.getCurrentUserId() != expectedUserId
                        || UserConfig.getInstance(session.account).getClientUserId() != expectedUserId
                        || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId) {
                    return;
                }
            }
            if (error != null || response == null || !response.optBoolean("ok", false)) {
                FileLog.w("[ZenoraChat] Could not refresh server last-message preview; dialog="
                        + session.dialogId + ", reason="
                        + (error == null ? "invalid response" : error.getMessage()));
                return;
            }
            JSONArray messages = response.optJSONArray("messages");
            JSONObject pagination = response.optJSONObject("pagination");
            if (messages == null || messages.length() > 1
                    || !ZenoraHistoryController.applyLatestServerDialogPreview(
                    session.account, session.dialogId, session.roomId,
                    messages, pagination, deletedMessageIds)) {
                FileLog.w("[ZenoraChat] Server replacement message failed validation; dialog="
                        + session.dialogId);
            }
        });
    }

    private static MutationPage validateMutationEventPage(JSONObject page, long roomId,
                                                           long requestedCursor, int limit) {
        if (page == null || !page.optBoolean("ok", false)
                || page.optLong("room_id", 0L) != roomId
                || page.optLong("after_event_id", -1L) != requestedCursor) {
            throw new IllegalStateException("Mutation event page belongs to another account or room cursor");
        }
        JSONArray events = page.optJSONArray("events");
        int scannedCount = page.optInt("scanned_count", -1);
        long nextCursor = page.optLong("next_after_event_id", -1L);
        boolean hasMore = page.optBoolean("has_more", false);
        if (events == null || events.length() > limit || scannedCount < events.length()
                || scannedCount < 0 || scannedCount > limit || nextCursor < requestedCursor
                || hasMore != (scannedCount == limit)
                || (scannedCount == 0 && nextCursor != requestedCursor)
                || (scannedCount > 0 && nextCursor <= requestedCursor)) {
            throw new IllegalStateException("Mutation event cursor metadata is invalid");
        }

        long previousId = requestedCursor;
        HashSet<Long> eventIds = new HashSet<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || event.optLong("room_id", 0L) != roomId) {
                throw new IllegalStateException("Mutation event belongs to another room");
            }
            long eventId = event.optLong("event_id", 0L);
            long messageId = event.optLong("message_id", 0L);
            long actorId = event.optLong("actor_user_id", 0L);
            String type = event.optString("event_type", "");
            JSONObject payload = event.optJSONObject("payload");
            if (eventId <= previousId || eventId > nextCursor || !eventIds.add(eventId)
                    || messageId <= 0L || messageId > Integer.MAX_VALUE || actorId <= 0L
                    || payload == null
                    || !("message.edited".equals(type)
                    || "message.deleted_for_me".equals(type)
                    || "message.deleted_for_all".equals(type))) {
                throw new IllegalStateException("Mutation event failed identity or type validation");
            }
            if ("message.edited".equals(type)) {
                String body = payload.optString("text", "");
                String editedAt = payload.optString("edited_at", "");
                long version = payload.optLong("edit_version", 0L);
                if (TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())
                        || TextUtils.isEmpty(editedAt)
                        || ZenoraChatController.parseHistoryDate(editedAt) <= 0
                        || version <= 0L || version > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Mutation edit payload is invalid");
                }
            }
            previousId = eventId;
        }
        if (events.length() > 0 && events.optJSONObject(events.length() - 1).optLong("event_id", 0L) > nextCursor) {
            throw new IllegalStateException("Mutation event page cursor moved backwards");
        }
        return new MutationPage(events, nextCursor, hasMore);
    }

    /** Parse entities on the UI thread so body, entities, edit metadata and cursor commit together. */
    private static HashMap<Integer, ArrayList<TLRPC.MessageEntity>> prepareEditedMessageEntities(
            Session session, JSONArray events) {
        HashMap<Integer, ArrayList<TLRPC.MessageEntity>> result = new HashMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"message.edited".equals(event.optString("event_type", ""))) continue;
            int messageId = (int) event.optLong("message_id", 0L);
            JSONObject payload = event.optJSONObject("payload");
            String body = payload == null ? "" : payload.optString("text", "");
            if (messageId <= 0 || TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())) {
                throw new IllegalStateException("Cannot prepare entities for invalid edited message");
            }
            ArrayList<TLRPC.MessageEntity> parsed = MediaDataController.getInstance(session.account)
                    .getEntities(new CharSequence[]{body}, true);
            result.put(messageId, parsed == null ? new ArrayList<>() : new ArrayList<>(parsed));
        }
        return result;
    }

    private static void applyEditedMessages(Session session, JSONArray events,
                                            ArrayList<TLRPC.Message> messages,
                                            ArrayList<Integer> suppressedEditMessageIds) {
        HashMap<String, JSONObject> metadata = new HashMap<>();
        HashSet<Integer> storedIds = new HashSet<>();
        if (suppressedEditMessageIds != null) storedIds.addAll(suppressedEditMessageIds);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"message.edited".equals(event.optString("event_type", ""))) continue;
            JSONObject payload = event.optJSONObject("payload");
            if (payload == null) continue;
            String key = event.optLong("message_id", 0L) + ":" + payload.optLong("edit_version", 0L);
            metadata.put(key, payload);
        }
        if (messages != null) {
            for (TLRPC.Message message : messages) {
                if (message == null || message.id <= 0) continue;
                if (suppressedEditMessageIds != null && suppressedEditMessageIds.contains(message.id)) {
                    storedIds.add(message.id);
                    continue;
                }
                storedIds.add(message.id);
                String key = message.id + ":" + message.zenoraEditVersion;
                JSONObject payload = metadata.get(key);
                if (payload == null) continue;
                String body = payload.optString("text", message.message);
                String editedAt = payload.optString("edited_at", "");
                ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(session.account)
                        .getEntities(new CharSequence[]{body}, true);
                MessageObject object = new MessageObject(session.account, message, true, true);
                boolean updated = ZenoraHistoryController.applyEditedMessage(
                        session.account, session.dialogId, session.roomId, object,
                        body, entities, editedAt, message.zenoraEditVersion);
                if (!updated) FileLog.w("[ZenoraChat] Stored edited message could not refresh active UI; id=" + message.id);
            }
        }
        // A reply parent can be cached as a lightweight preview without its full
        // row in messages_v2; keep that preview and all dependent replies current.
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"message.edited".equals(event.optString("event_type", ""))) continue;
            int messageId = (int) event.optLong("message_id", 0L);
            if (messageId <= 0 || storedIds.contains(messageId)) continue;
            JSONObject payload = event.optJSONObject("payload");
            if (payload == null) continue;
            ZenoraHistoryController.applyEditedMessageToCachedReplyPreview(
                    session.account, session.dialogId, session.roomId, messageId,
                    payload.optString("text", ""), payload.optString("edited_at", ""),
                    payload.optLong("edit_version", 0L));
        }
    }

    private static void fail(Session session, Exception error) {
        synchronized (lock) {
            if (!isCurrentLocked(session)) return;
            session.inFlight = false;
            session.failures = Math.min(session.failures + 1, 8);
            long delay = Math.min(MAX_BACKOFF_MS,
                    POLL_INTERVAL_MS << Math.min(4, session.failures - 1));
            FileLog.w("[ZenoraChat] Live sync retry scheduled; dialog=" + session.dialogId
                    + ", messageCursor=" + session.cursor + ", eventCursor=" + session.eventCursor
                    + ", delayMs=" + delay
                    + ", reason=" + (error == null ? "unknown" : error.getMessage()));
            scheduleLocked(session, delay);
        }
    }
}
