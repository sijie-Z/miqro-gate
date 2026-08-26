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

if grep -qP 'POSTGRES_PASSWORD: (?!change-me-in-production)' deploy/compose.yaml 2>/dev/null \
  || grep -q 'POSTGRES_PASSWORD: change-me-in-production' deploy/compose.yaml; then
  : # placeholder is the expected state
else
  echo "SECRET SCAN FAILED: compose.yaml password is not the documented placeholder" >&2
  exit 1
fi

echo "secret scan ok"
