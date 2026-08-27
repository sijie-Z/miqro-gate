package com.miqroera.miqrokey.persistence.config;

import com.miqroera.miqrokey.domain.service.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Argon2id password hasher using Spring Security's
 * {@link Argon2PasswordEncoder}.
 *
 * <p>
 * Parameters are tuned for server-side authentication: 64 MiB memory, 4
 * iterations, 1 lane of parallelism. The encoded output is a self-contained
 * string (includes salt, parameters, and hash) stored as UTF-8 bytes in the
 * {@code password_hash} column.
 * </p>
 *
 * <p>
 * Uses BouncyCastle as the Argon2 implementation provider (included
 * transitively via {@code spring-security-crypto}).
 * </p>
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 65536; // 64 MiB
    private static final int ITERATIONS = 4;

    private final Argon2PasswordEncoder encoder;

    public Argon2PasswordHasher() {
        this.encoder = new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }

    @Override
    public byte[] hash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        return encoder.encode(password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean verify(String password, byte[] hash) {
        if (password == null || hash == null || hash.length == 0) {
            return false;
        }
        try {
            return encoder.matches(password, new String(hash, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stable pre-computed Argon2 hash for timing-indistinguishable dummy
     * verification. Generated once at class-load time with the same parameters so
     * every call performs identical work.
     */
    private static final String DUMMY_HASH = new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM,
            MEMORY_KIB, ITERATIONS).encode("miqrokey-dummy-work-factor-anchor");

    @Override
    public boolean verifyAgainstDummy(String password) {
        // Always compute real Argon2 work against the stable dummy hash.
        // The result is always false, but the CPU/memory cost is identical to
        // a real verify() call.
        try {
            encoder.matches(password, DUMMY_HASH);
        } catch (Exception e) {
            // Ignore — timing path matters, not the boolean result
        }
        return false;
    }

    @Override
    public boolean needsRehash(byte[] hash) {
        if (hash == null || hash.length == 0) {
            return true;
        }
        try {
            return encoder.upgradeEncoding(new String(hash, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return true;
        }
    }
}
