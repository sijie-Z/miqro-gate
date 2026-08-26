#!/usr/bin/env bash
# SBOM generation + license gate (G6.3): cyclonedx aggregate BOM for the
# two deployable apps, then a copyleft license check over the BOM.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/d/programming/jdk-21.0.12.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"

# Windows Git Bash needs the JDK on PATH; CI runners already have java.
if ! command -v java >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -f mvnw ] && [ -x mvnw ]; then MVNW=./mvnw
elif [ -f mvnw.cmd ]; then MVNW=./mvnw.cmd
else MVNW=mvn; fi
BOM="$ROOT/backend/target/bom.json"
"$MVNW" -f backend/pom.xml -q org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -Dcyclonedx.outputDirectory=target/sbom -Dcyclonedx.outputName=bom \
  --batch-mode >/dev/null 2>&1 || { echo "SBOM generation failed" >&2; exit 1; }

python - "$BOM" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    bom = json.load(f)
forbidden = ("GPL", "LGPL", "AGPL", "SSPL", "EPL", "MPL", "CC-BY-NC", "CC-BY-SA")
violations = []
for c in bom.get("components", []):
    for lic in c.get("licenses", []):
        name = lic.get("license", {}).get("name", "") or lic.get("expression", "")
        if any(f in name for f in forbidden):
            violations.append(f"{c.get('group','')}:{c.get('name','')} -> {name}")
if violations:
    print("LICENSE GATE FAILED (copyleft in runtime deps):", file=sys.stderr)
    for v in violations:
        print("  " + v, file=sys.stderr)
    sys.exit(1)
print(f"license gate ok ({len(bom.get('components', []))} components)")
PY
echo "SBOM written: $BOM"
