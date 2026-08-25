package com.miqroera.miqrokey.adapters.catalog;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Ed25519 verification of catalog payloads. The signature covers the exact
 * catalog bytes; any change to the payload — including re-encoding that changes
 * no data — invalidates the signature.
 */
public final class CatalogSignatureVerifier {

    /** Length of an Ed25519 signature in bytes. */
    public static final int SIGNATURE_BYTES = 64;

    private CatalogSignatureVerifier() {
    }

    /**
     * Verifies {@code data} against {@code signature} with {@code publicKey}.
     *
     * @throws CatalogSignatureException
     *             when the key is not Ed25519, the signature is malformed, or
     *             verification fails (tampered payload or wrong key)
     */
    public static void verify(byte[] data, byte[] signature, PublicKey publicKey) throws CatalogSignatureException {
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            throw new CatalogSignatureException("Catalog signature must be exactly " + SIGNATURE_BYTES + " bytes, got "
                    + (signature == null ? "null" : signature.length));
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(data);
            if (!verifier.verify(signature)) {
                throw new CatalogSignatureException(
                        "Catalog signature verification failed (payload tampered or wrong key)");
            }
        } catch (CatalogSignatureException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new CatalogSignatureException("Catalog signature verification failed: " + e.getMessage(), e);
        }
    }
}
