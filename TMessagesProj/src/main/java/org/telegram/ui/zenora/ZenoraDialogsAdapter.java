package org.telegram.ui.zenora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.DialogsActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Maps Zenora REST chat records onto Telegram's original DialogCell. */
public final class ZenoraDialogsAdapter extends RecyclerView.Adapter<ZenoraDialogsAdapter.Holder> {
    private static final String ORIGIN = "https://miladalizadw.ir";
    private static final String WEB_BASE = ORIGIN + "/telegram/";
    private static final String HOST = "miladalizadw.ir";
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService AVATAR_IO = Executors.newFixedThreadPool(2);

    private static final class Item {
        long roomId;
        long stableId;
        String name = "";
        String preview = "";
        String avatarPath = "";
        int unread;
        int date;
        int pinnedOrder;
        boolean pinned;
        boolean muted;
        boolean verified;
        boolean isPrivate;
        boolean isSaved;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final DialogCell cell;
        Holder(DialogCell cell) {
            super(cell);
            this.cell = cell;
        }
    }

    private final Context context;
    private final DialogsActivity parent;
    private final int account;
    private final boolean archivedOnly;
    private final ArrayList<Item> items = new ArrayList<>();

    public ZenoraDialogsAdapter(Context context, DialogsActivity parent, int account, boolean archivedOnly) {
        this.context = context.getApplicationContext();
        this.parent = parent;
        this.account = account;
        this.archivedOnly = archivedOnly;
        setHasStableIds(true);
    }

    public void submitRooms(JSONObject response) {
        ArrayList<Item> next = new ArrayList<>();
        append(next, response.optJSONArray("dm_rooms"), true);
        append(next, response.optJSONArray("rooms"), false);

        JSONObject savedRoom = response.optJSONObject("saved_room");
        if (savedRoom != null) {
            long savedId = savedRoom.optLong("id", 0);
            boolean alreadyAdded = false;
            for (Item item : next) {
                if (item.isSaved && item.roomId == savedId) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) appendOne(next, savedRoom, true, true);
        }

        Collections.sort(next, (left, right) -> {
            if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
            if (left.pinned && right.pinned && left.pinnedOrder != right.pinnedOrder) {
                return Integer.compare(left.pinnedOrder, right.pinnedOrder);
            }
            return Integer.compare(right.date, left.date);
        });

        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    private void append(ArrayList<Item> target, JSONArray source, boolean privateChats) {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row != null) appendOne(target, row, privateChats, false);
        }
    }

    private void appendOne(ArrayList<Item> target, JSONObject row, boolean privateChats, boolean forceSaved) {
        long roomId = row.optLong("id", 0);
        if (roomId <= 0) return;

        boolean saved = forceSaved || "saved".equals(row.optString("type", ""));
        boolean archived = row.optInt("is_archived", 0) == 1;
        if (archivedOnly != archived) return;

        Item item = new Item();
        item.roomId = roomId;
        item.isSaved = saved;
        item.isPrivate = privateChats;
        item.stableId = stableId(roomId, saved ? 3 : (privateChats ? 2 : 1));

        if (saved) {
            item.name = "پیام‌های ذخیره‌شده";
            item.avatarPath = "";
        } else if (privateChats) {
            // rooms.php exposes peer-specific fields for DMs.
            item.name = firstNonEmpty(
                    row.optString("other_display_name", ""),
                    row.optString("other_username", ""),
                    "کاربر زنورا"
            );
            item.avatarPath = row.optString("other_avatar_path", "");
            item.verified = row.optInt("other_is_verified", 0) == 1;
        } else {
            item.name = firstNonEmpty(row.optString("name", ""), "", "گفتگو");
            item.avatarPath = row.optString("avatar_path", "");
            item.verified = row.optInt("is_verified", 0) == 1;
        }

        item.preview = messagePreview(row, privateChats, saved);
        item.unread = Math.max(0, row.optInt("unread_count", 0));
        item.date = parseDate(row.optString("last_message_time", ""));
        item.pinned = row.optInt("is_pinned", 0) == 1;
        item.pinnedOrder = row.optInt("pin_order", 0);
        item.muted = row.optInt("is_muted", 0) == 1;
        target.add(item);
    }

    private static long stableId(long roomId, int type) {
        return (((long) type) << 60) | (roomId & 0x0FFFFFFFFFFFFFFFL);
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        if (second != null && !second.trim().isEmpty()) return second.trim();
        return fallback;
    }

    private static String messagePreview(JSONObject row, boolean privateChat, boolean saved) {
        String body = row.optString("last_message_body", "").trim();
        String media = row.optString("last_message_media_kind", "");
        if (body.startsWith(":gif:")) {
            String caption = body.substring(5).trim();
            body = caption.isEmpty() ? "GIF" : "GIF · " + caption;
        } else if (body.startsWith(":sticker:")) {
            body = "استیکر";
        }
        if (body.isEmpty()) body = mediaPreview(media);

        String sender = row.optString("last_message_sender_display_name", "").trim();
        if (!body.isEmpty() && !sender.isEmpty()) {
            if (saved || "شما".equals(sender) || "You".equalsIgnoreCase(sender)) {
                return "شما: " + body;
            }
            if (!privateChat) return sender + ": " + body;
        }
        return body;
    }

    private static String mediaPreview(String kind) {
        switch (kind == null ? "" : kind) {
            case "photo":
            case "image": return "تصویر";
            case "video": return "ویدئو";
            case "video_note": return "پیام ویدئویی";
            case "voice":
            case "audio": return "پیام صوتی";
            case "music": return "موسیقی";
            case "file":
            case "document": return "فایل";
            case "sticker": return "استیکر";
            case "gif": return "GIF";
            default: return "";
        }
    }

    private static int parseDate(String value) {
        if (value == null || value.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getDefault());
                Date date = format.parse(value);
                if (date != null) return (int) (date.getTime() / 1000L);
            } catch (Exception ignore) {}
        }
        return 0;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stableId;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parentView, int viewType) {
        DialogCell cell = new DialogCell(parent, parentView.getContext(), true, false, account, null);
        cell.useSeparator = true;
        return new Holder(cell);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        Item item = items.get(position);
        DialogCell.CustomDialog model = new DialogCell.CustomDialog();
        model.id = (int) (item.roomId % Integer.MAX_VALUE);
        model.name = item.name;
        model.message = item.preview;
        model.date = item.date;
        model.unread_count = item.unread;
        model.pinned = item.pinned;
        model.muted = item.muted;
        model.verified = item.verified;
        model.type = 0;

        holder.cell.useSeparator = position < items.size() - 1;
        holder.cell.fullSeparator = false;
        holder.cell.setDialog(model);
        holder.cell.setCurrentDialogId(item.roomId);
        loadAvatar(holder, item);
    }

    private static String resolveAvatar(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        try {
            String value = path.trim();
            URL url;
            if (value.startsWith("https://") || value.startsWith("http://")) {
                url = new URL(value);
            } else if (value.startsWith("/telegram/")) {
                url = new URL(ORIGIN + value);
            } else if (value.startsWith("/uploads/")) {
                url = new URL(WEB_BASE + value.substring(1));
            } else if (value.startsWith("/")) {
                url = new URL(ORIGIN + value);
            } else {
                url = new URL(WEB_BASE + value.replaceFirst("^\\./", ""));
            }

            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !HOST.equalsIgnoreCase(url.getHost())
                    || (url.getPort() != -1 && url.getPort() != 443)) return "";
            return url.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void loadAvatar(Holder holder, Item item) {
        final String url = resolveAvatar(item.avatarPath);
        if (url.isEmpty()) return;

        final long stableId = item.stableId;
        final int expectedPosition = holder.getAdapterPosition();
        if (expectedPosition == RecyclerView.NO_POSITION) return;

        AVATAR_IO.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "image/*");
                connection.setRequestProperty("User-Agent", "ZenoraChat-Android/Stage1");

                int status = connection.getResponseCode();
                String contentType = connection.getContentType();
                int size = connection.getContentLength();
                if (status == HttpURLConnection.HTTP_OK
                        && (contentType == null || contentType.toLowerCase(Locale.ROOT).startsWith("image/"))
                        && (size < 0 || size <= MAX_AVATAR_BYTES)) {
                    try (InputStream input = connection.getInputStream()) {
                        byte[] bytes = readAvatar(input);
                        if (bytes != null) {
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 256);
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        }
                    }
                }
            } catch (Exception ignore) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            final Bitmap result = bitmap;
            if (result == null) return;
            AndroidUtilities.runOnUIThread(() -> {
                int current = holder.getAdapterPosition();
                if (current == RecyclerView.NO_POSITION || current != expectedPosition || current >= items.size()) return;
                if (items.get(current).stableId != stableId) return;
                holder.cell.avatarImage.setImageBitmap(new BitmapDrawable(context.getResources(), result));
            });
        });
    }

    private static byte[] readAvatar(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_AVATAR_BYTES) return null;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }
}
