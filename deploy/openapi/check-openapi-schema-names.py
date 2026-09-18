#!/usr/bin/env python3
"""Same-simple-name DTO guards (#838).

Two checks, both about the same failure: springdoc names component schemas by
**simple class name**, so when two files declare a `record` with the same name,
their JSON schemas collapse into one — and every endpoint referencing the merged
name documents whichever shape won. Before this existed, fifteen controllers
each declared `record CreateRequest(...)` with a different shape, three files
declared `ScopeRequest`, two declared `MemberRequest`; a contract document that
400s if you follow it, with frontend types that accept wrong payloads and
reject correct ones.

Check 1 (source scan, the general one): no record name may be declared in more
than one file under the scanned tree. This catches the collision at its cause,
without anyone having to notice the JSON was wrong.

Check 2 (spec denylist, the cheap belt-and-braces): the generated spec must not
contain the historical bare names even if some future declaration path slips
past the scan.

Usage: check-openapi-schema-names.py <spec.json> [source-root]
  source-root defaults to backend/control-plane-app/src/main/java
Exit 0 = clean; 1 = findings; 2 = usage.
"""

import json
import os
import re
import sys

# The exact simple-name spellings springdoc would produce for the historical
# offenders; the failure message can point straight at the Java declaration.
BARE_NAMES = {
    "CreateRequest",
    "UpdateRequest",
    "UpsertRequest",
    "AddRequest",
    "DeleteRequest",
    "PatchRequest",
    "ScopeRequest",
    "MemberRequest",
    "CreateBody",
    "UpdateBody",
    "UpsertBody",
}

RECORD_DECL = re.compile(r"\brecord\s+([A-Z][A-Za-z0-9_]*)\s*\(")


def scan_source_duplicates(root: str, spec_schemas: set[str]) -> dict[str, list[str]]:
    """record name -> declaring files, for names that can leak into the API.

    Only duplicates that can actually reach springdoc's schema registry are
    findings: a name declared in a controller file, or one already present in
    the spec. Two services each keeping a private `record HealthState` never
    produces a schema, and failing on those would train people to ignore this
    check.
    """
    seen: dict[str, list[str]] = {}
    controller_declared: set[str] = set()
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            rel = os.path.relpath(path, root)
            in_controller = os.sep + "controller" + os.sep in os.sep + rel
            for record in RECORD_DECL.findall(text):
                seen.setdefault(record, []).append(rel)
                if in_controller:
                    controller_declared.add(record)
    return {
        name: files
        for name, files in seen.items()
        if len(files) > 1 and (name in spec_schemas or name in controller_declared)
    }


def main() -> int:
    if not 1 <= len(sys.argv) - 1 <= 2:
        print(__doc__)
        return 2
    spec_path = sys.argv[1]
    source_root = sys.argv[2] if len(sys.argv) > 2 else "backend/control-plane-app/src/main/java"

    failed = False

    spec = json.load(open(spec_path, encoding="utf-8"))
    schemas = spec.get("components", {}).get("schemas", {})

    duplicates = scan_source_duplicates(source_root, set(schemas))
    if duplicates:
        failed = True
        print(
            "record name(s) declared in more than one file — springdoc merges their "
            "schemas into one, so at most one of the endpoints documents the truth:",
            file=sys.stderr,
        )
        for name in sorted(duplicates):
            print(f"  {name}: {', '.join(duplicates[name])}", file=sys.stderr)
        print(
            "Give each declaration a unique name (e.g. `record ProjectCreateRequest(...)`) "
            "and regenerate the baseline (OpenApiSpecIntegrationTest -> docs/openapi/openapi-3.1.json).",
            file=sys.stderr,
        )

    offenders = sorted(name for name in schemas if name in BARE_NAMES)
    if offenders:
        failed = True
        print(
            "bare-name schema(s) in components: " + ", ".join(offenders),
            file=sys.stderr,
        )

    if failed:
        return 1
    print(
        f"ok: no duplicate record declarations under {source_root}; "
        f"no bare-name schemas among {len(schemas)} components"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
