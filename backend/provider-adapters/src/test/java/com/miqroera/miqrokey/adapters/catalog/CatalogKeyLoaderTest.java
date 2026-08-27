package com.miqroera.miqrokey.adapters.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CatalogKeyLoader")
class CatalogKeyLoaderTest {

    @Test
    @DisplayName("classpath default key loads and is an Ed25519 key")
    void defaultKeyLoads() {
        PublicKey key = CatalogKeyLoader.loadDefault();
        // JDK reports Ed25519 keys as EdDSA (JCA family name); the key works
        // with Signature.getInstance("Ed25519").
        assertThat(key.getAlgorithm()).isEqualTo("EdDSA");
        assertThat(key.getFormat()).isEqualTo("X.509");
    }

    @Test
    @DisplayName("PEM file with the bundled public key loads")
    void pemFileLoads(@TempDir Path tempDir) throws Exception {
        String pem;
        try (var in = getClass().getClassLoader().getResourceAsStream(CatalogKeyLoader.DEFAULT_CLASSPATH_KEY)) {
            pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        Path file = tempDir.resolve("key.pem");
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        PublicKey key = CatalogKeyLoader.loadFile(file);
        assertThat(key.getAlgorithm()).isEqualTo("EdDSA");
        assertThat(key).isEqualTo(CatalogKeyLoader.loadDefault());
    }

    @Test
    @DisplayName("garbage or non-Ed25519 PEM is rejected")
    void garbageRejected(@TempDir Path tempDir) throws Exception {
        Path garbage = tempDir.resolve("garbage.pem");
        Files.writeString(garbage, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertThatThrownBy(() -> CatalogKeyLoader.loadFile(garbage)).isInstanceOf(IllegalArgumentException.class);

        // An RSA key must be rejected, not silently misinterpreted.
        KeyPair rsa = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String rsaPem = "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(rsa.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path rsaFile = tempDir.resolve("rsa.pem");
        Files.writeString(rsaFile, rsaPem);
        assertThatThrownBy(() -> CatalogKeyLoader.loadFile(rsaFile)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ed25519");
    }
}
