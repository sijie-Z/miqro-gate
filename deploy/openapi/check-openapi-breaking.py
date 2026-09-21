#!/usr/bin/env python3
"""OpenAPI breaking-change diff (F09, api-contract §8).

Compares the committed baseline spec (docs/openapi/openapi-3.1.json) against
the freshly generated head spec (backend/control-plane-app/target/
openapi-spec.json, produced by OpenApiSpecIntegrationTest) and fails when the
head removes or tightens an existing contract:

- a path or HTTP operation present in the baseline disappears;
- a baseline response code disappears from an operation;
- a baseline parameter disappears;
- a property becomes newly required (or a required property disappears)
  inside a shared schema.

Additions (new paths/operations/codes/parameters/properties) are legal and
printed as a summary only. Unknown schema references are compared by name.

Usage: check-openapi-breaking.py <baseline.json> <head.json>
Exit 0 = no breaking change; 1 = breaking change found.
"""

import json
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base = json.load(open(sys.argv[1], encoding="utf-8"))
    head = json.load(open(sys.argv[2], encoding="utf-8"))
    breaking: list[str] = []
    additions: list[str] = []

    base_paths = base.get("paths", {})
    head_paths = head.get("paths", {})

    for path, ops in base_paths.items():
        if path not in head_paths:
            breaking.append(f"path removed: {path}")
            continue
        head_ops = head_paths[path]
        for method, op in ops.items():
            if not isinstance(op, dict) or method not in ("get", "put", "post", "patch", "delete", "head", "options"):
                continue
            if method not in head_ops:
                breaking.append(f"operation removed: {method.upper()} {path}")
                continue
            head_op = head_ops[method]
            base_codes = set((op.get("responses") or {}).keys())
            head_codes = set((head_op.get("responses") or {}).keys())
            for code in sorted(base_codes - head_codes):
                breaking.append(f"response removed: {code} {method.upper()} {path}")
            for code in sorted(head_codes - base_codes):
                additions.append(f"response added: {code} {method.upper()} {path}")
            base_params = {p.get("name") for p in op.get("parameters", [])}
            head_params = {p.get("name") for p in head_op.get("parameters", [])}
            for name in sorted(base_params - head_params):
                breaking.append(f"parameter removed: {name} on {method.upper()} {path}")
            for name in sorted(head_params - base_params):
                additions.append(f"parameter added: {name} on {method.upper()} {path}")

    for path in sorted(set(head_paths) - set(base_paths)):
        additions.append(f"path added: {path}")

    base_schemas = (base.get("components") or {}).get("schemas", {})
    head_schemas = (head.get("components") or {}).get("schemas", {})
    for name, schema in base_schemas.items():
        if name not in head_schemas:
            breaking.append(f"schema removed: {name}")
            continue
        base_required = set(schema.get("required", []))
        head_required = set(head_schemas[name].get("required", []))
        newly_required = sorted(head_required - base_required)
        lost_required = sorted(base_required - head_required)
        for prop in newly_required:
            breaking.append(f"property became required: {name}.{prop}")
        for prop in lost_required:
            breaking.append(f"required property removed: {name}.{prop}")
    for name in sorted(set(head_schemas) - set(base_schemas)):
        additions.append(f"schema added: {name}")

    for line in sorted(additions):
        print(f"  + {line}")
    if breaking:
        print("BREAKING CHANGES (the generated spec removed or tightened a contract):")
        for line in sorted(breaking):
            print(f"  ! {line}")
        return 1
    print("no breaking OpenAPI changes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
