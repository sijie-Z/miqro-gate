#!/bin/sh
# MiQroGate deployment diagnostic — read-only, redacted, paste-ready.
#
# The bug-report form asks for environment facts that people otherwise write by
# hand and get wrong or leave out (deployment shape, component versions, health,
# recent logs). This prints them as one report. Run it from the tree that holds
# deploy/ — the live tree on a server, or your checkout on a dev box:
#
#   sh deploy/diagnose.sh                    # Linux, macOS, Git Bash
#   powershell -ExecutionPolicy Bypass -File deploy\diagnose.ps1   # Windows
#
# Paste everything between the two DIAGNOSTIC markers into the issue's
# "诊断输出" field (.github/ISSUE_TEMPLATE/bug_report.yml points back here).
#
# What it deliberately does NOT do:
#   * write anything — no files, no temp dirs, no state changed anywhere;
#   * change any container — docker is only ever asked ps/inspect/logs/exec-read/
#     stats/system-df, and the regression harness pins that down;
#   * reach the network beyond loopback and the site origin already in deploy/.env.
#
# Every printed value passes through redact(): API-key shapes, Bearer/Basic
# credentials, URL passwords, KEY/SECRET/TOKEN assignments, private-key headers.
# Masking is pattern-based, so scan the output once more before posting — the
# issue form makes you confirm exactly that.
#
# Environment knobs (matching deploy.sh where they overlap):
#   MIQROKEY_LIVE_DIR            deployed tree (default: /opt/miqrokey when it
#                                exists, else the tree this script lives in)
#   MIQROKEY_ENV_FILE            compose env file (default: <live>/deploy/.env)
#   MIQROKEY_DIAGNOSE_LOG_LINES  log tail per container (default 40)
#
# Exit codes: 0 = report printed (whatever it found), 1 = usage error.
set -u

usage() {
    # Same trick as deploy.sh: print this file's own header, minus the set line.
    sed -n '2,/^set -u$/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 1
}

case "${1:-}" in
    -h | --help) usage ;;
    "") ;;
    *) echo "unknown argument: $1" >&2; usage ;;
esac

have() { command -v "$1" >/dev/null 2>&1; }

# ---- redaction ---------------------------------------------------------------
# Patterns chosen for what this product actually handles (provider API keys,
# Virtual Keys, database URLs, webhook secrets). Over-masking is accepted —
# a reader can always re-run with a narrower eye, but a leaked key cannot be
# un-posted. MIQROKEY_* names that are NOT secrets (IMAGE_TAG, ORIGIN_ALLOWLIST,
# …) must survive: masking that swallowed them would make the report useless.
redact() {
    sed -E \
        -e 's/sk-[A-Za-z0-9_-]{8,}/<masked-api-key>/g' \
        -e 's/AIza[0-9A-Za-z_-]{20,}/<masked-api-key>/g' \
        -e 's/AKIA[0-9A-Z]{16}/<masked-aws-key>/g' \
        -e 's/(ghp|gho|ghs|ghu)_[A-Za-z0-9]{10,}/<masked-token>/g' \
        -e 's/xox[baprs]-[A-Za-z0-9-]{8,}/<masked-token>/g' \
        -e 's/([Bb]earer |[Bb]asic )[A-Za-z0-9._~+\/=-]{8,}/\1<masked>/g' \
        -e 's#(://[^/@:[:space:]]+:)[^/@[:space:]]+@#\1<masked>@#g' \
        -e 's/([?&]([Kk]ey|[Tt]oken|[Ss]ecret|[Pp]assword|[Aa]ccess[_-]?[Tt]oken|[Aa]pi[_-]?[Kk]ey)=)[^&[:space:]"]+/\1<masked>/g' \
        -e 's/"([A-Za-z0-9_]*([Aa]uthorization|[Aa]pi[_-]?[Kk]ey|[Ss]ecret|[Pp]assword|[Pp]asswd|[Tt]oken|[Pp]epper|[Cc]redential)[A-Za-z0-9_]*)":[[:space:]]*"[^"]*"/"\1":"<masked>"/g' \
        -e 's/([A-Za-z0-9_]*([Ss]ecret|[Pp]assword|[Pp]asswd|[Tt]oken|[Pp]epper|[Cc]redential|[Aa]pi[_-]?[Kk]ey)[A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*[^[:space:]]+/\1=<masked>/g' \
        -e 's/-----BEGIN [A-Z ]*PRIVATE KEY-----/<masked-private-key>/g'
}

section() { printf '\n**%s**\n' "$1"; }
# A line that says what could not be learned, without pretending it is a result.
note() { printf '(%s)\n' "$1"; }

# ---- locate the tree, the live dir, the env file -----------------------------
# When piped (`curl … | sh`) $0 is the shell, not this file: fall back to the
# cwd and keep going — the generic sections are still worth printing.
# `cd` run in a command substitution with CDPATH set prints the destination to
# stdout, which would land inside the captured path — clearing it for the call
# is the point of the empty assignment below, not a typo.
# shellcheck disable=SC1007
abs_dir() { CDPATH= cd -- "$1" 2>/dev/null && pwd -P; }

case "${0##*/}" in
    sh | bash | dash | ash) SCRIPT_DIR=$(pwd -P) ;;
    *) SCRIPT_DIR=$(abs_dir "$(dirname -- "$0")") || SCRIPT_DIR=$(pwd -P) ;;
esac
if [ -f "$SCRIPT_DIR/compose.prod.yaml" ]; then
    TREE=$(abs_dir "$SCRIPT_DIR/..")
else
    SCRIPT_DIR="$SCRIPT_DIR/deploy"
    TREE=$(pwd -P)
fi

LIVE="${MIQROKEY_LIVE_DIR:-}"
if [ -z "$LIVE" ] && [ -f /opt/miqrokey/deploy/compose.prod.yaml ]; then
    LIVE=/opt/miqrokey
fi
if [ -z "$LIVE" ] && [ -f "$TREE/deploy/compose.prod.yaml" ]; then
    LIVE="$TREE"
fi
ENV_FILE="${MIQROKEY_ENV_FILE:-}"
if [ -z "$ENV_FILE" ] && [ -n "$LIVE" ]; then
    ENV_FILE="$LIVE/deploy/.env"
fi
LOG_LINES="${MIQROKEY_DIAGNOSE_LOG_LINES:-40}"

# `docker` on PATH or nothing: the report says which, instead of dying here.
DOCKER=""
have docker && DOCKER=docker

echo "--- MiQroGate DIAGNOSTIC ---"
printf 'generated: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
runner_user=$(id -un 2>/dev/null || whoami 2>/dev/null || echo unknown)
runner_host=$(hostname 2>/dev/null || uname -n 2>/dev/null || echo unknown)
printf 'runner: %s@%s\n' "$runner_user" "$runner_host"
printf 'script: %s\n' "$SCRIPT_DIR/diagnose.sh"

# ---- host --------------------------------------------------------------------
section "Host"
printf 'uname: %s\n' "$(uname -a 2>/dev/null || echo "unavailable")"
if [ -f /etc/os-release ]; then
    printf 'os-release: %s\n' "$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"')"
fi
printf 'arch: %s\n' "$(uname -m 2>/dev/null || echo unknown)"
if have free; then
    printf 'memory: %s\n' "$(free -h 2>/dev/null | awk 'NR==2 {print $2" total, "$7" available"}')"
fi
if have uptime; then
    printf 'uptime: %s\n' "$(uptime 2>/dev/null | sed 's/^ *//')"
fi
if have df; then
    printf 'disk: %s\n' "$(df -h "${LIVE:-/}" 2>/dev/null | awk 'NR==2 {print $2" total, "$4" avail ("$5" used) on "$6}')"
fi

# ---- docker ------------------------------------------------------------------
section "Docker"
if [ -n "$DOCKER" ]; then
    printf 'client: %s\n' "$(docker version --format '{{.Client.Version}}' 2>/dev/null || echo unknown)"
    printf 'server: %s\n' "$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo "unreachable — daemon down, or this user is not in the docker group (retry with sudo on a deployment host)")"
    printf 'compose: %s\n' "$(docker compose version --short 2>/dev/null || echo "not installed")"
    if have df; then
        root=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || true)
        if [ -n "$root" ]; then
            root_free=$(df -h "$root" 2>/dev/null | awk 'NR==2 {print $4" avail ("$5" used)"}')
            printf 'docker root: %s — %s\n' "$root" \
                "${root_free:-df cannot see this path (Docker Desktop VM path?)}"
        fi
    fi
    printf '\n%s\n' "$(docker system df 2>/dev/null || echo '(docker system df unavailable)')"
else
    note "docker not on PATH — stack sections below will be empty; run this on the deployment host for the full picture"
fi

# ---- checkout ----------------------------------------------------------------
section "Checkout"
printf 'tree: %s\n' "$TREE"
if git -C "$TREE" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    printf 'git: %s\n' "$(git -C "$TREE" log -1 --format='%h (%cs) %s' 2>/dev/null)"
    printf 'branch: %s\n' "$(git -C "$TREE" rev-parse --abbrev-ref HEAD 2>/dev/null)"
else
    note "not a git checkout (a synced live tree) — the deploy log below is the record of what is live"
fi
if [ -f "$TREE/CHANGELOG.md" ]; then
    printf 'changelog head: %s\n' "$(sed -n 's/^## //p' "$TREE/CHANGELOG.md" | head -n 1)"
fi

# ---- the stack ---------------------------------------------------------------
section "Stack"
if [ -n "$LIVE" ]; then
    printf 'live dir: %s\n' "$LIVE"
    printf 'compose file: %s\n' "$LIVE/deploy/compose.prod.yaml"
else
    note "no production tree found (no /opt/miqrokey, and this tree has no deploy/compose.prod.yaml) — static sections only"
fi
if [ -n "$DOCKER" ]; then
    printf '\ncontainers (compose project miqrokey / name miqrokey-*):\n'
    found=$( { docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' \
        --filter 'label=com.docker.compose.project=miqrokey' 2>/dev/null
        docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' \
        --filter 'name=miqrokey-' 2>/dev/null; } | sort -u)
    if [ -n "$found" ]; then
        printf '%s\n' "$found" | while IFS='|' read -r id name status image; do
            health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$id" 2>/dev/null || echo '?')
            digest=$(docker inspect -f '{{.Image}}' "$id" 2>/dev/null || echo '?')
            printf '  %s  %s  health=%s\n    image=%s\n    id=%s\n' "$name" "$status" "$health" "$image" "$digest"
        done
    else
        note "no miqrokey containers running on this host"
    fi
    printf '\ndocker stats (one snapshot):\n'
    docker stats --no-stream --format '  {{.Name}}  cpu={{.CPUPerc}}  mem={{.MemUsage}} ({{.MemPerc}})' 2>/dev/null \
        | grep -i miqrokey || note "no running miqrokey containers to sample"
fi
if [ -n "$LIVE" ] && [ -f "$LIVE/deploy.log" ]; then
    printf '\nlast deploy log lines (deploy.sh writes what is live here):\n'
    tail -n 3 "$LIVE/deploy.log" 2>/dev/null | while IFS= read -r l; do printf '  %s\n' "$l"; done
fi

# ---- probes ------------------------------------------------------------------
section "Probes"
probe() { # url -> "HTTP <code>", or how it failed
    if ! have curl; then
        echo "curl not installed"
        return
    fi
    code=$(curl -sS -k -o /dev/null -w '%{http_code}' --max-time 8 "$1" 2>/dev/null) || code=000
    case "$code" in
        000) echo "unreachable (curl code 000)" ;;
        *) echo "HTTP $code" ;;
    esac
}
if [ -n "$DOCKER" ]; then
    printf 'portal  http://127.0.0.1/healthz          %s\n' "$(probe 'http://127.0.0.1/healthz')"
    if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
        origin=$(tr -d '\r' < "$ENV_FILE" | sed -n 's/^MIQROKEY_ORIGIN_ALLOWLIST=//p' | head -n 1 | cut -d, -f1)
        if [ -n "$origin" ]; then
            printf 'site    %s/  %s\n' "$origin" "$(probe "$origin/")"
        fi
    fi
    for svc_and_port in "control-plane 8080 miqrokey-control-plane" "gateway 8081 miqrokey-gateway"; do
        # shellcheck disable=SC2086
        set -- $svc_and_port
        cid=$(docker ps -q --filter "name=$3" 2>/dev/null | head -n 1)
        if [ -n "$cid" ]; then
            out=$(docker exec "$cid" wget -qO- --timeout=5 "http://localhost:$2/actuator/health" 2>/dev/null | head -c 500)
            printf '%s  /actuator/health (in-container :%s)   %s\n' "$1" "$2" "${out:-no answer (container up but health did not reply)}"
        else
            printf '%s  /actuator/health   not running\n' "$1"
        fi
    done
    pg=$(docker ps -q --filter 'name=miqrokey-postgres' 2>/dev/null | head -n 1)
    if [ -n "$pg" ]; then
        pguser=miqrokey
        pgdb=miqrokey
        if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
            pguser=$(tr -d '\r' < "$ENV_FILE" | sed -n 's/^POSTGRES_USER=//p' | head -n 1)
            pgdb=$(tr -d '\r' < "$ENV_FILE" | sed -n 's/^POSTGRES_DB=//p' | head -n 1)
            [ -n "$pguser" ] || pguser=miqrokey
            [ -n "$pgdb" ] || pgdb=miqrokey
        fi
        printf 'postgres  pg_isready                   %s\n' \
            "$(docker exec "$pg" pg_isready -U "$pguser" -d "$pgdb" 2>&1 | head -n 1)"
    else
        printf 'postgres  pg_isready                   not running\n'
    fi
else
    note "docker not on PATH — nothing probed"
fi

# ---- secrets presence (never their values) -----------------------------------
section "Secrets (presence only)"
if [ -n "$LIVE" ] && [ -d "$LIVE/deploy/secrets" ]; then
    for f in master_key vk_hmac_key bootstrap_secret db_password backup_key; do
        p="$LIVE/deploy/secrets/$f"
        if [ -e "$p" ]; then
            printf '  %s: present (%s)\n' "$f" "$(ls -l "$p" 2>/dev/null | awk '{print $1", "$5" bytes"}')"
        else
            printf '  %s: MISSING\n' "$f"
        fi
    done
    for f in fullchain.pem privkey.pem; do
        p="$LIVE/deploy/secrets/certs/$f"
        if [ -e "$p" ]; then
            printf '  certs/%s: present\n' "$f"
        else
            printf '  certs/%s: not present\n' "$f"
        fi
    done
else
    note "no deploy/secrets directory at ${LIVE:-<no live dir>} — expected on a deployment host, absent on a dev box"
fi

# ---- configuration (whitelisted values, presence for the rest) ----------------
section "Configuration"
if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    keys=$(tr -d '\r' < "$ENV_FILE" | sed -n 's/^\(export \)\{0,1\}\([A-Za-z_][A-Za-z0-9_]*\)=.*/\2/p')
    printf 'env file: %s (%s keys)\n' "$ENV_FILE" "$(printf '%s\n' "$keys" | grep -c .)"
    printf '%s\n' "$keys" | while IFS= read -r k; do
        [ -n "$k" ] || continue
        v=$(tr -d '\r' < "$ENV_FILE" | sed -n "s/^\(export \)\{0,1\}${k}=//p" | head -n 1)
        case "$k" in
            # Values a maintainer needs to read, and which hold no secret.
            MIQROKEY_PUBLIC_BASE_URL | MIQROKEY_GATEWAY_BASE_URL | MIQROKEY_ORIGIN_ALLOWLIST | \
                MIQROKEY_IMAGE_TAG | MIQROKEY_REGISTRATION_ENABLED | TZ | COMPOSE_PROFILES | \
                MIQROKEY_CACHE_ENABLED | MIQROKEY_RETENTION_CONSUMER_ENABLED | \
                MIQROKEY_RETENTION_CONSUMER_BOOTSTRAP_SERVERS | \
                MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS | \
                MIQROKEY_USAGE_PRICE_RECONCILE_ENABLED | MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES | \
                MIQROKEY_CONTROL_ADMIN_IP_ALLOWLIST | POSTGRES_DB | POSTGRES_USER)
                printf '  %s=%s\n' "$k" "$v"
                ;;
            # Everything else: that it is set, never what it is. A webhook URL
            # memorised by heart is still a credential in its path.
            *) printf '  %s: defined\n' "$k" ;;
        esac
    done
else
    note "no env file${ENV_FILE:+ at $ENV_FILE} — the prod stack expects deploy/.env next to compose.prod.yaml"
fi

# ---- dev-machine context -----------------------------------------------------
section "Dev toolchain"
if have java; then
    printf 'java: %s\n' "$(java -version 2>&1 | head -n 1)"
    printf 'JAVA_HOME: %s\n' "${JAVA_HOME:-<unset>}"
else
    printf 'java: not on PATH\n'
fi
if have node; then
    printf 'node: %s  npm: %s\n' "$(node --version 2>/dev/null)" "$(npm --version 2>/dev/null || echo n/a)"
else
    printf 'node: not on PATH\n'
fi
printf 'listening dev ports:'
for port in 5432 8080 8081 5173; do
    listening=no
    case "$(uname -s 2>/dev/null)" in
        MINGW* | MSYS* | CYGWIN*)
            if have netstat; then
                netstat -an 2>/dev/null | awk '$1 == "TCP" && $4 == "LISTENING" {print $2}' \
                    | grep -qE "[:.]${port}\$" && listening=yes
            fi
            ;;
        *)
            if have ss; then
                ss -ltn 2>/dev/null | awk 'NR > 1 {print $4}' | grep -qE "[:.]${port}\$" && listening=yes
            elif have netstat; then
                netstat -an 2>/dev/null | grep LISTEN | awk '{print $4}' \
                    | grep -qE "[:.]${port}\$" && listening=yes
            fi
            ;;
    esac
    printf ' %s=%s' "$port" "$listening"
done
printf '\n'

# ---- logs --------------------------------------------------------------------
# Tail, redact, and cap. The whole report has to fit in one GitHub issue body
# (65536 chars), so each container's slice is bounded with a visible marker
# rather than letting one noisy container crowd out the others.
section "Logs (last ${LOG_LINES} lines each, redacted)"
if [ -n "$DOCKER" ]; then
    names=$( { docker ps -a --format '{{.Names}}' --filter 'label=com.docker.compose.project=miqrokey' 2>/dev/null
        docker ps -a --format '{{.Names}}' --filter 'name=miqrokey-' 2>/dev/null; } | sort -u)
    if [ -n "$names" ]; then
        for name in $names; do
            out=$(docker logs --tail "$LOG_LINES" "$name" 2>&1 | redact | sed 's/[[:space:]]*$//')
            printf '\n----- %s -----\n' "$name"
            if [ "${#out}" -gt 8000 ]; then
                printf '(truncated to the last 8000 characters)\n'
                printf '%s\n' "$(printf '%s' "$out" | tail -c 8000)"
            else
                printf '%s\n' "$out"
            fi
        done
    else
        note "no miqrokey containers — nothing to tail"
    fi
else
    note "docker not on PATH — no container logs"
fi

echo ""
echo "Review before pasting: masking is pattern-based; confirm nothing sensitive remains."
echo "--- END DIAGNOSTIC ---"
