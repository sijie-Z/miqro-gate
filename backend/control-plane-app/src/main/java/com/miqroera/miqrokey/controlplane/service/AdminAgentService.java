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
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * observability aggregates by that credential for the per-agent view. Every
 * mutation records an audit event (AGENT_CREATE / AGENT_ENABLE / AGENT_UPDATE /
 * AGENT_DISABLE / AGENT_DELETE).
 */
@Service
public class AdminAgentService {

    private final AgentRepository agentRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final ProviderProductRepository productRepository;
    private final AdminUsageStatsService usageStatsService;
    private final AuditService auditService;

    public AdminAgentService(AgentRepository agentRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamSubscriptionRepository subscriptionRepository, ProviderProductRepository productRepository,
            AdminUsageStatsService usageStatsService, AuditService auditService) {
        this.agentRepository = agentRepository;
        this.credentialRepository = credentialRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.usageStatsService = usageStatsService;
        this.auditService = auditService;
    }

    public List<AgentView> list(UUID tenantId) {
        return agentRepository.findAllByTenantId(tenantId).stream().map(a -> toView(tenantId, a)).toList();
    }

    public AgentView get(UUID tenantId, UUID agentId) {
        return toView(tenantId, find(tenantId, agentId));
    }

    @Transactional
    public AgentView create(UUID tenantId, UUID adminId, String name, String description, UUID credentialId,
            String requestId) {
        // Row-lock the credential (#714): binding must serialize with rotate/disable,
        // which take the same lock before checking for an ACTIVE agent — otherwise a
        // concurrent disable could land between the ACTIVE check and the insert and
        // leave an agent bound to a credential that is no longer routable.
        UpstreamCredential credential = credentialRepository.findByIdForUpdate(credentialId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_NOT_FOUND", "凭证不存在。"));
        if (!credential.tenantId().equals(tenantId) || credential.status() != CredentialStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_NOT_FOUND", "凭证不存在或未启用。");
        }
        if (agentRepository.existsByCredentialId(tenantId, credentialId)) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_CREDENTIAL_TAKEN",
                    "该凭证已被其他 Agent 绑定（一个凭证只支持一个 Agent，保证按 Agent 用量可区分）。");
        }
        Agent agent = new Agent(UUID.randomUUID(), tenantId, name.trim(), description, credentialId, "ACTIVE", 0,
                adminId, Instant.now(), Instant.now());
        try {
            agentRepository.insert(agent);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_NAME_TAKEN", "Agent 名称已存在。");
        }
        auditService.record(tenantId, adminId, "AGENT_CREATE", "AGENT", agent.id(),
                AuditSummaries.summary("name", AuditSummaries.sanitize(agent.name())), requestId);
        return toView(tenantId, agent);
    }

    @Transactional
    public AgentView disable(UUID tenantId, UUID adminId, UUID agentId, String requestId) {
        Agent agent = find(tenantId, agentId);
        if ("DISABLED".equals(agent.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_ALREADY_DISABLED", "Agent 已禁用。");
        }
        AgentView view = toView(tenantId, agentRepository.updateStatus(tenantId, agentId, "DISABLED", agent.version()));
        auditService.record(tenantId, adminId, "AGENT_DISABLE", "AGENT", agentId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(agent.name())), requestId);
        return view;
    }

    /**
     * Re-enable a disabled agent (ADR-0025 §2-Q2). This is deliberately **not** the
     * mirror of {@link #disable}: while the agent was disabled the credential could
     * have been disabled too (rotating it is harmless — the agent binds the
     * credential row, not the secret). Coming back ACTIVE against a non-ACTIVE
     * credential would point the agent at an unroutable egress, so refuse and let
     * the operator fix the credential first.
     */
    @Transactional
    public AgentView enable(UUID tenantId, UUID adminId, UUID agentId, String requestId) {
        Agent agent = find(tenantId, agentId);
        if ("ACTIVE".equals(agent.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_ALREADY_ENABLED", "Agent 已启用。");
        }
        UpstreamCredential credential = credentialRepository.findById(agent.upstreamCredentialId()).orElse(null);
        if (credential == null || credential.status() != CredentialStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_NOT_ACTIVE", "该 Agent 绑定的凭证当前未启用，无法启用；请先启用凭证。");
        }
        AgentView view = toView(tenantId, agentRepository.updateStatus(tenantId, agentId, "ACTIVE", agent.version()));
        auditService.record(tenantId, adminId, "AGENT_ENABLE", "AGENT", agentId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(agent.name())), requestId);
        return view;
    }

    /** Rename / edit the description of an agent (ADR-0025 §2-Q3). */
    @Transactional
    public AgentView update(UUID tenantId, UUID adminId, UUID agentId, String name, String description,
            long expectedVersion, String requestId) {
        Agent agent = find(tenantId, agentId);
        Agent updated;
        try {
            updated = agentRepository.update(tenantId, agentId, name.trim(), description, expectedVersion);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_NAME_TAKEN", "Agent 名称已存在。");
        } catch (OptimisticLockingFailureException e) {
            // Lost the optimistic lock: the row changed since the form was rendered.
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "该 Agent 已被其他操作修改，请刷新后重试。");
        }
        // The audit carries before → after: a rename is exactly the case where a bare
        // resource id would leave the trail unreadable (ADR-0025 §2-Q3).
        auditService.record(
                tenantId, adminId, "AGENT_UPDATE", "AGENT", agentId, AuditSummaries.summary("from",
                        AuditSummaries.sanitize(agent.name()), "to", AuditSummaries.sanitize(updated.name())),
                requestId);
        return toView(tenantId, updated);
    }

    /**
     * Hard delete (ADR-0025 §3-D). Nothing references {@code agents} and usage is
     * attributed by credential, so removal frees both the name and the
     * one-agent-per-credential slot without touching history. The row is gone
     * afterwards, so the audit detail must carry the name snapshot — a bare id
     * would be un-resolvable.
     */
    @Transactional
    public void delete(UUID tenantId, UUID adminId, UUID agentId, String requestId) {
        Agent agent = find(tenantId, agentId);
        if (!agentRepository.delete(tenantId, agentId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在。");
        }
        auditService.record(tenantId, adminId, "AGENT_DELETE", "AGENT", agentId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(agent.name())), requestId);
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
                product != null ? product.displayName() : null, agent.status(), agent.version(), agent.createdAt());
    }
}
