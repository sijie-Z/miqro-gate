#!/usr/bin/env python3
"""OpenAPI breaking-change diff (F09, api-contract §8).

Compares the committed baseline spec (docs/openapi/openapi-3.1.json) against
the freshly generated head spec (backend/control-plane-app/target/
openapi-spec.json, produced by OpenApiSpecIntegrationTest) and fails when the
head removes or tightens an existing contract:

- a path or HTTP operation present in the baseline disappears;
- a baseline response code disappears from an operation;
- a baseline parameter disappears, changes type, becomes required, or changes
  its default;
- a response body or request body is repointed at another schema, reshaped, or
  loses a media type; a request body becomes required;
- a property becomes newly required (or a required property disappears) inside
  a schema;
- a property disappears, or its type, format, enum, default, nullability, or
  numerical/string bounds narrow -- and so does anything one level down, in a
  nested object or in an array's element schema.

The same checks apply wherever a schema is written: in `components.schemas`,
inline in a response body (springdoc writes every collection response that
way: `{type: array, items: {$ref: ...}}`), or inline in a request body.

Additions (new paths/operations/codes/parameters/properties) and widenings
(optional property added, enum value added, bound removed or relaxed, a type
widened to accept `null`) are legal and printed as a summary only. Unknown
schema references are compared by name.

The head is the generated spec and the baseline is regenerated in the same
commit as any deliberate API change, so a *newly generated* break is caught
only when the baseline was not refreshed alongside it -- see
docs/api-contract.md §8 and deploy/tests/openapi_breaking_regression.py, which
pins every verdict below.

Usage: check-openapi-breaking.py <baseline.json> <head.json>
Exit 0 = no breaking change; 1 = breaking change found.
"""

import json
import sys

METHODS = ("get", "put", "post", "patch", "delete", "head", "options")

# Bounds whose accepted set shrinks as the value grows, and vice versa.
FLOOR_BOUNDS = ("minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties")
CEILING_BOUNDS = ("maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties")

# `minLength: 0` (and its siblings) accepts every value, so a schema that starts
# declaring it has constrained nothing; springdoc emits it alongside a real
# `maxLength`, and reporting it would bury the real bounds in noise.
NO_OP_FLOOR = {"minLength": 0, "minItems": 0, "minProperties": 0}

# How far down nested objects and array elements are compared. A cap is required
# rather than merely prudent: a self-referential schema (`Node.properties.child`
# -> `$ref: Node`) would otherwise recurse forever, because `resolve` breaks the
# cycle only for a single lookup while every nested comparison resolves afresh.
MAX_DEPTH = 6


def ref_name(ref: str) -> str:
    return str(ref).rsplit("/", 1)[-1]


def resolve(node, schemas: dict, seen: tuple = ()):
    """Follow `$ref` and merge `allOf` so that only the contract is compared.

    A component rename (`.../QuotaRuleView` -> `.../QuotaRuleViewV2`) is not a
    breaking change by itself, so nothing here ever compares ref *names*: the
    target is resolved and only its shape is looked at.
    """
    if not isinstance(node, dict):
        return {}
    if "$ref" in node:
        name = ref_name(node["$ref"])
        if name in seen:
            return {}
        return resolve(schemas.get(name, {}), schemas, seen + (name,))
    merged = dict(node)
    for part in node.get("allOf") or []:
        for key, value in resolve(part, schemas, seen).items():
            merged.setdefault(key, value)
    merged.pop("allOf", None)
    return merged


def type_set(schema: dict) -> frozenset:
    """The JSON types a schema accepts. `type` is a list in 3.1, and a list is
    compared as a SET so that widening `"string"` to `["string", "null"]` -- the
    fix api-contract §8 needs for nullable fields -- reads as a loosening."""
    declared = schema.get("type")
    if isinstance(declared, list):
        return frozenset(t for t in declared if isinstance(t, str))
    if isinstance(declared, str):
        return frozenset([declared])
    return frozenset()


def numeric(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def compare_schema(prefix: str, label: str, base_node, head_node,
                   base_schemas: dict, head_schemas: dict, breaking: list, additions: list,
                   depth: int = 0) -> None:
    """Report every way `head_node` takes something away from `base_node`.

    Descends into `properties` and `items`, so a field that disappears one level
    down -- or an array whose element schema is repointed at another component --
    is caught wherever the schema is written: in a shared component, inline in a
    response body, or inline in a request body.
    """
    base = resolve(base_node, base_schemas)
    head = resolve(head_node, head_schemas)

    base_required = set(base.get("required") or [])
    head_required = set(head.get("required") or [])
    for prop in sorted(head_required - base_required):
        breaking.append(f"property became required: {label}.{prop}")
    for prop in sorted(base_required - head_required):
        breaking.append(f"required property removed: {label}.{prop}")

    base_types, head_types = type_set(base), type_set(head)
    lost = base_types - head_types
    gained = head_types - base_types - {"null"}
    # A head that declares no `type` at all accepts every JSON type, so it is a
    # widening, not a break. `format` is deliberately NOT treated this way: an
    # absent `format` is not a wider range but an unspecified one (`int64` ->
    # nothing lets a regenerated client pick a 32-bit int), so a declared format
    # disappearing is still reported below.
    if head_types and (lost or gained):
        breaking.append(
            f"{prefix} type changed: {label} {sorted(base_types)} -> {sorted(head_types)}")

    if base.get("format") != head.get("format"):
        breaking.append(
            f"{prefix} format changed: {label} {base.get('format')} -> {head.get('format')}")

    base_enum, head_enum = base.get("enum"), head.get("enum")
    if base_enum is not None:
        if head_enum is None:
            breaking.append(f"{prefix} enum removed: {label} {base_enum}")
        else:
            dropped = sorted(set(map(repr, base_enum)) - set(map(repr, head_enum)))
            if dropped:
                breaking.append(f"{prefix} enum value removed: {label} dropped {dropped}")
            added = sorted(set(map(repr, head_enum)) - set(map(repr, base_enum)))
            if added:
                additions.append(f"{prefix} enum value added: {label} added {added}")

    for bound in FLOOR_BOUNDS:
        base_bound, head_bound = numeric(base.get(bound)), numeric(head.get(bound))
        if head_bound is None or head_bound == NO_OP_FLOOR.get(bound):
            continue  # dropping a floor, or a floor that accepts everything
        if base_bound is None or head_bound > base_bound:
            breaking.append(f"{prefix} bound narrowed: {label} {bound} {base_bound} -> {head_bound}")
    for bound in CEILING_BOUNDS:
        base_bound, head_bound = numeric(base.get(bound)), numeric(head.get(bound))
        if head_bound is None:
            continue
        if base_bound is None or head_bound < base_bound:
            breaking.append(f"{prefix} bound narrowed: {label} {bound} {base_bound} -> {head_bound}")

    base_pattern, head_pattern = base.get("pattern"), head.get("pattern")
    if base_pattern != head_pattern and head_pattern is not None:
        breaking.append(
            f"{prefix} pattern changed: {label} {base_pattern} -> {head_pattern}")

    if base.get("default") != head.get("default"):
        breaking.append(
            f"{prefix} default changed: {label} {base.get('default')} -> {head.get('default')}")

    base_nullable, head_nullable = bool(base.get("nullable")), bool(head.get("nullable"))
    if base_nullable and not head_nullable:
        breaking.append(f"{prefix} nullable dropped: {label}")

    if depth >= MAX_DEPTH:
        return

    base_props = base.get("properties") or {}
    head_props = head.get("properties") or {}
    for prop, base_prop in base_props.items():
        if prop not in head_props:
            breaking.append(f"property removed: {label}.{prop}")
            continue
        compare_schema(prefix, f"{label}.{prop}", base_prop, head_props[prop],
                       base_schemas, head_schemas, breaking, additions, depth + 1)
    for prop in sorted(set(head_props) - set(base_props)):
        additions.append(f"property added: {label}.{prop}")

    base_items, head_items = base.get("items"), head.get("items")
    if base_items is not None and not isinstance(base_items, list):
        if head_items is None or isinstance(head_items, list):
            breaking.append(f"array element schema removed: {label}")
        else:
            compare_schema(prefix, f"{label}[]", base_items, head_items,
                           base_schemas, head_schemas, breaking, additions, depth + 1)
    elif head_items is not None and not isinstance(head_items, list):
        additions.append(f"array element schema added: {label}")


def compare_content(prefix: str, label: str, base_content: dict, head_content: dict,
                    base_schemas: dict, head_schemas: dict, breaking: list, additions: list) -> None:
    """Compare one operation's body (`content`) media type by media type."""
    for media in sorted(set(base_content) - set(head_content)):
        breaking.append(f"{prefix} media type removed: {media} on {label}")
    for media in sorted(set(head_content) - set(base_content)):
        additions.append(f"{prefix} media type added: {media} on {label}")
    for media in sorted(set(base_content) & set(head_content)):
        compare_schema(prefix, f"{label} {media}",
                       (base_content[media] or {}).get("schema", {}),
                       (head_content[media] or {}).get("schema", {}),
                       base_schemas, head_schemas, breaking, additions)


def content_of(op: dict, code: str) -> dict:
    return ((op.get("responses") or {}).get(code) or {}).get("content") or {}


def compare_request_body(method: str, path: str, base_op: dict, head_op: dict,
                         base_schemas: dict, head_schemas: dict,
                         base_bodies: dict, head_bodies: dict,
                         breaking: list, additions: list) -> None:
    """A request body is `$ref`-able in principle (`components.requestBodies`,
    unused in this repo's baseline) and inline in practice."""
    label = f"{method.upper()} {path}"
    base_rb, head_rb = base_op.get("requestBody"), head_op.get("requestBody")
    if base_rb is None:
        if head_rb is not None:
            additions.append(f"request body added: {label}")
        return
    if head_rb is None:
        breaking.append(f"request body removed: {label}")
        return
    base_rb = resolve(base_rb, base_bodies)
    head_rb = resolve(head_rb, head_bodies)
    # The body was already mandatory or already optional; only a body that
    # nobody had to send becoming one that everybody must send is a break.
    if base_rb.get("required") is not True and head_rb.get("required") is True:
        breaking.append(f"request body became required: {label}")
    compare_content("request body", label, base_rb.get("content") or {},
                    head_rb.get("content") or {}, base_schemas, head_schemas, breaking, additions)


def compare_parameters(method: str, path: str, base_op: dict, head_op: dict,
                       breaking: list, additions: list) -> None:
    def by_location(op: dict) -> dict:
        return {(p.get("name"), p.get("in")): p for p in op.get("parameters", []) if p.get("name")}

    base_params, head_params = by_location(base_op), by_location(head_op)
    for key, base_param in base_params.items():
        label = f"{key[0]} ({key[1]}) on {method.upper()} {path}"
        if key not in head_params:
            breaking.append(f"parameter removed: {label}")
            continue
        head_param = head_params[key]
        compare_schema("parameter", label, base_param.get("schema", {}),
                       head_param.get("schema", {}), {}, {}, breaking, additions)
        if base_param.get("required") is not True and head_param.get("required") is True:
            breaking.append(f"parameter became required: {label}")


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
    base_schemas = (base.get("components") or {}).get("schemas", {})
    head_schemas = (head.get("components") or {}).get("schemas", {})
    base_bodies = (base.get("components") or {}).get("requestBodies", {})
    head_bodies = (head.get("components") or {}).get("requestBodies", {})

    for path, ops in base_paths.items():
        if path not in head_paths:
            breaking.append(f"path removed: {path}")
            continue
        head_ops = head_paths[path]
        for method, op in ops.items():
            if not isinstance(op, dict) or method not in METHODS:
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
            for code in sorted(base_codes & head_codes):
                compare_content("response body", f"{method.upper()} {path} {code}",
                                content_of(op, code), content_of(head_op, code),
                                base_schemas, head_schemas, breaking, additions)
            base_param_names = {p.get("name") for p in op.get("parameters", [])}
            head_param_names = {p.get("name") for p in head_op.get("parameters", [])}
            for name in sorted(head_param_names - base_param_names, key=str):
                additions.append(f"parameter added: {name} on {method.upper()} {path}")
            compare_parameters(method, path, op, head_op, breaking, additions)
            compare_request_body(method, path, op, head_op, base_schemas, head_schemas,
                                 base_bodies, head_bodies, breaking, additions)

    for path in sorted(set(head_paths) - set(base_paths)):
        additions.append(f"path added: {path}")

    for name, schema in base_schemas.items():
        if name not in head_schemas:
            breaking.append(f"schema removed: {name}")
            continue
        compare_schema("property", name, schema, head_schemas[name],
                       base_schemas, head_schemas, breaking, additions)
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
