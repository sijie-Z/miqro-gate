package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.McpMockServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP data plane logs the caller-supplied JSON-RPC {@code method} string
 * (and, for {@code tools/call}, the tool name) on the {@code aigw.mcp.*} lines
 * that operators and log shippers treat as one line per call.
 *
 * <p>
 * A JSON string may carry a real line break ({@code "\n"} in the wire body
 * decodes to U+000A in the parsed value), so an authenticated caller must not
 * be able to turn one gateway log event into several physical lines. This is
 * the same hygiene {@code ApiKeyAuthFilter.forLog} applies to client-supplied
 * values in the control plane.
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
@DisplayName("MCP access log lines stay one line per call")
class McpLogInjectionContractTest {

    private static final McpMockServer mockServer = new McpMockServer();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockServer::getBaseUrl);
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    private static String bearer(GatewayTestKeys.ConsumerFixture consumer) {
        return "Bearer " + consumer.presentedKey();
    }

    @Test
    @DisplayName("a JSON-RPC method containing a line break must not forge a second log line")
    void methodWithLineBreakCannotForgeALogLine() {
        String forged = "tools/list\n2026-09-21T00:00:00.000Z ERROR 1 --- [ntLoopGroup-2] aigw.audit : FORGED-BY-PH66";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + forged.replace("\n", "\\n") + "\"}";

        List<String> callLines = captureCallLines(body, GatewayTestKeys.MCP_OUTSIDER);

        for (String line : callLines) {
            System.out.println("==== PH66 captured aigw.mcp.call event (one SLF4J event) ====");
            System.out.println(">>>BEGIN");
            System.out.println(line);
            System.out.println("<<<END");
            System.out.println(
                    "---- physical lines rendered from that single event: " + line.split("\n", -1).length + " ----");
            for (String physical : line.split("\n", -1)) {
                System.out.println("LINE| " + physical);
            }
        }

        assertThat(callLines).as("one authenticated MCP call must produce exactly one aigw.mcp.call event").hasSize(1);
        assertThat(callLines.get(0)).as("the caller-supplied method must not reach the log line verbatim")
                .doesNotContain("\n").doesNotContain("\r");
    }

    private List<String> captureCallLines(String body, GatewayTestKeys.ConsumerFixture consumer) {
        Logger logger = (Logger) LoggerFactory.getLogger(McpProxyController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(consumer)).bodyValue(body).exchange().expectStatus()
                    .isOk();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("aigw.mcp.call")).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
