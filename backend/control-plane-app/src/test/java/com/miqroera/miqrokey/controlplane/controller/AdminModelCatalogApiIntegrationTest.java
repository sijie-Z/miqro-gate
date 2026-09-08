package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual model-catalog maintenance (F18, V34): admin manual entry as the
 * probe-failure fallback, MANUAL/OFFICIAL provenance, duplicate handling and
 * the manual-only deletion boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin model catalog API integration tests (PostgreSQL)")
class AdminModelCatalogApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID productId;

    @BeforeEach
    void setUp() throws Exception {
        cleanCatalog();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        // Signed-catalog products are seeded at startup; reuse one for the test.
        productId = jdbc.queryForObject(
                "SELECT id FROM provider_products WHERE model_catalog_strategy = 'OFFICIAL_API' LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        assertThat(productId).isNotNull();
    }

    @AfterEach
    void tearDown() {
        cleanCatalog();
    }

    private void cleanCatalog() {
        try {
            jdbc.update("DELETE FROM model_catalog", new MapSqlParameterSource());
        } catch (Exception ignored) {
            // canonical FK set ordering
        }
        for (String table : new String[]{"user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private void insertOfficial(String modelId) {
        jdbc.update("""
                INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version, source,
                    created_at, updated_at)
                VALUES (:id, :productId, :modelId, :displayName, 'ACTIVE', 0, 'OFFICIAL', now(), now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                .addValue("modelId", modelId).addValue("displayName", modelId));
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

    @Test
    @DisplayName("manual entry lists as MANUAL and survives duplicate rejection")
    void manualEntryLifecycle() throws Exception {
        insertOfficial("deepseek-chat");
        mockMvc.perform(post("/api/v1/admin/models").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", productId.toString(), "modelId",
                        "manual-fallback-1", "displayName", "人工录入兜底模型", "contextWindow", 1000))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.modelId").value("manual-fallback-1"))
                .andExpect(jsonPath("$.contextWindow").value(1000));

        // Duplicate (same product + model id) is rejected.
        mockMvc.perform(post("/api/v1/admin/models").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("providerProductId", productId.toString(), "modelId", "manual-fallback-1"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODEL_ALREADY_IN_CATALOG"));

        // List exposes both provenance classes; filter narrows to MANUAL.
        mockMvc.perform(
                get("/api/v1/admin/models").param("providerProductId", productId.toString()).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/admin/models").param("source", "MANUAL").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].modelId").value("manual-fallback-1"));
    }

    @Test
    @DisplayName("only MANUAL rows can be deleted by hand; unknown product is rejected")
    void deletionBoundary() throws Exception {
        insertOfficial("deepseek-chat");
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/models").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("providerProductId", productId.toString(), "modelId", "manual-fallback-2"))))
                .andExpect(status().isCreated()).andReturn();
        String rowId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Deleting an OFFICIAL row is refused.
        UUID officialId = jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_id = 'deepseek-chat'",
                new MapSqlParameterSource(), UUID.class);
        mockMvc.perform(delete("/api/v1/admin/models/" + officialId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_MANUAL"));

        // Manual row deletion succeeds and the row disappears.
        mockMvc.perform(delete("/api/v1/admin/models/" + rowId).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token",
                csrfToken)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/models").param("source", "MANUAL").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Unknown product on add is a 404; unknown row on delete is a 404.
        mockMvc.perform(post("/api/v1/admin/models").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("providerProductId", UUID.randomUUID().toString(), "modelId", "whatever"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/admin/models/" + UUID.randomUUID()).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("model endpoints require a portal session")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/models")).andExpect(status().isUnauthorized());
    }
}
