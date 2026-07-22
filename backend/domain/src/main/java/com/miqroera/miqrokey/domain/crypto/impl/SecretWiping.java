package com.miqroera.miqrokey.domain.crypto.impl;

/**
 * Zero-fill utility for clearing sensitive byte arrays from memory.
 *
 * <p>
 * This is a defense-in-depth measure; it does not guarantee immediate physical
 * memory reclamation (the JVM may have copied the array during GC), but it
 * reduces the window where key material or plaintext is visible in a heap dump.
 * </p>
 */
public final class SecretWiping {

    private SecretWiping() {
        // utility class
    }

    /**
     * Fills the entire array with zero bytes. Null-safe.
     *
     * @param array
     *            the array to clear (may be null)
     */
    public static void clearArray(byte[] array) {
        if (array != null) {
            java.util.Arrays.fill(array, (byte) 0);
        }
    }
}
