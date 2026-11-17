#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/deploy-common.sh"
deploy_main install "$SCRIPT_DIR" "$@"
