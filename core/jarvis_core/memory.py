"""Local-first memory.

* Short-term context is the conversation table (bounded per session).
* Long-term memory is explicit, owner-visible rows that can be corrected or deleted.
* Anything marked sensitive is encrypted with AES (Fernet) using a key file with 0600 permissions.
* API keys and other credentials are refused outright rather than stored, encrypted or not.
"""

from __future__ import annotations

import os
import re
import time
from pathlib import Path
from typing import Any

from .db import Database

SECRET_PATTERNS = [
    # Covers plain (sk-…), project (sk-proj-…) and live (sk-live-…) key formats.
    (re.compile(r"\bsk-[A-Za-z0-9_\-]{16,}\b"), "openai_style_key"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}\b"), "google_api_key"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"), "github_token"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9\-]{10,}\b"), "slack_token"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "private_key"),
    (re.compile(r"\bBearer\s+[A-Za-z0-9._\-]{24,}\b"), "bearer_token"),
]

MEMORY_KINDS = {"preference", "fact", "project", "task", "device", "contact_preference", "note"}


def find_secret(text: str) -> str | None:
    for pattern, name in SECRET_PATTERNS:
        if pattern.search(text or ""):
            return name
    return None


class MemoryCipher:
    """Fernet wrapper. Absent `cryptography` degrades to plaintext with an honest status flag."""

    PREFIX = "enc:v1:"

    def __init__(self, key_path: Path):
        self.key_path = key_path
        self._fernet = None
        try:
            from cryptography.fernet import Fernet

            if key_path.exists():
                key = key_path.read_bytes().strip()
            else:
                key_path.parent.mkdir(parents=True, exist_ok=True)
                key = Fernet.generate_key()
                key_path.write_bytes(key)
                try:
                    os.chmod(key_path, 0o600)
                except OSError:
                    pass
            self._fernet = Fernet(key)
        except Exception:
            self._fernet = None

    @property
    def available(self) -> bool:
        return self._fernet is not None

    def encrypt(self, text: str) -> str:
        if self._fernet is None:
            return text
        token = self._fernet.encrypt(text.encode("utf-8")).decode("ascii")
        return f"{self.PREFIX}{token}"

    def decrypt(self, text: str) -> str:
        if self._fernet is None or not text.startswith(self.PREFIX):
            return text
        from cryptography.fernet import InvalidToken

        try:
            return self._fernet.decrypt(text[len(self.PREFIX):].encode("ascii")).decode("utf-8")
        except (InvalidToken, ValueError):
            return "[unreadable encrypted memory]"


class MemoryStore:
    def __init__(self, db: Database, cipher: MemoryCipher, history_turns: int = 8):
        self.db = db
        self.cipher = cipher
        self.history_turns = history_turns

    # ---------------------------------------------------------------- toggle

    @property
    def enabled(self) -> bool:
        return (self.db.kv_get("memory_enabled", "1") or "1") == "1"

    def set_enabled(self, enabled: bool) -> None:
        self.db.kv_set("memory_enabled", "1" if enabled else "0")
        self.db.audit("owner", "memory_toggled", f"enabled={enabled}", True)

    # -------------------------------------------------------------- long term

    def add(self, kind: str, content: str, sensitive: bool = False) -> dict[str, Any]:
        if not self.enabled:
            return {"stored": False, "reason": "memory_disabled"}
        content = (content or "").strip()
        if not content:
            raise ValueError("empty memory")
        if len(content) > 2000:
            content = content[:2000]
        secret = find_secret(content)
        if secret:
            self.db.audit("owner", "memory_rejected", f"secret_like:{secret}", False)
            raise ValueError("refusing to store something that looks like a credential")

        kind = kind if kind in MEMORY_KINDS else "note"
        stored = self.cipher.encrypt(content) if (sensitive and self.cipher.available) else content
        now = time.time()
        cur = self.db.execute(
            "INSERT INTO memories(kind, content, sensitive, created_at, updated_at) "
            "VALUES(?,?,?,?,?)",
            (kind, stored, 1 if sensitive else 0, now, now),
        )
        self.db.audit("owner", "memory_added", f"kind={kind}", True)
        return {
            "stored": True,
            "id": cur.lastrowid,
            "kind": kind,
            "sensitive": sensitive,
            "encrypted": bool(sensitive and self.cipher.available),
        }

    def list(self, limit: int = 100) -> list[dict[str, Any]]:
        rows = self.db.query(
            "SELECT id, kind, content, sensitive, created_at FROM memories ORDER BY id DESC LIMIT ?",
            (max(1, min(limit, 500)),),
        )
        out = []
        for row in rows:
            out.append(
                {
                    "id": row["id"],
                    "kind": row["kind"],
                    "content": self.cipher.decrypt(row["content"]),
                    "sensitive": bool(row["sensitive"]),
                    "created_at": row["created_at"],
                }
            )
        return out

    def delete(self, memory_id: int) -> bool:
        cur = self.db.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
        deleted = bool(cur.rowcount)
        if deleted:
            self.db.audit("owner", "memory_deleted", f"id={memory_id}", True)
        return deleted

    def clear(self) -> dict[str, int]:
        mem = self.db.execute("DELETE FROM memories").rowcount or 0
        conv = self.db.execute("DELETE FROM conversations").rowcount or 0
        self.db.audit("owner", "memory_cleared", f"memories={mem} turns={conv}", True)
        return {"memories": mem, "turns": conv}

    def export(self) -> dict[str, Any]:
        return {
            "exported_at": time.time(),
            "memories": self.list(limit=500),
            "turns": self.recent_turns_all(),
        }

    def status(self) -> dict[str, Any]:
        row = self.db.query_one("SELECT COUNT(*) AS c FROM memories")
        return {
            "enabled": self.enabled,
            "count": int(row["c"]) if row else 0,
            "encryption": self.cipher.available,
            "kinds": sorted(MEMORY_KINDS),
        }

    # ------------------------------------------------------------- short term

    def add_turn(self, session: str, role: str, content: str, sensitive: bool = False) -> None:
        if not self.enabled:
            return
        stored = self.cipher.encrypt(content) if (sensitive and self.cipher.available) else content
        self.db.execute(
            "INSERT INTO conversations(session, role, content, created_at) VALUES(?,?,?,?)",
            (session or "default", role, stored[:2000], time.time()),
        )
        # Bound the history so the database does not grow without limit.
        self.db.execute(
            "DELETE FROM conversations WHERE session = ? AND id NOT IN "
            "(SELECT id FROM conversations WHERE session = ? ORDER BY id DESC LIMIT 80)",
            (session or "default", session or "default"),
        )

    def recent_turns(self, session: str, limit: int | None = None) -> list[dict[str, str]]:
        limit = limit or self.history_turns * 2
        rows = self.db.query(
            "SELECT role, content FROM conversations WHERE session = ? ORDER BY id DESC LIMIT ?",
            (session or "default", limit),
        )
        return [{"role": r["role"], "content": self.cipher.decrypt(r["content"])} for r in reversed(rows)]

    def recent_turns_all(self, limit: int = 60) -> list[dict[str, Any]]:
        rows = self.db.query(
            "SELECT session, role, content, created_at FROM conversations ORDER BY id DESC LIMIT ?",
            (limit,),
        )
        return [
            {
                "session": r["session"],
                "role": r["role"],
                "content": self.cipher.decrypt(r["content"]),
                "created_at": r["created_at"],
            }
            for r in reversed(rows)
        ]

    def clear_conversation(self, session: str) -> int:
        cur = self.db.execute("DELETE FROM conversations WHERE session = ?", (session,))
        return cur.rowcount or 0
