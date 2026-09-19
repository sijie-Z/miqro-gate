package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #995: the audit chain's advisory lock and a tenant row lock must be taken in
 * the same order by every transaction.
 *
 * <p>
 * {@code AuditServiceImpl.record} takes the chain lock and <em>then</em>
 * inserts a row whose tenant foreign key takes {@code FOR KEY SHARE} on the
 * tenant. The bootstrap and registration transactions used to do the opposite —
 * {@code lockTenantForBootstrap} ({@code FOR UPDATE}) first, their audit write
 * second — so a bootstrap and a concurrent audit writer could take the two
 * locks in opposite orders. PostgreSQL's own deadlock report named the cycle:
 * </p>
 *
 * <pre>
 * Process 289 waits for ShareLock on transaction 8433; blocked by process 286.
 * Process 286 waits for ExclusiveLock on advisory lock [16384,287445236,2112454933,1]; blocked by process 289.
 *   Where: while locking tuple (0,1) in relation "tenants"
 * </pre>
 *
 * <p>
 * This test reproduces that cycle deterministically from the outside: one
 * connection plays the audit writer (chain lock, then the tenant row's
 * {@code KEY SHARE}), while the bootstrap endpoint runs to completion. Without
 * the ordering fix the two block on each other and PostgreSQL aborts one; with
 * it, the bootstrap waits for the chain lock <em>before</em> touching the
 * tenant row, so the audit writer finishes and everything proceeds.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Bootstrap vs audit-chain lock order (#995)")
class BootstrapAuditChainLockOrderTest {

    /**
     * Mirrors {@code AdminAuditEventRepositoryImpl.CHAIN_LOCK_KEY} (persistence
     * module).
     */
    private static final long CHAIN_LOCK_KEY = 1234567890123456789L;

    /** Mirrors {@code AuthenticationService.SEED_TENANT_ID}. */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        String secretPath = AuthIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString();
        registry.add("miqrokey.bootstrap-secret-file", () -> secretPath);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        try {
            jdbc.update("DELETE FROM user_sessions", new MapSqlParameterSource());
            jdbc.update("DELETE FROM users", new MapSqlParameterSource());
            jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
        } catch (Exception ignored) {
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("bootstrap completes while an audit writer holds the chain lock and wants the tenant row")
    void bootstrapWaitsOnTheChainLockFirst() throws Exception {
        ExecutorService bootstrapThread = Executors.newSingleThreadExecutor();
        try (Connection auditWriter = jdbc.getJdbcTemplate().getDataSource().getConnection()) {
            auditWriter.setAutoCommit(false);

            // 1. the audit writer takes the chain lock first, like every audit write does
            execute(auditWriter, "SELECT pg_advisory_xact_lock(" + CHAIN_LOCK_KEY + ")");

            // 2. bootstrap runs in parallel; it writes an audit event near its end
            Future<Integer> bootstrapStatus = bootstrapThread.submit(this::bootstrap);

            // Give it time to reach its first lock acquisition — that is the ordering
            // under test: chain lock before the tenant row lock.
            Thread.sleep(1_500);

            // 3. the audit writer now inserts, which takes KEY SHARE on the tenant row.
            // With the fix in place bootstrap holds nothing on that row yet, so this
            // proceeds; without it, the two wait on each other and PostgreSQL aborts
            // one of them (deadlock detected after deadlock_timeout, 1s by default).
            execute(auditWriter, "SELECT 1 FROM tenants WHERE id = '" + SEED_TENANT + "' FOR KEY SHARE");

            auditWriter.commit();

            assertThat(bootstrapStatus.get(30, TimeUnit.SECONDS))
                    .as("bootstrap must complete once the audit writer releases the chain lock").isEqualTo(201);
        } finally {
            bootstrapThread.shutdownNow();
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int bootstrap() throws Exception {
        String username = "ph995_admin_" + UUID.randomUUID().toString().substring(0, 8);
        BootstrapRequest request = new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username,
                "PH995");
        return mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andReturn().getResponse().getStatus();
    }
}
