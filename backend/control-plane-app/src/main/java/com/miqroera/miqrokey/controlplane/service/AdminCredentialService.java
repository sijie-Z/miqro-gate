package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.AdminCredentialCreateRequest;
import com.miqroera.miqrokey.controlplane.dto.CredentialDetailView;
import com.miqroera.miqrokey.controlplane.dto.CredentialVersionView;
import com.miqroera.miqrokey.controlplane.dto.CredentialView;
import com.miqroera.miqrokey.controlplane.dto.RotateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialResponse;
import com.miqroera.miqrokey.domain.credential.CredentialSecretValidator;
import com.miqroera.miqrokey.domain.crypto.CredentialFingerprint;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.CredentialVersionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialVersionRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin lifecycle for upstream credentials (api-contract §5): create, validate,
 * rotate, disable, list and detail.
 *
 * <h2>Security invariants</h2>
 * <ul>
 * <li>Plaintext secrets are accepted only on write inputs and are encrypted
 * (AES-256-GCM, AAD-bound to tenant + credential) before anything is persisted;
 * responses and audit summaries contain only metadata and a masked fingerprint
 * prefix, never the secret.</li>
 * <li>Validation is side-effect free: a test of an unsaved value never writes
 * to the database.</li>
 * <li>Rotation and disable hold the credential row lock
 * ({@code SELECT ... FOR UPDATE}) and demote the current ACTIVE version to
 * DRAINING before inserting the new ACTIVE version, so the partial unique index
 * {@code uq_credential_versions_one_active} never sees two ACTIVE rows and
 * concurrent lifecycle transitions serialize.</li>
 * <li>The drained version keeps decrypting until {@code retiredAt}
 * ({@code miqrokey.credential-drain-grace}); requests that already decrypted
 * the old secret complete, while requests started after the gateway snapshot
 * refresh use the new version (or fail cleanly once a credential is
 * DISABLED).</li>
 * </ul>
 */
@Service
public class AdminCredentialService {

    private static final int FINGERPRINT_PREFIX_BYTES = 8;

    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamCredentialVersionRepository versionRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final CredentialSecretValidator secretValidator;
    private final AuditService auditService;
    private final AuthProperties authProperties;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminCredentialService(UpstreamCredentialRepository credentialRepository,
            UpstreamCredentialVersionRepository versionRepository,
            UpstreamSubscriptionRepository subscriptionRepository, KeyEncryptionProvider keyEncryptionProvider,
            CredentialSecretValidator secretValidator, AuditService auditService, AuthProperties authProperties,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.credentialRepository = credentialRepository;
        this.versionRepository = versionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.secretValidator = secretValidator;
        this.auditService = auditService;
        this.authProperties = authProperties;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    /**
     * Creates a credential with a single ACTIVE version. The plaintext is validated
     * before anything is written; an invalid secret leaves the database untouched.
     */
    @Transactional
    public CredentialView create(User admin, AdminCredentialCreateRequest request, String requestId) {
        UUID tenantId = admin.tenantId();
        UpstreamSubscription subscription = subscriptionRepository.findById(request.subscriptionId())
                .filter(s -> s.tenantId().equals(tenantId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "SUBSCRIPTION_NOT_FOUND", "Subscription not found"));
        requireValidSecret(request.secret());

        Instant now = Instant.now();
        UUID credentialId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        byte[] fingerprint = CredentialFingerprint.sha256(request.secret());
        EncryptedSecret encrypted = keyEncryptionProvider.encrypt(request.secret().getBytes(StandardCharsets.UTF_8),
                tenantId, credentialId);

        // The two FKs between upstream_credentials and
        // upstream_credential_versions are circular (active_version_id <->
        // credential_id), so creation is three steps: insert the credential
        // without an active version, insert the version, then point the
        // credential at it via the optimistic-locked update (version 0 -> 1).
        UpstreamCredential credential = new UpstreamCredential(credentialId, tenantId, subscription.id(), null,
                request.name(), fingerprint, CredentialStatus.ACTIVE, null, now, null, 0L, now, now);
        credentialRepository.insert(credential);
        versionRepository.insert(new UpstreamCredentialVersion(versionId, tenantId, credentialId,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), fingerprint,
                CredentialVersionStatus.ACTIVE, now, null, now));
        UpstreamCredential pointed = new UpstreamCredential(credentialId, tenantId, subscription.id(), null,
                request.name(), fingerprint, CredentialStatus.ACTIVE, versionId, now, null, 1L, now, now);
        credentialRepository.update(pointed);

        auditService.record(tenantId, admin.id(), "CREDENTIAL_CREATE", "UPSTREAM_CREDENTIAL", credentialId,
                auditSummary("name", sanitize(request.name()), "subscriptionId", subscription.id()), requestId);
        routeRefreshPublisher.publishChanged();
        return toView(pointed);
    }

    /**
     * Validates a candidate secret against an existing credential without writing
     * anything. A well-formed secret is compared by SHA-256 fingerprint with the
     * currently active version (no decryption, no plaintext exposure).
     */
    @Transactional(readOnly = true)
    public ValidateCredentialResponse validate(User admin, UUID credentialId, ValidateCredentialRequest request,
            String requestId) {
        findOwned(credentialId, admin.tenantId());
        CredentialSecretValidator.ValidationResult result = secretValidator.validate(request.secret());
        if (!result.valid()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_INVALID", result.reason());
        }
        byte[] fingerprint = CredentialFingerprint.sha256(request.secret());
        UpstreamCredentialVersion active = versionRepository.findActiveByCredentialId(credentialId).orElse(null);
        boolean matchesActive = active != null && MessageDigest.isEqual(active.secretFingerprint(), fingerprint);
        if (matchesActive) {
            return new ValidateCredentialResponse(true, null);
        }
        return new ValidateCredentialResponse(false, "The secret does not match the active version");
    }

    /**
     * Atomic rotation. The credential row is locked, versions whose grace already
     * elapsed are retired, the current ACTIVE version is demoted to DRAINING
     * (retiredAt = now + drain grace), and a new ACTIVE version is inserted. A
     * failed pre-validation aborts before any write.
     */
    @Transactional
    public CredentialView rotate(User admin, UUID credentialId, RotateCredentialRequest request, String requestId) {
        UUID tenantId = admin.tenantId();
        UpstreamCredential credential = findOwnedForUpdate(credentialId, tenantId);
        if (credential.status() != CredentialStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_NOT_ROTATABLE",
                    "Only ACTIVE credentials can be rotated");
        }
        requireValidSecret(request.secret());

        Instant now = Instant.now();
        Instant graceEnd = now.plus(authProperties.getCredentialDrainGrace());
        retireExpiredVersions(credentialId, now);
        // Demote before insert: uq_credential_versions_one_active allows at most
        // one ACTIVE version per credential.
        versionRepository.findActiveByCredentialId(credentialId)
                .ifPresent(active -> versionRepository.update(demote(active, graceEnd)));

        UUID versionId = UUID.randomUUID();
        byte[] fingerprint = CredentialFingerprint.sha256(request.secret());
        EncryptedSecret encrypted = keyEncryptionProvider.encrypt(request.secret().getBytes(StandardCharsets.UTF_8),
                tenantId, credentialId);
        versionRepository.insert(new UpstreamCredentialVersion(versionId, tenantId, credentialId,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), fingerprint,
                CredentialVersionStatus.ACTIVE, now, null, now));

        UpstreamCredential updated = new UpstreamCredential(credential.id(), credential.tenantId(),
                credential.subscriptionId(), credential.seatId(), credential.credentialName(), fingerprint,
                CredentialStatus.ACTIVE, versionId, now, null, credential.version() + 1, credential.createdAt(), now);
        credentialRepository.update(updated);

        auditService.record(tenantId, admin.id(), "CREDENTIAL_ROTATE", "UPSTREAM_CREDENTIAL", credentialId,
                auditSummary("version", versionId), requestId);
        routeRefreshPublisher.publishChanged();
        return toView(updated);
    }

    /**
     * Disables a credential. The current ACTIVE version is drained (grace window)
     * and the credential stops appearing in the gateway route snapshot, so new
     * requests fail cleanly while in-flight ones complete.
     */
    @Transactional
    public void disable(User admin, UUID credentialId, String requestId) {
        UUID tenantId = admin.tenantId();
        UpstreamCredential credential = findOwnedForUpdate(credentialId, tenantId);
        if (credential.status() == CredentialStatus.DISABLED || credential.status() == CredentialStatus.INVALID) {
            throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_NOT_DISABLEABLE",
                    "Credential is already " + credential.status());
        }
        Instant now = Instant.now();
        retireExpiredVersions(credentialId, now);
        versionRepository.findActiveByCredentialId(credentialId).ifPresent(
                active -> versionRepository.update(demote(active, now.plus(authProperties.getCredentialDrainGrace()))));

        UpstreamCredential updated = new UpstreamCredential(credential.id(), credential.tenantId(),
                credential.subscriptionId(), credential.seatId(), credential.credentialName(),
                credential.secretFingerprint(), CredentialStatus.DISABLED, credential.activeVersionId(),
                credential.lastValidatedAt(), credential.lastValidationError(), credential.version() + 1,
                credential.createdAt(), now);
        credentialRepository.update(updated);

        auditService.record(tenantId, admin.id(), "CREDENTIAL_DISABLE", "UPSTREAM_CREDENTIAL", credentialId,
                auditSummary("status", "DISABLED"), requestId);
        routeRefreshPublisher.publishChanged();
    }

    /** Lists all credentials in the caller's tenant. */
    @Transactional(readOnly = true)
    public List<CredentialView> list(User admin) {
        // Single tenant in v1: list everything, but scope by tenant for safety.
        return credentialRepository.findAllByTenantId(admin.tenantId()).stream().map(this::toView).toList();
    }

    /** Credential metadata plus the full version history (newest first). */
    @Transactional(readOnly = true)
    public CredentialDetailView detail(User admin, UUID credentialId) {
        UpstreamCredential credential = findOwned(credentialId, admin.tenantId());
        List<CredentialVersionView> versions = versionRepository.findAllByCredentialId(credentialId).stream()
                .map(this::toVersionView).toList();
        return new CredentialDetailView(toView(credential), versions);
    }

    // ------------------------------------------------------------------

    private UpstreamCredential findOwned(UUID credentialId, UUID tenantId) {
        return credentialRepository.findById(credentialId).filter(c -> c.tenantId().equals(tenantId)).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Credential not found"));
    }

    private UpstreamCredential findOwnedForUpdate(UUID credentialId, UUID tenantId) {
        return credentialRepository.findByIdForUpdate(credentialId).filter(c -> c.tenantId().equals(tenantId))
                .orElseThrow(
                        () -> new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Credential not found"));
    }

    private void requireValidSecret(String secret) {
        CredentialSecretValidator.ValidationResult result = secretValidator.validate(secret);
        if (!result.valid()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_INVALID", result.reason());
        }
    }

    /** Lazily retires DRAINING versions whose grace window already elapsed. */
    private void retireExpiredVersions(UUID credentialId, Instant now) {
        for (UpstreamCredentialVersion v : versionRepository.findAllByCredentialId(credentialId)) {
            if (v.status() == CredentialVersionStatus.DRAINING && v.retiredAt() != null
                    && !v.retiredAt().isAfter(now)) {
                versionRepository.update(new UpstreamCredentialVersion(v.id(), v.tenantId(), v.credentialId(),
                        v.encryptedSecret(), v.nonce(), v.encryptionKeyVersion(), v.secretFingerprint(),
                        CredentialVersionStatus.RETIRED, v.validFrom(), v.retiredAt(), v.createdAt()));
            }
        }
    }

    private UpstreamCredentialVersion demote(UpstreamCredentialVersion v, Instant retiredAt) {
        return new UpstreamCredentialVersion(v.id(), v.tenantId(), v.credentialId(), v.encryptedSecret(), v.nonce(),
                v.encryptionKeyVersion(), v.secretFingerprint(), CredentialVersionStatus.DRAINING, v.validFrom(),
                retiredAt, v.createdAt());
    }

    private CredentialView toView(UpstreamCredential c) {
        return new CredentialView(c.id(), c.credentialName(), c.subscriptionId(), c.status().name(),
                c.activeVersionId(), CredentialFingerprint.hexPrefix(c.secretFingerprint(), FINGERPRINT_PREFIX_BYTES),
                c.lastValidatedAt(), c.lastValidationError(), c.version(), c.createdAt(), c.updatedAt());
    }

    private CredentialVersionView toVersionView(UpstreamCredentialVersion v) {
        return new CredentialVersionView(v.id(), v.status().name(), v.encryptionKeyVersion(),
                CredentialFingerprint.hexPrefix(v.secretFingerprint(), FINGERPRINT_PREFIX_BYTES), v.validFrom(),
                v.retiredAt(), v.createdAt());
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
