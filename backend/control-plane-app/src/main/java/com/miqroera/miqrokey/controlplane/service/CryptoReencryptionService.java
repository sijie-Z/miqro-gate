package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.CryptoReencryptReport;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-triggered batch re-encryption of stored ciphertexts onto the active AES
 * key version (issue #432; security.md「支持主密钥轮换……后台分批重新加密旧 密文」,
 * operations-runbook 密钥轮换节). Closes the gap where
 * {@link KeyEncryptionProvider#reEncrypt} existed as a primitive with no
 * execution path: without it, retiring an old key version from the
 * configuration breaks decryption of every row still on that version.
 *
 * <h2>Coverage</h2> The three tables holding AES-GCM ciphertext at rest:
 * {@code upstream_credential_versions} (AAD = tenant + credential id),
 * {@code webhook_endpoints} (AAD = tenant + endpoint id) and
 * {@code mcp_services} backend secrets (AAD = tenant + service id). Retention
 * capture envelopes are excluded by design — they leave through Kafka and are
 * never stored in this database.
 *
 * <h2>Semantics</h2> Per-row and idempotent: each row is decrypted with its
 * stored version and re-encrypted to the active one, then written back with a
 * compare-and-set on the old version ({@code WHERE id = … AND key_version = …})
 * so a concurrent lifecycle write (credential rotation, backend-auth change) is
 * never clobbered — such a row counts as {@code skipped} and is picked up by a
 * later run if still behind. A row that fails to decrypt is left untouched,
 * counted as {@code failed} and reported by id; the batch continues. Re-running
 * until {@code remaining == 0} is the documented precondition for removing the
 * old key version. All counts are audited ({@code CRYPTO_REENCRYPT}); no secret
 * material appears in the report, logs or the audit summary.
 */
@Service
public class CryptoReencryptionService {

    private static final Logger log = LoggerFactory.getLogger(CryptoReencryptionService.class);

    /** Safety bound per table per run; single-tenant volumes are far below. */
    static final int MAX_ROWS_PER_TABLE_PER_RUN = 10_000;
    /** Cap on failure identities carried in the response report. */
    static final int MAX_REPORTED_FAILURES = 50;

    private static final TableSpec UPSTREAM_CREDENTIAL_VERSIONS = new TableSpec("upstream_credential_versions", """
            SELECT id, credential_id AS aad_id, encrypted_secret AS ciphertext, nonce,
                   encryption_key_version AS old_version
            FROM upstream_credential_versions
            WHERE tenant_id = :tenantId AND encryption_key_version <> :activeVersion
            ORDER BY id LIMIT :limit
            """, """
            UPDATE upstream_credential_versions
            SET encrypted_secret = :ciphertext, nonce = :nonce, encryption_key_version = :newVersion
            WHERE id = :id AND encryption_key_version = :oldVersion
            """);

    private static final TableSpec WEBHOOK_ENDPOINTS = new TableSpec("webhook_endpoints", """
            SELECT id, id AS aad_id, secret_encrypted AS ciphertext, secret_nonce AS nonce,
                   secret_key_version AS old_version
            FROM webhook_endpoints
            WHERE tenant_id = :tenantId AND secret_key_version <> :activeVersion
            ORDER BY id LIMIT :limit
            """, """
            UPDATE webhook_endpoints
            SET secret_encrypted = :ciphertext, secret_nonce = :nonce, secret_key_version = :newVersion
            WHERE id = :id AND secret_key_version = :oldVersion
            """);

    private static final TableSpec MCP_SERVICES = new TableSpec("mcp_services", """
            SELECT id, id AS aad_id, backend_secret_ciphertext AS ciphertext, backend_secret_nonce AS nonce,
                   backend_secret_key_version AS old_version
            FROM mcp_services
            WHERE tenant_id = :tenantId AND backend_secret_key_version IS NOT NULL
              AND backend_secret_key_version <> :activeVersion
            ORDER BY id LIMIT :limit
            """, """
            UPDATE mcp_services
            SET backend_secret_ciphertext = :ciphertext, backend_secret_nonce = :nonce,
                backend_secret_key_version = :newVersion
            WHERE id = :id AND backend_secret_key_version = :oldVersion
            """);

    private static final TableSpec[] TABLES = {UPSTREAM_CREDENTIAL_VERSIONS, WEBHOOK_ENDPOINTS, MCP_SERVICES};

    private final NamedParameterJdbcTemplate jdbc;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final AuditService auditService;

    public CryptoReencryptionService(NamedParameterJdbcTemplate jdbc, KeyEncryptionProvider keyEncryptionProvider,
            AuditService auditService) {
        this.jdbc = jdbc;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.auditService = auditService;
    }

    /**
     * Migrates this tenant's ciphertext rows onto the active key version and
     * reports the outcome. Safe to invoke at any time, including when fully caught
     * up (all counters zero).
     */
    public CryptoReencryptReport reencrypt(UUID tenantId, UUID actorId, String requestId) {
        String activeVersion = keyEncryptionProvider.activeKeyVersion();
        Counters counters = new Counters();
        for (TableSpec table : TABLES) {
            reencryptTable(tenantId, activeVersion, table, counters);
        }
        long remaining = countRemaining(tenantId, activeVersion);
        auditService.record(tenantId, actorId, "CRYPTO_REENCRYPT", "TENANT", tenantId,
                AuditSummaries.summary("activeVersion", activeVersion, "scanned", counters.scanned, "reencrypted",
                        counters.reencrypted, "skipped", counters.skipped, "failed", counters.failed, "remaining",
                        remaining),
                requestId);
        log.info("aigw.crypto.reencrypt activeVersion={} scanned={} reencrypted={} skipped={} failed={} remaining={}",
                activeVersion, counters.scanned, counters.reencrypted, counters.skipped, counters.failed, remaining);
        return new CryptoReencryptReport(activeVersion, counters.scanned, counters.reencrypted, counters.skipped,
                counters.failed, remaining, List.copyOf(counters.failures));
    }

    private void reencryptTable(UUID tenantId, String activeVersion, TableSpec table, Counters counters) {
        List<Map<String, Object>> rows = jdbc.queryForList(table.selectSql(),
                new MapSqlParameterSource("tenantId", tenantId).addValue("activeVersion", activeVersion)
                        .addValue("limit", MAX_ROWS_PER_TABLE_PER_RUN));
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            UUID aadId = (UUID) row.get("aad_id");
            String oldVersion = (String) row.get("old_version");
            counters.scanned++;
            EncryptedSecret current = new EncryptedSecret((byte[]) row.get("ciphertext"), (byte[]) row.get("nonce"),
                    oldVersion);
            EncryptedSecret fresh;
            try {
                fresh = keyEncryptionProvider.reEncrypt(current, tenantId, aadId);
            } catch (RuntimeException e) {
                counters.failed++;
                if (counters.failures.size() < MAX_REPORTED_FAILURES) {
                    counters.failures.add(new CryptoReencryptReport.Failure(table.name(), id));
                }
                log.warn("aigw.crypto.reencrypt_failed table={} id={} version={}: {}", table.name(), id, oldVersion,
                        e.getMessage());
                continue;
            }
            int updated = jdbc.update(table.updateSql(),
                    new MapSqlParameterSource("id", id).addValue("ciphertext", fresh.ciphertext())
                            .addValue("nonce", fresh.nonce()).addValue("newVersion", fresh.keyVersion())
                            .addValue("oldVersion", oldVersion));
            if (updated == 1) {
                counters.reencrypted++;
            } else {
                // A concurrent lifecycle write replaced the row first; its new
                // ciphertext already uses the active version (or will be caught
                // by the remaining count below).
                counters.skipped++;
                log.info("aigw.crypto.reencrypt_skipped table={} id={} version={}", table.name(), id, oldVersion);
            }
        }
    }

    /**
     * Rows still on a non-active version across all three tables — the signal that
     * the old key version cannot be retired yet.
     */
    private long countRemaining(UUID tenantId, String activeVersion) {
        Long remaining = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM upstream_credential_versions
                        WHERE tenant_id = :tenantId AND encryption_key_version <> :activeVersion)
                     + (SELECT count(*) FROM webhook_endpoints
                        WHERE tenant_id = :tenantId AND secret_key_version <> :activeVersion)
                     + (SELECT count(*) FROM mcp_services
                        WHERE tenant_id = :tenantId AND backend_secret_key_version IS NOT NULL
                          AND backend_secret_key_version <> :activeVersion)
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("activeVersion", activeVersion),
                Long.class);
        return remaining == null ? 0 : remaining;
    }

    /** One ciphertext table: its scan query and compare-and-set update. */
    private record TableSpec(String name, String selectSql, String updateSql) {
    }

    /** Mutable per-run counters. */
    private static final class Counters {
        private int scanned;
        private int reencrypted;
        private int skipped;
        private int failed;
        private final List<CryptoReencryptReport.Failure> failures = new ArrayList<>();
    }
}
