package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ZenoraApiClient;
import org.telegram.messenger.ZenoraDialogsBridge;
import org.telegram.ui.ActionBar.BaseFragment;

public class ZenoraLoginActivity extends BaseFragment {
    private EditText usernameField;
    private EditText passwordField;
    private TextView submitButton;
    private TextView errorView;
    private boolean submitting;

    @Override
    protected View createView(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.rgb(15, 20, 26));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(42),
                AndroidUtilities.dp(28), AndroidUtilities.dp(32));
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));

        TextView logo = new TextView(context);
        logo.setText("Z");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(28);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable logoBackground = new GradientDrawable();
        logoBackground.setColor(Color.rgb(43, 125, 233));
        logoBackground.setCornerRadius(AndroidUtilities.dp(22));
        logo.setBackground(logoBackground);
        content.addView(logo, new LinearLayout.LayoutParams(AndroidUtilities.dp(68), AndroidUtilities.dp(68)));

        TextView title = new TextView(context);
        title.setText("ورود به ZenoraChat");
        title.setTextColor(Color.rgb(225, 233, 241));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = AndroidUtilities.dp(22);
        content.addView(title, titleParams);

        TextView subtitle = new TextView(context);
        subtitle.setText("با حساب زنوراچت وارد شوید تا گفتگوهای خود را در تلگرام ببینید.");
        subtitle.setTextColor(Color.rgb(132, 148, 167));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(AndroidUtilities.dp(3), 1f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = AndroidUtilities.dp(8);
        subtitleParams.bottomMargin = AndroidUtilities.dp(34);
        content.addView(subtitle, subtitleParams);

        usernameField = createField(context, "نام کاربری", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        content.addView(usernameField, fieldLayoutParams());
        
        passwordField = createField(context, "رمز عبور", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = fieldLayoutParams();
        passwordParams.topMargin = AndroidUtilities.dp(14);
        content.addView(passwordField, passwordParams);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordField.setOnEditorActionListener((v, actionId, event) -> {
            attemptLogin();
            return true;
        });

        errorView = new TextView(context);
        errorView.setTextColor(Color.rgb(239, 100, 100));
        errorView.setTextSize(13);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = AndroidUtilities.dp(12);
        content.addView(errorView, errorParams);

        submitButton = new TextView(context);
        submitButton.setText("ورود");
        submitButton.setTextColor(Color.WHITE);
        submitButton.setTextSize(15);
        submitButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        submitButton.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.rgb(43, 125, 233));
        buttonBackground.setCornerRadius(AndroidUtilities.dp(13));
        submitButton.setBackground(buttonBackground);
        submitButton.setOnClickListener(v -> attemptLogin());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52));
        buttonParams.topMargin = AndroidUtilities.dp(24);
        content.addView(submitButton, buttonParams);

        TextView footer = new TextView(context);
        footer.setText("اتصال امن به سرور زنوراچت");
        footer.setTextColor(Color.rgb(107, 123, 143));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = AndroidUtilities.dp(22);
        content.addView(footer, footerParams);

        fragmentView = root;
        return fragmentView;
    }

    private EditText createField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(Color.rgb(225, 233, 241));
        field.setHintTextColor(Color.rgb(111, 128, 146));
        field.setHint(hint);
        field.setInputType(inputType);
        field.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        field.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(23, 33, 43));
        background.setCornerRadius(AndroidUtilities.dp(12));
        background.setStroke(AndroidUtilities.dp(1), Color.rgb(42, 57, 72));
        field.setBackground(background);
        return field;
    }

    private LinearLayout.LayoutParams fieldLayoutParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(54));
    }

    private void attemptLogin() {
        if (submitting || usernameField == null || passwordField == null) return;
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            showError("نام کاربری و رمز عبور را وارد کنید.");
            return;
        }

        submitting = true;
        errorView.setVisibility(View.GONE);
        submitButton.setEnabled(false);
        submitButton.setText("در حال ورود...");
        AndroidUtilities.hideKeyboard(passwordField);

        ZenoraApiClient.login(username, password, new ZenoraApiClient.LoginCallback() {
            @Override
            public void onSuccess(JSONObject user, JSONObject rooms) {
                try {
                    ZenoraDialogsBridge.apply(currentAccount, user, rooms);
                    passwordField.setText("");
                    presentFragment(new DialogsActivity(null), true);
                } catch (Exception e) {
                    finishSubmitting();
                    showError(e.getMessage() == null ? "آماده‌سازی فهرست گفتگوها ناموفق بود." : e.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                finishSubmitting();
                showError(message);
            }
        });
    }

    private void finishSubmitting() {
        submitting = false;
        if (submitButton != null) {
            submitButton.setEnabled(true);
            submitButton.setText("ورود");
        }
    }

    private void showError(String message) {
        if (errorView != null) {
            errorView.setText(message == null ? "خطا در ورود به حساب." : message);
            errorView.setVisibility(View.VISIBLE);
        }
    }
}
