/**
 * Upstream forwarding (CAA Spec v1.1 §3.3): byte-exact passthrough — method,
 * path, query, body and the caller's Authorization are forwarded unchanged
 * (only `X-Miqro-*` hygiene + claims differ). SSE streams chunk by chunk;
 * aborts propagate in both directions.
 */
import http from "node:http";
import https from "node:https";
import type { IncomingMessage, ServerResponse } from "node:http";

export type ForwardOptions = {
  targetBaseUrl: string;
  method: string;
  url: string;
  headers: NodeJS.Dict<string | string[]>;
  body: Buffer;
};

export function forward(options: ForwardOptions, clientRes: ServerResponse, clientReq: IncomingMessage): Promise<void> {
  const target = new URL(options.url, options.targetBaseUrl);
  const transport = target.protocol === "https:" ? https : http;
  const headers: NodeJS.Dict<string | string[]> = { ...options.headers };
  delete headers["host"];
  delete headers["Host"];
  headers["host"] = target.host;
  if (options.body.length > 0 || ["POST", "PUT", "PATCH"].includes(options.method)) {
    headers["content-length"] = String(options.body.length);
  }

  return new Promise((resolve) => {
    const upstream = transport.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || (target.protocol === "https:" ? 443 : 80),
        path: target.pathname + target.search,
        method: options.method,
        headers,
      },
      (upstreamRes) => {
        const status = upstreamRes.statusCode ?? 502;
        const responseHeaders: NodeJS.Dict<string | string[]> = { ...upstreamRes.headers };
        // Hop-by-hop / length framing is re-established by this hop.
        delete responseHeaders["connection"];
        delete responseHeaders["keep-alive"];
        delete responseHeaders["transfer-encoding"];
        delete responseHeaders["content-length"];
        clientRes.writeHead(status, responseHeaders);
        upstreamRes.pipe(clientRes);
        upstreamRes.on("end", () => resolve());
        upstreamRes.on("error", () => {
          clientRes.destroy();
          resolve();
        });
      },
    );
    upstream.on("error", (err) => {
      if (!clientRes.headersSent) {
        clientRes.writeHead(502, { "content-type": "application/json" });
      }
      clientRes.end(
        JSON.stringify({
          error: { type: "agent_upstream_unreachable", message: `miqro-context could not reach the gateway: ${err.message}` },
        }),
      );
      resolve();
    });

    // Abort propagation: client gone → kill upstream; upstream gone → close client.
    clientReq.on("aborted", () => upstream.destroy());
    clientRes.on("close", () => {
      if (!clientRes.writableEnded) upstream.destroy();
    });

    if (options.body.length > 0) upstream.write(options.body);
    upstream.end();
  });
}
