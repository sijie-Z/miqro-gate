package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F15 end-to-end: with gateway persistence enabled, every MCP proxy request
 * with a resolvable identity writes exactly one {@code mcp_access_log} row
 * (async batch) carrying the fixture identity chain, the envelope metadata and
 * the terminal outcome. Pre-resolution failures (401 unknown key / 404 unknown
 * service) must NOT produce rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "miqrokey.gateway.mcp-log.flush-interval-ms=100", "spring.flyway.enabled=true"})
@Import(GatewayAuthTestConfig.class)
@Tag("integration")
@DisplayName("MCP access log end-to-end (gateway + PostgreSQL)")
class McpAccessLogIntegrationTest {

    private static final McpMockServer mockServer = new McpMockServer();

    private static final Path ENC_KEY_FILE = KeyFiles.write("mcplog-test-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("mcplog-test-hmac.key");

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

    @BeforeAll
    static void migrateAndSeedTenant() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("""
                        INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                        VALUES (:id, 'mcplog-test', 'MCP Log Test', 'ACTIVE', 0, now(), now())
                        """, new MapSqlParameterSource().addValue("id", GatewayTestKeys.TENANT_ID));
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    @BeforeEach
    void cleanRowsAndMock() {
        jdbc.update("DELETE FROM mcp_access_log", new MapSqlParameterSource());
        mockServer.reset();
    }

    @AfterEach
    void cleanRowsAfterTest() {
        jdbc.update("DELETE FROM mcp_access_log", new MapSqlParameterSource());
        mockServer.reset();
    }

    private static String envelope(String method, String toolName) {
        if (toolName == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"name\":\"" + toolName
                + "\",\"arguments\":{}}}";
    }

    private void post(String service, GatewayTestKeys.ConsumerFixture consumer, String body) {
        webTestClient.post().uri("/mcpservers/{service}/mcp", service)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + consumer.presentedKey())).bodyValue(body)
                .exchange().expectStatus().isOk();
    }

    private void awaitRowCount(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_access_log", new MapSqlParameterSource(),
                    Integer.class);
            if (count == expected) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling mcp_access_log", e);
            }
        }
        throw new AssertionError("timed out waiting for " + expected + " mcp_access_log row(s), saw " + jdbc
                .queryForObject("SELECT COUNT(*) FROM mcp_access_log", new MapSqlParameterSource(), Integer.class));
    }

    private Map<String, Object> singleRow() {
        return jdbc.queryForMap("SELECT service_name, consumer_name, rpc_method, tool_name, status, http_status,"
                + " tenant_id, gateway_request_id FROM mcp_access_log", new MapSqlParameterSource());
    }

    @Test
    @DisplayName("a forwarded tools/call writes one FORWARDED row with the upstream status")
    void forwardedCallRow() {
        mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}", 200);
        post(GatewayTestKeys.MCP_GATED_SERVICE, GatewayTestKeys.MCP_ALLOWED,
                envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED));
        awaitRowCount(1);

        Map<String, Object> row = singleRow();
        assertThat(row.get("tenant_id")).isEqualTo(GatewayTestKeys.TENANT_ID);
        assertThat(row.get("service_name")).isEqualTo(GatewayTestKeys.MCP_GATED_SERVICE);
        assertThat(row.get("consumer_name")).isEqualTo(GatewayTestKeys.MCP_ALLOWED.name());
        assertThat(row.get("rpc_method")).isEqualTo("tools/call");
        assertThat(row.get("tool_name")).isEqualTo(GatewayTestKeys.MCP_TOOL_SHARED);
        assertThat(row.get("status")).isEqualTo("FORWARDED");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("gateway_request_id")).isNotNull();
    }

    @Test
    @DisplayName("a forwarded call whose upstream answers 503 keeps the upstream status")
    void forwardedUpstreamErrorRow() {
        mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001}}", 503);
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_OUTSIDER.presentedKey()))
                .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isEqualTo(503);
        awaitRowCount(1);

        Map<String, Object> row = singleRow();
        assertThat(row.get("status")).isEqualTo("FORWARDED");
        assertThat(row.get("http_status")).isEqualTo(503);
    }

    @Test
    @DisplayName("ACL and tool denials write outcome rows with the client-facing status")
    void denialRows() {
        // Server-level denial on the gated service.
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_OUTSIDER.presentedKey()))
                .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isForbidden();
        // Tool override excludes a server-listed consumer.
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION,
                        "Bearer " + GatewayTestKeys.MCP_SERVER_ONLY.presentedKey()))
                .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).exchange().expectStatus()
                .isForbidden();
        // Disabled tool.
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_ALLOWED.presentedKey()))
                .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_QUIET)).exchange().expectStatus()
                .isForbidden();
        awaitRowCount(3);

        List<String> statuses = jdbc.queryForList("SELECT status FROM mcp_access_log", new MapSqlParameterSource(),
                String.class);
        assertThat(statuses).containsExactlyInAnyOrder("SERVICE_DENIED", "TOOL_DENIED", "TOOL_UNAVAILABLE");
        List<Integer> httpStatuses = jdbc.queryForList("SELECT http_status FROM mcp_access_log",
                new MapSqlParameterSource(), Integer.class);
        assertThat(httpStatuses).containsExactlyInAnyOrder(403, 403, 403);
    }

    @Test
    @DisplayName("an unparseable envelope writes an INVALID_ENVELOPE row")
    void invalidEnvelopeRow() {
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_OUTSIDER.presentedKey()))
                .bodyValue("this is not json").exchange().expectStatus().isBadRequest();
        awaitRowCount(1);
        assertThat(singleRow().get("status")).isEqualTo("INVALID_ENVELOPE");
        assertThat(singleRow().get("http_status")).isEqualTo(400);
    }

    @Test
    @DisplayName("pre-resolution failures (401/404) never produce rows")
    void preResolutionFailuresLeaveNoRows() {
        webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer mqk_api_drill_ghost_secret"))
                .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/mcpservers/{service}/mcp", "no-such-service")
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.MCP_ALLOWED.presentedKey()))
                .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isNotFound();

        // Wait well past the flush interval, then assert nothing ever landed.
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM mcp_access_log", new MapSqlParameterSource(), Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("duplicate flush retries never double-write one request")
    void writerIsIdempotent() {
        McpAccessLogEntry entry = new McpAccessLogEntry(UUID.randomUUID(), GatewayTestKeys.TENANT_ID, UUID.randomUUID(),
                GatewayTestKeys.MCP_OPEN_SERVICE, UUID.randomUUID(), GatewayTestKeys.MCP_OUTSIDER.name(), "tools/list",
                null, McpAccessStatus.FORWARDED, 200, UUID.randomUUID().toString(), java.time.Instant.now());
        new PostgresMcpAccessLogWriter(jdbc).writeBatch(new ArrayList<>(List.of(entry)));
        new PostgresMcpAccessLogWriter(jdbc).writeBatch(List.of(entry));
        assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM mcp_access_log", new MapSqlParameterSource(), Integer.class))
                .isEqualTo(1);
        jdbc.update("DELETE FROM mcp_access_log", new MapSqlParameterSource());
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
