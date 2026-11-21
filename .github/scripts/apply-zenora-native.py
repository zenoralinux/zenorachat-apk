#!/usr/bin/env python3
"""Apply the Zenora account/session bridge while retaining Telegram-native UI."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "TMessagesProj" / "src" / "main" / "AndroidManifest.xml"
LAUNCH_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "LaunchActivity.java"
USER_CONFIG = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "messenger" / "UserConfig.java"
DIALOGS_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "DialogsActivity.java"
DIALOGS_ADAPTER = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "Adapters" / "DialogsAdapter.java"
DIALOG_CELL = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "Cells" / "DialogCell.java"
SESSION = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "messenger" / "ZenoraSession.java"
API_CLIENT = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "messenger" / "ZenoraApiClient.java"
LOGIN_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "ZenoraLoginActivity.java"
LEGACY_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "zenora" / "ZenoraNativeActivity.java"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit("Required source file is missing: " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")


def write(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8")


def replace_exact(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one source match, found {count}")
    return content.replace(old, new, 1)


def replace_regex(content: str, pattern: str, replacement, label: str) -> str:
    updated, count = re.subn(pattern, replacement, content, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"{label}: expected one source match, found {count}")
    return updated


def remove_legacy_activity_registration() -> None:
    content = read(MANIFEST)
    pattern = re.compile(
        r"\s*<!-- ZENORA_NATIVE_BOOTSTRAP:.*?-->\s*"
        r"<activity\s+android:name=\"org\.telegram\.ui\.zenora\.ZenoraNativeActivity\".*?/>",
        re.DOTALL,
    )
    content, _ = pattern.subn("", content)
    if 'android:name="org.telegram.ui.zenora.ZenoraNativeActivity"' in content:
        raise SystemExit("Legacy Zenora Activity registration remains in AndroidManifest.xml.")
    write(MANIFEST, content)

    if LEGACY_ACTIVITY.exists():
        LEGACY_ACTIVITY.unlink()
        parent = LEGACY_ACTIVITY.parent
        if parent.exists() and not any(parent.iterdir()):
            parent.rmdir()


def remove_legacy_launch_redirect() -> None:
    content = read(LAUNCH_ACTIVITY)
    marker = "// ZENORA_NATIVE_BOOTSTRAP"
    if marker in content:
        pattern = re.compile(
            r"\s*// ZENORA_NATIVE_BOOTSTRAP:.*?return;\s*\}",
            re.DOTALL,
        )
        content, count = pattern.subn("", content, count=1)
        if count != 1 or marker in content:
            raise SystemExit("Could not safely remove the legacy Zenora launch redirect.")
        write(LAUNCH_ACTIVITY, content)

    content = read(LAUNCH_ACTIVITY)
    if marker in content:
        raise SystemExit("Legacy Zenora launch redirect is still present.")
    if "ApplicationLoader.postInitApplication();" not in content or "new MainTabsActivity()" not in content:
        raise SystemExit("Telegram's native LaunchActivity flow was not found; refusing to build.")


def patch_launch_activity() -> None:
    content = read(LAUNCH_ACTIVITY)
    content = replace_exact(
        content,
        '''    private BaseFragment getClientNotActivatedFragment() {
        if (LoginActivity.loadCurrentState(false, currentAccount).getInt("currentViewNum", 0) != 0) {
            return new LoginActivity();
        }
        return new IntroActivity();
    }''',
        '''    private BaseFragment getClientNotActivatedFragment() {
        // ZenoraChat accounts use the website's own username/password session.
        // Never fall through to Telegram's phone-number onboarding.
        return new ZenoraLoginActivity();
    }''',
        "LaunchActivity Zenora login entry",
    )
    write(LAUNCH_ACTIVITY, content)


def patch_user_config() -> None:
    content = read(USER_CONFIG)
    content = replace_exact(
        content,
        '''    public boolean isClientActivated() {
        synchronized (sync) {
            return currentUser != null;
        }
    }''',
        '''    public boolean isClientActivated() {
        if (ZenoraSession.isAuthenticated()) {
            clientUserId = ZenoraSession.getCompatUserId();
            return true;
        }
        return false;
    }''',
        "UserConfig active account",
    )
    content = replace_exact(
        content,
        '''    public long getClientUserId() {
        synchronized (sync) {
            return currentUser != null ? currentUser.id : 0;
        }
    }''',
        '''    public long getClientUserId() {
        return ZenoraSession.isAuthenticated() ? ZenoraSession.getCompatUserId() : 0;
    }''',
        "UserConfig synthetic account id",
    )
    content = replace_exact(
        content,
        '''    public String getClientPhone() {
        synchronized (sync) {
            return currentUser != null && currentUser.phone != null ? currentUser.phone : "";
        }
    }''',
        '''    public String getClientPhone() {
        return "";
    }''',
        "UserConfig Zenora phone isolation",
    )
    content = replace_exact(
        content,
        '''    public TLRPC.User getCurrentUser() {
        synchronized (sync) {
            return currentUser;
        }
    }''',
        '''    public TLRPC.User getCurrentUser() {
        return ZenoraSession.isAuthenticated() ? ZenoraSession.createCompatUser() : null;
    }''',
        "UserConfig compatibility self user",
    )
    write(USER_CONFIG, content)


def patch_dialogs_activity() -> None:
    content = read(DIALOGS_ACTIVITY)
    content = replace_exact(
        content,
        "import org.telegram.messenger.UserConfig;\n",
        "import org.telegram.messenger.UserConfig;\nimport org.telegram.messenger.ZenoraApiClient;\nimport org.telegram.messenger.ZenoraSession;\n",
        "DialogsActivity Zenora imports",
    )
    content = replace_exact(
        content,
        "    private ViewPage[] viewPages;\n",
        "    private ViewPage[] viewPages;\n    private boolean zenoraRefreshInFlight;\n    private long zenoraLastRefreshAt;\n",
        "DialogsActivity Zenora refresh state",
    )

    network_pattern = re.compile(
        r"        loadDialogs\(getAccountInstance\(\)\);\n"
        r".*?"
        r"        BirthdayController\.getInstance\(currentAccount\)\.check\(\);",
        re.DOTALL,
    )

    def wrap_dialog_bootstrap(match: re.Match) -> str:
        original = match.group(0)
        indented = "\n".join(("    " + line) if line else line for line in original.split("\n"))
        return (
            "        if (!ZenoraSession.isAuthenticated()) {\n"
            + indented
            + "\n        } else {\n"
            + "            // Dialogs and all account data are supplied by the Zenora REST service.\n"
            + "            dialogsLoaded[currentAccount] = true;\n"
            + "        }"
        )

    content = replace_regex(
        content,
        network_pattern.pattern,
        wrap_dialog_bootstrap,
        "DialogsActivity MTProto bootstrap isolation",
    )

    content = replace_exact(
        content,
        '''        int currentAccount = accountInstance.getCurrentAccount();
        if (!dialogsLoaded[currentAccount]) {''',
        '''        int currentAccount = accountInstance.getCurrentAccount();
        if (ZenoraSession.isAuthenticated()) {
            dialogsLoaded[currentAccount] = true;
            return;
        }
        if (!dialogsLoaded[currentAccount]) {''',
        "DialogsActivity loadDialogs REST mode",
    )

    content = replace_exact(
        content,
        '''    private void checkListLoad(ViewPage viewPage, int firstVisibleItem, int lastVisibleItem) {
        if (tabsAnimationInProgress''',
        '''    private void checkListLoad(ViewPage viewPage, int firstVisibleItem, int lastVisibleItem) {
        if (ZenoraSession.isAuthenticated()) {
            return;
        }
        if (tabsAnimationInProgress''',
        "DialogsActivity pagination network isolation",
    )

    content = replace_exact(
        content,
        '''        if (viewPages != null) {
            for (int a = 0; a < viewPages.length; a++) {
                viewPages[a].dialogsAdapter.notifyDataSetChanged();
            }
        }
        if (commentView != null) {''',
        '''        if (viewPages != null) {
            for (int a = 0; a < viewPages.length; a++) {
                viewPages[a].dialogsAdapter.notifyDataSetChanged();
            }
        }
        if (ZenoraSession.isAuthenticated()) {
            refreshZenoraDialogs();
        }
        if (commentView != null) {''',
        "DialogsActivity resume Zenora refresh",
    )

    content = replace_exact(
        content,
        '''        if (!onlySelect && folderId == 0 && communityId == 0) {
            getMediaDataController().checkStickers(MediaDataController.TYPE_EMOJI);
        }''',
        '''        if (!ZenoraSession.isAuthenticated() && !onlySelect && folderId == 0 && communityId == 0) {
            getMediaDataController().checkStickers(MediaDataController.TYPE_EMOJI);
        }''',
        "DialogsActivity sticker MTProto isolation",
    )

    content = replace_exact(
        content,
        '''            Object object = dialogsAdapter.getItem(position);
            if (delegate != null''',
        '''            Object object = dialogsAdapter.getItem(position);
            if (object instanceof ZenoraSession.DialogInfo) {
                android.widget.Toast.makeText(
                        getParentActivity(),
                        "باز کردن گفتگو در مرحله بعدی اتصال پیام‌ها فعال می‌شود.",
                        android.widget.Toast.LENGTH_SHORT
                ).show();
                return;
            }
            if (delegate != null''',
        "DialogsActivity Zenora row click",
    )

    content = replace_exact(
        content,
        '''                public boolean onItemClick(View view, int position, float x, float y) {
                    if (view instanceof DialogCell && ((DialogCell) view).isBlocked()) {''',
        '''                public boolean onItemClick(View view, int position, float x, float y) {
                    if (ZenoraSession.isAuthenticated()) {
                        return true;
                    }
                    if (view instanceof DialogCell && ((DialogCell) view).isBlocked()) {''',
        "DialogsActivity Zenora long press isolation",
    )

    content = replace_exact(
        content,
        '''        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (waitingForDialogsAnimationEnd(parentPage)''',
        '''        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (ZenoraSession.isAuthenticated()) {
                return 0;
            }
            if (waitingForDialogsAnimationEnd(parentPage)''',
        "DialogsActivity disable Telegram swipe gestures for Zenora",
    )
    content = replace_exact(
        content,
        '''        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            if (!(target.itemView instanceof DialogCell)) {''',
        '''        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            if (ZenoraSession.isAuthenticated()) {
                return false;
            }
            if (!(target.itemView instanceof DialogCell)) {''',
        "DialogsActivity disable Telegram reordering for Zenora",
    )
    content = replace_exact(
        content,
        '''        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            if (viewHolder != null) {''',
        '''        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            if (ZenoraSession.isAuthenticated()) {
                return;
            }
            if (viewHolder != null) {''',
        "DialogsActivity prevent Telegram swipe actions for Zenora",
    )

    marker = '''    @Override
    public void onResume() {
        super.onResume();'''
    helper = '''    private void updateZenoraDialogs(ArrayList<ZenoraSession.DialogInfo> dialogs) {
        if (viewPages == null) {
            return;
        }
        for (ViewPage viewPage : viewPages) {
            if (viewPage != null && viewPage.dialogsAdapter != null) {
                viewPage.dialogsAdapter.setZenoraDialogs(dialogs);
            }
        }
    }

    private void refreshZenoraDialogs() {
        if (!ZenoraSession.isAuthenticated()) {
            return;
        }
        ArrayList<ZenoraSession.DialogInfo> cached = ZenoraSession.getCachedDialogs();
        updateZenoraDialogs(cached);
        long now = System.currentTimeMillis();
        if (zenoraRefreshInFlight || now - zenoraLastRefreshAt < 5000) {
            return;
        }
        zenoraRefreshInFlight = true;
        zenoraLastRefreshAt = now;
        ZenoraApiClient.fetchDialogs(getParentActivity(), new ZenoraApiClient.DialogsCallback() {
            @Override
            public void onSuccess(ArrayList<ZenoraSession.DialogInfo> dialogs) {
                zenoraRefreshInFlight = false;
                updateZenoraDialogs(dialogs);
            }

            @Override
            public void onError(String message, boolean sessionExpired) {
                zenoraRefreshInFlight = false;
                if (sessionExpired) {
                    ZenoraSession.clear(getParentActivity());
                    Activity activity = getParentActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Intent restart = new Intent(activity, LaunchActivity.class);
                        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        activity.startActivity(restart);
                        activity.finish();
                    }
                } else if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();'''
    content = replace_exact(content, marker, helper, "DialogsActivity Zenora REST refresh helper")

    write(DIALOGS_ACTIVITY, content)


def patch_dialogs_adapter() -> None:
    content = read(DIALOGS_ADAPTER)
    content = replace_exact(
        content,
        "import org.telegram.messenger.UserConfig;\n",
        "import org.telegram.messenger.UserConfig;\nimport org.telegram.messenger.ZenoraSession;\n",
        "DialogsAdapter Zenora import",
    )
    content = replace_exact(
        content,
        "            VIEW_TYPE_DIALOG_COMMUNITY = 23;\n",
        "            VIEW_TYPE_DIALOG_COMMUNITY = 23,\n            VIEW_TYPE_ZENORA_DIALOG = 24;\n",
        "DialogsAdapter Zenora view type",
    )
    content = replace_exact(
        content,
        "    private int dialogsCount;\n",
        "    private int dialogsCount;\n    private ArrayList<ZenoraSession.DialogInfo> zenoraDialogs = new ArrayList<>();\n",
        "DialogsAdapter Zenora dialog state",
    )
    content = replace_exact(
        content,
        "        currentAccount = account;\n",
        "        currentAccount = account;\n        if (ZenoraSession.isAuthenticated()) zenoraDialogs = ZenoraSession.getCachedDialogs();\n",
        "DialogsAdapter Zenora cached state",
    )
    content = replace_exact(
        content,
        '''    public int getDialogsCount() {
        return dialogsCount;
    }
''',
        '''    public int getDialogsCount() {
        return dialogsCount;
    }

    public void setZenoraDialogs(ArrayList<ZenoraSession.DialogInfo> dialogs) {
        if (!ZenoraSession.isAuthenticated()) {
            return;
        }
        zenoraDialogs = dialogs == null ? new ArrayList<>() : new ArrayList<>(dialogs);
        updateList(null);
    }
''',
        "DialogsAdapter set Zenora dialogs",
    )
    content = replace_exact(
        content,
        "        TLRPC.Dialog dialog;\n",
        "        TLRPC.Dialog dialog;\n        ZenoraSession.DialogInfo zenoraDialog;\n",
        "DialogsAdapter Zenora item field",
    )
    content = replace_exact(
        content,
        '''        public ItemInternal(int viewType, TLRPC.Dialog dialog) {
            super(viewType, true);''',
        '''        public ItemInternal(int viewType, ZenoraSession.DialogInfo zenoraDialog) {
            super(viewType, true);
            this.zenoraDialog = zenoraDialog;
            long stableKey = -800000000000L - Math.abs(zenoraDialog.roomId);
            int currentId = dialogsStableIds.get(stableKey, -1);
            if (currentId >= 0) {
                stableId = currentId;
            } else {
                stableId = stableIdPointer++;
                dialogsStableIds.put(stableKey, stableId);
            }
        }

        public ItemInternal(int viewType, TLRPC.Dialog dialog) {
            super(viewType, true);''',
        "DialogsAdapter Zenora item constructor",
    )
    content = replace_exact(
        content,
        '''            if (viewType == VIEW_TYPE_DIALOG) {
                return dialog != null && itemInternal.dialog != null && dialog.id == itemInternal.dialog.id
                        && isFolder == itemInternal.isFolder &&
                        isForumCell == itemInternal.isForumCell &&
                        pinned == itemInternal.pinned;
            }''',
        '''            if (viewType == VIEW_TYPE_ZENORA_DIALOG) {
                return zenoraDialog != null && itemInternal.zenoraDialog != null
                        && zenoraDialog.roomId == itemInternal.zenoraDialog.roomId;
            }
            if (viewType == VIEW_TYPE_DIALOG) {
                return dialog != null && itemInternal.dialog != null && dialog.id == itemInternal.dialog.id
                        && isFolder == itemInternal.isFolder &&
                        isForumCell == itemInternal.isForumCell &&
                        pinned == itemInternal.pinned;
            }''',
        "DialogsAdapter Zenora item identity",
    )
    content = replace_exact(
        content,
        '''        } else if (item.dialog != null) {
            return item.dialog;
        } else if (item.contact != null) {''',
        '''        } else if (item.dialog != null) {
            return item.dialog;
        } else if (item.zenoraDialog != null) {
            return item.zenoraDialog;
        } else if (item.contact != null) {''',
        "DialogsAdapter Zenora item retrieval",
    )
    content = replace_exact(
        content,
        '''            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).viewType == newItems.get(newItemPosition).viewType;
            }''',
        '''            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                ItemInternal oldItem = oldItems.get(oldItemPosition);
                ItemInternal newItem = newItems.get(newItemPosition);
                if (oldItem.viewType != newItem.viewType) {
                    return false;
                }
                if (oldItem.viewType == VIEW_TYPE_ZENORA_DIALOG) {
                    return oldItem.zenoraDialog != null && oldItem.zenoraDialog.hasSameContent(newItem.zenoraDialog);
                }
                return true;
            }''',
        "DialogsAdapter Zenora diff content",
    )

    create_case = '''            case VIEW_TYPE_ZENORA_DIALOG:
                DialogCell zenoraDialogCell = new DialogCell(parentFragment, mContext, true, false, currentAccount, null);
                zenoraDialogCell.setDialogCellDelegate(this);
                zenoraDialogCell.setIsTransitionSupport(isTransitionSupport);
                view = zenoraDialogCell;
                break;
            case VIEW_TYPE_DIALOG_COMMUNITY:
'''
    content = replace_exact(
        content,
        """            case VIEW_TYPE_DIALOG_COMMUNITY:
""",
        create_case,
        "DialogsAdapter Zenora cell creation",
    )

    bind_case = '''            case VIEW_TYPE_ZENORA_DIALOG: {
                ZenoraSession.DialogInfo info = (ZenoraSession.DialogInfo) getItem(i);
                DialogCell cell = (DialogCell) holder.itemView;
                DialogCell.CustomDialog customDialog = new DialogCell.CustomDialog();
                customDialog.id = (int) info.roomId;
                customDialog.name = info.title;
                customDialog.message = info.message;
                customDialog.avatarPath = info.avatarPath;
                customDialog.unread_count = info.unreadCount;
                customDialog.date = info.date;
                customDialog.pinned = info.pinned;
                customDialog.muted = info.muted;
                customDialog.verified = info.verified;
                customDialog.isMedia = info.media;
                customDialog.type = "channel".equals(info.type) ? 2 : ("group".equals(info.type) ? 1 : 0);
                cell.useSeparator = true;
                cell.fullSeparator = false;
                cell.setDialog(customDialog);
                cell.checkHeight();
                break;
            }
            case VIEW_TYPE_DIALOG: {'''
    content = replace_exact(
        content,
        "            case VIEW_TYPE_DIALOG: {",
        bind_case,
        "DialogsAdapter Zenora native row binding",
    )

    update_pattern = re.compile(
        r"    private void updateItemList\(\) \{\n"
        r"        if \(communityId != 0\) \{\n"
        r"            updateItemListForCommunity\(\);\n"
        r"            return;\n"
        r"        \}\n\n"
        r"        itemInternals\.clear\(\);\n"
        r"        updateHasHints\(\);"
    )
    update_replacement = '''    private void updateItemList() {
        if (communityId != 0) {
            updateItemListForCommunity();
            return;
        }
        if (ZenoraSession.isAuthenticated()
                && dialogsType == DialogsActivity.DIALOGS_TYPE_DEFAULT
                && folderId == 0
                && !isOnlySelect) {
            itemInternals.clear();
            hasHints = false;
            hasChatlistHint = false;
            onlineContacts = null;
            dialogsCount = zenoraDialogs.size();
            isEmpty = dialogsCount == 0;
            if (isEmpty) {
                itemInternals.add(new ItemInternal(VIEW_TYPE_EMPTY, DialogsEmptyCell.TYPE_FILTER_NO_CHATS_TO_DISPLAY));
            } else {
                for (ZenoraSession.DialogInfo dialog : zenoraDialogs) {
                    itemInternals.add(new ItemInternal(VIEW_TYPE_ZENORA_DIALOG, dialog));
                }
            }
            itemInternals.add(new ItemInternal(VIEW_TYPE_LAST_EMPTY));
            return;
        }

        itemInternals.clear();
        updateHasHints();'''
    content = replace_regex(content, update_pattern.pattern, update_replacement, "DialogsAdapter Zenora list source")

    content = replace_exact(
        content,
        '''    public int getItemHeight(int position) {
        if (itemInternals.get(position).viewType == VIEW_TYPE_DIALOG) {''',
        '''    public int getItemHeight(int position) {
        if (itemInternals.get(position).viewType == VIEW_TYPE_ZENORA_DIALOG) {
            return AndroidUtilities.dp(SharedConfig.useThreeLinesLayout ? 76 : 70) + 1;
        }
        if (itemInternals.get(position).viewType == VIEW_TYPE_DIALOG) {''',
        "DialogsAdapter Zenora row height",
    )
    write(DIALOGS_ADAPTER, content)


def patch_dialog_cell() -> None:
    content = read(DIALOG_CELL)
    content = replace_exact(
        content,
        "        public String message;\n        public int id;\n",
        "        public String message;\n        public String avatarPath;\n        public int id;\n",
        "DialogCell Zenora avatar field",
    )
    content = replace_exact(
        content,
        '''    public void setDialog(CustomDialog dialog) {
        customDialog = dialog;
        messageId = 0;''',
        '''    public void setDialog(CustomDialog dialog) {
        customDialog = dialog;
        currentDialogId = dialog == null ? 0 : dialog.id;
        currentDialogFolderId = 0;
        isDialogCell = false;
        user = null;
        chat = null;
        messageId = 0;''',
        "DialogCell Zenora custom dialog identity",
    )
    content = replace_exact(
        content,
        '''            avatarDrawable.setInfo(customDialog.id, customDialog.name, null);
            if (isShareToStoryCell) {
                avatarImage.setImage(null, "50_50", repostStoryDrawable, null, 0);
            } else {
                avatarImage.setImage(null, "50_50", avatarDrawable, null, 0);
            }''',
        '''            avatarDrawable.setInfo(customDialog.id, customDialog.name, null);
            if (isShareToStoryCell) {
                avatarImage.setImage(null, "50_50", repostStoryDrawable, null, 0);
            } else if (!TextUtils.isEmpty(customDialog.avatarPath)
                    && new java.io.File(customDialog.avatarPath).isFile()) {
                avatarImage.setImage(customDialog.avatarPath, "50_50", avatarDrawable);
            } else {
                avatarImage.setImage(null, "50_50", avatarDrawable, null, 0);
            }''',
        "DialogCell Zenora avatar rendering",
    )
    write(DIALOG_CELL, content)


def verify_sources() -> None:
    for path in (SESSION, API_CLIENT, LOGIN_ACTIVITY):
        read(path)
    launch = read(LAUNCH_ACTIVITY)
    user_config = read(USER_CONFIG)
    adapter = read(DIALOGS_ADAPTER)
    dialogs = read(DIALOGS_ACTIVITY)
    cell = read(DIALOG_CELL)
    checks = {
        "Zenora login selected": "return new ZenoraLoginActivity();" in launch,
        "Zenora account compatibility": "ZenoraSession.createCompatUser()" in user_config,
        "Zenora list row": "VIEW_TYPE_ZENORA_DIALOG" in adapter,
        "Zenora REST loading": "ZenoraApiClient.fetchDialogs" in dialogs,
        "Telegram native dialog cell": "cell.setDialog(customDialog);" in adapter,
        "Zenora avatar support": "customDialog.avatarPath" in cell,
        "No Telegram login fallback": "return new IntroActivity();" not in launch,
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise SystemExit("Zenora native overlay verification failed: " + ", ".join(failed))


def main() -> None:
    remove_legacy_launch_redirect()
    remove_legacy_activity_registration()
    patch_launch_activity()
    patch_user_config()
    patch_dialogs_activity()
    patch_dialogs_adapter()
    patch_dialog_cell()
    verify_sources()
    print("Zenora Phase 1 overlay applied: Zenora auth + REST dialogs on Telegram-native DialogsActivity/DialogCell.")


if __name__ == "__main__":
    main()
