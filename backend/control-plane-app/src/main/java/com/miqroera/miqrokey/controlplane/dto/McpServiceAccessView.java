package com.miqroera.miqrokey.controlplane.dto;

import java.util.UUID;

/**
 * MCP service access information (#685): the gateway-side URLs a client should
 * call for this service, derived from the configured gateway base URL and the
 * service name — the console counterpart of the data-plane
 * {@code /mcpservers/{name}/mcp} routing. {@code authHint} states the accepted
 * credential shape without ever carrying a secret.
 */
public record McpServiceAccessView(UUID serviceId, String name, String mcpUrl, String sseUrl, String authHint) {
}
