package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Open admin surface authentication (ADR-0015): bearer machine keys vs portal
 * sessions, the SYSTEM_ADMIN-only session rule (batch 1b), digest lookup and
 * the tenant attribute contract every open controller reads.
 */
@DisplayName("AdminApiKeyAuthFilter (ADR-0015)")
class AdminApiKeyAuthFilterTest {

    private static final String OPEN_PATH = "/api/v1/admin-api/usage/summary";

    private final AdminApiKeyRepository repository = mock(AdminApiKeyRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final UserContext userContext = new UserContext();
    private final AdminApiKeyAuthFilter filter = new AdminApiKeyAuthFilter(repository, userContext, auditService);

    private final UUID tenant = UUID.randomUUID();
    private final UUID keyId = UUID.randomUUID();

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private static AdminApiKey activeKey(UUID id, UUID tenantId, String name, byte[] digest) {
        return new AdminApiKey(id, tenantId, name, digest, "mqk_admin_", null, null, null, Instant.now(), null);
    }

    @Test
    @DisplayName("a scoped key may reach its capability paths but is denied elsewhere (F60 batch 3)")
    void scopedKeyEnforcement() throws Exception {
        AdminApiKey scoped = new AdminApiKey(keyId, tenant, "usage-only", new byte[32], "mqk_admin_", null, null, null,
                Instant.now(), List.of(AdminApiKeyCapabilities.USAGE_READ));
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(scoped));

        MockHttpServletRequest allowed = new MockHttpServletRequest();
        allowed.setRequestURI("/api/v1/admin-api/usage/summary?groupBy=project");
        allowed.addHeader("Authorization", "Bearer mqk_admin_usage-only-token");
        filter.doFilter(allowed, response, chain);
        verify(chain).doFilter(allowed, response);

        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        MockHttpServletRequest denied = new MockHttpServletRequest();
        denied.setRequestURI("/api/v1/admin-api/alert-rules");
        denied.addHeader("Authorization", "Bearer mqk_admin_usage-only-token");
        filter.doFilter(denied, deniedResponse, chain);
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
        assertThat(deniedResponse.getContentAsString()).contains("ADMIN_API_SCOPE_DENIED");
        verify(chain, never()).doFilter(denied, deniedResponse);
    }

    @Test
    @DisplayName("an unscoped (full-access) key is never scope-denied")
    void unscopedKeyNotDenied() throws Exception {
        AdminApiKey full = new AdminApiKey(keyId, tenant, "full", new byte[32], "mqk_admin_", null, null, null,
                Instant.now(), null);
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(full));

        for (String path : List.of("/api/v1/admin-api/usage/summary", "/api/v1/admin-api/alert-rules",
                "/api/v1/admin-api/virtual-keys?userId=1", "/api/v1/admin-api/webhooks")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI(path);
            request.addHeader("Authorization", "Bearer mqk_admin_full-token");
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
    }

    private static User user(UUID tenantId, UserRole role) {
        return new User(UUID.randomUUID(), tenantId, "whoever", "Whoever", new byte[0], role, UserStatus.ACTIVE, false,
                0, null, null, 0, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("paths outside the open surface pass through untouched")
    void nonOpenPathPassesThrough() throws Exception {
        request.setRequestURI("/api/v1/usage/summary");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("missing or unknown credentials are rejected with 401")
    void missingOrUnknownCredentials() throws Exception {
        request.setRequestURI(OPEN_PATH);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("ADMIN_API_KEY_INVALID");

        MockHttpServletResponse second = new MockHttpServletResponse();
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.setRequestURI(OPEN_PATH);
        secondRequest.addHeader("Authorization", "Bearer mqk_admin_unknown-token");
        filter.doFilter(secondRequest, second, chain);
        assertThat(second.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a valid machine key authenticates, stores only its digest lookup and seeds tenant attrs")
    void validKeyAuthenticates() throws Exception {
        String token = "mqk_admin_" + UUID.randomUUID().toString().substring(0, 12);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(activeKey(keyId, tenant, "ops", digest)));

        request.setRequestURI(OPEN_PATH);
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        ArgumentCaptor<byte[]> digestCapture = ArgumentCaptor.forClass(byte[].class);
        verify(repository).findActiveByDigest(digestCapture.capture());
        assertThat(HexFormat.of().formatHex(digestCapture.getValue())).isEqualTo(HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))));
        assertThat(request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR)).isEqualTo(tenant);
        assertThat(request.getAttribute(AdminApiKeyAuthFilter.KEY_ATTR)).isEqualTo(keyId);
        assertThat(request.getAttribute(AdminApiKeyAuthFilter.NAME_ATTR)).isEqualTo("ops");
    }

    @Test
    @DisplayName("revoked or expired keys fail the active check before any controller runs")
    void revokedOrExpiredKeyRejected() throws Exception {
        String revokedToken = "mqk_admin_revoked-token";
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(new AdminApiKey(keyId, tenant, "revoked",
                new byte[32], "mqk_admin_", null, null, Instant.now(), Instant.now(), null)));
        request.setRequestURI(OPEN_PATH);
        request.addHeader("Authorization", "Bearer " + revokedToken);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);

        MockHttpServletResponse second = new MockHttpServletResponse();
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        when(repository.findActiveByDigest(any()))
                .thenReturn(Optional.of(new AdminApiKey(keyId, tenant, "expired", new byte[32], "mqk_admin_", null,
                        Instant.now().minus(1, ChronoUnit.MINUTES), null, Instant.now(), null)));
        secondRequest.setRequestURI(OPEN_PATH);
        secondRequest.addHeader("Authorization", "Bearer mqk_admin_expired-token");
        filter.doFilter(secondRequest, second, chain);
        assertThat(second.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a SYSTEM_ADMIN session passes and seeds its own tenant (batch 1b)")
    void systemAdminSessionPasses() throws Exception {
        userContext.setUser(user(tenant, UserRole.SYSTEM_ADMIN));
        request.setRequestURI(OPEN_PATH);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR)).isEqualTo(tenant);
        assertThat(request.getAttribute(AdminApiKeyAuthFilter.KEY_ATTR)).isNull();
    }

    @Test
    @DisplayName("a non-SYSTEM_ADMIN session is forbidden on the open surface (batch 1b)")
    void regularUserSessionForbidden() throws Exception {
        userContext.setUser(user(tenant, UserRole.USER));
        request.setRequestURI(OPEN_PATH);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_API_FORBIDDEN");
        verify(chain, never()).doFilter(any(), any());
    }
}
