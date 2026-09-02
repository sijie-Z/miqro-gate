package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.controlplane.dto.UpsertQuotaRuleRequest;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminQuotaRuleService;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Usage quota plans (api-contract §5.19): alerting-only quota governance per
 * the platform-middleware roadmap — plans plus live watermarks, never blocking.
 * SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/quota-rules")
public class AdminQuotaRuleController {

    private final AdminQuotaRuleService quotaRuleService;
    private final UserContext userContext;

    public AdminQuotaRuleController(AdminQuotaRuleService quotaRuleService, UserContext userContext) {
        this.quotaRuleService = quotaRuleService;
        this.userContext = userContext;
    }

    /** All quota rules with live watermarks for their current windows. */
    @GetMapping
    public List<QuotaRuleView> list() {
        return quotaRuleService.list(user().tenantId());
    }

    /** Inserts or updates the plan keyed on (scope, metric, period). */
    @PutMapping
    public QuotaRuleView put(@Valid @RequestBody UpsertQuotaRuleRequest body, HttpServletRequest httpReq) {
        return quotaRuleService.put(user().tenantId(), user().id(), body, requestId(httpReq));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest httpReq) {
        quotaRuleService.delete(user().tenantId(), user().id(), id, requestId(httpReq));
    }

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
