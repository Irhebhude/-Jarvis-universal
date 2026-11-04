"""Configuration for the JARVIS intelligence core.

Everything is environment driven. The core never writes secrets to disk and never prints them:
the AI provider key comes from the environment (or a 0600 env file the owner sources), and the
bridge token is only ever stored as a hash.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

VERSION = "1.0.0"


def _first_env(*names: str) -> str:
    for name in names:
        value = os.environ.get(name)
        if value:
            return value.strip()
    return ""


def _infer_provider() -> str:
    explicit = _first_env("JARVIS_PROVIDER")
    if explicit:
        return explicit.lower()
    # Do not assume a vendor: infer only from keys the owner actually set.
    if _first_env("GEMINI_API_KEY", "GOOGLE_API_KEY"):
        return "gemini"
    if _first_env("ANTHROPIC_API_KEY"):
        return "anthropic"
    if _first_env("OPENAI_API_KEY", "GROQ_API_KEY", "OPENROUTER_API_KEY", "JARVIS_API_KEY"):
        return "openai"
    return "offline"


def _default_base_url(provider: str) -> str:
    explicit = _first_env("JARVIS_BASE_URL", "OPENAI_BASE_URL")
    if explicit:
        return explicit.rstrip("/")
    if provider == "openai":
        if _first_env("GROQ_API_KEY"):
            return "https://api.groq.com/openai/v1"
        if _first_env("OPENROUTER_API_KEY"):
            return "https://openrouter.ai/api/v1"
        return "https://api.openai.com/v1"
    if provider == "gemini":
        return "https://generativelanguage.googleapis.com/v1beta"
    if provider == "anthropic":
        return "https://api.anthropic.com/v1"
    return ""


def _default_model(provider: str) -> str:
    explicit = _first_env("JARVIS_MODEL", "OPENAI_MODEL", "JARVIS_LLM_MODEL")
    if explicit:
        return explicit
    return {
        "openai": "gpt-4o-mini",
        "gemini": "gemini-1.5-flash",
        "anthropic": "claude-3-5-haiku-latest",
        "offline": "",
    }.get(provider, "")


@dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8765
    data_dir: Path = field(default_factory=lambda: Path.home() / ".jarvis")
    provider: str = "offline"
    model: str = ""
    api_key: str = ""
    base_url: str = ""
    stt_model_path: Path | None = None
    pair_ttl_seconds: int = 300
    rate_limit_per_minute: int = 180
    pair_attempts_per_minute: int = 6
    max_audio_bytes: int = 10_000_000
    max_command_chars: int = 2000
    request_timeout_seconds: float = 45.0
    memory_history_turns: int = 8
    log_level: str = "info"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "jarvis.db"

    @property
    def encryption_key_path(self) -> Path:
        return self.data_dir / "memory.key"

    @property
    def configured(self) -> bool:
        return self.provider != "offline" and bool(self.model)

    @classmethod
    def from_env(cls) -> "Settings":
        provider = _infer_provider()
        data_dir = Path(
            _first_env("JARVIS_DATA_DIR") or (Path.home() / ".jarvis")
        ).expanduser()

        stt_raw = _first_env("JARVIS_STT_MODEL")
        stt_path = Path(stt_raw).expanduser() if stt_raw else None
        if stt_path is None:
            candidate = data_dir / "vosk-model"
            if candidate.is_dir():
                stt_path = candidate

        port_raw = _first_env("JARVIS_PORT")
        try:
            port = int(os.environ.get("JARVIS_PORT", "8765") or "8765")
        except ValueError:
            port = 8765

        return cls(
            host=_first_env("JARVIS_HOST") or "127.0.0.1",
            port=port,
            data_dir=data_dir,
            provider=provider,
            model=_default_model(provider),
            api_key=_first_env(
                "JARVIS_API_KEY",
                "OPENAI_API_KEY",
                "GROQ_API_KEY",
                "OPENROUTER_API_KEY",
                "GEMINI_API_KEY",
                "GOOGLE_API_KEY",
                "ANTHROPIC_API_KEY",
            ),
            base_url=_default_base_url(provider),
            stt_model_path=stt_path,
            log_level=_first_env("JARVIS_LOG_LEVEL") or "info",
        )

    def public_summary(self) -> dict:
        """Safe to expose over the bridge: no secrets, only shape of configuration."""
        return {
            "version": VERSION,
            "host": self.host,
            "port": self.port,
            "data_dir": str(self.data_dir),
            "provider": self.provider,
            "model": self.model,
            "base_url": self.base_url,
            "configured": self.configured,
            "speech_recognition": "vosk" if self._stt_available() else "none",
            "max_command_chars": self.max_command_chars,
        }

    def _stt_available(self) -> bool:
        if self.stt_model_path is None or not self.stt_model_path.exists():
            return False
        try:
            import vosk  # noqa: F401
        except Exception:
            return False
        return True
