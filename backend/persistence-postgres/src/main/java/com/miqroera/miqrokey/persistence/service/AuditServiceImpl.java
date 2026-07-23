package com.miqroera.miqrokey.persistence.service;

import com.miqroera.miqrokey.domain.model.AdminAuditEvent;
import com.miqroera.miqrokey.domain.repository.AdminAuditEventRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Default {@link AuditService} implementation.
 *
 * <p>
 * Each event is tamper-evident: its {@code currentEventHash} is computed as
 * {@code SHA-256(previousHash || canonicalEncoding(allImmutableFields))}.
 * Changing any event field (tenantId, actorId, action, targetType, targetId,
 * changeSummary, adminRequestId, id, createdAt) will break the hash chain,
 * which can be verified by recomputing every link from the genesis hash.
 * </p>
 *
 * <p>
 * Concurrency is serialised at the PostgreSQL level via
 * {@code pg_advisory_xact_lock} — a transaction-scoped advisory lock that works
 * correctly even when the audit table is empty (unlike
 * {@code SELECT ... FOR UPDATE}, which cannot lock a non-existent row). This is
 * restart-safe and correct across multiple JVM instances.
 * </p>
 *
 * <p>
 * <b>Head selection:</b> {@code findMostRecent()} orders by
 * {@code chain_position DESC} — a database-monotonic identity/sequence assigned
 * at INSERT time. JVM clock ({@code Instant.now()}) and random UUID are
 * deliberately NOT used for head ordering because they can be causally out of
 * order under concurrent advisory-lock writers. The advisory lock serialises
 * the read→insert critical section, but pre-lock timestamps can disagree with
 * commit order; {@code chain_position} reflects the true causal commit order.
 * </p>
 *
 * <p>
 * A fixed global chain lock key is acceptable because the current audit chain
 * is global (not per-tenant). The advisory lock is released automatically when
 * the transaction commits or rolls back.
 * </p>
 *
 * <p>
 * <b>Transaction propagation:</b> uses default {@code REQUIRED} propagation.
 * Audit events join the caller's transaction so that the audit insert and the
 * caller's other writes are atomic (e.g. bootstrap user insert + BOOTSTRAP
 * audit event in one transaction avoids FK-lock self-deadlock). The advisory
 * lock, predecessor read, and event insert all execute in the same transaction.
 * When called without an outer transaction the method still starts its own
 * transaction via Spring's default proxy behaviour.
 * </p>
 */
@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditServiceImpl.class);
    private static final byte[] GENESIS_HASH = new byte[32]; // all-zero genesis

    private final AdminAuditEventRepository repository;

    public AuditServiceImpl(AdminAuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(UUID tenantId, UUID actorId, String action, String targetType, UUID targetId,
            String changeSummary, String requestId) {

        Instant now = Instant.now();
        UUID id = UUID.randomUUID();

        // Acquire PostgreSQL transaction-scoped advisory lock to serialise chain-link
        // construction across concurrent writers and JVM instances. The lock is
        // released when the current transaction commits.
        repository.acquireChainLock();

        // Read predecessor under the advisory lock — no FOR UPDATE needed.
        // Head selection uses chain_position DESC (database-monotonic), not
        // created_at/id, so pre-lock Instant/UUID skew cannot fork the chain.
        AdminAuditEvent predecessor = repository.findMostRecent();
        byte[] previousHash = predecessor != null ? predecessor.currentEventHash() : GENESIS_HASH.clone(); // defensive
                                                                                                           // copy —
                                                                                                           // never
                                                                                                           // mutate the
                                                                                                           // static
                                                                                                           // genesis

        // Compute hash over all security-relevant immutable event fields
        byte[] currentHash = computeEventHash(id, tenantId, actorId, action, targetType, targetId, changeSummary,
                requestId, now, previousHash);

        AdminAuditEvent event = new AdminAuditEvent(id, tenantId, actorId, action, targetType, targetId, changeSummary,
                null, requestId, previousHash, currentHash, now, 0L);
        // chainPosition == 0 is a placeholder — the database assigns the real
        // value via DEFAULT nextval('admin_audit_events_chain_seq') on INSERT.
        repository.insert(event);

        // Zero-fill temporary arrays. previousHash is always a fresh array
        // (either a clone from currentEventHash() or a clone of GENESIS_HASH).
        Arrays.fill(previousHash, (byte) 0);
    }

    /**
     * Compute a deterministic SHA-256 hash over all security-relevant immutable
     * event fields plus the previous hash. The canonical encoding is: previousHash
     * (32 bytes) || id (16 bytes, MSB) || tenantId (16 bytes, MSB) || actorId (16
     * bytes MSB, or zeros) || action (UTF-8) || targetType (UTF-8) || targetId (16
     * bytes MSB, or zeros) || changeSummary (UTF-8) || adminRequestId (UTF-8) ||
     * createdAt (epoch millis, 8 bytes BE).
     */
    public static byte[] computeEventHash(UUID id, UUID tenantId, UUID actorId, String action, String targetType,
            UUID targetId, String changeSummary, String adminRequestId, Instant createdAt, byte[] previousHash) {

        byte[] actionBytes = (action != null) ? action.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] targetTypeBytes = (targetType != null) ? targetType.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] changeSummaryBytes = (changeSummary != null)
                ? changeSummary.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        byte[] adminRequestIdBytes = (adminRequestId != null)
                ? adminRequestId.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        byte[] idBytes = uuidToBytes(id);
        byte[] tenantIdBytes = uuidToBytes(tenantId);
        byte[] actorIdBytes = uuidToBytes(actorId != null ? actorId : new UUID(0, 0));
        byte[] targetIdBytes = uuidToBytes(targetId != null ? targetId : new UUID(0, 0));

        int exactSize = previousHash.length + idBytes.length + tenantIdBytes.length + actorIdBytes.length + 4
                + actionBytes.length + 4 + targetTypeBytes.length + targetIdBytes.length + 4 + changeSummaryBytes.length
                + 4 + adminRequestIdBytes.length + 8;

        ByteBuffer buf = ByteBuffer.allocate(exactSize);

        buf.put(previousHash);
        buf.put(idBytes);
        buf.put(tenantIdBytes);
        buf.put(actorIdBytes);
        buf.putInt(actionBytes.length);
        buf.put(actionBytes);
        buf.putInt(targetTypeBytes.length);
        buf.put(targetTypeBytes);
        buf.put(targetIdBytes);
        buf.putInt(changeSummaryBytes.length);
        buf.put(changeSummaryBytes);
        buf.putInt(adminRequestIdBytes.length);
        buf.put(adminRequestIdBytes);
        buf.putLong(createdAt.toEpochMilli());

        byte[] result = sha256(buf.array());
        Arrays.fill(buf.array(), (byte) 0);
        return result;
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
