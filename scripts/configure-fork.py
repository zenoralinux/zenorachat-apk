#!/usr/bin/env python3
"""Apply Zenora branding and safe fork defaults to a checked-out Telegram tree."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_VARS = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
APP_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


def replace_one(text: str, pattern: str, replacement: str, label: str, flags: int = 0) -> str:
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"Expected exactly one {label} match, found {count}")
    return new_text


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def point_segment_distance(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / denom))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def make_icon(size: int, round_icon: bool) -> bytes:
    """Draw an original Zenora Z mark without external image libraries."""
    rows = []
    segments = ((0.29, 0.33, 0.71, 0.33), (0.71, 0.33, 0.29, 0.67), (0.29, 0.67, 0.71, 0.67))
    stroke = 0.075
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            nx, ny = (x + 0.5) / size, (y + 0.5) / size
            t = max(0.0, min(1.0, (nx + ny) / 2.0))
            r = int(10 + (37 - 10) * t)
            g = int(18 + (99 - 18) * t)
            b = int(35 + (235 - 35) * t)
            alpha = 255
            if round_icon and math.hypot(nx - 0.5, ny - 0.5) > 0.495:
                alpha = 0
            distance = min(point_segment_distance(nx, ny, *seg) for seg in segments)
            if distance <= stroke / 2:
                r, g, b, alpha = 255, 255, 255, 255
            row.extend((r, g, b, alpha))
        rows.append(bytes(row))
    raw = b"".join(rows)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b"")


def write_icon_assets() -> None:
    density_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    res = ROOT / "TMessagesProj/src/main/res"
    for density, size in density_sizes.items():
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "ic_launcher.png").write_bytes(make_icon(size, False))
        (folder / "ic_launcher_round.png").write_bytes(make_icon(size, True))

    drawable = res / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    (drawable / "zenora_icon_background.xml").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:startColor="#0A1223" android:endColor="#2563EB" android:angle="135" />
</shape>
""",
        encoding="utf-8",
    )
    foreground = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M31,34 L77,34 L31,74 L77,74"
        android:strokeColor="#FFFFFFFF" android:strokeWidth="9"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
"""
    (drawable / "zenora_icon_foreground.xml").write_text(foreground, encoding="utf-8")
    (drawable / "zenora_icon_monochrome.xml").write_text(foreground, encoding="utf-8")

    adaptive = res / "mipmap-anydpi-v26"
    adaptive.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (adaptive / name).write_text(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/zenora_icon_background" />
    <foreground android:drawable="@drawable/zenora_icon_foreground" />
    <monochrome android:drawable="@drawable/zenora_icon_monochrome" />
</adaptive-icon>
""",
            encoding="utf-8",
        )


def configure(args: argparse.Namespace) -> None:
    if not APP_ID_RE.fullmatch(args.application_id):
        raise SystemExit("Invalid Android application ID. Use a reverse-domain identifier, for example ir.zenora.zenorachat.")
    if not BUILD_VARS.is_file() or not STRINGS.is_file():
        raise SystemExit("Telegram source is missing. Run the Telegram source sync workflow first.")

    build_vars = BUILD_VARS.read_text(encoding="utf-8")
    build_vars = replace_one(build_vars, r'public static boolean IS_BILLING_UNAVAILABLE = false;', 'public static boolean IS_BILLING_UNAVAILABLE = true;', "billing fork flag")
    build_vars = replace_one(build_vars, r'public static boolean SUPPORTS_PASSKEYS = true;', 'public static boolean SUPPORTS_PASSKEYS = false;', "passkey fork flag")
    build_vars = replace_one(build_vars, r'public static boolean SUPPORTS_FPNV = true;', 'public static boolean SUPPORTS_FPNV = false;', "FPNV fork flag")
    build_vars = replace_one(build_vars, r'public static String SAFETYNET_KEY = "[^"]*";', 'public static String SAFETYNET_KEY = "";', "SafetyNet key")
    build_vars = replace_one(build_vars, r'public static String GOOGLE_AUTH_CLIENT_ID = "[^"]*";', 'public static String GOOGLE_AUTH_CLIENT_ID = "";', "Google OAuth client")
    build_vars = replace_one(build_vars, r'public static String PLAYSTORE_APP_URL = "[^"]*";', 'public static String PLAYSTORE_APP_URL = "";', "Play Store URL")
    build_vars = replace_one(build_vars, r'public static String HUAWEI_STORE_URL = "[^"]*";', 'public static String HUAWEI_STORE_URL = "";', "Huawei Store URL")
    build_vars = replace_one(build_vars, r'public static String HUAWEI_APP_ID = "[^"]*";', 'public static String HUAWEI_APP_ID = "";', "Huawei app ID")

    api_id = os.environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
    if args.mode == "release" and (not api_id.isdigit() or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash)):
        raise SystemExit("Release builds require valid TELEGRAM_API_ID and TELEGRAM_API_HASH secrets.")
    if args.mode == "debug" and bool(api_id) != bool(api_hash):
        raise SystemExit("Debug builds require both TELEGRAM_API_ID and TELEGRAM_API_HASH, or neither.")
    if args.mode == "debug" and not api_id:
        # Never ship Telegram's official app credentials in an unofficial fork.
        # The APK remains buildable, but login/API calls need the developer's own credentials.
        build_vars = replace_one(build_vars, r"public static int APP_ID = \d+;", "public static int APP_ID = 1;", "debug placeholder API ID")
        build_vars = replace_one(build_vars, r'public static String APP_HASH = "[^"]*";', 'public static String APP_HASH = "00000000000000000000000000000000";', "debug placeholder API hash")
    if api_id:
        if not api_id.isdigit() or int(api_id) <= 0:
            raise SystemExit("TELEGRAM_API_ID must be a positive integer.")
        build_vars = replace_one(build_vars, r"public static int APP_ID = \d+;", f"public static int APP_ID = {int(api_id)};", "API ID")
    if api_hash:
        if not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
            raise SystemExit("TELEGRAM_API_HASH must be a 32-character hexadecimal value.")
        build_vars = replace_one(build_vars, r'public static String APP_HASH = "[^"]*";', f'public static String APP_HASH = "{api_hash.lower()}";', "API hash")
    BUILD_VARS.write_text(build_vars, encoding="utf-8")

    strings = STRINGS.read_text(encoding="utf-8")
    strings = replace_one(strings, r'(<string name="AppName">).*?(</string>)', r'\1ZenoraChat\2', "application name", re.DOTALL)
    strings = replace_one(strings, r'(<string name="AppNameBeta">).*?(</string>)', r'\1ZenoraChat Debug\2', "debug application name", re.DOTALL)
    STRINGS.write_text(strings, encoding="utf-8")

    maps_key = os.environ.get("ZENORA_GOOGLE_MAPS_API_KEY", "").strip()
    for manifest in (
        ROOT / "TMessagesProj/config/release/AndroidManifest.xml",
        ROOT / "TMessagesProj/config/debug/AndroidManifest.xml",
    ):
        if manifest.exists():
            content = manifest.read_text(encoding="utf-8")
            content = re.sub(
                r'(<meta-data android:name="com\.google\.android\.maps\.v2\.API_KEY" android:value=")[^"]*(")',
                lambda match: match.group(1) + maps_key + match.group(2),
                content,
            )
            manifest.write_text(content, encoding="utf-8")

    firebase_b64 = os.environ.get("ZENORA_FIREBASE_JSON_BASE64", "").strip()
    app_package = args.application_id
    if firebase_b64:
        import base64
        try:
            firebase_bytes = base64.b64decode(firebase_b64, validate=True)
            firebase = json.loads(firebase_bytes)
        except Exception as exc:
            raise SystemExit(f"ZENORA_FIREBASE_JSON_BASE64 is not valid base64 JSON: {exc}") from exc
        if not isinstance(firebase.get("client"), list):
            raise SystemExit("Firebase JSON has no client list.")
        required_packages = {app_package, app_package + ".beta"}
        actual_packages = {
            item.get("client_info", {}).get("android_client_info", {}).get("package_name")
            for item in firebase["client"]
        }
        missing = required_packages - actual_packages
        if args.mode == "release" and missing:
            raise SystemExit("Firebase JSON is missing Android client package(s): " + ", ".join(sorted(missing)))
        payload = json.dumps(firebase, indent=2) + "\n"
    else:
        if args.mode == "release":
            raise SystemExit("Release builds require ZENORA_FIREBASE_JSON_BASE64.")
        # Valid placeholder for compile/test only. Firebase messaging will not connect.
        def dummy_client(package: str) -> dict:
            return {
                "client_info": {
                    "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
                    "android_client_info": {"package_name": package},
                },
                "api_key": [{"current_key": "AIzaSyDUMMY_DEBUG_ONLY_DO_NOT_USE_IN_PRODUCTION"}],
                "services": {},
            }
        payload = json.dumps({
            "project_info": {
                "project_number": "000000000000",
                "project_id": "zenora-debug-placeholder",
                "storage_bucket": "zenora-debug-placeholder.appspot.com",
            },
            "client": [dummy_client(app_package), dummy_client(app_package + ".beta")],
            "configuration_version": "1",
        }, indent=2) + "\n"

    for relative in ("TMessagesProj/google-services.json", "TMessagesProj_App/google-services.json"):
        target = ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(payload, encoding="utf-8")

    write_icon_assets()
    print(f"Configured ZenoraChat ({args.mode}) with application ID {app_package}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("debug", "release"), required=True)
    parser.add_argument("--application-id", required=True)
    configure(parser.parse_args())


if __name__ == "__main__":
    main()
