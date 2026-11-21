package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ZenoraApiClient;
import org.telegram.messenger.ZenoraSession;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

public class ZenoraLoginActivity extends BaseFragment {
    private EditText usernameField;
    private EditText passwordField;
    private TextView submitButton;
    private TextView messageView;
    private boolean submitting;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("ZenoraChat");
        actionBar.setBackButtonImage(0);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(24), AndroidUtilities.dp(28), AndroidUtilities.dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT
        ));

        TextView brand = new TextView(context);
        brand.setText("ZenoraChat");
        brand.setTextSize(27);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        brand.setGravity(Gravity.CENTER);
        content.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(56)
        ));

        TextView subtitle = new TextView(context);
        subtitle.setText("برای ادامه گفتگوها وارد حساب زنورای خود شوید.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.bottomMargin = AndroidUtilities.dp(32);
        content.addView(subtitle, subtitleParams);

        usernameField = createField(context, "نام کاربری", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        usernameField.setSingleLine(true);
        usernameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        usernameField.setTextDirection(View.TEXT_DIRECTION_LTR);
        usernameField.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        content.addView(usernameField, fieldParams());

        passwordField = createField(context, "رمز عبور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setSingleLine(true);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams passwordParams = fieldParams();
        passwordParams.topMargin = AndroidUtilities.dp(14);
        content.addView(passwordField, passwordParams);
        passwordField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin();
                return true;
            }
            return false;
        });

        submitButton = new TextView(context);
        submitButton.setText("ورود به حساب");
        submitButton.setTextSize(15);
        submitButton.setTypeface(null, android.graphics.Typeface.BOLD);
        submitButton.setTextColor(Color.WHITE);
        submitButton.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(0xff3390ec);
        buttonBackground.setCornerRadius(AndroidUtilities.dp(12));
        submitButton.setBackground(buttonBackground);
        submitButton.setClickable(true);
        submitButton.setFocusable(true);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(52)
        );
        buttonParams.topMargin = AndroidUtilities.dp(22);
        content.addView(submitButton, buttonParams);
        submitButton.setOnClickListener(v -> submitLogin());

        messageView = new TextView(context);
        messageView.setTextSize(13);
        messageView.setTextColor(0xffe05b62);
        messageView.setGravity(Gravity.CENTER);
        messageView.setVisibility(View.GONE);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = AndroidUtilities.dp(14);
        content.addView(messageView, messageParams);

        TextView note = new TextView(context);
        note.setText("حساب کاربری و رمز عبور همان حساب نسخه وب ZenoraChat است.");
        note.setTextSize(12);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        noteParams.topMargin = AndroidUtilities.dp(28);
        content.addView(note, noteParams);

        submitButton.setOnClickListener(v -> submitLogin());
        fragmentView = scroll;
        return scroll;
    }

    private EditText createField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        field.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.getColor(Theme.key_windowBackgroundWhiteInputField)));
        return field;
    }

    private LinearLayout.LayoutParams fieldParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(54)
        );
    }

    private void submitLogin() {
        if (submitting || usernameField == null || passwordField == null) return;
        String username = usernameField.getText() == null ? "" : usernameField.getText().toString().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            showMessage("نام کاربری و رمز عبور را وارد کنید.");
            return;
        }

        submitting = true;
        submitButton.setEnabled(false);
        submitButton.setAlpha(0.65f);
        submitButton.setText("در حال ورود...");
        messageView.setVisibility(View.GONE);
        AndroidUtilities.hideKeyboard(usernameField);

        ZenoraApiClient.login(getParentActivity(), username, password, new ZenoraApiClient.LoginCallback() {
            @Override
            public void onSuccess() {
                submitting = false;
                if (passwordField != null) passwordField.setText("");
                if (parentLayout != null && !isFinishing()) {
                    presentFragment(new MainTabsActivity(), true);
                }
            }

            @Override
            public void onError(String message) {
                submitting = false;
                if (passwordField != null) passwordField.setText("");
                if (submitButton != null) {
                    submitButton.setEnabled(true);
                    submitButton.setAlpha(1f);
                    submitButton.setText("ورود به حساب");
                }
                showMessage(message);
            }
        });
    }

    private void showMessage(String message) {
        if (messageView != null) {
            messageView.setText(message == null ? "ورود انجام نشد." : message);
            messageView.setVisibility(View.VISIBLE);
        }
    }
}
