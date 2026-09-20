package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.BoundProjectView;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyRequest;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyResponse;
import com.miqroera.miqrokey.controlplane.dto.MeGrantsResponse;
import com.miqroera.miqrokey.controlplane.dto.VirtualKeyView;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.KeyProjectBinding;
import com.miqroera.miqrokey.domain.model.KeyProjectBindingStatus;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.KeyProjectBindingRepository;
import com.miqroera.miqrokey.domain.repository.ProjectMembershipRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Self-service Virtual Key lifecycle (api-contract §4): create, list, detail,
 * rotate, revoke, and the grant options for the creation form.
 *
 * <h2>Security invariants</h2>
 * <ul>
 * <li>Only the key's owner (or a SYSTEM_ADMIN) can read, rotate, or revoke a
 * key; anything else is a generic 404 (no enumeration).</li>
 * <li>The full secret string is returned exactly once and never persisted,
 * logged, or re-served; only the HMAC digest is stored.</li>
 * <li>Audit summaries never contain the secret.</li>
 * </ul>
 *
 * <h2>Rotation</h2> Rotation is atomic (single transaction): a replacement key
 * is created with the same bindings and models, and the old key enters
 * {@code ROTATING} with {@code revoked_at = now + grace}. The gateway snapshot
 * keeps routing the old key while {@code revoked_at} is in the future, so
 * clients can switch over before the grace window closes.
 */
@Service
public class VirtualKeyService {

    private static final String CACHE_POLICY_DISABLED = "DISABLED";
    private static final String CACHE_POLICY_ENABLED = "ENABLED";

    private final VirtualKeyRepository keyRepository;
    private final KeyProjectBindingRepository bindingRepository;
    private final ProjectRepository projectRepository;
    private final ProjectProviderGrantRepository grantRepository;
    private final ProviderProductRepository productRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final VirtualKeyCrypto keyCrypto;
    private final AuditService auditService;
    private final AuthProperties authProperties;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public VirtualKeyService(VirtualKeyRepository keyRepository, KeyProjectBindingRepository bindingRepository,
            ProjectRepository projectRepository, ProjectProviderGrantRepository grantRepository,
            ProviderProductRepository productRepository, ProjectMembershipRepository membershipRepository,
            UserRepository userRepository, VirtualKeyCrypto keyCrypto, AuditService auditService,
            AuthProperties authProperties, RouteRefreshPublisher routeRefreshPublisher) {
        this.keyRepository = keyRepository;
        this.bindingRepository = bindingRepository;
        this.projectRepository = projectRepository;
        this.grantRepository = grantRepository;
        this.productRepository = productRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.keyCrypto = keyCrypto;
        this.auditService = auditService;
        this.authProperties = authProperties;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    /**
     * Creates a key and returns the one-time full secret. The caller must show it
     * to the user immediately; the server keeps only the digest.
     */
    @Transactional
    public CreateVirtualKeyResponse create(User user, CreateVirtualKeyRequest request, String requestId) {
        return createKey(user.tenantId(), user, user.id(), request, requestId, user.id(), null);
    }

    /**
     * Open-admin delegation (ADR-0016 增补, 案 1): a SYSTEM_ADMIN operator issues a
     * key whose owner is {@code targetUserId}. All creation invariants hold — the
     * project must be active and tagged, the grant must match, the member check
     * runs against the <em>target</em> user (SYSTEM_ADMIN targets are exempt, same
     * as self-service), and the key is bound 1:1 to the target. The audit actor
     * stays the delegating operator and the summary carries {@code targetUserId},
     * so both halves of the chain remain attributable.
     */
    @Transactional
    public CreateVirtualKeyResponse createForUser(UUID tenantId, UUID operatorId, UUID targetUserId,
            CreateVirtualKeyRequest request, String requestId) {
        User operator = operatorId == null
                ? null
                : userRepository.findById(operatorId).filter(u -> u.tenantId().equals(tenantId)).orElse(null);
        if (operator == null || operator.role() != UserRole.SYSTEM_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELEGATION_FORBIDDEN",
                    "代指定用户创建 Virtual Key 仅限 SYSTEM_ADMIN 委托人。");
        }
        User target = userRepository.findById(targetUserId).filter(u -> u.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TARGET_USER_NOT_FOUND", "目标用户不存在。"));
        if (target.status() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "TARGET_USER_INACTIVE", "目标用户已停用，不能代其建钥。");
        }
        return createKey(tenantId, target, target.id(), request, requestId, operator.id(), target.id());
    }

    private CreateVirtualKeyResponse createKey(UUID tenantId, User memberSubject, UUID ownerUserId,
            CreateVirtualKeyRequest request, String requestId, UUID actorId, UUID delegatedTargetId) {
        // ADR-0018: one key may serve several projects. `projectIds` is the full
        // list (first element = primary, which keeps the explicit grant pick);
        // legacy `projectId` is treated as a single-element list.
        LinkedHashSet<UUID> requestedIds = new LinkedHashSet<>();
        if (request.projectIds() != null) {
            requestedIds.addAll(request.projectIds());
        }
        if (requestedIds.isEmpty() && request.projectId() != null) {
            requestedIds.add(request.projectId());
        }
        if (requestedIds.isEmpty()) {
            // Defense in depth: bean validation requires projectId, so this is
            // unreachable through the API — the invariant stays documented.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_REQUIRED", "请至少选择一个项目。");
        }
        List<UUID> projectIds = List.copyOf(requestedIds);

        Project project = requireBindableProject(tenantId, memberSubject, delegatedTargetId, projectIds.get(0));

        ProjectProviderGrant grant = grantRepository.findById(request.credentialGrantId())
                .filter(g -> g.tenantId().equals(tenantId)).filter(g -> g.projectId().equals(project.id()))
                .filter(g -> g.providerProductId().equals(request.providerProductId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GRANT_INVALID",
                        "The credential grant does not match the project and provider product"));
        if (grant.status() != GrantStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_INACTIVE", "The credential grant is not active");
        }

        // Additional projects bind through their own ACTIVE grant of the SAME
        // product (ADR-0018 MVP rule): the server picks the earliest one —
        // deterministic and audited; per-project credential choice is a later UX.
        List<BindingPlan> plannedBindings = new ArrayList<>();
        plannedBindings.add(new BindingPlan(project.id(), grant.id()));
        for (UUID extraId : projectIds.subList(1, projectIds.size())) {
            Project extra = requireBindableProject(tenantId, memberSubject, delegatedTargetId, extraId);
            ProjectProviderGrant extraGrant = grantRepository.findAllByProjectIdAndStatus(extra.id(), "ACTIVE").stream()
                    .filter(g -> g.providerProductId().equals(request.providerProductId()))
                    .min(Comparator.comparing(ProjectProviderGrant::createdAt).thenComparing(ProjectProviderGrant::id))
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROJECT_GRANT_MISSING",
                            "项目「" + extra.name() + "」下没有该供应商产品的可用授权，无法绑定；" + "请先在该项目创建授权，或取消勾选该项目。"));
            plannedBindings.add(new BindingPlan(extra.id(), extraGrant.id()));
        }

        Set<String> grantModels = grantRepository.findModelIds(grant.id());
        Set<String> requested = new LinkedHashSet<>(request.allowedModels() == null || request.allowedModels().isEmpty()
                ? grantModels
                : request.allowedModels());
        if (!grantModels.containsAll(requested)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_NOT_GRANTED",
                    "One or more requested models are not granted to this project");
        }
        String cachePolicy = request.cachePolicy() == null ? CACHE_POLICY_DISABLED : request.cachePolicy();

        VirtualKeyMaterial material = keyCrypto.generate(tenantId, project.projectTag());
        try {
            Instant now = Instant.now();
            UUID keyId = UUID.randomUUID();
            VirtualKey key = new VirtualKey(keyId, tenantId, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), ownerUserId, project.id(), grant.id(),
                    grant.upstreamCredentialId(), request.purpose(), request.name(), cachePolicy,
                    VirtualKeyStatus.ACTIVE, now, null, null, null, 0L);
            keyRepository.insert(key);
            for (BindingPlan planned : plannedBindings) {
                bindingRepository.insert(new KeyProjectBinding(UUID.randomUUID(), tenantId, keyId, planned.projectId(),
                        planned.grantId(), KeyProjectBindingStatus.ACTIVE, 0L, now, now));
            }
            keyRepository.replaceKeyModels(tenantId, keyId, requested);
            auditService.record(tenantId, actorId, "VIRTUAL_KEY_CREATE", "VIRTUAL_KEY", keyId,
                    delegatedTargetId != null
                            ? auditSummary("name", sanitize(request.name()), "purpose", request.purpose(), "models",
                                    requested.size(), "cachePolicy", cachePolicy, "projects", plannedBindings.size(),
                                    "targetUserId", delegatedTargetId)
                            : auditSummary("name", sanitize(request.name()), "purpose", request.purpose(), "models",
                                    requested.size(), "cachePolicy", cachePolicy, "projects", plannedBindings.size()),
                    requestId);
            routeRefreshPublisher.publishChanged();
            return response(keyId, material, now, boundProjects(plannedBindings));
        } finally {
            material.destroy();
        }
    }

    /**
     * Shared per-project checks for key creation (primary and additional projects
     * alike): exists in tenant, ACTIVE, has a routing tag, and the member (or the
     * delegation target) belongs to it.
     */
    private Project requireBindableProject(UUID tenantId, User memberSubject, UUID delegatedTargetId, UUID projectId) {
        Project project = projectRepository.findById(projectId).filter(p -> p.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (project.system()) {
            // #647: the UNATTRIBUTED bucket is an accounting sink, never a key binding.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_NOT_SELECTABLE", "系统项目（未归属桶）不可被选为虚拟密钥的绑定项目。");
        }
        if (project.status() != ProjectStatus.ACTIVE) {
            // #1154: the picker no longer offers disabled projects, but this guard still
            // catches paths that never pass through it — admin delegation, a project
            // disabled between page load and submit, and callers of the raw API. For those
            // the detail is the only thing the user gets, so it names the next step the way
            // its siblings in this method do.
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_INACTIVE",
                    "项目已停用，无法创建 Virtual Key；请联系管理员在项目设置中恢复为「启用」后重试");
        }
        if (project.projectTag() == null || project.projectTag().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTING_TAG_MISSING",
                    "项目尚未设置路由标签，无法创建 Virtual Key；请联系管理员在项目设置中补充后重试");
        }
        if (memberSubject.role() != UserRole.SYSTEM_ADMIN
                && !membershipRepository.exists(project.id(), memberSubject.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_MEMBERSHIP_REQUIRED",
                    delegatedTargetId != null ? "目标用户不是该项目成员，不能代其建钥。" : "你不是该项目的成员，无法创建 Virtual Key。");
        }
        return project;
    }

    /**
     * Lightweight (projectId, grantId) carrier for planned/mirrored binding rows.
     */
    private record BindingPlan(UUID projectId, UUID grantId) {
    }

    private List<BoundProjectView> boundProjects(List<BindingPlan> bindings) {
        List<BoundProjectView> views = new ArrayList<>(bindings.size());
        for (BindingPlan binding : bindings) {
            String tag = projectRepository.findById(binding.projectId()).map(Project::projectTag).orElse(null);
            views.add(new BoundProjectView(binding.projectId(), tag));
        }
        return views;
    }

    /**
     * Atomically rotates a key: the old key enters ROTATING with a grace window, a
     * fresh replacement key becomes active with the same bindings. The new full
     * secret is returned exactly once.
     */
    @Transactional
    public CreateVirtualKeyResponse rotate(User user, UUID keyId, String requestId) {
        VirtualKey oldKey = ownedKey(user, keyId);
        if (oldKey.status() != VirtualKeyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ROTATABLE", "Only an active key can be rotated");
        }
        Set<String> models = keyRepository.findModelIds(oldKey.id());
        // The replacement keeps the same routing label as the key it replaces.
        String projectTag = projectRepository.findById(oldKey.projectId()).map(Project::projectTag).orElse(null);
        // ADR-0018: mirror EVERY project binding, not just the primary one.
        List<BindingPlan> mirrored = bindingRepository.findAllByVirtualKeyId(oldKey.id()).stream()
                .filter(b -> b.status() == KeyProjectBindingStatus.ACTIVE)
                .map(b -> new BindingPlan(b.projectId(), b.grantId())).toList();

        VirtualKeyMaterial material = keyCrypto.generate(user.tenantId(), projectTag);
        try {
            Instant now = Instant.now();
            UUID newKeyId = UUID.randomUUID();
            VirtualKey replacement = new VirtualKey(newKeyId, oldKey.tenantId(), material.publicKeyId(),
                    material.digest(), material.displayPrefix(), material.lastFour(), oldKey.userId(),
                    oldKey.projectId(), oldKey.grantId(), oldKey.upstreamCredentialId(), oldKey.purpose(),
                    oldKey.name(), oldKey.cachePolicy(), VirtualKeyStatus.ACTIVE, now, null, null, null, 0L);
            keyRepository.insert(replacement);
            for (BindingPlan plan : mirrored) {
                bindingRepository.insert(new KeyProjectBinding(UUID.randomUUID(), oldKey.tenantId(), newKeyId,
                        plan.projectId(), plan.grantId(), KeyProjectBindingStatus.ACTIVE, 0L, now, now));
            }
            keyRepository.replaceKeyModels(oldKey.tenantId(), newKeyId, models);

            Instant revokedAt = now.plus(authProperties.getVirtualKeyRotateGrace());
            VirtualKey rotated = new VirtualKey(oldKey.id(), oldKey.tenantId(), oldKey.publicKeyId(),
                    oldKey.secretDigest(), oldKey.displayPrefix(), oldKey.lastFour(), oldKey.userId(),
                    oldKey.projectId(), oldKey.grantId(), oldKey.upstreamCredentialId(), oldKey.purpose(),
                    oldKey.name(), oldKey.cachePolicy(), VirtualKeyStatus.ROTATING, oldKey.createdAt(),
                    oldKey.lastUsedAt(), revokedAt, newKeyId, oldKey.version() + 1);
            keyRepository.update(rotated);

            auditService.record(oldKey.tenantId(), user.id(), "VIRTUAL_KEY_ROTATE", "VIRTUAL_KEY", oldKey.id(),
                    auditSummary("replacedBy", newKeyId), requestId);
            auditService.record(oldKey.tenantId(), user.id(), "VIRTUAL_KEY_CREATE", "VIRTUAL_KEY", newKeyId,
                    auditSummary("name", sanitize(oldKey.name()), "purpose", oldKey.purpose(), "models", models.size(),
                            "cachePolicy", oldKey.cachePolicy()),
                    requestId);
            routeRefreshPublisher.publishChanged();
            return response(newKeyId, material, now, boundProjects(mirrored));
        } finally {
            material.destroy();
        }
    }

    /**
     * Revokes a key immediately; the gateway stops routing it at the next snapshot
     * refresh.
     */
    @Transactional
    public void revoke(User user, UUID keyId, String requestId) {
        VirtualKey key = ownedKey(user, keyId);
        if (key.status() != VirtualKeyStatus.ACTIVE && key.status() != VirtualKeyStatus.ROTATING) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_REVOCABLE", "This key cannot be revoked");
        }
        Instant now = Instant.now();
        VirtualKey revoked = new VirtualKey(key.id(), key.tenantId(), key.publicKeyId(), key.secretDigest(),
                key.displayPrefix(), key.lastFour(), key.userId(), key.projectId(), key.grantId(),
                key.upstreamCredentialId(), key.purpose(), key.name(), key.cachePolicy(), VirtualKeyStatus.REVOKED,
                key.createdAt(), key.lastUsedAt(), now, key.replacedByKeyId(), key.version() + 1);
        keyRepository.update(revoked);
        auditService.record(key.tenantId(), user.id(), "VIRTUAL_KEY_REVOKE", "VIRTUAL_KEY", key.id(),
                auditSummary("status", "REVOKED"), requestId);
        routeRefreshPublisher.publishChanged();
    }

    /**
     * Temporarily disables an ACTIVE key (#582): the route snapshot drops it at the
     * next refresh (its requests become the uniform unknown-key 404), and
     * {@link #enable} restores it with every binding intact. Distinct from revoke,
     * which is irreversible.
     */
    @Transactional
    public VirtualKeyView disable(User user, UUID keyId, String requestId) {
        VirtualKey key = ownedKey(user, keyId);
        if (key.status() != VirtualKeyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_DISABLEABLE", "只有可用状态的密钥可以停用");
        }
        VirtualKey disabled = withStatus(key, VirtualKeyStatus.DISABLED);
        keyRepository.update(disabled);
        auditService.record(key.tenantId(), user.id(), "VIRTUAL_KEY_DISABLE", "VIRTUAL_KEY", key.id(),
                auditSummary("status", "DISABLED"), requestId);
        routeRefreshPublisher.publishChanged();
        return view(disabled, user.tenantId());
    }

    /**
     * Re-enables a DISABLED key (#582); routing resumes at the next snapshot
     * refresh.
     */
    @Transactional
    public VirtualKeyView enable(User user, UUID keyId, String requestId) {
        VirtualKey key = ownedKey(user, keyId);
        if (key.status() != VirtualKeyStatus.DISABLED) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ENABLEABLE", "只有已停用的密钥可以启用");
        }
        VirtualKey enabled = withStatus(key, VirtualKeyStatus.ACTIVE);
        keyRepository.update(enabled);
        auditService.record(key.tenantId(), user.id(), "VIRTUAL_KEY_ENABLE", "VIRTUAL_KEY", key.id(),
                auditSummary("status", "ACTIVE"), requestId);
        routeRefreshPublisher.publishChanged();
        return view(enabled, user.tenantId());
    }

    /**
     * Renames a key (#582). Routing does not depend on the name, so no snapshot
     * refresh is published; revoked tombstones stay immutable.
     */
    @Transactional
    public VirtualKeyView rename(User user, UUID keyId, String name, String requestId) {
        VirtualKey key = ownedKey(user, keyId);
        if (key.status() == VirtualKeyStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_RENAMEABLE", "已吊销的密钥不可重命名");
        }
        String newName = name.trim();
        VirtualKey renamed = new VirtualKey(key.id(), key.tenantId(), key.publicKeyId(), key.secretDigest(),
                key.displayPrefix(), key.lastFour(), key.userId(), key.projectId(), key.grantId(),
                key.upstreamCredentialId(), key.purpose(), newName, key.cachePolicy(), key.status(), key.createdAt(),
                key.lastUsedAt(), key.revokedAt(), key.replacedByKeyId(), key.version() + 1);
        keyRepository.update(renamed);
        auditService.record(key.tenantId(), user.id(), "VIRTUAL_KEY_RENAME", "VIRTUAL_KEY", key.id(),
                auditSummary("from", sanitize(key.name()), "to", sanitize(newName)), requestId);
        return view(renamed, user.tenantId());
    }

    /** Status-only transition preserving every other field (#582). */
    private VirtualKey withStatus(VirtualKey key, VirtualKeyStatus status) {
        return new VirtualKey(key.id(), key.tenantId(), key.publicKeyId(), key.secretDigest(), key.displayPrefix(),
                key.lastFour(), key.userId(), key.projectId(), key.grantId(), key.upstreamCredentialId(), key.purpose(),
                key.name(), key.cachePolicy(), status, key.createdAt(), key.lastUsedAt(), key.revokedAt(),
                key.replacedByKeyId(), key.version() + 1);
    }

    /** Lists the caller's own keys with safe metadata (no secrets). */
    public List<VirtualKeyView> list(User user) {
        List<VirtualKey> keys = keyRepository.findAllByUserId(user.id());
        List<VirtualKeyView> views = new ArrayList<>(keys.size());
        for (VirtualKey key : keys) {
            views.add(view(key, user.tenantId()));
        }
        return views;
    }

    /** Open-admin view (ADR-0016 增补): all keys owned by one tenant user. */
    public List<VirtualKeyView> listForTenantUser(UUID tenantId, UUID userId) {
        return keyRepository.findAllByTenantIdAndUserId(tenantId, userId).stream().map(k -> view(k, tenantId)).toList();
    }

    /** Detail of one of the caller's own keys; generic 404 for anything else. */
    public VirtualKeyView get(User user, UUID keyId) {
        return view(ownedKey(user, keyId), user.tenantId());
    }

    /** What the user may pick when creating a key (projects, grants, purposes). */
    public MeGrantsResponse grantOptions(User user) {
        List<MeGrantsResponse.ProjectOption> projects = new ArrayList<>();
        List<MeGrantsResponse.GrantOption> grants = new ArrayList<>();
        List<UUID> projectIds = new ArrayList<>();
        Map<UUID, ProviderProduct> productCache = new HashMap<>();
        if (user.role() == UserRole.SYSTEM_ADMIN) {
            projectRepository.findAllByTenantId(user.tenantId()).forEach(p -> projectIds.add(p.id()));
        } else {
            membershipRepository.findAllByUserId(user.id()).forEach(m -> projectIds.add(m.projectId()));
        }
        for (UUID projectId : projectIds) {
            // #1145: the UNATTRIBUTED bucket is an accounting sink, never a key binding —
            // requireBindableProject rejects it further down this same class, so listing it
            // here advertises a choice that can never succeed. Its provider grants go with
            // it: a grant on a project no key can bind to enables nothing.
            // #1154: the ACTIVE check lives in this shared step rather than only in the
            // admin branch, where it used to sit alone. Memberships and grants both outlive
            // a project being disabled, so the member branch was offering a project that
            // requireBindableProject rejects and that the member cannot re-enable.
            projectRepository.findById(projectId).filter(p -> p.tenantId().equals(user.tenantId()))
                    .filter(p -> !p.system()).filter(p -> p.status() == ProjectStatus.ACTIVE).ifPresent(p -> {
                        projects.add(new MeGrantsResponse.ProjectOption(p.id(), p.code(), p.name(), p.projectTag()));
                        for (ProjectProviderGrant g : grantRepository.findAllByProjectIdAndStatus(p.id(), "ACTIVE")) {
                            // Display identity for the picker: a raw product UUID tells
                            // the user nothing (#528). Null when the product row is gone.
                            ProviderProduct product = productCache.computeIfAbsent(g.providerProductId(),
                                    id -> productRepository.findById(id).orElse(null));
                            // Deterministic model list (lexicographic) — the underlying
                            // repository returns an unordered Set.
                            grants.add(new MeGrantsResponse.GrantOption(g.id(), g.projectId(), g.providerProductId(),
                                    new TreeSet<>(grantRepository.findModelIds(g.id())),
                                    product != null ? product.productCode() : null,
                                    product != null ? product.displayName() : null));
                        }
                    });
        }
        List<String> purposes = new ArrayList<>();
        for (VirtualKeyPurpose purpose : VirtualKeyPurpose.values()) {
            purposes.add(purpose.name());
        }
        return new MeGrantsResponse(projects, grants, purposes);
    }

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

    private VirtualKeyView view(VirtualKey key, UUID tenantId) {
        String projectTag = projectRepository.findById(key.projectId()).map(Project::projectTag).orElse(null);
        List<BoundProjectView> bound = boundProjects(bindingRepository.findAllByVirtualKeyId(key.id()).stream()
                .filter(b -> b.status() == KeyProjectBindingStatus.ACTIVE)
                .map(b -> new BindingPlan(b.projectId(), b.grantId())).toList());
        return new VirtualKeyView(key.id(), key.name(), key.purpose(), key.status(), key.displayPrefix(),
                key.lastFour(), key.displayPrefix() + "…" + key.lastFour(), keyRepository.findModelIds(key.id()),
                key.projectId(), projectTag, bound, key.cachePolicy(), authProperties.getGatewayBaseUrl(),
                key.createdAt(), key.lastUsedAt(), key.revokedAt());
    }

    private CreateVirtualKeyResponse response(UUID keyId, VirtualKeyMaterial material, Instant now,
            List<BoundProjectView> boundProjects) {
        return new CreateVirtualKeyResponse(keyId, material.fullDisplayString(), authProperties.getGatewayBaseUrl(),
                material.displayPrefix() + "…" + material.lastFour(), true, now, 1L, boundProjects);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Builds a valid JSON document for the {@code change_summary} jsonb column; the
     * admin_audit_events insert casts the summary to jsonb, so plain-text summaries
     * would fail with "invalid input syntax for type json".
     */
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
