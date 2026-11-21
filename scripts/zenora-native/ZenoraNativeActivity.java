package org.telegram.ui.zenora;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ZenoraNativeActivity extends Activity {
    private static final String API_ROOT = "https://miladalizadw.ir/telegram/api/native/";
    private static final int BLUE = 0xff3390ec;
    private static final int TEXT = 0xff222222;
    private static final int MUTED = 0xff707579;
    private static final int BACKGROUND = 0xfff1f2f4;
    private static final int WHITE = Color.WHITE;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout chatRows;
    private TextView statusView;
    private EditText usernameInput;
    private EditText passwordInput;
    private TextView loginButton;
    private boolean busy;
    private JSONObject cachedChats;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (SessionStore.hasRefresh(this)) {
            showLoading("در حال اتصال به زنورا چت...");
            loadChats(false);
        } else {
            showLogin(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void beginRoot(int color) {
        root = vertical();
        root.setBackgroundColor(color);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        setContentView(root);
    }

    private void showLoading(String message) {
        beginRoot(BACKGROUND);
        TextView title = text("ZenoraChat", 25, TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView subtitle = text(message, 14, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(64)));
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showLogin(String message) {
        beginRoot(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(28), dp(24), dp(28), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));

        TextView logo = text("Z", 30, WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(shape(BLUE, 24));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        logoParams.gravity = Gravity.CENTER;
        page.addView(logo, logoParams);

        TextView title = text("ZenoraChat", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(52));
        titleParams.topMargin = dp(14);
        page.addView(title, titleParams);

        TextView subtitle = text("برای ادامه وارد حساب کاربری خود شوید", 14, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, dp(38));
        subtitleParams.bottomMargin = dp(22);
        page.addView(subtitle, subtitleParams);

        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(shape(WHITE, 14));
        card.setElevation(dp(2));
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));

        usernameInput = input("نام کاربری", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        card.addView(usernameInput, new LinearLayout.LayoutParams(-1, dp(52)));

        passwordInput = input("رمز عبور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(-1, dp(52));
        passwordParams.topMargin = dp(12);
        card.addView(passwordInput, passwordParams);
        passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin();
                return true;
            }
            return false;
        });

        loginButton = text("ورود", 15, WHITE, true);
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setBackground(shape(BLUE, 10));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(50));
        buttonParams.topMargin = dp(18);
        card.addView(loginButton, buttonParams);
        loginButton.setOnClickListener(v -> submitLogin());

        statusView = text(message == null ? "" : message, 13, 0xffd04444, false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(52));
        page.addView(statusView, statusParams);

        TextView footer = text("حساب کاربری وب زنورا چت نیز قابل استفاده است", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(48));
        page.addView(footer, footerParams);
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(0xff92989c);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(shape(0xfff7f8f9, 9));
        return edit;
    }

    private void submitLogin() {
        if (busy || usernameInput == null || passwordInput == null) return;
        final String username = usernameInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            statusView.setText("نام کاربری و رمز عبور را وارد کنید.");
            return;
        }
        setBusy(true);
        statusView.setText("در حال بررسی اطلاعات ورود...");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                body.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("platform", "Android");
                body.put("app_version", "native-stage-1");
                JSONObject response = requestRaw("login.php", "POST", body, null);
                JSONObject tokens = response.optJSONObject("tokens");
                if (tokens == null) throw new ApiException(500, "پاسخ احراز هویت ناقص است.");
                SessionStore.save(this, tokens.optString("access_token"), tokens.optString("refresh_token"));
                runOnUiThread(() -> { busy = false; setBusy(false); loadChats(false); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (statusView != null) statusView.setText(readableError(e));
                });
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        if (loginButton != null) {
            loginButton.setAlpha(value ? 0.55f : 1f);
            loginButton.setText(value ? "لطفاً صبر کنید..." : "ورود");
        }
    }

    private void loadChats(boolean fromRefreshButton) {
        if (busy) return;
        busy = true;
        executor.execute(() -> {
            try {
                JSONObject response = requestAuthenticated("chats.php", "GET", null);
                cachedChats = response;
                runOnUiThread(() -> {
                    busy = false;
                    showChats(response);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    if (!SessionStore.hasRefresh(this)) {
                        SessionStore.clear(this);
                        showLogin(readableError(e));
                    } else if (cachedChats != null) {
                        showChats(cachedChats);
                        Toast.makeText(this, "اتصال برقرار نشد؛ آخرین فهرست دریافت‌شده نمایش داده می‌شود.", Toast.LENGTH_LONG).show();
                    } else {
                        showConnectionError(readableError(e), fromRefreshButton);
                    }
                });
            }
        });
    }

    private void showConnectionError(String message, boolean retry) {
        beginRoot(BACKGROUND);
        TextView title = text("اتصال به زنورا چت برقرار نشد", 19, TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView detail = text(message, 13, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(28), 0, dp(28), 0);
        root.addView(detail, new LinearLayout.LayoutParams(-1, dp(72)));
        TextView button = text("تلاش دوباره", 15, WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(BLUE, 9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(dp(28), dp(18), dp(28), dp(28));
        root.addView(button, params);
        button.setOnClickListener(v -> loadChats(true));
    }

    private void showChats(JSONObject response) {
        beginRoot(WHITE);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), 0, dp(10), 0);
        toolbar.setBackgroundColor(WHITE);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout titleBox = vertical();
        TextView title = text("ZenoraChat", 19, TEXT, true);
        TextView subtitle = text(response.optJSONObject("me") == null ? "" : "@" + response.optJSONObject("me").optString("username", ""), 11, MUTED, false);
        titleBox.addView(title, new LinearLayout.LayoutParams(-1, dp(28)));
        titleBox.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(20)));
        toolbar.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refresh = text("⟳", 27, MUTED, false);
        refresh.setGravity(Gravity.CENTER);
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(44), -1));
        refresh.setOnClickListener(v -> loadChats(true));

        TextView more = text("⋮", 25, MUTED, false);
        more.setGravity(Gravity.CENTER);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(38), -1));
        more.setOnClickListener(v -> showMenu(more));

        View line = new View(this);
        line.setBackgroundColor(0xffe8eaed);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        chatRows = vertical();
        scroll.addView(chatRows, new ScrollView.LayoutParams(-1, -2));

        ArrayList<ChatItem> items = collectChats(response);
        Collections.sort(items, (left, right) -> {
            if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
            if (left.pinned && left.pinOrder != right.pinOrder) return Integer.compare(left.pinOrder, right.pinOrder);
            return Long.compare(right.time, left.time);
        });

        boolean hasArchived = false;
        for (ChatItem item : items) if (item.archived) hasArchived = true;
        if (hasArchived) {
            TextView archived = text("  گفتگوهای بایگانی‌شده", 14, MUTED, true);
            archived.setPadding(dp(18), 0, dp(18), 0);
            chatRows.addView(archived, new LinearLayout.LayoutParams(-1, dp(52)));
            View divider = new View(this);
            divider.setBackgroundColor(0xffedf0f2);
            chatRows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        int shown = 0;
        for (ChatItem item : items) {
            if (item.archived) continue;
            addChatRow(item);
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(items.isEmpty() ? "هنوز گفتگویی ندارید" : "همه گفتگوها در بایگانی هستند", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            chatRows.addView(empty, new LinearLayout.LayoutParams(-1, dp(180)));
        }

        TextView footer = text("نمایش " + shown + " گفتگو", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(32)));
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("به‌روزرسانی گفتگوها").setOnMenuItemClickListener(item -> {
            loadChats(true);
            return true;
        });
        menu.getMenu().add("خروج از حساب").setOnMenuItemClickListener(item -> {
            logout();
            return true;
        });
        menu.show();
    }

    private ArrayList<ChatItem> collectChats(JSONObject response) {
        ArrayList<ChatItem> items = new ArrayList<>();
        JSONObject saved = response.optJSONObject("saved_room");
        if (saved != null) {
            ChatItem item = new ChatItem(saved, true, false);
            item.title = "پیام‌های ذخیره‌شده";
            items.add(item);
        }
        JSONArray privateChats = response.optJSONArray("dm_rooms");
        if (privateChats != null) {
            for (int i = 0; i < privateChats.length(); i++) {
                JSONObject object = privateChats.optJSONObject(i);
                if (object == null || "saved".equals(object.optString("type"))) continue;
                items.add(new ChatItem(object, false, true));
            }
        }
        JSONArray rooms = response.optJSONArray("rooms");
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject object = rooms.optJSONObject(i);
                if (object != null) items.add(new ChatItem(object, false, false));
            }
        }
        return items;
    }

    private void addChatRow(ChatItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(7), dp(14), dp(7));
        row.setBackgroundColor(WHITE);
        chatRows.addView(row, new LinearLayout.LayoutParams(-1, dp(76)));

        TextView avatar = text(item.initials(), 18, WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shape(item.avatarColor(), 28));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(1), dp(2), dp(1));
        row.addView(content, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout firstLine = new LinearLayout(this);
        firstLine.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(firstLine, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = text(item.title, 15, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        firstLine.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
        if (item.pinned) {
            TextView pin = text("⌖", 13, MUTED, false);
            firstLine.addView(pin, new LinearLayout.LayoutParams(dp(18), -1));
        }
        TextView time = text(item.formattedTime(), 11, MUTED, false);
        time.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        firstLine.addView(time, new LinearLayout.LayoutParams(dp(54), -1));

        LinearLayout secondLine = new LinearLayout(this);
        secondLine.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(secondLine, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView preview = text(item.preview(), 13, MUTED, false);
        preview.setSingleLine(true);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        secondLine.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        if (item.unread > 0 || item.markedUnread) {
            TextView badge = text(item.unread > 99 ? "99+" : (item.unread > 0 ? String.valueOf(item.unread) : "•"), 11, WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(shape(BLUE, 12));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, dp(21));
            badgeParams.leftMargin = dp(8);
            badge.setPadding(dp(7), 0, dp(7), 0);
            secondLine.addView(badge, badgeParams);
        }
        View divider = new View(this);
        divider.setBackgroundColor(0xffedf0f2);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.leftMargin = dp(80);
        chatRows.addView(divider, dividerParams);
        row.setOnClickListener(v -> Toast.makeText(this, "نمایش پیام‌ها در مرحله بعد فعال می‌شود.", Toast.LENGTH_SHORT).show());
    }

    private void logout() {
        if (busy) return;
        busy = true;
        executor.execute(() -> {
            try {
                requestAuthenticated("logout.php", "POST", new JSONObject());
            } catch (Exception ignored) {
            }
            SessionStore.clear(this);
            runOnUiThread(() -> {
                busy = false;
                cachedChats = null;
                showLogin(null);
            });
        });
    }

    private JSONObject requestAuthenticated(String path, String method, JSONObject body) throws Exception {
        try {
            return requestRaw(path, method, body, SessionStore.access(this));
        } catch (ApiException e) {
            if (e.status != 401 || "refresh.php".equals(path)) throw e;
            String refresh = SessionStore.refresh(this);
            if (refresh == null || refresh.isEmpty()) throw e;
            JSONObject refreshBody = new JSONObject();
            refreshBody.put("refresh_token", refresh);
            JSONObject response = requestRaw("refresh.php", "POST", refreshBody, null);
            JSONObject tokens = response.optJSONObject("tokens");
            if (tokens == null) throw new ApiException(401, "نشست کاربری منقضی شده است.");
            SessionStore.save(this, tokens.optString("access_token"), tokens.optString("refresh_token"));
            return requestRaw(path, method, body, SessionStore.access(this));
        }
    }

    private JSONObject requestRaw(String path, String method, JSONObject body, String bearer) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_ROOT + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ZenoraChat-Android/native-stage-1");
            if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String responseText = readAll(input);
            JSONObject response = responseText.trim().isEmpty() ? new JSONObject() : new JSONObject(responseText);
            if (status < 200 || status >= 300 || !response.optBoolean("ok", status >= 200 && status < 300)) {
                throw new ApiException(status, response.optString("error", "REQUEST_FAILED"));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String readableError(Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).status;
            String error = e.getMessage();
            if ("INVALID_CREDENTIALS".equals(error)) return "نام کاربری یا رمز عبور صحیح نیست.";
            if ("LOGIN_RATE_LIMITED".equals(error)) return "تعداد تلاش‌های ورود زیاد است؛ ۱۵ دقیقه دیگر تلاش کنید.";
            if ("INVALID_REFRESH_TOKEN".equals(error) || "ACCESS_TOKEN_EXPIRED".equals(error)) return "نشست شما منقضی شده است؛ دوباره وارد شوید.";
            if (code >= 500) return "سرور موقتاً در دسترس نیست. کمی بعد دوباره تلاش کنید.";
            return error;
        }
        return "خطای اتصال: " + (e.getMessage() == null ? "ارتباط برقرار نشد" : e.getMessage());
    }

    private static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final class ChatItem {
        final JSONObject source;
        final boolean saved;
        final boolean privateChat;
        String title;
        final int unread;
        final boolean pinned;
        final boolean archived;
        final boolean markedUnread;
        final int pinOrder;
        final long time;
        final String body;
        final String mediaKind;

        ChatItem(JSONObject source, boolean saved, boolean privateChat) {
            this.source = source;
            this.saved = saved;
            this.privateChat = privateChat;
            this.title = saved ? "پیام‌های ذخیره‌شده" : privateChat
                    ? first(source, "other_display_name", "other_username", "name")
                    : first(source, "name", "room_chat_id");
            if (this.title == null || this.title.trim().isEmpty()) this.title = privateChat ? "کاربر" : "گفتگو";
            this.unread = source.optInt("unread_count", 0);
            this.pinned = saved || source.optInt("is_pinned", 0) == 1;
            this.archived = source.optInt("is_archived", 0) == 1;
            this.markedUnread = source.optInt("is_marked_unread", 0) == 1;
            this.pinOrder = saved ? -1 : source.optInt("pin_order", 0);
            this.body = source.optString("last_message_body", "");
            this.mediaKind = source.optString("last_message_media_kind", "");
            this.time = parseTime(source.optString("last_message_time", ""));
        }

        String initials() {
            String value = title == null ? "Z" : title.trim();
            if (value.isEmpty()) return "Z";
            String[] parts = value.split("\\s+");
            if (parts.length > 1 && parts[0].length() > 0 && parts[1].length() > 0) {
                return parts[0].substring(0, 1) + parts[1].substring(0, 1);
            }
            return value.substring(0, 1).toUpperCase(Locale.getDefault());
        }

        int avatarColor() {
            int[] colors = new int[] {0xff5e97d1, 0xff8e85c2, 0xffd17d74, 0xff5da99b, 0xffc18a55, 0xff7199a5};
            int hash = Math.abs((title == null ? 0 : title.hashCode()));
            return colors[hash % colors.length];
        }

        String preview() {
            String value = body == null ? "" : body.trim();
            if (!value.isEmpty()) {
                if (privateChat && !source.optString("last_message_sender_username").isEmpty()
                        && source.optString("last_message_sender_username").equals(source.optString("other_username"))) {
                    return value;
                }
                return value;
            }
            if ("photo".equals(mediaKind)) return "عکس";
            if ("video".equals(mediaKind)) return "ویدیو";
            if ("voice".equals(mediaKind) || "audio".equals(mediaKind)) return "پیام صوتی";
            if ("video_note".equals(mediaKind)) return "پیام ویدیویی";
            return saved ? "یادداشت‌های شخصی شما" : "هنوز پیامی ارسال نشده است";
        }

        String formattedTime() {
            if (time <= 0) return "";
            long now = System.currentTimeMillis();
            if (now - time < 24L * 60L * 60L * 1000L) {
                return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time));
            }
            return new SimpleDateFormat("MM/dd", Locale.getDefault()).format(new Date(time));
        }
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.trim().isEmpty() && !"null".equals(value)) return value;
        }
        return "";
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0;
        String[] formats = new String[] {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                parser.setLenient(true);
                return parser.parse(value).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static final class SessionStore {
        private static final String PREFS = "zenora_native_session";
        private static final String ALIAS = "zenora.native.session.key.v1";

        static boolean hasRefresh(Context context) {
            return refresh(context) != null;
        }

        static String access(Context context) {
            return decrypt(context, "access");
        }

        static String refresh(Context context) {
            return decrypt(context, "refresh");
        }

        static void save(Context context, String access, String refresh) throws Exception {
            if (access == null || access.length() != 64 || refresh == null || refresh.length() != 96) {
                throw new IllegalArgumentException("Invalid native session tokens");
            }
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            preferences.edit()
                    .putString("access", encrypt(context, access))
                    .putString("refresh", encrypt(context, refresh))
                    .apply();
        }

        static void clear(Context context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        }

        private static SecretKey key(Context context) throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }

        private static String encrypt(Context context, String value) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(context));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP);
        }

        private static String decrypt(Context context, String field) {
            try {
                String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(field, null);
                if (encoded == null) return null;
                byte[] combined = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
                if (combined.length <= 12) return null;
                byte[] iv = new byte[12];
                byte[] encrypted = new byte[combined.length - 12];
                System.arraycopy(combined, 0, iv, 0, 12);
                System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(context), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception e) {
                clear(context);
                return null;
            }
        }
    }
}
