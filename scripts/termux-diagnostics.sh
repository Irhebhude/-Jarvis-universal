#!/usr/bin/env bash
# One-command diagnostics for JARVIS UNIVERSAL.
#
# Checks: python/node/git, core dependencies, database, local API health, AI provider
# reachability, app installation, wake-word service state, permissions visible from Termux,
# storage, and battery-restriction status. Exits non-zero if a critical check fails.
#
#   ./scripts/termux-diagnostics.sh
#   ./scripts/termux-diagnostics.sh --json     # machine-readable

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${JARVIS_INSTALL_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
JSON=0
[ "${1:-}" = "--json" ] && JSON=1

CRITICAL_FAIL=0
FAILS=()
WARNS=()

ok()   { [ "$JSON" -eq 1 ] || printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { CRITICAL_FAIL=1; FAILS+=("$*"); [ "$JSON" -eq 1 ] || printf '  \033[31m✗\033[0m %s\n' "$*"; }
warn() { WARNS+=("$*"); [ "$JSON" -eq 1 ] || printf '  \033[33m!\033[0m %s\n' "$*"; }
head_() { [ "$JSON" -eq 1 ] || printf '\n\033[36m%s\033[0m\n' "$*"; }

if [ -f "$DATA_DIR/env" ]; then
  # shellcheck disable=SC1090
  set -a; . "$DATA_DIR/env"; set +a
fi
PORT="${JARVIS_PORT:-8765}"

head_ "Environment"
if command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1; then
  PY="$(command -v python3 || command -v python)"
  VERSION="$("$PY" -V 2>&1)"
  case "$VERSION" in
    *"3.1"[0-9]*) ok "$VERSION" ;;
    *) bad "Python too old: $VERSION (need 3.10+)" ;;
  esac
else
  bad "python missing (pkg install python)"
fi

if command -v node >/dev/null 2>&1; then
  ok "node $(node -v) (optional)"
else
  ok "node not installed (not required by the core)"
fi

if command -v git >/dev/null 2>&1; then
  ok "git $(git --version | awk '{print $3}')"
else
  warn "git missing (only needed to update from source: pkg install git)"
fi

head_ "Core installation"
VENV_PY="$INSTALL_DIR/.venv/bin/python"
if [ -x "$VENV_PY" ]; then
  ok "virtualenv present at $INSTALL_DIR/.venv"
  # The CLI exits non-zero when it *finds* problems; that is not a failure to run.
  DIAG_OUT="$( cd "$INSTALL_DIR" && JARVIS_DATA_DIR="$DATA_DIR" "$VENV_PY" -m jarvis_core diagnostics 2>&1 || true )"
  if printf '%s' "$DIAG_OUT" | grep -q '"tool_count"'; then
    ok "jarvis_core imports and diagnostics CLI runs"
  else
    bad "jarvis_core diagnostics failed to run"
  fi
else
  bad "core not installed (run scripts/termux-install.sh)"
fi

head_ "Dependencies"
for module in fastapi uvicorn httpx pydantic cryptography; do
  if [ -x "$VENV_PY" ] && "$VENV_PY" -c "import $module" >/dev/null 2>&1; then
    ok "$module"
  else
    bad "$module missing"
  fi
done
if [ -x "$VENV_PY" ] && "$VENV_PY" -c "import vosk" >/dev/null 2>&1; then
  if [ -d "$DATA_DIR/vosk-model" ]; then ok "vosk + model (offline transcription)"; else warn "vosk installed but no model at $DATA_DIR/vosk-model"; fi
else
  warn "vosk absent: core transcription disabled, app falls back to device recognizer"
fi

head_ "Database"
if [ -f "$DATA_DIR/jarvis.db" ]; then
  SIZE=$(wc -c <"$DATA_DIR/jarvis.db" | tr -d ' ')
  ok "database present ($SIZE bytes)"
  PERMS=$(stat -c '%a' "$DATA_DIR/jarvis.db" 2>/dev/null || echo "?")
  [ "$PERMS" = "600" ] && ok "database permissions 600" || warn "database permissions $PERMS (expected 600)"
  if command -v sqlite3 >/dev/null 2>&1; then
    DEVICES=$(sqlite3 "$DATA_DIR/jarvis.db" "SELECT COUNT(*) FROM devices;" 2>/dev/null || echo "?")
    MEMORIES=$(sqlite3 "$DATA_DIR/jarvis.db" "SELECT COUNT(*) FROM memories;" 2>/dev/null || echo "?")
    ok "paired devices: $DEVICES · memories: $MEMORIES"
  else
    warn "sqlite3 CLI not installed (records not counted)"
  fi
else
  bad "database missing at $DATA_DIR/jarvis.db (run jarvis-install)"
fi

head_ "Local API"
HEALTH="$(curl -sS --max-time 4 "http://127.0.0.1:$PORT/health" 2>/dev/null || true)"
if printf '%s' "$HEALTH" | grep -q '"status":"ok"'; then
  ok "core responding on 127.0.0.1:$PORT — $HEALTH"
else
  bad "core not responding on 127.0.0.1:$PORT (start it: $INSTALL_DIR/start.sh)"
fi
if printf '%s' "$HEALTH" | grep -q '"status":"ok"' && command -v ss >/dev/null 2>&1; then
  BIND=$(ss -ltn 2>/dev/null | grep ":$PORT " || true)
  if printf '%s' "$BIND" | grep -q '127.0.0.1'; then
    ok "bound to loopback only"
  elif [ -n "$BIND" ]; then
    warn "listening on a non-loopback address: $BIND"
  fi
fi

head_ "AI provider"
if [ -n "${JARVIS_PROVIDER:-}" ]; then
  ok "provider: $JARVIS_PROVIDER · model: ${JARVIS_MODEL:-unset}"
else
  ok "provider auto-detected from exported keys (rule-based commands work regardless)"
fi
BASE="${JARVIS_BASE_URL:-${OPENAI_BASE_URL:-}}"
if [ -n "$BASE" ]; then
  HOST=$(printf '%s' "$BASE" | sed -E 's#^https?://([^/]+).*#\1#')
  if curl -sS -o /dev/null --max-time 6 "https://$HOST" 2>/dev/null || curl -sS -o /dev/null --max-time 6 "http://$HOST" 2>/dev/null; then
    ok "provider host reachable: $HOST"
  else
    warn "provider host not reachable: $HOST (offline commands still work)"
  fi
else
  warn "no provider base URL set (offline rule-based mode)"
fi

head_ "Android app"
if command -v pm >/dev/null 2>&1; then
  if pm list packages 2>/dev/null | grep -q 'dev.jarvis.universal'; then
    ok "JARVIS app installed"
    DEBUG_PRESENT=$(pm list packages 2>/dev/null | grep -c 'dev.jarvis.universal.debug' || true)
    [ "${DEBUG_PRESENT:-0}" -gt 0 ] && ok "debug build also installed"
  else
    warn "JARVIS app not installed (build and install android/ per docs/BUILD.md)"
  fi
else
  warn "pm unavailable from this shell; install the APK from the app's Setup screen"
fi

head_ "Wake-word service"
SERVICE_DUMP=""
if command -v dumpsys >/dev/null 2>&1; then
  SERVICE_DUMP="$(dumpsys activity services dev.jarvis.universal 2>/dev/null | grep -i WakeWordService || true)"
fi
if printf '%s' "$SERVICE_DUMP" | grep -q 'WakeWordService'; then
  ok "WakeWordService is running (per dumpsys)"
elif [ -n "$SERVICE_DUMP" ]; then
  warn "WakeWordService entry found but not obviously active; check the listening notification"
else
  warn "cannot verify the microphone service from Termux (Android restricts dumpsys); check the notification"
fi

head_ "Permissions visible from Termux"
if command -v termux-battery-status >/dev/null 2>&1; then
  BATTERY="$(termux-battery-status 2>/dev/null | tr -d '\n' | head -c 160 || true)"
  [ -n "$BATTERY" ] && ok "battery: $BATTERY" || warn "termux-api present but returned nothing"
else
  warn "termux-api not installed (optional; the app reads device state natively)"
fi

head_ "Storage and battery restrictions"
FREE_MB=$(df -Pk "$DATA_DIR" 2>/dev/null | awk 'NR==2 {print int($4/1024)}')
if [ -n "${FREE_MB:-}" ]; then
  [ "$FREE_MB" -gt 200 ] && ok "free storage: ${FREE_MB} MB" || warn "low storage: ${FREE_MB} MB"
else
  warn "could not read free storage"
fi
if command -v dumpsys >/dev/null 2>&1; then
  if dumpsys deviceidle whitelist 2>/dev/null | grep -q 'dev.jarvis.universal'; then
    ok "app exempt from battery optimisation"
  else
    warn "battery optimisation status not visible / not exempt (grant it in Setup step 15)"
  fi
fi

if [ "$JSON" -eq 1 ]; then
  printf '{"critical_failures":%d,"failures":%d,"warnings":%d}\n' \
    "$CRITICAL_FAIL" "${#FAILS[@]}" "${#WARNS[@]}"
else
  printf '\n'
  if [ "$CRITICAL_FAIL" -eq 1 ]; then
    printf '\033[31mDiagnostics finished with %d critical problem(s) and %d warning(s).\033[0m\n' \
      "${#FAILS[@]}" "${#WARNS[@]}"
  else
    printf '\033[32mDiagnostics finished: no critical problems, %d warning(s).\033[0m\n' "${#WARNS[@]}"
  fi
fi

rm -f "/tmp/jarvis-diag.$$" 2>/dev/null
exit "$CRITICAL_FAIL"
