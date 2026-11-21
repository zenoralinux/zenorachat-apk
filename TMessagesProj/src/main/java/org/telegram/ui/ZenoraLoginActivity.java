package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenora.ZenoraApiClient;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * ZenoraChat username/password sign-in. Telegram's original sign-in screens
 * remain untouched in source; this fragment is used by this application build.
 */
public final class ZenoraLoginActivity extends BaseFragment {

    private EditText usernameField;
    private EditText passwordField;
    private TextView loginButton;
    private TextView statusView;
    private boolean submitting;

    public ZenoraLoginActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(52),
                AndroidUtilities.dp(28), AndroidUtilities.dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));

        TextView mark = new TextView(context);
        mark.setText("Z");
        mark.setTextSize(38);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(0xFFFFFFFF);
        GradientDrawable markBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1687E8, 0xFF6247D8});
        markBackground.setCornerRadius(AndroidUtilities.dp(26));
        mark.setBackground(markBackground);
        content.addView(mark, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(84), AndroidUtilities.dp(84)));

        TextView title = new TextView(context);
        title.setText("زنوراچت");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = AndroidUtilities.dp(22);
        content.addView(title, titleParams);

        TextView subtitle = new TextView(context);
        subtitle.setText("برای ادامه وارد حساب کاربری خود شوید");
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = AndroidUtilities.dp(8);
        subtitleParams.bottomMargin = AndroidUtilities.dp(34);
        content.addView(subtitle, subtitleParams);

        usernameField = createField(context, "نام کاربری", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_NORMAL);
        content.addView(usernameField, fieldLayoutParams());

        passwordField = createField(context, "گذرواژه", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = fieldLayoutParams();
        passwordParams.topMargin = AndroidUtilities.dp(14);
        content.addView(passwordField, passwordParams);

        loginButton = new TextView(context);
        loginButton.setText("ورود به زنوراچت");
        loginButton.setTextSize(16);
        loginButton.setTypeface(Typeface.DEFAULT_BOLD);
        loginButton.setTextColor(0xFFFFFFFF);
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setBackground(makeRounded(0xFF1687E8, 0xFF1687E8, 15));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(54));
        buttonParams.topMargin = AndroidUtilities.dp(24);
        content.addView(loginButton, buttonParams);
        loginButton.setOnClickListener(v -> submitLogin());

        statusView = new TextView(context);
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = AndroidUtilities.dp(16);
        content.addView(statusView, statusParams);

        passwordField.setOnEditorActionListener((v, actionId, event) -> {
            submitLogin();
            return true;
        });

        fragmentView = root;
        return fragmentView;
    }

    private EditText createField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setInputType(inputType);
        field.setHint(hint);
        field.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setBackground(makeRounded(
                Theme.getColor(Theme.key_windowBackgroundGray),
                Theme.getColor(Theme.key_windowBackgroundGray), 14));
        return field;
    }

    private LinearLayout.LayoutParams fieldLayoutParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(54));
    }

    private GradientDrawable makeRounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        drawable.setStroke(AndroidUtilities.dp(1), stroke);
        return drawable;
    }

    private void submitLogin() {
        if (submitting || usernameField == null || passwordField == null) {
            return;
        }
        final String username = usernameField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            showStatus("نام کاربری و گذرواژه را وارد کنید.", 0xFFE05252);
            return;
        }

        submitting = true;
        loginButton.setEnabled(false);
        loginButton.setAlpha(0.65f);
        loginButton.setText("در حال ورود...");
        showStatus("", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));

        new Thread(() -> {
            try {
                ZenoraApiClient client = new ZenoraApiClient(ApplicationLoader.applicationContext);
                JSONObject response = client.login(username, password);
                JSONObject user = response.optJSONObject("user");
                if (user == null || user.optLong("id", 0) <= 0) {
                    throw new IllegalStateException("INVALID_USER_RESPONSE");
                }
                AndroidUtilities.runOnUIThread(() -> finishLogin(user));
            } catch (Exception error) {
                final String code;
                if (error instanceof ZenoraApiClient.ZenoraApiException) {
                    code = ((ZenoraApiClient.ZenoraApiException) error).errorCode;
                } else if (error instanceof java.net.UnknownHostException) {
                    code = "DNS_ERROR";
                } else if (error instanceof java.net.SocketTimeoutException) {
                    code = "NETWORK_TIMEOUT";
                } else if (error instanceof javax.net.ssl.SSLException) {
                    code = "TLS_ERROR";
                } else {
                    code = error.getClass().getSimpleName();
                }
                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    if (loginButton != null) {
                        loginButton.setEnabled(true);
                        loginButton.setAlpha(1f);
                        loginButton.setText("ورود به زنوراچت");
                    }
                    showStatus(errorMessage(code), 0xFFE05252);
                });
            }
        }, "ZenoraChat-Login").start();
    }

    private String errorMessage(String code) {
        if ("INVALID_CREDENTIALS".equals(code)) return "نام کاربری یا گذرواژه نادرست است.";
        if ("AUTH_INTERNAL_ERROR".equals(code)) return "ورود با خطای داخلی سرور روبه‌رو شد.";
        if ("CREDENTIALS_REQUIRED".equals(code)) return "نام کاربری و گذرواژه را وارد کنید.";
        if ("ENDPOINT_NOT_FOUND".equals(code)) {
            return "مسیر ورود اندروید روی سرور پیدا نشد (404). فایل /Telegram/api/mobile_auth.php را روی هاست منتشر کن.";
        }
        if ("NON_JSON_RESPONSE".equals(code)) {
            return "سرور به‌جای JSON صفحه HTML برگرداند. مسیر استقرار API یا ریدایرکت هاست را بررسی کن.";
        }
        if ("CSRF_INVALID".equals(code)) {
            return "نشست امنیتی ورود نامعتبر شد (CSRF). کوکی PHP و مسیر دامنه را بررسی کن و دوباره تلاش کن.";
        }
        if ("NETWORK_TIMEOUT".equals(code)) return "پاسخ سرور زنوراچت بیش از حد طول کشید.";
        if ("DNS_ERROR".equals(code)) return "دامنه سرور زنوراچت از این دستگاه resolve نمی‌شود.";
        if ("TLS_ERROR".equals(code)) return "اتصال امن TLS به سرور برقرار نشد.";
        if ("EMPTY_RESPONSE".equals(code)) return "سرور پاسخ خالی برگرداند.";
        if (code != null && code.startsWith("HTTP_")) {
            return "سرور زنوراچت پاسخ HTTP " + code.substring(5) + " برگرداند.";
        }
        return "خطای ارتباط با زنوراچت: " + (code == null ? "UNKNOWN" : code);
    }

    private void finishLogin(JSONObject userData) {
        try {
            UserConfig config = UserConfig.getInstance(currentAccount);
            if (!config.isConfigLoaded()) {
                config.loadConfig();
            }

            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = userData.optLong("id");
            user.first_name = userData.optString("display_name",
                    userData.optString("username", "ZenoraChat"));
            user.last_name = "";
            user.username = userData.optString("username", "");
            user.phone = "";
            user.self = true;

            config.setCurrentUser(user);
            config.loginTime = (int) (System.currentTimeMillis() / 1000);
            config.saveConfig(true);
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.mainUserInfoChanged);

            presentFragment(new MainTabsActivity(), true);
        } catch (Exception error) {
            submitting = false;
            if (loginButton != null) {
                loginButton.setEnabled(true);
                loginButton.setAlpha(1f);
                loginButton.setText("ورود به زنوراچت");
            }
            showStatus("ورود انجام شد، اما آماده‌سازی حساب در برنامه ناموفق بود.", 0xFFE05252);
        }
    }

    private void showStatus(String message, int color) {
        if (statusView != null) {
            statusView.setText(message);
            statusView.setTextColor(color);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return false;
    }
}
