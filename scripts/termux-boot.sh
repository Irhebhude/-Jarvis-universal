#!/usr/bin/env bash
# Termux:Boot hook — starts the JARVIS core at device boot *if* the owner opted in.
#
# Install: copy to ~/.termux/boot/jarvis-core.sh (or run termux-install.sh --boot).
# Enable : touch ~/.jarvis/autostart
# Disable: rm ~/.jarvis/autostart
#
# Android still applies background-start restrictions to the app's microphone service; this
# script only starts the Python core, which is not a foreground microphone service.

set -euo pipefail

CORE_DIR="${JARVIS_CORE_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
LOG="$DATA_DIR/core.log"

if [ ! -f "$DATA_DIR/autostart" ]; then
  exit 0
fi
if [ ! -x "$CORE_DIR/start.sh" ]; then
  exit 0
fi

mkdir -p "$DATA_DIR"
# Wait briefly for the network stack and storage to settle after boot.
sleep 8

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock || true
fi

nohup "$CORE_DIR/start.sh" >>"$LOG" 2>&1 &
echo "started jarvis core at $(date -u +%FT%TZ) pid=$!" >>"$LOG"
