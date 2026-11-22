package org.telegram.messenger;

import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads public Zenora avatars into Telegram's native file cache.
 *
 * The native DialogCell/ImageReceiver path remains untouched. A TL photo is
 * attached only after its cache file exists, so Telegram's MTProto FileLoader
 * is never asked to fetch a Zenora avatar.
 */
public final class ZenoraAvatarLoader {
    private static final String ORIGIN = "https://miladalizadw.ir";
    private static final int MAX_AVATAR_BYTES = 6 * 1024 * 1024;
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final Object lock = new Object();
    private static final Set<String> inFlight = new HashSet<>();
    private static final Map<String, String> activeUrls = new HashMap<>();
    private static final Map<String, java.util.ArrayList<PendingAvatar>> pendingAvatars = new HashMap<>();

    private static final class PendingAvatar {
        final int account;
        final String kind;
        final long id;
        final String key;
        final long photoId;
        final TLRPC.TL_fileLocationToBeDeprecated smallLocation;
        final TLRPC.TL_fileLocationToBeDeprecated bigLocation;

        PendingAvatar(int account, String kind, long id, String key, long photoId,
                      TLRPC.TL_fileLocationToBeDeprecated smallLocation,
                      TLRPC.TL_fileLocationToBeDeprecated bigLocation) {
            this.account = account;
            this.kind = kind;
            this.id = id;
            this.key = key;
            this.photoId = photoId;
            this.smallLocation = smallLocation;
            this.bigLocation = bigLocation;
        }
    }

    private ZenoraAvatarLoader() {
    }

    public static void applyUserAvatar(int account, TLRPC.User user, String avatarPath) {
        if (user == null || user.id <= 0) return;
        String key = key(account, "u", user.id);
        String url = resolvePublicUrl(avatarPath);
        if (TextUtils.isEmpty(url)) {
            synchronized (lock) {
                activeUrls.remove(key);
            }
            user.photo = new TLRPC.TL_userProfilePhotoEmpty();
            return;
        }

        synchronized (lock) {
            activeUrls.put(key, url);
        }

        long photoId = makePhotoId(url);
        TLRPC.TL_fileLocationToBeDeprecated smallLocation = makeLocation(photoId, false);
        TLRPC.TL_fileLocationToBeDeprecated bigLocation = makeLocation(photoId, true);
        File cached = FileLoader.getInstance(account).getPathToAttach(smallLocation, true);
        File cachedBig = FileLoader.getInstance(account).getPathToAttach(bigLocation, true);
        if (isValidCachedImage(cached)) {
            if (!isValidCachedImage(cachedBig)) copyCacheFile(cached, cachedBig);
            user.photo = makeUserPhoto(photoId, smallLocation, bigLocation);
            ensureUserAccessHash(user, photoId);
            return;
        }

        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        enqueueDownload(account, "u", user.id, key, url, photoId, smallLocation, bigLocation);
    }

    public static void applyChatAvatar(int account, TLRPC.Chat chat, String avatarPath) {
        if (chat == null || chat.id <= 0) return;
        String key = key(account, "c", chat.id);
        String url = resolvePublicUrl(avatarPath);
        if (TextUtils.isEmpty(url)) {
            synchronized (lock) {
                activeUrls.remove(key);
            }
            chat.photo = new TLRPC.TL_chatPhotoEmpty();
            return;
        }

        synchronized (lock) {
            activeUrls.put(key, url);
        }

        long photoId = makePhotoId(url);
        TLRPC.TL_fileLocationToBeDeprecated smallLocation = makeLocation(photoId, false);
        TLRPC.TL_fileLocationToBeDeprecated bigLocation = makeLocation(photoId, true);
        File cached = FileLoader.getInstance(account).getPathToAttach(smallLocation, true);
        File cachedBig = FileLoader.getInstance(account).getPathToAttach(bigLocation, true);
        if (isValidCachedImage(cached)) {
            if (!isValidCachedImage(cachedBig)) copyCacheFile(cached, cachedBig);
            chat.photo = makeChatPhoto(photoId, smallLocation, bigLocation);
            ensureChatAccessHash(chat, photoId);
            return;
        }

        chat.photo = new TLRPC.TL_chatPhotoEmpty();
        enqueueDownload(account, "c", chat.id, key, url, photoId, smallLocation, bigLocation);
    }

    private static void enqueueDownload(int account, String kind, long id, String key,
                                        String url, long photoId,
                                        TLRPC.TL_fileLocationToBeDeprecated smallLocation,
                                        TLRPC.TL_fileLocationToBeDeprecated bigLocation) {
        boolean startDownload = false;
        synchronized (lock) {
            java.util.ArrayList<PendingAvatar> pending = pendingAvatars.get(url);
            if (pending == null) {
                pending = new java.util.ArrayList<>();
                pendingAvatars.put(url, pending);
            }
            pending.add(new PendingAvatar(account, kind, id, key, photoId, smallLocation, bigLocation));
            if (inFlight.add(url)) startDownload = true;
        }
        if (!startDownload) return;

        executor.execute(() -> {
            boolean downloaded = false;
            try {
                File smallTarget = FileLoader.getInstance(account).getPathToAttach(smallLocation, true);
                File bigTarget = FileLoader.getInstance(account).getPathToAttach(bigLocation, true);
                downloaded = isValidCachedImage(smallTarget) || downloadToCache(url, smallTarget);
                if (downloaded && !isValidCachedImage(bigTarget)) copyCacheFile(smallTarget, bigTarget);
            } catch (Throwable error) {
                FileLog.w("[ZenoraChat] avatar cache failed (" + error.getClass().getSimpleName() + ")");
            }

            java.util.ArrayList<PendingAvatar> completed;
            synchronized (lock) {
                inFlight.remove(url);
                completed = pendingAvatars.remove(url);
            }
            if (!downloaded || completed == null) return;

            for (PendingAvatar item : completed) {
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (lock) {
                        if (!url.equals(activeUrls.get(item.key))) return;
                    }

                    MessagesController controller = MessagesController.getInstance(item.account);
                    if ("u".equals(item.kind)) {
                        TLRPC.User current = controller.getUsers().get(item.id);
                        if (current == null) return;
                        current.photo = makeUserPhoto(item.photoId, item.smallLocation, item.bigLocation);
                        ensureUserAccessHash(current, item.photoId);
                        controller.putUser(current, false, true);
                    } else {
                        TLRPC.Chat current = controller.getChats().get(item.id);
                        if (current == null) return;
                        current.photo = makeChatPhoto(item.photoId, item.smallLocation, item.bigLocation);
                        ensureChatAccessHash(current, item.photoId);
                        controller.putChat(current, false);
                    }
                    NotificationCenter.getInstance(item.account)
                            .postNotificationName(NotificationCenter.dialogsNeedReload);
                });
            }
        });
    }

    private static boolean downloadToCache(String url, File target) {
        HttpURLConnection connection = null;
        File temporary = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            // Avatar origins are allow-listed above; do not follow redirects to a different host.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/1.0");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return false;
            long contentLength = connection.getContentLength();
            if (contentLength > MAX_AVATAR_BYTES) return false;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_AVATAR_BYTES) return false;
                    buffer.write(chunk, 0, read);
                }
            }
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) return false;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 8192 || bounds.outHeight > 8192) {
                return false;
            }

            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
                return false;
            }
            temporary = new File(parent, target.getName() + ".zenora.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!temporary.renameTo(target)) return false;
            }
            return isValidCachedImage(target);
        } catch (Throwable error) {
            FileLog.w("[ZenoraChat] avatar download failed (" + error.getClass().getSimpleName() + ")");
            return false;
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isValidCachedImage(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private static TLRPC.TL_userProfilePhoto makeUserPhoto(long photoId,
                                                            TLRPC.TL_fileLocationToBeDeprecated smallLocation,
                                                            TLRPC.TL_fileLocationToBeDeprecated bigLocation) {
        TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
        photo.photo_id = photoId;
        photo.photo_small = smallLocation;
        photo.photo_big = bigLocation;
        photo.dc_id = 0;
        return photo;
    }

    private static TLRPC.TL_chatPhoto makeChatPhoto(long photoId,
                                                     TLRPC.TL_fileLocationToBeDeprecated smallLocation,
                                                     TLRPC.TL_fileLocationToBeDeprecated bigLocation) {
        TLRPC.TL_chatPhoto photo = new TLRPC.TL_chatPhoto();
        photo.photo_id = photoId;
        photo.photo_small = smallLocation;
        photo.photo_big = bigLocation;
        photo.dc_id = 0;
        return photo;
    }

    private static void ensureUserAccessHash(TLRPC.User user, long photoId) {
        if (user.access_hash == 0) {
            user.access_hash = photoId;
            user.flags |= 1;
        }
    }

    private static void ensureChatAccessHash(TLRPC.Chat chat, long photoId) {
        if (chat instanceof TLRPC.TL_channel && chat.access_hash == 0) {
            chat.access_hash = photoId;
            chat.flags |= 1 << 13;
        }
    }

    /**
     * Telegram's current TL_userProfilePhoto/TL_chatPhoto serializers rebuild
     * their locations from photo_id: volume_id=-photo_id, local_id='a' for
     * small and 'c' for big. The cache paths must follow that exact contract
     * or the avatar disappears after the model is restored from local storage.
     */
    private static long makePhotoId(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(url.getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
            return value == 0 ? 1L : value;
        } catch (Exception error) {
            long value = ((long) url.hashCode()) & 0xffffffffL;
            return value == 0 ? 1L : value;
        }
    }

    private static TLRPC.TL_fileLocationToBeDeprecated makeLocation(long photoId, boolean big) {
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = -photoId;
        location.local_id = big ? 'c' : 'a';
        location.dc_id = 0;
        return location;
    }

    private static boolean copyCacheFile(File source, File target) {
        if (!isValidCachedImage(source)) return false;
        if (isValidCachedImage(target)) return true;
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            return false;
        }
        File temporary = new File(parent, target.getName() + ".zenora.tmp");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = input.read(chunk)) != -1) output.write(chunk, 0, read);
            output.flush();
            output.getFD().sync();
            if (!temporary.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!temporary.renameTo(target)) return false;
            }
            return isValidCachedImage(target);
        } catch (Throwable error) {
            FileLog.w("[ZenoraChat] avatar cache mirror failed (" + error.getClass().getSimpleName() + ")");
            return false;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static String resolvePublicUrl(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) return "";

        try {
            URL url;
            if (path.startsWith("https://") || path.startsWith("http://")) {
                url = new URL(path);
                if (!"miladalizadw.ir".equalsIgnoreCase(url.getHost())
                        || !"https".equalsIgnoreCase(url.getProtocol())) {
                    return "";
                }
            } else {
                if (!path.startsWith("/")) {
                    path = path.startsWith("uploads/") ? "/telegram/" + path : "/" + path;
                }
                if (!path.startsWith("/telegram/uploads/")) return "";
                url = new URL(ORIGIN + path);
            }
            if (!url.getPath().startsWith("/telegram/uploads/")) return "";
            return url.toExternalForm();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String key(int account, String kind, long id) {
        return account + ":" + kind + ":" + id;
    }
}
