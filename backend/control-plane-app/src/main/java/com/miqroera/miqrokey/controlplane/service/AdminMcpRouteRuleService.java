package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpHeaderCondition;
import com.miqroera.miqrokey.domain.model.McpRouteRule;
import com.miqroera.miqrokey.domain.model.McpRouteRules;
import com.miqroera.miqrokey.domain.repository.McpRouteRuleRepository;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MCP route rules (F11, Tencent AI gateway doc 135482) — configuration plane
 * only: rules gate which requests may reach a service, the upstream stays the
 * service itself. Each service owns an immutable system {@code default} rule
 * (created with the service, priority 0, no matchers) so the service stays
 * reachable; custom rules default to priority 1000.
 *
 * <p>
 * Validation enforces: reserved name, per-service name uniqueness, RE2 syntax
 * on every REGEX mode, path must start with '/', method whitelist and the
 * 8-header-condition cap. The real-time conflict check blocks two ENABLED rules
 * of one service from describing the identical match surface (equivalence of
 * the canonical condition set; containment of regex surfaces is undecidable, so
 * equivalence is the enforced bound — see api-contract). Enable transitions
 * re-run the check so a dormant duplicate cannot be armed. Enable/disable is
 * idempotent per the upstream doc (unlike tools, where the legacy
 * implementation rejects an unchanged status).
 */
@Service
public class AdminMcpRouteRuleService {

    private final McpRouteRuleRepository routeRepository;
    private final McpServiceRepository serviceRepository;
    private final AuditService auditService;

    public AdminMcpRouteRuleService(McpRouteRuleRepository routeRepository, McpServiceRepository serviceRepository,
            AuditService auditService) {
        this.routeRepository = routeRepository;
        this.serviceRepository = serviceRepository;
        this.auditService = auditService;
    }

    public List<McpRouteRule> list(UUID tenantId, UUID mcpServiceId) {
        requireService(tenantId, mcpServiceId);
        return routeRepository.findAllByService(tenantId, mcpServiceId);
    }

    @Transactional
    public McpRouteRule create(UUID tenantId, UUID adminId, UUID mcpServiceId, String name, String description,
            Integer priority, String pathMode, String pathValue, String hostMode, String hostValue,
            List<String> methods, List<McpHeaderCondition> headers, AuditContext context) {
        requireService(tenantId, mcpServiceId);
        McpRouteRule rule = build(tenantId, adminId, mcpServiceId, null, name, description,
                priority != null ? priority : McpRouteRule.DEFAULT_CUSTOM_PRIORITY, pathMode, pathValue, hostMode,
                hostValue, methods, headers, "ENABLED");
        rejectReservedName(rule.name());
        rejectConflicts(rule, routeRepository.findAllByService(tenantId, mcpServiceId));
        McpRouteRule created;
        try {
            created = routeRepository.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_NAME_TAKEN", "该服务下已存在同名路由。");
        }
        record(tenantId, context, "MCP_ROUTE_RULE_CREATE", created, "name", created.name(), "priority",
                created.priority());
        return created;
    }

    /**
     * Full replace of the editable fields (PUT-like; status lives on its own
     * endpoint). Missing matcher fields mean "cleared/unrestricted" — the edit form
     * always submits the complete rule. Priority falls back to the current value
     * when omitted so a partial client cannot silently reset it.
     */
    @Transactional
    public McpRouteRule update(UUID tenantId, UUID adminId, UUID serviceId, UUID ruleId, String name,
            String description, Integer priority, String pathMode, String pathValue, String hostMode, String hostValue,
            List<String> methods, List<McpHeaderCondition> headers, AuditContext context) {
        McpRouteRule current = find(tenantId, ruleId);
        if (!current.mcpServiceId().equals(serviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "路由不存在。");
        }
        rejectDefault(current);
        McpRouteRule updated = build(tenantId, adminId, serviceId, current.id(), name, description,
                priority != null ? priority : current.priority(), pathMode, pathValue, hostMode, hostValue, methods,
                headers, current.status());
        rejectReservedName(updated.name());
        rejectConflicts(updated, routeRepository.findAllByService(tenantId, serviceId));
        McpRouteRule saved;
        try {
            saved = routeRepository.update(updated, current.version());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_NAME_TAKEN", "该服务下已存在同名路由。");
        }
        // before/after on the two fields that decide which traffic a rule captures.
        record(tenantId, context, "MCP_ROUTE_RULE_UPDATE", saved, "name", saved.name(), "priority", saved.priority(),
                "previousName", current.name(), "previousPriority", current.priority());
        return saved;
    }

    /** Enable/disable; idempotent (same state is a no-op success). */
    @Transactional
    public McpRouteRule setStatus(UUID tenantId, UUID ruleId, String status, AuditContext context) {
        McpRouteRule current = find(tenantId, ruleId);
        rejectDefault(current);
        if (!(status.equals("ENABLED") || status.equals("DISABLED"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_STATUS_INVALID", "状态必须是 ENABLED 或 DISABLED。");
        }
        if (current.status().equals(status)) {
            // Idempotent no-op: nothing changed, so there is nothing to audit.
            return current;
        }
        if (status.equals("ENABLED")) {
            McpRouteRule rearmed = copyWithStatus(current, "ENABLED");
            rejectConflicts(rearmed, routeRepository.findAllByService(tenantId, current.mcpServiceId()));
        }
        McpRouteRule saved = routeRepository.updateStatus(tenantId, ruleId, status, current.version());
        record(tenantId, context, "MCP_ROUTE_RULE_STATUS", saved, "name", saved.name(), "status", saved.status(),
                "previousStatus", current.status());
        return saved;
    }

    @Transactional
    public void delete(UUID tenantId, UUID ruleId, AuditContext context) {
        McpRouteRule current = find(tenantId, ruleId);
        rejectDefault(current);
        routeRepository.deleteById(tenantId, ruleId);
        // After the delete: targetId is the rule that no longer exists, so the
        // summary has to carry the identity the row used to have.
        record(tenantId, context, "MCP_ROUTE_RULE_DELETE", current, "name", current.name(), "priority",
                current.priority());
    }

    /**
     * Writes one audit event for a route-rule mutation. Actor and request
     * correlation come from the caller's {@link AuditContext}; the summary is
     * key/value pairs so the JSON stays queryable.
     */
    private void record(UUID tenantId, AuditContext context, String action, McpRouteRule rule, Object... kv) {
        Object[] pairs = new Object[kv.length + 2];
        pairs[0] = "serviceId";
        pairs[1] = rule.mcpServiceId();
        System.arraycopy(kv, 0, pairs, 2, kv.length);
        auditService.record(tenantId, context.actorId(), action, "MCP_ROUTE_RULE", rule.id(),
                AuditSummaries.summary(context, pairs), context.requestId());
    }

    /**
     * Creates the immutable system default rule — called inside the MCP service
     * creation transaction so every service owns its catch-all row.
     */
    @Transactional
    public McpRouteRule createDefault(UUID tenantId, UUID mcpServiceId) {
        McpRouteRule rule = new McpRouteRule(UUID.randomUUID(), tenantId, mcpServiceId, McpRouteRule.DEFAULT_ROUTE_NAME,
                null, 0, null, null, null, null, null, List.of(), "ENABLED", 0, null, Instant.now(), Instant.now());
        return routeRepository.insert(rule);
    }

    private McpRouteRule build(UUID tenantId, UUID adminId, UUID serviceId, UUID ruleId, String name,
            String description, int priority, String pathMode, String pathValue, String hostMode, String hostValue,
            List<String> methods, List<McpHeaderCondition> headers, String status) {
        if (name == null || name.isBlank() || name.trim().length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_NAME_INVALID", "路由名称必填且不超过 64 字符。");
        }
        if (description != null && description.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_DESCRIPTION_INVALID", "描述不超过 200 字符。");
        }
        if (priority <= 0 || priority > McpRouteRule.MAX_PRIORITY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_PRIORITY_INVALID", "优先级必须在 1-65535 之间（0 为系统默认路由保留）。");
        }
        validateMatcher("路径", pathMode, pathValue, true);
        validateMatcher("Host", hostMode, hostValue, false);
        List<McpHeaderCondition> normalizedHeaders = headers == null ? List.of() : List.copyOf(headers);
        if (normalizedHeaders.size() > McpRouteRule.MAX_HEADER_CONDITIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_HEADERS_TOO_MANY",
                    "Header 条件最多 " + McpRouteRule.MAX_HEADER_CONDITIONS + " 条。");
        }
        String methodCsv = null;
        if (methods != null && !methods.isEmpty()) {
            java.util.TreeSet<String> unique = new java.util.TreeSet<>();
            for (String method : methods) {
                if (!McpRouteRule.HTTP_METHODS.contains(method)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_METHOD_INVALID", "不支持的方法 " + method);
                }
                unique.add(method);
            }
            methodCsv = String.join(",", unique);
        }
        UUID id = ruleId != null ? ruleId : UUID.randomUUID();
        return new McpRouteRule(id, tenantId, serviceId, name.trim(),
                description != null && !description.isBlank() ? description.trim() : null, priority, pathMode,
                pathValue, hostMode, hostValue, methodCsv, normalizedHeaders, status, 0, adminId, Instant.now(),
                Instant.now());
    }

    private static void validateMatcher(String label, String mode, String value, boolean path) {
        if (mode == null && value == null) {
            return;
        }
        if (mode == null || value == null || !McpRouteRule.MATCH_MODES.contains(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_MATCHER_INVALID", label + " 匹配需同时提供方式与值。");
        }
        if (value.length() > 256) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_MATCHER_INVALID", label + " 匹配值不超过 256 字符。");
        }
        if (path && !"REGEX".equals(mode) && !value.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_PATH_INVALID", "路径必须以 / 开头。");
        }
        try {
            McpRouteRules.requireRe2(mode, value);
        } catch (RuntimeException e) {
            // RE2 syntax errors surface as PatternSyntaxException.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_PATTERN_INVALID",
                    label + " 正则不是合法的 RE2 表达式：" + e.getMessage());
        }
    }

    private static void rejectReservedName(String name) {
        if (McpRouteRule.DEFAULT_ROUTE_NAME.equalsIgnoreCase(name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_NAME_RESERVED", "default 为系统保留路由名称。");
        }
    }

    private static void rejectConflicts(McpRouteRule candidate, List<McpRouteRule> existing) {
        List<McpRouteRule> conflicts = McpRouteRules.conflicting(candidate, existing);
        if (!conflicts.isEmpty()) {
            String names = conflicts.stream().map(McpRouteRule::name).collect(java.util.stream.Collectors.joining("、"));
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_MATCH_CONFLICT",
                    "与已启用路由的匹配条件冲突：" + names + "。请调整 Path/Host/方法/Header 条件。");
        }
    }

    private static void rejectDefault(McpRouteRule rule) {
        if (rule.isDefault()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_DEFAULT_IMMUTABLE", "默认路由为系统兜底：不可修改、不可禁用、不可删除。");
        }
    }

    private static McpRouteRule copyWithStatus(McpRouteRule rule, String status) {
        return new McpRouteRule(rule.id(), rule.tenantId(), rule.mcpServiceId(), rule.name(), rule.description(),
                rule.priority(), rule.pathMode(), rule.pathValue(), rule.hostMode(), rule.hostValue(), rule.methods(),
                rule.headerConditions(), status, rule.version(), rule.createdBy(), rule.createdAt(), rule.updatedAt());
    }

    private McpRouteRule find(UUID tenantId, UUID ruleId) {
        return routeRepository.findByIdAndTenantId(ruleId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "路由不存在。"));
    }

    private void requireService(UUID tenantId, UUID mcpServiceId) {
        serviceRepository.findByIdAndTenantId(mcpServiceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
    }
}
