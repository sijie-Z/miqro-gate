package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy.RetryCondition;
import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.model.McpToolRetryPolicy;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRetryRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tool-level retry override (issue #360, I13, raw doc 12): per-tool retry
 * fields layered over the service-level resilience policy. Validation mirrors
 * the service-level endpoint (retryMax 1..5, at least one condition when
 * enabled, unknown conditions rejected); a missing row means "no override".
 */
@Service
public class AdminMcpToolRetryService {

    private final McpServiceRepository serviceRepository;
    private final McpToolRepository toolRepository;
    private final McpToolRetryRepository retryRepository;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminMcpToolRetryService(McpServiceRepository serviceRepository, McpToolRepository toolRepository,
            McpToolRetryRepository retryRepository, AuditService auditService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.serviceRepository = serviceRepository;
        this.toolRepository = toolRepository;
        this.retryRepository = retryRepository;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    public McpToolRetryPolicy view(UUID tenantId, UUID serviceId, UUID toolId) {
        requireTool(tenantId, serviceId, toolId);
        return retryRepository.find(tenantId, toolId).orElse(McpToolRetryPolicy.disabled());
    }

    @Transactional
    public McpToolRetryPolicy configure(UUID tenantId, UUID adminId, UUID serviceId, UUID toolId,
            RequestedPolicy requested, String requestId) {
        McpTool tool = requireTool(tenantId, serviceId, toolId);
        McpToolRetryPolicy policy = build(requested);
        McpToolRetryPolicy stored = retryRepository.upsert(tenantId, toolId, policy, adminId);
        auditService.record(tenantId, adminId, "MCP_TOOL_RETRY_UPDATE", "MCP_TOOL", toolId,
                AuditSummaries.summary("tool", tool.toolName(), "retryEnabled", stored.retryEnabled(), "retryMax",
                        stored.retryMax(), "version", stored.version()),
                requestId);
        // The data plane reads the override through the route snapshot.
        routeRefreshPublisher.publishChanged();
        return stored;
    }

    /** Raw request shape (all defaults = the disabled override). */
    public record RequestedPolicy(Boolean retryEnabled, Integer retryMax, Set<String> retryConditions,
            Boolean idempotencyConfirmed) {
    }

    private McpToolRetryPolicy build(RequestedPolicy r) {
        McpToolRetryPolicy defaults = McpToolRetryPolicy.disabled();
        boolean retryEnabled = r.retryEnabled() != null ? r.retryEnabled() : defaults.retryEnabled();
        Set<RetryCondition> conditions = new LinkedHashSet<>();
        if (r.retryConditions() != null) {
            for (String raw : r.retryConditions()) {
                try {
                    conditions.add(RetryCondition.valueOf(raw));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_RETRY_POLICY_INVALID",
                            "unknown retry condition: " + raw);
                }
            }
        }
        int retryMax = r.retryMax() != null ? r.retryMax() : defaults.retryMax();
        if (retryEnabled && (r.retryMax() == null || retryMax < 1 || retryMax > 5)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_RETRY_POLICY_INVALID", "retryMax must be 1..5");
        }
        if (retryEnabled && conditions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_RETRY_POLICY_INVALID",
                    "at least one retry condition is required when retries are enabled");
        }
        boolean idempotencyConfirmed = r.idempotencyConfirmed() != null
                ? r.idempotencyConfirmed()
                : defaults.idempotencyConfirmed();
        return new McpToolRetryPolicy(retryEnabled, retryMax, conditions, idempotencyConfirmed, 0);
    }

    private McpTool requireTool(UUID tenantId, UUID serviceId, UUID toolId) {
        serviceRepository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
        McpTool tool = toolRepository.findByIdAndTenantId(toolId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "工具不存在。"));
        if (!tool.mcpServiceId().equals(serviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "工具不存在。");
        }
        return tool;
    }
}
