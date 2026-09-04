package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpRouteRuleService;
import com.miqroera.miqrokey.domain.model.McpHeaderCondition;
import com.miqroera.miqrokey.domain.model.McpRouteRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MCP route rules (F11, api-contract §5.19): configuration plane of the Tencent
 * doc 135482 routing feature — per-service priority rules whose
 * Path/Host/Method/Header conditions gate inbound requests (the upstream is
 * always the service itself). SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services/{serviceId}/route-rules")
public class AdminMcpRouteRuleController {

    private final AdminMcpRouteRuleService routeRules;
    private final UserContext userContext;

    public AdminMcpRouteRuleController(AdminMcpRouteRuleService routeRules, UserContext userContext) {
        this.routeRules = routeRules;
        this.userContext = userContext;
    }

    @GetMapping
    public List<McpRouteRule> list(@PathVariable UUID serviceId) {
        return routeRules.list(userContext.getUser().tenantId(), serviceId);
    }

    @PostMapping
    public McpRouteRule create(@PathVariable UUID serviceId, @Valid @RequestBody UpsertRequest body) {
        var user = userContext.getUser();
        return routeRules.create(user.tenantId(), user.id(), serviceId, body.name(), body.description(),
                body.priority(), body.pathMode(), body.pathValue(), body.hostMode(), body.hostValue(), body.methods(),
                body.conditions());
    }

    /** Full replace of the editable fields (see service javadoc for semantics). */
    @PatchMapping("/{ruleId}")
    public McpRouteRule update(@PathVariable UUID serviceId, @PathVariable UUID ruleId,
            @Valid @RequestBody UpsertRequest body) {
        var user = userContext.getUser();
        return routeRules.update(user.tenantId(), user.id(), serviceId, ruleId, body.name(), body.description(),
                body.priority(), body.pathMode(), body.pathValue(), body.hostMode(), body.hostValue(), body.methods(),
                body.conditions());
    }

    /** Enable/disable; idempotent per the upstream doc (same state is a no-op). */
    @PostMapping("/{ruleId}/status")
    public McpRouteRule setStatus(@PathVariable UUID serviceId, @PathVariable UUID ruleId,
            @RequestParam("status") String status) {
        return routeRules.setStatus(userContext.getUser().tenantId(), ruleId, status);
    }

    @DeleteMapping("/{ruleId}")
    public void delete(@PathVariable UUID serviceId, @PathVariable UUID ruleId) {
        routeRules.delete(userContext.getUser().tenantId(), ruleId);
    }

    public record HeaderConditionRequest(@NotBlank @Size(max = 64) String name, String mode,
            @Size(max = 256) String value) {
    }

    public record UpsertRequest(@NotBlank @Size(max = 64) String name, @Size(max = 200) String description,
            @Min(1) @Max(65535) Integer priority, String pathMode, @Size(max = 256) String pathValue, String hostMode,
            @Size(max = 256) String hostValue, List<String> methods, List<HeaderConditionRequest> headers) {

        /** Converts validated JSON entries into domain conditions (400 on bad rows). */
        public List<McpHeaderCondition> conditions() {
            if (headers == null) {
                return null;
            }
            try {
                return headers.stream().map(h -> new McpHeaderCondition(h.name(), h.mode(), h.value())).toList();
            } catch (IllegalArgumentException e) {
                throw new com.miqroera.miqrokey.controlplane.service.ApiException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "ROUTE_HEADER_INVALID",
                        "Header 条件不合法：" + e.getMessage());
            }
        }
    }
}
