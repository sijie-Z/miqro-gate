#!/usr/bin/env bash
# Behavioural test for check-secrets.sh: the scanner is a gate, so the thing
# worth asserting is not "it prints ok" but "it goes red on the inputs it
# exists to stop". Each case builds a throwaway git tree whose only interesting
# property is the one under test, runs the REAL script against it, and compares
# the exit status.
#
# The compose case is the one that regressed: the guard used to accept a
# hardcoded password (a `grep -P` lookahead was matched *by* the real value) and
# reject a missing line instead. Reverse-verified: restoring that guard makes
# case "hardcoded compose password" and case "compose password missing" fail.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/check-secrets.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
failures=0

# new_tree <name> — an empty tree carrying a copy of the scanner.
new_tree() {
  local dir="$TMP/$1"
  mkdir -p "$dir/deploy/security"
  cp "$SCRIPT" "$dir/deploy/security/check-secrets.sh"
  printf '%s' "$dir"
}

# compose <dir> <password-value|-> — a minimal compose.yaml; "-" omits the line.
compose() {
  if [ "$2" = "-" ]; then
    printf 'services:\n  postgres:\n    environment:\n      POSTGRES_USER: miqrokey\n' >"$1/deploy/compose.yaml"
  else
    printf 'services:\n  postgres:\n    environment:\n      POSTGRES_PASSWORD: %s\n' "$2" >"$1/deploy/compose.yaml"
  fi
}

# check <label> <expected-exit> <dir> <expected-substring>
# The message is asserted too: an exit status alone would also "pass" for a
# rejection made for the wrong reason (a crash, a bad path, a typo'd pattern).
check() {
  local label="$1" want="$2" dir="$3" want_msg="$4" got=0 bad=0
  if ! ( cd "$dir" && git init -q . && git add -A >/dev/null 2>&1 ); then
    echo "FAIL: $label — could not prepare the throwaway tree at $dir" >&2
    failures=$((failures + 1))
    return 0
  fi
  ( cd "$dir" && bash deploy/security/check-secrets.sh ) >"$dir.log" 2>&1 || got=$?
  if [ "$got" != "$want" ]; then
    echo "FAIL: $label — expected exit $want, got $got:" >&2
    sed 's/^/    | /' "$dir.log" >&2
    bad=1
  elif ! grep -qF -- "$want_msg" "$dir.log"; then
    echo "FAIL: $label — expected the output to mention '$want_msg':" >&2
    sed 's/^/    | /' "$dir.log" >&2
    bad=1
  fi
  if [ "$bad" != 0 ]; then
    failures=$((failures + 1))
  else
    echo "ok: $label (exit $got)"
  fi
}

# --- the compose-password guard -------------------------------------------
d="$(new_tree compose-hardcoded)";             compose "$d" 'hunter2-hardcoded-secret'
check "compose: a hardcoded password is rejected" 1 "$d" "SECRET SCAN FAILED"

d="$(new_tree compose-env-placeholder)";       compose "$d" '${POSTGRES_PASSWORD:-change-me-in-production}'
check "compose: the documented placeholder via env indirection is accepted" 0 "$d" "secret scan ok"

d="$(new_tree compose-bare-placeholder)";      compose "$d" 'change-me-in-production'
check "compose: the bare documented placeholder is accepted" 0 "$d" "secret scan ok"

d="$(new_tree compose-quoted)";                compose "$d" '"change-me-in-production"'
check "compose: a quoted placeholder is accepted" 0 "$d" "secret scan ok"

d="$(new_tree compose-comment)";               compose "$d" 'change-me-in-production # dev default'
check "compose: a placeholder with a trailing comment is accepted" 0 "$d" "secret scan ok"

d="$(new_tree compose-bare-env)";              compose "$d" '${POSTGRES_PASSWORD}'
check "compose: a bare env reference (no literal secret) is accepted" 0 "$d" "secret scan ok"

# The default of the indirection is a value in the file, so a credential hidden
# there is exactly as committed as a bare one.
d="$(new_tree compose-env-hardcoded)";         compose "$d" '${POSTGRES_PASSWORD:-hunter2-real-credential}'
check "compose: a credential in the env fallback is rejected" 1 "$d" "SECRET SCAN FAILED"

d="$(new_tree compose-missing)";               compose "$d" '-'
check "compose: a missing password line is rejected" 1 "$d" "SECRET SCAN FAILED"

# --- the credential pattern scan ------------------------------------------
d="$(new_tree clean-tree)";                    compose "$d" '${POSTGRES_PASSWORD:-change-me-in-production}'
check "clean tree passes" 0 "$d" "secret scan ok"

d="$(new_tree ghp-token)";                     compose "$d" '${POSTGRES_PASSWORD:-change-me-in-production}'
printf 'GITHUB_TOKEN=ghp_%s\n' 'abcdefghijklmnopqrstuvwx' >"$d/README.md"
check "a GitHub token in the tree is rejected" 1 "$d" "SECRET SCAN FAILED"

if [ "$failures" -ne 0 ]; then
  echo "check-secrets self-test: $failures case(s) failed" >&2
  exit 1
fi
echo "check-secrets self-test: all cases passed"
