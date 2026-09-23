package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import jakarta.servlet.FilterChain;
import tools.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @Test
    @DisplayName("every rejection carries the §2 requestId correlation token (PH16)")
    void rejectionsCarryRequestId() throws Exception {
        // 401 ADMIN_API_KEY_INVALID — no credential at all.
        request.setRequestURI(OPEN_PATH);
        request.addHeader("X-Request-Id", "ph16-open-401");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("ADMIN_API_KEY_INVALID")
                .contains("\"requestId\":\"ph16-open-401\"");

        // 403 ADMIN_API_SCOPE_DENIED — key outside its capability group.
        when(repository.findActiveByDigest(any()))
                .thenReturn(Optional.of(new AdminApiKey(keyId, tenant, "usage-only", new byte[32], "mqk_admin_", null,
                        null, null, Instant.now(), List.of(AdminApiKeyCapabilities.USAGE_READ))));
        MockHttpServletRequest scopeRequest = new MockHttpServletRequest();
        scopeRequest.setRequestURI("/api/v1/admin-api/alert-rules");
        scopeRequest.addHeader("Authorization", "Bearer mqk_admin_usage-only-token");
        scopeRequest.addHeader("X-Request-Id", "ph16-open-scope");
        MockHttpServletResponse scopeResponse = new MockHttpServletResponse();
        filter.doFilter(scopeRequest, scopeResponse, chain);
        assertThat(scopeResponse.getStatus()).isEqualTo(403);
        assertThat(scopeResponse.getContentAsString()).contains("ADMIN_API_SCOPE_DENIED")
                .contains("\"requestId\":\"ph16-open-scope\"");

        // 403 ADMIN_API_FORBIDDEN — portal session that is not SYSTEM_ADMIN.
        MockHttpServletRequest sessionRequest = new MockHttpServletRequest();
        sessionRequest.setRequestURI(OPEN_PATH);
        sessionRequest.addHeader("X-Request-Id", "ph16-open-403");
        MockHttpServletResponse sessionResponse = new MockHttpServletResponse();
        userContext.setUser(user(tenant, UserRole.USER));
        filter.doFilter(sessionRequest, sessionResponse, chain);
        assertThat(sessionResponse.getStatus()).isEqualTo(403);
        assertThat(sessionResponse.getContentAsString()).contains("ADMIN_API_FORBIDDEN")
                .contains("\"requestId\":\"ph16-open-403\"");
    }

    /**
     * #1382: {@code auditDenied} used to splice the decoded request path into the
     * change summary with a hand-rolled escaper that handled only {@code \} and
     * {@code "}. A percent-encoded control character survives Tomcat and
     * {@link RequestPaths#lookupPath} in decoded form, so the summary was not a
     * JSON document: the {@code ::jsonb} round-trip in
     * {@code AuditServiceImpl.record} threw, no audit row was written, and the
     * throw escaped this (non-transactional) filter before {@code forbiddenScope}
     * could run — the caller saw a 500 instead of the 403 the denial means.
     */
    @Test
    @DisplayName("a denied path carrying a control character is audited as valid JSON and still answered 403 (#1382)")
    void denialWithControlCharacterInPathIsAudited() throws Exception {
        UUID issuer = UUID.randomUUID();
        AdminApiKey scoped = new AdminApiKey(keyId, tenant, "usage-only", new byte[32], "mqk_admin_", issuer, null,
                null, Instant.now(), List.of(AdminApiKeyCapabilities.USAGE_READ));
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(scoped));

        MockHttpServletRequest denied = new MockHttpServletRequest();
        denied.setRequestURI("/api/v1/admin-api/export-tasks/%01");
        denied.addHeader("Authorization", "Bearer mqk_admin_usage-only-token");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, chain);

        assertThat(deniedResponse.getStatus()).as("the denial response must survive the audit write").isEqualTo(403);
        assertThat(deniedResponse.getContentAsString()).contains("ADMIN_API_SCOPE_DENIED");
        verify(chain, never()).doFilter(denied, deniedResponse);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(tenant), eq(issuer), eq("ADMIN_API_KEY_SCOPE_DENIED"), eq("ADMIN_API_KEY"),
                eq(keyId), summary.capture(), any());
        assertThat(summary.getValue()).as("the summary must be a JSON document PostgreSQL's jsonb parser accepts")
                .satisfies(
                        value -> assertThatCode(() -> new ObjectMapper().readTree(value)).doesNotThrowAnyException());
    }

    /**
     * #1408: {@code auditDenied} runs outside any transaction and before
     * {@code forbiddenScope}, so an exception from the audit write used to escape
     * the filter and replace the denial with a 500. Reproduced against a real
     * PostgreSQL by renaming {@code admin_audit_events}: the same over-privileged
     * GET that answered {@code 403 ADMIN_API_SCOPE_DENIED} with {@code audit Δ1}
     * answered {@code 500 Internal Server Error} with the table missing. A denied
     * caller that gets a 500 learns the audit path is down and, more importantly,
     * gets an answer that no longer says "denied".
     */
    @Test
    @DisplayName("a failing audit write cannot turn the scope denial into a 500 (#1408)")
    void auditFailureDoesNotReplaceDenial() throws Exception {
        UUID issuer = UUID.randomUUID();
        AdminApiKey scoped = new AdminApiKey(keyId, tenant, "usage-only", new byte[32], "mqk_admin_", issuer, null,
                null, Instant.now(), List.of(AdminApiKeyCapabilities.USAGE_READ));
        when(repository.findActiveByDigest(any())).thenReturn(Optional.of(scoped));
        doThrow(new IllegalStateException("admin_audit_events is unavailable")).when(auditService).record(any(), any(),
                any(), any(), any(), any(), any());

        MockHttpServletRequest denied = new MockHttpServletRequest();
        denied.setRequestURI("/api/v1/admin-api/alert-rules");
        denied.addHeader("Authorization", "Bearer mqk_admin_usage-only-token");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, chain);

        assertThat(deniedResponse.getStatus()).as("the denial is the decision; the audit write is best-effort")
                .isEqualTo(403);
        assertThat(deniedResponse.getContentAsString()).contains("ADMIN_API_SCOPE_DENIED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("rejections are UTF-8 encoded so the Chinese detail is not mangled (#630)")
    void rejectionsAreUtf8Encoded() throws Exception {
        request.setRequestURI(OPEN_PATH);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString()).contains("管理密钥缺失或无效");
    }
}
