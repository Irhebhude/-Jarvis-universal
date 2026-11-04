# Security and privacy

## Threat model

| Threat | Control |
|---|---|
| Another app on the phone calls the bridge | Random 256-bit bearer token per device; stored on the core only as SHA-256; loopback bind |
| A web page in any browser reaches the bridge (DNS rebinding / localhost fetch) | `Origin` present → 403; `Host` must be loopback; no CORS headers; `no-store` everywhere |
| Someone picks up the phone and issues commands by voice | Wake phrase + optional spoken confirmation for risky actions; owner can mute from the notification |
| Malicious/incorrect AI output | Two independent gates: argument validation on both sides, confirmation gate on the device, dangerous-request refusal in the planner |
| The AI is tricked into running shell commands | No shell tool exists; tool ids are validated against the registry; shell metacharacters rejected; and there is no code path from a plan to `Runtime.exec` |
| Secrets leaked into memory or logs | Credential patterns rejected; audit args redacted; provider keys read from env only |
| Data theft from a lost phone | Android `allowBackup=false`, no cloud backup, token in Keystore, memories AES-encrypted at rest, DB `0600` |
| Fake pairing (brute force) | 8-digit single-use codes, 300 s TTL, 5 attempts per code, 6 attempts/minute per client, codes stored hashed |
| Camera used without the owner noticing | Camera-type foreground service + mandatory notification + system privacy indicator + HUD badge; stop action everywhere; no recording, no retention |
| A plan weaponises the camera | Only `camera_start/stop/status/describe/read_text/identify` exist; images are never tool arguments; remote upload is a separate, off-by-default owner setting |
| Notification content leakage | Text is read only after exclusions, never written to disk, buffer is in-memory and bounded |

## Camera vision

The camera is an opt-in feature with visible, revocable operation. What is guaranteed:

- **Permission is requested at first use**, not at install. Denial simply leaves the camera off;
  every camera action then fails with `camera_permission_required` and says why.
- **Visible while active, three ways:** the mandatory `camera` foreground-service notification
  (title `JARVIS · REAR camera active`), Android's own camera privacy indicator, and a red
  `REAR CAM` / `FRONT CAM` badge in the holographic header. None can be hidden.
- **Immediate stop:** the notification's “Stop camera” action, the voice command “stop camera”,
  or killing the app. Stopping unbinds CameraX, releases the detector clients and clears every
  in-memory frame in the same call.
- **No recording, no retention.** No `MediaRecorder`, no video files, no image files, no database
  rows. At most **one** JPEG exists, in memory, and only while a question is being answered.
- **On-device by default.** Face, object, text and barcode detection all run through bundled ML Kit
  models on the handset. `PIPELINE: frame → ML Kit → SceneSnapshot` never leaves the phone.
- **Remote reasoning is off until enabled** (Setup step 21, `camera_remote_vision=false`). When on,
  a single still frame per question is POSTed to the loopback core, which forwards it to the
  provider the owner configured. A continuous feed is never uploaded and there is no streaming path.
- **No identity claims.** Face detection reports presence and orientation only; the app never
  stores a face template and never asserts who someone is.
- **Android limits are respected, not bypassed.** The camera starts only from a visible activity
  (foreground-service rules) and cannot run while the device is locked. Those refusals are spoken.
- **The AI cannot open the camera on its own.** Only the registered `camera_start` action can, and
  only with an enum argument (`front` / `rear` / `switch`) — there is no free-form camera control and
  no way to pass an image as a tool argument.

Verified by source review and by the JVM/pytest suites; on-device camera behaviour is **not**
claimed until a phone runs it (see `ACCEPTANCE_TESTS.md`, rows 21–38).

## Microphone

- Audio is captured only inside `WakeWordService` and only while listening.
- Wake-word inference runs **on the device** (Vosk). Wake audio is not uploaded.
- Command audio is sent either to the on-device platform recognizer or, in core mode, to the
  loopback core for local Vosk transcription. It is not stored, and it never goes to a cloud ASR
  unless the owner selects "Android recognizer" *and* their device's recognizer is cloud-based —
  which the Setup screen states plainly.
- Mute releases the microphone immediately and the notification reflects it.
- The listening notification is Android's mandatory disclosure; it cannot be hidden.

## Token lifecycle

1. Owner runs `./pair.sh` → single-use 8-digit code (hashed at rest).
2. App posts the code + device name → core issues a 43-char token, stores `sha256(token)` with the
   device record and last-seen time.
3. App stores the token in Keystore AES-256-GCM (`SecretStore`); no plaintext preference.
4. Every request is authenticated by hashing the presented token and comparing hashes.
5. `python -m jarvis_core revoke-devices` (or deleting the device row) invalidates all tokens.
6. Re-pairing issues a new token; old tokens for the same name are rejected after revocation.

If the phone is lost: revoke devices, change any provider key stored in `~/.jarvis/env`, and delete
`~/.jarvis/jarvis.db` if you do not need the memory.

## Provider keys

- Read from environment/`~/.jarvis/env` (0600) only; never written to the database, never logged,
  never returned by an endpoint, never sent to the app.
- `/status` exposes only `provider`, `model`, `configured`.
- Error strings are scrubbed of anything that looks like a key before being returned.
- Keys must be pasted into `~/.jarvis/env` on the phone, never into a chat or a repository.

## Memory and logs

- `memory.key` (Fernet) is `0600`; if it is deleted, encrypted rows become unreadable — that is the
  intended failure mode, not silent plaintext.
- Rows are marked sensitive when they match personal-data heuristics (addresses, medical, financial,
  identifiers) or when the owner marks them.
- Credentials are refused outright with HTTP 400 instead of being encrypted.
- The audit log records action ids, outcome, duration and error codes. Phone numbers, message
  bodies, file paths, URLs and search queries are redacted.
- Conversation history is bounded (configurable turns) and can be cleared from the app.

## What JARVIS will refuse

Refusals are implemented as pattern checks in the planner and as missing tools, and they are
reported to the owner, never silently dropped:

- root/su, privileged shell, remote script piping (`curl … | sh`), mass deletion
- disabling security, device admin, VPN, or the JARVIS service itself
- payments, transfers, purchases
- covert or locked-device camera use, screenshots, surreptitious recording, "record everything"
  (explicit, notified camera requests are allowed — see *Camera vision* above)
- reading or exporting credentials, OTPs, password managers
- deleting files, formatting storage, factory reset

## Android permission posture

| Permission | Requested when | Why it is not requested at launch |
|---|---|---|
| `RECORD_AUDIO` | Setup / first listen | Wake word requires it; without it the service stops with a clear message |
| `POST_NOTIFICATIONS` | Setup | Required for the mandatory foreground notification on Android 13+ |
| `READ_CONTACTS` | "find a contact" | Feature-scoped |
| `CALL_PHONE` | "call …" | Feature-scoped |
| `READ_PHONE_STATE` | call state reporting | Feature-scoped |
| `ANSWER_PHONE_CALLS` | never automatically | Only if the owner enables call answering |
| `SEND_SMS` / `READ_SMS` | "message …" | Feature-scoped, confirmation required |
| `ACCESS_NOTIFICATION_POLICY` | "do not disturb" | Special access, only used for that feature |
| `WRITE_SETTINGS` | "brightness" | Special access |
| `ACCESS_FINE_LOCATION` | "where am I" | Last known fix only, stated as possibly stale |
| `BODY_SENSORS` | "heart rate" | Only if hardware exists |
| `SYSTEM_ALERT_WINDOW` | Setup | Needed to open apps while the UI is closed (Android 10+ blocks background starts) |
| `CAMERA` | First "look" request or Setup step 20 | Camera vision; without it every camera action refuses with a spoken reason |
| `FOREGROUND_SERVICE_CAMERA` | Manifest (normal permission) | Declares the camera foreground-service type Android 14+ requires |
| Notification listener | Setup (optional) | Notification summaries; text read only after exclusions |
| Accessibility service | Setup (optional) | Global back/home/recents only — `onAccessibilityEvent` discards everything, no content flags requested |

Nothing is requested "just in case"; each grant has a visible feature and a Setup row.

## Known limitations (stated honestly)

- Any app with the token can issue commands — treat the token like a password.
- Root-level actions are impossible by design; JARVIS reports that instead of failing silently.
- Accessibility can perform global navigation only, so it cannot drive arbitrary UI.
- Notification content is visible to JARVIS once access is granted; exclusions reduce but do not
  eliminate that. Disable the listener if you do not want it.
- The core trusts the local user account: anyone with a Termux shell on the phone can read the
  database. Device encryption and screen lock are the real boundary.
