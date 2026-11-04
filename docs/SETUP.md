# Setup

Time: about 15 minutes, plus a ~40 MB model download. You need the phone, a USB cable (or the APK
transferred another way), and a Wi-Fi connection for the model.

Nothing here requires root, ADB-only privileges, a cloud account for the wake word, or a paid
service. The AI provider is optional.

---

## Part A — install the intelligence core (Termux)

1. Install **Termux** and **Termux:Boot** from **F-Droid** (the Play Store build is restricted and
   its Python packages are unreliable).
2. Open Termux:

```bash
pkg update -y && pkg upgrade -y
pkg install -y python git
git clone <your-repo-url> ~/jarvis-universal
cd ~/jarvis-universal
./scripts/termux-install.sh
```

The installer creates `~/jarvis-core` (code + virtualenv), `~/.jarvis` (database, key, env), and
`start.sh` / `pair.sh`. It is safe to re-run.

3. **Optional but recommended — offline transcription in the core** (so commands work without
   Google's recognizer):

```bash
pkg install -y python-numpy
cd ~/jarvis-core && .venv/bin/pip install vosk
mkdir -p ~/.jarvis/vosk-model
# download vosk-model-small-en-us-0.15.zip from alphacephei.com, unzip so that
# ~/.jarvis/vosk-model/am/ and ~/.jarvis/vosk-model/conf/ exist
```

4. **Optional — connect an AI provider.** Edit `~/.jarvis/env` (already `0600`, created by the
   installer) and set one of:

```bash
# OpenAI-compatible (OpenAI, Groq, OpenRouter, vLLM, LM Studio, Ollama's /v1, …)
JARVIS_PROVIDER=openai
JARVIS_BASE_URL=https://api.openai.com/v1
JARVIS_MODEL=gpt-4o-mini
JARVIS_API_KEY=...            # your own key; never commit or paste it into a chat

# Gemini
JARVIS_PROVIDER=gemini
JARVIS_MODEL=gemini-1.5-flash
JARVIS_API_KEY=...

# Anthropic
JARVIS_PROVIDER=anthropic
JARVIS_MODEL=claude-3-5-sonnet-latest
JARVIS_API_KEY=...
```

Without a key the core runs in **offline rule-based mode**: battery, apps, volume, calls, SMS,
notifications, files and settings still work. Only free-form conversation and multi-step reasoning
need a provider.

5. Start the core:

```bash
~/jarvis-core/start.sh
```

Leave it running (Termux keeps it alive; add a wake lock if your ROM is aggressive:
`termux-wake-lock`). Verify from another Termux session:

```bash
curl -s http://127.0.0.1:8765/health
```

6. **Optional autostart at boot:** `touch ~/.jarvis/autostart` and, once,
   `mkdir -p ~/.termux/boot && cp ~/jarvis-core/scripts/termux-boot.sh ~/.termux/boot/jarvis-core.sh`
   (or re-run the installer with `--boot`). Android still forbids the *microphone service* from
   starting at boot; the app posts a tap-to-resume notification instead.

---

## Part B — install the app

```bash
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No cable? Copy `artifacts/jarvis-universal-debug.apk` to the phone and open it (allow install from
this source). Android 14 will require **Allow restricted settings** before the notification listener
or accessibility service can be enabled for a sideloaded app.

---

## Part C — the 20 setup steps (in-app **Setup** tab)

Open JARVIS UNIVERSAL → **Setup**. Each row shows live status and a button. Do them in order.

| Step | What to do | Expected result |
|---|---|---|
| 1 | Check the reported Android version and ABI | ✓ with your model name |
| 2 | **Microphone** → Allow | ✓ Required for the wake word |
| 3 | **Notifications** → Allow (Android 13+) | ✓ |
| 4 | **Offline wake model** → *Manage model* → Download (or import a zip) | “Installed (NN MB)” |
| 5 | **Wake phrases** → choose *Hey JARVIS + JARVIS* (dual) or one phrase; set sensitivity (start 60) | Shows mode + sensitivity |
| 6 | **Female voice** → *Choose voice* → pick a female voice → *Test* | ✓; if your phone has none, it says “pitched fallback” instead of pretending |
| 7 | **Intelligence core** → *Check core* | “Reachable”, version and provider shown |
| 8 | In Termux run `~/jarvis-core/pair.sh`, then **Pair** → paste the 8-digit code | “Paired with http://127.0.0.1:8765” |
| 9 | **Contacts** → Allow | Only needed for “call my brother” |
| 10 | **Phone and SIM** → Allow both | Needed for calls |
| 11 | **SMS** → Allow | Optional; drafting works without it |
| 12 | **Notification access** → toggle JARVIS on | Optional; summaries |
| 13 | **Display over other apps** → Allow | Needed to open apps while the UI is closed |
| 14 | **Accessibility** → enable *JARVIS Navigation* | Optional; back/home/recents only |
| 15 | **Battery optimisation** → *Allow* → Unrestricted | ✓ Unrestricted |
| 16 | **Notes folder** → choose a folder | Optional; files are never deleted |
| 17 | **Wake word test** → *Run test*, say “Hey JARVIS” | Beep, then it speaks “I heard you say …” |
| 18 | **Command test** → *Run test* (“what is my battery level”) | Real percentage spoken |
| 19 | **Background operation** → press Home, say the wake phrase | Works with the app closed |
| 20 | **Diagnostics** → review permissions and log | Shows what is granted/missing |

### After setup: start listening

Press **Start listening** on the main screen (or tap the notification action). The persistent
notification is Android's requirement for a microphone foreground service — it is the honest
indicator that JARVIS can hear you.

---

## Using it

| Say | What happens |
|---|---|
| “Hey JARVIS” | Beep + listening; speak your command |
| “What is my battery level?” | Real reading |
| “Open WhatsApp” | Launches it (needs step 13) |
| “Call Alex” | Confirmation, then Telecom places the call; the outcome is reported honestly |
| “Message Alex I'm running late” | Draft, then send with confirmation (needs SMS) |
| “Set volume to 30” / “Mute” | Applies |
| “Do not disturb on” | Needs DND access |
| “What are my notifications?” | Summary (needs step 12) |
| “Take a note: buy milk” | Creates a file in your folder (step 16) |
| “Remember I prefer tea over coffee” | Stored in local memory |
| “Forget that” / Memory screen | Deletes |

Safety: risky actions (calls, messages, settings) always ask for a spoken “yes” first. Refusals
(root, payments, camera, deleting files, exposing credentials) are spoken, not silent.

---

## Privacy controls

- **Mute** — notification action, hologram button, or “mute yourself”. Releases the microphone.
- **Wake sensitivity** — lower it if the TV triggers it; the notification reports the last
  detection so you can judge.
- **Memory** — Memory screen: view, delete rows, disable, clear, export JSON.
- **Notification exclusions** — JARVIS → Settings → excluded apps; excluded apps are never read.
- **Core down** — stop the core in Termux. The app says so instead of failing silently.

---

## Verifying and repairing

```bash
cd ~/jarvis-universal && ./scripts/termux-diagnostics.sh
```

Checks Python, dependencies, database, loopback API, provider reachability, app presence, service
state, storage and battery exemption; exits non-zero on critical problems. `--json` for machines.

| Symptom | Cause | Fix |
|---|---|---|
| “My intelligence core is not running” | Core stopped / wrong port | `~/jarvis-core/start.sh`; check `curl http://127.0.0.1:8765/health` |
| 401 from the core | Token revoked or database replaced | `pair.sh` again, re-pair in step 8 |
| Wake phrase ignored | Model missing, muted, or service stopped | Step 4, step 17, notification |
| False wakes | Sensitivity too high / bare “JARVIS” mode | Lower sensitivity, use “Hey JARVIS” only |
| No voice / wrong voice | TTS engine lacks a female voice | Step 6; install a TTS voice in Android settings |
| App won't open by voice | Overlay grant missing | Step 13 — the failure message says exactly this |
| Calls fail | No SIM / permission denied | Step 10; result reports `no_sim` or the Telecom error |
| Service dies after a while | OEM battery manager | Step 15 + OEM-specific allowlist (see compatibility doc) |
| Model download fails | Network/proxy | Import the zip manually via step 4 |

---

## Uninstall

```bash
./scripts/termux-uninstall.sh          # remove code, keep memories/pairings
./scripts/termux-uninstall.sh --purge  # also delete ~/.jarvis (memories, keys, pairings)
```

Then uninstall the app in Android settings and revoke any special grants you no longer want.
