#!/usr/bin/env python3
"""Behavioural regressions for deploy/deploy.sh.

Three of the script's assertions have no other automated coverage — the ShellCheck
job added in #814 only proves the script is syntactically sound, not that what it
concludes is right. This harness covers the conclusions:

  * the .env echo is normalised the way compose's dotenv is, so a CRLF line in the
    file is not reported as a deployment fault, while a real difference still fails
    legibly (#807);
  * the derived smoke target can actually observe an origin rejection, which a bare
    GET on the portal root structurally cannot (#809);
  * certificates the host holds are present, byte for byte, inside the container
    that mounts them (#812).

Each scenario builds a stack whose ONLY defect is the one under test. That is the
point: the assertion is not being asked to pass on a healthy stack, it is being
asked to fail on a broken one. A run that passes where it should fail is reported
as a failure in its own right.

Run: python3 deploy/tests/deploy_script_regression.py [--keep]
Run it ALONE: the fixtures share the compose project name and image tags, so two
copies running at once report each other's containers as missing and the
failures say nothing about the script (hit once, 2026-09-20).
Needs: docker, python3, curl. Binds 127.0.0.1 only; the sole network use is pulling
the pinned base image.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(REPO, "deploy", "deploy.sh")
TAG = "deploytest"
PROJECT = "miqrokey-deploytest"

# The base is pinned by digest, but pulled and re-tagged ONCE by this harness rather
# than referenced as `FROM <digest>` in the fixture Dockerfile. Measured: a `FROM`
# on the digest costs ~1m26s on EVERY build (BuildKit re-resolves it against the
# registry) against ~1.4s from a local tag, and this run builds a dozen images —
# that is a 15-minute run with a hard network dependency, i.e. exactly the flake
# profile a gate like this must not have. Pinning here keeps the reproducibility
# and drops the per-build round trip.
BASE_IMAGE = "alpine@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc"
BASE_LOCAL = "miqrokey-deploytest-base:local"

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(ok), detail))
    status = "PASS" if ok else "FAIL"
    line = "  %s  %s" % (status, name)
    if detail and not ok:
        line += "\n        " + detail.replace("\n", "\n        ")
    print(line, flush=True)
    return bool(ok)


class Stub:
    """The running stack, as far as the smoke can tell.

    login_status models what an unloaded .env looks like from outside: the login
    route answers 403 whatever Origin is sent (#794). `/` still answers 200,
    because nothing guards it — which is exactly why a GET there proves nothing.
    """

    def __init__(self) -> None:
        self.login_status = 400
        # #1038: a replaced backend answers 5xx until it is up. A non-empty sequence
        # is consumed one code per login request before login_status applies, which is
        # what lets a scenario say "not ready yet, then ready".
        self.login_sequence: list[int] = []
        self.requests: list[tuple[str, str, str | None, str | None]] = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _record(self) -> None:
                stub.requests.append((self.command, self.path,
                                      self.headers.get("Origin"),
                                      self.headers.get("Content-Type")))

            def do_GET(self) -> None:
                self._record()
                code = 200 if self.path == "/" else (
                    405 if self.path in ("/api/v1/auth/login", "/alt405") else 404)
                self.send_response(code)
                self.end_headers()
                self.wfile.write(b"x")

            def do_POST(self) -> None:
                self._record()
                if self.path != "/api/v1/auth/login":
                    code = 404
                elif stub.login_sequence:
                    code = stub.login_sequence.pop(0)
                else:
                    code = stub.login_status
                self.send_response(code)
                self.end_headers()
                self.wfile.write(b"x")

            def log_message(self, *args) -> None:
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def origin(self) -> str:
        return "http://127.0.0.1:%d" % self.port

    def stop(self) -> None:
        self.server.shutdown()


def closed_port() -> int:
    """A port nothing is listening on, for the unreachable scenario."""
    import socket

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


class Fixture:
    """A throwaway live tree + build tree, matching what the script expects."""

    def __init__(self, root: str, allowlist: str, registration: str = "false",
                 cert_mount: str = "./secrets/certs:/etc/nginx/certs:ro",
                 host_certs: bool = True, shim: str | None = None,
                 script: str = SCRIPT) -> None:
        self.root = root
        self.shim = shim
        self.script = script
        self.live = os.path.join(root, "live")
        self.ctx = os.path.join(root, "ctx")
        self.env_file = os.path.join(self.live, "deploy", ".env")
        self.cert_dir = os.path.join(self.live, "deploy", "secrets", "certs")
        self.cert_mount = cert_mount
        self.allowlist = allowlist
        self.registration = registration

        self.write_certs(host_certs)
        self.write(os.path.join(self.ctx, "deploy", "docker", "control-plane.Dockerfile"),
                   "FROM %s\nCMD [\"sleep\", \"3600\"]\n" % BASE_LOCAL)
        self.write(os.path.join(self.ctx, "deploy", "docker", "portal.Dockerfile"),
                   "FROM %s\nCMD [\"sleep\", \"3600\"]\n" % BASE_LOCAL)
        self.write_compose()
        self.write_env(allowlist, registration, crlf=False)

    # -- fixture files ---------------------------------------------------------
    def write(self, path: str, text: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)

    def write_bytes(self, path: str, data: bytes) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(data)

    def write_certs(self, present: bool) -> None:
        if not present:
            shutil.rmtree(self.cert_dir, ignore_errors=True)
            return
        self.write_bytes(os.path.join(self.cert_dir, "fullchain.pem"),
                         b"-----BEGIN CERTIFICATE-----\nDEPLOY-TEST\n")
        self.write_bytes(os.path.join(self.cert_dir, "privkey.pem"),
                         b"-----BEGIN PRIVATE KEY-----\nDEPLOY-TEST\n")

    def write_compose(self) -> None:
        self.write(os.path.join(self.live, "deploy", "compose.prod.yaml"), """services:
  control-plane:
    image: miqrokey-control-plane:%(tag)s
    command: ["sleep", "3600"]
    environment:
      MIQROKEY_ORIGIN_ALLOWLIST: "${MIQROKEY_ORIGIN_ALLOWLIST:-none}"
      MIQROKEY_REGISTRATION_ENABLED: "false"
  portal:
    image: miqrokey-portal:%(tag)s
    command: ["sleep", "3600"]
    volumes:
      - %(mount)s
""" % {"tag": TAG, "mount": self.cert_mount})

    def write_env(self, allowlist: str, registration: str = "false", crlf: bool = False) -> None:
        lines = ["MIQROKEY_ORIGIN_ALLOWLIST=%s" % allowlist,
                 "MIQROKEY_REGISTRATION_ENABLED=%s" % registration]
        eol = "\r\n" if crlf else "\n"
        self.write_bytes(self.env_file, eol.join(lines).encode() + eol.encode())

    def set_mount(self, mount: str) -> None:
        self.cert_mount = mount
        self.write_compose()

    # -- running the script ----------------------------------------------------
    def run(self, *extra: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["MIQROKEY_LIVE_DIR"] = self.live
        env["MIQROKEY_COMPOSE_PROJECT"] = PROJECT
        env["MIQROKEY_IMAGE_TAG"] = TAG
        if self.shim:
            env["PATH"] = self.shim + os.pathsep + env["PATH"]
        cmd = ["sh", self.script, "--context", self.ctx,
               "--services", "control-plane portal", "--caller", "regression"]
        return subprocess.run(cmd + list(extra), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=env)

    def output(self, proc: subprocess.CompletedProcess) -> str:
        return (proc.stdout or "") + (proc.stderr or "")


def verified_ok(proc: subprocess.CompletedProcess, needle: str) -> str:
    body = (proc.stdout or "") + (proc.stderr or "")
    return "" if needle in body else "expected %r, got exit %d:\n%s" % (
        needle, proc.returncode, body[-600:])


def prepare_base() -> None:
    """Pull the pinned base once and give it a local name the fixtures can build from."""
    if subprocess.run(["docker", "image", "inspect", BASE_LOCAL],
                      capture_output=True).returncode == 0:
        return
    pull = subprocess.run(["docker", "pull", "-q", BASE_IMAGE], capture_output=True, text=True)
    if pull.returncode != 0:
        raise SystemExit("cannot pull the pinned base image %s:\n%s" % (BASE_IMAGE, pull.stderr))
    subprocess.run(["docker", "tag", BASE_IMAGE, BASE_LOCAL], check=True, capture_output=True)


def require_docker() -> None:
    """Say plainly when the environment, not the code, is what is missing.

    This gate drives real containers, so it cannot run without a daemon. Failing
    with a clear sentence keeps a missing prerequisite from being read as a broken
    deploy script — the two need different responses from whoever sees it red.
    """
    if shutil.which("docker") is None:
        raise SystemExit("docker is not available: this gate drives real containers, so it "
                         "cannot run here. That is an environment gap, not a finding about "
                         "deploy/deploy.sh.")
    probe = subprocess.run(["docker", "info"], capture_output=True)
    if probe.returncode != 0:
        raise SystemExit("the docker daemon is not reachable: this gate drives real "
                         "containers. Environment gap, not a finding about deploy/deploy.sh.")


def flock_shim(root: str) -> str | None:
    """A no-op flock for platforms without util-linux (Windows, Git Bash).

    Without it the script exits 1 before doing anything, so the harness would be
    unrunnable on a developer's Windows box — and `docs/deployment-and-operations.md`
    §9 says those are supported. The lock is not what these scenarios test, and on
    Linux (CI) the real one is used and exercised.
    """
    if shutil.which("flock"):
        return None
    shim = os.path.join(root, "shim")
    os.makedirs(shim, exist_ok=True)
    path = os.path.join(shim, "flock")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("#!/bin/sh\n# stand-in for util-linux flock; see deploy_script_regression.py\nexit 0\n")
    os.chmod(path, 0o755)
    return shim


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="keep the temp tree for inspection")
    parser.add_argument("--script", default=SCRIPT,
                        help="script under test; point it at a mutated copy to check "
                             "that these scenarios actually go red")
    args = parser.parse_args()

    if not os.path.exists(args.script):
        print("cannot find %s" % args.script, file=sys.stderr)
        return 2

    require_docker()
    prepare_base()
    root = tempfile.mkdtemp(prefix="deploy-regression-")
    shim = flock_shim(root)
    stub = Stub()
    print("workspace: %s\nstub origin: %s%s\n" % (
        root, stub.origin, "\nflock: shimmed (no util-linux here)" if shim else ""), flush=True)

    try:
        # -- the .env echo ----------------------------------------------------
        print("== env echo (#807) ==")
        fx = Fixture(root, stub.origin, shim=shim, script=args.script)
        fx.run()  # seed in deploy mode

        # A CRLF line is a normal state for a file like this; compose tolerates it,
        # so the assertion must too.
        fx.write_env(stub.origin, crlf=True)
        proc = fx.run("--verify-only")
        check("CRLF line in .env is not reported as a deployment fault",
              proc.returncode == 0, verified_ok(proc, "image identity verified"))

        # A real difference must still fail, and the report must show how: these
        # two print alike, so only the length and the byte dump distinguish them.
        fx.write_env(stub.origin, registration="FALSE")
        proc = fx.run("--verify-only")
        body = fx.output(proc)
        check("a genuine difference still fails (same length, different bytes)",
              proc.returncode == 2 and "MIQROKEY_REGISTRATION_ENABLED" in body,
              "exit %d:\n%s" % (proc.returncode, body[-600:]))
        check("and the report shows the bytes, not two identical strings",
              "len 5" in body and "F   A   L   S   E" in body,
              "the byte dump (od -c) is missing, so the report has no content:\n%s"
              % body[-600:])
        fx.write_env(stub.origin)

        # -- the smoke --------------------------------------------------------
        print("\n== smoke observes an origin rejection (#809) ==")
        fx.set_mount("./secrets/certs:/etc/nginx/certs:ro")
        fx.run()  # settle the mount back to its correct source

        stub.login_status = 403
        proc = fx.run("--verify-only")
        check("a stack rejecting its own origin fails the deploy",
              proc.returncode == 2 and "SMOKE FAILED" in fx.output(proc),
              "exit %d:\n%s" % (proc.returncode, fx.output(proc)[-600:]))

        # -- #1038: the backend is not up yet --------------------------------
        print("\n== a 5xx from a backend that is still starting (#1038) ==")
        os.environ["MIQROKEY_DEPLOY_SMOKE_RETRY_SECONDS"] = "100"  # keep the run short
        stub.login_status = 400
        stub.login_sequence = [502, 502]  # cold start, then ready
        proc = fx.run("--verify-only")
        body = fx.output(proc)
        retry_detail = "exit %d:\n%s" % (proc.returncode, body[-600:])
        check("a 502 that clears within the retry window is not a failed deploy",
              proc.returncode == 0 and "answered after 2 retries" in body, retry_detail)
        check("and the wait is visible, so a slow start is not silent",
              "not ready yet (502)" in body, body[-400:])

        # 403 is an answer, not a delay: it must be classified at once.
        stub.login_sequence = [403, 400]
        proc = fx.run("--verify-only")
        forbidden_detail = "exit %d:\n%s" % (proc.returncode, fx.output(proc)[-600:])
        check("a 403 is never retried into a pass",
              proc.returncode == 2 and "SMOKE FAILED" in fx.output(proc), forbidden_detail)
        del os.environ["MIQROKEY_DEPLOY_SMOKE_RETRY_SECONDS"]

        stub.login_status = 400
        stub.requests.clear()
        proc = fx.run("--verify-only")
        check("a healthy stack passes", proc.returncode == 0,
              verified_ok(proc, "smoke ok"))
        check("the derived target is a POST to the login route, carrying the origin",
              any(r[0] == "POST" and r[1] == "/api/v1/auth/login"
                  and r[2] == stub.origin and r[3] == "application/json"
                  for r in stub.requests),
              "observed requests: %r" % (stub.requests,))

        stub.requests.clear()
        proc = fx.run("--verify-only", "--smoke-url", "%s/alt405" % stub.origin,
                      "--smoke-expect", "200|405")
        check("alternatives in the expect pattern are honoured (405 allowed)",
              proc.returncode == 0 and "-> 405" in fx.output(proc),
              "exit %d:\n%s" % (proc.returncode, fx.output(proc)[-400:]))

        stub.requests.clear()
        proc = fx.run("--verify-only", "--smoke-url", "%s/" % stub.origin)
        check("an operator-supplied URL is still fetched with GET",
              proc.returncode == 0 and any(r[0] == "GET" and r[1] == "/" for r in stub.requests)
              and not any(r[0] == "POST" for r in stub.requests),
              "observed requests: %r" % (stub.requests,))

        dead = closed_port()
        fx.write_env("http://127.0.0.1:%d" % dead)
        fx.run()
        proc = fx.run("--verify-only")
        check("an unreachable target warns rather than failing",
              proc.returncode == 0 and "smoke WARNING" in fx.output(proc),
              "exit %d:\n%s" % (proc.returncode, fx.output(proc)[-400:]))
        fx.write_env(stub.origin)

        # -- the certificates -------------------------------------------------
        print("\n== certificates reach the container (#812) ==")
        fx.run()

        fx.set_mount("./elsewhere/certs:/etc/nginx/certs:ro")
        proc = fx.run()
        body = fx.output(proc)
        check("a certs mount that lands elsewhere fails the deploy",
              proc.returncode == 2 and "certificates mounted from" in body,
              "exit %d:\n%s" % (proc.returncode, body[-600:]))

        fx.set_mount("./secrets/certs:/etc/nginx/certs:ro")
        proc = fx.run()
        check("a correct mount is verified as present inside the container",
              proc.returncode == 0
              and "certificates present inside the container" in fx.output(proc),
              verified_ok(proc, "certificates present inside the container"))

        shutil.rmtree(fx.cert_dir, ignore_errors=True)
        proc = fx.run("--verify-only")
        check("a host without certificates is a note, not a failure",
              proc.returncode == 0 and "has no certificates" in fx.output(proc),
              "exit %d:\n%s" % (proc.returncode, fx.output(proc)[-400:]))
        fx.write_certs(True)

    finally:
        stub.stop()
        compose_dir = os.path.join(root, "live", "deploy")
        if os.path.isdir(compose_dir):
            subprocess.run(["docker", "compose", "-p", PROJECT, "down", "-v", "--remove-orphans"],
                           cwd=compose_dir, capture_output=True)
        for svc in ("control-plane", "portal"):
            subprocess.run(["docker", "rmi", "-f", "miqrokey-%s:%s" % (svc, TAG)],
                           capture_output=True)
        subprocess.run(["docker", "rmi", "-f", BASE_LOCAL], capture_output=True)
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
