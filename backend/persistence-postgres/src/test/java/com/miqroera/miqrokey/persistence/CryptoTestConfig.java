package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.impl.AesGcmEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.security.SecureRandom;
import java.util.Map;

@TestConfiguration
public class CryptoTestConfig {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Bean
    public KeyEncryptionProvider keyEncryptionProvider() {
        byte[] encKey = randomBytes(32);
        KeyRing keyRing = new KeyRing("test-v1", Map.of("test-v1", encKey));
        return new AesGcmEncryptionProvider(keyRing);
    }

    @Bean
    public VirtualKeyCrypto virtualKeyCrypto() {
        byte[] hmacKey = randomBytes(32);
        KeyRing keyRing = new KeyRing("hmac-v1", Map.of("hmac-v1", hmacKey));
        return new HmacVirtualKeyProvider(keyRing);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }
}
