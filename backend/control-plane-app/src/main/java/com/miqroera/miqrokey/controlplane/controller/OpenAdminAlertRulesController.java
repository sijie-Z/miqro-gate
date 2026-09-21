package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService;
import com.miqroera.miqrokey.controlplane.service.AlertRuleService.AlertRule;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * {@code /api/v1/admin-api/**}. Mutations are audited (#324): machine calls
 * attribute the issuing admin plus a {@code via} key marker, sessions the
 * acting admin directly.
 */
@RestController
@RequestMapping("/api/v1/admin-api/alert-rules")
public class OpenAdminAlertRulesController {

    private final AlertRuleService ruleService;
    private final UserContext userContext;

    public OpenAdminAlertRulesController(AlertRuleService ruleService, UserContext userContext) {
        this.ruleService = ruleService;
        this.userContext = userContext;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRule create(HttpServletRequest request, @Valid @RequestBody OpenAdminAlertRuleCreateRequest body) {
        return ruleService.create(tenantId(request), body.name(), body.type(), body.threshold(),
                body.dedupeMinutes() != null ? body.dedupeMinutes() : 60, body.webhookEndpointId(), body.scopeJson(),
                auditContext(request));
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
    public AlertRule update(HttpServletRequest request, @PathVariable UUID ruleId,
            @Valid @RequestBody OpenAdminAlertRuleUpdateRequest body) {
        return ruleService.update(tenantId(request), ruleId, body.name(), body.threshold(), body.dedupeMinutes(),
                body.enabled(), body.webhookEndpointId(), body.scopeJson(), auditContext(request));
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable UUID ruleId) {
        ruleService.delete(tenantId(request), ruleId, auditContext(request));
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }

    /**
     * Machine key -> issuing admin + via marker; SYSTEM_ADMIN session -> the user.
     */
    private AuditContext auditContext(HttpServletRequest request) {
        UUID issuer = (UUID) request.getAttribute(AdminApiKeyAuthFilter.ISSUER_ATTR);
        if (issuer != null) {
            return AuditContext.machine(issuer, (String) request.getAttribute(AdminApiKeyAuthFilter.NAME_ATTR),
                    requestId(request));
        }
        return AuditContext.human(userContext.getUser().id(), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    /**
     * Machine-key twin of {@code AdminAlertRuleController.AlertRuleCreateRequest}:
     * same constraints on both faces, so one payload cannot be answered two ways
     * depending on whether it arrived through the console or a script (#1073).
     * {@code threshold} deliberately has no lower bound — {@code <= 0} stays a
     * documented degenerate configuration.
     */
    public record OpenAdminAlertRuleCreateRequest(@NotBlank @Size(max = 200) String name, String type,
            @NotNull @Digits(integer = 6, fraction = 6) BigDecimal threshold, @Min(1) Integer dedupeMinutes,
            UUID webhookEndpointId, String scopeJson) {
    }

    /** PATCH is partial: only present values are constrained. */
    public record OpenAdminAlertRuleUpdateRequest(@Pattern(regexp = "\\s*\\S[\\s\\S]*") @Size(max = 200) String name,
            @Digits(integer = 6, fraction = 6) BigDecimal threshold, @Min(1) Integer dedupeMinutes, Boolean enabled,
            UUID webhookEndpointId, String scopeJson) {
    }
}
