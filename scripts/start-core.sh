#!/usr/bin/env bash
# Start the JARVIS intelligence core in the foreground.
#
#   ./start.sh                # default port from ~/.jarvis/env (8765)
#   ./start.sh --port 8790
#
# Sources ~/.jarvis/env if present. Never echoes secrets.

set -euo pipefail

CORE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
VENV_PY="$CORE_DIR/.venv/bin/python"
PORT_ARG=()

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT_ARG=(--port "$2"); shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -f "$DATA_DIR/env" ]; then
  # shellcheck disable=SC1090
  set -a; . "$DATA_DIR/env"; set +a
fi
export JARVIS_DATA_DIR="$DATA_DIR"

if [ ! -x "$VENV_PY" ]; then
  echo "No virtualenv at $CORE_DIR/.venv — run scripts/termux-install.sh first." >&2
  exit 1
fi

# Feature-detect wake-lock tooling; keep the CPU awake only while core work is active.
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock || true
  trap 'termux-wake-unlock >/dev/null 2>&1 || true' EXIT
fi

cd "$CORE_DIR"
# shellcheck disable=SC2068
if [ ${#PORT_ARG[@]} -gt 0 ]; then
  exec "$VENV_PY" -m jarvis_core serve "${PORT_ARG[@]}"
else
  exec "$VENV_PY" -m jarvis_core serve
fi
