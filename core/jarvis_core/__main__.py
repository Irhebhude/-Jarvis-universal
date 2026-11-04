"""Command line entry point: serve, pair, diagnostics, revoke-devices, init."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path

from .config import VERSION, Settings
from .db import Database
from .memory import MemoryCipher, MemoryStore
from .security import DeviceAuth, PairingService
from .tools import TOOLS

BANNER = r"""
    ___   __   ___  _   _ ___ ___
   |_  ) /  \ | _ \| | | / __| _ )
    / /| () ||   /| |_| \__ \ _ \
   /___|\__/ |_|_\ \___/|___/___/
   UNIVERSAL  ·  intelligence core  v{version}
"""


def _settings() -> Settings:
    return Settings.from_env()


def _open_db(settings: Settings) -> Database:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    return Database(settings.db_path)


def cmd_init(args: argparse.Namespace) -> int:
    settings = _settings()
    db = _open_db(settings)
    cipher = MemoryCipher(settings.encryption_key_path)
    MemoryStore(db, cipher)
    print(f"data directory : {settings.data_dir}")
    print(f"database       : {settings.db_path}")
    print(f"memory key     : {settings.encryption_key_path} (encryption={'on' if cipher.available else 'off'})")
    print(f"provider       : {settings.provider} / {settings.model or '(none)'}")
    print(f"tools          : {len(TOOLS)}")
    return 0


def cmd_serve(args: argparse.Namespace) -> int:
    settings = _settings()
    host = args.host or settings.host
    port = args.port or settings.port

    if host not in ("127.0.0.1", "localhost", "::1"):
        if os.environ.get("JARVIS_ALLOW_NON_LOOPBACK") != "1":
            print(
                f"Refusing to bind to {host}. The bridge is loopback-only by design.\n"
                "If you really intend to expose it, set JARVIS_ALLOW_NON_LOOPBACK=1 and configure a "
                "firewall; the pairing token only protects loopback traffic.",
                file=sys.stderr,
            )
            return 2

    print(BANNER.format(version=VERSION))
    print(f"  bridge   : http://{host}:{port}")
    print(f"  provider : {settings.provider} / {settings.model or '(not configured)'}")
    print(f"  speech   : {'vosk (offline)' if settings._stt_available() else 'device recognizer fallback'}")
    print(f"  database : {settings.db_path}")
    print(f"  tools    : {len(TOOLS)} registered actions")
    if not settings.configured:
        print("  note     : no AI provider configured — rule-based device commands still work")
    print()

    import uvicorn

    from .server import create_app

    app = create_app(settings)
    uvicorn.run(
        app,
        host=host,
        port=port,
        log_level=settings.log_level,
        access_log=False,
    )
    return 0


def cmd_pair(args: argparse.Namespace) -> int:
    settings = _settings()
    db = _open_db(settings)
    pairing = PairingService(db, settings.pair_ttl_seconds)
    code, expires_at = pairing.issue_code()
    remaining = int(expires_at - time.time())
    print()
    print("  Pairing code: " + code)
    print(f"  Valid for    : {remaining} seconds, single use")
    print(f"  Core address : 127.0.0.1:{settings.port}")
    print()
    print("  In the JARVIS app: Setup -> 8. Pair with core -> enter the code.")
    print()
    return 0


def cmd_revoke(args: argparse.Namespace) -> int:
    settings = _settings()
    db = _open_db(settings)
    count = DeviceAuth(db).revoke_all()
    print(f"Revoked {count} paired device(s). Pair again from the app when needed.")
    return 0


def cmd_diagnostics(args: argparse.Namespace) -> int:
    settings = _settings()
    report: dict[str, object] = {
        "version": VERSION,
        "python": sys.version.split()[0],
        "data_dir": str(settings.data_dir),
        "database": str(settings.db_path),
        "provider": settings.provider,
        "model": settings.model,
        "provider_configured": settings.configured,
        "speech_recognition": "vosk" if settings._stt_available() else "none",
        "tool_count": len(TOOLS),
    }

    problems: list[str] = []

    deps = {}
    for module in ("fastapi", "uvicorn", "httpx", "pydantic", "cryptography", "vosk"):
        try:
            __import__(module)
            deps[module] = True
        except Exception:
            deps[module] = False
    report["dependencies"] = deps
    for name in ("fastapi", "uvicorn", "httpx", "pydantic"):
        if not deps.get(name):
            problems.append(f"missing python dependency: {name}")
    if not deps.get("cryptography"):
        problems.append("cryptography unavailable: sensitive memory will be stored unencrypted")
    if not deps.get("vosk"):
        problems.append("vosk unavailable: core transcription disabled (app uses device recognizer)")

    try:
        db = _open_db(settings)
        row = db.query_one("SELECT COUNT(*) AS c FROM devices")
        report["paired_devices"] = int(row["c"]) if row else 0
        row = db.query_one("SELECT COUNT(*) AS c FROM memories")
        report["memories"] = int(row["c"]) if row else 0
        report["audit_events"] = len(db.recent_audit(5))
        cipher = MemoryCipher(settings.encryption_key_path)
        report["memory_encryption"] = cipher.available
    except Exception as exc:
        problems.append(f"database error: {exc}")

    try:
        import httpx

        response = httpx.get(f"http://127.0.0.1:{settings.port}/health", timeout=3.0)
        report["bridge_health"] = response.json() if response.status_code == 200 else response.status_code
        if response.status_code != 200:
            problems.append(f"bridge health returned HTTP {response.status_code}")
    except Exception as exc:
        report["bridge_health"] = f"unreachable: {exc.__class__.__name__}"
        problems.append("core is not running on the configured port")

    usage = shutil.disk_usage(str(settings.data_dir if settings.data_dir.exists() else Path.home()))
    report["free_disk_mb"] = usage.free // (1024 * 1024)

    report["problems"] = problems
    print(json.dumps(report, indent=2))
    return 0 if not problems else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="jarvis_core", description="JARVIS UNIVERSAL core")
    sub = parser.add_subparsers(dest="command")

    serve = sub.add_parser("serve", help="run the local bridge")
    serve.add_argument("--host", default=None)
    serve.add_argument("--port", type=int, default=None)
    serve.set_defaults(func=cmd_serve)

    pair = sub.add_parser("pair", help="print a one-time pairing code")
    pair.set_defaults(func=cmd_pair)

    init = sub.add_parser("init", help="create the database and key material")
    init.set_defaults(func=cmd_init)

    diag = sub.add_parser("diagnostics", help="check installation and connectivity")
    diag.set_defaults(func=cmd_diagnostics)

    revoke = sub.add_parser("revoke-devices", help="invalidate all paired device tokens")
    revoke.set_defaults(func=cmd_revoke)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        args = parser.parse_args(["serve"])
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
