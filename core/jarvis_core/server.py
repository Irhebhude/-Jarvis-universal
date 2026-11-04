"""FastAPI application exposing the authenticated localhost bridge.

Security posture:
  * binds to 127.0.0.1 by default (the run command sets the host from settings),
  * every endpoint except /health and /pair requires a per-device bearer token,
  * requests carrying an Origin header are refused (no browser can reach this API),
  * Host header must be loopback, which blocks DNS-rebinding attempts,
  * per-token rate limiting and a strict body-size cap,
  * responses are no-store; no CORS middleware exists on purpose.
"""

from __future__ import annotations

import base64
import hashlib
import json
import time
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .asr import LocalTranscriber, TranscriptionUnavailable
from .config import VERSION, Settings
from .db import Database
from .memory import MemoryCipher, MemoryStore
from .planner import Planner
from .providers import build_provider
from .security import DeviceAuth, PairingService, RateLimiter
from .tools import TOOLS, catalog

LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "127.0.0.1:8765"}


class PairRequest(BaseModel):
    code: str = Field(min_length=1, max_length=32)
    device_name: str = Field(default="android", max_length=80)
    platform: str = Field(default="android", max_length=32)


class CommandRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    session_id: str = Field(default="default", max_length=80)
    device: dict[str, Any] = Field(default_factory=dict)


class ExecuteRequest(BaseModel):
    plan_id: str = Field(min_length=1, max_length=80)
    results: list[dict[str, Any]] = Field(default_factory=list, max_length=20)


class TranscriptRequest(BaseModel):
    format: str = Field(default="pcm16", max_length=16)
    sample_rate: int = Field(default=16000, ge=8000, le=48000)
    audio_b64: str = Field(min_length=1)


class DeviceEvent(BaseModel):
    type: str = Field(min_length=1, max_length=64)
    payload: dict[str, Any] = Field(default_factory=dict)
    ts: int | None = None


class MemoryToggle(BaseModel):
    enabled: bool


class MemoryAdd(BaseModel):
    kind: str = Field(default="note", max_length=32)
    content: str = Field(min_length=1, max_length=2000)
    sensitive: bool = False


class WebSocketHub:
    def __init__(self) -> None:
        self._connections: list[WebSocket] = []

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self._connections.append(ws)

    def disconnect(self, ws: WebSocket) -> None:
        if ws in self._connections:
            self._connections.remove(ws)

    @property
    def count(self) -> int:
        return len(self._connections)

    async def broadcast(self, payload: dict[str, Any]) -> None:
        dead: list[WebSocket] = []
        for ws in list(self._connections):
            try:
                await ws.send_text(json.dumps(payload))
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    db = Database(settings.db_path)
    cipher = MemoryCipher(settings.encryption_key_path)
    memory = MemoryStore(db, cipher, settings.memory_history_turns)
    auth = DeviceAuth(db)
    pairing = PairingService(db, settings.pair_ttl_seconds)
    provider = build_provider(settings)
    planner = Planner(settings, db, memory, provider)
    transcriber = LocalTranscriber(settings.stt_model_path)
    limiter = RateLimiter(settings.rate_limit_per_minute)
    pair_limiter = RateLimiter(settings.pair_attempts_per_minute)
    hub = WebSocketHub()

    app = FastAPI(
        title="JARVIS intelligence core",
        version=VERSION,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.db = db
    app.state.memory = memory
    app.state.auth = auth
    app.state.pairing = pairing
    app.state.planner = planner
    app.state.transcriber = transcriber
    app.state.hub = hub
    app.state.device_actions = set(TOOLS)
    app.state.last_device_event = None

    # ------------------------------------------------------------- middleware

    @app.middleware("http")
    async def guard(request: Request, call_next):
        host = (request.headers.get("host") or "").split(":")[0]
        if host and host not in ("127.0.0.1", "localhost", "::1", "testserver"):
            return JSONResponse({"error": "forbidden_host"}, status_code=403)
        if request.headers.get("origin"):
            return JSONResponse({"error": "browser_origin_refused"}, status_code=403)
        if request.method in ("POST", "PUT", "PATCH"):
            length = request.headers.get("content-length")
            if length and int(length) > settings.max_audio_bytes + 2_000_000:
                return JSONResponse({"error": "payload_too_large"}, status_code=413)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    def require_device(request: Request) -> dict[str, Any]:
        header = request.headers.get("authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        device = auth.verify(token)
        if device is None:
            db.audit("unknown", "auth_failed", request.url.path, False)
            raise HTTPException(status_code=401, detail="unauthorized")
        fingerprint = hashlib.sha256(token.encode()).hexdigest()[:16]
        allowed, retry_after = limiter.allow(fingerprint)
        if not allowed:
            raise HTTPException(
                status_code=429,
                detail="rate_limited",
                headers={"Retry-After": str(int(retry_after) + 1)},
            )
        return device

    # ----------------------------------------------------------------- routes

    @app.get("/health")
    def health() -> dict[str, Any]:
        # Intentionally minimal: no configuration, no secrets, no owner data.
        return {"status": "ok", "version": VERSION, "detail": "jarvis core"}

    @app.post("/pair")
    def pair(payload: PairRequest, request: Request) -> dict[str, Any]:
        client = request.client.host if request.client else "unknown"
        allowed, retry = pair_limiter.allow(f"pair:{client}")
        if not allowed:
            raise HTTPException(
                status_code=429,
                detail="too_many_pair_attempts",
                headers={"Retry-After": str(int(retry) + 1)},
            )
        ok, result = pairing.redeem(payload.code, payload.device_name)
        if not ok:
            reason = str(result)
            status = 429 if reason == "too_many_attempts" else 401
            raise HTTPException(status_code=status, detail=reason)
        assert isinstance(result, dict)
        db.audit("pair", "paired", payload.device_name[:80], True)
        return {
            "device_token": result["device_token"],
            "name": result["name"],
            "version": VERSION,
            "port": settings.port,
        }

    @app.get("/status")
    def status(device: dict = Depends(require_device)) -> dict[str, Any]:
        devices = auth.devices()
        return {
            **settings.public_summary(),
            "version": VERSION,
            "memory": memory.status(),
            "devices": devices,
            "device_connected": hub.count > 0,
            "tools": len(TOOLS),
            "speech": transcriber.status(),
            "uptime_seconds": int(time.time() - START_TIME),
            "last_device_event": app.state.last_device_event,
        }

    @app.get("/tools")
    def tools(device: dict = Depends(require_device)) -> dict[str, Any]:
        return {"tools": catalog()}

    @app.post("/assistant/command")
    def assistant_command(payload: CommandRequest, device: dict = Depends(require_device)) -> dict[str, Any]:
        actions = payload.device.get("actions")
        if isinstance(actions, list) and actions:
            app.state.device_actions = {str(a) for a in actions}
        plan = planner.plan(payload.text, payload.session_id, payload.device)
        db.audit(
            "assistant",
            "command",
            f"tools={[a.id for a in plan.actions]} error={plan.error}",
            not bool(plan.error),
        )
        return plan.json()

    @app.post("/assistant/execute")
    def assistant_execute(payload: ExecuteRequest, device: dict = Depends(require_device)) -> dict[str, Any]:
        result = planner.compose(payload.plan_id, payload.results)
        db.audit(
            "assistant",
            "execute_composed",
            f"plan={payload.plan_id[:12]} reply={result['reply']}",
            result["reply"] == "completed",
        )
        return result

    @app.post("/voice/transcript")
    def voice_transcript(payload: TranscriptRequest, device: dict = Depends(require_device)) -> dict[str, Any]:
        if len(payload.audio_b64) > settings.max_audio_bytes * 4 // 3 + 100:
            raise HTTPException(status_code=413, detail="audio_too_large")
        if not transcriber.available:
            raise HTTPException(status_code=501, detail="local_transcription_unavailable")
        try:
            audio = base64.b64decode(payload.audio_b64, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail="invalid_base64") from None
        try:
            text = transcriber.transcribe(audio, payload.sample_rate)
        except TranscriptionUnavailable:
            raise HTTPException(status_code=501, detail="local_transcription_unavailable") from None
        except Exception as exc:  # pragma: no cover - native engine failure
            db.audit("device", "transcription_failed", exc.__class__.__name__, False)
            raise HTTPException(status_code=500, detail="transcription_failed") from None
        return {"text": text, "source": "core-vosk"}

    @app.get("/integrations/status")
    def integrations(device: dict = Depends(require_device)) -> dict[str, Any]:
        return {
            "ai_provider": {
                "configured": settings.configured,
                "provider": settings.provider,
                "model": settings.model,
            },
            "speech_recognition": transcriber.status(),
            "memory_encryption": {"enabled": cipher.available},
            "bridge": {"devices": len(auth.devices()), "connected": hub.count > 0},
            "device_actions": sorted(app.state.device_actions),
            "tools_registered": sorted(TOOLS),
        }

    @app.post("/device/event")
    def device_event(payload: DeviceEvent, device: dict = Depends(require_device)) -> dict[str, Any]:
        app.state.last_device_event = {
            "type": payload.type,
            "ts": payload.ts or int(time.time() * 1000),
            "payload": payload.payload,
        }
        db.audit(
            "device",
            payload.type[:64],
            json.dumps(payload.payload, default=str)[:400],
            bool(payload.payload.get("ok", True)),
        )
        return {"ok": True, "received": payload.type}

    # --------------------------------------------------------------- memory

    @app.get("/memory/status")
    def memory_status(device: dict = Depends(require_device)) -> dict[str, Any]:
        return memory.status()

    @app.get("/memory/list")
    def memory_list(limit: int = 100, device: dict = Depends(require_device)) -> dict[str, Any]:
        return {"memories": memory.list(limit=limit)}

    @app.get("/memory/export")
    def memory_export(device: dict = Depends(require_device)) -> dict[str, Any]:
        return memory.export()

    @app.post("/memory/enabled")
    def memory_enabled(payload: MemoryToggle, device: dict = Depends(require_device)) -> dict[str, Any]:
        memory.set_enabled(payload.enabled)
        return memory.status()

    @app.post("/memory/clear")
    def memory_clear(device: dict = Depends(require_device)) -> dict[str, Any]:
        return memory.clear()

    @app.post("/memory/add")
    def memory_add(payload: MemoryAdd, device: dict = Depends(require_device)) -> dict[str, Any]:
        try:
            return memory.add(payload.kind, payload.content, payload.sensitive)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from None

    @app.delete("/memory/{memory_id}")
    def memory_delete(memory_id: int, device: dict = Depends(require_device)) -> dict[str, Any]:
        if not memory.delete(memory_id):
            raise HTTPException(status_code=404, detail="memory_not_found")
        return {"ok": True, "deleted": memory_id}

    @app.get("/audit")
    def audit(limit: int = 50, device: dict = Depends(require_device)) -> dict[str, Any]:
        return {"events": db.recent_audit(limit=max(1, min(limit, 200)))}

    # ------------------------------------------------------------- websocket

    @app.websocket("/ws/device")
    async def device_socket(ws: WebSocket) -> None:
        header = ws.headers.get("authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        if auth.verify(token) is None:
            await ws.close(code=4401)
            return
        origin = ws.headers.get("origin")
        if origin:
            await ws.close(code=4403)
            return

        await hub.connect(ws)
        await ws.send_text(
            json.dumps(
                {
                    "type": "core_status",
                    "detail": f"core {VERSION} online; provider={settings.provider}",
                    "provider": settings.provider,
                    "model": settings.model,
                }
            )
        )
        try:
            while True:
                raw = await ws.receive_text()
                try:
                    message = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if isinstance(message, dict):
                    db.audit(
                        "device",
                        str(message.get("type", "ws_message"))[:64],
                        json.dumps(message.get("payload", {}), default=str)[:300],
                        True,
                    )
        except WebSocketDisconnect:
            pass
        except Exception:
            pass
        finally:
            hub.disconnect(ws)

    return app


START_TIME = time.time()
