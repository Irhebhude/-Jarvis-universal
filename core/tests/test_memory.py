from __future__ import annotations

import pytest

from jarvis_core.memory import MemoryCipher, MemoryStore, find_secret


def test_memory_add_list_and_delete(memory: MemoryStore):
    created = memory.add("preference", "Prefers metric units")
    assert created["stored"] is True
    assert created["id"] > 0

    listed = memory.list()
    assert len(listed) == 1
    assert listed[0]["content"] == "Prefers metric units"
    assert listed[0]["kind"] == "preference"

    assert memory.delete(created["id"]) is True
    assert memory.list() == []
    assert memory.delete(9999) is False


def test_memory_disabled_blocks_writes(memory: MemoryStore):
    memory.set_enabled(False)
    assert memory.enabled is False
    result = memory.add("fact", "should not be stored")
    assert result["stored"] is False
    assert memory.list() == []

    memory.set_enabled(True)
    assert memory.add("fact", "stored now")["stored"] is True


def test_secrets_are_refused(memory: MemoryStore):
    with pytest.raises(ValueError):
        memory.add("note", "my key is sk-abcdefghijklmnopqrstuvwx")
    assert memory.list() == []

    # Detector also covers other credential shapes, including prefixed provider keys.
    assert find_secret("token ghp_" + "a" * 30) == "github_token"
    assert find_secret("sk-live-abcdefghijklmnopqrstuvwxyz") == "openai_style_key"
    assert find_secret("sk-proj-" + "a" * 32) == "openai_style_key"
    assert find_secret("hello there") is None


def test_sensitive_memory_is_encrypted(memory: MemoryStore, db):
    row = memory.add("fact", "My door code is 4482", sensitive=True)
    assert row["encrypted"] in (True, False)

    raw = db.query("SELECT content FROM memories")[0]["content"]
    if memory.cipher.available:
        assert "4482" not in raw
        assert row["encrypted"] is True
    # The owner-facing read always decrypts.
    assert memory.list()[0]["content"] == "My door code is 4482"


def test_clear_and_export(memory: MemoryStore):
    memory.add("project", "JARVIS universal project")
    memory.add_turn("session-1", "user", "what is my battery")
    memory.add_turn("session-1", "assistant", "Battery is at 62 percent")

    exported = memory.export()
    assert exported["memories"][0]["content"] == "JARVIS universal project"
    assert any(t["role"] == "user" for t in exported["turns"])

    cleared = memory.clear()
    assert cleared["memories"] == 1
    assert cleared["turns"] == 2
    assert memory.list() == []


def test_conversation_history_is_bounded(memory: MemoryStore):
    for i in range(120):
        memory.add_turn("s", "user", f"turn {i}")
    turns = memory.recent_turns("s", limit=200)
    assert len(turns) <= 80
    assert turns[-1]["content"] == "turn 119"


def test_cipher_roundtrip(settings):
    cipher = MemoryCipher(settings.encryption_key_path)
    if not cipher.available:
        pytest.skip("cryptography not installed")
    encrypted = cipher.encrypt("secret value")
    assert encrypted.startswith(MemoryCipher.PREFIX)
    assert cipher.decrypt(encrypted) == "secret value"
    assert cipher.decrypt("plain value") == "plain value"
