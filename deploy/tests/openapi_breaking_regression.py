#!/usr/bin/env python3
"""Behavioural regressions for deploy/openapi/check-openapi-breaking.py.

The guard is what stands between develop and a silently broken API v1: it is the
only automated reader of the rule in docs/api-contract.md that, inside one major
version, only optional fields and new endpoints may be added -- deletion,
renaming and meaning changes are the next major's business. Nothing tested it,
and it was only ever exercised against a spec whose baseline had just been
regenerated in the same commit, so base and head were byte-identical and "no
breaking OpenAPI changes" proved nothing.

Each scenario starts from one base spec and applies exactly ONE mutation, so a
verdict can only come from the change under test. The assertion is not that the
guard passes on a clean pair; it is that the guard goes red on a broken one, and
stays green on a legal one. A scenario that passes where it should fail is
reported as a failure in its own right.

  * must-fail: a property is removed (at the top level or inside a nested
    object), its type or format changes, an enum value disappears, a constraint
    narrows, a default moves, a parameter changes type, becomes required or
    loses its default, a request or response body is repointed or reshaped, a
    media type disappears -- plus the shapes the guard already caught (path,
    operation, response code, parameter, schema removal, newly-required
    property).
  * must-pass: added optional fields (including inside nested objects), added
    paths, widened enums, loosened constraints, added parameters and media
    types, a component renamed to an equivalent schema.

Run: python deploy/tests/openapi_breaking_regression.py
Needs: only python -- no docker, no network, no git history (CI checkouts are
shallow, so nothing here may depend on past revisions of the real baseline).
Point it at a mutated copy of the guard to prove these scenarios can go red:
--guard <path>.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GUARD = os.path.join(REPO, "deploy", "openapi", "check-openapi-breaking.py")

PATH = "/api/v1/admin/quota-rules"
PARAM = "page"
SCHEMA = "QuotaRuleView"
# A response whose body is written inline (springdoc does this for every
# collection endpoint: `{type: array, items: {$ref: ...}}`) rather than
# referenced at the top level.
ARRAY_PATH = "/api/v1/admin/quota-rules/active"
# A response body that carries no `$ref` at all, next to one that does.
BLOB_PATH = "/api/v1/admin/quota-rules/export"
REQ_PATH = "/api/v1/admin/quota-rules/{ruleId}"
OTHER = "ConfigEntry"
REF = "#/components/schemas/"

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(ok), detail))
    print("  %s  %s" % ("PASS" if ok else "FAIL", name), flush=True)
    if detail and not ok:
        print("        " + detail.replace("\n", "\n        "), flush=True)
    return bool(ok)


# `used` carries the shape of the break that actually shipped in c097e8c0 (#683):
# declared integer/int64, then regenerated as a decimal number.
INT64 = {"type": "integer", "format": "int64"}


def base_spec() -> dict:
    """A minimal spec shaped like the generated one: an operation whose 200 body
    references a named component schema, a paginated query parameter, a body
    written inline, a collection response (`items: $ref`), and a request body."""
    return {
        "openapi": "3.1.0",
        "paths": {
            PATH: {
                "get": {
                    "parameters": [
                        {"name": PARAM, "in": "query",
                         "schema": {"type": "integer", "format": "int32", "default": 1}},
                    ],
                    "responses": {
                        "200": {"description": "ok", "content": {"*/*": {
                            "schema": {"$ref": REF + SCHEMA}}}},
                        "400": {"description": "bad request"},
                    },
                },
            },
            ARRAY_PATH: {
                "get": {
                    "responses": {
                        "200": {"description": "ok", "content": {"*/*": {
                            "schema": {"type": "array", "items": {"$ref": REF + SCHEMA}}}}},
                    },
                },
            },
            BLOB_PATH: {
                "get": {
                    "responses": {
                        "200": {"description": "ok", "content": {"*/*": {
                            "schema": {"type": "string", "format": "byte"}}}},
                    },
                },
            },
            REQ_PATH: {
                "put": {
                    "requestBody": {"content": {"application/json": {
                        "schema": {"$ref": REF + SCHEMA}}}},
                    "responses": {
                        "200": {"description": "ok", "content": {"*/*": {
                            "schema": {"$ref": REF + SCHEMA}}}},
                    },
                },
            },
        },
        "components": {
            "schemas": {
                SCHEMA: {
                    "type": "object",
                    "properties": {
                        "used": {**INT64, "minimum": 0},
                        "metric": {"type": "string", "enum": ["TOKENS", "REQUESTS", "COST"]},
                        "note": {"type": "string"},
                        "window": {"type": "object", "properties": {
                            "start": {"type": "string"}, "end": {"type": "string"}}},
                    },
                },
                OTHER: {"type": "object", "properties": {"key": {"type": "string"}}},
            },
        },
    }


def prop(head: dict, name: str = "used") -> dict:
    return head["components"]["schemas"][SCHEMA]["properties"][name]


def params(head: dict) -> list[dict]:
    return head["paths"][PATH]["get"]["parameters"]


def operation(head: dict) -> dict:
    return head["paths"][PATH]["get"]


def body(head: dict, path: str, method: str = "get") -> dict:
    return head["paths"][path][method]["responses"]["200"]["content"]["*/*"]["schema"]


def request_body(head: dict, path: str = REQ_PATH, method: str = "put") -> dict:
    return head["paths"][path][method]["requestBody"]["content"]["application/json"]["schema"]


def replace(into: dict, **keywords) -> None:
    """Rewrite a schema outright. `update` is wrong for a node that is a `$ref`:
    the guard resolves the reference and ignores sibling keywords (as springdoc
    does -- none of the baseline's 304 `$ref` nodes carries one), so adding a
    `type` beside one would change nothing and the scenario would prove nothing.
    """
    into.clear()
    into.update(keywords)


def build_cases() -> list[tuple]:
    """(name, mutate(head), breaking?, substring the report must name[, mutate(base)]).

    The substring matters as much as the exit code: a guard that fails for the
    wrong reason would otherwise count as a pass. Empty substring = any failure.
    The optional base mutation exists so that a scenario which REMOVES something
    can start from a spec that has it, without disturbing the shared base.
    """
    cases: list[tuple] = []

    # ---- the classes the guard did not look at -------------------------------
    cases.append(("property type changed integer/int64 -> number (the #683 shape)",
                  lambda h: prop(h).update({"type": "number", "format": None}),
                  True, SCHEMA + ".used"))
    cases.append(("property format changed int64 -> int32",
                  lambda h: prop(h).update({"format": "int32"}),
                  True, SCHEMA + ".used"))
    cases.append(("property format dropped (int64 -> none)",
                  lambda h: prop(h).update({"format": None}),
                  True, SCHEMA + ".used"))
    cases.append(("property removed from a response schema",
                  lambda h: h["components"]["schemas"][SCHEMA]["properties"].pop("used"),
                  True, SCHEMA + ".used"))
    cases.append(("enum value removed",
                  lambda h: prop(h, "metric").update({"enum": ["TOKENS", "REQUESTS"]}),
                  True, SCHEMA + ".metric"))
    cases.append(("constraint narrowed: maxLength appears where there was none",
                  lambda h: prop(h).update({"maxLength": 10}),
                  True, SCHEMA + ".used"))
    cases.append(("constraint narrowed: minimum raised",
                  lambda h: prop(h).update({"minimum": 1}),
                  True, SCHEMA + ".used"))
    cases.append(("constraint narrowed: maximum lowered",
                  lambda h: prop(h).update({"maximum": 99}),
                  True, SCHEMA + ".used", lambda b: prop(b).update({"maximum": 100})))
    cases.append(("constraint narrowed: minLength raised off its no-op default",
                  lambda h: prop(h, "metric").update({"minLength": 1}),
                  True, SCHEMA + ".metric"))
    cases.append(("constraint narrowed: pattern appears where there was none",
                  lambda h: prop(h).update({"pattern": "^[0-9]+$"}),
                  True, SCHEMA + ".used"))
    cases.append(("property default changed",
                  lambda h: prop(h).update({"default": 0}),
                  True, SCHEMA + ".used"))
    cases.append(("parameter type changed",
                  lambda h: params(h)[0].update({"schema": {"type": "string"}}),
                  True, PARAM))
    cases.append(("parameter became required",
                  lambda h: params(h)[0].update({"required": True}),
                  True, PARAM))
    cases.append(("parameter default changed (the #1003 pagination default)",
                  lambda h: params(h)[0]["schema"].update({"default": 0}),
                  True, PARAM))

    # ---- the shapes that live in `paths`, not in `components` ----------------
    # 216 of the baseline's response bodies are `$ref`s and 82 are written
    # inline, but only the components were ever walked -- so a body could be
    # repointed or rewritten and the guard still said "no breaking changes".
    cases.append(("response body $ref swapped to a different existing schema",
                  lambda h: body(h, PATH).update({"$ref": REF + OTHER}),
                  True, PATH))
    cases.append(("inline response body type changed string -> object",
                  lambda h: body(h, BLOB_PATH).update({"type": "object", "format": None}),
                  True, BLOB_PATH))
    cases.append(("inline array response: element schema swapped to another component",
                  lambda h: body(h, ARRAY_PATH)["items"].update({"$ref": REF + OTHER}),
                  True, ARRAY_PATH))
    cases.append(("response media type removed",
                  lambda h: h["paths"][BLOB_PATH]["get"]["responses"]["200"]["content"].pop("*/*"),
                  True, BLOB_PATH))
    cases.append(("request body type changed (object -> string)",
                  lambda h: replace(request_body(h), type="string"),
                  True, REQ_PATH))
    cases.append(("request body became required",
                  lambda h: h["paths"][REQ_PATH]["put"]["requestBody"].update({"required": True}),
                  True, REQ_PATH))
    # A property that is itself an inline object is not a leaf: without
    # descending into it, a field disappearing one level down is invisible.
    cases.append(("nested property removed (QuotaRuleView.window.end)",
                  lambda h: prop(h, "window")["properties"].pop("end"),
                  True, SCHEMA + ".window.end"))
    cases.append(("nested property type changed (window.start string -> integer)",
                  lambda h: prop(h, "window")["properties"]["start"].update({"type": "integer"}),
                  True, SCHEMA + ".window.start"))
    # `resolve` also merges `allOf`, which is how springdoc renders a component
    # that extends another. That merge needs binding too: a guard that dropped it
    # would resolve an `allOf` body to `{}` on BOTH sides and call the pair clean,
    # so without these two the suite would still score full marks while the
    # composition path quietly stopped being walked.
    cases.append(("allOf-composed response body: branch target swapped",
                  lambda h: replace(body(h, PATH), allOf=[{"$ref": REF + OTHER}]),
                  True, PATH + " 200 */*.used",
                  lambda b: replace(body(b, PATH), allOf=[{"$ref": REF + SCHEMA}])))
    cases.append(("allOf-composed response body: branch makes a property required",
                  lambda h: replace(body(h, PATH), allOf=[
                      {"$ref": REF + SCHEMA}, {"required": ["used"]}]),
                  True, PATH + " 200 */*.used",
                  lambda b: replace(body(b, PATH), allOf=[
                      {"$ref": REF + SCHEMA}, {"required": []}])))

    # ---- what it already caught: keep it catching them -----------------------
    cases.append(("path removed", lambda h: h.update({"paths": {}}), True, PATH))
    cases.append(("operation removed",
                  lambda h: h["paths"][PATH].pop("get"), True, "GET " + PATH))
    cases.append(("response code removed",
                  lambda h: operation(h)["responses"].pop("400"), True, ""))
    cases.append(("parameter removed", lambda h: operation(h).update({"parameters": []}), True, PARAM))
    cases.append(("property became required",
                  lambda h: h["components"]["schemas"][SCHEMA].update({"required": ["used"]}),
                  True, SCHEMA + ".used"))
    cases.append(("required property removed",
                  lambda h: h["components"]["schemas"][SCHEMA].update({"required": []}),
                  True, SCHEMA + ".used", lambda b: b["components"]["schemas"][SCHEMA].update({"required": ["used"]})))
    cases.append(("schema removed",
                  lambda h: h["components"].update({"schemas": {}}), True, SCHEMA))

    # ---- legal in the same major: adding and loosening -----------------------
    cases.append(("optional property added",
                  lambda h: h["components"]["schemas"][SCHEMA]["properties"].update({"limitValue": dict(INT64)}),
                  False, ""))
    cases.append(("enum value added",
                  lambda h: prop(h, "metric").update({"enum": ["TOKENS", "REQUESTS", "COST", "BUDGET"]}),
                  False, ""))
    cases.append(("constraint loosened: maxLength raised",
                  lambda h: prop(h).update({"maxLength": 20}),
                  False, "", lambda b: prop(b).update({"maxLength": 10})))
    cases.append(("constraint loosened: minimum lowered",
                  lambda h: prop(h).update({"minimum": -1}),
                  False, ""))
    cases.append(("constraint loosened: pattern dropped",
                  lambda h: prop(h).update({"pattern": None}),
                  False, "", lambda b: prop(b).update({"pattern": "^[0-9]+$"})))
    # springdoc emits `minLength: 0` next to every `maxLength`; it accepts every
    # string, so it must not be mistaken for a new constraint.
    cases.append(("no-op bound: minLength 0 appears beside a real maxLength",
                  lambda h: prop(h, "metric").update({"minLength": 0, "maxLength": 200}),
                  False, "", lambda b: prop(b, "metric").update({"maxLength": 100})))
    # A component may become nullable without breaking anyone: `#1212` plans to
    # fix the baseline by widening nullable fields to `["string", "null"]`, and
    # that fix must not be reported as the very kind of break it prevents.
    cases.append(("type widened to accept null (the #1212 shape)",
                  lambda h: prop(h).update({"type": ["integer", "null"]}),
                  False, ""))
    # No `type` at all accepts every JSON type, so the keyword disappearing is a
    # widening. (Losing a declared `format` is not: see the case above it.)
    cases.append(("type dropped entirely (unconstrained = wider)",
                  lambda h: prop(h, "note").update({"type": None}),
                  False, ""))
    cases.append(("parameter added",
                  lambda h: params(h).append({"name": "direction", "in": "query",
                                              "schema": {"type": "string"}}),
                  False, ""))
    cases.append(("operation added",
                  lambda h: h["paths"][PATH].update({"post": {"responses": {"200": {"description": "ok"}}}}),
                  False, ""))
    # Adding a level of nesting, or a field inside one, is still an addition.
    cases.append(("nested optional property added (window.tz)",
                  lambda h: prop(h, "window")["properties"].update({"tz": {"type": "string"}}),
                  False, ""))
    # Component renames stay legal: only the resolved shape is compared, never
    # the ref name -- a rename that keeps the shape breaks no client.
    cases.append(("response body $ref renamed to an equivalent new component",
                  lambda h: (h["components"]["schemas"].update(
                                 {"QuotaRuleViewV2": copy.deepcopy(h["components"]["schemas"][SCHEMA])}),
                             body(h, PATH).update({"$ref": REF + "QuotaRuleViewV2"})),
                  False, ""))
    # Same shape, re-expressed through `allOf`. springdoc does this whenever a
    # component extends another, so it is a shape a real baseline contains; the
    # scenario is load-bearing in the opposite direction from the two above --
    # a guard that stopped merging `allOf` would see a property-less head here
    # and cry "property removed" over a change no client can observe.
    cases.append(("response body $ref rewrapped as an equivalent one-branch allOf",
                  lambda h: replace(body(h, PATH), allOf=[{"$ref": REF + SCHEMA}]),
                  False, ""))
    cases.append(("inline response body widened to accept null",
                  lambda h: body(h, BLOB_PATH).update({"type": ["string", "null"]}),
                  False, ""))
    cases.append(("response media type added",
                  lambda h: h["paths"][BLOB_PATH]["get"]["responses"]["200"]["content"].update(
                      {"application/json": {"schema": {"type": "string", "format": "byte"}}}),
                  False, ""))
    cases.append(("request body made optional",
                  lambda h: h["paths"][REQ_PATH]["put"]["requestBody"].update({"required": False}),
                  False, "", lambda b: b["paths"][REQ_PATH]["put"]["requestBody"].update({"required": True})))
    return cases


def apply_mutation(head: dict, mutate) -> dict:
    """Run a mutation, dropping keys the scenario set to None as a deletion."""
    mutate(head)
    return prune_none(head)


def prune_none(node):
    if isinstance(node, dict):
        return {k: prune_none(v) for k, v in node.items() if v is not None}
    if isinstance(node, list):
        return [prune_none(v) for v in node]
    return node


def run_guard(guard: str, base: dict, head: dict, tmp: str) -> tuple[int, str]:
    files = []
    for name, doc in (("base.json", base), ("head.json", head)):
        fp = os.path.join(tmp, name)
        with open(fp, "w", encoding="utf-8") as fh:
            json.dump(doc, fh)
        files.append(fp)
    proc = subprocess.run([sys.executable, guard, files[0], files[1]],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--guard", default=GUARD,
                        help="guard under test; point it at a mutated copy to prove "
                             "that these scenarios can actually go red")
    args = parser.parse_args()

    if not os.path.exists(args.guard):
        print("cannot find %s" % args.guard, file=sys.stderr)
        return 2

    cases = build_cases()
    print("guard under test: %s\n%d scenarios\n" % (args.guard, len(cases)), flush=True)
    with tempfile.TemporaryDirectory(prefix="openapi-guard-regression-") as tmp:
        clean = run_guard(args.guard, base_spec(), base_spec(), tmp)
        check("an unchanged spec is not breaking", clean[0] == 0,
              "expected exit 0, got %d:\n%s" % (clean[0], clean[1][-800:]))
        for case in cases:
            name, mutate, expect_break, needle = case[:4]
            base = apply_mutation(base_spec(), case[4]) if len(case) > 4 else base_spec()
            head = apply_mutation(copy.deepcopy(base), mutate)
            code, out = run_guard(args.guard, base, head, tmp)
            body = out[-800:]
            if expect_break:
                ok = code == 1
                if not ok:
                    detail = "expected exit 1, got %d:\n%s" % (code, body)
                elif needle and needle not in out:
                    ok = False
                    detail = "the guard failed, but never names %r:\n%s" % (needle, body)
                else:
                    detail = ""
            else:
                ok = code == 0
                detail = "" if ok else "a legal addition was reported as breaking:\n%s" % body
            check(name, ok, detail)

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n%d/%d checks passed" % (passed, total))
    for name, ok, _ in RESULTS:
        if not ok:
            print("  failed: %s" % name)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
