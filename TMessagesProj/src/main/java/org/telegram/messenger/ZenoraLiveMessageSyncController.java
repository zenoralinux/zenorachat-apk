package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;

/**
 * Lifecycle-bound polling for new Zenora plain-text messages in the visible chat.
 * One request may be outstanding; responses from an old chat are ignored.
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
        final long dialogId;
        final long roomId;
        final int classGuid;
        final Runnable pollRunnable;
        long cursor = -1L;
        int failures;
        boolean inFlight;
        boolean active = true;

        Session(int account, long dialogId, long roomId, int classGuid) {
            this.account = account;
            this.dialogId = dialogId;
            this.roomId = roomId;
            this.classGuid = classGuid;
            pollRunnable = () -> poll(this);
        }
    }

    public static void onChatResumed(int account, long dialogId, int classGuid) {
        if (dialogId == 0 || !ZenoraChatController.isZenoraEnabled()
                || !ZenoraChatController.isZenoraDialog(account, dialogId)) {
            return;
        }
        long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0L) return;

        synchronized (lock) {
            if (activeSession != null && activeSession.account == account
                    && activeSession.dialogId == dialogId && activeSession.roomId == roomId
                    && activeSession.classGuid == classGuid) {
                if (activeSession.cursor < 0L) {
                    long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
                    if (latest >= 0L) activeSession.cursor = latest;
                }
                if (activeSession.cursor >= 0L) scheduleLocked(activeSession, 0L);
                return;
            }
            stopLocked(activeSession);
            Session session = new Session(account, dialogId, roomId, classGuid);
            activeSession = session;
            long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
            if (latest >= 0L) {
                session.cursor = latest;
                scheduleLocked(session, 0L);
            }
            FileLog.d("[ZenoraChat] Live sync attached; dialog=" + dialogId
                    + ", room=" + roomId + ", cursor=" + session.cursor);
        }
    }

    /** Called after initial native history has been applied. */
    public static void onHistoryApplied(int account, long dialogId, long roomId, int classGuid) {
        synchronized (lock) {
            Session session = activeSession;
            if (!isCurrentLocked(session) || session.account != account
                    || session.dialogId != dialogId || session.roomId != roomId
                    || session.classGuid != classGuid) {
                return;
            }
            if (session.cursor < 0L) {
                long latest = ZenoraHistoryController.getLatestMessageId(account, dialogId, roomId);
                // Recover from cursor zero if the initial history request failed.
                session.cursor = Math.max(0L, latest);
            }
            scheduleLocked(session, 0L);
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

    private static void stopLocked(Session session) {
        if (session == null) return;
        session.active = false;
        mainHandler.removeCallbacks(session.pollRunnable);
        session.inFlight = false;
    }

    private static boolean isCurrentLocked(Session session) {
        return session != null && session.active && activeSession == session;
    }

    private static void scheduleLocked(Session session, long delayMs) {
        if (!isCurrentLocked(session) || session.inFlight) return;
        mainHandler.removeCallbacks(session.pollRunnable);
        mainHandler.postDelayed(session.pollRunnable, Math.max(0L, delayMs));
    }

    private static void poll(Session session) {
        final long requestedCursor;
        synchronized (lock) {
            if (!isCurrentLocked(session) || session.inFlight || session.cursor < 0L) return;
            if (!ZenoraChatController.isZenoraEnabled()
                    || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId) {
                stopLocked(session);
                activeSession = null;
                return;
            }
            session.inFlight = true;
            requestedCursor = session.cursor;
        }
        ZenoraApiClient.fetchBasicTextMessagesAfter(session.roomId, requestedCursor, PAGE_SIZE,
                (response, error) -> handleResponse(session, requestedCursor, response, error));
    }

    private static void handleResponse(Session session, long requestedCursor,
                                       JSONObject response, Exception error) {
        synchronized (lock) {
            if (!isCurrentLocked(session)) return;
            if (!ZenoraChatController.isZenoraEnabled()
                    || ZenoraChatController.getRoomIdForDialog(session.account, session.dialogId) != session.roomId) {
                stopLocked(session);
                activeSession = null;
                return;
            }
        }
        if (error != null || response == null || !response.optBoolean("ok", false)) {
            fail(session, error != null ? error : new IllegalStateException("Invalid live-message response"));
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

        boolean hasMore = pagination.optBoolean("has_more_newer", false);
        if (messages.length() == 0) {
            if (hasMore || !pagination.isNull("next_after_id")) {
                fail(session, new IllegalStateException("Empty live-message page has an invalid cursor"));
                return;
            }
            synchronized (lock) {
                if (!isCurrentLocked(session)) return;
                session.inFlight = false;
                session.failures = 0;
                scheduleLocked(session, POLL_INTERVAL_MS);
            }
            return;
        }

        long previousId = requestedCursor;
        long lastId = 0L;
        HashSet<Long> pageIds = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || row.optLong("room_id", 0L) != session.roomId || row.isNull("text")) {
                fail(session, new IllegalStateException("Live message does not belong to the active room"));
                return;
            }
            long id = row.optLong("message_id", 0L);
            long senderId = row.optLong("sender_id", 0L);
            String body = row.optString("text", "");
            if (id <= previousId || id > Integer.MAX_VALUE || !pageIds.add(id)
                    || senderId <= 0L || senderId > Integer.MAX_VALUE
                    || TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())
                    || ZenoraChatController.parseHistoryDate(row.optString("created_at", "")) <= 0) {
                fail(session, new IllegalStateException("Live message failed validation"));
                return;
            }
            previousId = id;
            lastId = id;
        }
        long responseCursor = pagination.optLong("next_after_id", -1L);
        if (responseCursor != lastId || (hasMore && messages.length() != PAGE_SIZE)) {
            fail(session, new IllegalStateException("Live-message cursor does not match the returned page"));
            return;
        }

        long appliedCursor = ZenoraHistoryController.applyLiveTextMessages(
                session.account, session.dialogId, session.roomId, messages, requestedCursor);
        if (appliedCursor != lastId) {
            fail(session, new IllegalStateException("Native message application did not commit the page"));
            return;
        }

        synchronized (lock) {
            if (!isCurrentLocked(session) || session.cursor != requestedCursor) return;
            session.cursor = appliedCursor;
            session.failures = 0;
            session.inFlight = false;
            scheduleLocked(session, hasMore ? 0L : POLL_INTERVAL_MS);
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
                    + ", cursor=" + session.cursor + ", delayMs=" + delay
                    + ", reason=" + (error == null ? "unknown" : error.getMessage()));
            scheduleLocked(session, delay);
        }
    }
}
