package com.miqroera.miqrokey.gateway.mcplog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.McpMockServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caller-controlled MCP envelope/header fields are written into {@code
 * mcp_access_log} columns that are narrower than the input:
 *
 * <ul>
 * <li>{@code rpc_method varchar(64)} — the JSON-RPC {@code method} string.</li>
 * <li>{@code tool_name varchar(128)} — the JSON-RPC {@code params.name}.</li>
 * <li>{@code session_id varchar(128)} — the client {@code Session-Id}
 * header.</li>
 * </ul>
 *
 * <p>
 * Nothing between the request and the INSERT bounds them. One oversize value
 * makes the whole multi-row {@code batchUpdate} fail; {@link McpAccessLogQueue}
 * requeues the entire batch and retries it on every flush, so the poison batch
 * is re-drained together with every later entry and nothing is ever persisted
 * again.
 * </p>
 *
 * <p>
 * Each test therefore does two things: it makes one oversize call, and then
 * makes one <em>perfectly clean</em> call from a different consumer. The
 * assertion is on the clean call's row, which any correct implementation must
 * persist regardless of how the oversize value is handled (rejected, truncated
 * or stored in a wider column). That keeps the test fix-agnostic: it fails
 * today because the whole pipeline is dead, and it will pass once the pipeline
 * survives a bad row — it does not prescribe which remedy is chosen.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "miqrokey.gateway.mcp-log.flush-interval-ms=100", "spring.flyway.enabled=true"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@Tag("integration")
@DisplayName("MCP access log: one oversize caller value poisons the whole batch")
class McpAccessLogOversizePoisonTest {

    private static final McpMockServer mockServer = new McpMockServer();

    private static final Path ENC_KEY_FILE = KeyFiles.write("mcplog-poison-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("mcplog-poison-hmac.key");

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName
                .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
                .withPassword("miqrokey_test");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockServer::getBaseUrl);
        registry.add("miqrokey.gateway.upstream.allowed-cidrs", () -> "127.0.0.0/8, ::1/128");
        registry.add("miqrokey.gateway.persistence.url", POSTGRES::getJdbcUrl);
        registry.add("miqrokey.gateway.persistence.username", POSTGRES::getUsername);
        registry.add("miqrokey.gateway.persistence.password", POSTGRES::getPassword);
        registry.add("miqrokey.crypto.encryption.versions.v1", () -> ENC_KEY_FILE.toString());
        registry.add("miqrokey.crypto.hmac.versions.v1", () -> HMAC_KEY_FILE.toString());
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> queueLog;

    @BeforeAll
    static void migrateAndSeedTenant() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("""
                        INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                        VALUES (:id, 'mcplog-poison', 'MCP Log Poison Test', 'ACTIVE', 0, now(), now())
                        """, new MapSqlParameterSource().addValue("id", GatewayTestKeys.TENANT_ID));
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    @BeforeEach
    void attachQueueLogCapture() {
        jdbc.update("DELETE FROM mcp_access_log", new MapSqlParameterSource());
        mockServer.reset();
        queueLog = new ListAppender<>();
        queueLog.start();
        ((Logger) LoggerFactory.getLogger(McpAccessLogQueue.class)).addAppender(queueLog);
    }

    @AfterEach
    void detachQueueLogCapture() {
        ((Logger) LoggerFactory.getLogger(McpAccessLogQueue.class)).detachAppender(queueLog);
        jdbc.update("DELETE FROM mcp_access_log", new MapSqlParameterSource());
        mockServer.reset();
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_access_log", new MapSqlParameterSource(),
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * The clean control call in every test: {@code MCP_OUTSIDER} has no server ACL
     * entry for the gated service, so the call is denied at the ACL with no
     * upstream contact, and its row is {@code SERVICE_DENIED} with {@code
     * rpc_method='tools/list'}. Matching on that pair isolates the clean row from
     * the poison row, whatever a fix stores for the poison row.
     */
    private void postCleanControlCall() {
        post(GatewayTestKeys.MCP_GATED_SERVICE, GatewayTestKeys.MCP_OUTSIDER,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
    }

    /** Rows written by {@link #postCleanControlCall()} — must be exactly 1. */
    private int cleanControlRows() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mcp_access_log WHERE status = 'SERVICE_DENIED' AND rpc_method = 'tools/list'",
                new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Waits a fixed multiple of the 100 ms flush interval, then reports how many
     * rows the clean control call managed to land — the number the assertion is on.
     */
    private int cleanRowsAfterSettling() {
        try {
            Thread.sleep(Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return cleanControlRows();
    }

    private List<ILoggingEvent> retryErrors() {
        return queueLog.list.stream().filter(event -> event.getFormattedMessage().contains("requeued for retry"))
                .toList();
    }

    /**
     * Raw (unredacted-by-us) evidence: the queue's own ERROR line plus the driver
     * exception it carried, the retry count and the surviving row count.
     */
    private void dumpEvidence(String label, int rows) {
        List<ILoggingEvent> retries = retryErrors();
        System.out.println(">>>BEGIN " + label);
        if (retries.isEmpty()) {
            System.out.println("(no queue retry ERROR line captured)");
        } else {
            ILoggingEvent first = retries.get(0);
            System.out.println(first.getFormattedMessage());
            IThrowableProxy thrown = first.getThrowableProxy();
            if (thrown != null) {
                System.out.println(thrown.getClassName() + ": " + thrown.getMessage());
                if (thrown.getCause() != null) {
                    System.out.println(
                            "  caused by " + thrown.getCause().getClassName() + ": " + thrown.getCause().getMessage());
                }
            }
        }
        System.out.println("---- queue retry ERROR lines: " + retries.size() + " ----");
        System.out.println("---- clean control rows landed: " + rows + " (expected 1) ----");
        System.out.println("---- mcp_access_log total rows: " + rowCount() + " ----");
        System.out.println("<<<END " + label);
    }

    private void post(String service, GatewayTestKeys.ConsumerFixture consumer, String body, String sessionId) {
        webTestClient.post().uri("/mcpservers/{service}/mcp", service).headers(h -> {
            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + consumer.presentedKey());
            if (sessionId != null) {
                h.set("Session-Id", sessionId);
            }
        }).bodyValue(body).exchange().expectStatus().is4xxClientError();
    }

    @Test
    @DisplayName("a 129-char Session-Id must not stop a clean caller's row from being persisted")
    void oversizedSessionIdDoesNotStopTheWholeAccessLog() {
        // Trigger: caller-controlled header, 1 char past session_id varchar(128).
        // An unparseable body keeps the call upstream-free; the access-log row is
        // still built (session id is captured before the envelope is read).
        post(GatewayTestKeys.MCP_OPEN_SERVICE, GatewayTestKeys.MCP_OUTSIDER, "this is not json", "s".repeat(129));

        // A second, perfectly clean caller action — its row must not be collateral.
        postCleanControlCall();

        int clean = cleanRowsAfterSettling();
        dumpEvidence("OVERSIZE Session-Id (129 > varchar(128))", clean);

        assertThat(clean).as("the clean caller's row is collateral damage of the oversize Session-Id").isEqualTo(1);
    }

    @Test
    @DisplayName("a 65-char JSON-RPC method must not stop a clean caller's row from being persisted")
    void oversizedRpcMethodDoesNotStopTheWholeAccessLog() {
        // Server-level ACL denial on the gated service: no upstream involved, and
        // the row is recorded with the caller's method string.
        post(GatewayTestKeys.MCP_GATED_SERVICE, GatewayTestKeys.MCP_OUTSIDER,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + "m".repeat(65) + "\"}", null);

        postCleanControlCall();

        int clean = cleanRowsAfterSettling();
        dumpEvidence("OVERSIZE JSON-RPC method (65 > varchar(64))", clean);

        assertThat(clean).as("the clean caller's row is collateral damage of the oversize method").isEqualTo(1);
    }

    @Test
    @DisplayName("a 129-char tool name must not stop a clean caller's row from being persisted")
    void oversizedToolNameDoesNotStopTheWholeAccessLog() {
        // Unknown tool on the gated service for a server-listed consumer: the row
        // is recorded as TOOL_UNAVAILABLE with the caller's tool name, no upstream.
        post(GatewayTestKeys.MCP_GATED_SERVICE, GatewayTestKeys.MCP_ALLOWED,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + "t".repeat(129)
                        + "\",\"arguments\":{}}}",
                null);

        postCleanControlCall();

        int clean = cleanRowsAfterSettling();
        dumpEvidence("OVERSIZE tool name (129 > varchar(128))", clean);

        assertThat(clean).as("the clean caller's row is collateral damage of the oversize tool name").isEqualTo(1);
    }

    private static final class KeyFiles {
        private static final SecureRandom RANDOM = new SecureRandom();

        static Path write(String name) {
            try {
                byte[] key = new byte[32];
                RANDOM.nextBytes(key);
                Path file = Files.createTempFile(name, ".key");
                Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key));
                try {
                    Files.setPosixFilePermissions(file,
                            java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem: permission check is skipped anyway.
                }
                return file;
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
