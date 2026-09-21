package com.miqroera.miqrokey.controlplane.dto;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of one admin-triggered master-key batch re-encryption run (issue
 * #432). Counts only — never any ciphertext or plaintext material.
 *
 * @param activeKeyVersion
 *            the AES key version new writes use; the migration target
 * @param scanned
 *            rows found on a non-active version in this run
 * @param reencrypted
 *            rows successfully moved onto the active version
 * @param skipped
 *            rows whose CAS update matched nothing (concurrently replaced)
 * @param failed
 *            rows that could not be decrypted/re-encrypted (left untouched)
 * @param remaining
 *            rows still on a non-active version after this run; zero means the
 *            old key version can be retired from the configuration
 * @param failures
 *            identities (table + row id) of failed rows, capped for the report
 */
public record CryptoReencryptReport(String activeKeyVersion, int scanned, int reencrypted, int skipped, int failed,
        long remaining, List<Failure> failures) {

    /** A row that failed migration; carries no secret material. */
    public record Failure(String table, UUID id) {
    }
}
