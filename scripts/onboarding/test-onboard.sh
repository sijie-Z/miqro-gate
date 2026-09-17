#!/bin/sh
# Tests for scripts/onboarding/miqro-onboard.sh (issue #742, slice 2).
# Pure POSIX sh, no network: `verify` runs against a fake curl on PATH.
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

# run <expected-status-nonzero?-> : capture OUT/ST
# assert_status NAME EXPECTED
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

# ---------- print ----------

run sh "$SCRIPT" print env --gateway "$GW" --key "$VK"
assert_status 0 "print env (posix) exits 0"
assert_contains 'export ANTHROPIC_BASE_URL="https://gw.example.com"' "print env emits base URL"
assert_contains "export ANTHROPIC_AUTH_TOKEN=\"$VK\"" "print env emits key"
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
assert_contains "Bearer $VK" "curl carries the key"
assert_contains '"model":"deepseek-flash"' "curl seeds the model"

run sh "$SCRIPT" print mcp --gateway "$GW" --key "$VK" --mcp-url https://gw.example.com/mcpservers/demo/mcp
assert_status 1 "print mcp refuses a virtual key (wrong plane)"
assert_contains "plane mismatch" "mcp plane mismatch is explained"

run sh "$SCRIPT" print mcp --gateway "$GW" --key 'mqk_api_ZZZ' --mcp-url https://gw.example.com/mcpservers/demo/mcp
assert_status 0 "print mcp accepts a consumer key"
assert_contains '"url": "https://gw.example.com/mcpservers/demo/mcp"' "mcp emits the streamable URL"
assert_contains 'Bearer mqk_api_ZZZ' "mcp emits the consumer credential"

# ---------- validation ----------

run sh "$SCRIPT" print curl --gateway "$GW" --key 'mqk_live_bareKeyWithoutLabel'
assert_status 1 "bare virtual key (no .label) is refused"
assert_contains ".label" "the .label trap is named"

run sh "$SCRIPT" print curl --gateway http://example.com --key "$VK"
assert_status 1 "plain http to a non-local host is refused"

run sh "$SCRIPT" print env --gateway https://gw.example.com/v1/ --key "$VK"
assert_status 0 "trailing /v1/ is normalized"
assert_contains 'https://gw.example.com"' "normalized origin is used"

# ---------- apply dotenv ----------

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

# ---------- apply claude-settings ----------

if command -v jq >/dev/null 2>&1; then
    SET="\"$TMP/settings.json\""
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

# ---------- verify (fake curl) ----------

mkdir -p "$TMP/bin"
cat >"$TMP/bin/curl" <<'FAKE'
#!/bin/sh
out=/dev/null
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

FAKE_CODE=200
FAKE_BODY='{"data":[]}'
export FAKE_CODE FAKE_BODY
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 0 "verify accepts 200"
assert_contains "OK: HTTP 200" "verify reports the accepted credential"

FAKE_CODE=404
FAKE_BODY='{"code":"virtual_key_invalid"}'
export FAKE_CODE FAKE_BODY
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 1 "verify fails on 404"
assert_contains "virtual_key_invalid" "verify names the uniform 404"

FAKE_CODE=401
FAKE_BODY='{"code":"invalid_api_key"}'
export FAKE_CODE FAKE_BODY
run sh "$SCRIPT" verify --gateway "$GW" --key "$VK"
assert_status 1 "verify fails on 401"
assert_contains "wrong credential plane" "verify explains 401 attribution"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
