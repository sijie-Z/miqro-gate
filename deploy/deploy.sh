#!/bin/sh
# One serialized, self-verifying deploy of the MiQroKey stack.
#
# Why a wrapper instead of ad-hoc `docker build` + `docker compose up`: this box is
# shared by several sessions, and the three failures we actually hit were
#   (a) two of them building the same tag at once, after which "which image is
#       live" could not be answered from the box's state at all,
#   (b) `up` reporting success while the container kept an older image, because
#       nothing ever compared the running container against the tag, and
#   (c) a container that came up healthy on the right image but with the WRONG
#       CONFIGURATION — the .env file was not loaded, so every variable silently
#       fell back to its compose default (#794 left the stack rejecting its own
#       origin with 403).
# So the run holds one lock, and it ends by asserting what it can actually know:
# the running container IS the image just built (a and b). That says nothing about
# correctness, which is why --smoke-url exists: give it something a real client
# would do (c) and the run checks that too. Without one, the closing line says
# what was and was not verified rather than claiming "verified".
#
# Usage:
#   deploy.sh --context DIR [--services "a b"] [--commit SHA] [--caller LABEL]
#             [--smoke-url URL] [--smoke-method METHOD] [--smoke-data BODY]
#             [--smoke-origin ORIGIN] [--smoke-expect PATTERN]
#             [--dry-run] [--verify-only]
#
#   --context      build tree (the one holding backend/ frontend/ deploy/): the
#                  copy checked out for THIS deploy, never the live tree
#   --smoke-url    something a real client would ask the running stack for; the
#                  run fails unless it answers as expected. The image assertions
#                  cannot tell a correct deployment from a healthy-looking wrong
#                  one, and this is what can.
#   --smoke-method HTTP method to use (default GET; the derived default target
#                  below is a POST, because the origin check guards only
#                  state-changing methods and a GET cannot observe a rejection)
#   --smoke-data   request body, sent as JSON (default none; the derived target
#                  sends {} so the request reaches the login route's validation)
#   --smoke-expect status pattern for that URL. '|' separates alternatives. The
#                  default depends on who chose the target: '2??|400|401' for the
#                  derived one, '2??' for a URL given here
#                  (--smoke-url defaults to the first MIQROKEY_ORIGIN_ALLOWLIST
#                   entry in the env file; pass an empty value to opt out)
#   --smoke-origin Origin header to send (the allowlist is config, so a request
#                  that exercises it is worth more than one that does not)
#   --dry-run      print every command instead of running it; do this first on an
#                  unfamiliar box
#   --verify-only  skip build and swap; run the assertions (and the smoke) only
#
# Exit codes: 0 = deployed and verified, 1 = usage/environment, 2 = an assertion
# failed (something did not move), 3 = another deploy held the lock.
set -eu

# Under Git Bash, MSYS rewrites arguments that look like absolute paths into `C:\...`
# before they reach docker. An in-container path such as /etc/nginx/certs would then
# be handed to the daemon as a Windows host path, and the assertions below would
# report a missing certificate that is really there — a false alarm of exactly the
# kind this script is supposed to eliminate. No effect on Linux.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

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
PROJECT="${MIQROKEY_COMPOSE_PROJECT:-miqrokey}"
# "auto" (the default) derives the target from the env file's allowlist below;
# an explicit value is used as given; an explicit empty one opts out.
SMOKE_URL="${MIQROKEY_DEPLOY_SMOKE_URL:-auto}"
# Left empty on purpose: the default differs by who chose the target, and the
# derived value below is only known after the allowlist has been read.
SMOKE_EXPECT="${MIQROKEY_DEPLOY_SMOKE_EXPECT:-}"
SMOKE_METHOD="${MIQROKEY_DEPLOY_SMOKE_METHOD:-}"
SMOKE_DATA="${MIQROKEY_DEPLOY_SMOKE_DATA:-}"
SMOKE_ORIGIN="${MIQROKEY_DEPLOY_SMOKE_ORIGIN:-}"
SMOKE_TIMEOUT="${MIQROKEY_DEPLOY_SMOKE_TIMEOUT:-20}"
# The project directory is the compose file's own directory, and the env file is
# named explicitly rather than left to compose's search order. Compose reads .env
# from the *project directory*; pointing that at the parent (#794 did) made it miss
# the file entirely and quietly fall back to the compose defaults — the stack came
# up healthy on the right image, and rejected its own origin with 403.
COMPOSE_DIR="$LIVE_DIR/deploy"
ENV_FILE="${MIQROKEY_ENV_FILE:-$COMPOSE_DIR/.env}"

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
        --smoke-url) SMOKE_URL="${2-}"; shift 2 ;;
        --smoke-method) SMOKE_METHOD="${2:?--smoke-method needs a method}"; shift 2 ;;
        --smoke-data) SMOKE_DATA="${2-}"; shift 2 ;;
        --smoke-expect) SMOKE_EXPECT="${2:?--smoke-expect needs a pattern}"; shift 2 ;;
        --smoke-origin) SMOKE_ORIGIN="${2:?--smoke-origin needs an origin}"; shift 2 ;;
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
COMPOSE="$COMPOSE_DIR/compose.prod.yaml"
[ -f "$COMPOSE" ] || { echo "compose file not found: $COMPOSE" >&2; exit 1; }
# Required, not optional: every deployment here overrides at least the origin
# allowlist through this file, and a missing one fails silently (compose just uses
# its defaults). Better to stop than to ship a stack nobody can log in to.
[ -f "$ENV_FILE" ] || {
    echo "env file not found: $ENV_FILE (set MIQROKEY_ENV_FILE to point elsewhere)" >&2
    exit 1
}

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
    docker compose -p "$PROJECT" --project-directory "$COMPOSE_DIR" --env-file "$ENV_FILE" -f "$COMPOSE" "$@"
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

# ---- 5. the certificates must be inside the container that serves them --------
# This is layer two of the #794 incident, and it is not about the .env at all: the
# relative `./secrets/certs` mount resolved against the wrong project directory, so
# Docker created an EMPTY directory at the host path and nginx crash-looped on a
# missing certificate — while `up` reported success and compose still called the
# container "created". Each other check misses it for its own reason: the image is
# correct (so section 4 is satisfied), certificates are not environment variables
# (so the .env echo never looks at them), and a portal that cannot start answers the
# smoke with 000, which is deliberately classified as weather rather than incident.
# So it needs its own assertion, offline and deterministic: whatever the host holds,
# the container that mounts it must hold the same bytes.
cert_dir="$COMPOSE_DIR/secrets/certs"
if [ -s "$cert_dir/fullchain.pem" ] && [ -s "$cert_dir/privkey.pem" ]; then
    cert_host="$(cd "$cert_dir" 2>/dev/null && pwd -P)"
    for svc in $SERVICES; do
        if [ "$DRY" = 1 ]; then
            echo "DRY  assert $svc sees $cert_dir inside the container"
            continue
        fi
        cid="$(container_of "$svc")"
        [ -n "$cid" ] || continue
        # Destination is read from the container rather than assumed: where the
        # certificates land is the compose file's decision, not this script's.
        cert_mounts="$(docker inspect -f '{{range .Mounts}}{{.Source}}>{{.Destination}}{{"\n"}}{{end}}' "$cid" 2>/dev/null || true)"
        while IFS= read -r m; do
            [ -n "$m" ] || continue
            src="${m%%>*}"
            dest="${m##*>}"
            case "$dest" in
                */certs) : ;;
                *) continue ;;
            esac
            src_real="$(cd "$src" 2>/dev/null && pwd -P || true)"
            if [ "$src_real" != "$cert_host" ]; then
                echo "ASSERT FAILED $svc: certificates mounted from '$src_real', not '$cert_host'" >&2
                echo "  (a relative mount that resolved elsewhere is how #794 lost them)" >&2
                failed=1
                continue
            fi
            svc_ok=1
            for f in fullchain.pem privkey.pem; do
                # Read the host file on stdin rather than passing its name: given a
                # name, coreutils escapes the whole output line — a leading
                # backslash, doubled separators — whenever the path contains a
                # backslash or a newline, and the hash cut out of that line is then
                # wrong. Passing no name at all removes the question.
                want_sum="$(sha256sum < "$cert_dir/$f" | cut -d' ' -f1)"
                got_sum="$(docker exec "$cid" sha256sum "$dest/$f" 2>/dev/null | cut -d' ' -f1 || true)"
                if [ -z "$got_sum" ]; then
                    # Covers both "missing/empty inside" and "cannot be read at
                    # all", which is what a crash-looping container looks like.
                    echo "ASSERT FAILED $svc: cannot read $dest/$f inside the container" >&2
                    failed=1
                    svc_ok=0
                elif [ "$got_sum" != "$want_sum" ]; then
                    echo "ASSERT FAILED $svc: $dest/$f differs from $cert_dir/$f" >&2
                    echo "  container: $got_sum" >&2
                    echo "  host     : $want_sum" >&2
                    failed=1
                    svc_ok=0
                fi
            done
            if [ "$svc_ok" = 1 ]; then
                echo "verified $svc: certificates present inside the container"
            fi
        done <<EOF
$cert_mounts
EOF
    done
else
    echo "note: $cert_dir has no certificates — nothing here checked that the stack" \
        "can terminate TLS" >&2
fi

# ---- 6. a swapped backend invalidates the portal's resolved upstream ---------
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

# What compose's dotenv would hand the container, rather than the file's raw bytes:
# a trailing CR, surrounding whitespace and one layer of quotes all disappear on
# the way in, so a comparison that keeps them is stricter than the system it checks.
# The demo box's mixed-EOL .env made exactly that mistake visible: `false\r` vs
# `false`, printed identically (#805).
norm_env_value() {
    printf '%s' "$1" | tr -d '\r' \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
              -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

# ---- 7. assert the container received the .env's values ------------------------
# The image assertions cannot see this either, and it is how #794 shipped a stack
# whose origin allowlist had silently fallen back to the compose default. Comparing
# the container against `compose config` would prove nothing (both read the same
# file, and so are wrong together); comparing it against what the file *says* does.
# Only keys the container already carries are checked: a variable the compose file
# does not pass to a service is legitimately absent from it.
if [ "$DRY" = 0 ]; then
    while IFS= read -r env_line; do
        case "$env_line" in
            '' | '#'*) continue ;;
        esac
        env_line="${env_line#export }"
        env_key="${env_line%%=*}"
        env_want="${env_line#*=}"
        [ "$env_key" != "$env_line" ] || continue
        # shellcheck disable=SC2086
        for svc in $SERVICES; do
            cid="$(container_of "$svc")"
            [ -n "$cid" ] || continue
            env_got="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$cid" 2>/dev/null \
                | sed -n "s/^${env_key}=//p" | head -n 1)"
            [ -n "$env_got" ] || continue
            got_n="$(norm_env_value "$env_got")"
            want_n="$(norm_env_value "$env_want")"
            [ "$got_n" != "$want_n" ] || continue
            echo "ASSERT FAILED $svc: $env_key differs from $ENV_FILE" >&2
            echo "  container: '$got_n' (len ${#got_n})" >&2
            echo "  env file : '$want_n' (len ${#want_n})" >&2
            if [ "${#got_n}" = "${#want_n}" ]; then
                # Same length: the two print alike closely enough to be unreadable, so
                # show the bytes. The difference may be invisible (a CR) or merely easy
                # to miss (F vs f) — either way the dump settles it.
                echo "  (same length — the bytes below are where they differ)" >&2
                printf '  container: ' >&2
                printf '%s' "$got_n" | od -c | head -n 2 >&2
                printf '  env file : ' >&2
                printf '%s' "$want_n" | od -c | head -n 2 >&2
            fi
            failed=1
        done
    done <"$ENV_FILE"
fi

# `|` alternation is split here rather than handed to `case`: a bar that arrives
# by expansion is a literal character in a case pattern — the separator is syntax
# only when it is in the script text — so a pattern like '200|405' previously
# matched nothing at all, and a 405 the operator had allowed still failed (#809).
smoke_code_matches() {
    smoke_pattern="$1"
    smoke_seen="$2"
    while [ -n "$smoke_pattern" ]; do
        smoke_alt="${smoke_pattern%%|*}"
        if [ "$smoke_alt" = "$smoke_pattern" ]; then
            smoke_pattern=""
        else
            smoke_pattern="${smoke_pattern#*|}"
        fi
        [ -n "$smoke_alt" ] || continue
        # Unquoted on purpose, and safe here: each alternative is a glob so that
        # `2??` keeps meaning "three digits" — quoting it would match the literal
        # text, and rewriting the whole pattern as an ERE would turn `2??` into
        # "an optional 2" (grep -E reads `?` as a quantifier). The bar is already
        # consumed above, so the trap SC2254 warns about cannot reach this line.
        # shellcheck disable=SC2254
        case "$smoke_seen" in
            $smoke_alt) return 0 ;;
        esac
    done
    return 1
}

# One request, described the same way wherever it is printed.
smoke_curl() {
    set -- -sS -o /dev/null -w '%{http_code}' --max-time "$SMOKE_TIMEOUT" -X "$SMOKE_METHOD"
    if [ -n "$SMOKE_ORIGIN" ]; then
        set -- "$@" -H "Origin: $SMOKE_ORIGIN"
    fi
    if [ -n "$SMOKE_DATA" ]; then
        set -- "$@" -H 'Content-Type: application/json' -d "$SMOKE_DATA"
    fi
    curl "$@" "$SMOKE_URL" 2>/dev/null || true
}

# ---- 8. smoke: can a real client actually use it? ----------------------------
# Sections 4 and 5 prove the right image is running. They say nothing about
# whether it is *correct*: a container can be healthy, on exactly the right image,
# and still reject its own origin with 403 because its .env was never loaded and
# every variable fell back to a compose default. Only asking the stack to serve a
# request catches that, so this step exists and does not pretend to be optional.
# The derived default has to be a request that *can* observe the failure this
# step exists for, and the obvious one cannot. OriginInterceptor exempts every
# method outside POST/PUT/PATCH/DELETE outright, and it is a HandlerInterceptor —
# it therefore runs after handler mapping, so a GET aimed at a POST-only route is
# answered 405 before the interceptor is consulted at all. An earlier revision
# defaulted to `GET $origin/`; measured on the demo box, that returns 200 even
# with a deliberately wrong Origin, which is to say the default target could never
# have reported ORIGIN_REJECTED (#809).
#
# So the derived default is a POST to the login route: it is the endpoint the #794
# outage actually broke (logins all 403), it is CSRF-exempt so nothing answers
# before the origin check, and an empty JSON body earns a 400 from validation — a
# code that proves the request reached the handler, i.e. that the origin was
# accepted. Measured on the demo box, the host reaches its own origin in ~20ms
# (hairpin works), so this stays a real check rather than a hope.
if [ "$SMOKE_URL" = "auto" ]; then
    SMOKE_URL=""
    auto_origin="$(sed -n 's/^MIQROKEY_ORIGIN_ALLOWLIST=//p' "$ENV_FILE" 2>/dev/null | head -n 1 | cut -d, -f1)"
    case "$auto_origin" in
        http*)
            SMOKE_URL="${auto_origin%/}/api/v1/auth/login"
            : "${SMOKE_ORIGIN:=${auto_origin%/}}"
            [ -n "$SMOKE_METHOD" ] || SMOKE_METHOD=POST
            [ -n "$SMOKE_DATA" ] || SMOKE_DATA='{}'
            [ -n "$SMOKE_EXPECT" ] || SMOKE_EXPECT='2??|400|401'
            echo "smoke: defaulted to $SMOKE_METHOD $SMOKE_URL (a GET cannot observe the origin check)"
            ;;
    esac
fi
# A target chosen by the operator keeps GET and '2??': pointing this script at an
# arbitrary URL and then insisting it accept a POST is precisely how a check ends
# up stricter than the thing it checks — the mistake #807 just fixed.
[ -n "$SMOKE_METHOD" ] || SMOKE_METHOD=GET
[ -n "$SMOKE_EXPECT" ] || SMOKE_EXPECT='2??'

if [ -z "$SMOKE_URL" ]; then
    echo "note: no smoke target given, and none could be derived from $ENV_FILE —" \
        "nothing here checked that the stack serves requests" >&2
elif [ "$DRY" = 1 ]; then
    smoke_desc="-X $SMOKE_METHOD"
    if [ -n "$SMOKE_ORIGIN" ]; then
        smoke_desc="$smoke_desc -H 'Origin: $SMOKE_ORIGIN'"
    fi
    if [ -n "$SMOKE_DATA" ]; then
        smoke_desc="$smoke_desc -H 'Content-Type: application/json' -d '$SMOKE_DATA'"
    fi
    echo "DRY  curl $smoke_desc $SMOKE_URL  (expect $SMOKE_EXPECT)"
else
    smoke_code="$(smoke_curl)"
    # curl prints the code even when it fails, and a hard failure can leave it
    # empty: anything that is not a three-digit code means "it did not answer".
    case "$smoke_code" in
        [0-9][0-9][0-9]) : ;;
        *) smoke_code=000 ;;
    esac

    # "Answered the wrong thing" and "could not be reached" are different findings
    # and are kept apart: the first is the incident #794 caused (a stack rejecting
    # its own origin), the second is weather — the box's route to its own public
    # origin. Failing the deploy on the second would dilute the first.
    if smoke_code_matches "$SMOKE_EXPECT" "$smoke_code"; then
        echo "smoke ok: $SMOKE_METHOD $SMOKE_URL -> $smoke_code"
    elif [ "$smoke_code" = "000" ]; then
        echo "smoke WARNING: $SMOKE_URL unreachable — not a failure, but nothing here" \
            "checked that the stack serves requests" >&2
    else
        echo "SMOKE FAILED: $SMOKE_METHOD $SMOKE_URL -> $smoke_code (expected $SMOKE_EXPECT)" >&2
        failed=1
    fi
fi

# ---- 9. leave a durable trace ------------------------------------------------
# By the time anyone asks "what was live at 11:39", the container's image may be
# gone from `docker image ls` — a concurrent rebuild untags it and a prune can
# remove it. So this line, written while the answer is still knowable, is the only
# durable record. It stores the running image identities, not just the tag.
if [ "$DRY" = 0 ]; then
    {
        printf '%s mode=%s commit=%s caller=%s env_file=%s smoke=%s services="%s"' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$( [ "$VERIFY_ONLY" = 1 ] && echo verify || echo deploy )" "${COMMIT:-unknown}" "$CALLER" "$ENV_FILE" "${smoke_code:-none}/${SMOKE_URL:-none}" "$SERVICES"
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
# Deliberately not "deployed and verified": that word claimed more than the run
# checked, and the gap is exactly how a config-less container got waved through.
if [ "$VERIFY_ONLY" = 1 ]; then
    echo "image identity verified (nothing deployed): $SERVICES"
else
    echo "deployed; image identity verified: $SERVICES"
fi
