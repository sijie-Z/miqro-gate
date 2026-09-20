package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #451: a reconciliation run lives only in this process — after a restart every
 * PENDING/RUNNING row is a zombie that the idempotent re-upload path would
 * return forever. Startup recovery must mark them FAILED so the same bill can
 * be re-run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Reconciliation interrupted-run recovery (#451)")
class ReconciliationInterruptedRunRecoveryIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    ReconciliationService reconciliationService;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;
    private final UUID providerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("reconciliation_rows", "reconciliation_reports", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Shared-container residue ordering.
            }
        }
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "recon_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:id, :slug, 'Recon Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", providerId).addValue("slug", "recon-" + providerId));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:productId, :providerId, 'recon-product', 'Recon Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.recon.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("reconciliation_rows", "reconciliation_reports", "provider_products",
                "providers")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    @DisplayName("an interrupted PENDING run is marked FAILED and can be re-run (#451)")
    void interruptedRunIsRecoverable() throws Exception {
        byte[] payload = "provider,model,amount\nrecon,model-a,1.00\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        UUID zombie = UUID.randomUUID();
        Instant from = Instant.now().minusSeconds(86_400);
        Instant to = Instant.now();
        jdbc.update("""
                INSERT INTO reconciliation_reports (id, tenant_id, created_by, provider_code, currency, window_from,
                    window_to, status, upload_sha256, upload_bytes, created_at)
                VALUES (:id, :tenantId, :createdBy, 'recon-product', 'USD', :from, :to, 'PENDING', :sha, :bytes, now())
                """,
                new MapSqlParameterSource("id", zombie).addValue("tenantId", TENANT_ID)
                        .addValue("createdBy", adminUserId).addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to)).addValue("sha", sha).addValue("bytes", payload.length));

        // The startup hook, invoked directly as ApplicationReadyEvent would.
        try {
            ReconciliationService.class.getMethod("recoverInterruptedRuns").invoke(reconciliationService);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("startup recovery entry point missing (#451)", e);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM reconciliation_reports WHERE id = :id",
                new MapSqlParameterSource("id", zombie), String.class)).isEqualTo("FAILED");

        // The same upload must now create a NEW run instead of returning the zombie.
        Map<String, Object> created = reconciliationService.create(TENANT_ID,
                AuditContext.human(adminUserId, "recovery-it"), "recon-product", "USD", from, to, payload);
        assertThat(created.get("id")).isNotEqualTo(zombie.toString());
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : r.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
