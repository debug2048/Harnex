#!/bin/sh
set -eu
harnex_script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$harnex_script_dir/../.."
exec node scripts/dev/dev.mjs --stop "$@"
