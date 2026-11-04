from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from jarvis_core.config import Settings  # noqa: E402
from jarvis_core.db import Database  # noqa: E402
from jarvis_core.memory import MemoryCipher, MemoryStore  # noqa: E402


@pytest.fixture()
def settings(tmp_path: Path) -> Settings:
    return Settings(
        host="127.0.0.1",
        port=8765,
        data_dir=tmp_path / "data",
        provider="offline",
        model="",
        api_key="",
        base_url="",
        stt_model_path=None,
        pair_attempts_per_minute=100,
        rate_limit_per_minute=1000,
    )


@pytest.fixture()
def db(settings: Settings) -> Database:
    return Database(settings.db_path)


@pytest.fixture()
def memory(settings: Settings, db: Database) -> MemoryStore:
    return MemoryStore(db, MemoryCipher(settings.encryption_key_path))


class StubProvider:
    """Deterministic stand-in for a configured AI provider."""

    name = "stub"
    model = "stub-1"

    def __init__(self, reply: str):
        self.reply = reply

    def complete(self, messages, temperature: float = 0.3) -> str:
        return self.reply


@pytest.fixture()
def stub_provider():
    return StubProvider
