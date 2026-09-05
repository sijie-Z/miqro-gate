package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy.RetryCondition;
import com.miqroera.miqrokey.domain.repository.McpResilienceRepository;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F12/F13 resilience configuration of one MCP service ({@code
 * mcp_resilience_policy} V30, api-contract §5.25): retry gate + circuit
 * breaker, both default OFF. Every cross-field rule lives here:
 *
 * <ul>
 * <li>retry enabled requires 1..5 retries and at least one trigger condition;
 * POST/PUT/PATCH tool calls additionally need the idempotency confirmation
 * (validated at runtime against each tool's HTTP method, doc 134831);</li>
 * <li>breaker enabled requires at least one of the error-ratio / slow-call
 * triggers; the slow-call threshold must stay below the service's own
 * {@code check_timeout_seconds} (doc 134859: otherwise slow calls can never be
 * observed), and probe-success must not exceed the probe count.</li>
 * </ul>
 */
@Service
public class AdminMcpResilienceService {

    private final McpServiceRepository serviceRepository;
    private final McpResilienceRepository resilienceRepository;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminMcpResilienceService(McpServiceRepository serviceRepository,
            McpResilienceRepository resilienceRepository, AuditService auditService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.serviceRepository = serviceRepository;
        this.resilienceRepository = resilienceRepository;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    public McpResiliencePolicy view(UUID tenantId, UUID serviceId) {
        requireService(tenantId, serviceId);
        return resilienceRepository.find(tenantId, serviceId).orElse(McpResiliencePolicy.disabled());
    }

    @Transactional
    public McpResiliencePolicy configure(UUID tenantId, UUID adminId, UUID serviceId, RequestedPolicy requested,
            String requestId) {
        var service = requireService(tenantId, serviceId);
        McpResiliencePolicy policy = build(tenantId, requested, service.checkTimeoutSeconds());
        McpResiliencePolicy stored = resilienceRepository.upsert(tenantId, serviceId, policy, adminId);
        auditService.record(tenantId, adminId, "MCP_RESILIENCE_UPDATE", "MCP_SERVICE", serviceId,
                "{\"retryEnabled\":" + stored.retryEnabled() + ",\"breakerEnabled\":" + stored.breakerEnabled()
                        + ",\"version\":" + stored.version() + "}",
                requestId);
        // The data plane reads the policy through the route snapshot.
        routeRefreshPublisher.publishChanged();
        return stored;
    }

    /** Raw request shape (all defaults = the disabled policy). */
    public record RequestedPolicy(Boolean retryEnabled, Integer retryMax, Set<String> retryConditions,
            Boolean idempotencyConfirmed, Boolean breakerEnabled, Integer breakerWindowSeconds,
            Integer breakerMinRequests, Boolean breakerErrorEnabled, Integer breakerErrorRatio,
            Set<Integer> breakerErrorStatusCodes, Boolean breakerSlowEnabled, Integer breakerSlowCallMs,
            Integer breakerSlowRatio, Integer breakerOpenSeconds, Integer breakerProbeCount,
            Integer breakerProbeSuccess, Boolean breakerSkipRetry) {
    }

    private McpResiliencePolicy build(UUID tenantId, RequestedPolicy r, int checkTimeoutSeconds) {
        McpResiliencePolicy defaults = McpResiliencePolicy.disabled();
        boolean retryEnabled = r.retryEnabled() != null ? r.retryEnabled() : defaults.retryEnabled();
        boolean breakerEnabled = r.breakerEnabled() != null ? r.breakerEnabled() : defaults.breakerEnabled();
        Set<RetryCondition> conditions = new LinkedHashSet<>();
        if (r.retryConditions() != null) {
            for (String raw : r.retryConditions()) {
                try {
                    conditions.add(RetryCondition.valueOf(raw));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID",
                            "unknown retry condition: " + raw);
                }
            }
        }
        Set<Integer> codes = new LinkedHashSet<>(
                r.breakerErrorStatusCodes() == null ? defaults.breakerErrorStatusCodes() : r.breakerErrorStatusCodes());
        int slowMs = r.breakerSlowCallMs() != null ? r.breakerSlowCallMs() : defaults.breakerSlowCallMs();
        int probeCount = r.breakerProbeCount() != null ? r.breakerProbeCount() : defaults.breakerProbeCount();
        int probeSuccess = r.breakerProbeSuccess() != null ? r.breakerProbeSuccess() : defaults.breakerProbeSuccess();
        boolean errorEnabled = r.breakerErrorEnabled() != null
                ? r.breakerErrorEnabled()
                : defaults.breakerErrorEnabled();
        boolean slowEnabled = r.breakerSlowEnabled() != null ? r.breakerSlowEnabled() : defaults.breakerSlowEnabled();

        if (retryEnabled && (r.retryMax() == null || r.retryMax() < 1 || r.retryMax() > 5)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID", "retryMax must be 1..5");
        }
        if (retryEnabled && conditions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID",
                    "at least one retry condition is required when retries are enabled");
        }
        if (breakerEnabled && !errorEnabled && !slowEnabled) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID",
                    "at least one breaker trigger (error ratio or slow calls) must be enabled");
        }
        if (slowEnabled && slowMs >= checkTimeoutSeconds * 1000L) {
            // Doc 134859: the slow-call threshold must stay below the backend
            // timeout, otherwise slow calls are never observable.
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_SLOW_EXCEEDS_TIMEOUT",
                    "breakerSlowCallMs must be below the service check timeout (" + (checkTimeoutSeconds * 1000L)
                            + " ms)");
        }
        if (probeSuccess > probeCount) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID",
                    "breakerProbeSuccess must not exceed breakerProbeCount");
        }
        try {
            return new McpResiliencePolicy(retryEnabled, r.retryMax() != null ? r.retryMax() : 1, conditions,
                    r.idempotencyConfirmed() != null ? r.idempotencyConfirmed() : defaults.idempotencyConfirmed(),
                    breakerEnabled,
                    r.breakerWindowSeconds() != null ? r.breakerWindowSeconds() : defaults.breakerWindowSeconds(),
                    r.breakerMinRequests() != null ? r.breakerMinRequests() : defaults.breakerMinRequests(),
                    errorEnabled, r.breakerErrorRatio() != null ? r.breakerErrorRatio() : defaults.breakerErrorRatio(),
                    codes, slowEnabled, slowMs,
                    r.breakerSlowRatio() != null ? r.breakerSlowRatio() : defaults.breakerSlowRatio(),
                    r.breakerOpenSeconds() != null ? r.breakerOpenSeconds() : defaults.breakerOpenSeconds(), probeCount,
                    probeSuccess, r.breakerSkipRetry() != null ? r.breakerSkipRetry() : defaults.breakerSkipRetry(), 0);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_INVALID", e.getMessage());
        }
    }

    private com.miqroera.miqrokey.domain.model.McpService requireService(UUID tenantId, UUID serviceId) {
        return serviceRepository.findByIdAndTenantId(serviceId, tenantId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP service not found"));
    }
}
