#!/bin/sh
# Tests for scripts/onboarding/miqro-onboard.sh (issue #742, slice 2; review of #763).
# Pure POSIX sh, no network: `verify` runs against a fake curl on PATH, and the
# escaping/validation helpers are sourced directly via MIQRO_ONBOARD_SOURCE_ONLY.
set -u

DIR=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$DIR/miqro-onboard.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$1"
}

bad() {
    FAIL=$((FAIL + 1))
    printf 'FAIL %s\n' "$1"
}

# portable file mode (GNU stat, then BSD/macOS stat)
file_mode() {
    stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1" 2>/dev/null || printf '?'
}

assert_status() {
    if [ "$ST" = "$1" ]; then ok "$2"; else bad "$2 (status=$ST, expected $1)"; fi
}

assert_contains() {
    case "$OUT" in
        *"$1"*) ok "$2" ;;
        *) bad "$2 (missing: $1)"; printf '    got: %s\n' "$OUT" ;;
    esac
}

assert_not_contains() {
    case "$OUT" in
        *"$1"*) bad "$2 (unexpected: $1)" ;;
        *) ok "$2" ;;
    esac
}

run() {
    if OUT=$("$@" 2>&1); then ST=0; else ST=$?; fi
}

GW=https://gw.example.com
VK='mqk_live_AbCdEfGhIjKlMnOpQrStUv_XXXXXXXX.demo'

# ============ escaping helpers (sourced seam) ============

export MIQRO_ONBOARD_SOURCE_ONLY=1
# shellcheck disable=SC1090
. "$SCRIPT"

OUT=$(sq_posix "a'b")
case "$OUT" in
    "'a'\\''b'") ok "sq_posix escapes embedded single quotes" ;;
    *) bad "sq_posix escapes embedded single quotes (got: $OUT)" ;;
esac
OUT=$(sq_posix 'plain-value')
case "$OUT" in
    "'plain-value'") ok "sq_posix wraps in single quotes" ;;
    *) bad "sq_posix wraps in single quotes (got: $OUT)" ;;
esac
OUT=$(dq_ps 'a$b"c`d')
case "$OUT" in
    '"a`$b`"c``d"') ok "dq_ps escapes \$ \" and backtick for PowerShell" ;;
    *) bad "dq_ps escapes for PowerShell (got: $OUT)" ;;
esac
OUT=$(esc_json 'a\b"c')
case "$OUT" in
    'a\\b\"c') ok "esc_json escapes backslash and quote" ;;
    *) bad "esc_json escapes backslash and quote (got: $OUT)" ;;
esac
if OUT=$(validate_cmd_safe 'a&b' 2>&1); then ST=0; else ST=$?; fi
assert_status 1 "validate_cmd_safe rejects cmd metacharacters"
assert_contains "cmd.exe" "the cmd limitation is explained"
unset MIQRO_ONBOARD_SOURCE_ONLY

# ============ print ============

run sh "$SCRIPT" print env --gateway "$GW" --key "$VK"
assert_status 0 "print env (posix) exits 0"
assert_contains "export ANTHROPIC_BASE_URL='https://gw.example.com'" "print env emits base URL (single-quoted)"
assert_contains "export ANTHROPIC_AUTH_TOKEN='$VK'" "print env emits key (single-quoted)"
assert_contains "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" "print env disables nonessential traffic"

run sh "$SCRIPT" print env --gateway "$GW" --key "$VK" --shell cmd
assert_status 0 "print env (cmd) exits 0"
assert_contains "set ANTHROPIC_BASE_URL=https://gw.example.com" "cmd flavor uses set"

run sh "$SCRIPT" print env --gateway "$GW" --key "$VK" --shell powershell --flavor openai
assert_status 0 "print env (powershell/openai) exits 0"
assert_contains '$env:OPENAI_BASE_URL="https://gw.example.com/v1"' "openai powershell form includes /v1"

run sh "$SCRIPT" print curl --gateway "$GW" --key "$VK" --model deepseek-flash
assert_status 0 "print curl exits 0"
assert_contains "curl https://gw.example.com/v1/chat/completions" "curl targets chat completions"
assert_contains 'Bearer $MIQROKEY_API_KEY' "curl references the key through an env var (not inline)"
assert_not_contains "Bearer $VK" "curl no longer embeds the raw key in the command"
assert_contains '"model":"deepseek-flash"' "curl seeds the model"

run sh "$SCRIPT" print codex --gateway "$GW" --key "$VK" --model deepseek-flash
assert_status 0 "print codex exits 0"
assert_not_contains "$VK" "codex snippet does NOT contain the key (comment removed)"
assert_contains 'env_key = "MIQROKEY_API_KEY"' "codex keeps the env_key indirection"
assert_contains "不要写进本文件" "codex tells the user where the key belongs"

run sh "$SCRIPT" print claude-settings --gateway "$GW" --key "$VK"
assert_status 0 "print claude-settings exits 0"
assert_contains '"ANTHROPIC_AUTH_TOKEN"' "settings snippet carries the token field"

run sh "$SCRIPT" print mcp --gateway "$GW" --key "$VK" --mcp-url https://gw.example.com/mcpservers/demo/mcp
assert_status 1 "print mcp refuses a virtual key (wrong plane)"
assert_contains "plane mismatch" "mcp plane mismatch is explained"

run sh "$SCRIPT" print mcp --gateway "$GW" --key 'mqk_api_ZZZ' --mcp-url https://gw.example.com/mcpservers/demo/mcp
assert_status 0 "print mcp accepts a consumer key"
assert_contains '"url": "https://gw.example.com/mcpservers/demo/mcp"' "mcp emits the streamable URL"
assert_contains 'Bearer mqk_api_ZZZ' "mcp emits the consumer credential"

# ============ validation (malicious / malformed input) ============

run sh "$SCRIPT" print curl --gateway "$GW" --key 'mqk_live_bareKeyWithoutLabel'
assert_status 1 "bare virtual key (no .label) is refused"
assert_contains ".label" "the .label trap is named"

run sh "$SCRIPT" print env --gateway "$GW" --key 'mqk_live_ok.OK"evil'
assert_status 1 "key containing a double quote is refused"
assert_contains "allowed set" "the charset rule is named"

run sh "$SCRIPT" print env --gateway "$GW" --key "mqk_live_ok.OK'evil"
assert_status 1 "key containing a single quote is refused"

run sh "$SCRIPT" print env --gateway "$GW" --key 'mqk_live_ok.OK$(evil)'
assert_status 1 "key containing command substitution is refused"

run sh "$SCRIPT" print curl --gateway "$GW" --key "$VK" --model 'x;rm -rf /'
assert_status 1 "model containing a shell metacharacter is refused"
assert_contains "--model" "the offending option is named"

run sh "$SCRIPT" print curl --gateway https://user@host --key "$VK"
assert_status 1 "gateway with userinfo is refused"

run sh "$SCRIPT" print curl --gateway 'https://gw.example.com?x=1' --key "$VK"
assert_status 1 "gateway with a query is refused"

run sh "$SCRIPT" print mcp --gateway "$GW" --key 'mqk_api_X' --mcp-url 'https://gw.example.com/mcp"x'
assert_status 1 "mcp-url containing a quote is refused"

run sh "$SCRIPT" print env --gateway http://example.com --key "$VK"
assert_status 1 "plain http to a non-local host is refused"

run sh "$SCRIPT" print env --gateway https://gw.example.com/v1/ --key "$VK"
assert_status 0 "trailing /v1/ is normalized"
assert_contains "export ANTHROPIC_BASE_URL='https://gw.example.com'" "normalized origin is used"

run sh "$SCRIPT" print env --gateway
if [ "$ST" != 0 ]; then ok "missing option value is refused (non-zero: $ST)"; else bad "missing option value is refused"; fi
assert_contains "--gateway needs a value" "the missing value is named"

# key via stdin (keeps it out of argv / shell history)
OUT=$(printf '%s\n' "$VK" | sh "$SCRIPT" print env --gateway "$GW" --key - 2>&1)
case "$OUT" in
    *"$VK"*) ok "--key - reads the credential from stdin" ;;
    *) bad "--key - reads the credential from stdin (got: $OUT)" ;;
esac

# ============ apply dotenv ============

ENVF="$TMP/.env"
printf 'SOME_OTHER=1\n' >"$ENVF"
run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$ENVF"
assert_status 0 "apply dotenv exits 0"
CONTENT=$(cat "$ENVF")
case "$CONTENT" in
    *SOME_OTHER=1*) ok "apply dotenv preserves unrelated lines" ;;
    *) bad "apply dotenv preserves unrelated lines" ;;
esac
case "$CONTENT" in
    *OPENAI_BASE_URL=https://gw.example.com/v1*) ok "apply dotenv writes the base URL" ;;
    *) bad "apply dotenv writes the base URL" ;;
esac
case "$CONTENT" in
    *"OPENAI_API_KEY=$VK"*) ok "apply dotenv writes the key" ;;
    *) bad "apply dotenv writes the key" ;;
esac

run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$ENVF"
assert_status 0 "second apply exits 0"
assert_contains "unchanged: $ENVF" "second apply is idempotent"
BODY_LINES=$(grep -c 'OPENAI_API_KEY=' "$ENVF")
[ "$BODY_LINES" = 1 ] && ok "no duplicated keys after re-apply" || bad "no duplicated keys after re-apply (found $BODY_LINES)"
BAK1=$(ls "$ENVF".bak-* 2>/dev/null | wc -l | tr -d ' ')

run sh "$SCRIPT" apply dotenv --gateway "$GW" --key 'mqk_live_AbCdEfGhIjKlMnOpQrStUv_YYYYYYYY.other' --flavor openai --file "$ENVF"
assert_status 0 "apply with a different key exits 0"
assert_contains "backup:" "changing content creates a backup"
BAK2=$(ls "$ENVF".bak-* 2>/dev/null | wc -l | tr -d ' ')
[ "$BAK2" = "$((BAK1 + 1))" ] && ok "unchanged re-apply makes no backup; a change makes exactly one" || bad "backup delta (before=$BAK1 after=$BAK2)"
case "$(cat "$ENVF")" in
    *YYYYYYYY*) ok "managed block was replaced in place" ;;
    *) bad "managed block was replaced in place" ;;
esac
[ "$(grep -c 'OPENAI_API_KEY=' "$ENVF")" = 1 ] && ok "replacement keeps a single key line" || bad "replacement keeps a single key line"

run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$ENVF" --dry-run
assert_status 0 "dry-run exits 0"
assert_contains "dry-run, not written" "dry-run announces itself"

# ============ managed-block policy ============

DAMAGED="$TMP/damaged.env"
printf '# >>> miqro-onboard (managed) >>>\nOPENAI_API_KEY=stale\n' >"$DAMAGED"
run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$DAMAGED"
assert_status 1 "an unmatched managed marker is refused"
assert_contains "damaged managed-block markers" "the damaged marker is named"

DOUBLE="$TMP/double.env"
printf '# >>> miqro-onboard (managed) >>>\nA=1\n# <<< miqro-onboard (managed) <<<\n# >>> miqro-onboard (managed) >>>\nA=2\n# <<< miqro-onboard (managed) <<<\n' >"$DOUBLE"
run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$DOUBLE"
assert_status 1 "duplicated managed blocks are refused"

# ============ symlink target ============

REAL="$TMP/real.env"
printf 'X=1\n' >"$REAL"
LINK="$TMP/link.env"
ln -s "$REAL" "$LINK" 2>/dev/null
if [ -L "$LINK" ]; then
    run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$LINK"
    assert_status 1 "a symlinked target is refused"
    assert_contains "symlink" "the symlink refusal is explained"
    case "$(cat "$REAL")" in
        'X=1') ok "the symlink target was left untouched" ;;
        *) bad "the symlink target was left untouched" ;;
    esac
else
    printf 'SKIP symlink tests (ln -s unavailable)\n'
fi

# ============ secret hygiene ============

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        # NTFS carries no POSIX modes; the chmod guard is a no-op there (ACLs rule).
        printf 'SKIP file-mode assertions (Windows filesystem does not carry POSIX modes)\n'
        ;;
    *)
        case "$(file_mode "$ENVF")" in
            600) ok "written file is chmod 600 (it holds a credential)" ;;
            *) bad "written file is chmod 600 (got: $(file_mode "$ENVF"))" ;;
        esac
        BAK=$(ls "$ENVF".bak-* 2>/dev/null | head -1)
        case "$(file_mode "$BAK")" in
            600) ok "backup file is chmod 600" ;;
            *) bad "backup file is chmod 600 (got: $(file_mode "$BAK"))" ;;
        esac
        ;;
esac

if command -v git >/dev/null 2>&1; then
    GREPO="$TMP/gitrepo"
    mkdir -p "$GREPO"
    (cd "$GREPO" && git init -q .)
    run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$GREPO/.env"
    assert_status 0 "apply inside a git work tree exits 0"
    assert_contains "not ignored" "un-ignored secret file triggers a warning"
    printf '.env\n' >"$GREPO/.gitignore"
    run sh "$SCRIPT" apply dotenv --gateway "$GW" --key "$VK" --flavor openai --file "$GREPO/.env"
    assert_status 0 "second apply (now ignored) exits 0"
    assert_not_contains "not ignored" "ignored secret file stays quiet"
else
    printf 'SKIP git-ignore warning tests (git not installed)\n'
fi

# ============ apply claude-settings ============

if command -v jq >/dev/null 2>&1; then
    printf '{\n  "model": "keep-me",\n  "env": {"EXISTING": "1"}\n}\n' >"$TMP/settings.json"
    run sh "$SCRIPT" apply claude-settings --gateway "$GW" --key "$VK" --file "$TMP/settings.json"
    assert_status 0 "apply claude-settings exits 0"
    MERGED=$(cat "$TMP/settings.json")
    case "$MERGED" in
        *'"model": "keep-me"'*) ok "JSON merge preserves unrelated keys" ;;
        *) bad "JSON merge preserves unrelated keys" ;;
    esac
    case "$MERGED" in
        *'"EXISTING": "1"'*) ok "JSON merge preserves sibling env keys" ;;
        *) bad "JSON merge preserves sibling env keys" ;;
    esac
    case "$MERGED" in
        *"ANTHROPIC_AUTH_TOKEN"*) ok "JSON merge injects the token" ;;
        *) bad "JSON merge injects the token" ;;
    esac
    run sh "$SCRIPT" apply claude-settings --gateway "$GW" --key "$VK" --file "$TMP/settings.json"
    assert_contains "unchanged:" "claude-settings re-apply is idempotent"

    printf '{not json\n' >"$TMP/broken.json"
    run sh "$SCRIPT" apply claude-settings --gateway "$GW" --key "$VK" --file "$TMP/broken.json"
    assert_status 1 "invalid JSON is refused"
    case "$(cat "$TMP/broken.json")" in
        '{not json'*) ok "refused merge leaves the file untouched" ;;
        *) bad "refused merge leaves the file untouched" ;;
    esac
else
    printf 'SKIP claude-settings merge tests (jq not installed)\n'
    run sh "$SCRIPT" apply claude-settings --gateway "$GW" --key "$VK" --file "$TMP/settings.json"
    assert_status 1 "apply claude-settings without jq refuses politely"
    assert_contains "jq" "the refusal names jq"
fi

run sh "$SCRIPT" apply claude-settings --gateway "$GW" --key "$VK"
assert_status 1 "apply claude-settings without --file is refused"

# ============ verify (fake curl) ============

mkdir -p "$TMP/bin"
cat >"$TMP/bin/curl" <<'FAKE'
#!/bin/sh
out=/dev/null
printf '%s\n' "$*" >>"${FAKE_CURL_LOG:-/dev/null}"
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out=$2; shift 2 ;;
        *) shift ;;
    esac
done
printf '%s' "${FAKE_BODY:-}" >"$out"
printf '%s' "${FAKE_CODE:-200}"
FAKE
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"
export PATH
export FAKE_CURL_LOG="$TMP/curl.log"

export FAKE_CODE=200
export FAKE_BODY='{"object":"list","data":[{"id":"deepseek-flash"}]}'
: >"$FAKE_CURL_LOG"
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 0 "verify accepts 200 with a models-list body"
assert_contains "OK: HTTP 200" "verify reports the accepted credential"
CLOG=$(cat "$FAKE_CURL_LOG")
case "$CLOG" in
    *"--max-time 20"*) ok "verify bounds the request with a timeout" ;;
    *) bad "verify bounds the request with a timeout (curl args: $CLOG)" ;;
esac
case "$CLOG" in
    *"--connect-timeout 5"*) ok "verify bounds the connect phase" ;;
    *) bad "verify bounds the connect phase (curl args: $CLOG)" ;;
esac

export FAKE_CODE=200
export FAKE_BODY='<html>proxy error page</html>'
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 1 "verify rejects a 200 that is not the models list"
assert_contains "not a models list" "the structural failure is explained"

export FAKE_CODE=404
export FAKE_BODY='{"code":"virtual_key_invalid"}'
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 1 "verify fails on 404"
assert_contains "virtual_key_invalid" "verify names the uniform 404"
assert_contains "code: virtual_key_invalid" "the failure prints the parsed code, not the body"
assert_not_contains '"code":"virtual_key_invalid"' "the raw body is not dumped by default"

export FAKE_CODE=401
export FAKE_BODY='{"code":"invalid_api_key"}'
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 1 "verify fails on 401"
assert_contains "wrong credential plane" "verify explains 401 attribution"

export FAKE_CODE=404
export FAKE_BODY='{"code":"virtual_key_invalid"}'
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK" --verbose
assert_status 1 "verify --verbose still fails on 404"
assert_contains "body: " "verbose prints the body for diagnosis"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
