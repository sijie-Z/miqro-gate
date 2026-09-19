package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.crypto.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * External billing channel authentication (ADR-0010/0011): the rejection
 * envelopes must satisfy contract §2 — {@code application/problem+json} with
 * {@code type}/{@code title}/{@code status}/{@code code}/unique
 * {@code requestId} — because API clients correlate failures to server logs by
 * that token and never get a session cookie to fall back on.
 */
@DisplayName("ApiKeyAuthFilter (billing channel)")
class ApiKeyAuthFilterTest {

    private static final String BILLING_PATH = "/api/v1/billing/summary";

    private final ApiConsumerRepository repository = mock(ApiConsumerRepository.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final UserContext userContext = new UserContext();
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(repository, new ConsumerJwtVerifier(), userContext);

    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(repository.findByKeyDigest(any())).thenReturn(Optional.empty());
    }

    private ApiConsumer consumer(List<String> capabilities) {
        Instant now = Instant.now();
        return new ApiConsumer(UUID.randomUUID(), tenant, "platform", new byte[32], "abcd1234", "ACTIVE", null, null,
                null, 1L, now, now, capabilities, null);
    }

    @Test
    @DisplayName("a valid key with full access passes the filter")
    void fullAccessKeyPasses() throws Exception {
        when(repository.findByKeyDigest(any())).thenReturn(Optional.of(consumer(null)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("X-API-Key", "mqk_api_full-access");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("§2: scope denial and anonymous rejection both carry requestId (PH16)")
    void rejectionsCarryRequestId() throws Exception {
        // 403 CONSUMER_SCOPE_DENIED — a key narrowed away from the billing channel.
        when(repository.findByKeyDigest(any())).thenReturn(Optional.of(consumer(List.of("mcp:call"))));
        MockHttpServletRequest scoped = new MockHttpServletRequest();
        scoped.setRequestURI(BILLING_PATH);
        scoped.addHeader("X-API-Key", "mqk_api_mcp-only");
        scoped.addHeader("X-Request-Id", "ph16-billing-403");
        MockHttpServletResponse scopedResponse = new MockHttpServletResponse();
        filter.doFilter(scoped, scopedResponse, chain);
        assertThat(scopedResponse.getStatus()).isEqualTo(403);
        assertThat(scopedResponse.getContentAsString()).contains("\"code\":\"CONSUMER_SCOPE_DENIED\"")
                .contains("\"requestId\":\"ph16-billing-403\"");

        // 401 UNAUTHORIZED — no consumer credential and no portal session.
        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        anonymous.setRequestURI(BILLING_PATH);
        anonymous.addHeader("X-Request-Id", "ph16-billing-401");
        MockHttpServletResponse anonymousResponse = new MockHttpServletResponse();
        filter.doFilter(anonymous, anonymousResponse, chain);
        assertThat(anonymousResponse.getStatus()).isEqualTo(401);
        assertThat(anonymousResponse.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"requestId\":\"ph16-billing-401\"");
    }

    @Test
    @DisplayName("§2: a generated requestId is unique per rejection when the caller sends none (PH16)")
    void generatedRequestIdsAreUnique() throws Exception {
        when(repository.findByKeyDigest(any())).thenReturn(Optional.of(consumer(List.of("mcp:call"))));

        String first = rejectAndReadRequestId("ph16-first");
        String second = rejectAndReadRequestId("ph16-second");
        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    private String rejectAndReadRequestId(String apiKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getContentAsString());
        return body.path("requestId").asText();
    }
}
