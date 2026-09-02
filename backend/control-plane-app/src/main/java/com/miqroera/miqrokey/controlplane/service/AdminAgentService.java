package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AgentView;
import com.miqroera.miqrokey.domain.model.Agent;
import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.AgentRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Managed smart agents (P3.1, {@code agents} V17) modeled after the Alibaba AI
 * Gateway agent topology: an agent's egress is bound to one ACTIVE upstream
 * credential (the provider product follows from the credential), and usage
 * observability aggregates by that credential for the per-agent view.
 */
@Service
public class AdminAgentService {

    private final AgentRepository agentRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final ProviderProductRepository productRepository;
    private final AdminUsageStatsService usageStatsService;

    public AdminAgentService(AgentRepository agentRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamSubscriptionRepository subscriptionRepository, ProviderProductRepository productRepository,
            AdminUsageStatsService usageStatsService) {
        this.agentRepository = agentRepository;
        this.credentialRepository = credentialRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.usageStatsService = usageStatsService;
    }

    public List<AgentView> list(UUID tenantId) {
        return agentRepository.findAllByTenantId(tenantId).stream().map(a -> toView(tenantId, a)).toList();
    }

    public AgentView get(UUID tenantId, UUID agentId) {
        return toView(tenantId, find(tenantId, agentId));
    }

    @Transactional
    public AgentView create(UUID tenantId, UUID adminId, String name, String description, UUID credentialId) {
        UpstreamCredential credential = credentialRepository.findById(credentialId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_NOT_FOUND", "凭证不存在。"));
        if (!credential.tenantId().equals(tenantId) || credential.status() != CredentialStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_NOT_FOUND", "凭证不存在或未启用。");
        }
        if (agentRepository.existsByCredentialId(tenantId, credentialId)) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_CREDENTIAL_TAKEN",
                    "该凭证已被其他 Agent 绑定（一个凭证只支持一个 Agent，保证按 Agent 用量可区分）。");
        }
        Agent agent = new Agent(UUID.randomUUID(), tenantId, name, description, credentialId, "ACTIVE", 0, adminId,
                Instant.now(), Instant.now());
        try {
            agentRepository.insert(agent);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_NAME_TAKEN", "Agent 名称已存在。");
        }
        return toView(tenantId, agent);
    }

    @Transactional
    public AgentView disable(UUID tenantId, UUID agentId) {
        Agent agent = find(tenantId, agentId);
        if ("DISABLED".equals(agent.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_ALREADY_DISABLED", "Agent 已禁用。");
        }
        return toView(tenantId, agentRepository.updateStatus(tenantId, agentId, "DISABLED", agent.version()));
    }

    /** Per-agent usage: aggregation over the bound credential. */
    public UsageSummary usage(UUID tenantId, UUID agentId, Instant from, Instant to) {
        Agent agent = find(tenantId, agentId);
        return usageStatsService.summary(tenantId, "project", from, to, null, null, null, agent.upstreamCredentialId(),
                null, null, null);
    }

    private Agent find(UUID tenantId, UUID agentId) {
        return agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在。"));
    }

    private AgentView toView(UUID tenantId, Agent agent) {
        UpstreamCredential credential = credentialRepository.findById(agent.upstreamCredentialId()).orElse(null);
        UpstreamSubscription subscription = credential != null
                ? subscriptionRepository.findById(credential.subscriptionId()).orElse(null)
                : null;
        ProviderProduct product = subscription != null
                ? productRepository.findById(subscription.providerProductId()).orElse(null)
                : null;
        return new AgentView(agent.id(), agent.name(), agent.description(), agent.upstreamCredentialId(),
                credential != null ? credential.credentialName() : null, product != null ? product.id() : null,
                product != null ? product.displayName() : null, agent.status(), agent.createdAt());
    }
}
