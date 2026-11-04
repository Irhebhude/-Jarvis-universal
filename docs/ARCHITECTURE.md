# Architecture

Five connected components, exactly as specified.

## 1. Native Android companion (Kotlin)

`android/app/src/main/java/dev/jarvis/universal/`

| File | Responsibility |
|---|---|
| `JarvisApp.kt` | Process singletons: settings, secrets, bridge client, action registry |
| `service/WakeWordService.kt` | Microphone foreground service; owns the whole voice pipeline |
| `service/JarvisNotifications.kt` | Listening notification (mandatory), mute/stop actions, resume prompt |
| `service/ServiceControlReceiver.kt` | Notification actions and voice-triggered control intents |
| `service/BootReceiver.kt` | Reboot recovery (tap-to-resume, Android 14 compliant) |
| `service/HealthWorker.kt` | 15-minute health check, alerts only |
| `wake/WakePhraseMatcher.kt` | Pure Kotlin phrase matching, thresholds, normalization (unit tested) |
| `wake/VoskWakeEngine.kt` | AudioRecord + grammar-constrained Vosk recognition |
| `wake/PorcupineWakeEngine.kt` | Optional reflective Porcupine engine |
| `audio/AudioIo.kt` | Acknowledgement tone, command capture with energy VAD |
| `asr/Transcription.kt` | Core transcription / Android SpeechRecognizer routing |
| `tts/JarvisTts.kt` | Female voice selection, queueing, interruption, lip-sync signal |
| `net/CoreClient.kt` | Auth HTTP client + WebSocket channel with backoff |
| `control/DeviceControlService.kt` | Validation → permission → confirmation → execute → audit |
| `control/ActionSpec.kt` | Action contract, argument schema, validator, registry |
| `control/actions/*.kt` | The 34 actions, grouped by domain |
| `control/JarvisAccessibilityService.kt` | Optional global navigation only (no content access) |
| `notify/Notifications.kt` | Notification listener + in-memory buffer with exclusions |
| `ui/MainActivity.kt` | Holographic WebView host + JS bridge |
| `ui/SetupActivity.kt` | 22-step setup, per-feature permission requests |
| `ui/MemoryActivity.kt` | View / delete / export / disable memory |
| `model/ModelInstaller.kt` | Download or import the offline wake model |
| `util/SecretStore.kt` | AES-256-GCM Keystore storage for the device token |

`Backup` is disabled and data extraction rules exclude everything.

### 1.1 Camera vision pipeline

```
VisionService (foreground, type=camera, notification + stop action)
        │  CameraX ImageAnalysis, 640×480, KEEP_ONLY_LATEST, ~2 fps
        ▼
VisionAnalyzer ── FaceDetector ── ObjectDetector ── TextRecognizer ── BarcodeScanner
        │            (all bundled ML Kit, on device; OCR every 3rd analysed frame)
        ▼
SceneSnapshot  →  SceneAnalysis (presence, facing, position, scene hint, change score)
        │
        ▼
CameraActions (camera_start/stop/status/describe/read_text/identify)
        │                         │
        │ local evidence only     └─ if camera_remote_vision is ON:
        ▼                            one JPEG → core /vision/analyze → provider
spoken answer                     (only when the configured model accepts images)
```

Design choices worth knowing:

- **CameraX, not raw Camera2.** Lens switching, rotation and interruption recovery come for free,
  and `unbindAll()` is a reliable release path on OEM quirks.
- **A foreground service with `foregroundServiceType="camera"`**, started only from a visible
  activity. Android 12+ refuses background foreground-service starts; the action returns
  `camera_background_blocked` and JARVIS says the app must be on screen.
- **Frames are never arguments.** `Intent`/`JSONObject` payloads carry measurements only; the single
  in-memory JPEG is fetched by the device when it decides to ask the core.
- **Two-lane reasoning kept intact.** The rule lane answers camera commands with no provider at all;
  the provider lane only adds detail when its model is multimodal.

## 2. Termux intelligence core (Python)

`core/jarvis_core/`

| File | Responsibility |
|---|---|
| `server.py` | FastAPI app, auth dependency, loopback + Origin guards, WS hub |
| `security.py` | Pairing codes, per-device token hashing, rate limiting |
| `db.py` | SQLite schema: devices, pair_codes, memories, conversations, plans, audit, kv |
| `memory.py` | Long-term memory, AES (Fernet) for sensitive rows, credential refusal |
| `tools.py` | Registry mirror + argument validation + confirmation policy |
| `planner.py` | Rule-based lane, LLM lane, plan persistence, response composition |
| `providers.py` | OpenAI-compatible / Gemini / Anthropic / offline adapters |
| `asr.py` | Optional Vosk transcription |
| `config.py` | Environment-driven settings, no secrets on disk |
| `__main__.py` | `serve`, `pair`, `init`, `diagnostics`, `revoke-devices` |

## 3. Wake-word engine

Chosen engine: **Vosk** with a grammar of `["hey jarvis", "jarvis", "[unk]"]`.

Why: Apache-2.0, no account, no network, ARM-friendly, and both requested phrases work out of the
box. The grammar restriction keeps CPU/RAM low and makes false positives rarer. Porcupine is
implemented as an optional accelerator but needs an account and, for “Hey JARVIS”, a custom
keyword file that we cannot generate.

Tuning knobs: sensitivity slider (0–100) → thresholds, 2.5 s cooldown, stricter bare-word match,
long-utterance guard, notification review of the last detection.

## 4. Device-control bridge (Kotlin)

Every operation is an `ActionSpec`:

```
id · description · args (type, range, enum, maxLength) · permissions · confirmation · danger · handler
```

Execution order (all enforced in code, not prompt text):

```
registered? → arguments valid? → permissions granted? → owner confirmed? → execute → structured result → audit
```

`ActionResult` carries `ok`, `spoken`, `data`, `error`, `needs_confirmation`. The core composes the
final sentence **from these results**, so success is never invented.

## 5. Holographic interface

`android/app/src/main/assets/hologram/index.html` — canvas avatar (head, hair, eyes with blinking,
jaw/lips driven by the speaking amplitude), holographic rings that react to listening/capturing/
speaking, a HUD with phase, task, detail, error, permission chips, mute and wake-word buttons, and
a typed-command input that uses the same pipeline as voice.

Kotlin → JS via `window.jarvis.setState/ setAmplitude/ setTask/ setDetail/ setCore/ setMuted/
setWake/ setError/ setStatus`. JS → Kotlin via the `JarvisNative` `@JavascriptInterface`.

## Voice pipeline (the important sequence)

```
WakeWordService (foreground, mic)
   │  wake phrase detected locally (Vosk)
   ├─ acknowledgement tone
   ├─ engine stopped  → microphone released (no self-echo, single listener)
   ├─ CommandCapture (48 kHz? no: 16 kHz mono PCM16, energy VAD, timeout)
   ├─ TranscriptionRouter
   │     CORE mode : POST /voice/transcript  (offline; 501 → platform recognizer)
   │     ANDROID   : SpeechRecognizer
   ├─ POST /assistant/command  → {plan_id, speech, actions[]}
   ├─ for each action: DeviceControlService.execute(confirmed=false)
   │     └─ needs_confirmation → speak prompt → capture "yes" → execute(confirmed=true)
   ├─ POST /assistant/execute  → final speech composed from real results
   ├─ JarvisTts.speakAndWait (female voice, queued, interruptible)
   └─ engine restarted, notification updated
```

Failure paths: core unreachable → spoken explanation + notification, wake listening resumes; core
rejects token → re-pair message; action fails → the failure text is spoken, never a success;
mic permission revoked → service stops with an actionable notification.

## State and threading

- `JarvisState` (StateFlow) is the single source of truth for the UI: phase, task, detail, mute,
  wake, core online, amplitude, last error, integrations.
- `WakeWordService` uses a `Mutex` so only one pipeline runs; the wake engine is stopped during
  capture/speech and restarted afterwards.
- Audio loops run on dedicated high-priority threads; network and TTS use coroutines.
- The core uses one SQLite connection with an `RLock` (single process, localhost traffic).

## Persistence

| Store | Location | Contents |
|---|---|---|
| Android settings | SharedPreferences `jarvis_settings` | non-secret configuration |
| Android secret | Keystore-encrypted prefs | device bridge token |
| Wake model | app `filesDir/vosk-model` | Vosk model |
| Core database | `~/.jarvis/jarvis.db` (0600) | devices, memories, conversation, plans, audit |
| Memory key | `~/.jarvis/memory.key` (0600) | Fernet key for sensitive rows |
| Provider env | `~/.jarvis/env` (0600, owner-created) | API keys — never written by JARVIS |

Notification content is **never** persisted: it lives in a bounded in-memory buffer that dies with
the process.
