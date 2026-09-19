import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";

/** A stand-in gateway that answers every request with `status`. */
function gatewayStub(status: number): Promise<{ url: string; server: Server }> {
  return new Promise((resolve) => {
    const server = http.createServer((_req, res) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(JSON.stringify({ object: "list", data: [] }));
    });
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address() as AddressInfo;
      resolve({ url: `http://127.0.0.1:${port}`, server });
    });
  });
}

/** Run the real CLI (`miqro-context doctor`) and report what the user sees. */
function runDoctor(gatewayUrl: string): Promise<{ out: string; code: number }> {
  const cli = fileURLToPath(new URL("../src/cli/index.js", import.meta.url));
  return new Promise((resolve, reject) => {
    execFile(
      process.execPath,
      [cli, "doctor"],
      {
        timeout: 30_000,
        windowsHide: true,
        env: {
          ...process.env,
          MIQRO_CONTEXT_GATEWAY_URL: gatewayUrl,
          // Hermetic: never read the developer's real ~/.miqro/context.json.
          MIQRO_CONTEXT_CONFIG: "definitely/not/a/real/context.json",
          MIQRO_CONTEXT_VIRTUAL_KEY: "",
        },
      },
      (error, stdout, stderr) => {
        const out = `${stdout}${stderr}`;
        if (error === null) return resolve({ out, code: 0 });
        // execFile reports the exit status as a number; anything else (spawn
        // failure, timeout) is surfaced as a test error instead of a fake code.
        const code = (error as Error & { code?: unknown }).code;
        if (typeof code !== "number") return reject(error);
        resolve({ out, code });
      },
    );
  });
}

test("doctor: a gateway that answers HTTP 503 is a FAILURE, not a PASS", async () => {
  const { url, server } = await gatewayStub(503);
  try {
    const { out, code } = await runDoctor(url);
    assert.match(out, /\[FAIL\] gateway/, `gateway check should FAIL on HTTP 503; got:\n${out}`);
    assert.equal(code, 1, `doctor must exit non-zero when the gateway is broken; got:\n${out}`);
  } finally {
    server.close();
  }
});

test("doctor: a healthy gateway (HTTP 200) passes and exits 0", async () => {
  const { url, server } = await gatewayStub(200);
  try {
    const { out, code } = await runDoctor(url);
    assert.match(out, /\[PASS\] gateway/);
    assert.equal(code, 0, `healthy gateway must not be reported as a failure; got:\n${out}`);
  } finally {
    server.close();
  }
});

test("doctor: a gateway that is not listening at all fails", async () => {
  const { url, server } = await gatewayStub(200);
  const closed = url;
  await new Promise<void>((resolve) => server.close(() => resolve()));
  const { out, code } = await runDoctor(closed); // nothing is listening there any more
  assert.match(out, /\[FAIL\] gateway/);
  assert.equal(code, 1);
});
