package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filter-level rejection envelopes over a real Tomcat port (PH16).
 *
 * <p>
 * Why a real container: two properties of these envelopes are invisible under
 * {@code MockHttpServletResponse}. (1) Mock responses default to UTF-8, so the
 * writer mangles nothing even when the writer never sets a charset — Tomcat
 * falls back to ISO-8859-1 for a {@code Content-Type} without charset, turning
 * the Chinese {@code detail} into {@code ?} (the #630 class of bug). The app
 * declares no {@code server.servlet.encoding.*} property, so
 * {@code force-response} stays at its default {@code false} and nothing
 * normalises the charset behind these raw writers. (2) The requestId
 * correlation token is the only handle an external caller has — a machine
 * client gets no session cookie.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Filter rejections over a real HTTP port (contract §2 envelope)")
class RealPortProblemResponseIntegrationTest extends AbstractControlPlaneIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("open admin surface 401 carries code, requestId and an intact UTF-8 detail")
    void openAdminRejectionEnvelope() {
        ResponseEntity<byte[]> response = rest.exchange("/api/v1/admin-api/usage/summary", HttpMethod.GET, null,
                byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).contains("application/problem+json");

        // Decode the wire bytes as UTF-8 ourselves: TestRestTemplate would happily
        // decode ISO-8859-1 bytes as ISO-8859-1 and hide the mangling.
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"type\":\"about:blank\"");
        assertThat(body).contains("\"status\":401");
        assertThat(body).contains("\"code\":\"ADMIN_API_KEY_INVALID\"");
        assertThat(body).contains("\"detail\":\"管理密钥缺失或无效\"");
        assertThat(body).containsPattern("\"requestId\":\"[^\"]+\"");
    }

    @Test
    @DisplayName("billing channel 401 carries code, requestId and an intact UTF-8 detail")
    void billingRejectionEnvelope() {
        ResponseEntity<byte[]> response = rest.exchange("/api/v1/billing/summary", HttpMethod.GET, null, byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(body).containsPattern("\"requestId\":\"[^\"]+\"");
    }
}
