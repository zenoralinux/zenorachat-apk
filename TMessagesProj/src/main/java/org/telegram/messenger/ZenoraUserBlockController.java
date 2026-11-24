package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Account-scoped client state for Zenora's directional user-block relationship.
 * The server remains authoritative; persisted values are presentation-only cache.
 */
public final class ZenoraUserBlockController {
    public static final class State {
        public final long targetUserId;
        public final int blockedByMe;
        public final int blockedMe;
        public final int isBlocked;
        public final boolean canSendDm;
        public final boolean canReceiveDm;

        State(long targetUserId, JSONObject json) {
            this.targetUserId = targetUserId;
            blockedByMe = json.optInt("blocked_by_me", 0) == 1 ? 1 : 0;
            blockedMe = json.optInt("blocked_me", 0) == 1 ? 1 : 0;
            isBlocked = blockedByMe == 1 || blockedMe == 1 ? 1 : 0;
            canSendDm = json.optBoolean("can_send_dm", isBlocked == 0);
            canReceiveDm = json.optBoolean("can_receive_dm", isBlocked == 0);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("blocked_by_me", blockedByMe);
                json.put("blocked_me", blockedMe);
                json.put("is_blocked", isBlocked);
                json.put("can_send_dm", canSendDm);
                json.put("can_receive_dm", canReceiveDm);
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    public interface StateCallback {
        void onComplete(State state, Exception error);
    }

    public interface MutationCallback {
        void onComplete(State state, Exception error);
    }

    public interface SyncCallback {
        void onComplete(boolean changed, Exception error);
    }

    private static final String PREFS = "zenora_user_block_cache_v1";
    private static final Object LOCK = new Object();
    private static final Map<Long, Map<Long, State>> CACHE = new HashMap<>();
    private static final Map<String, Long> SEQUENCES = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Map<Long, Long> SYNCING = new HashMap<>();

    private ZenoraUserBlockController() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String stateKey(long actorId, long targetId) {
        return "state_" + actorId + "_" + targetId;
    }

    private static String cursorKey(long actorId) {
        return "events_cursor_" + actorId;
    }

    public static State getCachedState(long targetUserId) {
        long actorId = ZenoraApiClient.getAuthenticatedUserId();
        if (actorId <= 0 || targetUserId <= 0) return null;
        synchronized (LOCK) {
            Map<Long, State> accountCache = CACHE.get(actorId);
            State cached = accountCache == null ? null : accountCache.get(targetUserId);
            if (cached != null) return cached;
        }
        try {
            String raw = prefs().getString(stateKey(actorId, targetUserId), "");
            if (raw.isEmpty()) return null;
            State cached = new State(targetUserId, new JSONObject(raw));
            synchronized (LOCK) {
                CACHE.computeIfAbsent(actorId, ignored -> new HashMap<>()).put(targetUserId, cached);
            }
            return cached;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long nextSequence(long actorId, long targetId) {
        String key = actorId + ":" + targetId;
        synchronized (LOCK) {
            long next = SEQUENCES.containsKey(key) ? SEQUENCES.get(key) + 1 : 1;
            SEQUENCES.put(key, next);
            return next;
        }
    }

    private static boolean isLatest(long actorId, long targetId, long sequence) {
        synchronized (LOCK) {
            Long current = SEQUENCES.get(actorId + ":" + targetId);
            return current != null && current == sequence;
        }
    }

    private static void storeState(long actorId, long actorGeneration, State state) {
        if (actorId <= 0 || state == null
                || ZenoraApiClient.getSessionGeneration() != actorGeneration
                || ZenoraApiClient.getAuthenticatedUserId() != actorId) return;
        State previous = getCachedState(state.targetUserId);
        boolean changed = previous == null
                || previous.blockedByMe != state.blockedByMe
                || previous.blockedMe != state.blockedMe
                || previous.canSendDm != state.canSendDm
                || previous.canReceiveDm != state.canReceiveDm;
        synchronized (LOCK) {
            CACHE.computeIfAbsent(actorId, ignored -> new HashMap<>()).put(state.targetUserId, state);
        }
        prefs().edit().putString(stateKey(actorId, state.targetUserId), state.toJson().toString()).apply();
        if (changed && ZenoraApiClient.getSessionGeneration() == actorGeneration
                && ZenoraApiClient.getAuthenticatedUserId() == actorId) {
            NotificationCenter.getInstance(UserConfig.selectedAccount)
                    .postNotificationName(NotificationCenter.blockedUsersDidLoad);
        }
    }

    private static State parseState(JSONObject response, long targetId) throws Exception {
        if (response == null) throw new IllegalStateException("EMPTY_BLOCK_RESPONSE");
        JSONObject data = response.optJSONObject("data");
        JSONObject state = data == null ? null : data.optJSONObject("state");
        if (state == null) state = response.optJSONObject("state");
        long returnedTarget = data == null
                ? response.optLong("target_user_id", 0)
                : data.optLong("target_user_id", response.optLong("target_user_id", 0));
        if (state == null || (returnedTarget > 0 && returnedTarget != targetId)) {
            throw new IllegalStateException("INVALID_BLOCK_RESPONSE");
        }
        return new State(targetId, state);
    }

    /** Reconcile a profile against the server; a previous response cannot overwrite a newer request. */
    public static void refreshStatus(long targetUserId, StateCallback callback) {
        final long actorId = ZenoraApiClient.getAuthenticatedUserId();
        final long actorGeneration = ZenoraApiClient.getSessionGeneration();
        if (actorId <= 0 || targetUserId <= 0) {
            if (callback != null) callback.onComplete(getCachedState(targetUserId),
                    new IllegalStateException("ZENORA_SESSION_REQUIRED"));
            return;
        }
        final long sequence = nextSequence(actorId, targetUserId);
        ZenoraApiClient.getUserBlockStatus(targetUserId, (response, error) -> {
            if (ZenoraApiClient.getAuthenticatedUserId() != actorId
                    || ZenoraApiClient.getSessionGeneration() != actorGeneration) {
                if (callback != null) callback.onComplete(null, new IllegalStateException("ZENORA_SESSION_CHANGED"));
                return;
            }
            if (error != null) {
                if (callback != null) callback.onComplete(getCachedState(targetUserId), error);
                return;
            }
            try {
                State state = parseState(response, targetUserId);
                if (isLatest(actorId, targetUserId, sequence)) storeState(actorId, actorGeneration, state);
                if (callback != null) callback.onComplete(getCachedState(targetUserId), null);
            } catch (Exception parseError) {
                if (callback != null) callback.onComplete(getCachedState(targetUserId), parseError);
            }
        });
    }

    /**
     * Non-optimistic, serialized mutation. Ambiguous failures are reconciled with a fresh
     * standalone status read before the caller is told whether the operation succeeded.
     */
    public static void setBlocked(long targetUserId, boolean blocked, MutationCallback callback) {
        final long actorId = ZenoraApiClient.getAuthenticatedUserId();
        final long actorGeneration = ZenoraApiClient.getSessionGeneration();
        if (actorId <= 0 || targetUserId <= 0) {
            if (callback != null) callback.onComplete(getCachedState(targetUserId),
                    new IllegalStateException("ZENORA_SESSION_REQUIRED"));
            return;
        }
        final String pendingKey = actorId + ":" + targetUserId;
        final long sequence = nextSequence(actorId, targetUserId);
        synchronized (LOCK) {
            if (!PENDING.add(pendingKey)) {
                if (callback != null) callback.onComplete(getCachedState(targetUserId),
                        new IllegalStateException("BLOCK_OPERATION_PENDING"));
                return;
            }
        }

        ZenoraApiClient.setUserBlocked(targetUserId, blocked, (response, error) -> {
            if (ZenoraApiClient.getAuthenticatedUserId() != actorId
                    || ZenoraApiClient.getSessionGeneration() != actorGeneration) {
                synchronized (LOCK) { PENDING.remove(pendingKey); }
                if (callback != null) callback.onComplete(null, new IllegalStateException("ZENORA_SESSION_CHANGED"));
                return;
            }
            if (error == null) {
                try {
                    State state = parseState(response, targetUserId);
                    if (state.blockedByMe != (blocked ? 1 : 0)) {
                        throw new IllegalStateException("BLOCK_STATE_NOT_CONFIRMED");
                    }
                    if (isLatest(actorId, targetUserId, sequence)) storeState(actorId, actorGeneration, state);
                    synchronized (LOCK) { PENDING.remove(pendingKey); }
                    if (callback != null) callback.onComplete(getCachedState(targetUserId), null);
                    return;
                } catch (Exception parseError) {
                    error = parseError;
                }
            }

            final Exception mutationError = error;
            // A timeout/500 may mean the server committed but the response was lost.
            // A fresh authoritative read safely resolves both ambiguous and ordinary failures.
            ZenoraApiClient.getUserBlockStatus(targetUserId, (statusResponse, statusError) -> {
                synchronized (LOCK) { PENDING.remove(pendingKey); }
                if (ZenoraApiClient.getAuthenticatedUserId() != actorId
                        || ZenoraApiClient.getSessionGeneration() != actorGeneration) {
                    if (callback != null) callback.onComplete(null, new IllegalStateException("ZENORA_SESSION_CHANGED"));
                    return;
                }
                if (statusError == null) {
                    try {
                        State state = parseState(statusResponse, targetUserId);
                        if (isLatest(actorId, targetUserId, sequence)) storeState(actorId, actorGeneration, state);
                        if (state.blockedByMe == (blocked ? 1 : 0)) {
                            if (callback != null) callback.onComplete(getCachedState(targetUserId), null);
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (callback != null) callback.onComplete(getCachedState(targetUserId),
                        mutationError != null ? mutationError : statusError);
            });
        });
    }

    /** Consume same-actor changes from Web/other devices without trusting event payloads as state. */
    public static void syncEvents(SyncCallback callback) {
        final long actorId = ZenoraApiClient.getAuthenticatedUserId();
        final long actorGeneration = ZenoraApiClient.getSessionGeneration();
        if (actorId <= 0) {
            if (callback != null) callback.onComplete(false, new IllegalStateException("ZENORA_SESSION_REQUIRED"));
            return;
        }
        synchronized (LOCK) {
            Long activeGeneration = SYNCING.get(actorId);
            if (activeGeneration != null && activeGeneration == actorGeneration) {
                if (callback != null) callback.onComplete(false, null);
                return;
            }
            SYNCING.put(actorId, actorGeneration);
        }
        syncEventsPage(actorId, actorGeneration, prefs().getLong(cursorKey(actorId), 0), false, callback);
    }

    private static void syncEventsPage(long actorId, long actorGeneration, long cursor,
                                       boolean anyChanged, SyncCallback callback) {
        ZenoraApiClient.getUserBlockEvents(cursor, 100, (response, error) -> {
            if (ZenoraApiClient.getAuthenticatedUserId() != actorId
                    || ZenoraApiClient.getSessionGeneration() != actorGeneration) {
                finishSync(actorId, actorGeneration);
                if (callback != null) callback.onComplete(anyChanged, new IllegalStateException("ZENORA_SESSION_CHANGED"));
                return;
            }
            if (error != null) {
                finishSync(actorId, actorGeneration);
                if (callback != null) callback.onComplete(anyChanged, error);
                return;
            }
            try {
                JSONArray events = response.optJSONArray("events");
                long nextCursor = response.optLong("next_cursor", -1);
                if (events == null || nextCursor < cursor) throw new IllegalStateException("INVALID_BLOCK_EVENT_PAGE");
                Set<Long> targets = new HashSet<>();
                long maxEventId = cursor;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.optJSONObject(i);
                    if (event == null) throw new IllegalStateException("INVALID_BLOCK_EVENT");
                    long eventId = event.optLong("event_id", 0);
                    long targetId = event.optLong("target_user_id", 0);
                    if (eventId <= 0 || targetId <= 0) throw new IllegalStateException("INVALID_BLOCK_EVENT");
                    if (eventId > cursor) {
                        maxEventId = Math.max(maxEventId, eventId);
                        targets.add(targetId);
                    }
                }
                if (nextCursor > maxEventId) throw new IllegalStateException("BLOCK_EVENT_CURSOR_SKIPPED");
                final long committedCursor = Math.max(cursor, maxEventId);
                final boolean changed = anyChanged || !targets.isEmpty();
                if (targets.isEmpty()) {
                    commitEventPage(actorId, actorGeneration, committedCursor, changed,
                            response.optBoolean("has_more", false), callback);
                    return;
                }
                final int[] remaining = { targets.size() };
                final boolean[] refreshFailed = { false };
                for (Long targetId : targets) {
                    refreshStatus(targetId, (state, statusError) -> {
                        if (statusError != null || state == null) refreshFailed[0] = true;
                        remaining[0]--;
                        if (remaining[0] == 0) {
                            if (refreshFailed[0]) {
                                finishSync(actorId, actorGeneration);
                                if (callback != null) callback.onComplete(anyChanged,
                                        new IllegalStateException("BLOCK_SNAPSHOT_REFRESH_FAILED"));
                            } else {
                                commitEventPage(actorId, actorGeneration, committedCursor, changed,
                                        response.optBoolean("has_more", false), callback);
                            }
                        }
                    });
                }
            } catch (Exception parseError) {
                finishSync(actorId, actorGeneration);
                if (callback != null) callback.onComplete(anyChanged, parseError);
            }
        });
    }

    private static void commitEventPage(long actorId, long actorGeneration, long cursor,
                                        boolean changed, boolean hasMore, SyncCallback callback) {
        if (ZenoraApiClient.getAuthenticatedUserId() != actorId
                || ZenoraApiClient.getSessionGeneration() != actorGeneration) {
            finishSync(actorId, actorGeneration);
            if (callback != null) callback.onComplete(changed, new IllegalStateException("ZENORA_SESSION_CHANGED"));
            return;
        }
        prefs().edit().putLong(cursorKey(actorId), cursor).apply();
        if (hasMore) {
            syncEventsPage(actorId, actorGeneration, cursor, changed, callback);
        } else {
            finishSync(actorId, actorGeneration);
            if (callback != null) callback.onComplete(changed, null);
        }
    }
    /** Called on explicit logout; actor-prefixed disk cache cannot bleed into another identity. */
    public static void onSessionCleared(long actorId) {
        if (actorId <= 0) return;
        synchronized (LOCK) {
            CACHE.remove(actorId);
            SYNCING.remove(actorId);
            PENDING.removeIf(key -> key.startsWith(actorId + ":"));
            SEQUENCES.keySet().removeIf(key -> key.startsWith(actorId + ":"));
        }
    }
    private static void finishSync(long actorId, long actorGeneration) {
        synchronized (LOCK) {
            Long activeGeneration = SYNCING.get(actorId);
            if (activeGeneration != null && activeGeneration == actorGeneration) SYNCING.remove(actorId);
        }
    }
}
