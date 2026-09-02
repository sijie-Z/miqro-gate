package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ConfigureQuotaDefaultTemplateRequest;
import com.miqroera.miqrokey.controlplane.dto.QuotaDefaultTemplateView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminQuotaDefaultTemplateService;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Global default quota strategy (api-contract §5.22, Tencent doc 135489):
 * configure the snapshot source, then enable so newly created users receive an
 * automatic quota-rule copy. SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/quota-default-template")
public class AdminQuotaDefaultTemplateController {

    private final AdminQuotaDefaultTemplateService templateService;
    private final UserContext userContext;

    public AdminQuotaDefaultTemplateController(AdminQuotaDefaultTemplateService templateService,
            UserContext userContext) {
        this.templateService = templateService;
        this.userContext = userContext;
    }

    /** Current strategy state (empty view before the first configuration). */
    @GetMapping
    public QuotaDefaultTemplateView get() {
        return templateService.get(user().tenantId());
    }

    /** Stores the template definition (enabled state is preserved). */
    @PutMapping
    public QuotaDefaultTemplateView configure(@Valid @RequestBody ConfigureQuotaDefaultTemplateRequest body,
            HttpServletRequest httpReq) {
        return templateService.configure(user().tenantId(), user().id(), body, requestId(httpReq));
    }

    /** Starts auto-assigning quota-rule copies to newly created users. */
    @PostMapping("/enable")
    public QuotaDefaultTemplateView enable(HttpServletRequest httpReq) {
        return templateService.setEnabled(user().tenantId(), user().id(), true, requestId(httpReq));
    }

    /** Stops auto-assignment; already-assigned rules are kept untouched. */
    @PostMapping("/disable")
    public QuotaDefaultTemplateView disable(HttpServletRequest httpReq) {
        return templateService.setEnabled(user().tenantId(), user().id(), false, requestId(httpReq));
    }

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
