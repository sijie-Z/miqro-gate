package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/context-registry} (CAA Spec v1.1 §8, #639) against real
 * PostgreSQL: the repo → project mapping is scoped to the presented key's
 * bindings, unknown keys stay a uniform 404, and missing auth is a 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "spring.flyway.enabled=false", "miqrokey.gateway.upstream.url=http://127.0.0.1:9",
        "miqrokey.gateway.upstream.allowed-cidrs=127.0.0.0/8, ::1/128"})
@Import(GatewayAuthTestConfig.class)
@AutoConfigureWebTestClient
@Tag("integration")
@DisplayName("Context registry integration tests (#639)")
class ContextRegistryIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
            .withPassword("miqrokey_test");

    private static final java.nio.file.Path ENC_KEY_FILE = keyFile("ctx-reg-enc.key");
    private static final java.nio.file.Path HMAC_KEY_FILE = keyFile("ctx-reg-hmac.key");

    static {
        POSTGRES.start();
    }

    private static java.nio.file.Path keyFile(String name) {
        try {
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            java.nio.file.Path file = java.nio.file.Files.createTempFile(name, ".key");
            java.nio.file.Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key));
            try {
                java.nio.file.Files.setPosixFilePermissions(file,
                        java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
            } catch (UnsupportedOperationException ignored) {
                // 0400: Linux CI enforces owner-read-only on crypto key files
                // (FileSecretProvider CRYPTO_CONFIG_008). Windows does not enforce it.
            }
            return file;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.persistence.url", POSTGRES::getJdbcUrl);
        registry.add("miqrokey.gateway.persistence.username", POSTGRES::getUsername);
        registry.add("miqrokey.gateway.persistence.password", POSTGRES::getPassword);
        registry.add("miqrokey.crypto.encryption.versions.v1", () -> ENC_KEY_FILE.toString());
        registry.add("miqrokey.crypto.hmac.versions.v1", () -> HMAC_KEY_FILE.toString());
    }

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void seedRegistry() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, 'ctx-registry', 'Ctx Registry', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource("id", GatewayTestKeys.TENANT_ID));
        insertProject(jdbc, GatewayTestKeys.PROJECT_ID, "CRA", "Registry Alpha");
        insertProject(jdbc, GatewayTestKeys.OTHER_PROJECT_ID, "CRB", "Registry Beta");
        map(jdbc, GatewayTestKeys.PROJECT_ID, "github.com/acme/alpha");
        map(jdbc, GatewayTestKeys.OTHER_PROJECT_ID, "github.com/acme/beta");
    }

    private static void insertProject(NamedParameterJdbcTemplate jdbc, UUID id, String code, String name) {
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, :code, :name, 'ACTIVE', :tag, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", GatewayTestKeys.TENANT_ID)
                .addValue("code", code).addValue("name", name).addValue("tag", code.toLowerCase()));
    }

    private static void map(NamedParameterJdbcTemplate jdbc, UUID projectId, String repoKey) {
        jdbc.update("""
                INSERT INTO project_repositories (id, tenant_id, project_id, repo_key)
                VALUES (:id, :tenantId, :projectId, :repoKey)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", GatewayTestKeys.TENANT_ID)
                .addValue("projectId", projectId).addValue("repoKey", repoKey));
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("a single-bound key sees only its own project's repo mapping")
    void singleBoundKeySeesOwnProjectOnly() {
        List<Map<String, Object>> entries = fetchEntries(GatewayTestKeys.DEFAULT_KEY.presented());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("repoKey")).isEqualTo("github.com/acme/alpha");
        assertThat(entries.get(0).get("projectId")).isEqualTo(GatewayTestKeys.PROJECT_ID.toString());
    }

    @Test
    @DisplayName("a multi-bound key sees the mappings of both its projects")
    void multiBoundKeySeesBothProjects() {
        List<Map<String, Object>> entries = fetchEntries(GatewayTestKeys.MULTI_BOUND_KEY.presented());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(e -> e.get("repoKey"))).containsExactlyInAnyOrder("github.com/acme/alpha",
                "github.com/acme/beta");
    }

    @Test
    @DisplayName("#641: a multi-bound key with a NON-matching suffix still reads the registry (identity-only)")
    void multiBoundKeyWithUnmatchedSuffix() {
        String presented = GatewayTestKeys.MULTI_BOUND_KEY.presented().replace("demo-multi", "zzz-none");
        List<Map<String, Object>> entries = fetchEntries(presented);
        assertThat(entries).hasSize(2);
    }

    @Test
    @DisplayName("unknown keys and missing auth keep the uniform failure semantics")
    void unknownKeyAndMissingAuth() {
        webTestClient.get().uri("/v1/context-registry")
                .header("Authorization",
                        "Bearer mqk_live_aaaaaaaaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .exchange().expectStatus().isNotFound();
        // GatewayAuthTestConfig installs DEFAULT_KEY as the client's default
        // Authorization; a blank credential expresses "missing" and must 401.
        webTestClient.get().uri("/v1/context-registry").header("Authorization", "").exchange().expectStatus()
                .isUnauthorized();
    }

    /**
     * #1400: {@code REGISTRY_TIMEOUT} bounds only how long the caller waits, so on
     * its own it leaves the blocking read running — and its scheduler lane checked
     * out — until PostgreSQL answers. The read now carries a statement bound
     * strictly inside it, so a stalled query is <em>aborted</em> rather than merely
     * stopped-waiting-for.
     *
     * <p>
     * The two behaviours are told apart by <em>when</em> the caller is answered.
     * Occupy every lane of the shared four-lane pool with reads that are genuinely
     * stuck behind a table lock, then require each caller to be answered before
     * {@code
     * REGISTRY_TIMEOUT}: only an abort can answer early, and an abort is exactly
     * what hands the lane back. Without the statement bound all four are parked in
     * the socket read and can only surface when the reactor timer expires at the
     * full timeout.
     * </p>
     */
    @Test
    @DisplayName("#1400: a table lock aborts the read at the statement bound instead of parking every lane")
    void statementBoundAbortsTheReadAndFreesTheLanes() throws Exception {
        int lanes = 4;
        long reactorBoundMillis = 10_000;
        long mustBeAnsweredBefore = reactorBoundMillis - 1_000;

        DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        List<Long> elapsedMillis;
        try (Connection held = admin.getConnection()) {
            held.setAutoCommit(false);
            try (Statement lock = held.createStatement()) {
                lock.execute("LOCK TABLE project_repositories IN ACCESS EXCLUSIVE MODE");
            }
            elapsedMillis = probeConcurrently(lanes, GatewayTestKeys.DEFAULT_KEY.presented());
            held.rollback();
        }

        assertThat(elapsedMillis).as("every lane must be answered at the statement bound, not the reactor bound")
                .allSatisfy(ms -> assertThat(ms).isLessThan(mustBeAnsweredBefore));

        // And the lane came back rather than lingering: the same read succeeds now.
        assertThat(fetchEntries(GatewayTestKeys.DEFAULT_KEY.presented())).hasSize(1);
    }

    /**
     * Fires {@code n} registry reads at once, asserting each is a {@code 503}, and
     * returns their wall times.
     */
    private List<Long> probeConcurrently(int n, String presented) throws Exception {
        // The autowired client gives up after 5s; both outcomes under test are slower
        // than
        // that, so the client bound would mask which server-side bound ended the read.
        WebTestClient client = webTestClient.mutate().responseTimeout(Duration.ofSeconds(60)).build();
        List<Callable<Long>> probes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            probes.add(() -> {
                long start = System.nanoTime();
                client.get().uri("/v1/context-registry").header("Authorization", "Bearer " + presented).exchange()
                        .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).expectBody().jsonPath("$.error.type")
                        .isEqualTo("context_registry_unavailable");
                return Duration.ofNanos(System.nanoTime() - start).toMillis();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Long> out = new ArrayList<>();
            for (Future<Long> probe : pool.invokeAll(probes)) {
                out.add(probe.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchEntries(String presented) {
        byte[] body = webTestClient.get().uri("/v1/context-registry").header("Authorization", "Bearer " + presented)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            Map<String, Object> parsed = new tools.jackson.databind.ObjectMapper().readValue(body, Map.class);
            return (List<Map<String, Object>>) parsed.get("entries");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
