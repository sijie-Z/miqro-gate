package com.miqroera.miqrokey.gateway.proxy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.McpMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log-privacy contract of the MCP envelope parse path.
 *
 * <p>
 * The product promise is a <b>body-free log stream</b>
 * ({@code docs/deployment-and-operations.md}: "不记录正文和凭证";
 * {@code docs/ai-gateway-comparison.md}: "无正文日志（隐私测试锁死）"), and
 * {@code docs/coding-standards.md} §6 requires error logs to carry the gateway
 * request id and a <i>safe</i> error summary. The invalid-envelope branch is
 * the one place on the MCP data plane where the caller's bytes are handed to a
 * JSON parser, so it is exactly where a parser message can smuggle request-body
 * content into the log store.
 * </p>
 *
 * <p>
 * The in-repo precedent for the required behaviour is
 * {@code ContentFilterShadow}: "Only the exception type is logged - a message
 * could echo the body."
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("MCP invalid-envelope logs never carry the caller's body")
class McpEnvelopeLogPrivacyTest {

    private static final McpMockServer mockServer = new McpMockServer();

    /** Caller data planted where a JSON string was expected (unquoted argument). */
    private static final String CALLER_FRAGMENT = "CONFIDENTIAL_DRAFT_ACME_ACQUISITION";

    /**
     * A plausible mangled {@code tools/call}: the argument value lost its
     * surrounding quotes (templating/substitution bug on the client side), so
     * Jackson reports an unrecognized token whose text is the tool argument.
     */
    private static final String MALFORMED_ENVELOPE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo-tool\",\"arguments\":{\"prompt\": " + CALLER_FRAGMENT + "}}}";

    private static final Logger CONTROLLER_LOGGER = (Logger) LoggerFactory.getLogger(McpProxyController.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockServer::getBaseUrl);
    }

    @BeforeEach
    void attachAppender() {
        appender.start();
        CONTROLLER_LOGGER.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        CONTROLLER_LOGGER.detachAppender(appender);
        appender.stop();
        mockServer.reset();
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    @Test
    @DisplayName("a malformed body is rejected with 400 without echoing the body into the log")
    void malformedBodyIsRejectedWithoutEchoingIt() {
        byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_ALLOWED.presentedKey())
                .bodyValue(MALFORMED_ENVELOPE).exchange().expectStatus().isBadRequest().expectBody().returnResult()
                .getResponseBody();

        // The request really took the invalid-envelope branch.
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("invalid_jsonrpc");
        assertThat(mockServer.capturedRequests()).isEmpty();

        List<ILoggingEvent> lines = List.copyOf(appender.list);
        System.out.println("---- raw McpProxyController log lines for a malformed envelope ----");
        lines.forEach(line -> System.out.println("[" + line.getLevel() + "] " + line.getFormattedMessage()));
        System.out.println("------------------------------------------------------------------");

        assertThat(lines).isNotEmpty();

        // 1. No line may contain caller data. The caller's argument text is what
        // a Jackson parse failure quotes back ("Unrecognized token '...'").
        assertThat(lines).allSatisfy(line -> assertThat(line.getFormattedMessage())
                .as("log line must not carry request-body bytes: %s", line.getFormattedMessage())
                .doesNotContain(CALLER_FRAGMENT));

        // 2. The failure line must still be usable: coding-standards §6 requires
        // the gateway request id on error logs.
        assertThat(lines).filteredOn(line -> line.getFormattedMessage().contains("invalid envelope")).singleElement()
                .satisfies(line -> assertThat(line.getFormattedMessage()).contains("requestId="));
    }
}
