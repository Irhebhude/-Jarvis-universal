# Bridge API (127.0.0.1 only)

Base URL: `http://127.0.0.1:8765` (configurable port; the host is loopback by default and the
server refuses a non-loopback bind unless `JARVIS_ALLOW_NON_LOOPBACK=1` is set explicitly).

Authentication: `Authorization: Bearer <device_token>` on every endpoint except `/health` and
`/pair`. The token is issued once by `/pair`, stored hashed on the core and Keystore-encrypted on
the phone. `Origin`-bearing requests are rejected (403) so no browser can reach the bridge.

All responses are `Cache-Control: no-store`.

## Errors

| Status | Meaning |
|---|---|
| 401 | missing/invalid token, or pairing code invalid/used/expired |
| 403 | browser `Origin` header, or non-loopback `Host` |
| 413 | request body too large |
| 429 | rate limited (see `Retry-After`), or too many pairing attempts |
| 501 | endpoint supported but the capability is not installed (e.g. no local speech model) |

---

## `GET /health` (no auth)

```json
{"status":"ok","version":"1.0.0","detail":"jarvis core"}
```

## `POST /pair` (no auth, rate limited)

```json
{"code":"12345678","device_name":"Pixel 8","platform":"android"}
```

`code` comes from `./pair.sh` (5-minute TTL, single use, stored hashed).

```json
{"device_token":"...43 chars...","name":"Pixel 8","version":"1.0.0","port":8765}
```

## `GET /status`

```json
{
  "version":"1.0.0","host":"127.0.0.1","port":8765,
  "provider":"openai","model":"gpt-4o-mini","configured":true,
  "speech_recognition":"vosk",
  "memory":{"enabled":true,"count":7,"encryption":true,"kinds":["preference","fact", "..."]},
  "devices":[{"id":1,"name":"Pixel 8","created_at":...,"last_seen":...}],
  "device_connected":true,"tools":34,"uptime_seconds":512,
  "speech":{"engine":"vosk","installed":true,"model_path":"..."},
  "last_device_event":{...}
}
```

## `GET /tools`

Returns the registered tool catalog (id, description, args, confirmation, danger). Used by the app
to verify the two registries agree.

## `POST /assistant/command`

```json
{"text":"call my brother","session_id":"...","device":{"model":"Pixel 8","sdk":34,"actions":["battery_status","call_contact"]}}
```

```json
{
  "plan_id":"26cf45b0…",
  "speech":"Calling my brother.",
  "actions":[{"id":"call_contact","args":{"contact":"my brother"},
              "needs_confirmation":true,
              "description":"Place a call to a contact or number."}],
  "context":[]
}
```

Notes:
- `actions` are *requests*. Nothing has run yet.
- Unregistered tools, shell-like ids, unknown arguments, out-of-range values and tools the device
  did not advertise are dropped, and the reply says so.
- Dangerous requests (root/sudo, remote script piping, money transfer, security disabling) return
  `"error":"refused_dangerous_request"` with no actions.
- If no provider is configured and the request is not a rule-based device command, the response is
  `"error":"provider_unavailable"` plus a spoken explanation.

## `POST /assistant/execute`

```json
{"plan_id":"26cf45b0…",
 "results":[{"action_id":"call_contact",
             "result":{"ok":true,"spoken":"Calling Alex using Android's default SIM. …","data":{}}}]}
```

```json
{"reply":"completed","speech":"Calling Alex using Android's default SIM. …"}
```

`reply` is `completed` only if at least one result reported `ok=true`. The composed speech is
derived from the results; when a provider is configured it is rephrased with the instruction to
report only what the results contain.

## `POST /voice/transcript`

```json
{"format":"pcm16","sample_rate":16000,"audio_b64":"..."}
```

```json
{"text":"what is my battery level","source":"core-vosk"}
```

`501 {"detail":"local_transcription_unavailable"}` when `vosk`/model is absent — the app then uses
the platform recognizer.

## Memory

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/memory/status` | — | `{enabled, count, encryption, kinds}` |
| GET | `/memory/list?limit=100` | — | `{memories:[{id,kind,content,sensitive,created_at}]}` |
| POST | `/memory/add` | `{kind,content,sensitive}` | `{stored,id,kind,sensitive,encrypted}` |
| DELETE | `/memory/{id}` | — | `{ok,deleted}` |
| POST | `/memory/clear` | `{}` | `{memories,turns}` |
| POST | `/memory/enabled` | `{enabled}` | status |
| GET | `/memory/export` | — | `{exported_at,memories,turns}` |

Credential-shaped content is rejected with `400` and never stored.

## `GET /integrations/status`

```json
{
  "ai_provider":{"configured":true,"provider":"gemini","model":"gemini-1.5-flash"},
  "speech_recognition":{"engine":"vosk","installed":true},
  "memory_encryption":{"enabled":true},
  "bridge":{"devices":1,"connected":true},
  "device_actions":["battery_status","..."],
  "tools_registered":["battery_status","..."]
}
```

## `POST /device/event`

The app reports action outcomes for the audit trail:

```json
{"type":"action_result","payload":{"action_id":"battery_status","ok":true,"origin":"voice"},"ts":1700000000000}
```

## `GET /audit?limit=50`

Recent audit events (paired device required). Arguments containing phone numbers, message bodies,
URLs and queries are stored redacted.

## `WS /ws/device`

Same bearer auth in the handshake header. The core pushes:

```json
{"type":"core_status","detail":"core 1.0.0 online; provider=gemini","provider":"gemini","model":"gemini-1.5-flash"}
```

and accepts JSON audit events. The app reconnects with exponential backoff (1 s → 30 s) and a
20-second ping; a heartbeat polls `/health` every 60 s (180 s in battery-saving mode).

## Rate limits

- Default 180 requests/minute per device token (sliding window).
- Pairing: 6 attempts/minute per client, plus 5 attempts per code.
