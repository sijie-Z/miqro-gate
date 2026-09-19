#!/usr/bin/env bash
# Secret scanning gate (G6.3): greps the tree for high-signal credential
# patterns and fails if any match outside build outputs, the compose
# placeholder and the test fixtures that deliberately exercise them.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

HITS=$(git grep -n -I -E \
  -e 'sk-[A-Za-z0-9]{16,}' \
  -e 'Bearer [A-Za-z0-9._-]{24,}' \
  -e 'AKIA[0-9A-Z]{16}' \
  -e 'xox[baprs]-[A-Za-z0-9-]{10,}' \
  -e 'ghp_[A-Za-z0-9]{20,}' \
  -- ':!**/target/**' ':!**/node_modules/**' ':!**/dist/**' ':!deploy/compose.yaml' ':!**/src/test/**' \
  ':!**/*.spec.ts' ':!**/e2e/**' || true)

if [ -n "$HITS" ]; then
  echo "SECRET SCAN FAILED:" >&2
  printf '%s\n' "$HITS" >&2
  exit 1
fi

# deploy/compose.yaml is excluded from the pattern scan above, so this block is
# its only guard: the dev stack's password must resolve to the documented
# placeholder, written literally or as the fallback of an env indirection.
# Anything else is a pasted credential. (The value is admitted by exact match
# rather than by a `grep -P` lookahead: -P is GNU-only and its absence used to
# be swallowed by `2>/dev/null`, and "the line does not start with the
# placeholder" is the opposite of "the value is not the placeholder".)
password_lines=$(grep -E '^[[:space:]]*POSTGRES_PASSWORD:[[:space:]]' deploy/compose.yaml || true)
if [ -z "$password_lines" ]; then
  echo "SECRET SCAN FAILED: deploy/compose.yaml has no POSTGRES_PASSWORD line (expected the documented placeholder)" >&2
  exit 1
fi
while IFS= read -r password_line; do
  value="${password_line#*POSTGRES_PASSWORD:}"
  value="${value%%[[:space:]]#*}"                     # drop a trailing YAML comment
  value=$(printf '%s' "$value" | tr -d '[:space:]')
  case "$value" in                                    # drop one layer of YAML quoting
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  # Reduce an env indirection to what it falls back to: ${VAR} and ${VAR:?msg}
  # carry no credential at all, ${VAR:-default} carries `default` — which is why
  # the reduction is repeated below rather than matched as a fixed spelling
  # (a nested or differently-spelled indirection must still be checked by value).
  n=0
  while [ "$n" -lt 5 ]; do
    case "$value" in '${'*'}') ;; *) break ;; esac
    inner="${value#\$\{}"; inner="${inner%\}}"
    case "$inner" in
      *:-*) value="${inner#*:-}" ;;
      *)    value="" ;;
    esac
    n=$((n + 1))
  done
  case "$value" in
    'change-me-in-production' | '') ;;
    *)
      echo "SECRET SCAN FAILED: deploy/compose.yaml sets POSTGRES_PASSWORD to something other than the documented placeholder: $value" >&2
      exit 1
      ;;
  esac
done <<<"$password_lines"

echo "secret scan ok"
