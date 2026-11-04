#!/usr/bin/env bash
# JARVIS UNIVERSAL — reproducible Termux intelligence-core installer.
#
# Idempotent: re-running updates the code and dependencies without touching your database,
# memory key, pairing state or provider configuration.
#
# Usage:
#   ./scripts/termux-install.sh                 # install/update core
#   ./scripts/termux-install.sh --with-stt      # also try to install offline speech (vosk)
#   ./scripts/termux-install.sh --boot          # also install the Termux:Boot autostart script
#   ./scripts/termux-install.sh --install-dir DIR --data-dir DIR
#
# Nothing secret is written by this script. Provider keys belong in ~/.jarvis/env (0600),
# which this script creates empty for you to edit yourself.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${JARVIS_INSTALL_DIR:-$HOME/jarvis-core}"
DATA_DIR="${JARVIS_DATA_DIR:-$HOME/.jarvis}"
WITH_STT=0
INSTALL_BOOT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --with-stt) WITH_STT=1 ;;
    --boot) INSTALL_BOOT=1 ;;
    --install-dir) INSTALL_DIR="$2"; shift ;;
    --data-dir) DATA_DIR="$2"; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[36m[jarvis]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[jarvis] %s\033[0m\n' "$*" >&2; }
fail() { printf '\033[31m[jarvis] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- environment

IN_TERMUX=0
if [ -n "${TERMUX_VERSION:-}" ] || [ -d /data/data/com.termux ]; then
  IN_TERMUX=1
fi
if [ "$IN_TERMUX" -eq 0 ]; then
  warn "This does not look like Termux. Continuing anyway for testing on a desktop."
fi

ARCH="$(uname -m)"
case "$ARCH" in
  aarch64|armv8l|armv7l|x86_64|i686) say "architecture: $ARCH" ;;
  *) warn "untested architecture: $ARCH" ;;
esac

command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || \
  fail "python is missing. Run: pkg install python"
PYTHON_BIN="$(command -v python3 || command -v python)"
"$PYTHON_BIN" - <<'PY' || fail "Python 3.10+ is required (pkg install python)."
import sys
raise SystemExit(0 if sys.version_info >= (3, 10) else 1)
PY
say "python: $("$PYTHON_BIN" -V 2>&1)"

if [ -f "$REPO_DIR/core/requirements.txt" ]; then
  CORE_SRC="$REPO_DIR/core"
elif [ -f "$REPO_DIR/requirements.txt" ]; then
  CORE_SRC="$REPO_DIR"
else
  fail "core sources not found next to this script (expected core/requirements.txt)"
fi

# ---------------------------------------------------------------- file layout

say "install directory: $INSTALL_DIR"
say "data directory   : $DATA_DIR"
mkdir -p "$INSTALL_DIR" "$DATA_DIR"
chmod 700 "$DATA_DIR" 2>/dev/null || true

copy_core() {
  # Copy sources without ever following into an existing virtualenv or cache.
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete \
      --exclude '.venv' --exclude '__pycache__' --exclude '.pytest_cache' \
      --exclude 'tests' \
      "$CORE_SRC"/ "$INSTALL_DIR"/
  else
    ( cd "$CORE_SRC" && tar -cf - \
        --exclude='.venv' --exclude='__pycache__' --exclude='.pytest_cache' --exclude='tests' \
        . ) | ( cd "$INSTALL_DIR" && tar -xf - )
  fi
}
copy_core
say "core sources copied"

# ---------------------------------------------------------------- dependencies

if [ ! -x "$INSTALL_DIR/.venv/bin/python" ]; then
  say "creating virtual environment"
  "$PYTHON_BIN" -m venv "$INSTALL_DIR/.venv" || fail "venv creation failed (pkg install python)"
fi
VENV_PY="$INSTALL_DIR/.venv/bin/python"

say "installing python dependencies"
"$VENV_PY" -m pip install --quiet --upgrade pip
"$VENV_PY" -m pip install --quiet -r "$INSTALL_DIR/requirements.txt" || \
  fail "dependency installation failed (check network, then re-run)"

if [ "$WITH_STT" -eq 1 ]; then
  say "installing optional offline speech recognition (vosk)"
  if "$VENV_PY" -m pip install --quiet vosk; then
    say "vosk installed. Put a model in $DATA_DIR/vosk-model to enable core transcription."
  else
    warn "vosk could not be installed on this device; the app will use the Android recognizer."
  fi
fi

# ---------------------------------------------------------------- configuration

if [ ! -f "$DATA_DIR/env" ]; then
  umask 077
  cat > "$DATA_DIR/env" <<'ENVEOF'
# JARVIS core environment — keep this file private (chmod 600).
# Uncomment ONE provider block and set your own key. Never share this file.

# --- OpenAI-compatible (OpenAI, Groq, OpenRouter, local server)
# export JARVIS_PROVIDER=openai
# export JARVIS_API_KEY=your-key-here
# export JARVIS_MODEL=gpt-4o-mini
# export JARVIS_BASE_URL=https://api.openai.com/v1

# --- Google Gemini
# export JARVIS_PROVIDER=gemini
# export GEMINI_API_KEY=your-key-here
# export JARVIS_MODEL=gemini-1.5-flash

# --- Anthropic Claude
# export JARVIS_PROVIDER=anthropic
# export ANTHROPIC_API_KEY=your-key-here
# export JARVIS_MODEL=claude-3-5-haiku-latest

# --- Local server (e.g. Ollama or llama.cpp) needs no key
# export JARVIS_PROVIDER=openai
# export JARVIS_API_KEY=local
# export JARVIS_MODEL=llama3.1
# export JARVIS_BASE_URL=http://127.0.0.1:11434/v1

# Bridge port (must match the app's Setup step 8).
export JARVIS_PORT=8765
ENVEOF
  chmod 600 "$DATA_DIR/env"
  say "created $DATA_DIR/env (empty by default: rule-based commands work without any provider)"
else
  say "keeping existing $DATA_DIR/env"
fi

say "initialising database and encryption key"
( cd "$INSTALL_DIR" && JARVIS_DATA_DIR="$DATA_DIR" "$VENV_PY" -m jarvis_core init )

# ---------------------------------------------------------------- helpers

install -m 755 "$REPO_DIR/scripts/start-core.sh" "$INSTALL_DIR/start.sh"
install -m 755 "$REPO_DIR/scripts/pair.sh" "$INSTALL_DIR/pair.sh"
say "installed $INSTALL_DIR/start.sh and $INSTALL_DIR/pair.sh"

if [ "$INSTALL_BOOT" -eq 1 ]; then
  if [ -d "$HOME/.termux/boot" ]; then
    install -m 755 "$REPO_DIR/scripts/termux-boot.sh" "$HOME/.termux/boot/jarvis-core.sh"
    say "installed Termux:Boot script (~/.termux/boot/jarvis-core.sh)"
    say "autostart is gated on the file $DATA_DIR/autostart (create it to enable)"
  else
    warn "Termux:Boot not set up. Install Termux:Boot, open it once, then re-run with --boot."
  fi
fi

# ---------------------------------------------------------------- verification

say "running core diagnostics"
( cd "$INSTALL_DIR" && JARVIS_DATA_DIR="$DATA_DIR" "$VENV_PY" -m jarvis_core diagnostics ) || \
  warn "diagnostics reported issues (expected if the core is not running yet)"

cat <<EOF

$(printf '\033[32mCore installed.\033[0m')

  Start the core      : $INSTALL_DIR/start.sh
  Pair the app        : $INSTALL_DIR/pair.sh
  Diagnostics         : $REPO_DIR/scripts/termux-diagnostics.sh
  Update later        : $REPO_DIR/scripts/termux-update.sh
  Uninstall           : $REPO_DIR/scripts/termux-uninstall.sh

  Add your AI provider key to $DATA_DIR/env, then restart the core.
  Without a key, all device commands still work through the rule-based lane.
EOF
