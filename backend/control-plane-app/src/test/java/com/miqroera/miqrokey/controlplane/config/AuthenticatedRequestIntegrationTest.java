package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

/**
 * Regression for the real-container SessionFilter bug: with
 * HIGHEST_PRECEDENCE the filter wrote the request-scoped UserContext before
 * Spring Boot's RequestContextFilter bound the request to the thread, so
 * every authenticated request 500'd with ScopeNotActiveException. MockMvc
 * binds the request context itself and never exposed it — this test drives a
 * real server over HTTP with a real session cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Authenticated requests over a real HTTP port (session filter scope)")
class AuthenticatedRequestIntegrationTest {
    static final java.nio.file.Path SECRET_FILE;
    static final String SECRET = "test-bootstrap-secret-min-16chars";
    static {
        try {
            SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
            java.nio.file.Files.writeString(SECRET_FILE, SECRET);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> SECRET_FILE.toAbsolutePath().toString());
    }

    @Autowired
    TestRestTemplate rest;

    private String sessionCookie;
    private String csrf;

    @BeforeEach
    void setUp() {
        // Full bootstrap + password change over real HTTP.
        ResponseEntity<Map> boot = rest.postForEntity("/api/v1/auth/bootstrap",
                new BootstrapRequest(SECRET, "root", "Root Admin"), Map.class);
        String tempPw = (String) boot.getBody().get("temporaryPassword");
        for (String cookie : boot.getHeaders().get("Set-Cookie")) {
            if (cookie.startsWith("MIQROKEY_SESSION=")) {
                sessionCookie = cookie.split(";")[0];
            } else if (cookie.startsWith("MIQROKEY_CSRF=")) {
                csrf = cookie.split(";")[0].substring("MIQROKEY_CSRF=".length());
            }
        }

        HttpHeaders headers = sessionHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<PasswordChangeRequest> change = new HttpEntity<>(
                new PasswordChangeRequest(tempPw, "DrillPass2026!"), headers);
        rest.exchange("/api/v1/auth/password", HttpMethod.POST, change, Map.class);
    }

    @Test
    @DisplayName("session-authenticated GET /auth/me returns 200 over real HTTP")
    void authenticatedRequestSucceeds() {
        HttpEntity<Void> entity = new HttpEntity<>(sessionHeaders());
        ResponseEntity<Map> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET, entity, Map.class);
        assertThatStatus(me);
    }

    private HttpHeaders sessionHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        if (csrf != null) {
            headers.add("X-CSRF-Token", csrf);
        }
        return headers;
    }

    private static void assertThatStatus(ResponseEntity<Map> response) {
        if (response.getStatusCode().value() != 200) {
            throw new AssertionError("expected 200 but got " + response.getStatusCode() + ": " + response.getBody());
        }
    }
}
