#!/usr/bin/env bash
# dev.sh — watch src/**/*.kt, rebuild on change, and restart the server automatically.
# Requires: entr (brew install entr  /  apt install entr  /  dnf install entr)

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
WEB_PORT="${WEB_PORT:-7070}"
JAR="build/libs/coreys-attractor-server-devel.jar"

# ── Internal: called by entr to rebuild then exec the server ─────────────────
if [[ "${1:-}" == "--run-server" ]]; then
    echo ""
    echo "  [dev] Rebuilding..."
    if ./gradlew jar -q; then
        echo "  [dev] Build OK — starting server on :$WEB_PORT"
        echo ""
        exec "$JAVA_HOME/bin/java" -jar "$JAR" --web-port "$WEB_PORT"
    else
        echo ""
        echo "  [dev] Build FAILED — fix the errors above and save a file to retry."
        echo ""
        exit 1
    fi
fi

# ── Check dependency ──────────────────────────────────────────────────────────
if ! command -v entr &>/dev/null; then
    echo ""
    echo "  ERROR: 'entr' is not installed."
    echo ""
    echo "  Install it, then re-run:"
    echo ""
    echo "    brew install entr      # macOS"
    echo "    apt  install entr      # Debian / Ubuntu"
    echo "    dnf  install entr      # Fedora"
    echo "    pacman -S entr         # Arch"
    echo ""
    exit 1
fi

# ── Start watch loop ──────────────────────────────────────────────────────────
echo ""
echo "  coreys-attractor — dev mode"
echo "  Watching: src/**/*.kt"
echo "  Web UI:   http://localhost:$WEB_PORT"
echo "  Stop:     Ctrl+C"
echo ""

find src -name "*.kt" | entr -r "$0" --run-server
