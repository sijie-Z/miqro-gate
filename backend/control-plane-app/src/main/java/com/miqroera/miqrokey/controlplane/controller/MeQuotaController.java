package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminQuotaRuleService;
import com.miqroera.miqrokey.domain.model.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Self-service quota visibility (F04): the caller's own USER-scope quota rules
 * with their live watermarks — read-only, never another scope's rules. The
 * endpoint mirrors the admin quota-rules list for the single user slice.
 */
@RestController
@RequestMapping("/api/v1/me/quota-rules")
public class MeQuotaController {

    private final AdminQuotaRuleService quotaRuleService;
    private final UserContext userContext;

    public MeQuotaController(AdminQuotaRuleService quotaRuleService, UserContext userContext) {
        this.quotaRuleService = quotaRuleService;
        this.userContext = userContext;
    }

    /**
     * Quota plans set on the caller's own account, with current-window watermarks.
     */
    @GetMapping
    public List<QuotaRuleView> listMine() {
        User user = userContext.getUser();
        return quotaRuleService.listForUser(user.tenantId(), user.id());
    }
}
