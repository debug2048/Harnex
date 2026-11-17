#!/usr/bin/env bash
# Export immutable image contents and image-only Compose assets.
set -euo pipefail
umask 077

VERSION="${1:?usage: build-docker-offline.sh <version> <backend-image> <frontend-image> [linux/amd64|linux/arm64] [output-dir]}"
BACKEND_REF="${2:?missing backend image ref}"
FRONTEND_REF="${3:?missing frontend image ref}"
PLATFORM="${4:-linux/amd64}"
[[ "$VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || { echo "Invalid package version" >&2; exit 2; }
[[ "$PLATFORM" == linux/amd64 || "$PLATFORM" == linux/arm64 ]] || { echo "Unsupported platform: $PLATFORM" >&2; exit 2; }
for ref in "$BACKEND_REF" "$FRONTEND_REF"; do
    [[ "$ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@+-]*$ ]] || { echo "Invalid image reference" >&2; exit 2; }
done
ARCH="${PLATFORM#linux/}"
REPO_ROOT="$(CDPATH= cd "$(dirname "$0")/../.." && pwd)"
OUTPUT="${5:-${REPO_ROOT}/dist/docker-offline/${VERSION}-linux-${ARCH}}"
mkdir -p "$OUTPUT"
OUTPUT="$(CDPATH= cd "$OUTPUT" && pwd)"
TEMP_ROOT="$(CDPATH= cd "${TMPDIR:-/tmp}" && pwd)"
WORKDIR="$(mktemp -d "${TEMP_ROOT}/harnex-docker-package.XXXXXX")"
ARCHIVE_TEMP=""
cleanup() {
    [[ -z "$ARCHIVE_TEMP" ]] || rm -f -- "$ARCHIVE_TEMP" "${ARCHIVE_TEMP}.sha256"
    case "$WORKDIR" in "$TEMP_ROOT"/harnex-docker-package.*) rm -rf -- "$WORKDIR";; esac
}
trap cleanup EXIT
PKG="harnex-${VERSION}-offline-linux-${ARCH}"
PAYLOAD="${WORKDIR}/${PKG}"
mkdir -p "${PAYLOAD}/images"

docker pull --platform "$PLATFORM" "$BACKEND_REF"
docker pull --platform "$PLATFORM" "$FRONTEND_REF"
image_identity() {
    local id actual
    id="$(docker image inspect --format '{{.Id}}' "$1")"
    actual="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$id")"
    [[ "$id" =~ ^sha256:[0-9a-f]{64}$ && "$actual" == "$PLATFORM" ]] ||
        { echo "Image identity/platform mismatch for $1 (wanted $PLATFORM)" >&2; return 1; }
    printf '%s' "$id"
}
BACKEND_ID="$(image_identity "$BACKEND_REF")"
FRONTEND_ID="$(image_identity "$FRONTEND_REF")"
docker image save -o "${PAYLOAD}/images/harnex-backend.tar" "$BACKEND_ID"
docker image save -o "${PAYLOAD}/images/harnex-frontend.tar" "$FRONTEND_ID"
cp "${REPO_ROOT}/docker-compose.yml" "${REPO_ROOT}/.env.example" "$PAYLOAD/"
for file in install.sh upgrade.sh deploy-common.sh; do
    cp "${REPO_ROOT}/scripts/deploy/${file}" "$PAYLOAD/"
    chmod +x "${PAYLOAD}/${file}"
done
printf 'BACKEND_ID=%s\nFRONTEND_ID=%s\nPLATFORM=%s\n' "$BACKEND_ID" "$FRONTEND_ID" "$PLATFORM" > "${PAYLOAD}/images.env"
printf '%s\n' "$VERSION" > "${PAYLOAD}/VERSION"
cat > "${PAYLOAD}/README.txt" <<EOF
Harnex Docker offline package ${VERSION} (${PLATFORM})
Source images: ${BACKEND_REF}, ${FRONTEND_REF}
Requires Bash and Docker with Linux containers, plus Docker Compose >= 2.24.

Install: copy .env.example to .env, configure credentials, then run bash install.sh.
Upgrade: copy your existing .env into this new package directory, then run bash upgrade.sh.
The existing Compose project identity is reused, preserving its named volumes.
Optional: bash install.sh /path/to/deployment (directory must contain docker-compose.yml and .env).

No image pulls or source builds occur during offline installation.
Upgrade failure restores both previous container images and verifies their health.
Rollback does not revert application data, database migrations or user-edited configuration.
Keep the previous package/configuration and a data backup for incompatible releases.
Deployment records: .harnex-current-images.env and .harnex-previous-images.env.
Failed deployment returns nonzero even if rollback succeeds.
EOF
ARCHIVE_TEMP="$(mktemp "${OUTPUT}/.${PKG}.XXXXXX")"
tar -C "$WORKDIR" -czf "$ARCHIVE_TEMP" "$PKG"
if command -v sha256sum >/dev/null 2>&1; then
    DIGEST="$(sha256sum "$ARCHIVE_TEMP")"
else
    DIGEST="$(shasum -a 256 "$ARCHIVE_TEMP")"
fi
DIGEST="${DIGEST%% *}"
printf '%s  %s.tar.gz\n' "$DIGEST" "$PKG" > "${ARCHIVE_TEMP}.sha256"
mv -f -- "$ARCHIVE_TEMP" "${OUTPUT}/${PKG}.tar.gz"
mv -f -- "${ARCHIVE_TEMP}.sha256" "${OUTPUT}/${PKG}.tar.gz.sha256"
ARCHIVE_TEMP=""
printf 'Docker offline package: %s/%s.tar.gz\n' "$OUTPUT" "$PKG"
