package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyRequest;
import com.miqroera.miqrokey.controlplane.support.RouteSnapshotRefreshNotifier;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end route refresh notification test: a dedicated probe connection
 * LISTENs on {@code miqrokey_route_refresh}; a committed
 * {@link VirtualKeyService#create} emits a {@code pg_notify} that is delivered
 * to the probe (AFTER_COMMIT semantics), and a rolled-back create publishes
 * nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("RouteSnapshotRefreshNotifier")
class RouteSnapshotRefreshNotifierTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    VirtualKeyService virtualKeyService;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final Fixture fx = new Fixture();

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("a committed key create publishes a NOTIFY on the shared channel")
    void committedCreatePublishesNotification() throws Exception {
        fx.seed();

        try (Connection probe = probeConnection(); Statement statement = probe.createStatement()) {
            statement.execute("LISTEN " + RouteSnapshotRefreshNotifier.CHANNEL);
            PGConnection pg = probe.unwrap(org.postgresql.PGConnection.class);

            virtualKeyService.create(fx.adminUser(), fx.request(), "req-committed");

            PGNotification notification = awaitNotification(pg, 10, TimeUnit.SECONDS);
            assertThat(notification).isNotNull();
            assertThat(notification.getName()).isEqualTo(RouteSnapshotRefreshNotifier.CHANNEL);
        }
    }

    @Test
    @DisplayName("a rolled-back create publishes no NOTIFY")
    void rolledBackCreatePublishesNothing() throws Exception {
        fx.seed();

        try (Connection probe = probeConnection(); Statement statement = probe.createStatement()) {
            statement.execute("LISTEN " + RouteSnapshotRefreshNotifier.CHANNEL);
            PGConnection pg = probe.unwrap(org.postgresql.PGConnection.class);

            // Unknown project id -> ApiException -> the transaction rolls back.
            assertThatThrownBy(() -> virtualKeyService.create(fx.adminUser(),
                    new CreateVirtualKeyRequest("notify-key", UUID.randomUUID(), fx.productId, fx.grantId,
                            VirtualKeyPurpose.CLAUDE_CODE, null, null),
                    "req-rolled-back")).isInstanceOf(ApiException.class);

            // afterCommit never fires on rollback, so no notification can arrive.
            assertThat(pg.getNotifications(2000)).isNullOrEmpty();
            assertThat(pg.getNotifications(2000)).isNullOrEmpty();
        }
    }

    /** Opens a raw JDBC probe connection to the shared test PostgreSQL. */
    private static Connection probeConnection() throws Exception {
        return DriverManager.getConnection(AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl(),
                AbstractControlPlaneIntegrationTest.POSTGRES.getUsername(),
                AbstractControlPlaneIntegrationTest.POSTGRES.getPassword());
    }

    private static PGNotification awaitNotification(PGConnection pg, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            PGNotification[] notifications = pg.getNotifications(2000);
            if (notifications != null && notifications.length > 0) {
                return notifications[0];
            }
        }
        return null;
    }

    /** JDBC fixtures: admin user + catalog + project with grant. */
    private final class Fixture {
        final UUID adminId = UUID.randomUUID();
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();

        void seed() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", TENANT_ID).addValue("adminId", adminId);
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role)
                    VALUES (:adminId, :tenantId, :username, 'Notify Admin', :hash, 'SYSTEM_ADMIN')
                    """, p.addValue("username", "notify-admin-" + adminId.toString().substring(0, 8)).addValue("hash",
                    new byte[32]));
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'notify-provider', 'Notify Provider', 'ACTIVE', 0)
                    """, p.addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'notify-product', 'Notify Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, p.addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Notify Project', 'ACTIVE', 'notify-proj', 0)
                    """, p.addValue("projectId", projectId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Notify Sub', 'PAYG', 'ACTIVE', 0)
                    """, p.addValue("subscriptionId", subscriptionId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Notify Cred', 'ACTIVE', 0)
                    """, p.addValue("credentialId", credentialId));
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :adminId, 0)
                    """, p.addValue("grantId", grantId));
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, 'claude-3-7-sonnet')
                    """, p);
        }

        User adminUser() {
            return new User(adminId, TENANT_ID, "notify-admin", "Notify Admin", new byte[32], UserRole.SYSTEM_ADMIN,
                    UserStatus.ACTIVE, false, 0, null, null, 0L, Instant.now(), Instant.now());
        }

        CreateVirtualKeyRequest request() {
            return new CreateVirtualKeyRequest("notify-key", projectId, productId, grantId,
                    VirtualKeyPurpose.CLAUDE_CODE, null, null);
        }

        void reset() {
            for (String table : List.of("virtual_key_models", "key_project_binding", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "upstream_subscriptions", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Tolerate migration variance; FK ordering above covers the canonical set.
                }
            }
        }
    }

    static class BootstrapHelper {
        static final Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = Files.createTempFile("bootstrap-secret", ".txt");
                Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        static Path secretFile() {
            return SECRET_FILE;
        }
    }
}
