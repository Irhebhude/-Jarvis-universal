# Build, test and release

## Toolchain actually used for the verified builds

| Tool | Version |
|---|---|
| JDK | Temurin 17 |
| Gradle | 8.7 (wrapper committed) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| Build tools | 34.0.0 |
| Python (core) | 3.10+ (3.12 used here) |

## Prerequisites

1. JDK 17 (`JAVA_HOME` must point at it; JDK 21 also works with AGP 8.5, JDK 11 does not).
2. Android SDK with platform 34, build-tools 34.0.0, platform-tools.
3. `android/local.properties` containing `sdk.dir=/path/to/Android/Sdk`
   (or set `ANDROID_HOME`).

## Debug build

```bash
cd android
./gradlew assembleDebug
# android/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A prebuilt copy lives at `artifacts/jarvis-universal-debug.apk` (42,077,423 bytes).

## Unit tests

```bash
cd android && ./gradlew testDebugUnitTest
# report: app/build/reports/tests/testDebugUnitTest/index.html
```

40 tests: `SceneAnalysisTest` (camera scene logic), `WakePhraseMatcherTest` (exact phrase, near-miss rejection, sensitivity thresholds,
normalisation, cooldown) and `ArgValidatorTest` (unknown args, ranges, enums, length limits,
duplicate detection, shell-metacharacter rejection). They are plain JVM tests — no device, no
Robolectric, no `org.json` stubs.

## Release build

```bash
cd android
./gradlew assembleRelease
# android/app/build/outputs/apk/release/app-release-unsigned.apk (83 MB)
```

### Signing (required to install a release APK)

Create `android/keystore.properties` (gitignored) and keep the keystore **outside** the repo:

```properties
storeFile=/absolute/path/to/jarvis-release.jks
storePassword=...
keyAlias=jarvis
keyPassword=...
```

```bash
keytool -genkeypair -v -keystore ~/jarvis-release.jks -alias jarvis \
        -keyalg RSA -keysize 4096 -validity 10000
cd android && ./gradlew assembleRelease
```

`app/build.gradle.kts` picks the signing config up automatically when the file exists; without it
the release APK stays unsigned (that is the verified state here) and will not install.

Verify the signature before distributing:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app-release.apk
```

## Core build and tests

```bash
cd core
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q          # 59 passed
.venv/bin/python -m jarvis_core diagnostics
```

Note: `pytest` may emit a `StarletteDeprecationWarning` about `httpx` — harmless, from the test
client, not the app.

## Packaging the core for a phone

There is nothing to compile. Copy the repository (or `core/`) to the phone and run
`scripts/termux-install.sh`, which builds the virtualenv on-device. Do **not** copy a desktop
`.venv`: it contains host binaries.

## Build troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Unsupported class file major version` | Wrong JDK | `export JAVA_HOME=/path/to/jdk17` |
| `SDK location not found` | Missing `local.properties` | Add `sdk.dir=...` |
| `Failed to install the following SDK components` | Missing platform 34 | `sdkmanager "platforms;android-34" "build-tools;34.0.0"` |
| `2 files found with path 'META-INF/...'` | Duplicate packaging | Already handled via `packaging { resources { excludes += ... } }` |
| `v1/v2 signing not found` | Unsigned release | Add `keystore.properties` |
| Gradle can't download | Offline / proxy | Use the committed wrapper with a populated `~/.gradle` cache, or a proxy via `gradle.properties` |

## Reproducing the verified state

```bash
# app
cd android && ./gradlew clean testDebugUnitTest assembleDebug assembleRelease

# core
cd core && .venv/bin/python -m pytest -q

# scripts
bash -n scripts/*.sh
JARVIS_INSTALL_DIR=/tmp/jarvis-install JARVIS_DATA_DIR=/tmp/jarvis-data bash scripts/termux-install.sh
JARVIS_INSTALL_DIR=/tmp/jarvis-install JARVIS_DATA_DIR=/tmp/jarvis-data bash scripts/termux-update.sh
JARVIS_INSTALL_DIR=/tmp/jarvis-install JARVIS_DATA_DIR=/tmp/jarvis-data bash scripts/termux-diagnostics.sh
```

These commands are what produced the results in `docs/ACCEPTANCE_TESTS.md`; nothing beyond them was
run, and no on-device test was performed.
