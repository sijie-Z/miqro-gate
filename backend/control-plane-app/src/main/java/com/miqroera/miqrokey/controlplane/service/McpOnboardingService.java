package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.McpServiceAccessView;
import com.miqroera.miqrokey.controlplane.dto.McpServiceVerifyView;
import com.miqroera.miqrokey.domain.model.McpService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * MCP onboarding closure (#685, Tencent "调用验证" step): the gateway-side access
 * URLs a client should call, and an on-demand connectivity probe that reuses
 * the health checker's probe shapes without touching stored health telemetry.
 * Both operations are read-only and tenant-scoped.
 */
@Service
public class McpOnboardingService {

    /** Same credential story as the data plane (ADR-0013): consumer keys only. */
    static final String AUTH_HINT = "认证：Authorization: Bearer <消费者 API Key 或 JWT>（需 mcp:call 作用域）";

    private final AdminMcpService adminMcpService;
    private final McpHealthChecker healthChecker;
    private final AuthProperties authProperties;

    public McpOnboardingService(AdminMcpService adminMcpService, McpHealthChecker healthChecker,
            AuthProperties authProperties) {
        this.adminMcpService = adminMcpService;
        this.healthChecker = healthChecker;
        this.authProperties = authProperties;
    }

    /** Gateway URLs for this service, derived exactly like the data-plane route. */
    public McpServiceAccessView access(UUID tenantId, UUID serviceId) {
        McpService service = adminMcpService.get(tenantId, serviceId);
        String base = trimTrailingSlash(authProperties.getGatewayBaseUrl());
        String segment = UriUtils.encodePathSegment(service.name(), StandardCharsets.UTF_8);
        return new McpServiceAccessView(service.id(), service.name(), base + "/mcpservers/" + segment + "/mcp",
                base + "/mcpservers/" + segment + "/sse", AUTH_HINT);
    }

    /** Immediate probe of the upstream; never mutates health state. */
    public McpServiceVerifyView verify(UUID tenantId, UUID serviceId) {
        McpService service = adminMcpService.get(tenantId, serviceId);
        long start = System.nanoTime();
        McpHealthChecker.ProbeResult result = healthChecker.probeOnce(service);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new McpServiceVerifyView(service.id(), result.healthy(), service.checkMode(), latencyMs, result.detail(),
                Instant.now());
    }

    private static String trimTrailingSlash(String base) {
        return base != null && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
