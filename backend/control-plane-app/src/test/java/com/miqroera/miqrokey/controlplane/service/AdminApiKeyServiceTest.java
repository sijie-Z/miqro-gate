package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminApiKeyView;
import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Open admin API key lifecycle (ADR-0015)")
class AdminApiKeyServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final AdminApiKeyService service = new AdminApiKeyService(repository, new AuditService() {
        @Override
        public void record(UUID tenantId, UUID actorId, String action, String targetType, UUID targetId,
                String changeSummary, String requestId) {
            recorded.add(action);
        }
    });
    private final List<String> recorded = new ArrayList<>();

    static final class FakeRepository implements AdminApiKeyRepository {
        final List<AdminApiKey> rows = new ArrayList<>();

        @Override
        public AdminApiKey insert(AdminApiKey key) {
            boolean duplicate = rows.stream()
                    .anyMatch(k -> k.tenantId().equals(key.tenantId()) && k.name().equals(key.name()));
            if (duplicate) {
                throw new org.springframework.dao.DuplicateKeyException("uq_admin_api_keys_tenant_name");
            }
            rows.add(key);
            return key;
        }

        @Override
        public List<AdminApiKey> findAllByTenantId(UUID tenantId) {
            return rows.stream().filter(k -> k.tenantId().equals(tenantId)).toList();
        }

        @Override
        public Optional<AdminApiKey> findByIdAndTenantId(UUID id, UUID tenantId) {
            return rows.stream().filter(k -> k.id().equals(id) && k.tenantId().equals(tenantId)).findFirst();
        }

        @Override
        public Optional<AdminApiKey> findActiveByDigest(byte[] keyDigest) {
            return rows.stream().filter(k -> java.util.Arrays.equals(k.keyDigest(), keyDigest))
                    .filter(AdminApiKey::active).findFirst();
        }

        @Override
        public java.util.List<AdminApiKey> findActiveExpiringBetween(UUID tenantId, Instant from, Instant to) {
            return rows
                    .stream().filter(k -> k.tenantId().equals(tenantId) && k.revokedAt() == null
                            && k.expiresAt() != null && !k.expiresAt().isBefore(from) && !k.expiresAt().isAfter(to))
                    .toList();
        }

        @Override
        public boolean updateScope(UUID id, UUID tenantId, List<String> capabilities) {
            AdminApiKey row = findByIdAndTenantId(id, tenantId).orElse(null);
            if (row == null) {
                return false;
            }
            rows.set(rows.indexOf(row), new AdminApiKey(row.id(), row.tenantId(), row.name(), row.keyDigest(),
                    row.keyPrefix(), row.createdBy(), row.expiresAt(), row.revokedAt(), row.createdAt(), capabilities));
            return true;
        }

        @Override
        public boolean revoke(UUID id, UUID tenantId, Instant revokedAt) {
            AdminApiKey row = findByIdAndTenantId(id, tenantId).orElse(null);
            if (row == null || row.revokedAt() != null) {
                return false;
            }
            rows.set(rows.indexOf(row), new AdminApiKey(row.id(), row.tenantId(), row.name(), row.keyDigest(),
                    row.keyPrefix(), row.createdBy(), row.expiresAt(), revokedAt, row.createdAt(), row.capabilities()));
            return true;
        }
    }

    private final UUID tenant = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void clear() {
        recorded.clear();
    }

    @Test
    @DisplayName("issue stores only the digest and returns the plaintext once")
    void issueStoresDigestOnly() {
        AdminApiKeyService.Issued issued = service.issue(tenant, actor, "ops-read", null);

        assertThat(issued.secret()).startsWith("mqk_admin_");
        assertThat(repository.rows).hasSize(1);
        AdminApiKey stored = repository.rows.get(0);
        assertThat(stored.keyDigest()).isNotEqualTo(issued.secret().getBytes());
        assertThat(stored.keyDigest()).hasSize(32);
        assertThat(new String(stored.keyDigest())).doesNotContain("mqk_admin_");
        assertThat(stored.active()).isTrue();
        assertThat(recorded).containsExactly("ADMIN_API_KEY_ISSUE");
    }

    @Test
    @DisplayName("list surfaces views without digests and expiry flips active")
    void listAndExpiry() {
        service.issue(tenant, actor, "short-lived", Instant.now().plus(10, ChronoUnit.MINUTES));
        service.issue(tenant, actor, "expired", Instant.now().minusSeconds(5));

        List<AdminApiKeyView> views = service.list(tenant);
        assertThat(views).hasSize(2);
        assertThat(views.stream().filter(AdminApiKeyView::active)).hasSize(1);
    }

    @Test
    @DisplayName("revoke is immediate, idempotent conflicts are rejected, unknown id 404s")
    void revokeSemantics() {
        AdminApiKeyService.Issued issued = service.issue(tenant, actor, "ops", null);

        AdminApiKeyView revoked = service.revoke(tenant, actor, issued.id());
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.active()).isFalse();
        assertThat(recorded).contains("ADMIN_API_KEY_REVOKE");

        assertThatThrownBy(() -> service.revoke(tenant, actor, issued.id())).isInstanceOf(ApiException.class)
                .hasMessageContaining("已吊销");
        assertThatThrownBy(() -> service.revoke(tenant, actor, UUID.randomUUID())).isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("duplicate key names conflict")
    void duplicateName() {
        service.issue(tenant, actor, "dup", null);
        assertThatThrownBy(() -> service.issue(tenant, actor, "dup", null)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("updateScope narrows the key and audits the change")
    void updateScopeSetsCapabilities() {
        AdminApiKeyService.Issued issued = service.issue(tenant, actor, "scoped", null);
        AdminApiKeyView updated = service.updateScope(tenant, actor, issued.id(),
                List.of(AdminApiKeyCapabilities.USAGE_READ));
        assertThat(updated.capabilities()).containsExactly(AdminApiKeyCapabilities.USAGE_READ);
        assertThat(repository.findByIdAndTenantId(issued.id(), tenant).orElseThrow().allows("usage:read")).isTrue();
        assertThat(repository.findByIdAndTenantId(issued.id(), tenant).orElseThrow().allows("alerts:write")).isFalse();
        assertThat(recorded).contains("ADMIN_API_KEY_SCOPE_UPDATE");

        // Clearing to null restores full access.
        service.updateScope(tenant, actor, issued.id(), null);
        assertThat(repository.findByIdAndTenantId(issued.id(), tenant).orElseThrow().allows("alerts:write")).isTrue();
    }

    @Test
    @DisplayName("updateScope rejects unknown or duplicate capabilities")
    void updateScopeRejectsInvalid() {
        AdminApiKeyService.Issued issued = service.issue(tenant, actor, "ops", null);
        assertThatThrownBy(() -> service.updateScope(tenant, actor, issued.id(), List.of("usage:read", "unknown:cap")))
                .isInstanceOf(ApiException.class).hasMessageContaining("能力组");
        assertThatThrownBy(() -> service.updateScope(tenant, actor, issued.id(), List.of("usage:read", "usage:read")))
                .isInstanceOf(ApiException.class).hasMessageContaining("能力组");
        assertThatThrownBy(() -> service.updateScope(tenant, actor, UUID.randomUUID(),
                List.of(AdminApiKeyCapabilities.USAGE_READ))).isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");
    }
}
