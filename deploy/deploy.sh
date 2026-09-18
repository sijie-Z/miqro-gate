#!/bin/sh
# One serialized, self-verifying deploy of the MiQroKey stack.
#
# Why a wrapper instead of ad-hoc `docker build` + `docker compose up`: this box is
# shared by several sessions, and the two failures we actually hit were
#   (a) two of them building the same tag at once, after which "which image is
#       live" could not be answered from the box's state at all, and
#   (b) `up` reporting success while the container kept an older image, because
#       nothing ever compared the running container against the tag.
# So: the whole run holds one lock, and it ends by asserting that what is running
# IS what was just built. Container uptime and a `healthy` status are not evidence
# of that — they look identical when the swap silently did not happen.
#
# Usage:
#   deploy.sh --context DIR [--services "control-plane portal"] [--commit SHA] [--caller LABEL] [--dry-run]
#
#   --context  build tree (the one holding backend/ frontend/ deploy/): the copy
#              checked out for THIS deploy, never the live tree
#   --dry-run  print every command instead of running it; do this first on an
#              unfamiliar box
#
# Exit codes: 0 = deployed and verified, 1 = usage/environment, 2 = an assertion
# failed (something did not move), 3 = another deploy held the lock.
set -eu

usage() {
    sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

# Defaults match the documented server layout (docs/deployment-and-operations.md);
# every one of them is overridable for a different host.
LIVE_DIR="${MIQROKEY_LIVE_DIR:-/opt/miqrokey}"
LOG="${MIQROKEY_DEPLOY_LOG:-$LIVE_DIR/deploy.log}"
LOCK="${MIQROKEY_DEPLOY_LOCK:-$LIVE_DIR/.deploy.lock}"
LOCK_WAIT="${MIQROKEY_DEPLOY_LOCK_WAIT:-600}"
TAG="${MIQROKEY_IMAGE_TAG:-local}"
# Compose derives its project name from the directory it is invoked in, so the
# same compose file points at a different stack depending on where the caller
# happens to stand: run it from elsewhere and `up` would create a SECOND set of
# containers next to the live ones instead of replacing them. Pinned here, along
# with the project directory, so the script is cwd-independent.
#
# The project directory is the COMPOSE FILE's directory — and that is load-bearing,
# not cosmetic: it is where `.env` is read from and where relative bind mounts
# (`./secrets/certs`) are anchored. Pinning it one level up swaps BOTH silently:
# every `${VAR:-default}` falls back to its default and the mounts point at
# directories Docker happily creates empty. The site then comes up on the wrong
# origin with no TLS certificate, and `up` still reports success (#802).
PROJECT="${MIQROKEY_COMPOSE_PROJECT:-miqrokey}"

CONTEXT=""
# Deliberately a plain list, not an array: this script is POSIX sh like its
# neighbours under scripts/.
SERVICES="control-plane portal gateway"
COMMIT=""
CALLER="$(id -un 2>/dev/null || echo unknown)@$(hostname 2>/dev/null || echo unknown)"
DRY=0
VERIFY_ONLY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --context) CONTEXT="${2:?--context needs a directory}"; shift 2 ;;
        --services) SERVICES="${2:?--services needs a list}"; shift 2 ;;
        --commit) COMMIT="${2:?--commit needs a value}"; shift 2 ;;
        --caller) CALLER="${2:?--caller needs a value}"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        # Check what is live without deploying anything — the same assertion, on
        # its own. Useful before a deploy ("what am I about to disturb?") and long
        # after one ("is this still what we deployed?").
        --verify-only) VERIFY_ONLY=1; shift ;;
        -h | --help) usage ;;
        *)
            echo "unknown argument: $1" >&2
            usage
            ;;
    esac
done

[ -n "$CONTEXT" ] || usage
[ -d "$CONTEXT" ] || { echo "build context not found: $CONTEXT" >&2; exit 1; }
COMPOSE="$LIVE_DIR/deploy/compose.prod.yaml"
[ -f "$COMPOSE" ] || { echo "compose file not found: $COMPOSE" >&2; exit 1; }
# The compose file's own directory, never its parent: `.env` is read from here and
# relative bind mounts are anchored here (see PROJECT above, and #802).
COMPOSE_DIR="$(dirname "$COMPOSE")"

run() {
    if [ "$DRY" = 1 ]; then
        echo "DRY  $*"
    else
        echo "+    $*"
        "$@"
    fi
}

# Every compose call goes through here so the project and its directory are pinned
# in one place (see PROJECT above).
compose() {
    docker compose -p "$PROJECT" --project-directory "$COMPOSE_DIR" -f "$COMPOSE" "$@"
}

# The container a service is actually running, asked of compose rather than
# assembled from the project name: the `<project>-<service>-1` convention depends
# on the directory the compose file happens to live in.
container_of() {
    compose ps -q "$1" 2>/dev/null | head -n 1
}

# ---- 1. one deploy at a time -------------------------------------------------
# A build and an `up` that interleave with someone else's leave a state nobody can
# explain afterwards; the lock makes the second caller queue instead. It covers
# the build too, because that is where the interleaving actually hurts: two builds
# of the same tag is what makes an image untraceable later.
exec 9>"$LOCK"
if [ "$DRY" = 1 ]; then
    echo "DRY  flock -w $LOCK_WAIT 9   ($LOCK)"
else
    # Checked here rather than up front so that --dry-run works on any machine —
    # its whole purpose is to be runnable before touching the box.
    command -v flock >/dev/null 2>&1 || { echo "flock not found (util-linux)" >&2; exit 1; }
    flock -w "$LOCK_WAIT" 9 || {
        echo "another deploy held $LOCK for ${LOCK_WAIT}s; not starting a second one" >&2
        exit 3
    }
fi

# ---- 2. build every requested service from the given tree --------------------
if [ "$VERIFY_ONLY" = 0 ]; then
    # The service list is intentionally unquoted: it is a whitespace-separated list.
    # shellcheck disable=SC2086
    for svc in $SERVICES; do
        dockerfile="$CONTEXT/deploy/docker/$svc.Dockerfile"
        [ -f "$dockerfile" ] || { echo "no Dockerfile for '$svc': $dockerfile" >&2; exit 1; }
        run docker build -q -f "$dockerfile" -t "miqrokey-$svc:$TAG" "$CONTEXT"
    done
fi

# ---- 3. swap the containers --------------------------------------------------
# --no-build: compose.prod.yaml gives control-plane a `build:` section whose
#   context is the LIVE tree, so an unguarded `up` may build from the tree we did
#   not just deploy and quietly ship the wrong code.
# --force-recreate: the tag now points at a new image; recreate rather than
#   relying on the engine to notice. The assertion below is what actually proves
#   it worked.
# shellcheck disable=SC2086
[ "$VERIFY_ONLY" = 1 ] || run compose up -d --no-build --force-recreate --no-deps $SERVICES

# ---- 4. assert what is running IS what was built -----------------------------
# This is the step that catches the silent half-swap. `Up N seconds (healthy)`
# proves nothing: it is exactly what the box shows when the container kept an
# older image.
failed=0
for svc in $SERVICES; do
    if [ "$DRY" = 1 ]; then
        # Not executed: a dry run must not claim a verification it did not do.
        echo "DRY  assert running $svc container == image miqrokey-$svc:$TAG"
        continue
    fi
    want="$(docker image inspect -f '{{.Id}}' "miqrokey-$svc:$TAG" 2>/dev/null || true)"
    got="$(docker inspect -f '{{.Image}}' "$(container_of "$svc")" 2>/dev/null || true)"
    if [ -z "$want" ] || [ -z "$got" ] || [ "$want" != "$got" ]; then
        echo "ASSERT FAILED $svc: running image '$got' != tag image '$want'" >&2
        failed=1
    else
        echo "verified $svc: $got"
    fi
done

# ---- 4b. assert the project directory actually took effect -------------------
# The image-identity check above cannot see a project directory that resolved to
# the wrong place: that swaps which `.env` is read and where the relative mounts
# point while every image stays exactly the one that was built. Both halves are
# asserted here, because both were silently wrong on the demo box once (#802).
#
# Skipped when the file it compares against does not exist: a box without certs or
# without an `.env` entry is a legitimate shape, and the assertion is about the
# value drifting away from the file — not about the file being mandatory.
assert_env_matches_file() {
    svc="$1"
    var="$2"
    [ -f "$COMPOSE_DIR/.env" ] || { echo "skipped $svc: no $COMPOSE_DIR/.env"; return 0; }
    want="$(grep -E "^$var=" "$COMPOSE_DIR/.env" | head -n 1 | cut -d= -f2-)"
    [ -n "$want" ] || { echo "skipped $svc: $var not set in $COMPOSE_DIR/.env"; return 0; }
    got="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(container_of "$svc")" 2>/dev/null \
        | grep -E "^$var=" | head -n 1 | cut -d= -f2-)"
    if [ "$got" != "$want" ]; then
        echo "ASSERT FAILED $svc: $var='$got' but $COMPOSE_DIR/.env says '$want'" >&2
        echo "  -> the project directory is not the compose file's directory; .env was not read" >&2
        failed=1
    else
        echo "verified $svc: $var taken from $COMPOSE_DIR/.env"
    fi
}

assert_mount_landed() {
    svc="$1"
    host_path="$2"
    container_path="$3"
    [ -s "$host_path" ] || { echo "skipped $svc: no $host_path on the host"; return 0; }
    if ! docker exec "$(container_of "$svc")" test -s "$container_path" 2>/dev/null; then
        echo "ASSERT FAILED $svc: $host_path exists but $container_path is missing in the container" >&2
        echo "  -> a relative mount resolved somewhere else (Docker creates missing bind sources empty)" >&2
        failed=1
    else
        echo "verified $svc: $container_path"
    fi
}

if [ "$DRY" = 0 ]; then
    for svc in $SERVICES; do
        case "$svc" in
            control-plane) assert_env_matches_file control-plane MIQROKEY_ORIGIN_ALLOWLIST ;;
            portal) assert_mount_landed portal "$COMPOSE_DIR/secrets/certs/fullchain.pem" /etc/nginx/certs/fullchain.pem ;;
        esac
    done
fi

# ---- 4c. functional smoke: proving the build is the right one is not the same as
# proving it was configured right, and the two silent failures above are exactly
# that kind — configuration, invisible to an image id.
#
# Unlike 4b, this one goes THROUGH the portal, so it must run after the upstream
# restart below: nginx resolves the service names once at start, and a backend
# recreated a moment ago answers 502 until the portal is restarted. Run it earlier
# and a perfectly good deploy fails its own smoke — the fix for the ordering is
# what makes the check trustworthy rather than flaky (review on #804).
#
# One request discriminates: `POST /api/v1/auth/login` with an empty body and the
# configured Origin answers 400 when everything is in place, 403 when the container
# holds a different allowlist, and 502/000 when nginx cannot reach the upstream it
# just had swapped underneath it. Nothing is authenticated and no credential is
# sent — the body is deliberately empty.
smoke_api_origin() {
    [ -f "$COMPOSE_DIR/.env" ] || { echo "skipped smoke: no $COMPOSE_DIR/.env"; return 0; }
    origin="$(grep -E '^MIQROKEY_ORIGIN_ALLOWLIST=' "$COMPOSE_DIR/.env" | head -n 1 | cut -d= -f2- | cut -d, -f1)"
    [ -n "$origin" ] || { echo "skipped smoke: no MIQROKEY_ORIGIN_ALLOWLIST in .env"; return 0; }
    command -v curl >/dev/null 2>&1 || { echo "skipped smoke: no curl"; return 0; }
    code="$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
        -H "Origin: $origin" -H 'Content-Type: application/json' -d '{}' \
        https://127.0.0.1/api/v1/auth/login || true)"
    case "${code:-000}" in
        403)
            echo "ASSERT FAILED smoke: API answers 403 to the configured Origin '$origin'" >&2
            echo "  -> the container's allowlist is not the one in $COMPOSE_DIR/.env" >&2
            failed=1
            ;;
        000 | 5*)
            echo "ASSERT FAILED smoke: API through the portal answered '${code:-000}'" >&2
            echo "  -> nginx cannot reach the upstream it was restarted against" >&2
            failed=1
            ;;
        *)
            echo "verified smoke: API answers $code to the configured Origin"
            ;;
    esac
}

# ---- 5. a swapped backend invalidates the portal's resolved upstream ---------
# nginx resolves the upstream names once, at start: after a backend container is
# replaced its address changes and every /api call answers 502 until nginx is
# restarted. Cheap here, expensive to rediscover.
if [ "$VERIFY_ONLY" = 0 ]; then
    case " $SERVICES " in
        *" control-plane "* | *" gateway "*)
            run compose restart portal
            ;;
    esac
fi

# ---- 5b. the smoke is the last assertion, and the only one that goes through the
# portal: until the restart above, nginx may still hold the previous upstream
# address, so running it earlier would blame a good build for a stale proxy.
if [ "$DRY" = 0 ]; then
    case " $SERVICES " in
        *" control-plane "* | *" portal "*) smoke_api_origin ;;
    esac
fi

# ---- 6. leave a durable trace ------------------------------------------------
# By the time anyone asks "what was live at 11:39", the container's image may be
# gone from `docker image ls` — a concurrent rebuild untags it and a prune can
# remove it. So this line, written while the answer is still knowable, is the only
# durable record. It stores the running image identities, not just the tag.
if [ "$DRY" = 0 ]; then
    {
        printf '%s mode=%s commit=%s caller=%s services="%s"' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$( [ "$VERIFY_ONLY" = 1 ] && echo verify || echo deploy )" "${COMMIT:-unknown}" "$CALLER" "$SERVICES"
        # shellcheck disable=SC2086
        for svc in $SERVICES; do
            printf ' %s_running=%s' "$svc" "$(docker inspect -f '{{.Image}}' "$(container_of "$svc")" 2>/dev/null || echo unknown)"
            # Both identities, not just the running one: if they ever differ later,
            # this is what says whether the tag moved after the deploy or the swap
            # never happened at all.
            printf ' %s_tag=%s' "$svc" "$(docker image inspect -f '{{.Id}}' "miqrokey-$svc:$TAG" 2>/dev/null || echo unknown)"
        done
        printf '\n'
    } >>"$LOG"
    echo "logged to $LOG"
fi

if [ "$failed" != 0 ]; then
    if [ "$VERIFY_ONLY" = 1 ]; then
        echo "verification failed — the box does not match the tags" >&2
    else
        echo "deployed, but an assertion failed — the box does not match the build" >&2
    fi
    exit 2
fi
if [ "$VERIFY_ONLY" = 1 ]; then
    echo "verified (nothing deployed): $SERVICES"
else
    echo "deployed and verified: $SERVICES"
fi
