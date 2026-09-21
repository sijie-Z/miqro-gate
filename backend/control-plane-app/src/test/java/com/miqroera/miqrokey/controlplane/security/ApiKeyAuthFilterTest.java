package com.miqroera.miqrokey.controlplane.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(repository.findByKeyDigest(any())).thenReturn(Optional.empty());
        appender.start();
        ((Logger) LoggerFactory.getLogger(ApiKeyAuthFilter.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ApiKeyAuthFilter.class)).detachAppender(appender);
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
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

    @Test
    @DisplayName("§2: a control char in the client-controlled X-Request-Id cannot corrupt the envelope (PH16)")
    void reflectedRequestIdStaysParseableJson() throws Exception {
        when(repository.findByKeyDigest(any())).thenReturn(Optional.of(consumer(List.of("mcp:call"))));

        // A literal TAB survives Tomcat's header parser and reaches getHeader()
        // verbatim, so escaping only '"' and '\' would splice a raw control
        // character into the JSON string and make the whole envelope unparseable.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("X-API-Key", "mqk_api_tab-probe");
        request.addHeader("X-Request-Id", "ph16-tab-\t-end");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("requestId").asText()).isEqualTo("ph16-tab-\t-end");
    }

    @Test
    @DisplayName("PH45: a rejection is traced server-side by requestId, never by the credential")
    void rejectionsAreLoggedWithoutTheSecret() throws Exception {
        // 401 — the presented key matches no consumer.
        MockHttpServletRequest unknown = new MockHttpServletRequest();
        unknown.setRequestURI(BILLING_PATH);
        unknown.addHeader("X-API-Key", "mqk_api_ph45-secret-material");
        unknown.addHeader("X-Request-Id", "ph45-c2-unknown");
        MockHttpServletResponse unknownResponse = new MockHttpServletResponse();
        filter.doFilter(unknown, unknownResponse, chain);

        assertThat(unknownResponse.getStatus()).isEqualTo(401);
        assertThat(messages(Level.WARN))
                .anySatisfy(line -> assertThat(line).contains("ph45-c2-unknown", "UNKNOWN_API_KEY"));
        // Axis 5: the rejected material itself must not reach the log.
        assertThat(messages(Level.WARN)).allSatisfy(line -> assertThat(line).doesNotContain("secret-material"));

        // 403 — a known consumer whose key lacks billing:read.
        UUID consumerId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.findByKeyDigest(any())).thenReturn(Optional.of(new ApiConsumer(consumerId, tenant, "platform",
                new byte[32], "abcd1234", "ACTIVE", null, null, null, 1L, now, now, List.of("mcp:call"), null)));
        MockHttpServletRequest scoped = new MockHttpServletRequest();
        scoped.setRequestURI(BILLING_PATH);
        scoped.addHeader("X-API-Key", "mqk_api_ph45-scope-probe");
        scoped.addHeader("X-Request-Id", "ph45-c2-scope");
        MockHttpServletResponse scopedResponse = new MockHttpServletResponse();
        filter.doFilter(scoped, scopedResponse, chain);

        assertThat(scopedResponse.getStatus()).isEqualTo(403);
        assertThat(messages(Level.WARN)).anySatisfy(
                line -> assertThat(line).contains("ph45-c2-scope", consumerId.toString(), tenant.toString()));
    }

    @Test
    @DisplayName("PH45: an unverified JWT sub cannot forge extra log lines")
    void unverifiedJwtSubjectIsFlattened() throws Exception {
        // extractSubject() runs before the signature check, so the claim is
        // attacker-controlled text by the time it reaches the log line.
        String claims = "{\"sub\":\"acme\\n2026-09-20 00:00:00 ERROR forged line\"}";
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        request.addHeader("X-Request-Id", "ph45-c2-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(messages(Level.WARN))
                .anySatisfy(line -> assertThat(line).contains("ph45-c2-jwt", "UNKNOWN_CONSUMER", "acme?2026-09-20"));
        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("\n"));
    }

    @Test
    @DisplayName("PH45: the client-controlled X-Request-Id is bounded and cannot forge log structure")
    void clientSuppliedRequestIdIsSanitizedInTheLog() throws Exception {
        // Tomcat rejects CR/LF, so a caller forges with characters a header may
        // legally carry: a comma + bracket closes the [requestId=…, reason=…]
        // structure early and the rest reads as a second event, and 4 KB of padding
        // turns every rejection into a log-amplification vector. The envelope still
        // echoes the token verbatim (contract §2) — only the log line is sanitized.
        String forged = "x, reason=NO_CREDENTIAL] Billing channel rejected [requestId=y" + "A".repeat(4000);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("X-API-Key", "mqk_api_ph45-long-id");
        request.addHeader("X-Request-Id", forged);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(messages(Level.WARN)).isNotEmpty();
        assertThat(messages(Level.WARN)).allSatisfy(
                line -> assertThat(line).doesNotContain("reason=NO_CREDENTIAL] Billing channel rejected ["));
        assertThat(messages(Level.WARN)).allSatisfy(line -> assertThat(line.length()).isLessThan(300));
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("requestId").asText()).isEqualTo(forged);
    }

    private String rejectAndReadRequestId(String apiKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(BILLING_PATH);
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        return body.path("requestId").asText();
    }
}
