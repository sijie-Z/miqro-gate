package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
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
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.KeyProjectBindingRepository;
import com.miqroera.miqrokey.domain.repository.ProjectMembershipRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final ProjectMembershipRepository membershipRepository;
    private final VirtualKeyCrypto keyCrypto;
    private final AuditService auditService;
    private final AuthProperties authProperties;

    public VirtualKeyService(VirtualKeyRepository keyRepository, KeyProjectBindingRepository bindingRepository,
            ProjectRepository projectRepository, ProjectProviderGrantRepository grantRepository,
            ProjectMembershipRepository membershipRepository, VirtualKeyCrypto keyCrypto, AuditService auditService,
            AuthProperties authProperties) {
        this.keyRepository = keyRepository;
        this.bindingRepository = bindingRepository;
        this.projectRepository = projectRepository;
        this.grantRepository = grantRepository;
        this.membershipRepository = membershipRepository;
        this.keyCrypto = keyCrypto;
        this.auditService = auditService;
        this.authProperties = authProperties;
    }

    /**
     * Creates a key and returns the one-time full secret. The caller must show it
     * to the user immediately; the server keeps only the digest.
     */
    @Transactional
    public CreateVirtualKeyResponse create(User user, CreateVirtualKeyRequest request, String requestId) {
        UUID tenantId = user.tenantId();
        Project project = projectRepository.findById(request.projectId()).filter(p -> p.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_INACTIVE", "The project is not active");
        }
        if (project.projectTag() == null || project.projectTag().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTING_TAG_MISSING",
                    "The project has no routing tag; an administrator must assign one before keys can be created");
        }
        if (user.role() != UserRole.SYSTEM_ADMIN && !membershipRepository.exists(project.id(), user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_MEMBERSHIP_REQUIRED",
                    "You are not a member of this project");
        }

        ProjectProviderGrant grant = grantRepository.findById(request.credentialGrantId())
                .filter(g -> g.tenantId().equals(tenantId)).filter(g -> g.projectId().equals(project.id()))
                .filter(g -> g.providerProductId().equals(request.providerProductId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GRANT_INVALID",
                        "The credential grant does not match the project and provider product"));
        if (grant.status() != GrantStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_INACTIVE", "The credential grant is not active");
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

        VirtualKeyMaterial material = keyCrypto.generate(tenantId);
        try {
            Instant now = Instant.now();
            UUID keyId = UUID.randomUUID();
            VirtualKey key = new VirtualKey(keyId, tenantId, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(),
                    grant.upstreamCredentialId(), request.purpose(), request.name(), cachePolicy,
                    VirtualKeyStatus.ACTIVE, now, null, null, null, 0L);
            keyRepository.insert(key);
            bindingRepository.insert(new KeyProjectBinding(UUID.randomUUID(), tenantId, keyId, project.id(),
                    KeyProjectBindingStatus.ACTIVE, 0L, now, now));
            keyRepository.replaceKeyModels(tenantId, keyId, requested);
            auditService.record(tenantId, user.id(), "VIRTUAL_KEY_CREATE", "VIRTUAL_KEY", keyId,
                    "name=" + sanitize(request.name()) + ", purpose=" + request.purpose() + ", models="
                            + requested.size() + ", cachePolicy=" + cachePolicy,
                    requestId);
            return response(keyId, material, now);
        } finally {
            material.destroy();
        }
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

        VirtualKeyMaterial material = keyCrypto.generate(user.tenantId());
        try {
            Instant now = Instant.now();
            UUID newKeyId = UUID.randomUUID();
            VirtualKey replacement = new VirtualKey(newKeyId, oldKey.tenantId(), material.publicKeyId(),
                    material.digest(), material.displayPrefix(), material.lastFour(), oldKey.userId(),
                    oldKey.projectId(), oldKey.grantId(), oldKey.upstreamCredentialId(), oldKey.purpose(),
                    oldKey.name(), oldKey.cachePolicy(), VirtualKeyStatus.ACTIVE, now, null, null, null, 0L);
            keyRepository.insert(replacement);
            bindingRepository.insert(new KeyProjectBinding(UUID.randomUUID(), oldKey.tenantId(), newKeyId,
                    oldKey.projectId(), KeyProjectBindingStatus.ACTIVE, 0L, now, now));
            keyRepository.replaceKeyModels(oldKey.tenantId(), newKeyId, models);

            Instant revokedAt = now.plus(authProperties.getVirtualKeyRotateGrace());
            VirtualKey rotated = new VirtualKey(oldKey.id(), oldKey.tenantId(), oldKey.publicKeyId(),
                    oldKey.secretDigest(), oldKey.displayPrefix(), oldKey.lastFour(), oldKey.userId(),
                    oldKey.projectId(), oldKey.grantId(), oldKey.upstreamCredentialId(), oldKey.purpose(),
                    oldKey.name(), oldKey.cachePolicy(), VirtualKeyStatus.ROTATING, oldKey.createdAt(),
                    oldKey.lastUsedAt(), revokedAt, newKeyId, oldKey.version() + 1);
            keyRepository.update(rotated);

            auditService.record(oldKey.tenantId(), user.id(), "VIRTUAL_KEY_ROTATE", "VIRTUAL_KEY", oldKey.id(),
                    "replacedBy=" + newKeyId, requestId);
            auditService.record(oldKey.tenantId(), user.id(), "VIRTUAL_KEY_CREATE", "VIRTUAL_KEY", newKeyId,
                    "name=" + sanitize(oldKey.name()) + ", purpose=" + oldKey.purpose() + ", models=" + models.size()
                            + ", cachePolicy=" + oldKey.cachePolicy(),
                    requestId);
            return response(newKeyId, material, now);
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
        auditService.record(key.tenantId(), user.id(), "VIRTUAL_KEY_REVOKE", "VIRTUAL_KEY", key.id(), "status=REVOKED",
                requestId);
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

    /** Detail of one of the caller's own keys; generic 404 for anything else. */
    public VirtualKeyView get(User user, UUID keyId) {
        return view(ownedKey(user, keyId), user.tenantId());
    }

    /** What the user may pick when creating a key (projects, grants, purposes). */
    public MeGrantsResponse grantOptions(User user) {
        List<MeGrantsResponse.ProjectOption> projects = new ArrayList<>();
        List<MeGrantsResponse.GrantOption> grants = new ArrayList<>();
        List<UUID> projectIds = new ArrayList<>();
        if (user.role() == UserRole.SYSTEM_ADMIN) {
            for (Project p : projectRepository.findAllByTenantId(user.tenantId())) {
                if (p.status() == ProjectStatus.ACTIVE) {
                    projectIds.add(p.id());
                }
            }
        } else {
            membershipRepository.findAllByUserId(user.id()).forEach(m -> projectIds.add(m.projectId()));
        }
        for (UUID projectId : projectIds) {
            projectRepository.findById(projectId).filter(p -> p.tenantId().equals(user.tenantId())).ifPresent(p -> {
                projects.add(new MeGrantsResponse.ProjectOption(p.id(), p.code(), p.name(), p.projectTag()));
                for (ProjectProviderGrant g : grantRepository.findAllByProjectIdAndStatus(p.id(), "ACTIVE")) {
                    grants.add(new MeGrantsResponse.GrantOption(g.id(), g.projectId(), g.providerProductId(),
                            grantRepository.findModelIds(g.id())));
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
        return new VirtualKeyView(key.id(), key.name(), key.purpose(), key.status(), key.displayPrefix(),
                key.lastFour(), key.displayPrefix() + "…" + key.lastFour(), keyRepository.findModelIds(key.id()),
                key.projectId(), projectTag, key.cachePolicy(), authProperties.getGatewayBaseUrl(), key.createdAt(),
                key.lastUsedAt(), key.revokedAt());
    }

    private CreateVirtualKeyResponse response(UUID keyId, VirtualKeyMaterial material, Instant now) {
        return new CreateVirtualKeyResponse(keyId, material.fullDisplayString(), authProperties.getGatewayBaseUrl(),
                material.displayPrefix() + "…" + material.lastFour(), true, now, 1L);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }
}
