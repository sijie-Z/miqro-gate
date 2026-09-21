package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Base for the rc.20 adversarial batch: real PostgreSQL (shared Testcontainers
 * container), the real filter chain, and — the point of this class — a database
 * that is reset to empty both before and after <em>every</em> test method.
 *
 * <p>
 * The container is shared process-wide, so a test that bootstraps an admin and
 * walks away ("already bootstrapped") turns the next class's logins into 401s.
 * Resetting in the base class rather than per subclass is what makes that
 * impossible to forget. The single {@code @DynamicPropertySource} method is
 * declared here on purpose: subclasses then share one Spring context (the
 * context cache keys on the property-source method identity), so the whole
 * batch starts one context instead of five.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
abstract class AbstractAdversarialIntegrationTest extends AbstractControlPlaneIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void adversarialProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdversarialTestSupport.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected NamedParameterJdbcTemplate jdbc;
    @Autowired
    protected PasswordHasher passwordHasher;

    @BeforeEach
    void resetDatabaseBefore() {
        AdversarialTestSupport.resetAll(jdbc);
    }

    @AfterEach
    void resetDatabaseAfter() {
        AdversarialTestSupport.resetAll(jdbc);
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie candidate : result.getResponse().getCookies()) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /** Parses a response body into a tree (Jackson 3, the migrated stack). */
    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode body(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> map(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }
}
