package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Bundle;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenora.ZenoraApiClient;
import org.telegram.messenger.zenora.ZenoraSessionStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Native username/password entry for ZenoraChat. Visual primitives and colors
 * are taken from Telegram's current theme; Telegram phone/SMS auth is bypassed.
 */
public class ZenoraLoginActivity extends BaseFragment {
    public ZenoraLoginActivity() {
        super();
    }

    public ZenoraLoginActivity(Bundle args) {
        super(args);
    }

    private EditText usernameField;
    private EditText passwordField;
    private TextView submitButton;
    private boolean submitting;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        actionBar.setBackButtonImage(0);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("ZenoraChat");
        actionBar.setCastShadows(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ActionBar.getCurrentActionBarHeight(), Gravity.TOP));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        FrameLayout.LayoutParams scrollParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT);
        scrollParams.topMargin = ActionBar.getCurrentActionBarHeight();
        root.addView(scroll, scrollParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(38), AndroidUtilities.dp(28), AndroidUtilities.dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        TextView heading = new TextView(context);
        heading.setText("ورود به زنورا چت");
        heading.setTextSize(23);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        heading.setGravity(Gravity.CENTER);
        content.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));

        TextView subtitle = new TextView(context);
        subtitle.setText("با نام کاربری و رمز عبور حساب زنورا وارد شوید");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 30));

        usernameField = createField(context, "نام کاربری", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        usernameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        content.addView(usernameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 14));

        passwordField = createField(context, "رمز عبور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        content.addView(passwordField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 24));
        passwordField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });

        submitButton = new TextView(context);
        submitButton.setText("ورود");
        submitButton.setTextSize(16);
        submitButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        submitButton.setGravity(Gravity.CENTER);
        submitButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        Drawable buttonBackground = Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_featuredStickers_addButton));
        submitButton.setBackground(buttonBackground);
        content.addView(submitButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 0));
        submitButton.setOnClickListener(v -> submit());

        TextView footer = new TextView(context);
        footer.setText("اتصال امن به سرور زنورا چت");
        footer.setTextSize(12);
        footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));

        fragmentView = root;
        return root;
    }

    private EditText createField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_windowBackgroundWhiteInputField)));
        return field;
    }

    private void submit() {
        if (submitting || usernameField == null || passwordField == null) return;
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(getParentActivity(), "نام کاربری و رمز عبور را وارد کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        submitting = true;
        submitButton.setEnabled(false);
        submitButton.setAlpha(0.65f);
        submitButton.setText("در حال ورود…");
        AndroidUtilities.hideKeyboard(passwordField);

        ZenoraApiClient.login(getParentActivity(), username, password, (result, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (isFinished || getParentActivity() == null) return;
                    submitting = false;
                    submitButton.setEnabled(true);
                    submitButton.setAlpha(1f);
                    submitButton.setText("ورود");
                    if (error != null || result == null || !result.optBoolean("authenticated", false)) {
                        Toast.makeText(getParentActivity(), friendlyError(error), Toast.LENGTH_LONG).show();
                        return;
                    }
                    ZenoraSessionStore.applyCachedUser(getParentActivity(), currentAccount);
                    if (arguments != null && arguments.getBoolean("resumeExistingTabs", false)) {
                        finishFragment();
                    } else {
                        presentFragment(new MainTabsActivity(), true);
                    }
                }));
    }

    private String friendlyError(String error) {
        if ("INVALID_CREDENTIALS".equals(error)) return "نام کاربری یا رمز عبور صحیح نیست.";
        if ("AUTH_REDIRECT".equals(error) || "AUTH_REQUIRED".equals(error)) return "ورود به سرور انجام نشد؛ API موبایل را بررسی کنید.";
        if ("CSRF_INVALID".equals(error) || "CSRF_UNAVAILABLE".equals(error)) return "نشست امنیتی ورود ایجاد نشد. دوباره تلاش کنید.";
        if ("NETWORK_ERROR".equals(error)) return "ارتباط با سرور برقرار نشد.";
        return "ورود انجام نشد. " + (error == null ? "" : error);
    }
}
