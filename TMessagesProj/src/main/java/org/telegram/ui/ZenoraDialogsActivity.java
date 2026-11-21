package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenora.ZenoraApiClient;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Native Telegram-themed chat list backed by ZenoraChat's rooms API.
 * Message history and sending are intentionally not faked by MTProto models.
 */
public final class ZenoraDialogsActivity extends BaseFragment
        implements MainTabsActivity.TabFragmentDelegate {

    private LinearLayout rowsContainer;
    private TextView stateView;
    private ScrollView scrollView;
    private boolean loading;

    public ZenoraDialogsActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("گفتگوها");

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        root.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(6), 0,
                AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 12));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));

        stateView = new TextView(context);
        stateView.setText("در حال دریافت گفتگوها...");
        stateView.setTextSize(14);
        stateView.setGravity(Gravity.CENTER);
        stateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        content.addView(stateView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(110)));

        rowsContainer = new LinearLayout(context);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(rowsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        fragmentView = root;
        loadRooms();
        return fragmentView;
    }

    private void loadRooms() {
        if (loading || rowsContainer == null) return;
        loading = true;
        final ZenoraApiClient client = new ZenoraApiClient(ApplicationLoader.applicationContext);
        JSONArray cached = client.getCachedRooms();
        if (cached.length() > 0) {
            renderRooms(cached, false);
        } else {
            setState("در حال دریافت گفتگوها...");
        }

        new Thread(() -> {
            try {
                JSONArray rooms = client.fetchRooms();
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    renderRooms(rooms, false);
                });
            } catch (Exception error) {
                boolean expired = error instanceof ZenoraApiClient.ZenoraApiException
                        && "AUTH_REQUIRED".equals(
                        ((ZenoraApiClient.ZenoraApiException) error).errorCode);
                JSONArray fallback = client.getCachedRooms();
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    if (expired) {
                        client.clearSession();
                        UserConfig.getInstance(currentAccount).clearConfig();
                        presentFragment(new ZenoraLoginActivity(), true);
                    } else if (fallback.length() > 0) {
                        renderRooms(fallback, true);
                    } else {
                        setState("گفتگویی برای نمایش وجود ندارد یا اتصال به سرور برقرار نشد. برای تلاش دوباره لمس کنید.");
                        stateView.setOnClickListener(v -> loadRooms());
                    }
                });
            }
        }, "ZenoraChat-Rooms").start();
    }

    private void renderRooms(JSONArray source, boolean offline) {
        if (rowsContainer == null) return;
        rowsContainer.removeAllViews();

        ArrayList<JSONObject> rooms = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject room = source.optJSONObject(i);
            if (room != null) rooms.add(room);
        }
        Collections.sort(rooms, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject left, JSONObject right) {
                int pinnedLeft = left.optInt("is_pinned", 0);
                int pinnedRight = right.optInt("is_pinned", 0);
                if (pinnedLeft != pinnedRight) return pinnedRight - pinnedLeft;
                return right.optString("last_message_time", "")
                        .compareTo(left.optString("last_message_time", ""));
            }
        });

        if (rooms.isEmpty()) {
            setState("هنوز گفتگویی در حساب زنوراچت وجود ندارد.");
            return;
        }
        stateView.setVisibility(offline ? View.VISIBLE : View.GONE);
        if (offline) {
            stateView.setText("نمایش نسخه ذخیره‌شده؛ اتصال به سرور برقرار نشد.");
            stateView.setOnClickListener(v -> loadRooms());
        }

        Context context = getContext();
        if (context == null) return;
        for (JSONObject room : rooms) {
            rowsContainer.addView(createRoomRow(context, room), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(74)));
            View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, AndroidUtilities.dp(0.5f)));
            dividerParams.leftMargin = AndroidUtilities.dp(76);
            rowsContainer.addView(divider, dividerParams);
        }
    }

    private View createRoomRow(Context context, JSONObject room) {
        String title = room.optString("name", "");
        if (TextUtils.isEmpty(title)) {
            title = room.optString("other_display_name",
                    room.optString("other_username", "گفتگو"));
        }

        String preview = room.optString("typing_text", "");
        if (TextUtils.isEmpty(preview)) {
            preview = room.optString("last_message_body", "");
            if (TextUtils.isEmpty(preview)) {
                String media = room.optString("last_message_media_kind", "");
                if ("photo".equals(media) || "image".equals(media)) preview = "عکس";
                else if ("video".equals(media)) preview = "ویدیو";
                else if ("voice".equals(media) || "audio".equals(media)) preview = "پیام صوتی";
                else if ("video_note".equals(media)) preview = "ویدیوی گرد";
            }
            String sender = room.optString("last_message_sender_display_name", "");
            if (!TextUtils.isEmpty(sender) && !"شما".equals(sender)) {
                preview = sender + ": " + preview;
            }
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7),
                AndroidUtilities.dp(14), AndroidUtilities.dp(7));
        row.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector), 0));

        TextView avatar = new TextView(context);
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(initials(title));
        avatar.setTextSize(18);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setTextColor(0xFFFFFFFF);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setColor(avatarColor(title));
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avatarBackground);
        row.addView(avatar, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(54), AndroidUtilities.dp(54)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        textsParams.leftMargin = AndroidUtilities.dp(13);
        row.addView(texts, textsParams);

        TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView message = new TextView(context);
        message.setText(TextUtils.isEmpty(preview) ? " " : preview);
        message.setTextSize(13);
        message.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        message.setMaxLines(1);
        message.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout trailing = new LinearLayout(context);
        trailing.setOrientation(LinearLayout.VERTICAL);
        trailing.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(trailing, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TextView time = new TextView(context);
        time.setText(displayTime(room.optString("last_message_time", "")));
        time.setTextSize(11);
        time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        time.setGravity(Gravity.RIGHT);
        trailing.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f));

        int unread = room.optInt("unread_count", 0);
        if (unread > 0) {
            TextView badge = new TextView(context);
            badge.setText(unread > 999 ? "999+" : String.valueOf(unread));
            badge.setTextSize(11);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
            GradientDrawable badgeBackground = new GradientDrawable();
            badgeBackground.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            badgeBackground.setCornerRadius(AndroidUtilities.dp(10));
            badge.setBackground(badgeBackground);
            trailing.addView(badge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(20)));
        }

        row.setContentDescription(title + ", " + preview);
        return row;
    }

    private String displayTime(String value) {
        if (value.length() >= 16) return value.substring(11, 16);
        return "";
    }

    private String initials(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) return "Z";
        String[] parts = value.split("\\s+");
        if (parts.length > 1 && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        }
        return value.substring(0, 1).toUpperCase();
    }

    private int avatarColor(String value) {
        int[] palette = {0xFF5A8DEE, 0xFF26A69A, 0xFFE08B49, 0xFF8D70C7,
                0xFFD66A83, 0xFF4E9EBD, 0xFF7C9B51};
        return palette[Math.abs(value.hashCode()) % palette.length];
    }

    private void setState(String message) {
        if (stateView != null) {
            stateView.setVisibility(View.VISIBLE);
            stateView.setText(message);
        }
    }

    @Override
    public void onParentScrollToTop() {
        if (scrollView != null) scrollView.smoothScrollTo(0, 0);
    }
}
