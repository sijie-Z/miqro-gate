package com.miqroera.miqrokey.gateway;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.route.RouteSnapshotHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end route refresh event test: with persistence enabled, the gateway
 * boots a {@code RouteSnapshotRefreshListener} on a dedicated connection; a
 * control-plane style {@code pg_notify} on the shared channel triggers a
 * snapshot reload WITHOUT waiting for the 30s scheduled refresh.
 *
 * <p>
 * The scheduled refresh is disabled for the test (1h interval) so any version
 * bump can only come from the NOTIFY listener.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "spring.flyway.enabled=true"})
@Tag("integration")
@DisplayName("Route snapshot refresh via PostgreSQL NOTIFY")
class RouteSnapshotRefreshIntegrationTest {

    private static final String CHANNEL = "miqrokey_route_refresh";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID KEY_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private static final Path ENC_KEY_FILE = KeyFiles.write("gateway-test-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("gateway-test-hmac.key");

    /**
     * Dedicated singleton container for this gateway test. The gateway module
     * cannot see the control-plane module's test classes, so the container is
     * declared here (same image/credentials as the control-plane suite).
     */
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
        registry.add("miqrokey.gateway.persistence.url", POSTGRES::getJdbcUrl);
        registry.add("miqrokey.gateway.persistence.username", POSTGRES::getUsername);
        registry.add("miqrokey.gateway.persistence.password", POSTGRES::getPassword);
        registry.add("miqrokey.crypto.encryption.versions.v1", () -> ENC_KEY_FILE.toString());
        registry.add("miqrokey.crypto.hmac.versions.v1", () -> HMAC_KEY_FILE.toString());
    }

    @Autowired
    RouteSnapshotHolder holder;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("pg_notify reloads the snapshot without waiting for the scheduled refresh")
    void notificationTriggersRefresh() throws Exception {
        // Wait for the listener's initial connect + refresh (version >= 1).
        await(() -> holder.current().version() >= 1);

        // Insert a routable key (with binding) behind the gateway's back, then
        // notify. The notification must be delivered to the already-connected
        // listener, which reloads the snapshot. Plain Statement.execute (simple
        // query protocol), matching the control-plane notifier: the pgjdbc
        // executeUpdate path throws "Unexpected result returned" on pg_notify.
        seedKey();
        jdbc.getJdbcTemplate().execute("SELECT pg_notify('" + CHANNEL + "', '')");

        await(() -> holder.current().version() >= 2);
        RouteSnapshot snapshot = holder.current();
        assertThat(snapshot.keys()).containsKey("mqk_test_public_key_id");
        RouteSnapshot.BindingRecord binding = snapshot.bindings().get(KEY_ID);
        assertThat(binding).isNotNull();
        assertThat(binding.projectTag()).isEqualTo("notify-proj");
    }

    private void seedKey() {
        UUID providerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("tenantId", TENANT_ID).addValue("providerId", providerId).addValue("productId", productId)
                .addValue("subscriptionId", subscriptionId).addValue("credentialId", credentialId)
                .addValue("grantId", grantId).addValue("projectId", PROJECT_ID).addValue("userId", USER_ID)
                .addValue("keyId", KEY_ID);
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:providerId, 'notify-provider', 'Notify Provider', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:productId, :providerId, 'notify-product', 'Notify Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, p);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role)
                VALUES (:userId, :tenantId, 'notify-user', 'Notify User', :hash, 'SYSTEM_ADMIN')
                """, p.addValue("hash", new byte[32]));
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:projectId, :tenantId, 'P1', 'Notify Project', 'ACTIVE', 'notify-proj', 0)
                """, p);
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:subscriptionId, :tenantId, :productId, 'Notify Sub', 'PAYG', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                VALUES (:credentialId, :tenantId, :subscriptionId, 'Notify Cred', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO project_provider_grants
                    (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                     version)
                VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :userId, 0)
                """, p);
        jdbc.update("""
                INSERT INTO virtual_keys
                    (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four,
                     user_id, project_id, grant_id, upstream_credential_id, purpose, name, status, version)
                VALUES (:keyId, :tenantId, 'mqk_test_public_key_id', :digest, 'mqk_test', '1234',
                        :userId, :projectId, :grantId, :credentialId, 'CLAUDE_CODE', 'notify-key', 'ACTIVE', 0)
                """, p.addValue("digest", new byte[32]));
        jdbc.update("""
                INSERT INTO key_project_binding (id, tenant_id, virtual_key_id, project_id, status, version)
                VALUES (:bindingId, :tenantId, :keyId, :projectId, 'ACTIVE', 0)
                """, p.addValue("bindingId", UUID.randomUUID()));
    }

    /** Polls a condition with a deadline; fails the test on timeout. */
    private static void await(ThrowingSupplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 15s");
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Writes a fresh random 32-byte key file (base64) for the crypto config. */
    private static final class KeyFiles {
        private static final SecureRandom RANDOM = new SecureRandom();

        static Path write(String name) {
            try {
                byte[] key = new byte[32];
                RANDOM.nextBytes(key);
                Path file = Files.createTempFile(name, ".key");
                Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key));
                // Production FileSecretProvider requires exactly 0400 on POSIX;
                // createTempFile defaults to 0600. No-op on non-POSIX (Windows).
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
