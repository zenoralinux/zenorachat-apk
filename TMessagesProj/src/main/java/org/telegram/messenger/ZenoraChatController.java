package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Converts ZenoraChat API rooms into Telegram's native TL dialog model.
 * The existing DialogsActivity and DialogCell views are not replaced.
 */
public final class ZenoraChatController {
    private static final Object loadLock = new Object();
    private static final Set<Integer> loadingAccounts = new HashSet<>();
    private static final Set<Integer> cacheRestoreStarted = new HashSet<>();
    private static final Set<Integer> snapshotAvailable = new HashSet<>();
    private static final Map<Integer, Boolean> pendingRefresh = new HashMap<>();
    private static final Map<Integer, Long> requestGenerations = new HashMap<>();
    private static final Map<Integer, Integer> retryAttempts = new HashMap<>();
    private static final Map<Integer, Long> lastSuccessfulLoad = new HashMap<>();
    private static final Map<Integer, Long> snapshotUserIds = new HashMap<>();
    private static final Map<Integer, Map<Long, Long>> dialogRoomIds = new HashMap<>();
    private static final Map<Integer, Set<Long>> savedDialogIds = new HashMap<>();
    private static final Map<Integer, Set<Long>> typingDialogIdsByAccount = new HashMap<>();
    private static final Map<Integer, Set<Long>> presenceUserIdsByAccount = new HashMap<>();
    private static final Map<Integer, Map<Long, Integer>> attentionReadMessageIds = new HashMap<>();
    private static final Map<Integer, Map<Long, Integer>> attentionReadPending = new HashMap<>();
    private static final Map<Integer, AccountInstance> accountInstances = new HashMap<>();
    private static final Map<Integer, Integer> visibleDialogsScreens = new HashMap<>();
    private static final Handler retryHandler = new Handler(Looper.getMainLooper());
    private static final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();
    private static boolean lifecycleRegistered;
    private static int startedActivities;
    private static boolean appForeground;

    private static final Runnable foregroundPoll = new Runnable() {
        @Override
        public void run() {
            ArrayList<AccountInstance> instances = new ArrayList<>();
            synchronized (loadLock) {
                if (!appForeground) return;
                instances.addAll(accountInstances.values());
            }
            for (AccountInstance instance : instances) {
                if (instance != null && ZenoraApiClient.isZenoraSessionEnabled()) {
                    refreshDialogs(instance);
                    // Rebuild pending Zenora text sends only after this account's room map is refreshed.
                    ZenoraHistoryController.restorePendingTextSends(instance.getCurrentAccount());
                }
            }
            lifecycleHandler.postDelayed(this, 2_000L);
        }
    };

    public interface OperationCallback {
        void onComplete(boolean success, String errorMessage);
    }

    private ZenoraChatController() {
    }

    public static void initializeLifecycleTracking() {
        synchronized (loadLock) {
            if (lifecycleRegistered || ApplicationLoader.applicationContext == null) return;
            Context context = ApplicationLoader.applicationContext.getApplicationContext();
            if (!(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }

                @Override
                public void onActivityStarted(Activity activity) {
                    synchronized (loadLock) {
                        startedActivities++;
                        if (!appForeground) {
                            appForeground = true;
                            lifecycleHandler.removeCallbacks(foregroundPoll);
                            lifecycleHandler.postDelayed(foregroundPoll, 2_000L);
                        }
                    }
                }

                @Override
                public void onActivityResumed(Activity activity) { }

                @Override
                public void onActivityPaused(Activity activity) { }

                @Override
                public void onActivityStopped(Activity activity) {
                    synchronized (loadLock) {
                        startedActivities = Math.max(0, startedActivities - 1);
                        if (startedActivities == 0) {
                            appForeground = false;
                            lifecycleHandler.removeCallbacks(foregroundPoll);
                        }
                    }
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

                @Override
                public void onActivityDestroyed(Activity activity) { }
            });
            lifecycleRegistered = true;
        }
    }

    public static boolean isZenoraEnabled() {
        return ZenoraApiClient.isZenoraSessionEnabled();
    }

    public static long getRoomIdForDialog(int account, long dialogId) {
        synchronized (loadLock) {
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            Long roomId = mappings == null ? null : mappings.get(dialogId);
            return roomId == null ? 0L : roomId;
        }
    }

    public static boolean isZenoraDialog(int account, long dialogId) {
        return dialogId != 0 && isZenoraEnabled()
                && getRoomIdForDialog(account, dialogId) > 0;
    }

    public static TLRPC.User makeHistorySenderUser(long id, String username,
                                                    String displayName, boolean verified) {
        return makeUser(id, username, displayName, false, verified);
    }

    public static int parseHistoryDate(String value) {
        return parseDate(value);
    }


    private static void clearEphemeralState(int account) {
        Set<Long> peerIds;
        synchronized (loadLock) {
            Set<Long> stored = presenceUserIdsByAccount.remove(account);
            peerIds = stored == null ? new HashSet<>() : new HashSet<>(stored);
        }
        MessagesController controller = MessagesController.getInstance(account);
        for (Long peerId : peerIds) {
            if (peerId == null || peerId <= 0) continue;
            TLRPC.User user = controller.getUsers().get(peerId);
            if (user != null) user.status = new TLRPC.TL_userStatusEmpty();
        }
        controller.applyZenoraTypingSnapshot(new HashMap<>());
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
    }

    /**
     * Remove all Zenora-only transient state when the API session is explicitly
     * cleared. In-flight room snapshots are invalidated so an old callback
     * cannot republish a previous account's dialogs after logout.
     */
    public static void clearSessionEphemeralState() {
        HashSet<Integer> accounts;
        synchronized (loadLock) {
            accounts = new HashSet<>(accountInstances.keySet());
            accounts.addAll(presenceUserIdsByAccount.keySet());
        }
        for (Integer accountValue : accounts) {
            if (accountValue == null || accountValue < 0) continue;
            int account = accountValue;
            clearEphemeralState(account);
            ZenoraMediaPreviewRegistry.clear(account);
            synchronized (loadLock) {
                long generation = requestGenerations.containsKey(account)
                        ? requestGenerations.get(account) + 1L : 1L;
                requestGenerations.put(account, generation);
                pendingRefresh.remove(account);
                snapshotAvailable.remove(account);
                cacheRestoreStarted.remove(account);
                lastSuccessfulLoad.remove(account);
                retryAttempts.remove(account);
                snapshotUserIds.remove(account);
                dialogRoomIds.remove(account);
                savedDialogIds.remove(account);
                typingDialogIdsByAccount.remove(account);
                presenceUserIdsByAccount.remove(account);
                attentionReadMessageIds.remove(account);
                attentionReadPending.remove(account);
                ZenoraHistoryController.clearAccount(account);
            }
            cacheExecutor.execute(() -> ZenoraDialogCache.delete(account));
            DialogsActivity.resetZenoraLoadState(account);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public static void activateAccount(int account, JSONObject profile) {
        TLRPC.User self = makeUser(profile.optLong("id", 0),
                profile.optString("username", ""),
                profile.optString("display_name", ""), true);
        if (self == null) return;
        UserConfig config = UserConfig.getInstance(account);
        TLRPC.User previousUser = config.getCurrentUser();
        Long previousSnapshotUserId;
        synchronized (loadLock) {
            previousSnapshotUserId = snapshotUserIds.get(account);
        }
        boolean accountIdentityChanged = (previousUser != null && previousUser.id != self.id)
                || (previousSnapshotUserId != null && previousSnapshotUserId > 0
                && previousSnapshotUserId != self.id);
        if (accountIdentityChanged) {
            clearEphemeralState(account);
            ZenoraMediaPreviewRegistry.clear(account);
            synchronized (loadLock) {
                snapshotAvailable.remove(account);
                cacheRestoreStarted.remove(account);
                lastSuccessfulLoad.remove(account);
                retryAttempts.remove(account);
                snapshotUserIds.remove(account);
                dialogRoomIds.remove(account);
                savedDialogIds.remove(account);
                typingDialogIdsByAccount.remove(account);
                presenceUserIdsByAccount.remove(account);
                attentionReadMessageIds.remove(account);
                attentionReadPending.remove(account);
                ZenoraHistoryController.clearAccount(account);
                long generation = requestGenerations.containsKey(account)
                        ? requestGenerations.get(account) + 1L : 1L;
                requestGenerations.put(account, generation);
            }
            DialogsActivity.resetZenoraLoadState(account);
            cacheExecutor.execute(() -> ZenoraDialogCache.delete(account));
        }
        config.setCurrentUser(self);
        config.saveConfig(true);
        MessagesController.getInstance(account).putUser(self, false, true);
    }

    public static void loadDialogs(AccountInstance accountInstance) {
        if (accountInstance == null) return;
        initializeLifecycleTracking();
        synchronized (loadLock) {
            accountInstances.put(accountInstance.getCurrentAccount(), accountInstance);
        }
        restoreCachedSnapshot(accountInstance);
        requestRooms(accountInstance, true);
    }

    public static void refreshDialogs(AccountInstance accountInstance) {
        if (accountInstance == null || !ZenoraApiClient.isZenoraSessionEnabled()) return;
        int account = accountInstance.getCurrentAccount();
        synchronized (loadLock) {
            accountInstances.put(account, accountInstance);
            Long lastLoaded = lastSuccessfulLoad.get(account);
            long minimumInterval = visibleDialogsScreens.containsKey(account)
                    && visibleDialogsScreens.get(account) > 0 ? 2_000L : 4_000L;
            if (lastLoaded != null
                    && SystemClock.elapsedRealtime() - lastLoaded < minimumInterval) {
                return;
            }
        }
        requestRooms(accountInstance, false);
    }

    /** Send native composer typing state to the currently mapped Zenora room. */
    public static void setTypingState(int account, long dialogId, boolean typing) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId == 0) return;
        final Long roomId;
        synchronized (loadLock) {
            Set<Long> supportedTypingDialogs = typingDialogIdsByAccount.get(account);
            if (supportedTypingDialogs == null || !supportedTypingDialogs.contains(dialogId)) return;
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            roomId = mappings == null ? null : mappings.get(dialogId);
        }
        if (roomId == null || roomId <= 0) return;
        ZenoraApiClient.updateTyping(roomId, typing, (response, error) -> {
            if (error != null) FileLog.w("[ZenoraChat] typing state update failed: " + error.getMessage());
        });
    }

    /**
     * Advance mention/reaction cursors only through the message range Telegram
     * has actually marked read after its message-visibility checks.
     */
    public static void markDialogAttentionRead(int account, long dialogId, int maxReadMessageId) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId >= 0 || maxReadMessageId <= 0) return;
        final long roomId;
        synchronized (loadLock) {
            Set<Long> saved = savedDialogIds.get(account);
            if (saved != null && saved.contains(dialogId)) return;
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            Long resolved = mappings == null ? null : mappings.get(dialogId);
            if (resolved == null || resolved <= 0) return;
            roomId = resolved;
            int last = attentionReadMessageIds.containsKey(account)
                    ? attentionReadMessageIds.get(account).getOrDefault(dialogId, 0) : 0;
            if (maxReadMessageId <= last) return;
            Map<Long, Integer> pending = attentionReadPending.computeIfAbsent(account, key -> new HashMap<>());
            int pendingId = pending.getOrDefault(dialogId, 0);
            if (pendingId >= maxReadMessageId) return;
            pending.put(dialogId, maxReadMessageId);
            if (pendingId > 0) return;
        }
        submitAttentionRead(account, dialogId, roomId, maxReadMessageId);
    }

    private static void submitAttentionRead(int account, long dialogId, long roomId, int sentMessageId) {
        ZenoraApiClient.markRoomAttentionRead(roomId, sentMessageId, (response, error) -> {
            int highestPending;
            AccountInstance instance;
            synchronized (loadLock) {
                Map<Long, Integer> pending = attentionReadPending.get(account);
                highestPending = pending == null ? sentMessageId : pending.getOrDefault(dialogId, sentMessageId);
                if (pending != null) {
                    pending.remove(dialogId);
                    if (pending.isEmpty()) attentionReadPending.remove(account);
                }
                if (error == null) {
                    Map<Long, Integer> read = attentionReadMessageIds.computeIfAbsent(account, key -> new HashMap<>());
                    read.put(dialogId, Math.max(read.getOrDefault(dialogId, 0), sentMessageId));
                }
                instance = accountInstances.get(account);
            }
            if (error != null) {
                FileLog.w("[ZenoraChat] mention/reaction read cursor update failed: " + error.getMessage());
                if (highestPending > sentMessageId) {
                    markDialogAttentionRead(account, dialogId, highestPending);
                }
                return;
            }
            if (highestPending > sentMessageId) {
                markDialogAttentionRead(account, dialogId, highestPending);
            } else if (instance != null) {
                requestRooms(instance, true);
            }
        });
    }

    /**
     * Persist Mark as Read in Zenora rather than issuing an unrelated MTProto
     * read request. Marker-only chats clear the marker without moving room_reads.
     */
    public static void markDialogRead(int account, long dialogId, OperationCallback callback) {
        updateDialogReadState(account, dialogId, false, callback);
    }

    /** Set only the current user's Marked Unread flag; never rewind room_reads. */
    public static void markDialogUnread(int account, long dialogId, OperationCallback callback) {
        updateDialogReadState(account, dialogId, true, callback);
    }

    private static void updateDialogReadState(int account, long dialogId, boolean markUnread,
                                              OperationCallback callback) {
        if (!ZenoraApiClient.isZenoraSessionEnabled()) {
            if (callback != null) callback.onComplete(false, "حساب زنورا فعال نیست.");
            return;
        }
        if (dialogId == 0) {
            if (callback != null) callback.onComplete(false, "شناسه گفتگو معتبر نیست.");
            return;
        }

        final long roomId;
        synchronized (loadLock) {
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            Long mappedRoomId = mappings == null ? null : mappings.get(dialogId);
            if (mappedRoomId == null || mappedRoomId <= 0) {
                if (callback != null) callback.onComplete(false,
                        "شناسه اتاق زنورا پیدا نشد؛ فهرست گفتگوها را تازه‌سازی کنید.");
                return;
            }
            roomId = mappedRoomId;
        }

        int lastReadMessageId = 0;
        if (!markUnread) {
            TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
            if (dialog == null) {
                if (callback != null) callback.onComplete(false,
                        "اطلاعات گفتگو در دسترس نیست؛ فهرست را تازه‌سازی کنید.");
                return;
            }
            // This count represents actual unread messages, not the manual flag.
            // A zero count means Mark as Read must only clear is_unread.
            if (dialog.unread_count > 0) {
                if (dialog.top_message <= 0) {
                    if (callback != null) callback.onComplete(false,
                            "شناسه آخرین پیام خواندنی معتبر نیست؛ فهرست را تازه‌سازی کنید.");
                    return;
                }
                lastReadMessageId = dialog.top_message;
            }
        }

        final int cursor = lastReadMessageId;
        ZenoraApiClient.Callback apiCallback = (response, error) -> {
            if (error != null) {
                FileLog.w("[ZenoraChat] " + (markUnread ? "mark unread" : "mark read")
                        + " operation failed: " + error.getMessage());
                if (callback != null) callback.onComplete(false, error.getMessage());
                return;
            }
            requestRooms(AccountInstance.getInstance(account), true);
            if (callback != null) callback.onComplete(true, null);
        };
        if (markUnread) {
            ZenoraApiClient.setRoomMarkedUnread(roomId, apiCallback);
        } else {
            ZenoraApiClient.markRoomRead(roomId, cursor, apiCallback);
        }
    }

    public static void setDialogsScreenVisible(int account, boolean visible) {
        if (account < 0) return;
        AccountInstance instance = AccountInstance.getInstance(account);
        synchronized (loadLock) {
            accountInstances.put(account, instance);
            int count = visibleDialogsScreens.containsKey(account) ? visibleDialogsScreens.get(account) : 0;
            if (visible) {
                visibleDialogsScreens.put(account, count + 1);
            } else if (count <= 1) {
                visibleDialogsScreens.remove(account);
            } else {
                visibleDialogsScreens.put(account, count - 1);
            }
        }
        if (visible && ZenoraApiClient.isZenoraSessionEnabled()) {
            refreshDialogs(instance);
        }
    }

    /**
     * Archive/unarchive selected Telegram dialogs through Zenora's PHP API.
     * dialogIds are native Telegram ids; room ids are resolved only from the
     * latest authenticated snapshot to avoid sending mismatched peer ids.
     */
    public static void setDialogsArchived(int account, ArrayList<Long> dialogIds,
                                          boolean archived, boolean restorePinnedOnUndo,
                                          OperationCallback callback) {
        if (!ZenoraApiClient.isZenoraSessionEnabled()) {
            if (callback != null) callback.onComplete(false, "حساب زنورا فعال نیست.");
            return;
        }
        if (dialogIds == null || dialogIds.isEmpty() || dialogIds.size() > 500) {
            if (callback != null) callback.onComplete(false, "تعداد گفتگوهای انتخاب‌شده معتبر نیست.");
            return;
        }

        final ArrayList<Long> roomIds = new ArrayList<>();
        synchronized (loadLock) {
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            Set<Long> savedIds = savedDialogIds.get(account);
            if (mappings == null) {
                if (callback != null) callback.onComplete(false, "شناسه گفتگوهای زنورا در دسترس نیست؛ فهرست را تازه‌سازی کنید.");
                return;
            }
            HashSet<Long> seen = new HashSet<>();
            for (Long dialogId : dialogIds) {
                if (dialogId == null || !seen.add(dialogId)) continue;
                if (archived && savedIds != null && savedIds.contains(dialogId)) {
                    if (callback != null) callback.onComplete(false, "پیام‌های ذخیره‌شده قابل آرشیو کردن نیستند.");
                    return;
                }
                Long roomId = mappings.get(dialogId);
                if (roomId == null || roomId <= 0) {
                    if (callback != null) callback.onComplete(false, "شناسه یکی از گفتگوها در زنورا پیدا نشد؛ دوباره فهرست را دریافت کنید.");
                    return;
                }
                roomIds.add(roomId);
            }
        }
        if (roomIds.isEmpty()) {
            if (callback != null) callback.onComplete(false, "گفتگوی معتبری برای تغییر وضعیت انتخاب نشده است.");
            return;
        }

        AccountInstance instance = AccountInstance.getInstance(account);
        ZenoraApiClient.setChatArchive(roomIds, archived, restorePinnedOnUndo, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                if (callback != null) callback.onComplete(false, error.getMessage());
                return;
            }
            // Bypass the normal 2-4s poll throttle. If a request is already active,
            // requestRooms coalesces this into a guaranteed follow-up snapshot.
            requestRooms(instance, true);
            if (callback != null) callback.onComplete(true, null);
        });
    }


    /**
     * Commits native Delete/Leave operations to Zenora after Telegram's native
     * Undo window has expired. Dialog IDs are resolved only from the latest
     * complete room snapshot; unsupported or stale IDs fail closed.
     */
    public static void deleteDialogs(int account, ArrayList<Long> dialogIds,
                                     Set<Long> revokeDialogIds, long blockPeerUserId,
                                     OperationCallback callback) {
        if (!ZenoraApiClient.isZenoraSessionEnabled()) {
            if (callback != null) callback.onComplete(false, "حساب زنورا فعال نیست.");
            return;
        }
        if (dialogIds == null || dialogIds.isEmpty() || dialogIds.size() > 500) {
            if (callback != null) callback.onComplete(false, "تعداد گفتگوهای انتخاب‌شده معتبر نیست.");
            return;
        }

        final ArrayList<Long> roomIds = new ArrayList<>();
        final Map<Long, String> scopes = new HashMap<>();
        try {
            synchronized (loadLock) {
                Map<Long, Long> mappings = dialogRoomIds.get(account);
                Set<Long> savedIds = savedDialogIds.get(account);
                if (mappings == null) {
                    throw new IllegalStateException("شناسه گفتگوهای زنورا در دسترس نیست؛ فهرست را تازه‌سازی کنید.");
                }
                HashSet<Long> seenDialogs = new HashSet<>();
                HashSet<Long> seenRooms = new HashSet<>();
                for (Long dialogId : dialogIds) {
                    if (dialogId == null || !seenDialogs.add(dialogId)) continue;
                    Long roomId = mappings.get(dialogId);
                    if (roomId == null || roomId <= 0) {
                        throw new IllegalStateException("شناسه یکی از گفتگوها در زنورا پیدا نشد؛ فهرست را تازه‌سازی کنید.");
                    }
                    if (!seenRooms.add(roomId)) {
                        throw new IllegalStateException("دو گفتگوی انتخاب‌شده به یک اتاق زنورا اشاره می‌کنند.");
                    }
                    roomIds.add(roomId);
                    boolean isSaved = savedIds != null && savedIds.contains(dialogId);
                    boolean mayDeleteForBoth = !isSaved && dialogId > 0
                            && revokeDialogIds != null && revokeDialogIds.contains(dialogId);
                    scopes.put(roomId, mayDeleteForBoth ? "both" : "me");
                }
            }
        } catch (Exception error) {
            if (callback != null) callback.onComplete(false, error.getMessage());
            return;
        }

        if (roomIds.isEmpty()) {
            if (callback != null) callback.onComplete(false, "گفتگوی معتبری برای حذف انتخاب نشده است.");
            return;
        }

        AccountInstance instance = AccountInstance.getInstance(account);
        String operationId = UUID.randomUUID().toString();
        ZenoraApiClient.deleteChats(roomIds, scopes, operationId, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                requestRooms(instance, true);
                if (callback != null) callback.onComplete(false, deleteOperationError(error));
                return;
            }

            if (blockPeerUserId > 0) {
                ZenoraApiClient.setDmBlocked(blockPeerUserId, true, (blockResponse, blockError) -> {
                    requestRooms(instance, true);
                    if (blockError != null) {
                        FileLog.e(blockError);
                        if (callback != null) {
                            callback.onComplete(false, "گفتگو حذف شد، اما مسدودسازی کاربر انجام نشد.");
                        }
                        return;
                    }
                    if (callback != null) callback.onComplete(true, null);
                });
            } else {
                requestRooms(instance, true);
                if (callback != null) callback.onComplete(true, null);
            }
        });
    }

    private static String deleteOperationError(Exception error) {
        if (error instanceof ZenoraApiClient.ApiException) {
            String code = ((ZenoraApiClient.ApiException) error).errorCode;
            if ("ROOM_OWNER_CANNOT_LEAVE".equals(code)) {
                return "مالک گروه یا کانال تا زمان انتقال مالکیت نمی‌تواند گفتگو را ترک کند.";
            }
            if ("CHAT_NOT_ACCESSIBLE".equals(code) || "FORBIDDEN".equals(code)) {
                return "دسترسی به یکی از گفتگوها تغییر کرده است؛ فهرست دوباره همگام‌سازی شد.";
            }
            if ("INVALID_DELETE_SCOPE".equals(code)) {
                return "دامنه حذف برای یکی از گفتگوها معتبر نیست.";
            }
            if ("ROOM_BLOCKED".equals(code)) {
                return "این گفتگو مسدود شده و امکان ترک آن وجود ندارد.";
            }
        }
        return error.getMessage() == null ? "عملیات حذف یا ترک گفتگو ناموفق بود." : error.getMessage();
    }

    /**
     * Pin or unpin native dialogs through the authenticated Zenora API.
     * Native dialog ids are resolved against the latest complete snapshot.
     */
    public static void setDialogsPinned(int account, ArrayList<Long> dialogIds,
                                        boolean pinned, OperationCallback callback) {
        if (!ZenoraApiClient.isZenoraSessionEnabled()) {
            if (callback != null) callback.onComplete(false, "حساب زنورا فعال نیست.");
            return;
        }
        final ArrayList<Long> roomIds;
        try {
            roomIds = resolveDialogRoomIds(account, dialogIds);
        } catch (Exception e) {
            if (callback != null) callback.onComplete(false, e.getMessage());
            return;
        }

        AccountInstance instance = AccountInstance.getInstance(account);
        ZenoraApiClient.setChatPinned(roomIds, pinned, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                requestRooms(instance, true);
                if (callback != null) callback.onComplete(false, pinOperationError(error));
                return;
            }
            requestRooms(instance, true);
            if (callback != null) callback.onComplete(true, null);
        });
    }

    /**
     * Reorders every pinned dialog in the supplied folder from top to bottom.
     * The API's move_pin_top operation applies the sequence atomically per user.
     */
    public static void reorderPinnedDialogs(int account, ArrayList<Long> orderedDialogIds,
                                            OperationCallback callback) {
        if (!ZenoraApiClient.isZenoraSessionEnabled()) {
            if (callback != null) callback.onComplete(false, "حساب زنورا فعال نیست.");
            return;
        }
        final ArrayList<Long> roomIds;
        try {
            roomIds = resolveDialogRoomIds(account, orderedDialogIds);
        } catch (Exception e) {
            if (callback != null) callback.onComplete(false, e.getMessage());
            return;
        }

        AccountInstance instance = AccountInstance.getInstance(account);
        ZenoraApiClient.movePinnedChatsToTop(roomIds, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                // Restore the order from the last valid server snapshot if the
                // optimistic RecyclerView drag could not be committed.
                requestRooms(instance, true);
                if (callback != null) callback.onComplete(false, pinOperationError(error));
                return;
            }
            requestRooms(instance, true);
            if (callback != null) callback.onComplete(true, null);
        });
    }

    private static ArrayList<Long> resolveDialogRoomIds(int account, ArrayList<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty() || dialogIds.size() > 500) {
            throw new IllegalArgumentException("تعداد گفتگوهای انتخاب‌شده معتبر نیست.");
        }
        ArrayList<Long> roomIds = new ArrayList<>();
        synchronized (loadLock) {
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            if (mappings == null) {
                throw new IllegalStateException("شناسه گفتگوهای زنورا در دسترس نیست؛ فهرست را تازه‌سازی کنید.");
            }
            HashSet<Long> seen = new HashSet<>();
            for (Long dialogId : dialogIds) {
                if (dialogId == null || !seen.add(dialogId)) continue;
                Long roomId = mappings.get(dialogId);
                if (roomId == null || roomId <= 0) {
                    throw new IllegalStateException("شناسه یکی از گفتگوها در زنورا پیدا نشد؛ فهرست را تازه‌سازی کنید.");
                }
                roomIds.add(roomId);
            }
        }
        if (roomIds.isEmpty()) {
            throw new IllegalArgumentException("گفتگوی معتبری برای تغییر وضعیت انتخاب نشده است.");
        }
        return roomIds;
    }

    private static String pinOperationError(Exception error) {
        if (error instanceof ZenoraApiClient.ApiException) {
            String code = ((ZenoraApiClient.ApiException) error).errorCode;
            if ("PIN_LIMIT_REACHED".equals(code)) {
                return "حداکثر سه گفتگو در فهرست اصلی قابل سنجاق کردن است.";
            }
            if ("PINNED_DIALOG_SET_CHANGED".equals(code)) {
                return "وضعیت گفتگوهای سنجاق‌شده تغییر کرده است؛ فهرست دوباره همگام‌سازی شد.";
            }
            if ("CHAT_NOT_ACCESSIBLE".equals(code)) {
                return "یکی از گفتگوها دیگر در دسترس حساب زنورا نیست.";
            }
        }
        return error == null || TextUtils.isEmpty(error.getMessage())
                ? "ذخیره ترتیب گفتگوهای سنجاق‌شده ناموفق بود."
                : error.getMessage();
    }

    private static void restoreCachedSnapshot(AccountInstance accountInstance) {
        final int account = accountInstance.getCurrentAccount();
        synchronized (loadLock) {
            if (!cacheRestoreStarted.add(account)) return;
        }
        JSONObject profile = ZenoraApiClient.getStoredProfile();
        final long expectedUserId = profile == null ? 0 : profile.optLong("id", 0);
        if (expectedUserId <= 0) return;
        cacheExecutor.execute(() -> {
            JSONObject cached = ZenoraDialogCache.read(account, expectedUserId);
            if (cached == null) return;
            try {
                cached.put("_zenora_cache_restore", true);
            } catch (Exception cacheMarkerError) {
                FileLog.e(cacheMarkerError);
                return;
            }
            retryHandler.post(() -> {
                synchronized (loadLock) {
                    if (snapshotAvailable.contains(account)) return;
                }
                if (!ZenoraApiClient.isZenoraSessionEnabled()) return;
                try {
                    applyRooms(accountInstance, cached);
                    synchronized (loadLock) {
                        snapshotAvailable.add(account);
                    }
                    DialogsActivity.markZenoraLoadComplete(account);
                    FileLog.d("[ZenoraChat] restored cached dialog snapshot for account " + account);
                } catch (Exception e) {
                    FileLog.e(e);
                    ZenoraDialogCache.delete(account);
                }
            });
        });
    }

    private static void requestRooms(AccountInstance accountInstance, boolean force) {
        final int account = accountInstance.getCurrentAccount();
        final long generation;
        synchronized (loadLock) {
            accountInstances.put(account, accountInstance);
            if (loadingAccounts.contains(account)) {
                pendingRefresh.put(account, true);
                return;
            }
            if (!force) {
                Long lastLoaded = lastSuccessfulLoad.get(account);
                long minimumInterval = visibleDialogsScreens.containsKey(account)
                        && visibleDialogsScreens.get(account) > 0 ? 2_000L : 4_000L;
                if (lastLoaded != null
                        && SystemClock.elapsedRealtime() - lastLoaded < minimumInterval) {
                    return;
                }
            }
            loadingAccounts.add(account);
            generation = requestGenerations.containsKey(account)
                    ? requestGenerations.get(account) + 1L : 1L;
            requestGenerations.put(account, generation);
        }

        ZenoraApiClient.fetchRooms((response, error) -> {
            boolean rerun;
            synchronized (loadLock) {
                loadingAccounts.remove(account);
                rerun = pendingRefresh.remove(account) != null;
                Long currentGeneration = requestGenerations.get(account);
                if (currentGeneration == null || currentGeneration != generation) {
                    if (rerun) retryHandler.postDelayed(() -> requestRooms(accountInstance, true), 200L);
                    return;
                }
            }

            if (error != null) {
                if (error instanceof ZenoraApiClient.ApiException
                        && ((ZenoraApiClient.ApiException) error).statusCode == 401) {
                    synchronized (loadLock) {
                        retryAttempts.remove(account);
                        lastSuccessfulLoad.remove(account);
                        snapshotAvailable.remove(account);
                    }
                    FileLog.e("[ZenoraChat] rooms.php rejected the saved session after retry: " + error.getMessage());
                    clearEphemeralState(account);
                    DialogsActivity.resetZenoraLoadState(account);
                    ZenoraApiClient.clearSession();
                    LaunchActivity.requestZenoraLogin();
                    return;
                }
                if (!hasSnapshot(account)) {
                    DialogsActivity.resetZenoraLoadState(account);
                }
                FileLog.e(error);
                if (!rerun) scheduleRetry(accountInstance);
            } else {
                try {
                    applyRooms(accountInstance, response);
                    synchronized (loadLock) {
                        retryAttempts.remove(account);
                        lastSuccessfulLoad.put(account, SystemClock.elapsedRealtime());
                        snapshotAvailable.add(account);
                    }
                    DialogsActivity.markZenoraLoadComplete(account);
                    final JSONObject cachePayload = new JSONObject(response.toString());
                    cacheExecutor.execute(() -> ZenoraDialogCache.write(account,
                            response.optJSONObject("me") == null ? 0 : response.optJSONObject("me").optLong("id", 0),
                            cachePayload));
                } catch (Exception e) {
                    FileLog.e(e);
                    if (!hasSnapshot(account)) DialogsActivity.resetZenoraLoadState(account);
                    if (!rerun) scheduleRetry(accountInstance);
                }
            }

            if (rerun) {
                retryHandler.postDelayed(() -> requestRooms(accountInstance, true), 200L);
            }
        });
    }

    private static boolean hasSnapshot(int account) {
        synchronized (loadLock) {
            return snapshotAvailable.contains(account);
        }
    }

    private static void scheduleRetry(AccountInstance accountInstance) {
        final int account = accountInstance.getCurrentAccount();
        final int retryIndex;
        synchronized (loadLock) {
            retryIndex = retryAttempts.containsKey(account) ? retryAttempts.get(account) : 0;
            retryAttempts.put(account, Math.min(retryIndex + 1, 6));
        }
        long delay = Math.min(60_000L, 2_000L << Math.min(retryIndex, 5));
        retryHandler.postDelayed(() -> {
            synchronized (loadLock) {
                if (!appForeground) return;
            }
            requestRooms(accountInstance, true);
        }, delay);
    }

    private static void validateRows(JSONArray rows, boolean directList) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optLong("id", 0) <= 0) {
                throw new IllegalStateException("Invalid room row at index " + i);
            }
            String type = row.optString("type", directList ? "dm" : "group");
            if (directList && !"saved".equals(type) && row.optLong("other_user_id", 0) <= 0) {
                throw new IllegalStateException("Direct room is missing its peer at index " + i);
            }
        }
    }

    private static final class DialogSnapshot {
        final ConcurrentHashMap<Long, TLRPC.User> users = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, TLRPC.Chat> chats = new ConcurrentHashMap<>();
        final HashMap<Long, TLRPC.Dialog> dialogs = new HashMap<>();
        final HashMap<Long, ArrayList<TLRPC.Message>> messages = new HashMap<>();
        final HashMap<Long, Long> roomIds = new HashMap<>();
        final HashMap<Long, Integer> muteUntil = new HashMap<>();
        final HashMap<Long, ArrayList<Long>> typingUsers = new HashMap<>();
        final Set<Long> typingDialogIds = new HashSet<>();
        final Set<Long> presenceUserIds = new HashSet<>();
        long serverTime;
        boolean applyEphemeralState;
        boolean typingDmEnabled = true;
        boolean typingGroupEnabled = true;
        final HashMap<String, String> avatars = new HashMap<>();
        final ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> mainDialogs = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> archivedDialogs = new ArrayList<>();
        final Set<Long> savedDialogs = new HashSet<>();
        final HashMap<String, ZenoraMediaPreviewRegistry.Preview> mediaPreviews = new HashMap<>();
    }

    private static void applyRooms(AccountInstance accountInstance, JSONObject response) throws Exception {
        if (response == null || !response.optBoolean("ok", false)) {
            throw new IllegalStateException("Rooms API did not return success");
        }
        JSONObject meJson = response.optJSONObject("me");
        JSONObject storedProfile = ZenoraApiClient.getStoredProfile();
        if (meJson != null && storedProfile != null
                && storedProfile.optLong("id", 0) > 0
                && storedProfile.optLong("id", 0) != meJson.optLong("id", 0)) {
            throw new IllegalStateException("Rooms response belongs to a different authenticated user");
        }
        JSONArray rooms = response.optJSONArray("rooms");
        JSONArray directRooms = response.optJSONArray("dm_rooms");
        if (meJson == null || meJson.optLong("id", 0) <= 0 || rooms == null || directRooms == null) {
            throw new IllegalStateException("Incomplete rooms API payload");
        }
        validateRows(rooms, false);
        validateRows(directRooms, true);

        int account = accountInstance.getCurrentAccount();
        UserConfig userConfig = accountInstance.getUserConfig();
        MessagesController controller = accountInstance.getMessagesController();
        TLRPC.User self = userConfig.getCurrentUser();
        if (self == null || self.id != meJson.optLong("id", 0)) {
            self = makeUser(meJson.optLong("id", 0), meJson.optString("username", ""),
                    meJson.optString("display_name", ""), true);
        }
        if (self == null) throw new IllegalStateException("ZenoraChat profile is missing");

        // Build every model in isolated collections. No currently displayed map,
        // avatar registry, or notification state is touched until the full snapshot
        // has been mapped and sorted successfully.
        DialogSnapshot snapshot = new DialogSnapshot();
        snapshot.serverTime = response.optLong("server_time", 0);
        snapshot.applyEphemeralState = !response.optBoolean("_zenora_cache_restore", false);
        snapshot.typingDmEnabled = response.optBoolean("typing_dm_enabled", true);
        snapshot.typingGroupEnabled = response.optBoolean("typing_group_enabled", true);
        snapshot.users.put(self.id, self);
        for (int i = 0; i < rooms.length(); i++) {
            addDialog(account, snapshot, self, rooms.getJSONObject(i), false);
        }
        for (int i = 0; i < directRooms.length(); i++) {
            addDialog(account, snapshot, self, directRooms.getJSONObject(i), true);
        }
        // Resolve presence after all peer/sender models have been merged, so a
        // last-message sender cannot overwrite the final presence state.
        for (int i = 0; i < directRooms.length(); i++) {
            JSONObject row = directRooms.getJSONObject(i);
            if (!"saved".equals(row.optString("type", "dm"))) {
                long peerId = row.optLong("other_user_id", 0);
                if (peerId > 0) snapshot.presenceUserIds.add(peerId);
                applyUserPresence(account, snapshot.users.get(peerId), row,
                        snapshot.serverTime, snapshot.applyEphemeralState);
            }
        }

        java.util.Comparator<TLRPC.Dialog> order = (left, right) -> {
            if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
            if (left.pinned) {
                int pinOrder = Integer.compare(right.pinnedNum, left.pinnedNum);
                if (pinOrder != 0) return pinOrder;
            }
            return Integer.compare(right.last_message_date, left.last_message_date);
        };
        for (TLRPC.Dialog dialog : snapshot.allDialogs) {
            if (dialog.folder_id == 1) snapshot.archivedDialogs.add(dialog);
            else snapshot.mainDialogs.add(dialog);
        }
        Collections.sort(snapshot.mainDialogs, order);
        Collections.sort(snapshot.archivedDialogs, order);

        // Persist the authenticated profile only after the candidate is complete.
        ZenoraApiClient.enableSession(meJson);
        if (userConfig.getCurrentUser() == null || userConfig.getCurrentUser().id != self.id) {
            userConfig.setCurrentUser(self);
            userConfig.saveConfig(true);
        }

        // Commit is deliberately one synchronous main-thread section. Consumers
        // receive dialogsNeedReload only after all related maps have been replaced.
        controller.dialogs_dict.clear();
        controller.dialogMessage.clear();
        controller.dialogMessagesByIds.clear();
        controller.dialogMessagesByRandomIds.clear();
        controller.dialogsByFolder.clear();
        controller.allDialogs.clear();
        controller.dialogs_read_inbox_max.clear();
        controller.dialogs_read_outbox_max.clear();
        controller.getUsers().clear();
        controller.getChats().clear();
        controller.getUsers().putAll(snapshot.users);
        controller.getChats().putAll(snapshot.chats);
        for (Map.Entry<Long, TLRPC.Dialog> entry : snapshot.dialogs.entrySet()) {
            controller.dialogs_dict.put(entry.getKey(), entry.getValue());
            controller.dialogs_read_inbox_max.put(entry.getKey(), entry.getValue().read_inbox_max_id);
        }
        // Recreate MessageObjects against MessagesController's live peer maps.
        // This avoids retaining references to the staging maps after publication.
        for (Map.Entry<Long, ArrayList<TLRPC.Message>> entry : snapshot.messages.entrySet()) {
            ArrayList<MessageObject> liveMessages = new ArrayList<>();
            for (TLRPC.Message message : entry.getValue()) {
                MessageObject object = new MessageObject(account, message,
                        controller.getUsers(), controller.getChats(), false, false);
                liveMessages.add(object);
                controller.dialogMessagesByIds.put(object.getId(), object);
                if (message.random_id != 0) {
                    controller.dialogMessagesByRandomIds.put(message.random_id, object);
                }
            }
            controller.dialogMessage.put(entry.getKey(), liveMessages);
        }
        controller.allDialogs.addAll(snapshot.allDialogs);
        controller.dialogsByFolder.put(0, snapshot.mainDialogs);
        controller.dialogsByFolder.put(1, snapshot.archivedDialogs);
        controller.resetDialogsLoadedTillDate();
        controller.sortDialogs(null);
        // Recreate Telegram's special Archive folder row after each complete
        // Zenora snapshot; sortDialogs alone only populates folder contents.
        controller.checkArchiveFolder();

        // Never replay ephemeral status/typing from the local snapshot cache.
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
        controller.applyZenoraTypingSnapshot(snapshot.typingUsers);

        synchronized (loadLock) {
            dialogRoomIds.put(account, new HashMap<>(snapshot.roomIds));
            savedDialogIds.put(account, new HashSet<>(snapshot.savedDialogs));
            typingDialogIdsByAccount.put(account, new HashSet<>(snapshot.typingDialogIds));
            presenceUserIdsByAccount.put(account, new HashSet<>(snapshot.presenceUserIds));
            snapshotUserIds.put(account, meJson.optLong("id", 0));
        }
        ZenoraAvatarRegistry.replaceSnapshot(account, snapshot.avatars);
        ZenoraMediaPreviewRegistry.replaceSnapshot(account, snapshot.mediaPreviews);
        int pendingMuteStateCount = 0;
        for (Long dialogId : snapshot.muteUntil.keySet()) {
            if (dialogId != null && dialogId != 0L) {
                pendingMuteStateCount++;
            }
        }
        final int[] pendingMuteStates = {pendingMuteStateCount};
        Runnable publishDialogsReload = () -> NotificationCenter.getInstance(account)
                .postNotificationName(NotificationCenter.dialogsNeedReload);
        if (pendingMuteStateCount == 0) {
            publishDialogsReload.run();
        } else {
            for (Map.Entry<Long, Integer> mute : snapshot.muteUntil.entrySet()) {
                if (mute.getKey() == null || mute.getKey() == 0L) {
                    continue;
                }
                NotificationsController.getInstance(account).getNotificationsSettingsFacade()
                        .applyZenoraMuteState(mute.getKey(), mute.getValue(), () -> {
                            if (--pendingMuteStates[0] == 0) {
                                // Publish the complete dialog list only after all
                                // per-chat notification overrides are applied.
                                publishDialogsReload.run();
                            }
                        });
            }
        }
        FileLog.d("[ZenoraChat] atomically published dialog snapshot: groups=" + rooms.length()
                + ", direct=" + directRooms.length() + ", main=" + snapshot.mainDialogs.size()
                + ", archived=" + snapshot.archivedDialogs.size());
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void addDialog(int account, DialogSnapshot snapshot, TLRPC.User self,
                                  JSONObject row, boolean fromDmList) throws Exception {
        String type = row.optString("type", fromDmList ? "dm" : "group");
        boolean saved = "saved".equals(type);
        boolean direct = fromDmList && !saved;
        boolean channel = "channel".equals(type);
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) return;

        long dialogId;
        TLRPC.Peer peer;
        if (saved) {
            // Saved Messages is Telegram's native self-chat. Keep the Zenora
            // room mapping separate from the peer identity so native dialogs,
            // clear-history confirmation, and Saved Messages checks all use
            // the authenticated self user just like the official client.
            TLRPC.TL_peerUser savedPeer = new TLRPC.TL_peerUser();
            savedPeer.user_id = self.id;
            peer = savedPeer;
            dialogId = self.id;
        } else if (direct) {
            long otherId = row.optLong("other_user_id", 0);
            if (otherId <= 0) return;
            putAvatar(snapshot, "u:" + otherId,
                    ZenoraApiClient.resolvePublicUrl(row.optString("avatar_url", "")));
            boolean otherVerified = row.optInt("is_verified", 0) == 1;
            TLRPC.User other = makeUser(otherId,
                    row.optString("other_username", ""),
                    row.optString("other_display_name", ""), false, otherVerified);
            if (other == null) return;
            snapshot.users.put(other.id, other);
            TLRPC.TL_peerUser userPeer = new TLRPC.TL_peerUser();
            userPeer.user_id = otherId;
            peer = userPeer;
            dialogId = otherId;
        } else {
            TLRPC.Chat chat;
            if (channel) {
                TLRPC.TL_channel channelChat = new TLRPC.TL_channel();
                channelChat.broadcast = true;
                channelChat.megagroup = false;
                channelChat.flags |= 1 << 5;
                chat = channelChat;
            } else {
                chat = new TLRPC.TL_chat();
            }
            boolean roomVerified = row.optInt("is_verified", 0) == 1;
            chat.verified = roomVerified;
            // The native TL_channel schema carries verified in flag bit 7.
            // TL_chat has no wire-level verified flag, but DialogCell reads the
            // common Chat.verified property directly for this local Zenora model.
            if (channel && roomVerified) chat.flags |= 1 << 7;
            chat.id = roomId;
            chat.title = nonEmpty(row.optString("name", ""), " ");
            chat.photo = new TLRPC.TL_chatPhotoEmpty();
            putAvatar(snapshot, "c:" + roomId,
                    ZenoraApiClient.resolvePublicUrl(row.optString("avatar_url", "")));
            chat.date = parseDate(row.optString("last_message_time", ""));
            chat.participants_count = row.optInt("member_count", 0);
            snapshot.chats.put(chat.id, chat);

            if (channel) {
                TLRPC.TL_peerChannel channelPeer = new TLRPC.TL_peerChannel();
                channelPeer.channel_id = roomId;
                peer = channelPeer;
            } else {
                TLRPC.TL_peerChat chatPeer = new TLRPC.TL_peerChat();
                chatPeer.chat_id = roomId;
                peer = chatPeer;
            }
            dialogId = -roomId;
        }

        int messageId = (int) Math.max(0, Math.min(Integer.MAX_VALUE, row.optLong("last_message_id", 0)));
        int readId = (int) Math.max(0, Math.min(Integer.MAX_VALUE, row.optLong("last_read_id", 0)));
        int date = parseDate(row.optString("last_message_time", ""));
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = dialogId;
        dialog.peer = peer;
        dialog.top_message = messageId;
        dialog.read_inbox_max_id = readId;
        dialog.unread_count = Math.max(0, row.optInt("unread_count", 0));
        dialog.unread_mentions_count = Math.max(0, row.optInt("unread_mentions", 0));
        dialog.unread_reactions_count = Math.max(0, row.optInt("unread_reaction_mentions", 0));
        dialog.unread_mark = row.optInt("is_marked_unread", 0) == 1;
        dialog.pinned = row.optInt("is_pinned", 0) == 1;
        dialog.pinnedNum = 100000 - Math.max(0, row.optInt("pin_order", 0));
        dialog.folder_id = row.optInt("is_archived", 0) == 1 ? 1 : 0;
        dialog.last_message_date = date;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();

        synchronized (loadLock) {
            snapshot.roomIds.put(dialogId, roomId);
        }
        snapshot.dialogs.put(dialogId, dialog);
        snapshot.allDialogs.add(dialog);
        if (saved) snapshot.savedDialogs.add(dialogId);
        applyDialogMuteSnapshot(snapshot, dialogId, row);

        if (messageId > 0) {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = messageId;
            message.date = date;
            message.peer_id = peer;
            message.dialog_id = dialogId;
            ZenoraMediaPreviewMapper.Mapping mediaMapping =
                    ZenoraMediaPreviewMapper.map(dialogId, messageId, date, row);
            message.message = mediaMapping.messageText;
            message.media = mediaMapping.media;
            if (!(message.media instanceof TLRPC.TL_messageMediaEmpty)) {
                message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
            }
            if (mediaMapping.preview != null) {
                snapshot.mediaPreviews.put(
                        ZenoraMediaPreviewRegistry.key(dialogId, messageId), mediaMapping.preview);
            }
            long senderId = row.optLong("last_message_sender_user_id", 0);
            String senderUsername = row.optString("last_message_sender_username", "");
            if (senderId <= 0 && !TextUtils.isEmpty(self.username)
                    && self.username.equalsIgnoreCase(senderUsername)) senderId = self.id;
            if (senderId <= 0 && direct) senderId = row.optLong("other_user_id", 0);
            message.out = senderId > 0 ? senderId == self.id
                    : (!TextUtils.isEmpty(self.username) && self.username.equalsIgnoreCase(senderUsername));
            if (message.out) {
                message.flags |= 1 << 1;
                senderId = self.id;
            }
            if (senderId > 0) {
                if (senderId == self.id) {
                    snapshot.users.put(self.id, self);
                } else {
                    String senderName = row.optString("last_message_sender_display_name", "");
                    String senderAvatar = row.optString("last_message_sender_avatar_url", "");
                    putAvatar(snapshot, "u:" + senderId,
                            ZenoraApiClient.resolvePublicUrl(senderAvatar));
                    // A DM peer may also be the last-message sender. Preserve its
                    // verification bit when this sender object replaces the peer
                    // model in the snapshot's user map.
                    boolean senderIsVerifiedPeer = direct
                            && senderId == row.optLong("other_user_id", 0)
                            && row.optInt("is_verified", 0) == 1;
                    TLRPC.User senderUser = makeUser(
                            senderId, senderUsername, senderName, false, senderIsVerifiedPeer);
                    if (senderUser != null) snapshot.users.put(senderUser.id, senderUser);
                }
                TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                fromPeer.user_id = senderId;
                message.from_id = fromPeer;
                message.flags |= 1 << 8;
            }

            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            messages.add(message);
            snapshot.messages.put(dialogId, messages);
        }
        boolean typingSupported = !saved && !channel
                && ((direct && snapshot.typingDmEnabled)
                || ("group".equals(type) && snapshot.typingGroupEnabled));
        if (typingSupported) snapshot.typingDialogIds.add(dialogId);
        addTypingUsers(snapshot, row, dialogId, typingSupported);
    }

    private static void addTypingUsers(DialogSnapshot snapshot, JSONObject row,
                                       long dialogId, boolean typingSupported) {
        ArrayList<Long> ids = new ArrayList<>();
        JSONArray typers = snapshot.applyEphemeralState && typingSupported
                ? row.optJSONArray("typing_users") : null;
        if (typers != null) {
            for (int i = 0; i < typers.length(); i++) {
                JSONObject typer = typers.optJSONObject(i);
                if (typer == null) continue;
                long userId = typer.optLong("user_id", 0);
                if (userId <= 0 || ids.contains(userId)) continue;
                String username = typer.optString("username", "");
                String displayName = typer.optString("display_name", "");
                TLRPC.User typingUser = makeUser(userId, username, displayName, false);
                if (typingUser != null) snapshot.users.putIfAbsent(userId, typingUser);
                ids.add(userId);
            }
        }
        snapshot.typingUsers.put(dialogId, ids);
    }

    private static void applyUserPresence(int account, TLRPC.User user, JSONObject row,
                                          long serverTime, boolean applyEphemeralState) {
        if (user == null) return;
        user.status = new TLRPC.TL_userStatusEmpty();
        if (!applyEphemeralState || row.optInt("presence_visible", 0) != 1) return;
        int currentTime = ConnectionsManager.getInstance(account).getCurrentTime();
        int onlineRemaining = Math.max(0, row.optInt("online_remaining_seconds", 0));
        if (row.optInt("is_online", 0) == 1 && onlineRemaining > 0) {
            TLRPC.TL_userStatusOnline status = new TLRPC.TL_userStatusOnline();
            status.expires = (int) Math.min(Integer.MAX_VALUE, (long) currentTime + onlineRemaining);
            user.status = status;
            return;
        }
        long lastSeenUnix = row.optLong("other_last_seen_unix", 0);
        if (lastSeenUnix <= 0) return;
        long ageSeconds = row.optLong("last_seen_age_seconds", -1);
        if (ageSeconds < 0) ageSeconds = serverTime > 0 ? Math.max(0, serverTime - lastSeenUnix) : 0;
        TLRPC.TL_userStatusOffline status = new TLRPC.TL_userStatusOffline();
        status.expires = (int) Math.max(Integer.MIN_VALUE, (long) currentTime - ageSeconds);
        user.status = status;
    }

    private static void applyDialogMuteSnapshot(DialogSnapshot snapshot, long dialogId, JSONObject row) {
        boolean muted = row.optInt("is_muted", 0) == 1;
        long until = row.optLong("mute_until", 0);
        snapshot.muteUntil.put(dialogId, toLocalMuteUntil(muted, until, snapshot.serverTime));
    }

    /**
     * Convert an absolute server timestamp into the local wall-clock timestamp
     * expected by Telegram's notification preferences. This avoids making
     * timed mutes shorter/longer when the phone and PHP server clocks differ.
     * A zero server deadline represents a permanent mute only when muted=true.
     */
    private static int toLocalMuteUntil(boolean muted, long serverMuteUntil, long serverTime) {
        if (!muted) return 0;
        if (serverMuteUntil <= 0) return Integer.MAX_VALUE;
        long localNow = System.currentTimeMillis() / 1000L;
        long serverNow = serverTime > 0 ? serverTime : localNow;
        long remaining = serverMuteUntil - serverNow;
        if (remaining <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, localNow + remaining);
    }

    private static void putAvatar(DialogSnapshot snapshot, String key, String url) {
        if (url == null || url.trim().isEmpty()) snapshot.avatars.remove(key);
        else snapshot.avatars.put(key, url.trim());
    }

    /**
     * Intercept all native notification-setting menu actions for Zenora.
     * Timed values are intentionally normalized to a permanent mute; unmute
     * remains an explicit per-chat override removal on the server.
     */
    public static boolean handleMuteSetting(int account, long dialogId, long topicId, int setting) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId == 0) return false;
        if (setting != NotificationsController.SETTING_MUTE_UNMUTE
                && setting != NotificationsController.SETTING_MUTE_HOUR
                && setting != NotificationsController.SETTING_MUTE_8_HOURS
                && setting != NotificationsController.SETTING_MUTE_2_DAYS
                && setting != NotificationsController.SETTING_MUTE_FOREVER) {
            return false;
        }
        boolean muted = setting != NotificationsController.SETTING_MUTE_UNMUTE;
        return submitMuteAction(account, dialogId, topicId, muted, 0);
    }

    /** Intercept legacy duration calls defensively: every Zenora mute is permanent. */
    public static boolean handleMuteUntil(int account, long dialogId, long topicId, int seconds) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId == 0) return false;
        return submitMuteAction(account, dialogId, topicId, true, 0);
    }

    /** Intercept native Telegram mute/unmute toggles while Zenora is the active backend. */
    public static boolean handleMuteDialog(int account, long dialogId, long topicId, boolean mute) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId == 0) return false;
        return submitMuteAction(account, dialogId, topicId, mute, 0);
    }

    private static boolean submitMuteAction(int account, long dialogId, long topicId,
                                            boolean muted, int muteSeconds) {
        if (topicId != 0) {
            Toast.makeText(ApplicationLoader.applicationContext,
                    "تنظیم اعلان موضوع‌ها هنوز در زنوراچت پشتیبانی نمی‌شود.", Toast.LENGTH_LONG).show();
            return true;
        }
        Long roomId = null;
        synchronized (loadLock) {
            Map<Long, Long> mappings = dialogRoomIds.get(account);
            if (mappings != null) roomId = mappings.get(dialogId);
        }
        if (roomId == null || roomId <= 0) {
            Toast.makeText(ApplicationLoader.applicationContext,
                    "شناسه گفتگوی زنورا پیدا نشد؛ فهرست گفتگوها را تازه‌سازی کنید.", Toast.LENGTH_LONG).show();
            return true;
        }
        // Do not accept timed mutes from legacy callers; Zenora Android uses
        // permanent mute/unmute only. The PHP endpoint remains backward-compatible
        // for other clients such as the web app.
        ZenoraApiClient.setChatMute(roomId, muted, 0, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                Toast.makeText(ApplicationLoader.applicationContext,
                        "ذخیره وضعیت بی‌صدا در زنوراچت ناموفق بود.", Toast.LENGTH_LONG).show();
                return;
            }
            long serverMuteUntil = response.optLong("mute_until", 0);
            long serverTime = response.optLong("server_time", 0);
            TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
            settings.flags |= 1 << 2;
            settings.mute_until = toLocalMuteUntil(muted, serverMuteUntil, serverTime);
            NotificationsController.getInstance(account).getNotificationsSettingsFacade()
                    .applyZenoraMuteState(dialogId, settings.mute_until, () ->
                            NotificationCenter.getInstance(account)
                                    .postNotificationName(NotificationCenter.dialogsNeedReload));
        });
        return true;
    }

    private static TLRPC.User makeUser(long id, String username, String displayName, boolean self) {
        return makeUser(id, username, displayName, self, false);
    }

    private static TLRPC.User makeUser(long id, String username, String displayName,
                                       boolean self, boolean verified) {
        if (id <= 0) return null;
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = self;
        user.username = username == null ? "" : username;
        user.first_name = nonEmpty(displayName, nonEmpty(user.username, "ZenoraChat"));
        user.last_name = "";
        user.phone = "";
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.status = new TLRPC.TL_userStatusEmpty();
        user.flags |= (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 6);
        if (self) user.flags |= 1 << 10;
        user.verified = verified;
        if (verified) user.flags |= 1 << 17;
        return user;
    }

    private static String messagePreview(JSONObject row) {
        if (row == null) return "";
        String kind = row.optString("last_message_media_kind", "");
        String body = row.optString("last_message_body", "");
        if ("gif".equals(kind)) body = body.replace(":gif:", "");
        if ("sticker".equals(kind)) {
            int marker = body.indexOf(":sticker:");
            if (marker >= 0) {
                int end = marker + ":sticker:".length();
                while (end < body.length() && !Character.isWhitespace(body.charAt(end))) end++;
                body = body.substring(0, marker) + body.substring(end);
            }
        }
        if (!TextUtils.isEmpty(body.trim())) return body.trim();
        switch (kind) {
            case "photo": return "Photo";
            case "video": return "Video";
            case "video_note": return "Video message";
            case "voice": return "Voice message";
            case "music": return "Music";
            case "gif": return "GIF";
            case "sticker": return "";
            case "document":
            case "file": return "File";
            case "album": return "Album";
            default: return "";
        }
    }

    private static String nonEmpty(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int parseDate(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            long numeric = Long.parseLong(value);
            if (numeric > 100000000000L) numeric /= 1000L;
            if (numeric > 0) return (int) Math.min(Integer.MAX_VALUE, numeric);
        } catch (NumberFormatException ignored) {
        }
        String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                parser.setLenient(false);
                Date parsed = parser.parse(value);
                if (parsed != null) return (int) Math.min(Integer.MAX_VALUE, parsed.getTime() / 1000L);
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}
