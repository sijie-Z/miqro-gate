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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
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
import java.util.function.Function;

/**
 * Model-approval workflow (原始设计文档 §8.2 / §5.6): a user asks for an additional
 * model on one of their virtual keys; a SYSTEM_ADMIN approves or rejects it in
 * the admin queue.
 *
 * <h2>Effect of an approval</h2> The gateway's model gate is
 * {@code key.models ∩ grant.models ∩ catalog(ACTIVE)}, so an approval writes
 * the model into both {@code virtual_key_models} (the requesting key only) and,
 * when it is not yet there, {@code project_provider_grant_models} (the key's
 * grant) — then triggers an immediate route-snapshot refresh. Other keys
 * sharing the grant keep their own model snapshot and are unaffected.
 *
 * <h2>Catalog precondition (#506)</h2> The third gate needs an ACTIVE
 * {@code model_catalog} row for the key's product; without it an approval is
 * effective in the two tables yet invisible at {@code /v1/models}. Submission
 * and review therefore both require the row (fail fast with
 * {@code MODEL_NOT_IN_CATALOG} and an actionable message) instead of silently
 * granting a model the gateway will never serve.
 *
 * <h2>Security invariants</h2>
 * <ul>
 * <li>A user can only request models for their own key; anything else is a
 * generic 404 (no enumeration).</li>
 * <li>Only PENDING requests can be reviewed (409 ALREADY_REVIEWED); the
 * optimistic {@code version} column makes the transition race-safe.</li>
 * <li>One (key, model) pair carries at most one PENDING request — enforced by
 * the partial unique index {@code uq_model_approval_pending}, not by the
 * pre-insert SELECT alone, which cannot see concurrent writers (#1305).</li>
 * <li>Review summaries never contain key material.</li>
 * </ul>
 */
@Service
public class ModelApprovalService {

    private static final String AUTO_APPROVE_NOTE = "Auto-approved: model on the approval whitelist";

    /**
     * One (key, model) pair carries at most one request awaiting review — the
     * message is shared by the two paths that can observe the violation, so a retry
     * that overlaps another in-flight submit reads exactly like the sequential
     * retry (#1305).
     */
    private static final String DUPLICATE_PENDING_MESSAGE = "该模型在此密钥上已有待审批的申请，请等待管理员处理，无需重复提交";

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
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE",
                    "该虚拟密钥当前状态为「" + keyStatusLabel(key.status()) + "」，无法接收新模型；请在状态为「可用」的密钥上提交申请");
        }
        String modelId = validatedModel(request.modelId());
        Set<String> keyModels = keyRepository.findModelIds(key.id());
        if (keyModels.contains(modelId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ALREADY_AVAILABLE", "该模型已在此密钥的可用范围内，无需重复申请");
        }
        boolean pendingDuplicate = approvalRepository.findAllByVirtualKeyId(key.id()).stream()
                .anyMatch(a -> a.status() == ModelApprovalStatus.PENDING && a.modelId().equals(modelId));
        if (pendingDuplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PENDING", DUPLICATE_PENDING_MESSAGE);
        }
        // #506: the /v1/models gate requires the model to be ACTIVE in the
        // provider's model_catalog — without it an approval could never take
        // effect. Fail fast instead of silently granting a dead model.
        ProjectProviderGrant grant = grantRepository.findById(key.grantId()).filter(g -> g.tenantId().equals(tenantId))
                .orElseThrow(
                        () -> new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "该密钥所属授权不存在；请联系管理员检查授权配置"));
        if (!catalogModelActive(grant.providerProductId(), modelId)) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_NOT_IN_CATALOG",
                    "该模型当前不在供应商目录（model_catalog）中，暂时无法申请；" + "请联系管理员在「供应商 → 模型」中录入或探测该模型后再试");
        }

        Instant now = Instant.now();
        ModelApproval approval = new ModelApproval(UUID.randomUUID(), tenantId, key.id(), modelId, user.id(),
                ModelApprovalStatus.PENDING, null, trimmed(request.reason()), null, 0L, now, now);
        try {
            approvalRepository.insert(approval);
        } catch (DuplicateKeyException e) {
            // #1305: the check above reads a snapshot, and READ COMMITTED lets two
            // overlapping submits both pass it. uq_model_approval_pending (V73) is
            // the structural guard; the loser of that race lands here and gets the
            // same 409 as a sequential retry — instead of stacking a second pending
            // row, a second audit record and a second alert event.
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PENDING", DUPLICATE_PENDING_MESSAGE);
        }
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
        return views(approvalRepository.findAllByRequestedBy(user.id()), user.tenantId());
    }

    /**
     * Admin queue page: the tenant's own requests only. {@code status} null returns
     * every status; ordering is newest-first with a keyset cursor handled by the
     * controller.
     */
    public List<ModelApprovalView> listQueue(User admin, ModelApprovalStatus status, int limit, Instant beforeCreatedAt,
            UUID beforeId) {
        return views(approvalRepository.findPage(admin.tenantId(), status, limit, beforeCreatedAt, beforeId),
                admin.tenantId());
    }

    /**
     * Renders a list of approvals with a constant number of lookups: at most one
     * batch query per referenced entity type, independent of the row count. Per-row
     * lookups made {@code listMine} — the caller's entire request history, with no
     * page size — cost more as the account accumulated approvals.
     */
    private List<ModelApprovalView> views(List<ModelApproval> approvals, UUID tenantId) {
        if (approvals.isEmpty()) {
            return List.of();
        }
        Set<UUID> keyIds = new LinkedHashSet<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        for (ModelApproval approval : approvals) {
            keyIds.add(approval.virtualKeyId());
            userIds.add(approval.requestedBy());
            if (approval.reviewedBy() != null) {
                userIds.add(approval.reviewedBy());
            }
        }
        Map<UUID, VirtualKey> keys = index(keyRepository.findAllByIds(keyIds), VirtualKey::id);
        Set<UUID> projectIds = new LinkedHashSet<>();
        for (VirtualKey key : keys.values()) {
            projectIds.add(key.projectId());
        }
        Map<UUID, Project> projects = index(projectRepository.findAllByIds(projectIds), Project::id);
        Map<UUID, User> users = index(userRepository.findAllByIds(userIds), User::id);
        List<ModelApprovalView> views = new ArrayList<>(approvals.size());
        for (ModelApproval approval : approvals) {
            views.add(view(approval, tenantId, keys.get(approval.virtualKeyId()), projects, users));
        }
        return views;
    }

    private static <T> Map<UUID, T> index(List<T> rows, Function<T, UUID> id) {
        Map<UUID, T> indexed = new LinkedHashMap<>(rows.size());
        for (T row : rows) {
            indexed.put(id.apply(row), row);
        }
        return indexed;
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
                .orElseThrow(
                        () -> new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "该申请对应的虚拟密钥不存在，无法批准；请改为「驳回」本申请"));
        if (key.status() != VirtualKeyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE",
                    "该申请对应的虚拟密钥当前状态为「" + keyStatusLabel(key.status()) + "」，批准后无法生效；请改为「驳回」本申请，并请申请人在状态为「可用」的密钥上重新提交");
        }
        ProjectProviderGrant grant = grantRepository.findById(key.grantId()).filter(g -> g.tenantId().equals(tenantId))
                .orElseThrow(
                        () -> new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "该密钥所属授权不存在；请联系管理员检查授权配置"));
        if (grant.status() != GrantStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_INACTIVE",
                    "该密钥所属授权已停用或过期，批准后无法生效；请先在「授权」页启用该授权后再批准，或改为「驳回」本申请");
        }
        // #506: re-check the catalog at decision time — the model may have been
        // removed or disabled between submission and review, which would make
        // the approval silently ineffective at the gateway.
        if (!catalogModelActive(grant.providerProductId(), approval.modelId())) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_NOT_IN_CATALOG", "模型 '" + approval.modelId()
                    + "' 当前不在供应商目录（ACTIVE）中，批准后也无法在网关生效；" + "请先在「供应商 → 模型」中录入/启用该模型，再批准本申请");
        }
        jdbc.update("""
                INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                SELECT tenant_id, :grantId, :modelId FROM project_provider_grants WHERE id = :grantId
                ON CONFLICT (grant_id, model_id) DO NOTHING
                """, new MapSqlParameterSource("grantId", grant.id()).addValue("modelId", approval.modelId()));
        // #451: add-only semantics as ONE atomic statement. The previous
        // findModelIds -> replaceKeyModels read-modify-write let two concurrent
        // approvals for different models silently clobber each other
        // (replace-all wrote back a stale set).
        jdbc.update("""
                INSERT INTO virtual_key_models (tenant_id, virtual_key_id, model_id)
                SELECT tenant_id, :keyId, :modelId FROM virtual_keys
                WHERE id = :keyId AND tenant_id = :tenantId
                ON CONFLICT (virtual_key_id, model_id) DO NOTHING
                """, new MapSqlParameterSource("keyId", key.id()).addValue("tenantId", tenantId).addValue("modelId",
                approval.modelId()));

        ModelApproval approved = optimisticUpdate(new ModelApproval(approval.id(), tenantId, key.id(),
                approval.modelId(), approval.requestedBy(), ModelApprovalStatus.APPROVED, reviewerId, approval.reason(),
                reviewNote, approval.version() + 1, approval.createdAt(), now));
        routeRefreshPublisher.publishChanged();
        return approved;
    }

    private ModelApproval optimisticUpdate(ModelApproval next) {
        try {
            return approvalRepository.update(next);
        } catch (OptimisticLockingFailureException e) {
            // Concurrent review lost the optimistic lock — the request was already
            // decided by someone else.
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "该申请已被处理（可能在其他页面或会话中已通过或驳回），请刷新列表查看最新状态");
        }
    }

    // -------------------------------------------------------------------
    // validation & views
    // -------------------------------------------------------------------

    private VirtualKey ownedKey(User user, UUID keyId) {
        VirtualKey key = keyRepository.findById(keyId).filter(k -> k.tenantId().equals(user.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "虚拟密钥不存在"));
        if (user.role() != UserRole.SYSTEM_ADMIN && !key.userId().equals(user.id())) {
            // IDOR guard: another user's key is indistinguishable from a missing one.
            throw new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "虚拟密钥不存在");
        }
        return key;
    }

    private ModelApproval reviewable(UUID tenantId, UUID approvalId) {
        ModelApproval approval = approvalRepository.findById(approvalId).filter(a -> a.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "该申请不存在或已被删除，请刷新列表"));
        if (approval.status() != ModelApprovalStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "该申请已被处理（可能在其他页面或会话中已通过或驳回），请刷新列表查看最新状态");
        }
        return approval;
    }

    /**
     * True when {@code model_catalog} holds an ACTIVE row for the product — the
     * same third gate {@code /v1/models} applies. Rows are global (non-tenant), so
     * the product id is the whole scope.
     */
    private boolean catalogModelActive(UUID providerProductId, String modelId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM model_catalog
                WHERE provider_product_id = :productId AND model_id = :modelId AND status = 'ACTIVE'
                """, new MapSqlParameterSource("productId", providerProductId).addValue("modelId", modelId),
                Integer.class);
        return count != null && count > 0;
    }

    /**
     * Model IDs are exact, case-sensitive; reject blanks, control chars and
     * overlength.
     */
    private String validatedModel(String modelId) {
        String model = trimmed(modelId);
        if (model == null || model.isBlank() || model.length() > 128
                || model.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_INVALID", "模型 ID 无效：不能为空、超过 128 个字符或包含控制字符");
        }
        return model;
    }

    /**
     * Admin-facing Chinese label for a key status, matching the keys page badges.
     */
    private static String keyStatusLabel(VirtualKeyStatus status) {
        return switch (status) {
            case ACTIVE -> "可用";
            case ROTATING -> "轮换中";
            case REVOKED -> "已吊销";
            case DISABLED -> "已停用";
        };
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * Single-approval render (submit/review responses); list paths use
     * {@link #views}.
     */
    private ModelApprovalView view(ModelApproval approval, UUID tenantId) {
        VirtualKey key = keyRepository.findById(approval.virtualKeyId()).orElse(null);
        Map<UUID, Project> projects = new LinkedHashMap<>();
        if (key != null) {
            projectRepository.findById(key.projectId()).ifPresent(project -> projects.put(project.id(), project));
        }
        Map<UUID, User> users = new LinkedHashMap<>();
        userRepository.findById(approval.requestedBy()).ifPresent(user -> users.put(user.id(), user));
        if (approval.reviewedBy() != null) {
            userRepository.findById(approval.reviewedBy()).ifPresent(user -> users.put(user.id(), user));
        }
        return view(approval, tenantId, key, projects, users);
    }

    /**
     * Renders one approval from pre-resolved entities. A user of another tenant is
     * reported as "deleted user" exactly as a missing one, so a cross-tenant
     * display name can never leak through a shared key or grant.
     */
    private static ModelApprovalView view(ModelApproval approval, UUID tenantId, VirtualKey key,
            Map<UUID, Project> projects, Map<UUID, User> users) {
        Project project = key == null ? null : projects.get(key.projectId());
        User requester = visible(users.get(approval.requestedBy()), tenantId);
        User reviewer = approval.reviewedBy() == null ? null : visible(users.get(approval.reviewedBy()), tenantId);
        return new ModelApprovalView(approval.id(), approval.virtualKeyId(), key == null ? null : key.name(),
                key == null ? null : key.displayPrefix() + "…" + key.lastFour(),
                project == null ? null : project.projectTag(), approval.modelId(), approval.reason(), approval.status(),
                approval.requestedBy(), requester == null ? "deleted user" : requester.displayName(),
                approval.reviewNote(), reviewer == null ? null : reviewer.displayName(), approval.createdAt(),
                approval.updatedAt());
    }

    private static User visible(User user, UUID tenantId) {
        return user != null && user.tenantId().equals(tenantId) ? user : null;
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
