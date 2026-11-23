package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Converts the rooms.php preview contract to Telegram-native media classification
 * plus an independent registry of compact HTTPS thumbnails. No MTProto locations
 * or data-center identifiers are fabricated for Zenora-hosted uploads.
 */
public final class ZenoraMediaPreviewMapper {
    private static final int MAX_VERSION = 3, MAX_ATTACHMENTS = 3, MAX_DIMENSION = 16384;
    private static final double MAX_DURATION = 604800d;
    private ZenoraMediaPreviewMapper() {}

    public static final class Mapping {
        public final TLRPC.MessageMedia media;
        public final String messageText;
        public final ZenoraMediaPreviewRegistry.Preview preview;
        private Mapping(TLRPC.MessageMedia media, String text, ZenoraMediaPreviewRegistry.Preview preview) {
            this.media = media == null ? new TLRPC.TL_messageMediaEmpty() : media;
            messageText = text == null ? "" : text;
            this.preview = preview;
        }
    }

    private static final class Item {
        long uploadId, size;
        String kind="", mime="", name="", compactUrl="", title="", artist="", album="";
        double duration;
        int width, height;
    }

    public static Mapping map(long dialogId, int messageId, int date, JSONObject row) {
        JSONObject api = row == null ? null : row.optJSONObject("last_message_preview");
        if (api == null) return new Mapping(new TLRPC.TL_messageMediaEmpty(), legacyText(row), null);
        int version = api.optInt("preview_version", 0);
        if (version < 1 || version > MAX_VERSION) {
            FileLog.w("[ZenoraChat] unsupported media preview version " + version);
            return new Mapping(new TLRPC.TL_messageMediaEmpty(), legacyText(row), null);
        }
        String kind = normalizeKind(api.optString("kind",
                row == null ? "" : row.optString("last_message_media_kind", "")));
        String caption = cleanCaption(api.optString("caption",
                row == null ? "" : row.optString("last_message_body", "")), kind);
        ArrayList<Item> items = parseItems(api.optJSONArray("attachments"), kind, api);
        int total = Math.max(api.optInt("attachment_count", 0), items.size());
        JSONObject album = api.optJSONObject("album");
        if (total == 0 && album != null) total = Math.max(0, album.optInt("attachment_count", 0));
        Item first = items.isEmpty() ? null : items.get(0);
        TLRPC.MessageMedia media = makeMedia(kind, first, messageId, date);

        ArrayList<ZenoraMediaPreviewRegistry.Attachment> compact = new ArrayList<>();
        for (Item item : items) compact.add(new ZenoraMediaPreviewRegistry.Attachment(
                item.kind, item.mime, resolveCompactUrl(item.compactUrl)));

        JSONObject audio = api.optJSONObject("audio");
        String title = audio == null ? "" : limit(audio.optString("title", ""), 200);
        String artist = audio == null ? "" : limit(audio.optString("artist", ""), 200);
        String audioAlbum = audio == null ? "" : limit(audio.optString("album", ""), 200);
        if (first != null) {
            if (TextUtils.isEmpty(title)) title = first.title;
            if (TextUtils.isEmpty(artist)) artist = first.artist;
            if (TextUtils.isEmpty(audioAlbum)) audioAlbum = first.album;
        }
        int more = album == null ? Math.max(0, total - compact.size())
                : Math.max(0, album.optInt("more_count", total - compact.size()));
        ZenoraMediaPreviewRegistry.Preview preview = new ZenoraMediaPreviewRegistry.Preview(
                kind, api.optString("signature", ""), total, more,
                title, artist, audioAlbum, compact);
        if (TextUtils.isEmpty(caption) && "album".equals(kind) && total > 1) caption = "Album";
        return new Mapping(media, caption, preview);
    }

    private static ArrayList<Item> parseItems(JSONArray array, String logicalKind, JSONObject root) {
        ArrayList<Item> result = new ArrayList<>();
        if (array != null) {
            for (int i=0; i<array.length() && result.size()<MAX_ATTACHMENTS; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) result.add(parseItem(object, logicalKind));
            }
        }
        // Compatibility for old single-file snapshots. Never substitute a viewer
        // poster, legacy thumbnail or original file for the dedicated list image.
        if (result.isEmpty() && !"album".equals(logicalKind) && !"text".equals(logicalKind)) {
            result.add(parseItem(root, logicalKind));
        }
        return result;
    }

    private static Item parseItem(JSONObject object, String fallbackKind) {
        Item item = new Item();
        item.uploadId = Math.max(0, object.optLong("upload_id", 0));
        item.kind = normalizeKind(object.optString("kind", object.optString("media_kind", fallbackKind)));
        item.mime = normalizeMime(object.optString("mime", ""));
        item.name = safeName(object.optString("original_name", object.optString("name", "")));
        item.compactUrl = object.optString("chat_list_thumbnail_url", "");
        item.size = Math.max(0, object.optLong("size_bytes", 0));
        item.duration = safeDuration(object.optDouble("duration", object.optDouble("media_duration", 0)));
        item.width = safeDimension(object.optInt("width", object.optInt("media_width", object.optInt("image_width", 0))));
        item.height = safeDimension(object.optInt("height", object.optInt("media_height", object.optInt("image_height", 0))));
        JSONObject audio = object.optJSONObject("audio");
        if (audio != null) {
            item.title = limit(audio.optString("title", ""), 200);
            item.artist = limit(audio.optString("artist", ""), 200);
            item.album = limit(audio.optString("album", ""), 200);
        } else {
            item.title = limit(object.optString("audio_title", ""), 200);
            item.artist = limit(object.optString("audio_artist", ""), 200);
            item.album = limit(object.optString("audio_album", ""), 200);
        }
        return item;
    }

    private static TLRPC.MessageMedia makeMedia(String logicalKind, Item first, int messageId, int date) {
        if (first == null || "text".equals(logicalKind)) return new TLRPC.TL_messageMediaEmpty();
        // One logical Zenora album remains one Telegram Message. The first ordered
        // item determines TL classification; DialogCell renders the separate strip.
        String kind = "album".equals(logicalKind) ? first.kind : logicalKind;
        if ("photo".equals(kind)) {
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = first.uploadId > 0 ? first.uploadId : Math.max(1, messageId);
            photo.file_reference = new byte[0];
            photo.date = date;
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = photo;
            media.flags |= 1;
            return media;
        }

        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = first.uploadId > 0 ? first.uploadId : Math.max(1, messageId);
        document.file_reference = new byte[0];
        document.date = date;
        document.mime_type = TextUtils.isEmpty(first.mime) ? defaultMime(kind) : first.mime;
        document.size = first.size;
        document.file_name = TextUtils.isEmpty(first.name)
                ? defaultName(kind, document.mime_type, document.id) : first.name;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = document.file_name;
        document.attributes.add(filename);

        if ("voice".equals(kind) || "music".equals(kind)) {
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = first.duration;
            audio.voice = "voice".equals(kind);
            audio.title = first.title;
            audio.performer = first.artist;
            if (!TextUtils.isEmpty(audio.title)) audio.flags |= 1;
            if (!TextUtils.isEmpty(audio.performer)) audio.flags |= 1 << 1;
            if (audio.voice) audio.flags |= 1 << 10;
            document.attributes.add(audio);
        } else if ("video".equals(kind) || "video_note".equals(kind)
                || ("gif".equals(kind) && "video/mp4".equals(document.mime_type))) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.duration = first.duration;
            video.w = first.width;
            video.h = first.height;
            video.round_message = "video_note".equals(kind);
            video.supports_streaming = true;
            video.flags |= 1 << 1;
            if (video.round_message) video.flags |= 1;
            document.attributes.add(video);
            if ("gif".equals(kind)) document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        }
        if ("sticker".equals(kind)) {
            TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
            sticker.alt = "";
            sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
            document.attributes.add(sticker);
            // MessageObject's TGS detector requires a non-empty thumbs vector.
            if ("application/x-tgsticker".equals(document.mime_type)) {
                TLRPC.TL_photoSizeEmpty marker = new TLRPC.TL_photoSizeEmpty();
                marker.type = "s";
                document.thumbs.add(marker);
                document.flags |= 1;
            }
        }

        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        media.flags |= 1;
        if ("video".equals(kind)) {
            media.video = true;
            media.flags |= 1 << 6;
        } else if ("video_note".equals(kind)) {
            media.video = true;
            media.round = true;
            media.flags |= (1 << 6) | (1 << 7);
        } else if ("voice".equals(kind)) {
            media.voice = true;
            media.flags |= 1 << 8;
        }
        return media;
    }

    private static String normalizeKind(String input) {
        String kind = input == null ? "" : input.trim().toLowerCase(Locale.US);
        if ("file".equals(kind)) return "document";
        switch (kind) {
            case "photo": case "video": case "video_note": case "voice": case "music":
            case "gif": case "sticker": case "document": case "album": case "text":
                return kind;
            default: return TextUtils.isEmpty(kind) ? "text" : "document";
        }
    }
    private static String normalizeMime(String input) {
        String mime = input == null ? "" : input.trim().toLowerCase(Locale.US);
        int p = mime.indexOf(';');
        return p < 0 ? mime : mime.substring(0, p).trim();
    }
    private static String defaultMime(String kind) {
        switch (kind) {
            case "photo": return "image/jpeg";
            case "video": case "video_note": case "gif": return "video/mp4";
            case "voice": return "audio/ogg";
            case "music": return "audio/mpeg";
            case "sticker": return "image/webp";
            default: return "application/octet-stream";
        }
    }
    private static String defaultName(String kind, String mime, long id) {
        String ext;
        switch (mime) {
            case "image/jpeg": ext="jpg"; break; case "image/png": ext="png"; break;
            case "image/gif": ext="gif"; break; case "image/webp": ext="webp"; break;
            case "video/webm": ext="webm"; break; case "audio/ogg": ext="ogg"; break;
            case "audio/flac": ext="flac"; break; case "audio/mpeg": ext="mp3"; break;
            default: ext="bin"; break;
        }
        return kind+"-"+id+"."+ext;
    }
    private static String safeName(String value) {
        String name=value==null?"":value.replace('\\','/');
        int p=name.lastIndexOf('/');
        if(p>=0)name=name.substring(p+1);
        return limit(name,255);
    }
    private static String limit(String value,int max) {
        String text=value==null?"":value.trim();
        return text.length()>max?text.substring(0,max):text;
    }
    private static double safeDuration(double value) {
        if(Double.isNaN(value)||Double.isInfinite(value)||value<0)return 0;
        return Math.min(value,MAX_DURATION);
    }
    private static int safeDimension(int value) {
        return value<=0?0:Math.min(value,MAX_DIMENSION);
    }
    private static String resolveCompactUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String resolved = ZenoraApiClient.resolvePublicUrl(raw);
        if (!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(resolved)) {
            return "";
        }
        return resolved;
    }
    private static String cleanCaption(String input,String kind) {
        String text=input==null?"":input;
        if("gif".equals(kind))text=text.replace(":gif:","");
        if("sticker".equals(kind)){
            int p=text.indexOf(":sticker:");
            if(p>=0){
                int end=p+":sticker:".length();
                while(end<text.length()&&!Character.isWhitespace(text.charAt(end)))end++;
                text=text.substring(0,p)+text.substring(end);
            }
        }
        return text.trim();
    }
    private static String legacyText(JSONObject row) {
        if(row==null)return "";
        String kind=normalizeKind(row.optString("last_message_media_kind",""));
        String body=cleanCaption(row.optString("last_message_body",""),kind);
        if(!TextUtils.isEmpty(body))return body;
        switch(kind){
            case "photo":return "Photo"; case "video":return "Video";
            case "video_note":return "Video message"; case "voice":return "Voice message";
            case "music":return "Music"; case "gif":return "GIF";
            case "sticker":return ""; case "document":return "File";
            case "album":return "Album"; default:return "";
        }
    }
}
