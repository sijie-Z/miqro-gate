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
#                   mqk_api_… / consumer JWT for the mcp target.
#                   Prefer --key - to read it from stdin instead of the
#                   command line (argv lands in shell history and process lists)
#   --key -         read the key from stdin (first line)
#   --model ID      model id seeded into snippets (e.g. deepseek-flash)
#   --flavor F      anthropic (default) | openai — env/dotenv key naming
#   --shell S       posix (default) | cmd | powershell — print env only
#   --file PATH     apply only: target file
#                   (defaults: miqro-env.sh | .env; claude-settings requires it)
#   --mcp-url URL   mcp only: streamableHttpUrl copied from the MCP service page
#   --dry-run       apply only: print the result, write nothing
#   --verbose       verify only: also print the response body on failure
#
# Validation encodes the documented traps (docs/operations-runbook.md §14.1):
# credential planes do not interchange (uniform 401); a virtual key must carry
# its ".label" suffix (a bare mqk_live_ key answers the uniform 404
# virtual_key_invalid); the gateway base URL is an origin (a trailing /v1 is
# stripped with a note).
#
# Safety posture (review of #763): this tool GENERATES configuration that other
# programs execute or parse, so every emitted value is (a) restricted to a
# charset that is safe for its destination and (b) escaped for that
# destination's syntax. Validation alone is the boundary that is tested;
# escaping is the second layer. The key value is never echoed into a code
# comment and never required on the command line.
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
    sed -n '2,41p' "$0" | sed 's/^# \{0,1\}//'
}

# ---------- validation ----------

strip_trailing_slashes() {
    _s=$1
    while [ -n "$_s" ] && [ "${_s%/}" != "$_s" ]; do
        _s=${_s%/}
    done
    printf '%s' "$_s"
}

# require_charset LABEL VALUE ALLOWED_CLASS
# ALLOWED_CLASS is a bracket-expression body (e.g. 'A-Za-z0-9._-'); anything
# outside it is refused. Matching the complement, not "class then anything":
# a trailing * in a case pattern would happily swallow quotes and metacharacters.
require_charset() {
    _label=$1
    _value=$2
    [ -n "$_value" ] || die "$_label is empty"
    # shellcheck disable=SC2254
    case "$_value" in
        *[!${3}]*) die "$_label contains characters outside the allowed set ($3)" ;;
    esac
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
    # An origin is host[:port] and nothing else — no path, query, fragment or
    # userinfo (a userinfo would also leak into every snippet we emit).
    require_charset "gateway" "$_rest" 'A-Za-z0-9._:-'
    printf '%s' "$_g"
}

# validate_key KEY PLANE(v1|mcp)
validate_key() {
    _key=$1
    _plane=$2
    [ -n "$_key" ] || die "--key is required"
    require_charset "credential" "$_key" 'A-Za-z0-9._-'
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

validate_model() {
    [ -n "$1" ] || return 0
    require_charset "--model" "$1" 'A-Za-z0-9._:/-'
}

validate_mcp_url() {
    _u=$1
    case "$_u" in
        https://*) ;;
        *) die "--mcp-url must be https (got: $_u)" ;;
    esac
    _urest=${_u#https://}
    require_charset "--mcp-url" "$_urest" 'A-Za-z0-9._:/-'
}

# ---------- escaping (one per destination syntax) ----------

# POSIX shell: wrap in single quotes, close/reopen around any single quote.
sq_posix() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# PowerShell double-quoted string: backtick is the escape character.
dq_ps() {
    printf '"%s"' "$(printf '%s' "$1" | sed -e 's/`/``/g' -e 's/\$/`$/g' -e 's/"/`"/g')"
}

# JSON / TOML basic string body (callers add the surrounding quotes).
esc_json() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# cmd.exe has no quoting that survives every metacharacter; rather than emit
# a snippet that may do something else when pasted, refuse the value.
# (Each metacharacter gets its own pattern: an unquoted | or <> inside a
# bracket expression would be parsed as case syntax, not as bracket content.)
validate_cmd_safe() {
    _cv=$1
    case "$_cv" in
        *\&* | *\|* | *\<* | *\>* | *\^* | *%* | *!* | *\"* | *\\*)
            die "value cannot be emitted safely for cmd.exe (contains & | < > ^ % ! \" or a backslash): use --shell posix or --shell powershell (got: $_cv)"
            ;;
    esac
}

# ---------- snippet builders (mirror frontend/src/lib/ccswitch.ts) ----------

env_block() { # FLAVOR SHELL GATEWAY KEY
    case "$1:$2" in
        anthropic:posix)
            printf 'export ANTHROPIC_BASE_URL=%s\nexport ANTHROPIC_AUTH_TOKEN=%s\nexport CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\n' \
                "$(sq_posix "$3")" "$(sq_posix "$4")"
            ;;
        anthropic:cmd)
            validate_cmd_safe "$3"
            validate_cmd_safe "$4"
            printf 'set ANTHROPIC_BASE_URL=%s\nset ANTHROPIC_AUTH_TOKEN=%s\nset CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\n' "$3" "$4"
            ;;
        anthropic:powershell)
            printf '$env:ANTHROPIC_BASE_URL=%s\n$env:ANTHROPIC_AUTH_TOKEN=%s\n$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="1"\n' \
                "$(dq_ps "$3")" "$(dq_ps "$4")"
            ;;
        openai:posix)
            printf 'export OPENAI_BASE_URL=%s\nexport OPENAI_API_KEY=%s\n' \
                "$(sq_posix "$3/v1")" "$(sq_posix "$4")"
            ;;
        openai:cmd)
            validate_cmd_safe "$3"
            validate_cmd_safe "$4"
            printf 'set OPENAI_BASE_URL=%s/v1\nset OPENAI_API_KEY=%s\n' "$3" "$4"
            ;;
        openai:powershell)
            printf '$env:OPENAI_BASE_URL=%s\n$env:OPENAI_API_KEY=%s\n' \
                "$(dq_ps "$3/v1")" "$(dq_ps "$4")"
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
    printf '{\n  "env": {\n    "ANTHROPIC_BASE_URL": "%s",\n    "ANTHROPIC_AUTH_TOKEN": "%s",\n    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"\n  }\n}\n' \
        "$(esc_json "$1")" "$(esc_json "$2")"
}

codex_block() { # GATEWAY KEY MODEL
    # The key is deliberately NOT embedded (not even in a comment): a config
    # file gets grepped, indexed, backed up and attached to tickets, and a real
    # value in it would ride along every time. The env var is set separately.
    printf 'model_provider = "miqrokey"\nmodel = "%s"\n\n[model_providers.miqrokey]\nname = "MiQroKey"\nbase_url = "%s/v1"\nenv_key = "MIQROKEY_API_KEY"\nwire_api = "chat"\n\n# 然后设置环境变量（Windows CMD 用 set，PowerShell 用 $env:，POSIX 用 export）：\n#   MIQROKEY_API_KEY=<你的虚拟密钥，不要写进本文件>\n' \
        "$(esc_json "${3:-<已授权模型>}")" "$(esc_json "$1")"
}

openai_block() { # GATEWAY KEY MODEL
    printf 'Base URL: %s/v1\nAPI Key:  %s\n\n# 连通性自测（把 key 放进环境变量更安全）：\ncurl %s/v1/chat/completions \\\n  -H "Authorization: Bearer $%s" -H "Content-Type: application/json" \\\n  -d '"'"'{"model":"%s","messages":[{"role":"user","content":"hi"}]}'"'"'\n' \
        "$1" "$2" "$1" "OPENAI_API_KEY" "$(esc_json "${3:-<已授权模型>}")"
}

curl_block() { # GATEWAY KEY MODEL
    printf 'curl %s/v1/chat/completions \\\n  -H "Authorization: Bearer $MIQROKEY_API_KEY" -H "Content-Type: application/json" \\\n  -d '"'"'{"model":"%s","messages":[{"role":"user","content":"hi"}]}'"'"'\n\n# 先把密钥放进环境变量（避免进入 shell history 与进程列表）：\n#   export MIQROKEY_API_KEY=%s   # 本机自用可如此；CI 请用 secret 注入\n' \
        "$1" "$(esc_json "${3:-<已授权模型>}")" "$(sq_posix "$2")"
}

mcp_block() { # MCP_URL KEY
    printf '{\n  "mcpServers": {\n    "miqrogate": {\n      "type": "http",\n      "url": "%s",\n      "headers": { "Authorization": "Bearer %s" }\n    }\n  }\n}\n' \
        "$(esc_json "$1")" "$(esc_json "$2")"
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
    if ! chmod 600 "$_b" 2>/dev/null; then
        die "could not restrict permissions on the backup $_b — it holds a credential; refusing to report success"
    fi
    printf '%s' "$_b"
}

# refuse_symlink FILE — a symlinked target would be replaced by a regular file
# (the reference relationship would disappear silently).
refuse_symlink() {
    [ -L "$1" ] && die "refusing to write through a symlink: $1 (replace it with a regular file, or point --file at the real target)"
    return 0
}

# harden_and_warn FILE
# Every target we write embeds a credential: restrict permissions, and warn
# when the file sits in a git work tree without being ignored (mis-commit).
harden_and_warn() {
    if ! chmod 600 "$1" 2>/dev/null; then
        die "could not restrict permissions on $1 — it holds a credential; refusing to report success"
    fi
    _d=$(dirname "$1")
    # `git -C DIR` breaks when MSYS argument path conversion is switched off —
    # e.g. with MSYS_NO_PATHCONV=1 exported, as some harnesses do. The native
    # git then receives an unconverted `/tmp/...` or `/c/...`, exits 128, and the
    # `&&` below short-circuits, so the warning is silently skipped instead of
    # firing. A stock Git Bash converts the path and is unaffected. Let the shell
    # resolve it instead: correct under either setting. `CDPATH=` and `--` keep a
    # bare relative `_d` or a `-`-prefixed name from misdirecting `cd`.
    if command -v git >/dev/null 2>&1 && (CDPATH= cd -- "$_d" 2>/dev/null && git rev-parse --is-inside-work-tree >/dev/null 2>&1); then
        if ! (CDPATH= cd -- "$_d" 2>/dev/null && git check-ignore -q -- "$(basename "$1")" 2>/dev/null); then
            warn "$1 is inside a git work tree and not ignored — it now holds a credential; add it to .gitignore before committing"
        fi
    fi
}

# apply_managed_block FILE BLOCK_STRING
# Replaces the block between the managed markers, or appends it. The marker
# policy is deliberately strict: 0 markers => append; exactly one ordered pair
# => replace; anything else (an unmatched or duplicated marker) => refuse,
# because guessing there can leave a file that parsers read twice.
apply_managed_block() {
    _f=$1
    _block=$2
    refuse_symlink "$_f"
    _blkfile=$(mktemp)
    printf '%s' "$_block" >"$_blkfile"
    _dir=$(dirname "$_f")
    [ -d "$_dir" ] || mkdir -p "$_dir"
    # Same-directory temp file: the final replace must be an atomic rename.
    _new=$(mktemp "$_dir/.miqro-onboard.XXXXXX")
    if [ -f "$_f" ]; then
        _in=$_f
    else
        _in=/dev/null
    fi
    _markers=$(awk '
        /^# >>> miqro-onboard \(managed\) >>>$/ { s++ }
        /^# <<< miqro-onboard \(managed\) <<<$/ { e++ }
        END { printf "%d %d", s + 0, e + 0 }' "$_in")
    _starts=${_markers% *}
    _ends=${_markers#* }
    case "$_starts:$_ends" in
        0:0 | 1:1) ;;
        *) die "$_f has damaged managed-block markers ($_starts start, $_ends end) — expected either none or exactly one pair; fix the file or use a different --file" ;;
    esac
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
    refuse_symlink "$_f"
    _dir=$(dirname "$_f")
    [ -d "$_dir" ] || mkdir -p "$_dir"
    _tmp=$(mktemp "$_dir/.miqro-onboard.XXXXXX")
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
    _code=$(curl -sS -o "$_body" -w '%{http_code}' \
        --connect-timeout 5 --max-time 20 \
        -H "Authorization: Bearer $_key" "$_gw/v1/models" 2>/dev/null) ||
        die "request to $_gw/v1/models failed (network/TLS problem?)"
    _r=0
    case "$_code" in
        200)
            # 200 alone is not proof this is the gateway: a proxy, WAF or
            # error page can answer 200. The models list has a "data" array.
            if grep -q '"data"' "$_body" 2>/dev/null; then
                printf 'OK: HTTP 200 with a models-list body — the credential is accepted by the inference plane\n'
            else
                printf 'FAIL: HTTP 200 but the body is not a models list — check the URL points at the gateway itself (proxies and error pages also answer 200)\n' >&2
                _r=1
            fi
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
    if [ "$_r" = 1 ]; then
        _codehint=$(sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([A-Za-z0-9_]*\)".*/\1/p' "$_body" 2>/dev/null | head -n 1)
        [ -n "$_codehint" ] && printf 'code: %s\n' "$_codehint" >&2
        if [ "${VERBOSE:-0}" = 1 ]; then
            printf 'body: %s\n' "$(head -c 300 "$_body" 2>/dev/null)" >&2
        fi
    fi
    rm -f "$_body"
    return "$_r"
}

# ---------- argument parsing ----------

# Sourced by the test suite (all helpers defined above, dispatcher below not
# run) to exercise escaping/validation without touching a real target.
if [ "${MIQRO_ONBOARD_SOURCE_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

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
VERBOSE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --gateway) GATEWAY="${2:?--gateway needs a value}"; shift 2 ;;
        --key)
            KEY="${2:?--key needs a value (or '-' to read stdin)}"
            [ "$KEY" = "-" ] && { IFS= read -r KEY || true; }
            shift 2
            ;;
        --model) MODEL="${2:?--model needs a value}"; shift 2 ;;
        --mcp-url) MCP_URL="${2:?--mcp-url needs a value}"; shift 2 ;;
        --flavor) FLAVOR="${2:?--flavor needs a value}"; shift 2 ;;
        --shell) SHELLF="${2:?--shell needs a value}"; shift 2 ;;
        --file) FILE="${2:?--file needs a value}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --verbose) VERBOSE=1; shift ;;
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
validate_model "$MODEL"

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
        validate_mcp_url "$MCP_URL"
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
