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
            synchronized (loadLock) {
                snapshotAvailable.remove(account);
                cacheRestoreStarted.remove(account);
                lastSuccessfulLoad.remove(account);
                retryAttempts.remove(account);
                snapshotUserIds.remove(account);
                dialogRoomIds.remove(account);
                savedDialogIds.remove(account);
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
            retryHandler.post(() -> {
                synchronized (loadLock) {
                    if (snapshotAvailable.contains(account)) return;
                }
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
        final HashMap<String, String> avatars = new HashMap<>();
        final ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> mainDialogs = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> archivedDialogs = new ArrayList<>();
        final Set<Long> savedDialogs = new HashSet<>();
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
        snapshot.users.put(self.id, self);
        for (int i = 0; i < rooms.length(); i++) {
            addDialog(account, snapshot, self, rooms.getJSONObject(i), false);
        }
        for (int i = 0; i < directRooms.length(); i++) {
            addDialog(account, snapshot, self, directRooms.getJSONObject(i), true);
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

        synchronized (loadLock) {
            dialogRoomIds.put(account, new HashMap<>(snapshot.roomIds));
            savedDialogIds.put(account, new HashSet<>(snapshot.savedDialogs));
            snapshotUserIds.put(account, meJson.optLong("id", 0));
        }
        ZenoraAvatarRegistry.replaceSnapshot(account, snapshot.avatars);
        for (Map.Entry<Long, Integer> mute : snapshot.muteUntil.entrySet()) {
            NotificationsController.getInstance(account).getNotificationsSettingsFacade()
                    .applyZenoraMuteState(mute.getKey(), mute.getValue());
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
            TLRPC.TL_chat savedChat = new TLRPC.TL_chat();
            savedChat.id = roomId;
            savedChat.title = nonEmpty(row.optString("name", ""), "پیام‌های ذخیره‌شده");
            savedChat.photo = new TLRPC.TL_chatPhotoEmpty();
            savedChat.date = parseDate(row.optString("last_message_time", ""));
            snapshot.chats.put(savedChat.id, savedChat);
            TLRPC.TL_peerChat savedPeer = new TLRPC.TL_peerChat();
            savedPeer.chat_id = roomId;
            peer = savedPeer;
            dialogId = -roomId;
        } else if (direct) {
            long otherId = row.optLong("other_user_id", 0);
            if (otherId <= 0) return;
            putAvatar(snapshot, "u:" + otherId,
                    ZenoraApiClient.resolvePublicUrl(row.optString("avatar_url", "")));
            TLRPC.User other = makeUser(otherId,
                    row.optString("other_username", ""),
                    row.optString("other_display_name", ""), false);
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
            message.message = messagePreview(row);
            message.media = new TLRPC.TL_messageMediaEmpty();
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
                    TLRPC.User senderUser = makeUser(senderId, senderUsername, senderName, false);
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
    }

    private static void applyDialogMuteSnapshot(DialogSnapshot snapshot, long dialogId, JSONObject row) {
        boolean muted = row.optInt("is_muted", 0) == 1;
        long until = row.optLong("mute_until", 0);
        int muteUntil = !muted ? 0 : until <= 0
                ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, until);
        snapshot.muteUntil.put(dialogId, muteUntil);
    }

    private static void putAvatar(DialogSnapshot snapshot, String key, String url) {
        if (url == null || url.trim().isEmpty()) snapshot.avatars.remove(key);
        else snapshot.avatars.put(key, url.trim());
    }

    /** Intercept native Telegram mute-duration actions while Zenora is the active backend. */
    public static boolean handleMuteUntil(int account, long dialogId, long topicId, int seconds) {
        if (!ZenoraApiClient.isZenoraSessionEnabled() || dialogId == 0) return false;
        return submitMuteAction(account, dialogId, topicId, true,
                seconds == Integer.MAX_VALUE ? 0 : Math.max(1, seconds));
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
        ZenoraApiClient.setChatMute(roomId, muted, muteSeconds, (response, error) -> {
            if (error != null) {
                FileLog.e(error);
                Toast.makeText(ApplicationLoader.applicationContext,
                        "ذخیره وضعیت بی‌صدا در زنوراچت ناموفق بود.", Toast.LENGTH_LONG).show();
                return;
            }
            TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
            settings.flags |= 1 << 2;
            settings.mute_until = !muted ? 0 : muteSeconds <= 0
                    ? Integer.MAX_VALUE
                    : (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() / 1000L + muteSeconds);
            NotificationsController.getInstance(account).getNotificationsSettingsFacade()
                    .applyZenoraMuteState(dialogId, settings.mute_until);
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.dialogsNeedReload);
        });
        return true;
    }

    private static TLRPC.User makeUser(long id, String username, String displayName, boolean self) {
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
        return user;
    }

    private static String messagePreview(JSONObject row) {
        String body = row.optString("last_message_body", "");
        if (!TextUtils.isEmpty(body)) return body;
        switch (row.optString("last_message_media_kind", "")) {
            case "photo": return "Photo";
            case "video": return "Video";
            case "video_note": return "Video message";
            case "voice": return "Voice message";
            case "music": return "Music";
            case "gif": return "GIF";
            case "file": return "File";
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
