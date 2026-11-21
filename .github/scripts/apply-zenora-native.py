#!/usr/bin/env python3
"""Keep Telegram's own launcher and UI intact for ZenoraChat builds.

The previous integration redirected LaunchActivity to a separately drawn
Android Activity. That bypassed Telegram's LoginActivity, MainTabsActivity,
DialogsActivity and ChatActivity. This overlay deliberately removes that
redirect and does not register or copy the legacy ZenoraNativeActivity.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "TMessagesProj" / "src" / "main" / "AndroidManifest.xml"
LAUNCH_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "LaunchActivity.java"
LEGACY_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "zenora" / "ZenoraNativeActivity.java"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit("Required upstream file is missing: " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")


def write(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8")


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

    # Guard against accidental reintroduction of a custom launcher route.
    content = read(LAUNCH_ACTIVITY)
    if marker in content:
        raise SystemExit("Legacy Zenora launch redirect is still present.")
    if "ApplicationLoader.postInitApplication();" not in content or "new MainTabsActivity()" not in content:
        raise SystemExit("Telegram's native LaunchActivity flow was not found; refusing to build.")


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

    # The old class is kept under scripts/ as historical source only. It must
    # never be copied into the Android source set or launched by this build.
    if LEGACY_ACTIVITY.exists():
        LEGACY_ACTIVITY.unlink()
        parent = LEGACY_ACTIVITY.parent
        if parent.exists() and not any(parent.iterdir()):
            parent.rmdir()


def main() -> None:
    remove_legacy_launch_redirect()
    remove_legacy_activity_registration()
    print("Telegram native UI preserved: LaunchActivity -> LoginActivity/MainTabsActivity.")


if __name__ == "__main__":
    main()
