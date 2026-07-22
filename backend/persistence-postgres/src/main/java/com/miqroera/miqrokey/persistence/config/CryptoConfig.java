package com.miqroera.miqrokey.persistence.config;

import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.impl.AesGcmEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "miqrokey.crypto", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CryptoConfig {

    @Bean
    public KeyEncryptionProvider keyEncryptionProvider(
            @Value("${miqrokey.crypto.encryption.active-version:v1}") String activeVersion,
            @Value("${miqrokey.crypto.encryption.key-v1-base64:}") String keyBase64) {

        if (keyBase64.isEmpty()) {
            throw new IllegalStateException(
                    "Encryption key not configured. Set miqrokey.crypto.encryption.key-v1-base64 "
                            + "or provide a key file via MIQROKEY_MASTER_KEY_FILE.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        KeyRing keyRing = new KeyRing(activeVersion, Map.of(activeVersion, keyBytes));
        clearArray(keyBytes);
        return new AesGcmEncryptionProvider(keyRing);
    }

    @Bean
    public VirtualKeyCrypto virtualKeyCrypto(@Value("${miqrokey.crypto.hmac.active-version:v1}") String activeVersion,
            @Value("${miqrokey.crypto.hmac.key-v1-base64:}") String keyBase64) {

        if (keyBase64.isEmpty()) {
            throw new IllegalStateException("HMAC key not configured. Set miqrokey.crypto.hmac.key-v1-base64 "
                    + "or provide a key file via MIQROKEY_VK_HMAC_KEY_FILE.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        KeyRing keyRing = new KeyRing(activeVersion, Map.of(activeVersion, keyBytes));
        clearArray(keyBytes);
        return new HmacVirtualKeyProvider(keyRing);
    }

    private static void clearArray(byte[] array) {
        if (array != null) {
            java.util.Arrays.fill(array, (byte) 0);
        }
    }
}
