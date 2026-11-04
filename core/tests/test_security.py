from __future__ import annotations

import time

from jarvis_core.db import Database
from jarvis_core.security import (
    DeviceAuth,
    PairingService,
    RateLimiter,
    _hash,
)


def test_pairing_returns_a_device_token_and_stores_only_a_hash(db: Database):
    pairing = PairingService(db, ttl_seconds=300)
    code, _ = pairing.issue_code()

    ok, result = pairing.redeem(code, "pixel-test")
    assert ok and isinstance(result, dict)
    token = result["device_token"]
    assert len(token) > 30

    stored = db.query("SELECT token_hash FROM devices")
    assert len(stored) == 1
    assert stored[0]["token_hash"] == _hash(token)
    assert token not in stored[0]["token_hash"]

    auth = DeviceAuth(db)
    assert auth.verify(token) is not None
    assert auth.verify(token + "x") is None
    assert auth.verify("") is None
    assert auth.verify(None) is None


def test_pairing_code_is_single_use(db: Database):
    pairing = PairingService(db, ttl_seconds=300)
    code, _ = pairing.issue_code()

    ok, _ = pairing.redeem(code, "device-a")
    assert ok
    ok_again, reason = pairing.redeem(code, "device-b")
    assert not ok_again
    assert reason == "code_already_used"
    assert len(db.query("SELECT * FROM devices")) == 1


def test_pairing_code_expires(db: Database):
    pairing = PairingService(db, ttl_seconds=1)
    code, expires_at = pairing.issue_code()
    assert expires_at > time.time()

    db.execute("UPDATE pair_codes SET expires_at = ?", (time.time() - 5,))
    ok, reason = pairing.redeem(code, "device")
    assert not ok
    assert reason == "code_expired"


def test_unknown_code_is_rejected(db: Database):
    pairing = PairingService(db, ttl_seconds=300)
    ok, reason = pairing.redeem("00000000", "device")
    assert not ok
    assert reason == "invalid_code"


def test_pairing_attempts_are_capped(db: Database):
    pairing = PairingService(db, ttl_seconds=300, max_attempts=1)
    code, _ = pairing.issue_code()
    db.execute("UPDATE pair_codes SET attempts = 1")
    ok, reason = pairing.redeem(code, "device")
    assert not ok
    assert reason == "too_many_attempts"


def test_revoke_all_invalidates_tokens(db: Database):
    auth = DeviceAuth(db)
    token = auth.issue("device")
    assert auth.verify(token) is not None

    revoked = auth.revoke_all()
    assert revoked == 1
    assert auth.verify(token) is None


def test_rate_limiter_allows_then_blocks():
    limiter = RateLimiter(per_minute=3)
    assert limiter.allow("client")[0]
    assert limiter.allow("client")[0]
    assert limiter.allow("client")[0]
    allowed, retry = limiter.allow("client")
    assert not allowed
    assert retry >= 0
    # A different client has its own bucket.
    assert limiter.allow("other")[0]

    limiter.reset("client")
    assert limiter.allow("client")[0]
