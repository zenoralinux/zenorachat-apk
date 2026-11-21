#!/usr/bin/env python3
"""Reapply Zenora's CI performance overlay after replacing the Telegram source tree."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ABI_BLOCK = """def zenoraDefaultAbis = 'armeabi-v7a,arm64-v8a,x86,x86_64'
def zenoraTargetAbis = (rootProject.findProperty('ZENORA_ABIS') ?: zenoraDefaultAbis)
        .toString().split(',').collect { it.trim() }.findAll { !it.isEmpty() }
def zenoraSupportedAbis = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64']
def zenoraInvalidAbis = zenoraTargetAbis.findAll { !zenoraSupportedAbis.contains(it) }
if (zenoraTargetAbis.isEmpty() || !zenoraInvalidAbis.isEmpty()) {
    throw new GradleException("Invalid ZENORA_ABIS. Supported values: ${zenoraSupportedAbis.join(', ')}")
}

"""


def read_text(relative_path: str) -> str:
    path = ROOT / relative_path
    if not path.is_file():
        raise SystemExit(f"Required upstream Gradle file is missing: {relative_path}")
    return path.read_text(encoding="utf-8")


def write_text(relative_path: str, content: str) -> None:
    (ROOT / relative_path).write_text(content, encoding="utf-8")


def ensure_abi_block(content: str, plugin_anchor: str, relative_path: str) -> str:
    if "def zenoraTargetAbis =" in content:
        return content
    if content.count(plugin_anchor) != 1:
        raise SystemExit(f"Cannot safely apply ABI configuration to {relative_path}: plugin anchor changed")
    return content.replace(plugin_anchor, plugin_anchor + "\n" + ABI_BLOCK, 1)


def patch_app_module() -> None:
    path = "TMessagesProj_App/build.gradle"
    content = ensure_abi_block(
        read_text(path),
        "apply plugin: 'org.telegram.build-app-plugin'",
        path,
    )
    pattern = re.compile(
        r'(?m)^(\s*)abiFilters\s+"armeabi-v7a",\s*"arm64-v8a",\s*"x86",\s*"x86_64"\s*$'
    )
    content, replacements = pattern.subn(r"\1abiFilters(*zenoraTargetAbis)", content)
    if replacements == 0 and "abiFilters(*zenoraTargetAbis)" not in content:
        raise SystemExit(f"Upstream ABI declaration changed in {path}; refusing an incomplete optimization")
    write_text(path, content)


def patch_native_library() -> None:
    path = "TMessagesProj/build.gradle"
    content = ensure_abi_block(read_text(path), "apply plugin: 'idea'", path)
    if "abiFilters(*zenoraTargetAbis)" not in content:
        target = re.search(r"(?m)^        targetSdkVersion[^\n]*\n", content)
        if not target:
            raise SystemExit(f"Cannot locate defaultConfig targetSdkVersion in {path}")
        insertion = (
            target.group(0)
            + "\n        ndk {\n"
            + "            abiFilters(*zenoraTargetAbis)\n"
            + "        }\n"
        )
        content = content[: target.start()] + insertion + content[target.end() :]
    write_text(path, content)


def patch_gradle_properties() -> None:
    path = "gradle.properties"
    content = read_text(path)
    settings = {
        "org.gradle.workers.max": "4",
        "org.gradle.caching": "true",
    }
    for key, value in settings.items():
        pattern = re.compile(rf"(?m)^{re.escape(key)}=.*$")
        line = f"{key}={value}"
        if pattern.search(content):
            content = pattern.sub(line, content)
        else:
            if not content.endswith("\n"):
                content += "\n"
            content += line + "\n"
    write_text(path, content)


def main() -> None:
    patch_app_module()
    patch_native_library()
    patch_gradle_properties()
    print("Zenora build overlay applied: configurable ABIs, native ABI filtering, 4 workers and Gradle build cache.")


if __name__ == "__main__":
    main()
