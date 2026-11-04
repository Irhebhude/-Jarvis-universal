# Device compatibility

Verified nothing on hardware — this workspace has no phone. What follows is what the code
*requires* and *handles*, marked so the owner can check their own device.

## Android versions

| Version | API | State | Notes |
|---|---|---|---|
| 8.0 – 8.1 | 26–27 | Implemented, untested | minSdk. Foreground service + notification work. `POST_NOTIFICATIONS` is a no-op. |
| 9 | 28 | Implemented, untested | Cleartext loopback allowed through the network security config. |
| 10 | 29 | Implemented, untested | First version with background-activity-start restrictions → display-over-other-apps needed to open apps with the UI closed. |
| 11 | 30 | Implemented, untested | Package visibility: `<queries>` declared for launchable apps and messaging/dialer intents. |
| 12 | 31 | Implemented, untested | `BLUETOOTH_CONNECT` split out; the Bluetooth action only *reads* state and asks for the grant. Approximate location may be all you get. |
| 13 | 32 | Implemented, untested | `POST_NOTIFICATIONS` runtime prompt; notification listener needs "Allow restricted settings" when sideloaded. |
| 14 | 34 | Implemented, target | Microphone foreground services cannot start from `BOOT_COMPLETED` → tap-to-resume notification. FGS type `microphone` declared. |
| 15+ | 35+ | Not targeted | Likely works; not built or tested against it. |

## Required at runtime

| Item | Requirement |
|---|---|
| ABI | arm64-v8a or armeabi-v7a (the APK is limited to those two so the ML Kit native pipelines are not shipped four times); Vosk ships the same two |
| RAM | ~180 MB app + ~120 MB model; 2 GB devices are tight with other apps |
| Free storage | ~170 MB (APK 84 MB with bundled ML Kit + wake model ~40 MB) |
| Microphone | Required |
| Cameras | Rear and/or front camera; `CAMERA` runtime permission; ABSENT cameras fail with `camera_missing_front/rear` instead of crashing |
| Camera API | CameraX 1.3.4 over Camera2 (API 26+); ML Kit 16/17 bundled models — no Play Services download needed for detection |
| Speech engine | Any installed TTS engine; female voice depends on what the OEM ships |
| Speech recognizer | Google or an OEM recognizer for the Android-ASR fallback; not required if core Vosk transcription is installed |
| Termux | F-Droid build (Play build is restricted), Python 3.10+ |
| Termux:Boot | Optional, only for core autostart |

## Known OEM behaviour (must be verified per device)

These are the realistic failure points for "always available" on Android:

| OEM | Behaviour | Mitigation built in / to do |
|---|---|---|
| Samsung (One UI) | "Put unused apps to sleep", aggressive app killing | Add JARVIS to *Never sleeping apps*, battery → Unrestricted |
| Xiaomi / Redmi (MIUI, HyperOS) | Autostart off by default, notification and background restrictions | Enable Autostart, battery → No restrictions, lock the app in recents |
| Oppo / Realme / OnePlus (ColorOS/OxygenOS) | Background freeze, "Sleep standby optimization" | Allow background activity, disable optimization for JARVIS |
| Huawei (EMUI) | "Power-intensive prompt", protected apps | Add to Protected apps |
| Vivo (Funtouch/OriginOS) | High background power consumption restrictions | Allow high background power usage |
| Stock Android / Pixel | Reference behaviour | Battery → Unrestricted is enough |
| GrapheneOS / CalyxOS | No Google ASR; stricter background limits | Use core Vosk transcription instead of Android ASR |

The app itself cannot override these — they are OS policy. The Setup screen lists them, and the
diagnostics script prints the battery-optimisation state when `dumpsys` is readable.

## Feature prerequisites

| Feature | Requires | Honest degradation |
|---|---|---|
| Wake word | Microphone + model | Without the model: engine reports `model_missing`, notification asks you to install it, no false claims |
| "Hey JARVIS" on Porcupine | Custom `.ppn` | Built-in keyword is "jarvis" only; the engine says so |
| Local wake while screen off | OEM allows mic FGS | On hostile OEMs the service may be killed; diagnostics reports "not obviously active" |
| App launch with UI closed | Display over other apps | Refuses with `background_activity_blocked` and explains the grant |
| Calls | SIM + `CALL_PHONE` | Without a SIM: `no_sim`; with dual SIM: "Android's default SIM" unless mappable |
| Per-SIM routing | Public Telecom account mapping | Falls back and says "default SIM" — never guesses |
| SMS | `SEND_SMS` + confirmation | Drafts into the messaging app if sending is denied |
| Notification summaries | Notification listener | Feature chip shows "needs access"; no partial reads |
| DND | Notification policy access | Opens the grant screen |
| Brightness set | `WRITE_SETTINGS` | Reads value, explains the grant |
| Sensor readings | Hardware present | `sensor_unavailable` for missing sensors |
| Heart rate | `BODY_SENSORS` + sensor | Same |
| Location | Location on + `ACCESS_FINE_LOCATION` | Last known fix, flagged possibly stale |
| File save | SAF folder chosen once | Falls back to app-private storage and says where it saved |
| Accessibility navigation | Optional grant | Buttons hidden/disabled when not granted |
| In-call services | Default-dialer role | **Not implemented** — requires a full dialer; documented as external dependency |
| Shizuku/ADB | Separate privileged setup | **Not implemented** — not needed for the documented features |

## Tested in this workspace

- `assembleDebug` and `assembleRelease` with JDK 17 / Android SDK 34 / Gradle 8.7 → success.
- 40 JVM unit tests (wake matching, argument validation, camera scene logic) → pass.
- Core: 59 pytest tests + live HTTP smoke test → pass.

## Not tested here (needs the owner's phone)

Wake detection accuracy in real rooms, TTS voice availability, service survival on a specific OEM,
reboot recovery, Telecom call results, notification listener on a real notification stream,
SAF picker flows, and battery impact over a day.
