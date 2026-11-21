#!/usr/bin/env python3
"""Keep every Telegram dialogs-list mode isolated from MTProto for Zenora accounts."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "Adapters" / "DialogsAdapter.java"


def main() -> None:
    if not ADAPTER.is_file():
        raise SystemExit("DialogsAdapter.java is missing.")
    source = ADAPTER.read_text(encoding="utf-8")

    pattern = re.compile(
        r"    private void updateItemList\(\) \{\n"
        r"        if \(communityId != 0\) \{\n"
        r"            updateItemListForCommunity\(\);\n"
        r"            return;\n"
        r"        \}\n"
        r"        if \(ZenoraSession\.isAuthenticated\(\)\n"
        r"                && dialogsType == DialogsActivity\.DIALOGS_TYPE_DEFAULT\n"
        r"                && folderId == 0\n"
        r"                && !isOnlySelect\) \{\n"
        r".*?"
        r"            return;\n"
        r"        \}\n\n"
        r"        itemInternals\.clear\(\);\n"
        r"        updateHasHints\(\);",
        re.DOTALL,
    )

    replacement = '''    private void updateItemList() {
        if (ZenoraSession.isAuthenticated()) {
            itemInternals.clear();
            hasHints = false;
            hasChatlistHint = false;
            onlineContacts = null;
            dialogsCount = 0;
            isEmpty = true;
            // Phase 1 supports the main conversation list only. Never fall
            // through to Telegram's MessagesController for archive, folders,
            // picker modes or community lists while a Zenora account is active.
            if (dialogsType == DialogsActivity.DIALOGS_TYPE_DEFAULT
                    && folderId == 0
                    && !isOnlySelect
                    && communityId == 0) {
                dialogsCount = zenoraDialogs.size();
                isEmpty = dialogsCount == 0;
                if (isEmpty) {
                    itemInternals.add(new ItemInternal(VIEW_TYPE_EMPTY, DialogsEmptyCell.TYPE_FILTER_NO_CHATS_TO_DISPLAY));
                } else {
                    for (ZenoraSession.DialogInfo dialog : zenoraDialogs) {
                        itemInternals.add(new ItemInternal(VIEW_TYPE_ZENORA_DIALOG, dialog));
                    }
                }
            } else {
                itemInternals.add(new ItemInternal(VIEW_TYPE_EMPTY, DialogsEmptyCell.TYPE_FILTER_NO_CHATS_TO_DISPLAY));
            }
            itemInternals.add(new ItemInternal(VIEW_TYPE_LAST_EMPTY));
            return;
        }
        if (communityId != 0) {
            updateItemListForCommunity();
            return;
        }

        itemInternals.clear();
        updateHasHints();'''

    updated, count = pattern.subn(replacement, source, count=1)
    if count != 1:
        raise SystemExit("Could not safely isolate the Zenora dialog-list source.")
    if updated.count("if (ZenoraSession.isAuthenticated()) {") < 1:
        raise SystemExit("Zenora list isolation check missing.")
    if "if (ZenoraSession.isAuthenticated()) {\n            itemInternals.clear();" not in updated:
        raise SystemExit("Unconditional Zenora list branch was not installed.")

    ADAPTER.write_text(updated, encoding="utf-8")
    print("Zenora dialogs are isolated from Telegram MTProto in all adapter modes.")


if __name__ == "__main__":
    main()
