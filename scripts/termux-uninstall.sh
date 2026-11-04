#!/usr/bin/env bash
# Uninstall the JARVIS core.
#
#   ./scripts/termux-uninstall.sh           # remove code only, keep database/keys/memory
#   ./scripts/termux-uninstall.sh --purge   # also delete ~/.jarvis (memories, pairings, keys)
#
# The Android app is uninstalled from Android settings; this script prints that reminder.

set -euo pipefail

INSTALL_DIR="${JARVIS_INSTALL_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

say() { printf '\033[36m[jarvis]\033[0m %s\n' "$*"; }

say "stopping any running core"
if command -v pkill >/dev/null 2>&1; then
  pkill -f "jarvis_core serve" 2>/dev/null || true
fi

if [ -f "$HOME/.termux/boot/jarvis-core.sh" ]; then
  rm -f "$HOME/.termux/boot/jarvis-core.sh"
  say "removed Termux:Boot autostart"
fi

if [ -d "$INSTALL_DIR" ]; then
  if [ -f "$DATA_DIR/jarvis.db" ]; then
    BACKUP="$DATA_DIR/backups/jarvis-uninstall-$(date -u +%Y%m%dT%H%M%SZ).db"
    mkdir -p "$DATA_DIR/backups"
    cp "$DATA_DIR/jarvis.db" "$BACKUP" && say "database copied to $BACKUP"
  fi
  rm -rf "$INSTALL_DIR"
  say "removed $INSTALL_DIR"
else
  say "$INSTALL_DIR not present"
fi

if [ "$PURGE" -eq 1 ]; then
  if [ -d "$DATA_DIR" ]; then
    rm -rf "$DATA_DIR"
    say "removed $DATA_DIR (memories, pairings, encryption key, env)"
  fi
else
  say "kept $DATA_DIR (database, keys, provider env). Re-run with --purge to delete it."
fi

cat <<'EOF'

Remaining manual steps on the phone:
  1. Android Settings -> Apps -> JARVIS UNIVERSAL -> Uninstall.
  2. Revoke optional grants you no longer want:
       - Settings -> Accessibility -> JARVIS Navigation (off)
       - Settings -> Notifications -> Device & app notifications -> JARVIS (off)
       - Settings -> Apps -> Special access -> Display over other apps -> JARVIS (off)
  3. Remove "Allow restricted settings"/battery exemption if you granted it.
EOF
