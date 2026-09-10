package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService.AlertRule;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin alert rules (G4.5, api-contract §5): metric thresholds evaluated on a
 * schedule with per-rule dedupe windows and optional webhook delivery. Access
 * is SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor. Mutations are audited (ALERT_RULE_CREATE/UPDATE/DELETE, #324).
 */
@RestController
@RequestMapping("/api/v1/admin/alert-rules")
public class AdminAlertRuleController {

    private final AlertRuleService ruleService;
    private final UserContext userContext;

    public AdminAlertRuleController(AlertRuleService ruleService, UserContext userContext) {
        this.ruleService = ruleService;
        this.userContext = userContext;
    }

    @PostMapping
    public AlertRule create(@RequestBody CreateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return ruleService.create(user.tenantId(), body.name(), body.type(), body.threshold(),
                body.dedupeMinutes() != null ? body.dedupeMinutes() : 60, body.webhookEndpointId(), body.scopeJson(),
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    @GetMapping
    public List<AlertRule> list() {
        return ruleService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{ruleId}")
    public AlertRule get(@PathVariable UUID ruleId) {
        return ruleService.get(userContext.getUser().tenantId(), ruleId);
    }

    @PatchMapping("/{ruleId}")
    public AlertRule update(@PathVariable UUID ruleId, @RequestBody UpdateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return ruleService.update(user.tenantId(), ruleId, body.name(), body.threshold(), body.dedupeMinutes(),
                body.enabled(), body.webhookEndpointId(), body.scopeJson(),
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    @DeleteMapping("/{ruleId}")
    public void delete(@PathVariable UUID ruleId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        ruleService.delete(user.tenantId(), ruleId, AuditContext.human(user.id(), requestId(httpReq)));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    public record CreateRequest(String name, String type, BigDecimal threshold, Integer dedupeMinutes,
            UUID webhookEndpointId, String scopeJson) {
    }

    public record UpdateRequest(String name, BigDecimal threshold, Integer dedupeMinutes, Boolean enabled,
            UUID webhookEndpointId, String scopeJson) {
    }
}
