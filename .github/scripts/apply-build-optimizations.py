#!/usr/bin/env python3
"""Reapply Zenora CI build optimizations after replacing the Telegram source tree."""

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
ABI_END = """    throw new GradleException("Invalid ZENORA_ABIS. Supported values: ${zenoraSupportedAbis.join(', ')}")
}
"""
FAST_BLOCK = """
def zenoraFastDebug = rootProject.findProperty('ZENORA_FAST_DEBUG')?.toString() == 'true'
def zenoraDebugSymbolLevel = zenoraFastDebug ? 'SYMBOL_TABLE' : 'FULL'
"""
CMAKE_ARGS = """                def zenoraCmakeArguments = [
                    '-DANDROID_STL=c++_static',
                    '-DANDROID_PLATFORM=android-23'
                ]
                if (rootProject.findProperty('ZENORA_USE_CCACHE')?.toString() == 'true') {
                    zenoraCmakeArguments.add('-DCMAKE_C_COMPILER_LAUNCHER=ccache')
                    zenoraCmakeArguments.add('-DCMAKE_CXX_COMPILER_LAUNCHER=ccache')
                }
                if (zenoraFastDebug) {
                    zenoraCmakeArguments.add('-DZENORA_FAST_DEBUG=ON')
                }
                arguments zenoraCmakeArguments
"""

def read_file(relative):
    path = ROOT / relative
    if not path.is_file():
        raise SystemExit(f"Required upstream file is missing: {relative}")
    return path.read_text(encoding="utf-8")

def write_file(relative, content):
    (ROOT / relative).write_text(content, encoding="utf-8")

def ensure_variables(content, plugin_anchor, relative):
    if "def zenoraTargetAbis =" not in content:
        if content.count(plugin_anchor) != 1:
            raise SystemExit(f"Cannot safely add ABI settings to {relative}")
        content = content.replace(plugin_anchor, plugin_anchor + "\n\n" + ABI_BLOCK, 1)
    if "def zenoraFastDebug =" not in content:
        if content.count(ABI_END) != 1:
            raise SystemExit(f"Cannot safely add Fast Debug settings to {relative}")
        content = content.replace(ABI_END, ABI_END + FAST_BLOCK, 1)
    return content

def patch_debug_symbols(content, relative):
    if "ndk.debugSymbolLevel = zenoraDebugSymbolLevel" in content:
        return content
    pattern = re.compile(r"(?ms)(buildTypes\s*\{\s*debug\s*\{.*?ndk\.debugSymbolLevel\s*=\s*)['\"]FULL['\"]")
    updated, count = pattern.subn(r"\1zenoraDebugSymbolLevel", content, count=1)
    if count != 1:
        raise SystemExit(f"Cannot safely change Debug symbols in {relative}")
    return updated

def patch_app():
    path = "TMessagesProj_App/build.gradle"
    content = ensure_variables(read_file(path), "apply plugin: 'org.telegram.build-app-plugin'", path)
    pattern = re.compile(r'(?m)^(\s*)abiFilters\s+"armeabi-v7a",\s*"arm64-v8a",\s*"x86",\s*"x86_64"\s*$')
    content, count = pattern.subn(r"\1abiFilters(*zenoraTargetAbis)", content)
    if count == 0 and "abiFilters(*zenoraTargetAbis)" not in content:
        raise SystemExit(f"Cannot safely patch ABI filters in {path}")
    write_file(path, patch_debug_symbols(content, path))

def patch_library():
    path = "TMessagesProj/build.gradle"
    content = ensure_variables(read_file(path), "apply plugin: 'idea'", path)
    if "abiFilters(*zenoraTargetAbis)" not in content:
        target = re.search(r"(?m)^        targetSdkVersion[^\n]*\n", content)
        if not target:
            raise SystemExit(f"Cannot locate targetSdkVersion in {path}")
        insert = target.group(0) + "\n        ndk {\n            abiFilters(*zenoraTargetAbis)\n        }\n"
        content = content[:target.start()] + insert + content[target.end():]
    content = patch_debug_symbols(content, path)
    old = "                arguments '-DANDROID_STL=c++_static', '-DANDROID_PLATFORM=android-23' //, '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'"
    if CMAKE_ARGS.strip() not in content:
        if content.count(old) != 1:
            raise SystemExit(f"Cannot safely add ccache CMake launcher arguments in {path}")
        content = content.replace(old, CMAKE_ARGS.rstrip(), 1)
    write_file(path, content)

def patch_native_cmake():
    path = "TMessagesProj/jni/CMakeLists.txt"
    content = read_file(path)
    if "option(ZENORA_FAST_DEBUG" in content:
        return
    anchor = "set(CMAKE_CXX_EXTENSIONS OFF)\n"
    if content.count(anchor) != 1:
        raise SystemExit(f"Cannot safely add Fast Debug LTO control to {path}")
    content = content.replace(
        anchor,
        anchor + "\noption(ZENORA_FAST_DEBUG \"Disable full LTO for quick Zenora Debug builds\" OFF)\n"
        + "if(ZENORA_FAST_DEBUG)\n    set(ZENORA_LTO_FLAG \"\")\n"
        + "else()\n    set(ZENORA_LTO_FLAG \"-flto=full\")\nendif()\n",
        1,
    )
    changes = {
        'set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -flto=full")':
        'set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden ${ZENORA_LTO_FLAG}")',
        'set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -flto=full")':
        'set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden ${ZENORA_LTO_FLAG}")',
        'set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=full -Wl,--gc-sections -Wl,--icf=safe -Wl,-Bsymbolic")':
        'set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${ZENORA_LTO_FLAG} -Wl,--gc-sections -Wl,--icf=safe -Wl,-Bsymbolic")',
    }
    for old, new in changes.items():
        if content.count(old) != 1:
            raise SystemExit(f"Expected one LTO anchor in {path}: {old}")
        content = content.replace(old, new, 1)
    write_file(path, content)

def patch_gradle_properties():
    path = "gradle.properties"
    content = read_file(path)
    for key, value in {"org.gradle.workers.max": "4", "org.gradle.caching": "true"}.items():
        pattern = re.compile(rf"(?m)^{re.escape(key)}=.*$")
        line = f"{key}={value}"
        if pattern.search(content):
            content = pattern.sub(line, content)
        else:
            content += ("" if content.endswith("\n") else "\n") + line + "\n"
    write_file(path, content)

def main():
    patch_app()
    patch_library()
    patch_native_cmake()
    patch_gradle_properties()
    print("Zenora build overlay applied: ABI filters, ccache, Fast Debug without Full LTO and lighter Debug symbols.")

if __name__ == "__main__":
    main()
