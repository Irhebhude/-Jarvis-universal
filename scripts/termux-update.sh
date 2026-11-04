#!/usr/bin/env bash
# Update the JARVIS core without losing data, keys or pairing state.
#
#   ./scripts/termux-update.sh           # update code + dependencies, keep database
#   ./scripts/termux-update.sh --pull    # also git pull first (if this is a git checkout)
#
# A timestamped database backup is written before anything is replaced.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${JARVIS_INSTALL_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
DO_PULL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --pull) DO_PULL=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[36m[jarvis]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[jarvis] %s\033[0m\n' "$*" >&2; }

if [ "$DO_PULL" -eq 1 ]; then
  if git -C "$REPO_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    say "pulling latest sources"
    git -C "$REPO_DIR" pull --ff-only || warn "git pull failed; continuing with local sources"
  else
    warn "not a git checkout; skipping pull"
  fi
fi

if [ -f "$DATA_DIR/jarvis.db" ]; then
  BACKUP="$DATA_DIR/backups/jarvis-$(date -u +%Y%m%dT%H%M%SZ).db"
  mkdir -p "$DATA_DIR/backups"
  cp "$DATA_DIR/jarvis.db" "$BACKUP"
  chmod 600 "$BACKUP" 2>/dev/null || true
  say "database backed up to $BACKUP"
fi

if [ -f "$REPO_DIR/core/requirements.txt" ]; then
  CORE_SRC="$REPO_DIR/core"
else
  CORE_SRC="$REPO_DIR"
fi

say "copying updated sources to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --exclude '.venv' --exclude '__pycache__' --exclude '.pytest_cache' \
    --exclude 'tests' "$CORE_SRC"/ "$INSTALL_DIR"/
else
  ( cd "$CORE_SRC" && tar -cf - --exclude='.venv' --exclude='__pycache__' \
      --exclude='.pytest_cache' --exclude='tests' . ) | ( cd "$INSTALL_DIR" && tar -xf - )
fi

VENV_PY="$INSTALL_DIR/.venv/bin/python"
[ -x "$VENV_PY" ] || { echo "No virtualenv; run termux-install.sh instead." >&2; exit 1; }

say "updating dependencies"
"$VENV_PY" -m pip install --quiet --upgrade pip
"$VENV_PY" -m pip install --quiet -r "$INSTALL_DIR/requirements.txt"

say "applying schema changes (idempotent)"
( cd "$INSTALL_DIR" && JARVIS_DATA_DIR="$DATA_DIR" "$VENV_PY" -m jarvis_core init )

install -m 755 "$REPO_DIR/scripts/start-core.sh" "$INSTALL_DIR/start.sh"
install -m 755 "$REPO_DIR/scripts/pair.sh" "$INSTALL_DIR/pair.sh"

say "update complete. Restart the core: stop it (Ctrl-C) and run $INSTALL_DIR/start.sh"
