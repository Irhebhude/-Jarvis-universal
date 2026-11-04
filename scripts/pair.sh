#!/usr/bin/env bash
# Print a one-time pairing code for the JARVIS Android app.
# The code expires in 5 minutes and only works once.

set -euo pipefail

CORE_DIR="${JARVIS_CORE_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
VENV_PY="$CORE_DIR/.venv/bin/python"

if [ -f "$DATA_DIR/env" ]; then
  # shellcheck disable=SC1090
  set -a; . "$DATA_DIR/env"; set +a
fi

if [ ! -x "$VENV_PY" ]; then
  echo "Core is not installed at $CORE_DIR. Run scripts/termux-install.sh first." >&2
  exit 1
fi

export JARVIS_DATA_DIR="$DATA_DIR"
cd "$CORE_DIR"
exec "$VENV_PY" -m jarvis_core pair
