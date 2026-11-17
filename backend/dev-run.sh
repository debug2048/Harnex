#!/usr/bin/env bash
# Harnex backend dev runner (Linux / macOS / WSL).
#
# Default: foreground — logs stream to your terminal AND are mirrored to target/dev.log.
#          Stop with Ctrl+C.
# --bg / -b: background via nohup, PID written to target/dev.pid (legacy behaviour).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p target

case "${1:-}" in
  --bg|-b)
    nohup mvn -q -DskipTests spring-boot:run > target/dev.log 2>&1 &
    echo $! > target/dev.pid
    echo "backend started in background:"
    echo "  pid : $(cat target/dev.pid)"
    echo "  log : $(pwd)/target/dev.log   (tail -f to follow)"
    echo "  url : http://localhost:8088"
    echo "  stop: kill \$(cat $(pwd)/target/dev.pid)"
    ;;
  *)
    cat <<EOF

============================================================
 Harnex backend dev server
 url   : http://localhost:8088
 logs  : $(pwd)/target/dev.log  (also streamed to this terminal)
 stop  : Ctrl+C
 (run with --bg to detach into background instead)
============================================================

EOF
    # Drop -q so Maven prints progress; tee mirrors output to target/dev.log.
    mvn -DskipTests spring-boot:run 2>&1 | tee target/dev.log
    ;;
esac
