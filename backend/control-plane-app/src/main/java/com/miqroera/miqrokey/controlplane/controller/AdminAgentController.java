package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AgentView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminAgentService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Managed smart agents (P3.1, api-contract §5.13): CRUD plus per-agent usage
 * aggregated over the bound credential. SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
public class AdminAgentController {

    private final AdminAgentService agentService;
    private final UserContext userContext;

    public AdminAgentController(AdminAgentService agentService, UserContext userContext) {
        this.agentService = agentService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AgentView> list() {
        return agentService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{agentId}")
    public AgentView get(@PathVariable UUID agentId) {
        return agentService.get(userContext.getUser().tenantId(), agentId);
    }

    @PostMapping
    public AgentView create(@Valid @RequestBody CreateRequest body) {
        var user = userContext.getUser();
        return agentService.create(user.tenantId(), user.id(), body.name().trim(), body.description(),
                body.credentialId());
    }

    @PostMapping("/{agentId}/disable")
    public AgentView disable(@PathVariable UUID agentId) {
        return agentService.disable(userContext.getUser().tenantId(), agentId);
    }

    /** Per-agent usage over the bound credential; from/to optional ISO-8601. */
    @GetMapping("/{agentId}/usage")
    public UsageSummary usage(@PathVariable UUID agentId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromIso = from != null && !from.isBlank() ? Instant.parse(from) : null;
        Instant toIso = to != null && !to.isBlank() ? Instant.parse(to) : null;
        return agentService.usage(userContext.getUser().tenantId(), agentId, fromIso, toIso);
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotNull UUID credentialId) {
    }
}
