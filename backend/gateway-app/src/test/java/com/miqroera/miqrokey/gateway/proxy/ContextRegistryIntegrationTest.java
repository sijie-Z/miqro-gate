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
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/context-registry} (CAA Spec v1.1 §8, #639) against real
 * PostgreSQL: the repo → project mapping is scoped to the presented key's
 * bindings, unknown keys stay a uniform 404, and missing auth is a 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchEntries(String presented) {
        byte[] body = webTestClient.get().uri("/v1/context-registry").header("Authorization", "Bearer " + presented)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            return (List<Map<String, Object>>) parsed.get("entries");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
