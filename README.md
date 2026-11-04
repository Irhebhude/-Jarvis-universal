# JARVIS UNIVERSAL — Android device-resident assistant

A real Android integration, not a web app pretending to have OS privileges. Two native halves and
a local bridge:

```
┌──────────────────────────── Android phone ────────────────────────────┐
│                                                                        │
│  ┌───────────────────────────┐        ┌─────────────────────────────┐  │
│  │ JARVIS UNIVERSAL (Kotlin) │        │ Termux intelligence core    │  │
│  │                           │        │ (Python, 127.0.0.1:8765)    │  │
│  │ • WakeWordService         │◄──────►│ • AI orchestration          │  │
│  │   microphone foreground   │ HTTP + │ • intent + planning         │  │
│  │   service, stays alive    │  WS    │ • memory (SQLite, AES)      │  │
│  │ • offline Vosk wake word  │        │ • tool validation           │  │
│  │ • command capture         │        │ • audit + rate limiting     │  │
│  │ • DeviceControlService    │        │ • local Vosk transcription  │  │
│  │   34 registered actions   │        │   (optional)                │  │
│  │ • female TTS + lip sync   │        └─────────────────────────────┘  │
│  │ • holographic WebView UI  │                                         │
│  └───────────────────────────┘                                         │
└────────────────────────────────────────────────────────────────────────┘
```

- **Wake phrases:** “Hey JARVIS” and “JARVIS”, detected **offline** (Vosk, grammar-constrained).
- **Always available:** works with the holographic interface closed, via an Android microphone
  foreground service and its mandatory notification.
- **Voice:** command capture → transcription → plan → **authorization** → execution → verification
  → spoken reply in a female voice.
- **Camera:** never requested, never declared, no camera code exists.
- **No shell:** the AI can only invoke registered, argument-validated, permission-checked actions.

### The interface

Rendered from `android/app/src/main/assets/hologram/index.html` (the app's actual WebView asset),
with live state injected through the same `window.jarvis.*` bridge the Kotlin layer uses:

| Listening | Speaking |
|---|---|
| ![listening](artifacts/hologram-listening.png) | ![speaking](artifacts/hologram-speaking.png) |

Status summary (details in [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) and
[docs/ACCEPTANCE_TESTS.md](docs/ACCEPTANCE_TESTS.md)):

| Area | State |
|---|---|
| Android app compiles, debug + release APKs build | verified in this workspace |
| Wake-phrase matching + action validation (JVM unit tests) | 18 tests passing |
| Termux core (pytest) | 41 tests passing |
| Core ↔ HTTP bridge (pairing, plan, execute, auth, refusals) | integration tested over real HTTP |
| Scripts (install / update / diagnostics / uninstall) | executed here against a real install |
| On-device wake detection, calls, TTS, service survival | **implemented, not device-tested** — no phone in this environment |

## Layout

| Path | What it is |
|---|---|
| `android/` | Kotlin app (Gradle, minSdk 26, targetSdk 34) |
| `core/` | Python intelligence core (FastAPI + SQLite) |
| `scripts/` | Termux install / update / pair / boot / diagnostics / uninstall |
| `docs/` | architecture, bridge API, security, compatibility, matrix, acceptance tests, setup, build |
| `artifacts/` | built debug APK |

## Quick start

**1. Core on the phone (Termux, F-Droid build)**

```bash
pkg update && pkg install python git
git clone <this-repo> ~/jarvis-universal
cd ~/jarvis-universal && ./scripts/termux-install.sh
# optional: export a provider key in ~/.jarvis/env (see docs/SETUP.md)
~/jarvis-core/start.sh
```

**2. App**

```bash
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or install `artifacts/jarvis-universal-debug.apk`.

**3. Pair and enable**

Open the app → **Setup** → grant microphone and notifications → install the offline wake model
(step 4) → pair with the code from `./pair.sh` (step 8) → grant “Display over other apps” if you
want JARVIS to open apps while its UI is closed → run the wake-word test (step 17).

Full walkthrough: [docs/SETUP.md](docs/SETUP.md).

## Build and test

```bash
# Android: unit tests + APK
cd android && ./gradlew testDebugUnitTest assembleDebug

# Core: tests
cd core && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

Requires JDK 17 and Android SDK 34 for the app; Python 3.10+ for the core.

## What it cannot do (by design or by Android)

- No arbitrary shell, root, ADB, or package installation.
- No camera access at all.
- No interception of ordinary cellular call audio; calls report exactly what Telecom accepts.
- No silent SMS, no silent payments, no disabling of device security.
- “Open an app while JARVIS' UI is closed” needs the display-over-other-apps grant, because
  Android 10+ blocks background activity starts. Without it the action refuses and says why.
- Per-SIM call routing is only used when Android lets a SIM be mapped to a Telecom account.

Every refused or failed operation is reported as such; the assistant never claims an action
succeeded unless the device returned success.

## Licence / third-party components

Vosk (Apache-2.0) is bundled for offline recognition. Picovoice Porcupine is **optional** and not
bundled; using it needs a Picovoice account and, for “Hey JARVIS”, a custom keyword file.
