#!/usr/bin/env python3
"""Apply the persistent Zenora native-client overlay after Telegram source sync."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "scripts" / "zenora-native" / "ZenoraNativeActivity.java"
JAVA_TARGET = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "zenora"
MANIFEST = ROOT / "TMessagesProj" / "src" / "main" / "AndroidManifest.xml"
LAUNCH_ACTIVITY = ROOT / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "LaunchActivity.java"

def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit("Required upstream file is missing: " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")

def write(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8")

def copy_sources() -> None:
    if not SOURCE.is_file():
        raise SystemExit("Zenora native source is missing: scripts/zenora-native/ZenoraNativeActivity.java")
    JAVA_TARGET.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SOURCE, JAVA_TARGET / "ZenoraNativeActivity.java")

def patch_manifest() -> None:
    content = read(MANIFEST)
    marker = 'android:name="org.telegram.ui.zenora.ZenoraNativeActivity"'
    if marker in content:
        return
    if content.count("</application>") != 1:
        raise SystemExit("Cannot safely register ZenoraNativeActivity in AndroidManifest.xml")
    activity = """
        <!-- ZENORA_NATIVE_BOOTSTRAP: native account entry for the first integration phase. -->
        <activity
            android:name="org.telegram.ui.zenora.ZenoraNativeActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
"""
    write(MANIFEST, content.replace("</application>", activity + "    </application>", 1))

def patch_launch_activity() -> None:
    content = read(LAUNCH_ACTIVITY)
    marker = "// ZENORA_NATIVE_BOOTSTRAP"
    if marker in content:
        return
    pattern = re.compile(r"(@Override\s+protected void onCreate\(Bundle savedInstanceState\)\s*\{)")
    updated, count = pattern.subn(
        r"""\\1
        // ZENORA_NATIVE_BOOTSTRAP: keep the Telegram protocol stack out of Zenora account mode.
        // The original Telegram screens remain in the source tree; the Zenora REST client
        // runs as an isolated native entry point and never starts an MTProto session.
        super.onCreate(savedInstanceState);
        startActivity(new android.content.Intent(this, org.telegram.ui.zenora.ZenoraNativeActivity.class));
        finish();
        return;
""",
        content,
        count=1,
    )
    if count != 1:
        raise SystemExit("Cannot safely install Zenora entry routing in LaunchActivity.onCreate")
    write(LAUNCH_ACTIVITY, updated)

def main() -> None:
    copy_sources()
    patch_manifest()
    patch_launch_activity()
    print("Zenora native client overlay applied successfully.")

if __name__ == "__main__":
    main()
