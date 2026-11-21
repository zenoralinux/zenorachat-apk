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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uses Telegram's own DialogCell for Zenora records. No Telegram Dialog,
 * UserConfig server identity or MTProto data is used for the rendered rows.
 */
public final class ZenoraDialogsAdapter extends RecyclerView.Adapter<ZenoraDialogsAdapter.Holder> {
    private static final String WEB_BASE = "https://miladalizadw.ir/telegram/";
    private static final ExecutorService AVATAR_IO = Executors.newFixedThreadPool(2);

    private static final class Item {
        long roomId;
        String name;
        String preview;
        String avatarPath;
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
    private final ArrayList<Item> items = new ArrayList<>();

    public ZenoraDialogsAdapter(Context context, DialogsActivity parent, int account) {
        this.context = context;
        this.parent = parent;
        this.account = account;
        setHasStableIds(true);
    }

    public void submitRooms(JSONObject response) {
        ArrayList<Item> next = new ArrayList<>();
        append(next, response.optJSONArray("dm_rooms"), true);
        append(next, response.optJSONArray("rooms"), false);
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
            if (row == null) continue;
            long roomId = row.optLong("id", 0);
            if (roomId <= 0) continue;
            Item item = new Item();
            item.roomId = roomId;
            item.name = row.optString("name", "");
            if (item.name.isEmpty()) item.name = privateChats ? row.optString("other_username", "کاربر") : "گفتگو";
            item.preview = row.optString("last_message_body", "");
            if (item.preview.isEmpty()) item.preview = mediaPreview(row.optString("last_message_media_kind", ""));
            item.avatarPath = row.optString("avatar_path", "");
            item.unread = Math.max(0, row.optInt("unread_count", 0));
            item.date = parseDate(row.optString("last_message_time", ""));
            item.pinned = row.optInt("is_pinned", 0) == 1;
            item.pinnedOrder = row.optInt("pin_order", 0);
            item.muted = row.optInt("is_muted", 0) == 1;
            item.verified = row.optInt("is_verified", 0) == 1;
            item.isPrivate = privateChats;
            item.isSaved = "saved".equals(row.optString("type", ""));
            target.add(item);
        }
    }

    private static String mediaPreview(String kind) {
        switch (kind) {
            case "photo": case "image": return "تصویر";
            case "video": return "ویدئو";
            case "video_note": return "پیام ویدئویی";
            case "voice": case "audio": return "پیام صوتی";
            case "music": return "موسیقی";
            case "file": case "document": return "فایل";
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
        return items.get(position).roomId;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parentView, int viewType) {
        DialogCell cell = new DialogCell(parent, context, true, false, account, null);
        cell.useSeparator = true;
        return new Holder(cell);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        Item item = items.get(position);
        DialogCell.CustomDialog model = new DialogCell.CustomDialog();
        model.id = (int) Math.min(Integer.MAX_VALUE, item.roomId);
        model.name = item.name;
        model.message = item.preview;
        model.date = item.date;
        model.unread_count = item.unread;
        model.pinned = item.pinned;
        model.muted = item.muted;
        model.verified = item.verified;
        model.type = 0;
        holder.cell.setCurrentDialogId(item.roomId);
        holder.cell.useSeparator = position < items.size() - 1;
        holder.cell.fullSeparator = false;
        holder.cell.setDialog(model);
        loadAvatar(holder, item);
    }

    private void loadAvatar(Holder holder, Item item) {
        String path = item.avatarPath;
        if (path == null || path.isEmpty()) return;
        final long roomId = item.roomId;
        final int expectedPosition = holder.getAdapterPosition();
        final String url;
        if (path.startsWith("https://") || path.startsWith("http://")) url = path;
        else if (path.startsWith("/")) url = "https://miladalizadw.ir" + path;
        else url = WEB_BASE + path.replaceFirst("^\\./", "");

        AVATAR_IO.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "ZenoraChat-Android/Stage1");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream input = connection.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(input);
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
                if (items.get(current).roomId != roomId) return;
                holder.cell.avatarImage.setImageBitmap(new BitmapDrawable(context.getResources(), result));
            });
        });
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }
}
