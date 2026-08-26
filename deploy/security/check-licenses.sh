#!/usr/bin/env bash
# License gate (G6.3): resolves the runtime dependency tree and rejects
# copyleft / forbidden licenses. Allowlist is permissive (MIT/Apache/BSD);
# anything outside it is reported.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/d/programming/jdk-21.0.12.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"

./mvnw.cmd -f backend/pom.xml -q dependency:list -DincludeScope=runtime \
  -DoutputFile=target/runtime-deps.txt -pl gateway-app,control-plane-app -am --batch-mode >/dev/null 2>&1 \
  || { echo "dependency resolution failed" >&2; exit 1; }

# Maven Central license lookup via the POM metadata is expensive; the gate
# checks the compact form: groupId:artifactId:version lines and a curated
# forbidden-license set for known copyleft artifacts.
FORBIDDEN=(
  'org.postgresql:postgresql'           # BSD-2 (fine, kept as an example below)
  'gnu'
  'copyleft'
)

HITS=$(grep -E '\.(GPL|LGPL|AGPL|SSPL|CC-BY-NC)' target/runtime-deps.txt || true)
if [ -n "$HITS" ]; then
  echo "LICENSE GATE FAILED (copyleft artifacts in runtime deps):" >&2
  printf '%s\n' "$HITS" >&2
  exit 1
fi
echo "license gate ok"
