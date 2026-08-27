package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.domain.model.AdminAuditEvent;
import com.miqroera.miqrokey.domain.repository.AdminAuditEventRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.persistence.service.AuditServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the audit chain using the <strong>real</strong>
 * {@link AuditService} with PostgreSQL advisory locking
 * ({@code pg_advisory_xact_lock}) and database-monotonic {@code chain_position}
 * ordering.
 *
 * <p>
 * These tests begin with an empty audit table, record events concurrently
 * through the service, and verify chain integrity — including continuation with
 * a fresh service instance through a real transaction boundary, tamper
 * detection, and a targeted regression proving that JVM clock skew and pre-lock
 * request timestamps cannot alter head ordering.
 * </p>
 *
 * <p>
 * The chain is global and every verification reads the <strong>whole</strong>
 * {@code admin_audit_events} table, so this class runs against a dedicated
 * database on the shared container (see {@link #AUDIT_CHAIN_DB_NAME}). Other
 * integration test classes on the same container delete or insert audit rows
 * from the public schema; sharing it would let their {@code @BeforeEach}
 * deletes or service-driven inserts interleave with this test's concurrent
 * write phase and break chain links nondeterministically.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
@DisplayName("Audit chain integrity tests (real service + advisory lock + chain_position)")
class AuditChainIntegrityTest {

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] GENESIS_HASH = new byte[32];

    /**
     * Dedicated database on the shared container — isolates the global chain from
     * other integration test classes that delete or insert audit rows.
     */
    private static final String AUDIT_CHAIN_DB_NAME = "miqrokey_audit_chain_test";

    static {
        // Ensure the shared container is initialised before Spring context
        PostgreSQLContainer<?> postgres = AbstractControlPlaneIntegrationTest.POSTGRES;
        postgres.getJdbcUrl();

        // Create the dedicated database once per JVM. CREATE DATABASE cannot run
        // inside a transaction, so it is issued on a plain auto-commit connection.
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + AUDIT_CHAIN_DB_NAME);
        } catch (SQLException e) {
            if (!e.getMessage().contains("already exists")) {
                throw new IllegalStateException(
                        "Failed to create dedicated audit-chain test database " + AUDIT_CHAIN_DB_NAME, e);
            }
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Register everything explicitly (instead of delegating to
        // AbstractControlPlaneIntegrationTest) so the datasource points at the
        // dedicated database while still sharing the container itself.
        PostgreSQLContainer<?> postgres = AbstractControlPlaneIntegrationTest.POSTGRES;
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getFirstMappedPort() + "/" + AUDIT_CHAIN_DB_NAME);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "true");
    }

    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    AuditService auditService;
    @Autowired
    AdminAuditEventRepository repository;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearAudit() {
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    // -----------------------------------------------------------------------
    // Helper: Base64-encode a byte array for readable assertions
    // -----------------------------------------------------------------------
    static String b64(byte[] bytes) {
        return bytes != null ? Base64.getEncoder().encodeToString(bytes) : "null";
    }

    // -----------------------------------------------------------------------
    // Helper: a byte[] wrapper with value equality for use in Set/Map
    // -----------------------------------------------------------------------
    static final class ByteArrayKey {
        final byte[] data;

        ByteArrayKey(byte[] data) {
            this.data = data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ByteArrayKey that))
                return false;
            return Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    // -----------------------------------------------------------------------
    // Read all events from the database ordered by chain_position (the
    // authoritative ordering). Returns a list from oldest to newest.
    // -----------------------------------------------------------------------
    List<AdminAuditEvent> readAllByChainPosition() {
        return jdbc.query("SELECT * FROM admin_audit_events ORDER BY chain_position ASC",
                (rs, rn) -> new AdminAuditEvent((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("actor_id"), rs.getString("action"), rs.getString("target_type"),
                        (UUID) rs.getObject("target_id"), rs.getString("change_summary"),
                        rs.getString("gateway_request_id"), rs.getString("admin_request_id"),
                        rs.getBytes("previous_event_hash"), rs.getBytes("current_event_hash"),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("chain_position")));
    }

    // -----------------------------------------------------------------------
    // Verify the full chain: recompute every hash, check genesis uniqueness,
    // no duplicate predecessors, and reconstruct one complete linked chain
    // from genesis through every event exactly once to a single head.
    // Returns the list in chain_position order for further assertions.
    // -----------------------------------------------------------------------
    List<AdminAuditEvent> verifyFullChain(int expectedCount) {
        List<AdminAuditEvent> events = readAllByChainPosition();
        assertThat(events).as("exactly %d events", expectedCount).hasSize(expectedCount);

        // 1. Exactly one genesis (previousEventHash == all-zeros)
        long genesisCount = events.stream().filter(e -> Arrays.equals(e.previousEventHash(), GENESIS_HASH)).count();
        assertThat(genesisCount).as("exactly one genesis predecessor").isEqualTo(1);

        // 2. No duplicate raw predecessors (full byte-array equality, not hashCode)
        Set<ByteArrayKey> uniquePredecessors = new HashSet<>();
        long nonGenesisCount = 0;
        for (AdminAuditEvent e : events) {
            if (!Arrays.equals(e.previousEventHash(), GENESIS_HASH)) {
                nonGenesisCount++;
            }
            uniquePredecessors.add(new ByteArrayKey(e.previousEventHash()));
        }
        assertThat(uniquePredecessors.size()).as("no duplicate predecessor hashes").isEqualTo(expectedCount);

        // 3. Recompute every event's hash from its fields + predecessor
        for (AdminAuditEvent e : events) {
            byte[] computed = AuditServiceImpl.computeEventHash(e.id(), e.tenantId(), e.actorId(), e.action(),
                    e.targetType(), e.targetId(), e.changeSummary(), e.adminRequestId(), e.createdAt(),
                    e.previousEventHash());
            assertThat(e.currentEventHash())
                    .as("recomputed hash matches for event %s (chain_position=%d)", e.id(), e.chainPosition())
                    .isEqualTo(computed);
        }

        // 4. Build predecessor→successor map: every current_hash (except the head)
        // must be referenced as some successor's previous_event_hash.
        Map<ByteArrayKey, AdminAuditEvent> byCurrentHash = new HashMap<>();
        for (AdminAuditEvent e : events) {
            byCurrentHash.put(new ByteArrayKey(e.currentEventHash()), e);
        }

        // Every non-genesis event's predecessor must exist in the map.
        AdminAuditEvent head = null;
        byte[] genesisCurrentHash = null;
        for (AdminAuditEvent e : events) {
            if (Arrays.equals(e.previousEventHash(), GENESIS_HASH)) {
                genesisCurrentHash = e.currentEventHash();
            }
            ByteArrayKey predKey = new ByteArrayKey(e.previousEventHash());
            if (byCurrentHash.containsKey(predKey)) {
                // predecessor exists — this is not the genesis
            } else if (!Arrays.equals(e.previousEventHash(), GENESIS_HASH)) {
                // non-genesis with no predecessor in the chain → broken link
                throw new AssertionError("Event " + e.id() + " at chain_position=" + e.chainPosition()
                        + " has predecessor " + b64(e.previousEventHash()) + " that is not any event's current_hash");
            }
        }

        // 5. Find the single head: the event whose current_hash is NOT any
        // predecessor. This is the last event in the chain.
        Set<ByteArrayKey> allPredecessors = new HashSet<>();
        for (AdminAuditEvent e : events) {
            allPredecessors.add(new ByteArrayKey(e.previousEventHash()));
        }
        AdminAuditEvent genesis = events.stream().filter(e -> Arrays.equals(e.previousEventHash(), GENESIS_HASH))
                .findFirst().orElseThrow();
        for (AdminAuditEvent e : events) {
            if (!allPredecessors.contains(new ByteArrayKey(e.currentEventHash()))) {
                head = e;
                break;
            }
        }
        assertThat(head).as("a single head event exists").isNotNull();

        // 6. Walk the chain forward from genesis to head, visiting every event
        // exactly once.
        Map<ByteArrayKey, AdminAuditEvent> byPreviousHash = new HashMap<>();
        for (AdminAuditEvent e : events) {
            byPreviousHash.put(new ByteArrayKey(e.previousEventHash()), e);
        }
        List<AdminAuditEvent> walked = new ArrayList<>();
        ByteArrayKey cursor = new ByteArrayKey(genesis.currentEventHash());
        walked.add(genesis);
        while (byPreviousHash.containsKey(cursor)) {
            AdminAuditEvent next = byPreviousHash.get(cursor);
            if (walked.contains(next)) {
                throw new AssertionError("Cycle detected at " + next.id() + " (chain_position=" + next.chainPosition()
                        + "): predecessor " + b64(cursor.data) + " already visited");
            }
            walked.add(next);
            cursor = new ByteArrayKey(next.currentEventHash());
        }
        assertThat(walked).as("every event visited exactly once in walked chain").hasSize(expectedCount);
        assertThat(walked.get(walked.size() - 1)).as("walked chain ends at head").isEqualTo(head);

        return events;
    }

    // -----------------------------------------------------------------------
    // 1. Concurrent writers — empty table start (strengthened)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("concurrent writers from empty table: exactly N rows, one genesis, single chain, no forks, full walk")
    void concurrentWritersFromEmptyTable() throws Exception {
        int writers = 5;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Callable<Void>> tasks = new ArrayList<>();
        UUID actorId = UUID.randomUUID();

        for (int i = 0; i < writers; i++) {
            final int idx = i;
            tasks.add(() -> {
                latch.await();
                // jsonb scalar values that survive round-trip normalization unchanged
                auditService.record(SEED_TENANT_ID, actorId, "ACTION_" + idx, "TARGET_TYPE", UUID.randomUUID(),
                        String.valueOf(idx), "req-" + idx);
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get(1, TimeUnit.SECONDS); // throw if any exception
        }

        // Full verification: exactly N, one genesis, no dup preds, all hashes
        // recompute, single linked chain from genesis to head.
        verifyFullChain(writers);
    }

    // -----------------------------------------------------------------------
    // 2. Continuation with fresh service instance through real transaction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("continuation: fresh service instance continues chain from last committed event via real transaction")
    void freshServiceContinuesChain() {
        UUID actorId = UUID.randomUUID();

        // Record N events through the primary (Spring-managed) service.
        auditService.record(SEED_TENANT_ID, actorId, "EVENT_1", "TYPE", UUID.randomUUID(), "1", "req-1");
        auditService.record(SEED_TENANT_ID, actorId, "EVENT_2", "TYPE", UUID.randomUUID(), "2", "req-2");
        auditService.record(SEED_TENANT_ID, actorId, "EVENT_3", "TYPE", UUID.randomUUID(), "3", "req-3");

        // Read EVENT_3 from DB (by chain_position) before creating fresh service
        List<AdminAuditEvent> eventsBefore = readAllByChainPosition();
        AdminAuditEvent event3 = eventsBefore.stream().filter(e -> "EVENT_3".equals(e.action())).findFirst()
                .orElseThrow();

        // Create a fresh AuditServiceImpl sharing the same repository — simulates
        // a restart. This plain object has no @Transactional proxy, so we wrap
        // the call in a TransactionTemplate to provide a real transaction boundary.
        AuditService freshService = new AuditServiceImpl(repository);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(status -> {
            freshService.record(SEED_TENANT_ID, actorId, "EVENT_4", "TYPE", UUID.randomUUID(), "4", "req-4");
        });

        // Full verification: exactly 4, one genesis, single linked chain
        List<AdminAuditEvent> events = verifyFullChain(4);

        // Assert direct chain link: EVENT_4.previousHash == EVENT_3.currentHash
        AdminAuditEvent event4 = events.stream().filter(e -> "EVENT_4".equals(e.action())).findFirst().orElseThrow();
        assertThat(event4.previousEventHash())
                .as("EVENT_4 directly links to EVENT_3 — previous_hash matches current_hash")
                .isEqualTo(event3.currentEventHash());
    }

    // -----------------------------------------------------------------------
    // 3. Tamper detection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tamper detection: modifying any field breaks the hash chain")
    void tamperDetectionBreaksChain() {
        UUID actorId = UUID.randomUUID();

        auditService.record(SEED_TENANT_ID, actorId, "LOGIN", "USER", actorId, "{\"username\":\"alice\"}",
                "req-tamper");

        AdminAuditEvent event = repository.findMostRecent();
        assertThat(event).isNotNull();

        byte[] originalHash = event.currentEventHash();

        // Tamper with changeSummary
        byte[] tamperedHash = AuditServiceImpl.computeEventHash(event.id(), event.tenantId(), event.actorId(),
                event.action(), event.targetType(), event.targetId(), "{\"tampered\":true}", event.adminRequestId(),
                event.createdAt(), event.previousEventHash());
        assertThat(originalHash).as("tampered summary should produce different hash").isNotEqualTo(tamperedHash);

        // Tamper with createdAt
        byte[] tamperedTimeHash = AuditServiceImpl.computeEventHash(event.id(), event.tenantId(), event.actorId(),
                event.action(), event.targetType(), event.targetId(), event.changeSummary(), event.adminRequestId(),
                event.createdAt().plusSeconds(1), event.previousEventHash());
        assertThat(originalHash).as("tampered timestamp should produce different hash").isNotEqualTo(tamperedTimeHash);

        // Tamper with action
        byte[] tamperedActionHash = AuditServiceImpl.computeEventHash(event.id(), event.tenantId(), event.actorId(),
                "TAMPERED_ACTION", event.targetType(), event.targetId(), event.changeSummary(), event.adminRequestId(),
                event.createdAt(), event.previousEventHash());
        assertThat(originalHash).as("tampered action should produce different hash").isNotEqualTo(tamperedActionHash);
    }

    // -----------------------------------------------------------------------
    // 3b. Regression: object change summaries survive jsonb normalisation
    //
    // change_summary is a jsonb column: PostgreSQL reorders object keys and
    // strips insignificant whitespace. The stored hash must therefore be
    // computed over the normalised text form, otherwise recomputing the hash
    // from the persisted row flags every object-summary event as tampered.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("regression: object change summaries verify against the normalised jsonb form")
    void objectChangeSummaryVerifiesAgainstNormalisedJsonb() {
        UUID actorId = UUID.randomUUID();

        // Key order "b,a,c" and compact spacing differ from jsonb's canonical
        // form, so an unnormalised hash would never match the stored row.
        auditService.record(SEED_TENANT_ID, actorId, "JSON_SUMMARY", "TYPE", UUID.randomUUID(),
                "{\"b\":2,\"a\":\"x y\",\"c\":[1,2]}", "req-json");

        // Full chain verification recomputes every hash from the persisted row.
        verifyFullChain(1);
    }

    // -----------------------------------------------------------------------
    // 4. Regression: pre-lock timestamps do NOT determine head ordering
    //
    // AuditServiceImpl creates Instant.now() and UUID before acquiring the
    // advisory lock. Under concurrency, a later-writer with an older
    // Instant can commit after a faster writer. If head selection used
    // created_at, this would fork the chain. This test proves that
    // chain_position (database-monotonic) is the authoritative ordering
    // and that created_at values do not influence head selection.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("regression: pre-lock timestamps cannot alter head ordering — chain_position is authoritative")
    void preLockTimestampsDoNotAffectHeadOrdering() throws Exception {
        int writers = 8;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Callable<Void>> tasks = new ArrayList<>();
        UUID actorId = UUID.randomUUID();

        for (int i = 0; i < writers; i++) {
            final int idx = i;
            tasks.add(() -> {
                latch.await();
                // Simulate varying pre-lock work: some threads sleep, some don't.
                // A faster thread gets a newer Instant; a slower thread gets an
                // older Instant but commits after. This is exactly the scenario
                // that forked the chain with created_at ordering.
                if (idx % 2 == 1) {
                    Thread.sleep(1); // small skew — later writer, older timestamp
                }
                auditService.record(SEED_TENANT_ID, actorId, "REGRESSION_" + idx, "TARGET", UUID.randomUUID(),
                        String.valueOf(idx), "r-" + idx);
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get(1, TimeUnit.SECONDS);
        }

        // Full chain verification: single genesis, no forks, complete walkable chain.
        List<AdminAuditEvent> events = verifyFullChain(writers);

        // Additional: verify that chain_position strictly increases along the
        // walked chain — the database assigned monotonically increasing values.
        List<AdminAuditEvent> sorted = readAllByChainPosition();
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).chainPosition()).as("chain_position is strictly increasing (pos %d > pos %d)",
                    sorted.get(i).chainPosition(), sorted.get(i - 1).chainPosition())
                    .isGreaterThan(sorted.get(i - 1).chainPosition());
        }

        // Verify that created_at does NOT strictly increase along the chain —
        // proving that pre-lock timestamps can be out of order, yet the chain
        // remains valid because chain_position is the authoritative ordering.
        boolean createdAtIsStrictlyIncreasing = true;
        for (int i = 1; i < events.size(); i++) {
            if (!events.get(i).createdAt().isAfter(events.get(i - 1).createdAt())) {
                createdAtIsStrictlyIncreasing = false;
                break;
            }
        }
        // We don't assert this is false — it might happen to be true by chance.
        // We only assert that the chain is valid regardless of created_at ordering.
        // The key invariant is: chain_position ordering always produces a valid chain,
        // even when created_at ordering would not.
    }

    // -----------------------------------------------------------------------
    // 5. Verify that ordering by created_at on the SAME concurrent-writer
    // dataset can disagree with chain_position — i.e., the original bug
    // scenario. This is a diagnostic test, not a strict assertion.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("diagnostic: created_at ordering may differ from chain_position ordering under concurrency")
    void createdAtOrderingMayDivergeFromChainPosition() throws Exception {
        int writers = 10;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Callable<Void>> tasks = new ArrayList<>();
        UUID actorId = UUID.randomUUID();

        for (int i = 0; i < writers; i++) {
            final int idx = i;
            tasks.add(() -> {
                latch.await();
                // Varying pre-lock delays to maximise timestamp skew
                if (idx % 3 == 1)
                    Thread.sleep(2);
                if (idx % 3 == 2)
                    Thread.sleep(5);
                auditService.record(SEED_TENANT_ID, actorId, "DIAG_" + idx, "T", UUID.randomUUID(), String.valueOf(idx),
                        "d-" + idx);
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get(1, TimeUnit.SECONDS);
        }

        // Full chain verification by chain_position
        verifyFullChain(writers);

        // Read by created_at ordering (the old bug-prone ordering)
        List<AdminAuditEvent> byCreatedAt = jdbc.query("SELECT * FROM admin_audit_events ORDER BY created_at, id",
                (rs, rn) -> new AdminAuditEvent((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("actor_id"), rs.getString("action"), rs.getString("target_type"),
                        (UUID) rs.getObject("target_id"), rs.getString("change_summary"),
                        rs.getString("gateway_request_id"), rs.getString("admin_request_id"),
                        rs.getBytes("previous_event_hash"), rs.getBytes("current_event_hash"),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("chain_position")));

        // Read by chain_position (correct ordering)
        List<AdminAuditEvent> byChainPos = readAllByChainPosition();

        // Both orderings must produce exactly the same set of events (same
        // chain_positions), just potentially in different order.
        Set<Long> cpByCreatedAt = new HashSet<>();
        for (AdminAuditEvent e : byCreatedAt) {
            cpByCreatedAt.add(e.chainPosition());
        }
        Set<Long> cpByChainPos = new HashSet<>();
        for (AdminAuditEvent e : byChainPos) {
            cpByChainPos.add(e.chainPosition());
        }
        assertThat(cpByCreatedAt).as("same chain_positions regardless of ordering").isEqualTo(cpByChainPos);

        // The diagnostic: report whether the two orderings disagree.
        boolean orderingDisagrees = false;
        for (int i = 0; i < writers; i++) {
            if (!Objects.equals(byCreatedAt.get(i).chainPosition(), byChainPos.get(i).chainPosition())) {
                orderingDisagrees = true;
                break;
            }
        }
        // This is informational — when it disagrees, it proves the bug exists.
        // When it doesn't disagree (fast CI), the full-chain verification above
        // still proves the fix is correct.
        System.out.println("[diagnostic] created_at ordering "
                + (orderingDisagrees ? "DIFFERS from" : "happens to match") + " chain_position ordering in this run");
    }
}
