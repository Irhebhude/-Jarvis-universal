# Feature matrix and honest status

Statuses used exactly as required:

- **Designed** — specified, not written.
- **Implemented** — code exists and compiles/imports.
- **Locally tested** — automated tests or a build ran in this workspace.
- **Integration tested** — components exercised together over real interfaces.
- **End-to-end tested** — exercised on a real Android device, by the owner.
- **Production deployed** — installed and used as the owner's daily assistant.
- **Blocked by external dependency** — needs hardware, an account, a role, or a paid service.

This workspace has **no Android device and no Termux device**. Everything that needs one is marked
*Implemented* or *Integration tested* (core side) and never claimed as device-verified.

## 1. Always-available voice

| Requirement | Status | Evidence / note |
|---|---|---|
| Wake detection while UI closed | Implemented | `WakeWordService` is a microphone foreground service; `MainActivity` is independent. Not device-tested. |
| “Hey JARVIS” | Implemented, locally tested (matcher) | 40 JVM tests cover exact, near-miss and rejection cases plus camera scene logic. Vosk grammar includes the phrase. |
| “JARVIS” alone | Implemented, locally tested (matcher) | Stricter threshold; only allowed in DUAL mode. |
| Offline detection, no audio upload | Implemented | Vosk runs locally; wake audio never leaves the device. |
| Dual sensitivity control | Implemented | Slider 0–100 → thresholds; `WakePhraseMatcher.thresholdsForSensitivity`. |
| False-activation handling | Implemented | Cooldown 2.5 s, stricter bare-word match, grammar restriction, review flag in notification. |
| Single microphone listener | Implemented | Engine singleton guarded; engine is stopped before capture/TTS. |
| Immediate mute / privacy control | Implemented | Notification action + hologram button + `set_microphone_muted`. |
| Acknowledgement sound | Implemented | `AckTone` (ToneGenerator beep). |
| Listening / command timeouts | Implemented | Configurable (`listeningTimeoutMs`, `commandTimeoutMs`). |
| Battery-saving mode | Implemented | Reduces heartbeat frequency; wake engine stays lightweight. |
| Service health + recovery | Implemented | Heartbeat, WorkManager check, restart of engine if it died, tap-to-resume after reboot. |
| Survives reboot automatically | **Blocked by external dependency** | Android 14 forbids microphone FGS start from BOOT_COMPLETED. A tap-to-resume notification is the lawful alternative. |

## 2. Wake-word engine

| Item | Status | Note |
|---|---|---|
| Vosk (chosen default) | Implemented | Apache-2.0, no account, grammar-constrained; model installed on demand (~40 MB). |
| Picovoice Porcupine (evaluated) | Implemented (optional, reflective) | Not bundled. “JARVIS” is built in; “Hey JARVIS” needs a custom `.ppn` from a Picovoice account. **Blocked by external dependency**. |
| Model install | Implemented | Download with consent, zip import via SAF, zip-slip protected, validated after extraction. |
| Model download requires network | Blocked by external dependency | One-time download from alphacephei.com (or side-load the zip). |

## 3. Speech recognition and voice

| Item | Status | Note |
|---|---|---|
| Android SpeechRecognizer | Implemented | Selectable mode and automatic fallback when the core has no local model. May use network depending on the device's recognizer. |
| Vosk local transcription | Implemented (core, optional) | Needs `vosk` + model in `~/.jarvis/vosk-model`; otherwise `/voice/transcript` returns 501 and the app falls back. |
| Whisper | Not implemented | Would need a local runtime or a remote API; no vendor assumed. |
| Female voice | Implemented | Picks an installed female voice by name/locale; otherwise pitch-shifted fallback with an explicit “fallback” label in Setup. |
| Interruptible, queued speech | Implemented | `QUEUE_FLUSH`, `speakAndWait`, stop on shutdown. |
| Lip sync | Implemented | Word-range callbacks + synthesized envelope driving the avatar (labelled as visualisation, not audio analysis). |

## 4. Device control (34 registered actions)

All actions carry an id, description, argument schema, permission requirements, confirmation
policy and structured result. Unknown ids and unknown arguments are rejected.

| Capability | Status |
|---|---|
| Launch/list apps | Implemented (needs display-over-other-apps for background launch) |
| Battery, network, device info | Implemented (real platform APIs) |
| Volume get/set | Implemented |
| DND status/set | Implemented (needs DND access) |
| Brightness get/set | Implemented (set needs “modify system settings”) |
| Bluetooth status | Implemented (read-only; toggling needs the system screen) |
| Wi-Fi toggle | Not possible; opens Wi-Fi settings instead (honest refusal) |
| Sensors (light, proximity, accel, gyro, magnetometer, pressure, steps, humidity) | Implemented (only if the hardware exposes them) |
| Location | Implemented (last known fix only; marked as possibly stale) |
| Contacts search | Implemented |
| Call a contact | Implemented via Telecom; SIM routing only when mappable; connection state reported honestly |
| InCallService / CallScreeningService / CallRedirectionService | **Not implemented** — requires default-dialer role and a full in-call UI; listed as external dependency |
| SMS draft (opens messaging app) | Implemented |
| SMS send | Implemented (needs SEND_SMS + confirmation) |
| Notifications summary | Implemented (needs notification access; exclusions honoured before any text is read) |
| Files search / read | Implemented (MediaStore + content URIs) |
| Files create note | Implemented (SAF folder, never overwrites, never deletes) |
| Files delete/move | Not implemented on purpose (safety) |
| Settings screens | Implemented (12 screens) |
| Accessibility navigation (back/home/recents) | Implemented, optional, **no screen content access** |
| Shizuku / ADB / root | Not implemented — would require separate privileged setup; not required for normal use |
| Termux:API | Not used (not needed: the native app reads device state directly) |

## 5. Intelligence core

| Requirement | Status | Evidence |
|---|---|---|
| Local authenticated API on 127.0.0.1 | Implemented, integration tested | Live pairing → command → execute over HTTP in this workspace |
| Token protection | Implemented, tested | Per-device token, stored hashed; 401 tests; browser Origin refused (403) |
| Endpoints `/health`, `/status`, `/voice/transcript`, `/assistant/command`, `/assistant/execute`, `/memory/status`, `/integrations/status`, `/device/event` | Implemented, integration tested | see [API.md](API.md) |
| WebSocket channel | Implemented | `/ws/device`, bearer auth, reconnect with backoff |
| No arbitrary shell execution | Implemented, tested | No shell tool exists; `validate_call` rejects shell-like ids/args; dangerous commands refused |
| AI provider agnostic | Implemented | OpenAI-compatible, Gemini, Anthropic, offline fallback; keys only from env |
| Rule-based offline command lane | Implemented, tested | Battery, calls, apps, volume, mute, notifications, files, settings… |
| Memory + conversation | Implemented, tested | Add/list/delete/clear/export, bounded history, sensitive rows AES-encrypted |
| Refuses credentials in memory | Implemented, tested | `sk-…`, `sk-live-…`, `sk-proj-…`, `AIza…`, `ghp_…`, bearer tokens, private keys |
| Action authorization | Implemented | Two gates: core marks risky tools, device refuses without `confirmed=true` from the owner |
| Audit log | Implemented, tested | Every command/plan/action/rejection; args redacted for numbers/bodies/URLs |
| Rate limiting | Implemented, tested | Per-token sliding window + stricter pairing limit |

## 6. Interface

| Requirement | Status |
|---|---|
| Female holographic command centre | Implemented (canvas avatar, HUD, state rings, lip sync) |
| Close UI, keep listening | Implemented (`stopWithTask=false`, separate service) |
| Reopen by voice / launcher | Implemented (`open_jarvis_ui` + launcher icon) |
| Show listening/processing/speaking | Implemented (phase flow → WebView) |
| Show task, status, integrations, permission problems | Implemented |
| Avatar not required for voice | Implemented (avatar is a view of state only) |

## 7. External accounts, permissions and paid services

| Item | Type | Needed for |
|---|---|---|
| AI provider key (OpenAI / Groq / OpenRouter / Gemini / Anthropic / local server) | Account, possibly paid | Free-form conversation and multi-step planning. **Not needed** for rule-based device commands. |
| Picovoice access key | Free-tier account | Porcupine engine only |
| Custom “Hey JARVIS” `.ppn` | Picovoice Console | Porcupine with both phrases |
| Vosk model download | Free download (~40 MB) | Offline wake word and offline transcription |
| Termux + Termux:Boot (F-Droid) | Free apps | Core runtime, optional autostart |
| Microphone, notifications | Runtime permissions | Always-on voice |
| Contacts, phone, SMS | Runtime permissions | Contact resolution, calls, messages |
| Display over other apps | Special grant | Background app launching / bringing UI forward |
| Notification access | Special grant | Notification summaries |
| Accessibility | Special grant (optional) | Back/home/recents only |
| Do Not Disturb access | Special grant | DND changes |
| Modify system settings | Special grant | Brightness changes |
| Battery optimisation exemption | Special grant | Reliable background listening |
| SIM / telephony hardware | Hardware | Calls, SIM listing, SMS |
| Hardware sensors | Hardware | Sensor readings |
| Default-dialer role | System role | In-call service features — **not implemented** |

## 8. Camera vision (front + rear)

| Requirement | Status | Evidence / note |
|---|---|---|
| Front camera initialisation | Implemented | `VisionService` + `CameraSession` bind `LENS_FACING_FRONT`; not device-tested |
| Rear camera initialisation | Implemented | Same path with `LENS_FACING_BACK` |
| Camera switching | Implemented, locally tested (logic) | `camera_start(mode=switch)` resolves the other lens; `Lens.parse`/`Lens.other` covered by JVM tests |
| Face detection | Implemented | ML Kit `FaceDetector`, bundled model, on device |
| Face position estimation | Implemented, locally tested | `SceneAnalysis.position` nine-region mapping (JVM tests) |
| Head orientation / facing camera | Implemented, locally tested | yaw ≤30°, pitch ≤24° → “yes”; otherwise “no” (JVM tests) |
| User presence detection | Implemented, locally tested | `SceneAnalysis.presence`: present / absent / unknown |
| Visual attention tracking | Implemented (per snapshot) | eye-open probability + yaw/pitch per frame; no persistent tracking store |
| User identification | **Not implemented** | Deliberate: no face templates, no identity claims. Requires a separate biometric setup that the owner has not authorised |
| Object detection + classification | Implemented | ML Kit `ObjectDetector` (stream mode, multiple objects) |
| OCR / document / screen text | Implemented | ML Kit `TextRecognizer`, every 3rd analysed frame |
| Barcode / QR | Implemented | ML Kit `BarcodeScanner` |
| Scene understanding | Implemented (coarse, honest) | `SceneAnalysis.sceneHint` maps detected labels to a hint; not presented as a classifier |
| Visual question answering | Integration tested (core) / Implemented (device) | `VisionEngine.analyze` + `/vision/analyze` covered by pytest with stub providers; on-device path not device-tested |
| Object identification command | Implemented | `camera_identify` → labels + optional VQA |
| Activity recognition (visual) | **Blocked** | Would need a dedicated activity model; JARVIS reports object/scene hints only and does not claim activity inference |
| Visual change detection | Implemented, locally tested | Label-set Jaccard + OCR delta (`SceneAnalysis.changeScore`, JVM tests) |
| Voice command integration | Implemented, locally tested (planner) | Planner rules map “activate the rear camera”, “what do you see”, “read this”, “switch camera”, “stop camera” to camera tools; pytest covers rules |
| Camera permission handling | Implemented | Runtime request at first use + Setup step 20; denial → spoken `camera_permission_required` |
| Camera unavailable / no lens | Implemented | `hasCamera(selector)` check → `camera_missing_front/rear` |
| Interruption recovery | Implemented | CameraX unbind/rebind; error callback stops the service and clears frames |
| Conflicting camera sessions | Implemented | Single `CameraSession`; `unbindAll()` before every bind |
| Battery / memory optimisation | Implemented | 2 fps analysis (adjustable), 640×480, KEEP_ONLY_LATEST, OCR every 3rd frame, detectors closed on stop |
| Rotation / orientation | Implemented | `ImageProxy.rotationDegrees` applied to still encodes; ML Kit receives rotation |
| Visible camera indicator | Implemented | Notification title names the lens; HUD badge `REAR CAM`/`FRONT CAM`; system privacy dot |
| Privacy switch / immediate shutdown | Implemented | Notification “Stop camera”, voice “stop camera”, `camera_stop` action |
| No recording / no retention | Implemented | No `MediaRecorder`, no file or DB writes; ≤1 in-memory JPEG |
| Remote processing disclosure | Implemented | Off by default; Setup step 21 states that one frame per question leaves the phone when enabled |
| Background / locked-device camera | **Blocked by Android** | Camera only from a visible activity; locked-device and background starts are refused and reported |
