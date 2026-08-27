package com.miqroera.miqrokey.adapters.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CatalogSignatureVerifier Ed25519")
class CatalogSignatureVerifierTest {

    @Test
    @DisplayName("valid signature over exact bytes verifies")
    void validSignatureVerifies() throws Exception {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = "catalog payload".getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), data);
        assertThatCode(() -> CatalogSignatureVerifier.verify(data, signature, keys.getPublic()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("any byte change in the payload invalidates the signature")
    void tamperedPayloadRejected() {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = "catalog payload".getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), data);
        byte[] tampered = "catalog payload!".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CatalogSignatureVerifier.verify(tampered, signature, keys.getPublic()))
                .isInstanceOf(CatalogSignatureException.class).hasMessageContaining("verification failed");
    }

    @Test
    @DisplayName("tampered signature is rejected")
    void tamperedSignatureRejected() {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = "catalog payload".getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), data);
        signature[0] ^= 0x01;
        assertThatThrownBy(() -> CatalogSignatureVerifier.verify(data, signature, keys.getPublic()))
                .isInstanceOf(CatalogSignatureException.class);
    }

    @Test
    @DisplayName("signature from a different key is rejected")
    void wrongKeyRejected() {
        KeyPair signerKeys = TestCatalogSigner.newKeyPair();
        KeyPair otherKeys = TestCatalogSigner.newKeyPair();
        byte[] data = "catalog payload".getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(signerKeys.getPrivate(), data);
        assertThatThrownBy(() -> CatalogSignatureVerifier.verify(data, signature, otherKeys.getPublic()))
                .isInstanceOf(CatalogSignatureException.class).hasMessageContaining("verification failed");
    }

    @Test
    @DisplayName("truncated or oversized signatures are rejected without verification")
    void malformedSignatureRejected() {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = "catalog payload".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CatalogSignatureVerifier.verify(data, new byte[63], keys.getPublic()))
                .isInstanceOf(CatalogSignatureException.class).hasMessageContaining("64 bytes");
        assertThatThrownBy(() -> CatalogSignatureVerifier.verify(data, null, keys.getPublic()))
                .isInstanceOf(CatalogSignatureException.class).hasMessageContaining("64 bytes");
    }
}
