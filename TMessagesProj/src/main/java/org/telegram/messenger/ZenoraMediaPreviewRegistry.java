package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-account URL registry for compact Zenora conversation-list media previews. */
public final class ZenoraMediaPreviewRegistry {
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<String, Preview>> SNAPSHOTS = new ConcurrentHashMap<>();
    private ZenoraMediaPreviewRegistry() {}

    public static final class Attachment {
        public final String kind, mime, chatListThumbnailUrl;
        public final boolean showPlayIcon, roundVideo;
        public Attachment(String kind, String mime, String url) {
            this.kind = clean(kind);
            this.mime = clean(mime);
            chatListThumbnailUrl = clean(url);
            roundVideo = "video_note".equals(this.kind);
            showPlayIcon = "video".equals(this.kind) || roundVideo;
        }
    }

    public static final class Preview {
        public final String kind, signature, audioTitle, audioArtist, audioAlbum;
        public final int attachmentCount, moreCount;
        public final List<Attachment> attachments;
        public Preview(String kind, String signature, int attachmentCount, int moreCount,
                       String audioTitle, String audioArtist, String audioAlbum,
                       List<Attachment> attachments) {
            this.kind = clean(kind);
            this.signature = clean(signature);
            this.attachmentCount = Math.max(0, attachmentCount);
            this.moreCount = Math.max(0, moreCount);
            this.audioTitle = clean(audioTitle);
            this.audioArtist = clean(audioArtist);
            this.audioAlbum = clean(audioAlbum);
            ArrayList<Attachment> copy = new ArrayList<>();
            if (attachments != null) {
                for (Attachment item : attachments) {
                    if (item != null) copy.add(item);
                    if (copy.size() == 3) break;
                }
            }
            this.attachments = Collections.unmodifiableList(copy);
        }
    }

    public static String key(long dialogId, int messageId) {
        return dialogId + ":" + messageId;
    }

    /** Atomically replaces one account's complete media-preview snapshot. */
    public static void replaceSnapshot(int account, Map<String, Preview> snapshot) {
        ConcurrentHashMap<String, Preview> next = new ConcurrentHashMap<>();
        if (snapshot != null) {
            for (Map.Entry<String, Preview> item : snapshot.entrySet()) {
                if (item.getKey() != null && item.getValue() != null) next.put(item.getKey(), item.getValue());
            }
        }
        SNAPSHOTS.put(account, next);
    }

    public static Preview get(int account, long dialogId, int messageId) {
        Map<String, Preview> snapshot = SNAPSHOTS.get(account);
        return snapshot == null ? null : snapshot.get(key(dialogId, messageId));
    }

    public static void clear(int account) {
        SNAPSHOTS.remove(account);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
