#!/usr/bin/env bash
set -euo pipefail

MODE="${1:?usage: validate-apk.sh <debug|release> <apk>}"
APK="${2:?usage: validate-apk.sh <debug|release> <apk>}"
APP_PACKAGE="${APP_PACKAGE:-ir.zenora.zenorachat}"
BUILD_TOOLS_VERSION="36.0.0"
AAPT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME is not set}}/build-tools/${BUILD_TOOLS_VERSION}/aapt"
APKSIGNER="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME is not set}}/build-tools/${BUILD_TOOLS_VERSION}/apksigner"

[[ -s "$APK" ]] || { echo "::error::APK is missing or empty: $APK"; exit 1; }
[[ -x "$AAPT" ]] || { echo "::error::aapt not found: $AAPT"; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "::error::apksigner not found: $APKSIGNER"; exit 1; }

EXPECTED_PACKAGE="$APP_PACKAGE"
if [[ "$MODE" == "debug" ]]; then
  EXPECTED_PACKAGE="${APP_PACKAGE}.beta"
elif [[ "$MODE" != "release" ]]; then
  echo "::error::Unknown build mode: $MODE"
  exit 1
fi

BADGING="$("$AAPT" dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -F "package: name='$EXPECTED_PACKAGE'" >/dev/null || {
  echo "::error::Unexpected APK application ID. Expected $EXPECTED_PACKAGE"
  printf '%s\n' "$BADGING" | head -n 5
  exit 1
}
printf '%s\n' "$BADGING" | grep -F "application-label:'ZenoraChat" >/dev/null || {
  echo "::error::APK display name does not appear to be ZenoraChat"
  exit 1
}

unzip -t "$APK" >/dev/null

if [[ -n "${EXPECTED_ABIS:-}" ]]; then
  EXPECTED_ABIS_SORTED="$(printf '%s' "$EXPECTED_ABIS" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | sed '/^$/d' | sort -u)"
  ACTUAL_ABIS_SORTED="$(unzip -Z1 "$APK" | awk -F/ '$1 == "lib" && NF >= 3 && $3 ~ /\.so$/ { print $2 }' | sort -u)"
  if [[ -z "$ACTUAL_ABIS_SORTED" ]]; then echo "::error::No native shared libraries were found in the APK."; exit 1; fi
  if [[ "$ACTUAL_ABIS_SORTED" != "$EXPECTED_ABIS_SORTED" ]]; then
    echo "::error::APK ABI list does not match the requested target."
    echo "Expected ABIs:"; printf '%s\n' "$EXPECTED_ABIS_SORTED"
    echo "Actual ABIs:"; printf '%s\n' "$ACTUAL_ABIS_SORTED"
    exit 1
  fi
  echo "ABIs: $(printf '%s' "$ACTUAL_ABIS_SORTED" | paste -sd, -)"
fi
"$APKSIGNER" verify --verbose "$APK"

echo "APK validation passed."
echo "Mode: $MODE"
echo "Package: $EXPECTED_PACKAGE"
echo "File: $APK"
sha256sum "$APK"
