#!/usr/bin/env python3
"""Behavioural regressions for deploy/diagnose.sh and deploy/diagnose.ps1.

The diagnostic scripts exist to be pasted into an issue, so their load-bearing
properties are not "prints something" but two the ShellCheck job cannot ask
about (it proves syntax, not conclusions):

  * redaction: the report is assembled from container logs and an .env echo, the
    two places a deployment keeps its credentials. Every seeded secret below
    must be absent from the report, while the non-secrets a maintainer needs
    (the origin allowlist, a requestId, a model name) must survive verbatim -
    masking that swallowed those would make the report useless, which is its own
    kind of failure;
  * read-only: the script must never mutate the stack or the host. A docker stub
    records every invocation and this harness fails on any verb outside the read
    set, or any `exec` that is not `wget`/`pg_isready`.

Two platform facts are pinned because they were real bugs, not hypotheses:
a container whose logs live on stderr (redpanda does) emptied the whole log
section under Windows PowerShell 5.1 (`$ErrorActionPreference = SilentlyContinue`
silently drops native stderr merged with 2>&1), and PS 5.1 decodes a BOM-less
UTF-8 file as ANSI, mangling non-ASCII text. Both are exercised here via the
same fixture for .sh and .ps1.

The .ps1 leg runs only where `pwsh` exists and OS path resolution finds the
extensionless stub (POSIX): on Windows a stub named `docker` would be skipped by
PATHEXT and the *real* daemon would answer, which is not something a fixture test
should touch. On Windows, run it by hand as the forms instruct.

Run: python3 deploy/tests/diagnose_script_regression.py [--keep] [--script PATH]
Run it alone: fixtures share no global state, but --keep prints its temp tree.
Calibrate (prove the checks can go red) with --script pointing at a copy that
(a) drops one redact() rule - the masking check must fail, or (b) is given a
`docker restart` - the read-only check must fail.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(REPO, "deploy", "diagnose.sh")
PS1 = os.path.join(REPO, "deploy", "diagnose.ps1")

# Seeded credentials. Built at run time rather than written as literals so the
# repo's own secret scan (deploy/security/check-secrets.sh greps tracked files
# for real key shapes) is not tripped by the fixtures, and so a reader of this
# file can see at a glance that none of them is real.
FAKE = {
    "api_key": "sk-" + "A" * 32,                     # provider key shape
    "bearer": "Bearer " + "B" * 40,                  # auth header shape
    "json_pw": "p@ssw0rd-json-field",                # {"password": "..."}
    "db_url": "postgres://miqrokey:hunter2@postgres:5432/miqrokey",
    "db_pw": "hunter2",                              # the part above that leaks
    "env_kv": "SEKRET-VALUE-123",                    # KEY=... assignment
    "webhook_key": "W3BK-4B1T-K3Y",                  # ?key=... query param
    "privkey": "-----BEGIN RSA PRIVATE KEY-----",    # private-key header
}
# Values that must come through untouched: over-masking is the failure mode the
# other direction, and it is invisible unless a check insists on these.
SURVIVES = (
    "requestId=9f2c1e7a",
    "model=gpt-4o",
    "MIQROKEY_ORIGIN_ALLOWLIST=https://miqro.example.com",
    "MIQROKEY_IMAGE_TAG=localtest",
)

# docker verbs the diagnostic may use; anything else fails the run.
ALLOWED_VERBS = {"ps", "inspect", "logs", "exec", "version", "info", "stats", "compose", "system"}
ALLOWED_EXEC = ("wget", "pg_isready")

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(ok), detail))
    print("  %s  %s" % ("PASS" if ok else "FAIL", name), flush=True)
    if detail and not ok:
        print("        " + detail.replace("\n", "\n        "), flush=True)
    return bool(ok)


DOCKER_STUB = r"""#!/bin/sh
# Fixture docker: records every invocation, answers canned output only.
printf '%s\n' "$*" >> "$DOCKER_CALL_LOG"
cmd="$1"; shift
case "$cmd" in
  version) printf '27.0.0-stub\n' ;;
  compose) printf 'v2.30.0-stub\n' ;;
  info)    printf '/var/lib/docker\n' ;;
  system)  printf 'TYPE            TOTAL     ACTIVE    SIZE\ndummy-table\n' ;;
  ps)
    case "$*" in
      *'{{.Names}}'*) printf 'miqrokey-control-plane-1\nmiqrokey-redpanda-local\nmiqrokey-postgres-1\n' ;;
      *'-q'*)         printf 'f00dcafe0000\n' ;;
      *) printf 'f00dcafe0000|miqrokey-control-plane-1|Up 2 hours (healthy)|miqrokey-control-plane:localtest\n'
         printf 'deadbeef0000|miqrokey-redpanda-local|Exited (255) 2 days ago|redpandadata/redpanda:latest\n' ;;
    esac ;;
  inspect)
    case "$*" in
      *State.Health*) printf 'healthy\n' ;;
      *) printf 'sha256:cafebabe00000000000000000000000000000000000000000000000000000000\n' ;;
    esac ;;
  stats) printf 'miqrokey-control-plane-1  cpu=1.00%%  mem=100MiB / 1GiB (10.00%%)\n' ;;
  exec)
    case "$*" in
      *pg_isready*) printf '/var/run/postgresql:5432 - accepting connections\n' ;;
      *) printf '{"status":"UP"}\n' ;;
    esac ;;
  logs)
    name=""
    for a in "$@"; do name="$a"; done
    case "$name" in
      *redpanda*)
        # stderr on purpose: this is where redpanda's log lives, and where a
        # PowerShell 5.1 capture silently lost it until 2026-09-23.
        printf 'INFO storage - segment.cc - opening /var/lib/redpanda/data/controller/0_0/14-1-v1.log\n' >&2
        printf 'FATAL connection to %s failed\n' "$FAKE_DB_URL" >&2
        printf 'WARN retry with secret=%s\n' "$FAKE_ENV_KV" >&2
        printf 'ERROR webhook -> https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=%s\n' "$FAKE_WEBHOOK_KEY" >&2
        ;;
      *)
        printf 'INFO requestId=9f2c1e7a model=gpt-4o handling request\n'
        printf 'ERROR upstream rejected: %s\n' "$FAKE_BEARER"
        printf '{"event":"login","password":"%s"}\n' "$FAKE_JSON_PW"
        printf 'DEBUG provider key=%s\n' "$FAKE_API_KEY"
        printf '%s\n' "$FAKE_PRIVKEY"
        ;;
    esac ;;
esac
"""

CURL_STUB = r"""#!/bin/sh
# Fixture curl: records argv, always answers 200.
printf '%s\n' "$*" >> "$CURL_CALL_LOG"
printf '200'
"""

ENV_FILE = """# fixture .env - values are synthetic
MIQROKEY_PUBLIC_BASE_URL=https://miqro.example.com
MIQROKEY_GATEWAY_BASE_URL=https://miqro.example.com
MIQROKEY_ORIGIN_ALLOWLIST=https://miqro.example.com,https://alt.example.com
MIQROKEY_IMAGE_TAG=localtest
MIQROKEY_REGISTRATION_ENABLED=false
POSTGRES_DB=miqrokey
POSTGRES_USER=miqrokey
MIQROKEY_BACKUP_WEBHOOK_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={webhook_key}
MIQROKEY_BACKUP_WEBHOOK_SECRET={env_kv}
"""


class Fixture:
    def __init__(self, root: str, script: str, ps1: str) -> None:
        self.root = root
        self.tree = os.path.join(root, "tree")
        self.shim = os.path.join(root, "shim")
        self.env_file = os.path.join(self.tree, "deploy", ".env")
        self.docker_log = os.path.join(root, "docker-calls.log")
        self.curl_log = os.path.join(root, "curl-calls.log")
        self.write(os.path.join(self.tree, "deploy", "diagnose.sh"), open(script, encoding="utf-8").read())
        self.write(os.path.join(self.tree, "deploy", "diagnose.ps1"), open(ps1, encoding="utf-8").read())
        self.write(os.path.join(self.tree, "deploy", "compose.prod.yaml"), "services: {}\n")
        self.write(os.path.join(self.tree, "CHANGELOG.md"), "## [Unreleased] \u2014 fixture\n")
        self.write(os.path.join(self.tree, "deploy.log"),
                   "2026-09-01T00:00:00Z mode=deploy commit=abc1234 caller=regression smoke=200/https://miqro.example.com\n")
        for f in ("master_key", "vk_hmac_key", "bootstrap_secret", "db_password", "backup_key"):
            self.write(os.path.join(self.tree, "deploy", "secrets", f), "fixture-not-a-real-key\n")
        for f in ("fullchain.pem", "privkey.pem"):
            self.write(os.path.join(self.tree, "deploy", "secrets", "certs", f), "fixture\n")
        self.env_text = ENV_FILE.format(webhook_key=FAKE["webhook_key"], env_kv=FAKE["env_kv"])
        self.write(self.env_file, self.env_text)
        self.write_exec(os.path.join(self.shim, "docker"), self.rendered_docker())
        self.write_exec(os.path.join(self.shim, "curl"), CURL_STUB)

    @staticmethod
    def rendered_docker() -> str:
        """DOCKER_STUB with the seeded values filled in.

        The values live in FAKE (constructed at run time), not in this file, and
        the substitution happens here so the fixture leaks them into the report
        the way a real deployment would - if this ever stopped happening, the
        masking assertions would pass vacuously and the harness would be the
        thing that is broken.
        """
        stub = DOCKER_STUB
        for var, value in (("FAKE_DB_URL", FAKE["db_url"]),
                           ("FAKE_ENV_KV", FAKE["env_kv"]),
                           ("FAKE_WEBHOOK_KEY", FAKE["webhook_key"]),
                           ("FAKE_BEARER", FAKE["bearer"]),
                           ("FAKE_JSON_PW", FAKE["json_pw"]),
                           ("FAKE_API_KEY", FAKE["api_key"]),
                           ("FAKE_PRIVKEY", FAKE["privkey"])):
            assert "$" + var in stub, var
            stub = stub.replace("$" + var, value)
        return stub

    def write(self, path: str, text: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)

    def write_exec(self, path: str, text: str) -> None:
        self.write(path, text)
        os.chmod(path, 0o755)

    def env(self) -> dict:
        env = dict(os.environ)
        env["PATH"] = self.shim + os.pathsep + env.get("PATH", "")
        env["MIQROKEY_LIVE_DIR"] = self.tree
        env["MIQROKEY_ENV_FILE"] = self.env_file
        env["MIQROKEY_DIAGNOSE_LOG_LINES"] = "40"
        env["DOCKER_CALL_LOG"] = self.docker_log
        env["CURL_CALL_LOG"] = self.curl_log
        return env

    def run_sh(self, path: str | None = None) -> subprocess.CompletedProcess:
        sh = shutil.which("sh") or "/bin/sh"
        return subprocess.run([sh, path or os.path.join(self.tree, "deploy", "diagnose.sh")],
                              cwd=self.tree, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=self.env())

    def run_ps1(self) -> subprocess.CompletedProcess:
        return subprocess.run(["pwsh", "-NoProfile", "-File", os.path.join(self.tree, "deploy", "diagnose.ps1")],
                              cwd=self.tree, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=self.env())

    def docker_calls(self) -> list[str]:
        if not os.path.exists(self.docker_log):
            return []
        with open(self.docker_log, encoding="utf-8") as fh:
            return [line.strip() for line in fh if line.strip()]

    def curl_calls(self) -> list[str]:
        if not os.path.exists(self.curl_log):
            return []
        with open(self.curl_log, encoding="utf-8") as fh:
            return [line.strip() for line in fh if line.strip()]


def snapshot(tree: str) -> dict:
    out = {}
    for dirpath, _dirnames, filenames in os.walk(tree):
        for name in filenames:
            p = os.path.join(dirpath, name)
            st = os.stat(p)
            out[os.path.relpath(p, tree)] = (st.st_size, st.st_mtime_ns)
    return out


def report_checks(tag: str, proc: subprocess.CompletedProcess) -> str:
    out = (proc.stdout or "") + (proc.stderr or "")
    check("%s: exits 0" % tag, proc.returncode == 0,
          "exit %d:\n%s" % (proc.returncode, out[-500:]))
    check("%s: both markers present" % tag,
          "--- MiQroGate DIAGNOSTIC ---" in out and "--- END DIAGNOSTIC ---" in out,
          out[-500:])
    for name, secret in FAKE.items():
        check("%s: seeded %s is masked" % (tag, name), secret not in out,
              "the seeded value leaked into the report:\n%s"
              % ("\n".join(l for l in out.splitlines() if secret in l)[:400]))
    check("%s: masking markers present" % tag, out.count("<masked") >= 4,
          "found %d '<masked' occurrences" % out.count("<masked"))
    for keep in SURVIVES:
        check("%s: non-secret survives: %s" % (tag, keep), keep in out,
              "over-masking removed a value the report needs")
    return out


def verb_checks(fx: Fixture) -> None:
    calls = fx.docker_calls()
    check("docker was actually exercised (stub saw calls)", bool(calls),
          "no docker invocations recorded: the stub was never on PATH?")
    bad = [c for c in calls if (c.split()[0] if c.split() else "") not in ALLOWED_VERBS]
    check("no docker verb outside the read set", not bad, "offending: %r" % bad[:5])
    bad_exec = [c for c in calls if c.split() and c.split()[0] == "exec"
                and not any(w in c for w in ALLOWED_EXEC)]
    check("every docker exec is wget/pg_isready", not bad_exec, "offending: %r" % bad_exec[:5])
    bad_system = [c for c in calls if c.split() and c.split()[0] == "system" and "df" not in c]
    check("docker system only asked for df", not bad_system, "offending: %r" % bad_system[:5])
    curl = fx.curl_calls()
    check("curl was exercised and only asked (no -X/-d/--data)", bool(curl)
          and not any((" -X " in c) or (" -d " in c) or ("--data" in c) for c in curl),
          "curl invocations: %r" % curl[:5])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="keep the temp tree for inspection")
    parser.add_argument("--script", default=SCRIPT,
                        help="diagnose.sh under test; point it at a mutated copy to check "
                             "that these scenarios actually go red")
    parser.add_argument("--ps1", default=PS1, help="diagnose.ps1 under test")
    args = parser.parse_args()

    for p in (args.script, args.ps1):
        if not os.path.exists(p):
            print("cannot find %s" % p, file=sys.stderr)
            return 2

    root = tempfile.mkdtemp(prefix="diagnose-regression-")
    fx = Fixture(root, args.script, args.ps1)
    print("workspace: %s\n" % root, flush=True)

    try:
        before = snapshot(fx.tree)
        print("== diagnose.sh ==")
        proc = fx.run_sh()
        report_checks("sh", proc)
        print("\n== read-only ==")
        verb_checks(fx)
        after = snapshot(fx.tree)
        check("the report changed nothing in the tree", before == after,
              "added/changed: %r" % sorted(set(after.items()) ^ set(before.items()))[:5])

        print("\n== without docker on PATH (graceful degradation) ==")
        empty = os.path.join(root, "emptybin")
        os.makedirs(empty, exist_ok=True)
        env = dict(os.environ)
        env["PATH"] = empty
        env["MIQROKEY_LIVE_DIR"] = fx.tree
        env["MIQROKEY_ENV_FILE"] = fx.env_file
        sh = shutil.which("sh") or "/bin/sh"
        proc = subprocess.run([sh, os.path.join(fx.tree, "deploy", "diagnose.sh")],
                              cwd=fx.tree, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=env)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("still exits 0 and says docker is missing",
              proc.returncode == 0 and "docker not on PATH" in out and "--- END DIAGNOSTIC ---" in out,
              "exit %d:\n%s" % (proc.returncode, out[-400:]))

        print("\n== diagnose.ps1 ==")
        pwsh = shutil.which("pwsh")
        if not pwsh:
            print("  SKIP  pwsh is not installed here - the .ps1 leg runs in CI (ubuntu has pwsh);\n"
                  "        on Windows run deploy\\diagnose.ps1 by hand (the extensionless stub would\n"
                  "        not resolve there, and the real daemon is not a fixture).", flush=True)
        elif os.name == "nt":
            print("  SKIP  on Windows PATH resolution would skip the extensionless stub.", flush=True)
        else:
            proc = fx.run_ps1()
            report_checks("ps1", proc)

        print("\n== the stub is honest about stderr ==")
        # If the fixture above ever moved redpanda's log to stdout, the PS 5.1
        # regression would stop being exercised while everything stayed green.
        # Invoked through sh, not directly: an extensionless file is not a
        # Windows executable image.
        sh = shutil.which("sh") or "/bin/sh"
        raw = subprocess.run([sh, os.path.join(fx.shim, "docker"), "logs", "--tail", "5", "miqrokey-redpanda-local"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace",
                             env=fx.env())
        check("redpanda fixture logs on stderr", raw.stdout == "" and FAKE["db_pw"] in raw.stderr,
              "stdout=%r stderr=%r" % (raw.stdout[:120], raw.stderr[:120]))
    finally:
        if args.keep:
            print("\nkept: %s" % root)
        else:
            shutil.rmtree(root, ignore_errors=True)

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n%d/%d checks passed" % (passed, total))
    for name, ok, _ in RESULTS:
        if not ok:
            print("  failed: %s" % name)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
