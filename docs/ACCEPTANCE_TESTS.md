# Acceptance tests

The implementation is only "done" for a row when the owner has run it on their device. Rows below
record what was verified **in this workspace** (no phone, no Termux device) and exactly how to run
the rest.

Legend: ✅ verified here · 🟡 implemented, needs the device · ⛔ blocked by an external dependency

| # | Test | Required result | Status here | How the owner verifies |
|---|---|---|---|---|
| 1 | Wake phrase “Hey JARVIS” | Detected | 🟡 matcher unit-tested | Enable listening, say the phrase, listen for the beep and “I heard you say …” (Setup step 17). |
| 2 | Wake phrase “JARVIS” | Detected | 🟡 matcher unit-tested (DUAL mode) | Setup step 5 → both phrases; repeat test 1 without “hey”. |
| 3 | Wake detection without internet | Works | 🟡 offline by design (Vosk) | Turn on aeroplane mode, run test 1. The model runs locally. |
| 4 | Interface closed | Voice service remains active | 🟡 foreground service | Press Home, watch the listening notification stay, run test 1. |
| 5 | Screen off | Tested and documented | 🟡 | Lock the phone, run test 1. Some vendors kill mic services even with a notification — see compatibility doc. |
| 6 | Spoken question | Correctly processed | 🟡 core lane integration-tested | “What is my battery level?” → real percentage spoken. |
| 7 | Female voice response | Works | 🟡 | Setup step 6 → pick a female voice → “Test current”. |
| 8 | Termux core stopped | Failure detected and reported | 🟡 | Stop the core, say a command → spoken “My intelligence core is not running…” and a notification. |
| 9 | Termux core restarted | Bridge reconnects | 🟡 WS backoff + heartbeat | Start the core again; within ~60 s (or immediately on next command) status returns to connected. |
| 10 | Android reboot | Recovery tested | 🟡 tap-to-resume by design | Reboot → notification “JARVIS is ready”, tap → listening resumes. Android 14 prohibits automatic mic start at boot. |
| 11 | Application launch | Real application opens | 🟡 needs overlay grant | Grant display-over-other-apps, close the UI, “open <app>”. Without the grant JARVIS must refuse — that refusal is the correct result. |
| 12 | Contact lookup | Authorized contacts resolve | 🟡 | Grant contacts, “find <name>”. |
| 13 | Call initiation | Actual result reported | 🟡 needs SIM | “Call <name>” → confirmation → Telecom result; JARVIS must not claim “connected”. |
| 14 | Battery reading | Real API reading | 🟡 | “Battery” → compare with the system status bar. |
| 15 | Microphone mute | Stops listening | 🟡 | Notification action “Mute” → say the wake phrase → nothing happens; notification says muted. |
| 16 | Camera permission | Requested at first use, denial handled | ✅ code · 🟡 device | `CAMERA` is requested only when you first ask JARVIS to look (or Setup step 20). Denying it leaves every camera action refusing with `camera_permission_required` and a spoken reason. |
| 17 | Unauthorized action | Rejected | ✅ core · 🟡 device | Core: unregistered tool ids and shell-like ids are rejected (tests + live HTTP). Device: `DeviceControlService` rejects unregistered ids, unknown args, missing permissions. |
| 18 | Network unavailable | Graceful fallback | ✅ core | Offline provider returns a clear message; rule-based device commands keep working (tested). Cloud AI requests fail cleanly. |
| 19 | AI provider unavailable | Clear error and recovery | ✅ core | `provider_unavailable` plan with spoken explanation; app still speaks it. |
| 20 | Local model missing | Clear, actionable error | ✅ device code path | Vosk engine reports `model_missing` and posts “Wake word needs its model”. |

| 21 | Front camera initialisation | Camera opens, notification names the lens | 🟡 device | Say “activate the front camera” → camera FGS notification `FRONT camera active`, HUD badge `FRONT CAM`, system privacy dot. |
| 22 | Rear camera initialisation | Same for the rear lens | 🟡 device | “activate the rear camera”; point at a desk and check for object labels in the spoken status. |
| 23 | Camera switching | Lens swaps without a conflict | 🟡 device | “switch camera” twice; only one lens is ever bound. |
| 24 | Face detection | Face present flag is real | 🟡 device | Front camera, look at the phone → “camera status” reports someone in view. Look away → presence unchanged, facing becomes “no”. |
| 25 | Face position estimation | Reported region matches reality | ✅ logic · 🟡 device | JVM tests cover the nine regions; on device, move to the frame edges and compare the spoken position. |
| 26 | User presence detection | Absent when nobody is there | 🟡 device | Point the front camera at an empty room → “I do not see anyone in front of the camera.” |
| 27 | Object detection | Labels match visible objects | 🟡 device | “what am I looking at” with a laptop/cup in view → labels in the answer. |
| 28 | Object identification | Answer grounded in detections | 🟡 device | “identify this” over a bottle; must not name something absent. |
| 29 | OCR | Text read from a real document | 🟡 device | “read this” over printed text; compare with the page. No text → honest refusal. |
| 30 | Scene understanding | Hint, not a classifier | ✅ logic · 🟡 device | Desk labels → “a desk or workspace”; vehicle labels → street hint. |
| 31 | Visual question answering | Provider only when multimodal | ✅ core (stub provider) · 🟡 device | pytest covers provider/fallback paths; on device enable step 21 and compare answers with and without a vision model. |
| 32 | Voice-triggered activation | Planner maps the phrase | ✅ core rule tests · 🟡 device | “Hey JARVIS, activate rear camera” → `camera_start(mode=rear)`. |
| 33 | Voice-triggered shutdown | Camera stops immediately | ✅ core rule tests · 🟡 device | “stop camera” → notification disappears, badge clears, no further frames. |
| 34 | Permission denial | Clear, actionable refusal | 🟡 device | Revoke camera in system settings, say “activate the camera” → refusal plus a pointer to setup. |
| 35 | Interruption recovery | Camera released, then recoverable | 🟡 device | Take a call / open another camera app → session stops with a message; ask again to resume. |
| 36 | Battery and memory | No runaway analysis | 🟡 device | Battery stats over 10 minutes of front-camera listening; analysis is capped at 2 fps. |
| 37 | Privacy controls | Every stop path works | 🟡 device | Notification “Stop camera”, voice stop, task swipe — all release the camera and clear frames. |
| 38 | Real AI vision integration | End-to-end with the owner's key | 🟡 blocked by provider key | Needs a multimodal model key; without one the answer stays local and says so. |

## Automated checks that already ran

**Android (JVM, no device):**

```
40 tests completed — PASS
  WakePhraseMatcherTest   exact / near-miss / rejection / thresholds / normalization
  ArgValidatorTest        unknown args, ranges, enums, duplicates, shell-ish rejection
  SceneAnalysisTest       presence, facing thresholds, position, scene hints, change score,
                          evidence payload, cadence throttling, lens parsing
assembleDebug   → app-debug.apk (84 MB, ML Kit bundles + ARM libs only)
assembleRelease → app-release-unsigned.apk (83 MB)
```

**Core (pytest):**

```
59 passed
  test_security.py  pairing single-use/expiry/attempts, token hashing, revoke, rate limiter
  test_memory.py    CRUD, disabled mode, secret refusal, encryption, bounded history, export
  test_vision.py    provider vision capability inspection, fact grounding, fallback,
                    endpoint auth/size/limit paths
  test_planner.py   rule intents, confirmations, dangerous refusal, LLM JSON validation,
                    compose-never-invents-success, plan persistence
  test_server.py    auth 401, origin 403, pairing flow, tools catalog, command+execute,
                    memory API, secret rejection, 501 transcription, audit, pairing rate limit
```

**Live bridge smoke test (real HTTP, real SQLite):**

```
health                     200 {"status":"ok","version":"1.0.0"}
pairing code → /pair       200, 43-char device token
/status with token         200 (provider=offline, memory encryption=true)
/status without token      401
/status with Origin header 403
"what is my battery level" → plan_id + battery_status action
/assistant/execute         {"reply":"completed","speech":"Battery is at 81 percent."}
"call my brother"          call_contact with needs_confirmation=true
"run sudo rm -rf /"        error=refused_dangerous_request, 0 actions
memory add of sk-live-…    400 "refusing to store something that looks like a credential"
/voice/transcript          501 when no local speech model
```

**Scripts:** `termux-install.sh` (fresh + idempotent re-run), `termux-update.sh` (with DB backup),
`termux-diagnostics.sh` (text + `--json`), `termux-uninstall.sh` syntax, `bash -n` on all scripts.

## What remains genuinely untestable here

1. Real microphone/wake detection, TTS voice output, lip sync.
2. Android service lifecycle under OEM battery managers.
3. Telecom/contacts/SMS behaviour, per-SIM mapping, notification listener content.
4. Reboot recovery and background-start behaviour on the owner's Android version.
5. Any AI provider call (no key is present in this workspace, and keys must never be pasted in chat).

Report those as *pending device verification* until the owner runs them.
