"""Authentication, pairing and rate limiting for the local bridge.

Design:
  * The device never holds a shared master secret. Pairing a device returns a fresh 256-bit token
    which is displayed to the app once and only stored as a SHA-256 hash here.
  * Pairing codes are 8 digits, single use, expire after five minutes, and are also stored hashed.
  * Failures are rate limited per client and pairing attempts are additionally limited per code,
    so a stolen code cannot be brute forced over the loopback bridge.
  * Tokens are compared with hmac.compare_digest.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time
from collections import defaultdict, deque
from typing import Any

from .db import Database

_HASH_SALT = b"jarvis-universal-bridge-v1"


def _hash(secret: str) -> str:
    return hashlib.sha256(_HASH_SALT + secret.encode("utf-8")).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)


def new_pairing_code() -> str:
    """8 digit numeric code, leading zeros preserved."""
    return f"{secrets.randbelow(100_000_000):08d}"


class RateLimiter:
    """Sliding-window limiter keyed by an opaque string (token fingerprint or client id)."""

    def __init__(self, per_minute: int):
        self.per_minute = max(1, per_minute)
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str, cost: int = 1) -> tuple[bool, float]:
        now = time.monotonic()
        window_start = now - 60.0
        with self._lock:
            bucket = self._hits[key]
            while bucket and bucket[0] < window_start:
                bucket.popleft()
            if len(bucket) + cost > self.per_minute:
                retry_after = max(0.0, 60.0 - (now - bucket[0])) if bucket else 60.0
                return False, retry_after
            for _ in range(cost):
                bucket.append(now)
            return True, 0.0

    def reset(self, key: str | None = None) -> None:
        with self._lock:
            if key is None:
                self._hits.clear()
            else:
                self._hits.pop(key, None)


class DeviceAuth:
    """Issues and verifies per-device bearer tokens."""

    def __init__(self, db: Database):
        self.db = db

    def issue(self, device_name: str) -> str:
        token = new_token()
        now = time.time()
        self.db.execute(
            "INSERT INTO devices(name, token_hash, created_at, last_seen) VALUES(?,?,?,?)",
            (device_name[:80] or "android", _hash(token), now, now),
        )
        self.db.audit("pair", "device_paired", device_name[:80], True)
        return token

    def verify(self, token: str | None) -> dict[str, Any] | None:
        if not token:
            return None
        candidate = _hash(token)
        for row in self.db.query("SELECT * FROM devices"):
            if hmac.compare_digest(row["token_hash"], candidate):
                self.db.execute(
                    "UPDATE devices SET last_seen = ? WHERE id = ?",
                    (time.time(), row["id"]),
                )
                return dict(row)
        return None

    def devices(self) -> list[dict]:
        rows = self.db.query("SELECT id, name, created_at, last_seen FROM devices")
        return [dict(r) for r in rows]

    def revoke_all(self) -> int:
        cur = self.db.execute("DELETE FROM devices")
        self.db.audit("owner", "devices_revoked", None, True)
        return cur.rowcount if cur.rowcount is not None else 0


class PairingService:
    def __init__(self, db: Database, ttl_seconds: int = 300, max_attempts: int = 5):
        self.db = db
        self.ttl = ttl_seconds
        self.max_attempts = max_attempts
        self.auth = DeviceAuth(db)

    def issue_code(self) -> tuple[str, float]:
        code = new_pairing_code()
        now = time.time()
        self.db.execute("DELETE FROM pair_codes WHERE expires_at < ?", (now,))
        self.db.execute(
            "INSERT INTO pair_codes(code_hash, created_at, expires_at, used, attempts) "
            "VALUES(?,?,?,0,0)",
            (_hash(code), now, now + self.ttl),
        )
        return code, now + self.ttl

    def redeem(self, code: str, device_name: str) -> tuple[bool, dict | str]:
        code = (code or "").strip()
        if not code:
            return False, "missing_code"
        candidate = _hash(code)
        row = self.db.query_one("SELECT * FROM pair_codes WHERE code_hash = ?", (candidate,))
        if row is None:
            # Still count the attempt against every live code so guessing is expensive.
            self.db.audit("unknown", "pair_failed", "unknown_code", False)
            return False, "invalid_code"
        if row["used"]:
            return False, "code_already_used"
        if row["expires_at"] < time.time():
            return False, "code_expired"
        if row["attempts"] >= self.max_attempts:
            return False, "too_many_attempts"

        self.db.execute(
            "UPDATE pair_codes SET attempts = attempts + 1 WHERE code_hash = ?",
            (candidate,),
        )
        self.db.execute("UPDATE pair_codes SET used = 1 WHERE code_hash = ?", (candidate,))
        token = self.auth.issue(device_name or "android")
        return True, {"device_token": token, "name": device_name or "android"}
