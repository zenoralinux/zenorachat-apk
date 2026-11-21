package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.TLRPC;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ZenoraApiClient;
import org.telegram.messenger.ZenoraChatController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** ZenoraChat account entry screen. The native Telegram UI remains unchanged after sign-in. */
public class ZenoraLoginActivity extends BaseFragment {
    private EditText usernameField;
    private EditText passwordField;
    private TextView errorView;
    private TextView loginButton;
    private boolean submitting;

    public ZenoraLoginActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("ورود به زنوراچت");
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(26), AndroidUtilities.dp(28),
                AndroidUtilities.dp(26), AndroidUtilities.dp(28));
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView title = new TextView(context);
        title.setText("به زنوراچت خوش آمدید");
        title.setTextSize(23);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));

        TextView subtitle = new TextView(context);
        subtitle.setText("برای ادامه با حساب زنوراچت وارد شوید.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(42));
        subtitleParams.bottomMargin = AndroidUtilities.dp(28);
        root.addView(subtitle, subtitleParams);

        usernameField = createField(context, "نام کاربری",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        usernameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        root.addView(usernameField, fieldParams());

        passwordField = createField(context, "رمز عبور",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams passwordParams = fieldParams();
        passwordParams.topMargin = AndroidUtilities.dp(12);
        root.addView(passwordField, passwordParams);

        errorView = new TextView(context);
        errorView.setTextSize(13);
        errorView.setTextColor(0xffe05252);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = AndroidUtilities.dp(10);
        root.addView(errorView, errorParams);

        loginButton = new TextView(context);
        loginButton.setText("ورود");
        loginButton.setTextSize(16);
        loginButton.setTextColor(Color.WHITE);
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setBackground(buttonBackground());
        loginButton.setClickable(true);
        loginButton.setFocusable(true);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52));
        buttonParams.topMargin = AndroidUtilities.dp(22);
        root.addView(loginButton, buttonParams);

        loginButton.setOnClickListener(v -> submitLogin());
        passwordField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin();
                return true;
            }
            return false;
        });

        fragmentView = root;
        return fragmentView;
    }

    private EditText createField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        background.setCornerRadius(AndroidUtilities.dp(10));
        background.setStroke(AndroidUtilities.dp(1), Theme.getColor(Theme.key_windowBackgroundWhiteInputField));
        field.setBackground(background);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52));
    }

    private GradientDrawable buttonBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff3390ec);
        background.setCornerRadius(AndroidUtilities.dp(12));
        return background;
    }

    private void submitLogin() {
        if (submitting || usernameField == null || passwordField == null) return;
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            showError("نام کاربری و رمز عبور را وارد کنید.");
            return;
        }

        submitting = true;
        loginButton.setEnabled(false);
        loginButton.setAlpha(0.65f);
        loginButton.setText("در حال ورود…");
        errorView.setVisibility(View.GONE);

        ZenoraApiClient.login(username, password, (response, error) -> {
            if (loginButton == null) return;
            submitting = false;
            loginButton.setEnabled(true);
            loginButton.setAlpha(1f);
            loginButton.setText("ورود");
            if (error != null) {
                String message = "ورود به زنوراچت انجام نشد. دوباره تلاش کنید.";
                if (error instanceof ZenoraApiClient.ApiException
                        && "INVALID_CREDENTIALS".equals(((ZenoraApiClient.ApiException) error).errorCode)) {
                    message = "نام کاربری یا رمز عبور نادرست است.";
                }
                showError(message);
                return;
            }

            JSONObject me = response == null ? null : response.optJSONObject("me");
            if (me == null || me.optLong("id", 0) <= 0) {
                showError("پاسخ سرور معتبر نیست.");
                return;
            }
            ZenoraChatController.activateAccount(currentAccount, me);
            presentFragment(new MainTabsActivity(), true);
        });
    }

    private void showError(String message) {
        if (errorView != null) {
            errorView.setText(message);
            errorView.setVisibility(View.VISIBLE);
        }
    }
}
