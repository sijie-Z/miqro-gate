package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1028: {@code PlatformOidcAuthService.provisionUser} takes the tenant row
 * lock and then checks-then-inserts the username. That only means anything if
 * the lock is still held when the insert runs — outside a transaction
 * PostgreSQL releases a {@code FOR UPDATE} at the end of its own statement, and
 * two concurrent first logins could both pick the same free username and
 * collide on {@code uq_users_tenant_username} (a 500, not the intended
 * USERNAME_CONFLICT).
 *
 * <p>
 * The window is made observable instead of instantaneous with a BEFORE INSERT
 * trigger on {@code users} that parks the insert inside {@code pg_sleep} for
 * the username this test's stub IdP returns. While that insert is in flight —
 * the insert is only reachable after the lock statement returned — the test
 * probes the tenants row from a second connection with
 * {@code FOR UPDATE NOWAIT}:
 *
 * <ul>
 * <li>lock held (fix): the probe cannot get the row, SQLSTATE
 * {@code 55P03};</li>
 * <li>lock released (bug): the probe gets it and the assertion fails.</li>
 * </ul>
 *
 * <p>
 * The probe waits for the insert to actually be in flight rather than sampling
 * blindly, so it cannot be satisfied by the short-lived FK {@code KEY SHARE}
 * that the audit write takes on the same row later in the request.
 */
@SpringBootTest
@Tag("integration")
@DisplayName("OIDC auto-provisioning keeps its tenant lock (#1028)")
class OidcProvisioningTenantLockIntegrationTest {

    private static final String PAUSE_USERNAME = "forge_pause_user";
    private static final String TRIGGER = "miqro_test_slow_user_insert";

    private static HttpServer idp;

    @BeforeAll
    static void startIdp() throws IOException {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", OidcProvisioningTenantLockIntegrationTest::tokenHandler);
        idp.createContext("/userinfo", OidcProvisioningTenantLockIntegrationTest::userinfoHandler);
        idp.start();
    }

    private static void tokenHandler(HttpExchange exchange) throws IOException {
        respond(exchange, "{\"access_token\":\"stub-access-token\",\"token_type\":\"Bearer\"}");
    }

    private static void userinfoHandler(HttpExchange exchange) throws IOException {
        respond(exchange,
                "{\"sub\":\"forge-pause-sub\",\"username\":\"" + PAUSE_USERNAME + "\",\"nickname\":\"暂停用户\"}");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterAll
    static void stopIdp() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.platform-oidc-enabled", () -> "true");
        registry.add("miqrokey.platform-oidc-client-id", () -> "stub-client");
        registry.add("miqrokey.platform-oidc-client-secret", () -> "stub-secret");
        registry.add("miqrokey.platform-oidc-authorize-uri",
                () -> "http://127.0.0.1:" + idp.getAddress().getPort() + "/authorize");
        registry.add("miqrokey.platform-oidc-token-uri",
                () -> "http://127.0.0.1:" + idp.getAddress().getPort() + "/token");
        registry.add("miqrokey.platform-oidc-userinfo-uri",
                () -> "http://127.0.0.1:" + idp.getAddress().getPort() + "/userinfo");
        registry.add("miqrokey.platform-oidc-redirect-uri", () -> "http://localhost/api/v1/auth/oauth/callback");
        registry.add("miqrokey.platform-oidc-auto-provision", () -> "true");
    }

    @Autowired
    PlatformOidcAuthService service;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    private ExecutorService io;

    @BeforeEach
    void setUp() {
        io = Executors.newSingleThreadExecutor();
        cleanRows();
        String slowInsert = "CREATE OR REPLACE FUNCTION miqro_test_slow_user_insert() RETURNS trigger AS $$\n"
                + "BEGIN\n" + "    IF NEW.username = '" + PAUSE_USERNAME + "' THEN\n" + "        PERFORM pg_sleep(2);\n"
                + "    END IF;\n" + "    RETURN NEW;\n" + "END;\n" + "$$ LANGUAGE plpgsql";
        jdbc.update(slowInsert, new MapSqlParameterSource());
        jdbc.update("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON users FOR EACH ROW "
                + "EXECUTE FUNCTION miqro_test_slow_user_insert()", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        if (io != null) {
            io.shutdownNow();
        }
        try {
            jdbc.update("DROP TRIGGER IF EXISTS " + TRIGGER + " ON users", new MapSqlParameterSource());
            jdbc.update("DROP FUNCTION IF EXISTS miqro_test_slow_user_insert()", new MapSqlParameterSource());
        } catch (Exception ignored) {
            // Leaving the trigger behind would only slow later tests on this prefix.
        }
        cleanRows();
    }

    private void cleanRows() {
        for (String table : new String[]{"user_identity_link", "user_sessions", "admin_audit_events", "users"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("the tenants row lock is still held while the provisioned user is inserted")
    void provisioningHoldsTheTenantLockAcrossTheInsert() throws Exception {
        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        service.start(new MockHttpServletRequest(), startResponse);
        String state = startResponse.getCookie("MIQROKEY_OAUTH_STATE").getValue();

        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setCookies(new Cookie("MIQROKEY_OAUTH_STATE", state));
        Future<String> pending = io
                .submit(() -> service.complete(callback, new MockHttpServletResponse(), "stub-code", state));

        try {
            awaitUsersInsertInFlight();
            assertThat(tenantRowIsLocked())
                    .as("the provisioning transaction must still hold the tenants row lock while it inserts the user")
                    .isTrue();
        } finally {
            assertThat(pending.get(60, TimeUnit.SECONDS)).isEqualTo("/app/keys");
        }

        Long users = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = :u",
                new MapSqlParameterSource("u", PAUSE_USERNAME), Long.class);
        Long links = jdbc.queryForObject(
                "SELECT count(*) FROM user_identity_link WHERE platform_user_id = 'forge-pause-sub'",
                new MapSqlParameterSource(), Long.class);
        assertThat(users).isEqualTo(1L);
        assertThat(links).isEqualTo(1L);
    }

    /**
     * Waits until the backend running our provisioning request is parked inside the
     * users insert (its current statement, because the trigger sleeps in a BEFORE
     * INSERT), which is the only moment the two revisions differ.
     */
    private void awaitUsersInsertInFlight() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Integer inFlight = jdbc.queryForObject("""
                    SELECT count(*)::int FROM pg_stat_activity
                    WHERE datname = current_database() AND state = 'active' AND pid <> pg_backend_pid()
                      AND query ILIKE '%INSERT INTO users%'
                    """, new MapSqlParameterSource(), Integer.class);
            if (inFlight != null && inFlight > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the users insert never became observable — is the pause trigger still there?");
    }

    /**
     * Probes the seed tenant from a second connection with {@code FOR UPDATE
     * NOWAIT}: SQLSTATE {@code 55P03} means somebody else is holding that row.
     */
    private boolean tenantRowIsLocked() throws SQLException {
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(false);
            try (Statement statement = probe.createStatement()) {
                statement.execute("SELECT id FROM tenants WHERE id = '" + PlatformOidcAuthService.SEED_TENANT_ID
                        + "' FOR UPDATE NOWAIT");
                probe.rollback();
                return false;
            } catch (SQLException e) {
                probe.rollback();
                if (!"55P03".equals(e.getSQLState())) {
                    throw e;
                }
                return true;
            }
        }
    }
}
