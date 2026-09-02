package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.ApprovalProperties;
import com.miqroera.miqrokey.controlplane.dto.ModelApprovalView;
import com.miqroera.miqrokey.controlplane.dto.SubmitModelApprovalRequest;
import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.ModelApproval;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.ModelApprovalRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Model-approval workflow (原始设计文档 §8.2 / §5.6): a user asks for an additional
 * model on one of their virtual keys; a SYSTEM_ADMIN approves or rejects it in
 * the admin queue.
 *
 * <h2>Effect of an approval</h2> The gateway's model gate is
 * {@code key.models ∩ grant.models}, so an approval writes the model into both
 * {@code virtual_key_models} (the requesting key only) and, when it is not yet
 * there, {@code project_provider_grant_models} (the key's grant) — then
 * triggers an immediate route-snapshot refresh. Other keys sharing the grant
 * keep their own model snapshot and are unaffected.
 *
 * <h2>Security invariants</h2>
 * <ul>
 * <li>A user can only request models for their own key; anything else is a
 * generic 404 (no enumeration).</li>
 * <li>Only PENDING requests can be reviewed (409 ALREADY_REVIEWED); the
 * optimistic {@code version} column makes the transition race-safe.</li>
 * <li>Review summaries never contain key material.</li>
 * </ul>
 */
@Service
public class ModelApprovalService {

    private static final String AUTO_APPROVE_NOTE = "Auto-approved: model on the approval whitelist";

    private final ModelApprovalRepository approvalRepository;
    private final VirtualKeyRepository keyRepository;
    private final ProjectProviderGrantRepository grantRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalProperties approvalProperties;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;
    private final AlertEventDispatcher alertEventDispatcher;

    public ModelApprovalService(ModelApprovalRepository approvalRepository, VirtualKeyRepository keyRepository,
            ProjectProviderGrantRepository grantRepository, ProjectRepository projectRepository,
            UserRepository userRepository, NamedParameterJdbcTemplate jdbc, ApprovalProperties approvalProperties,
            AuditService auditService, RouteRefreshPublisher routeRefreshPublisher,
            AlertEventDispatcher alertEventDispatcher) {
        this.approvalRepository = approvalRepository;
        this.keyRepository = keyRepository;
        this.grantRepository = grantRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.approvalProperties = approvalProperties;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.alertEventDispatcher = alertEventDispatcher;
    }

    /** Submits a request for one additional model on the caller's key. */
    @Transactional
    public ModelApprovalView submit(User user, SubmitModelApprovalRequest request, String requestId) {
        UUID tenantId = user.tenantId();
        VirtualKey key = ownedKey(user, request.virtualKeyId());
        if (key.status() != VirtualKeyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE", "Only an active key can receive models");
        }
        String modelId = validatedModel(request.modelId());
        Set<String> keyModels = keyRepository.findModelIds(key.id());
        if (keyModels.contains(modelId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ALREADY_AVAILABLE",
                    "The model is already available on this key");
        }
        boolean pendingDuplicate = approvalRepository.findAllByVirtualKeyId(key.id()).stream()
                .anyMatch(a -> a.status() == ModelApprovalStatus.PENDING && a.modelId().equals(modelId));
        if (pendingDuplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PENDING",
                    "A pending request for this model on this key already exists");
        }

        Instant now = Instant.now();
        ModelApproval approval = new ModelApproval(UUID.randomUUID(), tenantId, key.id(), modelId, user.id(),
                ModelApprovalStatus.PENDING, null, trimmed(request.reason()), null, 0L, now, now);
        approvalRepository.insert(approval);
        auditService.record(tenantId, user.id(), "MODEL_APPROVAL_SUBMITTED", "MODEL_APPROVAL", approval.id(),
                auditSummary("virtualKeyId", key.id(), "modelId", modelId, "autoApproved",
                        approvalProperties.getWhitelistModels().contains(modelId)),
                requestId);
        if (approvalProperties.getWhitelistModels().contains(modelId)) {
            // Whitelisted models skip the review queue but still record the grant
            // (actor = requester, reviewer column stays null).
            ModelApproval autoApproved = review(tenantId, null, approval, AUTO_APPROVE_NOTE, now);
            auditService.record(tenantId, user.id(), "MODEL_APPROVAL_APPROVED", "MODEL_APPROVAL", approval.id(),
                    auditSummary("virtualKeyId", key.id(), "modelId", modelId, "autoApproved", true), requestId);
            notifyApproval(tenantId, "MODEL_APPROVAL_SUBMITTED", approval, user.id(), key.id(),
                    Map.of("reason", approval.reason() == null ? "" : approval.reason()));
            notifyApproval(tenantId, "MODEL_APPROVAL_APPROVED", autoApproved, user.id(), key.id(),
                    Map.of("autoApproved", true, "reviewNote",
                            autoApproved.reviewNote() == null ? "" : autoApproved.reviewNote()));
            return view(autoApproved, tenantId);
        }
        notifyApproval(tenantId, "MODEL_APPROVAL_SUBMITTED", approval, user.id(), key.id(),
                Map.of("reason", approval.reason() == null ? "" : approval.reason()));
        return view(approval, tenantId);
    }

    /** The caller's own requests, newest first. */
    public List<ModelApprovalView> listMine(User user) {
        List<ModelApproval> approvals = approvalRepository.findAllByRequestedBy(user.id());
        List<ModelApprovalView> views = new ArrayList<>(approvals.size());
        for (ModelApproval approval : approvals) {
            views.add(view(approval, user.tenantId()));
        }
        return views;
    }

    /**
     * Admin queue page. {@code status} null returns every status; ordering is
     * newest-first with a keyset cursor handled by the controller.
     */
    public List<ModelApprovalView> listQueue(User admin, ModelApprovalStatus status, int limit, Instant beforeCreatedAt,
            UUID beforeId) {
        List<ModelApproval> approvals = approvalRepository.findPage(status, limit, beforeCreatedAt, beforeId);
        List<ModelApprovalView> views = new ArrayList<>(approvals.size());
        for (ModelApproval approval : approvals) {
            views.add(view(approval, admin.tenantId()));
        }
        return views;
    }

    /** Approves a PENDING request: grants the model and refreshes the snapshot. */
    @Transactional
    public ModelApprovalView approve(User admin, UUID approvalId, String reviewNote, String requestId) {
        ModelApproval approval = reviewable(admin.tenantId(), approvalId);
        Instant now = Instant.now();
        ModelApproval approved = review(admin.tenantId(), admin.id(), approval, trimmed(reviewNote), now);
        auditService.record(admin.tenantId(), admin.id(), "MODEL_APPROVAL_APPROVED", "MODEL_APPROVAL", approval.id(),
                auditSummary("virtualKeyId", approval.virtualKeyId(), "modelId", approval.modelId()), requestId);
        notifyApproval(admin.tenantId(), "MODEL_APPROVAL_APPROVED", approved, approval.requestedBy(),
                approval.virtualKeyId(),
                Map.of("reviewNote", approved.reviewNote() == null ? "" : approved.reviewNote()));
        return view(approved, admin.tenantId());
    }

    /** Rejects a PENDING request (no route impact). */
    @Transactional
    public ModelApprovalView reject(User admin, UUID approvalId, String reviewNote, String requestId) {
        UUID tenantId = admin.tenantId();
        ModelApproval approval = reviewable(tenantId, approvalId);
        Instant now = Instant.now();
        ModelApproval rejected = optimisticUpdate(new ModelApproval(approval.id(), tenantId, approval.virtualKeyId(),
                approval.modelId(), approval.requestedBy(), ModelApprovalStatus.REJECTED, admin.id(), approval.reason(),
                trimmed(reviewNote), approval.version() + 1, approval.createdAt(), now));
        auditService.record(tenantId, admin.id(), "MODEL_APPROVAL_REJECTED", "MODEL_APPROVAL", approval.id(),
                auditSummary("virtualKeyId", approval.virtualKeyId(), "modelId", approval.modelId()), requestId);
        notifyApproval(tenantId, "MODEL_APPROVAL_REJECTED", rejected, approval.requestedBy(), approval.virtualKeyId(),
                Map.of("reviewNote", rejected.reviewNote() == null ? "" : rejected.reviewNote()));
        return view(rejected, tenantId);
    }

    // -------------------------------------------------------------------
    // approval side effects
    // -------------------------------------------------------------------

    /**
     * Fires an event-driven webhook notification (F03) for an approval transition.
     * Details are pure metadata — approval id, model, requester, key display and
     * transition specifics — never key material or request bodies. No-op unless the
     * tenant configured an enabled rule of the type.
     */
    private void notifyApproval(UUID tenantId, String type, ModelApproval approval, UUID requesterId, UUID keyId,
            Map<String, Object> transition) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvalId", approval.id().toString());
        details.put("modelId", approval.modelId());
        details.put("status", approval.status().name());
        VirtualKey key = keyId == null
                ? null
                : keyRepository.findById(keyId).filter(k -> k.tenantId().equals(tenantId)).orElse(null);
        if (key != null) {
            details.put("keyName", key.name());
            details.put("keyDisplay", key.displayPrefix() + "…" + key.lastFour());
        }
        User requester = userRepository.findById(requesterId).filter(u -> u.tenantId().equals(tenantId)).orElse(null);
        if (requester != null) {
            details.put("username", requester.username());
            details.put("requesterName", requester.displayName());
        }
        details.putAll(transition);
        alertEventDispatcher.notifyForType(tenantId, type, details);
    }

    /**
     * Transitions a PENDING request to APPROVED and applies its effect: the model
     * joins the key's own model set and, when missing from the key's grant, the
     * grant's model set first (the gateway gates on
     * {@code key.models ∩ grant.models}). A refresh is published so the change
     * applies immediately rather than at the next 30s snapshot.
     */
    private ModelApproval review(UUID tenantId, UUID reviewerId, ModelApproval approval, String reviewNote,
            Instant now) {
        VirtualKey key = keyRepository.findById(approval.virtualKeyId()).filter(k -> k.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "Virtual key not found"));
        if (key.status() != VirtualKeyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE",
                    "The key is no longer active; the request cannot take effect");
        }
        ProjectProviderGrant grant = grantRepository.findById(key.grantId()).filter(g -> g.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "Grant not found"));
        if (grant.status() != GrantStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_INACTIVE",
                    "The key's grant is disabled; re-enable it before approving model requests");
        }
        jdbc.update("""
                INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                SELECT tenant_id, :grantId, :modelId FROM project_provider_grants WHERE id = :grantId
                ON CONFLICT (grant_id, model_id) DO NOTHING
                """, new MapSqlParameterSource("grantId", grant.id()).addValue("modelId", approval.modelId()));
        Set<String> keyModels = new LinkedHashSet<>(keyRepository.findModelIds(key.id()));
        if (keyModels.add(approval.modelId())) {
            keyRepository.replaceKeyModels(tenantId, key.id(), keyModels);
        }

        ModelApproval approved = optimisticUpdate(new ModelApproval(approval.id(), tenantId, key.id(),
                approval.modelId(), approval.requestedBy(), ModelApprovalStatus.APPROVED, reviewerId, approval.reason(),
                reviewNote, approval.version() + 1, approval.createdAt(), now));
        routeRefreshPublisher.publishChanged();
        return approved;
    }

    private ModelApproval optimisticUpdate(ModelApproval next) {
        try {
            return approvalRepository.update(next);
        } catch (IllegalStateException e) {
            // Concurrent review lost the optimistic lock — the request was already
            // decided by someone else.
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "This request was already reviewed");
        }
    }

    // -------------------------------------------------------------------
    // validation & views
    // -------------------------------------------------------------------

    private VirtualKey ownedKey(User user, UUID keyId) {
        VirtualKey key = keyRepository.findById(keyId).filter(k -> k.tenantId().equals(user.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "Virtual key not found"));
        if (user.role() != UserRole.SYSTEM_ADMIN && !key.userId().equals(user.id())) {
            // IDOR guard: another user's key is indistinguishable from a missing one.
            throw new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "Virtual key not found");
        }
        return key;
    }

    private ModelApproval reviewable(UUID tenantId, UUID approvalId) {
        ModelApproval approval = approvalRepository.findById(approvalId).filter(a -> a.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND",
                        "Approval request not found"));
        if (approval.status() != ModelApprovalStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "This request was already reviewed");
        }
        return approval;
    }

    /**
     * Model IDs are exact, case-sensitive; reject blanks, control chars and
     * overlength.
     */
    private String validatedModel(String modelId) {
        String model = trimmed(modelId);
        if (model == null || model.isBlank() || model.length() > 128
                || model.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_INVALID", "Invalid model id");
        }
        return model;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private ModelApprovalView view(ModelApproval approval, UUID tenantId) {
        VirtualKey key = keyRepository.findById(approval.virtualKeyId()).orElse(null);
        String keyName = key == null ? null : key.name();
        String keyDisplay = key == null ? null : key.displayPrefix() + "…" + key.lastFour();
        String projectTag = null;
        if (key != null) {
            projectTag = projectRepository.findById(key.projectId()).map(Project::projectTag).orElse(null);
        }
        User requester = userRepository.findById(approval.requestedBy()).filter(u -> u.tenantId().equals(tenantId))
                .orElse(null);
        User reviewer = approval.reviewedBy() == null
                ? null
                : userRepository.findById(approval.reviewedBy()).filter(u -> u.tenantId().equals(tenantId))
                        .orElse(null);
        return new ModelApprovalView(approval.id(), approval.virtualKeyId(), keyName, keyDisplay, projectTag,
                approval.modelId(), approval.reason(), approval.status(), approval.requestedBy(),
                requester == null ? "deleted user" : requester.displayName(), approval.reviewNote(),
                reviewer == null ? null : reviewer.displayName(), approval.createdAt(), approval.updatedAt());
    }

    private static String auditSummary(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
                "\\t");
    }
}
