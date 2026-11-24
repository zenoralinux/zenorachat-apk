package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Loads the first bounded Zenora text-history page and hands it to Telegram's
 * existing MessagesController/ChatActivity pipeline. This class adds no UI.
 */
public final class ZenoraHistoryController {
    private static final Object lock = new Object();
    private static final Map<Integer, Map<Long, Snapshot>> cache = new HashMap<>();
    private static final Map<Integer, Map<Long, ArrayList<Target>>> inFlight = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000L;
    private static final int CACHE_MAX_DIALOGS = 24;

    private ZenoraHistoryController() {
    }

    private static final class Target {
        final int classGuid;
        final int loadIndex;

        Target(int classGuid, int loadIndex) {
            this.classGuid = classGuid;
            this.loadIndex = loadIndex;
        }
    }

    private static final class Snapshot {
        final long roomId;
        final JSONArray messages;
        final JSONArray users;
        final long fetchedAt;

        Snapshot(long roomId, JSONArray messages, JSONArray users) {
            this.roomId = roomId;
            this.messages = messages;
            this.users = users;
            this.fetchedAt = SystemClock.elapsedRealtime();
        }
    }

    public static void clearAccount(int account) {
        synchronized (lock) {
            cache.remove(account);
            inFlight.remove(account);
        }
    }

    public static void loadInitialHistory(int account, long dialogId, int classGuid,
                                          int loadIndex, int requestedLimit) {
        final Target target = new Target(classGuid, loadIndex);
        final int limit = Math.max(1, Math.min(100, requestedLimit));
        final long roomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        if (roomId <= 0 || !ZenoraChatController.isZenoraEnabled()) {
            publish(account, dialogId, 0, target, null,
                    new IllegalStateException("Zenora dialog is no longer available"), limit);
            return;
        }

        Snapshot cachedSnapshot;
        boolean startRequest = false;
        synchronized (lock) {
            Map<Long, Snapshot> accountCache = cache.get(account);
            Snapshot candidate = accountCache == null ? null : accountCache.get(dialogId);
            if (candidate != null && candidate.roomId == roomId
                    && SystemClock.elapsedRealtime() - candidate.fetchedAt <= CACHE_TTL_MS) {
                cachedSnapshot = candidate;
            } else {
                if (candidate != null && accountCache != null) accountCache.remove(dialogId);
                cachedSnapshot = null;
                Map<Long, ArrayList<Target>> accountRequests =
                        inFlight.computeIfAbsent(account, ignored -> new HashMap<>());
                ArrayList<Target> waiters = accountRequests.get(dialogId);
                if (waiters == null) {
                    waiters = new ArrayList<>();
                    accountRequests.put(dialogId, waiters);
                    startRequest = true;
                }
                waiters.add(target);
            }
        }

        if (cachedSnapshot != null) {
            publish(account, dialogId, roomId, target, cachedSnapshot, null, limit);
            return;
        }
        if (!startRequest) return;

        ZenoraApiClient.fetchBasicTextHistory(roomId, limit, (response, error) -> {
            if (error != null || response == null) {
                complete(account, dialogId, roomId, null,
                        error != null ? error : new IllegalStateException("History response is empty"),
                        limit);
                return;
            }
            JSONArray messages = response.optJSONArray("messages");
            if (messages == null) {
                complete(account, dialogId, roomId, null,
                        new IllegalStateException("History response has no messages array"), limit);
                return;
            }

            // Names are fetched separately because the stable text-history contract
            // intentionally returns sender IDs only. Failure here does not lose messages.
            ZenoraApiClient.fetchUsers((usersResponse, usersError) -> {
                JSONArray users = usersResponse == null ? null : usersResponse.optJSONArray("users");
                if (usersError != null) {
                    FileLog.w("[ZenoraChat] Sender directory unavailable: " + usersError.getMessage());
                }
                complete(account, dialogId, roomId, new Snapshot(roomId, messages, users), null, limit);
            });
        });
    }

    private static void complete(int account, long dialogId, long roomId, Snapshot snapshot,
                                 Exception error, int limit) {
        long currentRoomId = ZenoraChatController.getRoomIdForDialog(account, dialogId);
        boolean stillCurrent = currentRoomId == roomId
                && ZenoraChatController.isZenoraEnabled();
        ArrayList<Target> targets;
        synchronized (lock) {
            Map<Long, ArrayList<Target>> accountRequests = inFlight.get(account);
            targets = accountRequests == null ? null : accountRequests.remove(dialogId);
            if (accountRequests != null && accountRequests.isEmpty()) inFlight.remove(account);
            if (snapshot != null && stillCurrent) {
                Map<Long, Snapshot> accountCache =
                        cache.computeIfAbsent(account, ignored -> new HashMap<>());
                accountCache.put(dialogId, snapshot);
                while (accountCache.size() > CACHE_MAX_DIALOGS) {
                    Long firstKey = accountCache.keySet().iterator().next();
                    accountCache.remove(firstKey);
                }
            }
        }
        if (targets == null) return;

        for (Target target : targets) {
            if (!stillCurrent) {
                FileLog.w("[ZenoraChat] Dropped stale history response for dialog " + dialogId);
            } else {
                publish(account, dialogId, roomId, target, snapshot, error, limit);
            }
        }
    }

    private static void publish(int account, long dialogId, long roomId, Target target,
                                Snapshot snapshot, Exception error, int limit) {
        if (snapshot != null && (snapshot.roomId != roomId
                || ZenoraChatController.getRoomIdForDialog(account, dialogId) != roomId)) {
            FileLog.w("[ZenoraChat] Refusing cross-dialog history payload for " + dialogId);
            return;
        }

        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = messagesController.getDialog(dialogId);
        if (dialog == null || dialog.peer == null) {
            FileLog.w("[ZenoraChat] Native dialog peer is unavailable for " + dialogId);
            return;
        }
        if (error != null) FileLog.e(error);

        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        HashSet<Long> includedUsers = new HashSet<>();
        HashMap<Long, TLRPC.User> directory = new HashMap<>();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.User self = messagesController.getUser(selfId);
        if (self != null && includedUsers.add(selfId)) result.users.add(self);

        if (snapshot != null && snapshot.users != null) {
            for (int i = 0; i < snapshot.users.length(); i++) {
                JSONObject row = snapshot.users.optJSONObject(i);
                if (row == null) continue;
                long userId = row.optLong("id", 0);
                if (userId <= 0 || userId == selfId) continue;
                TLRPC.User user = messagesController.getUser(userId);
                if (user == null) {
                    user = ZenoraChatController.makeHistorySenderUser(
                            userId,
                            row.optString("username", ""),
                            row.optString("display_name", ""),
                            row.optInt("is_verified", 0) == 1);
                }
                if (user != null) directory.put(userId, user);
            }
        }

        if (dialogId < 0) {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat != null) result.chats.add(chat);
        } else {
            TLRPC.User peer = messagesController.getUser(dialogId);
            if (peer != null && includedUsers.add(peer.id)) result.users.add(peer);
        }

        if (snapshot != null) {
            HashSet<Integer> seenIds = new HashSet<>();
            ArrayList<TLRPC.Message> mapped = new ArrayList<>();
            for (int i = 0; i < snapshot.messages.length(); i++) {
                JSONObject row = snapshot.messages.optJSONObject(i);
                if (row == null || row.optLong("room_id", 0) != roomId || row.isNull("text")) continue;

                long rawId = row.optLong("message_id", 0);
                long senderId = row.optLong("sender_id", 0);
                if (rawId <= 0 || rawId > Integer.MAX_VALUE
                        || senderId <= 0 || senderId > Integer.MAX_VALUE) continue;
                int messageId = (int) rawId;
                if (!seenIds.add(messageId)) continue;

                String body = row.optString("text", "");
                if (TextUtils.isEmpty(body) || TextUtils.isEmpty(body.trim())) continue;
                int date = ZenoraChatController.parseHistoryDate(row.optString("created_at", ""));
                if (date <= 0) continue;

                TLRPC.User sender = messagesController.getUser(senderId);
                if (sender == null) sender = directory.get(senderId);
                if (sender == null) {
                    sender = ZenoraChatController.makeHistorySenderUser(
                            senderId, "", "Zenora user " + senderId, false);
                }
                if (sender != null && includedUsers.add(senderId)) result.users.add(sender);

                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = messageId;
                message.date = date;
                message.dialog_id = dialogId;
                message.peer_id = dialog.peer;
                message.message = body;
                message.media = new TLRPC.TL_messageMediaEmpty();
                message.out = senderId == selfId;
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = senderId;
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
                if (message.out) message.flags |= 1 << 1;
                mapped.add(message);
            }

            // ChatActivity's normal reverse-layout adapter expects newest messages first.
            Collections.sort(mapped, (left, right) -> Integer.compare(right.id, left.id));
            result.messages.addAll(mapped);
        }

        result.count = result.messages.size();
        messagesController.processLoadedMessages(
                result,
                result.messages.size(),
                dialogId,
                0,
                Math.max(1, Math.min(100, limit)),
                0,
                0,
                false,
                target.classGuid,
                0,
                0,
                Math.max(0, dialog.unread_count),
                dialog.last_message_date,
                2,
                true,
                0,
                0L,
                target.loadIndex,
                false,
                0,
                true,
                false,
                null
        );
        FileLog.d("[ZenoraChat] Native initial text history published; dialog="
                + dialogId + ", count=" + result.messages.size()
                + ", failed=" + (error != null));
    }
}
