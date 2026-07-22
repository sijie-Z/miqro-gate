package com.miqroera.miqrokey.persistence.config;

import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.impl.AesGcmEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-configuration for cryptographic providers.
 *
 * <h2>Activation</h2> Activated by {@code miqrokey.crypto.enabled=true}. When
 * enabled, BOTH the AES encryption key ring and the HMAC key ring must be
 * configured. Missing configuration causes a startup failure.
 *
 * <h2>Production configuration format</h2>
 *
 * <pre>
 * miqrokey.crypto.enabled=true
 * miqrokey.crypto.encryption.active-version=v1
 * miqrokey.crypto.encryption.versions[v1]=/etc/miqrokey/keys/master-key-v1.key
 * miqrokey.crypto.hmac.active-version=v1
 * miqrokey.crypto.hmac.versions[v1]=/etc/miqrokey/keys/vk-hmac-v1.key
 * </pre>
 *
 * <h2>Multi-version support</h2> Old versions are kept in the map for
 * decryption/validation. New encryptions always use the active version. Add a
 * new version, deploy, re-encrypt, then remove the old version, then restart.
 *
 * <h2>Key separation</h2> The AES master key and HMAC key MUST be different
 * files with different material. The configuration fails fast if they resolve
 * to the same byte[] content.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "miqrokey.crypto", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(CryptoConfig.CryptoProperties.class)
public class CryptoConfig {

    @ConfigurationProperties(prefix = "miqrokey.crypto")
    public record CryptoProperties(Encryption encryption, Hmac hmac) {

        public record Encryption(String activeVersion, Map<String, String> versions) {
        }

        public record Hmac(String activeVersion, Map<String, String> versions) {
        }
    }

    @Bean
    public KeyEncryptionProvider keyEncryptionProvider(CryptoProperties props) {
        var enc = props.encryption();
        if (enc == null || enc.versions() == null || enc.versions().isEmpty()) {
            throw new CryptoOperationException("CRYPTO_CONFIG_010", "AES encryption key ring not configured. "
                    + "Set miqrokey.crypto.encryption.versions[v1]=/path/to/key-file");
        }
        String activeVersion = enc.activeVersion() != null ? enc.activeVersion() : "v1";

        KeyRing keyRing = FileSecretProvider.loadKeyRing(activeVersion, new LinkedHashMap<>(enc.versions()), 32,
                "AES master key");
        return new AesGcmEncryptionProvider(keyRing);
    }

    @Bean
    public VirtualKeyCrypto virtualKeyCrypto(CryptoProperties props) {
        var hmac = props.hmac();
        if (hmac == null || hmac.versions() == null || hmac.versions().isEmpty()) {
            throw new CryptoOperationException("CRYPTO_CONFIG_010",
                    "HMAC key ring not configured. " + "Set miqrokey.crypto.hmac.versions[v1]=/path/to/key-file");
        }
        String activeVersion = hmac.activeVersion() != null ? hmac.activeVersion() : "v1";

        // Verify master and HMAC keys use different material
        // (byte-content comparison across all version combinations in constant time)
        var enc = props.encryption();
        if (enc != null && enc.versions() != null) {
            // Fast-fail: reject identical file paths
            for (var encEntry : enc.versions().entrySet()) {
                for (var hmacEntry : hmac.versions().entrySet()) {
                    if (encEntry.getValue().equals(hmacEntry.getValue())) {
                        throw new CryptoOperationException("CRYPTO_CONFIG_011",
                                "Master and HMAC keys must use different files. Version " + encEntry.getKey() + " and "
                                        + hmacEntry.getKey() + " point to the same file: " + encEntry.getValue());
                    }
                }
            }
            // Deep check: load and compare byte contents in constant time
            FileSecretProvider.verifyKeyMaterialSeparation(new LinkedHashMap<>(enc.versions()),
                    new LinkedHashMap<>(hmac.versions()));
        }

        KeyRing hmacKeyRing = FileSecretProvider.loadKeyRing(activeVersion, new LinkedHashMap<>(hmac.versions()), -1,
                "HMAC key");

        return new HmacVirtualKeyProvider(hmacKeyRing);
    }
}
