#!/usr/bin/env bash
set -a
source "$(dirname "$0")/.env"
set +a

UI_DIR="$(dirname "$0")/ui"

if [ ! -d "$UI_DIR/node_modules" ]; then
  echo "Installing UI dependencies..."
  npm install --prefix "$UI_DIR"
fi

# Start Vite dev server in background
npm run dev --prefix "$UI_DIR" &
UI_PID=$!

cleanup() {
  kill "$UI_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

# Start Play (foreground)
sbt run
