package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.McpAccessView;
import com.miqroera.miqrokey.controlplane.dto.McpAccessView.ConsumerRef;
import com.miqroera.miqrokey.controlplane.dto.McpAccessView.ToolAccess;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.model.McpAccessGrant;
import com.miqroera.miqrokey.domain.model.McpAclMode;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.model.McpServiceAccess;
import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import com.miqroera.miqrokey.domain.repository.McpAccessRepository;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-level MCP access control (api-contract §5.21, Tencent AI gateway doc
 * 134890): who may call an MCP server and who may call each tool. The server
 * mode is NONE (open) / ALLOW (whitelist) / DENY (blacklist); tool overrides
 * only exist while the server is open and only narrow the server decision.
 * Decisions are made by {@link McpAccessPolicy} at call time; this service
 * manages the plan and audit-trails every change.
 */
@Service
public class AdminMcpAccessService {

    private final McpAccessRepository accessRepository;
    private final McpServiceRepository serviceRepository;
    private final McpToolRepository toolRepository;
    private final ApiConsumerRepository consumerRepository;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminMcpAccessService(McpAccessRepository accessRepository, McpServiceRepository serviceRepository,
            McpToolRepository toolRepository, ApiConsumerRepository consumerRepository, AuditService auditService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.accessRepository = accessRepository;
        this.serviceRepository = serviceRepository;
        this.toolRepository = toolRepository;
        this.consumerRepository = consumerRepository;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    public McpAccessView view(UUID tenantId, UUID serviceId) {
        McpService service = requireService(tenantId, serviceId);
        McpServiceAccess access = accessRepository.findService(tenantId, serviceId).orElse(null);
        McpAclMode mode = access == null ? McpAclMode.NONE : access.mode();

        List<ConsumerRef> serverConsumers = new ArrayList<>();
        Map<UUID, List<McpAccessGrant>> toolGrants = new LinkedHashMap<>();
        if (access != null) {
            for (McpAccessGrant grant : accessRepository.findGrants(tenantId, access.id())) {
                if (grant.toolId() == null) {
                    serverConsumers.add(ref(tenantId, grant.consumerId()));
                } else {
                    toolGrants.computeIfAbsent(grant.toolId(), k -> new ArrayList<>()).add(grant);
                }
            }
        }
        List<ToolAccess> tools = new ArrayList<>();
        for (McpTool tool : toolRepository.findAllByService(tenantId, serviceId)) {
            List<McpAccessGrant> grants = toolGrants.get(tool.id());
            if (grants == null || grants.isEmpty()) {
                tools.add(new ToolAccess(tool.id(), tool.toolName(), null, List.of()));
                continue;
            }
            // One tool scope is always replaced in a single mode.
            List<ConsumerRef> consumers = grants.stream().map(g -> ref(tenantId, g.consumerId())).toList();
            tools.add(new ToolAccess(tool.id(), tool.toolName(), grants.get(0).mode(), consumers));
        }
        return new McpAccessView(serviceId, service.name(), mode, serverConsumers, tools);
    }

    /** Sets the server-level mode. Switching to NONE drops the server list. */
    @Transactional
    public McpAccessView setMode(UUID tenantId, UUID adminId, UUID serviceId, McpAclMode mode, String requestId) {
        McpService service = requireService(tenantId, serviceId);
        McpServiceAccess existing = accessRepository.findService(tenantId, serviceId).orElse(null);
        Instant now = Instant.now();
        McpServiceAccess stored = accessRepository
                .upsertService(new McpServiceAccess(existing == null ? UUID.randomUUID() : existing.id(), tenantId,
                        serviceId, mode, adminId, existing == null ? 0 : existing.version(), now, now));
        if (mode == McpAclMode.NONE) {
            accessRepository.clearGrants(tenantId, stored.id(), null);
        }
        auditService.record(tenantId, adminId, "MCP_ACCESS_MODE", "MCP_SERVICE", serviceId,
                "{\"mode\":\"" + mode.name() + "\"}", requestId);
        routeRefreshPublisher.publishChanged();
        return view(tenantId, serviceId);
    }

    /** Replaces one scope (server list or a tool override) with a fresh list. */
    @Transactional
    public McpAccessView replaceGrants(UUID tenantId, UUID adminId, UUID serviceId, UUID toolId, McpAclMode mode,
            List<UUID> consumerIds, String requestId) {
        McpService service = requireService(tenantId, serviceId);
        McpServiceAccess access = ensureAccess(tenantId, serviceId, adminId);
        if (toolId != null) {
            McpTool tool = toolRepository.findByIdAndTenantId(toolId, tenantId)
                    .filter(t -> t.mcpServiceId().equals(serviceId))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND",
                            "The tool does not belong to this MCP service"));
            if (access.mode() != McpAclMode.NONE) {
                throw new ApiException(HttpStatus.CONFLICT, "TOOL_ACL_UNSUPPORTED",
                        "Tool-level overrides require the server to be open (NONE), per the upstream access model");
            }
        } else if (access.mode() == McpAclMode.NONE) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVER_LIST_UNSUPPORTED",
                    "An open server (NONE) has no server-level list; set ALLOW or DENY first");
        }
        List<UUID> validated = new ArrayList<>(consumerIds.size());
        for (UUID consumerId : consumerIds) {
            ApiConsumer consumer = consumerRepository.findByIdAndTenantId(consumerId, tenantId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CONSUMER_NOT_FOUND",
                            "One or more consumers do not exist in this tenant"));
            if (!"ACTIVE".equals(consumer.status())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CONSUMER_NOT_ACTIVE",
                        "Only ACTIVE consumers can be granted access");
            }
            validated.add(consumerId);
        }
        Instant now = Instant.now();
        List<McpAccessGrant> grants = validated.stream().map(cid -> new McpAccessGrant(UUID.randomUUID(), tenantId,
                access.id(), toolId, cid, mode, adminId, 0, now, now)).toList();
        accessRepository.replaceGrants(tenantId, access.id(), toolId, grants);
        auditService.record(tenantId, adminId, "MCP_ACCESS_GRANTS", "MCP_SERVICE", serviceId,
                "{\"toolId\":" + (toolId == null ? "null" : "\"" + toolId + "\"") + ",\"mode\":\"" + mode.name()
                        + "\",\"consumers\":" + validated.size() + "}",
                requestId);
        routeRefreshPublisher.publishChanged();
        return view(tenantId, serviceId);
    }

    /**
     * Removes a scope: the server list (server returns to NONE/open) or one tool's
     * override (the tool returns to inheriting the server rule).
     */
    @Transactional
    public McpAccessView clear(UUID tenantId, UUID adminId, UUID serviceId, UUID toolId, String requestId) {
        McpService service = requireService(tenantId, serviceId);
        McpServiceAccess access = accessRepository.findService(tenantId, serviceId).orElse(null);
        if (access == null) {
            return view(tenantId, serviceId); // nothing configured — nothing to clear
        }
        accessRepository.clearGrants(tenantId, access.id(), toolId);
        if (toolId == null) {
            Instant now = Instant.now();
            accessRepository.upsertService(new McpServiceAccess(access.id(), tenantId, serviceId, McpAclMode.NONE,
                    adminId, access.version(), access.createdAt(), now));
        }
        auditService.record(tenantId, adminId, "MCP_ACCESS_RESET", "MCP_SERVICE", serviceId,
                "{\"toolId\":" + (toolId == null ? "null" : "\"" + toolId + "\"") + "}", requestId);
        routeRefreshPublisher.publishChanged();
        return view(tenantId, serviceId);
    }

    // -------------------------------------------------------------------

    private McpService requireService(UUID tenantId, UUID serviceId) {
        return serviceRepository.findByIdAndTenantId(serviceId, tenantId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP service not found"));
    }

    private McpServiceAccess ensureAccess(UUID tenantId, UUID serviceId, UUID adminId) {
        McpServiceAccess existing = accessRepository.findService(tenantId, serviceId).orElse(null);
        if (existing != null) {
            return existing;
        }
        Instant now = Instant.now();
        return accessRepository.upsertService(
                new McpServiceAccess(UUID.randomUUID(), tenantId, serviceId, McpAclMode.NONE, adminId, 0, now, now));
    }

    private ConsumerRef ref(UUID tenantId, UUID consumerId) {
        ApiConsumer consumer = consumerRepository.findByIdAndTenantId(consumerId, tenantId).orElse(null);
        return consumer == null
                ? new ConsumerRef(consumerId, "deleted consumer")
                : new ConsumerRef(consumerId, consumer.name());
    }
}
