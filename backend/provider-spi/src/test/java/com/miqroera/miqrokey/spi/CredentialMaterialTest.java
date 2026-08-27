package com.miqroera.miqrokey.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CredentialMaterial secret hygiene")
class CredentialMaterialTest {

    @Test
    @DisplayName("destroy() wipes the secret bytes in place")
    void destroyWipesSecret() {
        byte[] secret = "sk-ant-abcdef123456".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CredentialMaterial material = new CredentialMaterial(java.util.UUID.randomUUID(), secret);
        assertThat(material.secret())
                .isEqualTo("sk-ant-abcdef123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        material.destroy();
        assertThat(material.secret()).containsOnly((byte) 0);
    }

    @Test
    @DisplayName("toString never reveals the secret")
    void toStringRedactsSecret() {
        CredentialMaterial material = new CredentialMaterial(java.util.UUID.randomUUID(),
                "sk-ant-super-secret-value".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(material.toString()).doesNotContain("super-secret").contains("REDACTED");
    }

    @Test
    @DisplayName("constructor copies the array so the caller's buffer is not aliased")
    void constructorCopies() {
        byte[] secret = "sk-ant-copy-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CredentialMaterial material = new CredentialMaterial(java.util.UUID.randomUUID(), secret);
        Arrays.fill(secret, (byte) 0);
        assertThat(material.secret()).isEqualTo("sk-ant-copy-test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        material.destroy();
    }
}
