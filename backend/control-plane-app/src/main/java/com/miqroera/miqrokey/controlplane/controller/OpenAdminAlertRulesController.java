package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService.AlertRule;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Open admin alert rules (ADR-0016 batch 2, option C): the session endpoint's
 * full rule lifecycle is pure tenant-scoped configuration without executor
 * columns, so it opens as-is to machine keys and SYSTEM_ADMIN sessions on
 * {@code /api/v1/admin-api/**}.
 */
@RestController
@RequestMapping("/api/v1/admin-api/alert-rules")
public class OpenAdminAlertRulesController {

    private final AlertRuleService ruleService;

    public OpenAdminAlertRulesController(AlertRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRule create(HttpServletRequest request, @RequestBody CreateRequest body) {
        return ruleService.create(tenantId(request), body.name(), body.type(), body.threshold(),
                body.dedupeMinutes() != null ? body.dedupeMinutes() : 60, body.webhookEndpointId(), body.scopeJson());
    }

    @GetMapping
    public List<AlertRule> list(HttpServletRequest request) {
        return ruleService.list(tenantId(request));
    }

    @GetMapping("/{ruleId}")
    public AlertRule get(HttpServletRequest request, @PathVariable UUID ruleId) {
        return ruleService.get(tenantId(request), ruleId);
    }

    @PatchMapping("/{ruleId}")
    public AlertRule update(HttpServletRequest request, @PathVariable UUID ruleId, @RequestBody UpdateRequest body) {
        return ruleService.update(tenantId(request), ruleId, body.name(), body.threshold(), body.dedupeMinutes(),
                body.enabled(), body.webhookEndpointId(), body.scopeJson());
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable UUID ruleId) {
        ruleService.delete(tenantId(request), ruleId);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }

    public record CreateRequest(String name, String type, BigDecimal threshold, Integer dedupeMinutes,
            UUID webhookEndpointId, String scopeJson) {
    }

    public record UpdateRequest(String name, BigDecimal threshold, Integer dedupeMinutes, Boolean enabled,
            UUID webhookEndpointId, String scopeJson) {
    }
}
