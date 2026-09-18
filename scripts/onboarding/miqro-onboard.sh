#!/bin/sh
# MiQroGate onboarding injector — reference implementation (issue #742, slice 2).
#
# Points a client at the gateway: prints the copy-ready snippets for every
# covered client shape, applies the file-based forms idempotently (managed
# block or JSON merge, timestamped backup), and verifies the credential
# against the inference plane. Snippet shapes mirror the console
# 「使用密钥」panel (frontend/src/lib/ccswitch.ts) — keep the two in sync.
#
#   miqro-onboard.sh print <env|claude-settings|codex|openai|curl|mcp> [options]
#   miqro-onboard.sh apply <env|dotenv|claude-settings> [options]
#   miqro-onboard.sh verify [options]
#
# Common options:
#   --gateway URL   gateway origin, e.g. https://gw.example.com   (required)
#   --key KEY       mqk_live_… for inference targets;
#                   mqk_api_… / consumer JWT for the mcp target
#   --model ID      model id seeded into snippets (e.g. deepseek-flash)
#   --flavor F      anthropic (default) | openai — env/dotenv key naming
#   --shell S       posix (default) | cmd | powershell — print env only
#   --file PATH     apply only: target file
#                   (defaults: miqro-env.sh | .env; claude-settings requires it)
#   --mcp-url URL   mcp only: streamableHttpUrl copied from the MCP service page
#   --dry-run       apply only: print the result, write nothing
#
# Validation encodes the documented traps (docs/operations-runbook.md §14.1):
# credential planes do not interchange (uniform 401); a virtual key must carry
# its ".label" suffix (a bare mqk_live_ key answers the uniform 404
# virtual_key_invalid); the gateway base URL is an origin (a trailing /v1 is
# stripped with a note).
set -eu

PROG=miqro-onboard

die() {
    printf '%s: error: %s\n' "$PROG" "$1" >&2
    exit 1
}

warn() {
    printf '%s: warning: %s\n' "$PROG" "$1" >&2
}

usage() {
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
}

# ---------- validation ----------

strip_trailing_slashes() {
    _s=$1
    while [ -n "$_s" ] && [ "${_s%/}" != "$_s" ]; do
        _s=${_s%/}
    done
    printf '%s' "$_s"
}

normalize_gateway() {
    _g=$1
    [ -n "$_g" ] || die "--gateway is required"
    case "$_g" in
        https://*) ;;
        http://127.0.0.1 | http://127.0.0.1:* | http://localhost | http://localhost:*)
            warn "plain http is acceptable for local development only"
            ;;
        *) die "gateway must be an https origin (got: $_g)" ;;
    esac
    _g=$(strip_trailing_slashes "$_g")
    case "$_g" in
        */v1)
            warn "stripping the trailing /v1 — the base URL is the gateway origin; clients append /v1/…"
            _g=$(strip_trailing_slashes "${_g%/v1}")
            ;;
    esac
    _rest=${_g#*://}
    case "$_rest" in
        */*) die "gateway must not carry a path (got: $_g)" ;;
        '') die "gateway is empty" ;;
    esac
    printf '%s' "$_g"
}

# validate_key KEY PLANE(v1|mcp)
validate_key() {
    _key=$1
    _plane=$2
    [ -n "$_key" ] || die "--key is required"
    case "$_key" in
        mqk_live_*)
            case "$_key" in
                *.*) ;;
                *) die "virtual key is missing its .label suffix — the context-attribution parser requires mqk_live_<id>.<label>; a bare key answers the uniform 404 virtual_key_invalid" ;;
            esac
            _kplane=v1
            ;;
        mqk_api_*) _kplane=mcp ;;
        *.*.*) _kplane=mcp ;; # consumer JWT (three-segment RS256)
        *) die "unrecognized credential shape (expected mqk_live_…, mqk_api_… or a consumer JWT)" ;;
    esac
    [ "$_plane" = "$_kplane" ] || die "credential plane mismatch: this target needs a $_plane credential but got a $_kplane one — mixed use is a uniform 401 (operations-runbook §14.1)"
}

# ---------- snippet builders (mirror frontend/src/lib/ccswitch.ts) ----------

env_block() { # FLAVOR SHELL GATEWAY KEY
    case "$1:$2" in
        anthropic:posix)
            printf 'export ANTHROPIC_BASE_URL="%s"\nexport ANTHROPIC_AUTH_TOKEN="%s"\nexport CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\n' "$3" "$4"
            ;;
        anthropic:cmd)
            printf 'set ANTHROPIC_BASE_URL=%s\nset ANTHROPIC_AUTH_TOKEN=%s\nset CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\n' "$3" "$4"
            ;;
        anthropic:powershell)
            printf '$env:ANTHROPIC_BASE_URL="%s"\n$env:ANTHROPIC_AUTH_TOKEN="%s"\n$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="1"\n' "$3" "$4"
            ;;
        openai:posix)
            printf 'export OPENAI_BASE_URL="%s/v1"\nexport OPENAI_API_KEY="%s"\n' "$3" "$4"
            ;;
        openai:cmd)
            printf 'set OPENAI_BASE_URL=%s/v1\nset OPENAI_API_KEY=%s\n' "$3" "$4"
            ;;
        openai:powershell)
            printf '$env:OPENAI_BASE_URL="%s/v1"\n$env:OPENAI_API_KEY="%s"\n' "$3" "$4"
            ;;
        *) die "unsupported flavor/shell combination: $1/$2 (flavor=anthropic|openai, shell=posix|cmd|powershell)" ;;
    esac
}

dotenv_block() { # FLAVOR GATEWAY KEY (dotenv is key=value — no export, no quotes)
    case "$1" in
        anthropic) printf 'ANTHROPIC_BASE_URL=%s\nANTHROPIC_AUTH_TOKEN=%s\nCLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\n' "$2" "$3" ;;
        openai) printf 'OPENAI_BASE_URL=%s/v1\nOPENAI_API_KEY=%s\n' "$2" "$3" ;;
        *) die "unsupported flavor: $1 (anthropic|openai)" ;;
    esac
}

claude_settings_block() { # GATEWAY KEY
    printf '{\n  "env": {\n    "ANTHROPIC_BASE_URL": "%s",\n    "ANTHROPIC_AUTH_TOKEN": "%s",\n    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"\n  }\n}\n' "$1" "$2"
}

codex_block() { # GATEWAY KEY MODEL
    printf 'model_provider = "miqrokey"\nmodel = "%s"\n\n[model_providers.miqrokey]\nname = "MiQroKey"\nbase_url = "%s/v1"\nenv_key = "MIQROKEY_API_KEY"\nwire_api = "chat"\n\n# 然后设置环境变量（Windows CMD 用 set，PowerShell 用 $env:）：\n#   MIQROKEY_API_KEY=%s\n' "${3:-<已授权模型>}" "$1" "$2"
}

openai_block() { # GATEWAY KEY MODEL
    printf 'Base URL: %s/v1\nAPI Key:  %s\n\n# 连通性自测：\ncurl %s/v1/chat/completions \\\n  -H "Authorization: Bearer %s" -H "Content-Type: application/json" \\\n  -d '"'"'{"model":"%s","messages":[{"role":"user","content":"hi"}]}'"'"'\n' "$1" "$2" "$1" "$2" "${3:-<已授权模型>}"
}

curl_block() { # GATEWAY KEY MODEL
    printf 'curl %s/v1/chat/completions \\\n  -H "Authorization: Bearer %s" -H "Content-Type: application/json" \\\n  -d '"'"'{"model":"%s","messages":[{"role":"user","content":"hi"}]}'"'"'\n' "$1" "$2" "${3:-<已授权模型>}"
}

mcp_block() { # MCP_URL KEY
    printf '{\n  "mcpServers": {\n    "miqrogate": {\n      "type": "http",\n      "url": "%s",\n      "headers": { "Authorization": "Bearer %s" }\n    }\n  }\n}\n' "$1" "$2"
}

# ---------- file injection ----------

# backup_file FILE -> path
# Timestamped backup; a second change within the same second must not
# silently overwrite the previous generation, so the name disambiguates.
backup_file() {
    _b=$1.bak-$(date -u +%Y%m%dT%H%M%SZ)
    _n=1
    while [ -e "$_b" ]; do
        _n=$((_n + 1))
        _b=$1.bak-$(date -u +%Y%m%dT%H%M%SZ)-$_n
    done
    cp "$1" "$_b"
    chmod 600 "$_b" 2>/dev/null || true # backups hold the credential too
    printf '%s' "$_b"
}

# harden_and_warn FILE
# Every target we write embeds a credential: restrict permissions, and warn
# when the file sits in a git work tree without being ignored (mis-commit).
harden_and_warn() {
    chmod 600 "$1" 2>/dev/null || true
    _d=$(dirname "$1")
    if command -v git >/dev/null 2>&1 && git -C "$_d" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        if ! git -C "$_d" check-ignore -q "$(basename "$1")" 2>/dev/null; then
            warn "$1 is inside a git work tree and not ignored — it now holds a credential; add it to .gitignore before committing"
        fi
    fi
}

# apply_managed_block FILE BLOCK_STRING LABEL
# Replaces the block between the managed markers, or appends it.
# Backup before write; reports "unchanged" when the result is identical.
apply_managed_block() {
    _f=$1
    _block=$2
    _blkfile=$(mktemp)
    printf '%s' "$_block" >"$_blkfile"
    _new=$(mktemp)
    if [ -f "$_f" ]; then
        _in=$_f
    else
        _in=/dev/null
    fi
    awk -v blockfile="$_blkfile" '
        BEGIN { while ((getline l < blockfile) > 0) block = block l "\n" }
        { lines[NR] = $0 }
        END {
            s = 0; e = 0
            for (i = 1; i <= NR; i++) {
                if (lines[i] == "# >>> miqro-onboard (managed) >>>") s = i
                else if (lines[i] == "# <<< miqro-onboard (managed) <<<") e = i
            }
            if (s > 0 && e > s) {
                for (i = 1; i <= s; i++) print lines[i]
                printf "%s", block
                for (i = e; i <= NR; i++) print lines[i]
            } else {
                for (i = 1; i <= NR; i++) print lines[i]
                if (NR > 0) print ""
                print "# >>> miqro-onboard (managed) >>>"
                printf "%s", block
                print "# <<< miqro-onboard (managed) <<<"
            }
        }' "$_in" >"$_new"
    if [ -f "$_f" ] && cmp -s "$_new" "$_f"; then
        printf 'unchanged: %s\n' "$_f"
        rm -f "$_blkfile" "$_new"
        return 0
    fi
    if [ "$DRY_RUN" = 1 ]; then
        printf -- '--- %s (dry-run, not written) ---\n' "$_f"
        cat "$_new"
        rm -f "$_blkfile" "$_new"
        return 0
    fi
    _dir=$(dirname "$_f")
    [ -d "$_dir" ] || mkdir -p "$_dir"
    if [ -f "$_f" ]; then
        printf 'backup: %s\n' "$(backup_file "$_f")"
    fi
    mv "$_new" "$_f"
    harden_and_warn "$_f"
    printf 'wrote: %s\n' "$_f"
    rm -f "$_blkfile"
}

# apply_claude_settings FILE GATEWAY KEY
apply_claude_settings() {
    _f=$1
    _gw=$2
    _key=$3
    [ -n "$_f" ] || die "apply claude-settings requires --file (refusing to guess at a live config path)"
    command -v jq >/dev/null 2>&1 || die "apply claude-settings needs jq for a safe JSON merge; use 'print claude-settings' and paste it manually instead"
    _tmp=$(mktemp)
    if [ -f "$_f" ]; then
        jq -e . "$_f" >/dev/null 2>&1 || die "$_f is not valid JSON — fix it first (no write performed)"
        jq --arg url "$_gw" --arg tok "$_key" \
            '.env = ((.env // {}) + {ANTHROPIC_BASE_URL: $url, ANTHROPIC_AUTH_TOKEN: $tok, CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1"})' \
            "$_f" >"$_tmp" || die "jq merge failed (no write performed)"
    else
        claude_settings_block "$_gw" "$_key" | jq . >"$_tmp" || die "jq failed"
    fi
    if [ -f "$_f" ] && cmp -s "$_tmp" "$_f"; then
        printf 'unchanged: %s\n' "$_f"
        rm -f "$_tmp"
        return 0
    fi
    if [ "$DRY_RUN" = 1 ]; then
        printf -- '--- %s (dry-run, not written) ---\n' "$_f"
        cat "$_tmp"
        rm -f "$_tmp"
        return 0
    fi
    _dir=$(dirname "$_f")
    [ -d "$_dir" ] || mkdir -p "$_dir"
    if [ -f "$_f" ]; then
        printf 'backup: %s\n' "$(backup_file "$_f")"
    fi
    mv "$_tmp" "$_f"
    harden_and_warn "$_f"
    printf 'wrote: %s\n' "$_f"
}

# ---------- verify ----------

verify_gateway() {
    _gw=$1
    _key=$2
    _body=$(mktemp)
    _code=$(curl -sS -o "$_body" -w '%{http_code}' -H "Authorization: Bearer $_key" "$_gw/v1/models" 2>/dev/null) ||
        die "request to $_gw/v1/models failed (network/TLS problem?)"
    case "$_code" in
        200)
            printf 'OK: HTTP 200 — the credential is accepted by the inference plane\n'
            ;;
        404)
            printf 'FAIL: HTTP 404 — check the .label suffix and that the key was not revoked (uniform virtual_key_invalid)\n' >&2
            _r=1
            ;;
        401)
            printf 'FAIL: HTTP 401 — wrong credential plane or expired credential\n' >&2
            _r=1
            ;;
        *)
            printf 'FAIL: HTTP %s\n' "$_code" >&2
            _r=1
            ;;
    esac
    if [ "${_r:-0}" = 1 ] && [ -s "$_body" ]; then
        printf 'body: %s\n' "$(head -c 300 "$_body")" >&2
    fi
    rm -f "$_body"
    return "${_r:-0}"
}

# ---------- argument parsing ----------

[ $# -gt 0 ] || { usage; exit 2; }
CMD=$1
shift
TARGET=""
case "$CMD" in
    print | apply)
        [ $# -gt 0 ] || die "$CMD requires a target (see usage)"
        TARGET=$1
        shift
        ;;
    verify) ;;
    help | -h | --help)
        usage
        exit 0
        ;;
    *) die "unknown command: $CMD (print|apply|verify)" ;;
esac

GATEWAY=""
KEY=""
MODEL=""
MCP_URL=""
FLAVOR=anthropic
SHELLF=posix
FILE=""
DRY_RUN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --gateway) GATEWAY=${2:-}; shift 2 ;;
        --key) KEY=${2:-}; shift 2 ;;
        --model) MODEL=${2:-}; shift 2 ;;
        --mcp-url) MCP_URL=${2:-}; shift 2 ;;
        --flavor) FLAVOR=${2:-}; shift 2 ;;
        --shell) SHELLF=${2:-}; shift 2 ;;
        --file) FILE=${2:-}; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) die "unknown option: $1" ;;
    esac
done
case "$SHELLF" in
    posix | cmd | powershell) ;;
    *) die "--shell must be posix|cmd|powershell" ;;
esac
case "$FLAVOR" in
    anthropic | openai) ;;
    *) die "--flavor must be anthropic|openai" ;;
esac
GW=$(normalize_gateway "$GATEWAY")

# ---------- dispatch ----------

if [ "$CMD" = verify ]; then
    validate_key "$KEY" v1
    verify_gateway "$GW" "$KEY"
    exit $?
fi

case "$CMD:$TARGET" in
    print:env)
        validate_key "$KEY" v1
        env_block "$FLAVOR" "$SHELLF" "$GW" "$KEY"
        ;;
    print:claude-settings)
        validate_key "$KEY" v1
        claude_settings_block "$GW" "$KEY"
        ;;
    print:codex)
        validate_key "$KEY" v1
        codex_block "$GW" "$KEY" "$MODEL"
        ;;
    print:openai)
        validate_key "$KEY" v1
        openai_block "$GW" "$KEY" "$MODEL"
        ;;
    print:curl)
        validate_key "$KEY" v1
        curl_block "$GW" "$KEY" "$MODEL"
        ;;
    print:mcp)
        [ -n "$MCP_URL" ] || die "print mcp requires --mcp-url (copy streamableHttpUrl from the MCP service page)"
        validate_key "$KEY" mcp
        mcp_block "$MCP_URL" "$KEY"
        ;;
    apply:env)
        validate_key "$KEY" v1
        FILE=${FILE:-miqro-env.sh}
        apply_managed_block "$FILE" "$(env_block "$FLAVOR" posix "$GW" "$KEY")"
        ;;
    apply:dotenv)
        validate_key "$KEY" v1
        FILE=${FILE:-.env}
        apply_managed_block "$FILE" "$(dotenv_block "$FLAVOR" "$GW" "$KEY")"
        ;;
    apply:claude-settings)
        validate_key "$KEY" v1
        apply_claude_settings "$FILE" "$GW" "$KEY"
        ;;
    *) die "unknown $CMD target: $TARGET (see usage)" ;;
esac
