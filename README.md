# ZenoraChat APK

A reproducible GitHub Actions build pipeline for the Android client derived from the official Telegram Android source.

## What it does

- Imports the official `TelegramMessenger/Telegram-Android` source into `main`.
- Pins the exact upstream commit in `UPSTREAM_COMMIT.txt`.
- Initializes and checks out pinned Git submodules recursively for builds.
- Builds a fast **ARM64 Debug APK** by default on source updates and pull requests.
- Can build a full four-ABI **Universal Debug APK** on demand.
- Builds a signed universal **Release APK** on demand and publishes it to GitHub Releases.
- Rebrands the app at build time as ZenoraChat, including a non-Telegram launcher icon.
- Validates APK structure, package ID, version metadata and signing before publishing.

## Workflows

| Workflow | Trigger | Output |
|---|---|---|
| Sync Telegram source | Initial setup, daily schedule, manual | Source commit in `main` initially; later updates as a PR |
| Build Debug APK | Source changes in `main`, PRs and manual | Fast ARM64 by default; Universal is selectable manually |
| Release APK | Manual dispatch or `zenora-v*` tag | Signed APK attached to GitHub Releases |

The Debug workflow uses **Fast ARM64** for pushes, pull requests and the default manual run. It compiles native code for `arm64-v8a` only, avoiding builds for three unused architectures during routine testing. To build all architectures, open **Actions → Build Debug APK → Run workflow** and choose `universal`. The Universal APK includes `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`; `fast` produces ARM64 only. The validator checks native ABI directories inside the APK before upload.

The Debug artifact is not a public Release. This avoids presenting a test-signed build as a production download.

## First run

1. Open **Actions** and enable workflows for this repository if GitHub asks.
2. The first source-sync run imports the latest `master` tree and its pinned submodule references into `main`.
3. After sync completes, the Debug workflow creates a Fast ARM64 APK. Download it from the workflow run's **Artifacts** section.
4. Configure the repository variable and secrets listed below before publishing a public Release.

## Repository variable

| Name | Example | Purpose |
|---|---|---|
| `ZENORA_APPLICATION_ID` | `ir.zenora.zenorachat` | Stable Android application ID. Choose the final value before the first public release. |

The default fallback is `ir.zenora.zenorachat`. Verify that this package ID is available and owned by your project before publishing.

## Required GitHub Actions secrets for Release

| Secret | Content |
|---|---|
| `TELEGRAM_API_ID` | API ID issued for your own Telegram application |
| `TELEGRAM_API_HASH` | API hash issued for your own Telegram application |
| `ZENORA_RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore (single line) |
| `ZENORA_RELEASE_STORE_PASSWORD` | Keystore password |
| `ZENORA_RELEASE_KEY_ALIAS` | Signing key alias |
| `ZENORA_RELEASE_KEY_PASSWORD` | Signing key password |
| `ZENORA_FIREBASE_JSON_BASE64` | Base64-encoded Firebase `google-services.json` |

The Firebase project must contain Android apps for both:
- `ZENORA_APPLICATION_ID`
- `ZENORA_APPLICATION_ID.beta`

The second ID is used by the Debug variant. The Release workflow refuses to publish when required production credentials are missing.

For a Debug APK that can actually connect to Telegram, also configure both `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. These two secrets are optional for Debug compilation, but when omitted the build uses dummy placeholder credentials: the APK can be built and installed, but Telegram login/API requests will not work. The script deliberately never embeds Telegram's official app credentials in an unofficial fork.

Optional secret:
- `ZENORA_GOOGLE_MAPS_API_KEY`: your own Google Maps Android API key. Without it, the build removes the upstream Maps key and map tiles may not work.

### Create secret values

Keystore as Base64:

```bash
base64 -w 0 zenora-release.jks
```

Firebase JSON as Base64:

```bash
base64 -w 0 google-services.json
```

Do not commit keystores, passwords, API hashes or Firebase configuration to Git.

## Build performance

- Gradle uses four workers on the standard four-vCPU GitHub-hosted runner.
- Gradle build caching is enabled, while the Gradle setup action persists reusable Gradle caches between runs.
- ABI filters are applied in both the app module and Telegram native library module, so ARM64 mode avoids compiling other architectures instead of merely excluding them at packaging time.
- Debug artifacts include Gradle profiling and GNU `time` resource diagnostics.
- Gradle Configuration Cache is not forced until compatibility with the upstream custom plugins is verified.

## Local build commands

Requires JDK 17, Android SDK 36, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.

Fast ARM64 Debug:

```bash
python3 scripts/configure-fork.py --mode debug --application-id ir.zenora.zenorachat
./gradlew :TMessagesProj_App:assembleAfatDebug --build-cache --parallel --max-workers=4 -PZENORA_ABIS=arm64-v8a
```

Universal Debug:

```bash
./gradlew :TMessagesProj_App:assembleAfatDebug --build-cache --parallel --max-workers=4 -PZENORA_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64
```

Release (after provisioning your own credentials and keystore):

```bash
python3 scripts/configure-fork.py --mode release --application-id ir.zenora.zenorachat
./gradlew :TMessagesProj_App:assembleAfatRelease --build-cache --parallel --max-workers=4 -PZENORA_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64
```

## Important

- This is an **unofficial third-party client**, not the official Telegram application.
- Obtain and use your own API ID/API hash and configure your own Firebase project.
- The source is licensed under GPL-2.0-or-later. Preserve the upstream license and comply with the source and dependency licenses when distributing binaries.
- Release signing keys must remain stable. Losing the private key prevents in-place upgrades of already installed APKs.
- Upstream changes are proposed through a pull request after the initial import, so local Zenora changes are not silently overwritten.
