package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin unit-price APIs against real PostgreSQL (G7.2): snapshot create/list,
 * permission boundary and validation errors. Prices are global (not
 * tenant-scoped) but the endpoints stay SYSTEM_ADMIN-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin price API integration tests (PostgreSQL)")
class AdminPriceApiIntegrationTest {
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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("anonymous requests are rejected")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/prices")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("create then list returns the latest snapshot per triple")
    void createAndList() throws Exception {
        fx.insertProviderAndProduct();
        mockMvc.perform(
                post("/api/v1/admin/prices").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(),
                                "modelId", "test-model", "tokenType", "INPUT", "currency", "CNY", "unitPrice", "2.0000",
                                "source", "MANUAL"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.modelId").value("test-model"))
                .andExpect(jsonPath("$.tokenType").value("INPUT")).andExpect(jsonPath("$.currency").value("CNY"));

        mockMvc.perform(get("/api/v1/admin/prices").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelId").value("test-model"))
                .andExpect(jsonPath("$[0].unitPrice").value(2.0000));
    }

    @Test
    @DisplayName("a newer snapshot for the same triple supersedes the older one")
    void latestWins() throws Exception {
        fx.insertProviderAndProduct();
        for (String unitPrice : List.of("1.0000", "2.0000")) {
            mockMvc.perform(
                    post("/api/v1/admin/prices").contentType(MediaType.APPLICATION_JSON)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                            .content(objectMapper.writeValueAsString(Map.of("providerProductId",
                                    fx.productId.toString(), "modelId", "test-model", "tokenType", "INPUT", "currency",
                                    "CNY", "unitPrice", unitPrice, "source", "MANUAL"))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/admin/prices").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unitPrice").value(2.0000));
    }

    @Test
    @DisplayName("unknown product is rejected with 404")
    void unknownProduct() throws Exception {
        mockMvc.perform(post("/api/v1/admin/prices").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper
                        .writeValueAsString(Map.of("providerProductId", UUID.randomUUID().toString(), "modelId", "m",
                                "tokenType", "INPUT", "currency", "CNY", "unitPrice", "1", "source", "MANUAL"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("unknown token type is rejected with 400")
    void unknownTokenType() throws Exception {
        fx.insertProviderAndProduct();
        mockMvc.perform(post("/api/v1/admin/prices").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("providerProductId", fx.productId.toString(), "modelId",
                        "m", "tokenType", "REASONING", "currency", "CNY", "unitPrice", "1", "source", "MANUAL"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return result.getResponse().getCookies() != null
                ? java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                        .findFirst().orElse(null)
                : null;
    }

    private final class Fixture {
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("price_snapshot", "provider_products", "providers", "admin_audit_events",
                    "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertProviderAndProduct() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status,
                         balance_authority, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}',
                            'VERIFIED', 'OFFICIAL_API', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
        }
    }
}
