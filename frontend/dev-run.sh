#!/usr/bin/env bash
# Harnex frontend dev runner (Linux / macOS / WSL).
#
# Default: foreground — logs stream to your terminal AND are mirrored to dev.log.
#          Stop with Ctrl+C.
# --bg / -b: background via nohup, PID written to dev.pid (legacy behaviour).
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -d node_modules ]]; then
  echo "[dev-run] installing npm dependencies (first run, may take a minute)..."
  npm install --no-audit --no-fund
fi

case "${1:-}" in
  --bg|-b)
    nohup npm run dev > dev.log 2>&1 &
    echo $! > dev.pid
    echo "frontend started in background:"
    echo "  pid : $(cat dev.pid)"
    echo "  log : $(pwd)/dev.log   (tail -f to follow)"
    echo "  url : http://localhost:18081"
    echo "  stop: kill \$(cat $(pwd)/dev.pid)"
    ;;
  *)
    cat <<EOF

============================================================
 Harnex frontend dev server
 url   : http://localhost:18081
 logs  : $(pwd)/dev.log  (also streamed to this terminal)
 stop  : Ctrl+C
 (run with --bg to detach into background instead)
============================================================

EOF
    # tee keeps the live stream visible AND writes to dev.log so you can grep later.
    npm run dev 2>&1 | tee dev.log
    ;;
esac
