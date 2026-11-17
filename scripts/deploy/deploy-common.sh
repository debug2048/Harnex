#!/usr/bin/env bash
# Shared Docker deployment operations. Shipped beside install.sh and upgrade.sh.
deploy_fail() { printf '[ERROR] %s\n' "$*" >&2; return 1; }
deploy_valid_id() { [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]; }
deploy_valid_ref() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@+-]*$ ]]; }
deploy_tag() { printf 'harnex-%s:deploy-%s' "$1" "${2#sha256:}"; }

deploy_cleanup() {
    [[ -z "${DEPLOY_RECORD_TEMP:-}" ]] || rm -f -- "$DEPLOY_RECORD_TEMP"
    if [[ "${DEPLOY_OWNS_LOCK:-false}" == true ]]; then rmdir -- "$DEPLOY_LOCK" 2>/dev/null || true; fi
}

deploy_record() {
    local file="$1" backend="$2" frontend="$3"
    DEPLOY_RECORD_TEMP="$(mktemp "${DEPLOY_DIR}/.harnex-images.XXXXXX")"
    printf 'PROJECT=%s\nBACKEND_ID=%s\nFRONTEND_ID=%s\n' "$DEPLOY_PROJECT" "$backend" "$frontend" > "$DEPLOY_RECORD_TEMP"
    mv -f -- "$DEPLOY_RECORD_TEMP" "${DEPLOY_DIR}/${file}"
    DEPLOY_RECORD_TEMP=""
}

deploy_inspect_container() {
    docker container inspect --format '{{.Image}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "HARNEX-$1" 2>/dev/null
}

deploy_existing_container() {
    local service="$1" names name
    names="$(docker container ls --all --format '{{.Names}}')" || return 1
    while IFS= read -r name; do
        if [[ "$name" == "HARNEX-$service" ]]; then
            deploy_inspect_container "$service"
            return $?
        fi
    done <<< "$names"
}

deploy_check_snapshot() {
    local service="$1" line="$2" id project actual_service state health
    [[ -z "$line" ]] && return 0
    IFS='|' read -r id project actual_service state health <<< "$line"
    deploy_valid_id "$id" && [[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ && "$actual_service" == "$service" ]] ||
        deploy_fail "HARNEX-$service is not a recognized Compose service; refusing to replace it"
}

deploy_image_platform() {
    local actual
    deploy_valid_id "$1" || { deploy_fail "Invalid image ID"; return 1; }
    actual="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$1")" || return 1
    [[ "$actual" == "$DEPLOY_PLATFORM" ]] || deploy_fail "Image platform $actual does not match Docker server $DEPLOY_PLATFORM"
}

deploy_manifest() {
    local key value seen_backend=false seen_frontend=false seen_platform=false
    [[ -f "${PAYLOAD_DIR}/images.env" ]] || { deploy_fail "images.env is required for offline deployment"; return 1; }
    while IFS='=' read -r key value || [[ -n "$key" ]]; do
        value="${value%$'\r'}"
        case "$key" in
            BACKEND_ID) [[ "$seen_backend" == false ]] || return 1; NEW_BACKEND="$value"; seen_backend=true;;
            FRONTEND_ID) [[ "$seen_frontend" == false ]] || return 1; NEW_FRONTEND="$value"; seen_frontend=true;;
            PLATFORM) [[ "$seen_platform" == false ]] || return 1; PACKAGE_PLATFORM="$value"; seen_platform=true;;
            *) deploy_fail "Invalid images.env field"; return 1;;
        esac
    done < "${PAYLOAD_DIR}/images.env"
    deploy_valid_id "$NEW_BACKEND" && deploy_valid_id "$NEW_FRONTEND" ||
        { deploy_fail "Offline manifest requires exact image IDs"; return 1; }
    [[ "$PACKAGE_PLATFORM" == "$DEPLOY_PLATFORM" ]] ||
        { deploy_fail "Package platform $PACKAGE_PLATFORM does not match Docker server $DEPLOY_PLATFORM"; return 1; }
    [[ -f "${PAYLOAD_DIR}/images/harnex-backend.tar" && -f "${PAYLOAD_DIR}/images/harnex-frontend.tar" ]] ||
        { deploy_fail "Both offline image archives are required"; return 1; }
    docker image load -i "${PAYLOAD_DIR}/images/harnex-backend.tar" || return 1
    docker image load -i "${PAYLOAD_DIR}/images/harnex-frontend.tar" || return 1
}

deploy_registry() {
    local backend_ref frontend_ref
    [[ -n "${HARBOR_REGISTRY:-}" && -n "${HARBOR_PROJECT:-}" && -n "${IMAGE_TAG:-}" ]] ||
        { deploy_fail "Registry upgrade requires HARBOR_REGISTRY, HARBOR_PROJECT and IMAGE_TAG"; return 1; }
    backend_ref="${HARBOR_REGISTRY}/${HARBOR_PROJECT}/backend:${IMAGE_TAG}"
    frontend_ref="${HARBOR_REGISTRY}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG}"
    deploy_valid_ref "$backend_ref" && deploy_valid_ref "$frontend_ref" ||
        { deploy_fail "Invalid registry image reference"; return 1; }
    if [[ -n "${HARBOR_USER:-}" || -n "${HARBOR_PWD:-}" ]]; then
        [[ -n "${HARBOR_USER:-}" && -n "${HARBOR_PWD:-}" ]] ||
            { deploy_fail "Both registry credentials must be supplied"; return 1; }
        printf '%s' "$HARBOR_PWD" | docker login "$HARBOR_REGISTRY" -u "$HARBOR_USER" --password-stdin || return 1
    fi
    docker pull --platform "$DEPLOY_PLATFORM" "$backend_ref" || return 1
    docker pull --platform "$DEPLOY_PLATFORM" "$frontend_ref" || return 1
    NEW_BACKEND="$(docker image inspect --format '{{.Id}}' "$backend_ref")" || return 1
    NEW_FRONTEND="$(docker image inspect --format '{{.Id}}' "$frontend_ref")" || return 1
}

deploy_pin() { docker image tag "$2" "$(deploy_tag "$1" "$2")"; }

deploy_healthy() {
    local service="$1" expected="$2" line id project actual_service state health
    line="$(deploy_inspect_container "$service")" || return 1
    IFS='|' read -r id project actual_service state health <<< "$line"
    [[ "$id" == "$expected" && "$project" == "$DEPLOY_PROJECT" && "$actual_service" == "$service" &&
       "$state" == running && "$health" == healthy ]]
}

deploy_activate() {
    local backend="$1" frontend="$2"
    deploy_pin backend "$backend" && deploy_pin frontend "$frontend" || return 1
    HARNEX_BACKEND_IMAGE="$(deploy_tag backend "$backend")" \
    HARNEX_FRONTEND_IMAGE="$(deploy_tag frontend "$frontend")" \
        docker compose --project-name "$DEPLOY_PROJECT" --project-directory "$DEPLOY_DIR" \
        --env-file "${DEPLOY_DIR}/.env" --file "${DEPLOY_DIR}/docker-compose.yml" \
        up --no-build --pull never --wait --wait-timeout "$DEPLOY_TIMEOUT" || return 1
    # Compose can return 0 on interruption. Verify both exact identities and health states.
    deploy_healthy backend "$backend" && deploy_healthy frontend "$frontend" ||
        { deploy_fail "Both services must be running and healthy on the requested images"; return 1; }
}

deploy_main() {
    local mode="$1" payload="$2" registry_requested=false registry_input field version major minor
    local backend_line frontend_line backend_project frontend_project ignored_fields
    shift 2
    umask 077
    if [[ "${1:-}" == --registry-stdin || "${1:-}" == --registry ]]; then
        [[ "$mode" == upgrade ]] || { deploy_fail "Registry input is only supported by upgrade"; return 1; }
        registry_requested=true
        registry_input="$1"
        shift
        if [[ "$registry_input" == --registry-stdin ]]; then
            for field in HARBOR_REGISTRY HARBOR_PROJECT IMAGE_TAG HARBOR_USER HARBOR_PWD; do
                IFS= read -r -d '' "$field" || { deploy_fail "Incomplete registry input"; return 1; }
            done
        fi
    fi
    [[ "$#" -le 1 ]] || { deploy_fail "Usage: $mode.sh [deployment-directory]"; return 1; }
    PAYLOAD_DIR="$payload"
    DEPLOY_DIR="$(CDPATH= cd "${1:-$payload}" && pwd)"
    [[ -f "${DEPLOY_DIR}/.env" && -f "${DEPLOY_DIR}/docker-compose.yml" ]] ||
        { deploy_fail "Deployment directory must contain .env and docker-compose.yml"; return 1; }
    command -v docker >/dev/null 2>&1 || { deploy_fail "Docker is required"; return 1; }
    version="$(docker compose version --short)" || return 1
    version="${version#v}"; major="${version%%.*}"; minor="${version#*.}"; minor="${minor%%.*}"
    [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]] ||
        { deploy_fail "Cannot determine Compose version"; return 1; }
    (( major > 2 || major == 2 && minor >= 24 )) ||
        { deploy_fail "Docker Compose >= 2.24 is required"; return 1; }
    DEPLOY_PLATFORM="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')" || return 1
    [[ "$DEPLOY_PLATFORM" == linux/amd64 || "$DEPLOY_PLATFORM" == linux/arm64 ]] ||
        { deploy_fail "Deployment requires Linux amd64/arm64 containers"; return 1; }
    DEPLOY_TIMEOUT="${HARNEX_DEPLOY_TIMEOUT:-120}"
    [[ "$DEPLOY_TIMEOUT" =~ ^[1-9][0-9]{0,3}$ ]] ||
        { deploy_fail "HARNEX_DEPLOY_TIMEOUT must be 1..9999 seconds"; return 1; }

    # Shared per-user lock serializes upgrades launched from different package directories.
    DEPLOY_LOCK="${HARNEX_DEPLOY_LOCK_DIR:-${HOME}/.harnex/docker-deploy.lock}"
    [[ "$DEPLOY_LOCK" == /* ]] || { deploy_fail "Deployment lock path must be absolute"; return 1; }
    mkdir -p "$(dirname "$DEPLOY_LOCK")"
    mkdir "$DEPLOY_LOCK" 2>/dev/null ||
        { deploy_fail "Another deployment or an interrupted deployment holds $DEPLOY_LOCK"; return 1; }
    DEPLOY_OWNS_LOCK=true
    DEPLOY_RECORD_TEMP=""
    trap deploy_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    backend_line="$(deploy_existing_container backend)" || return 1
    frontend_line="$(deploy_existing_container frontend)" || return 1
    deploy_check_snapshot backend "$backend_line" && deploy_check_snapshot frontend "$frontend_line" || return 1
    OLD_BACKEND=""; OLD_FRONTEND=""; backend_project=""; frontend_project=""
    IFS='|' read -r OLD_BACKEND backend_project ignored_fields <<< "$backend_line"
    IFS='|' read -r OLD_FRONTEND frontend_project ignored_fields <<< "$frontend_line"
    [[ -z "$backend_project" || -z "$frontend_project" || "$backend_project" == "$frontend_project" ]] ||
        { deploy_fail "Existing services belong to different Compose projects"; return 1; }
    DEPLOY_PROJECT="${COMPOSE_PROJECT_NAME:-${backend_project:-${frontend_project:-harnex}}}"
    [[ "$DEPLOY_PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] ||
        { deploy_fail "Invalid Compose project name"; return 1; }
    [[ -z "$backend_project" || "$backend_project" == "$DEPLOY_PROJECT" ]] &&
        [[ -z "$frontend_project" || "$frontend_project" == "$DEPLOY_PROJECT" ]] ||
        { deploy_fail "Existing containers belong to another Compose project"; return 1; }
    CAN_ROLLBACK=false
    if [[ "$mode" == upgrade && -n "$OLD_BACKEND" && -n "$OLD_FRONTEND" ]]; then
        CAN_ROLLBACK=true
        deploy_pin backend "$OLD_BACKEND" && deploy_pin frontend "$OLD_FRONTEND" || return 1
        deploy_record .harnex-previous-images.env "$OLD_BACKEND" "$OLD_FRONTEND"
    fi

    NEW_BACKEND=""; NEW_FRONTEND=""; PACKAGE_PLATFORM=""
    if [[ "$mode" == upgrade && ( "$registry_requested" == true ||
        ! -f "${PAYLOAD_DIR}/images.env" && ( -n "${HARBOR_REGISTRY:-}" || -n "${IMAGE_TAG:-}" ) ) ]]; then
        deploy_registry || return 1
    else
        deploy_manifest || return 1
    fi
    deploy_image_platform "$NEW_BACKEND" && deploy_image_platform "$NEW_FRONTEND" || return 1
    if [[ "$mode" == install || "$CAN_ROLLBACK" == false ]]; then
        [[ -z "$OLD_BACKEND" || "$OLD_BACKEND" == "$NEW_BACKEND" ]] &&
            [[ -z "$OLD_FRONTEND" || "$OLD_FRONTEND" == "$NEW_FRONTEND" ]] ||
            { deploy_fail "Initial deployment would replace an existing image; recover the previous pair before upgrading"; return 1; }
    fi

    if deploy_activate "$NEW_BACKEND" "$NEW_FRONTEND"; then
        deploy_record .harnex-current-images.env "$NEW_BACKEND" "$NEW_FRONTEND"
        printf 'Deployment healthy: project=%s backend=%s frontend=%s\n' "$DEPLOY_PROJECT" "$NEW_BACKEND" "$NEW_FRONTEND"
        return 0
    fi
    deploy_fail "$mode failed" || true
    if [[ "$CAN_ROLLBACK" == true ]]; then
        if deploy_activate "$OLD_BACKEND" "$OLD_FRONTEND"; then
            deploy_record .harnex-current-images.env "$OLD_BACKEND" "$OLD_FRONTEND"
            printf 'Previous images restored and healthy; upgrade still failed.\n' >&2
        else
            printf '[ERROR] Rollback failed; inspect both services and .harnex-previous-images.env.\n' >&2
        fi
    fi
    return 1
}
