package com.miqroera.miqrokey.adapters.catalog;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Test-only helpers to sign arbitrary payloads with an ephemeral Ed25519 key.
 * Production catalogs are signed by the release process; tests only ever use
 * throwaway keys.
 */
final class TestCatalogSigner {

    private TestCatalogSigner() {
    }

    static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(data);
            return signer.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign test payload", e);
        }
    }

    static byte[] signUtf8(PrivateKey key, String data) {
        return sign(key, data.getBytes(StandardCharsets.UTF_8));
    }

    /** A minimal valid catalog manifest (one product). */
    static String validManifest() {
        return """
                {
                  "version": 1,
                  "products": [
                    {
                      "id": "deepseek-payg-api",
                      "vendor": "deepseek",
                      "displayName": "DeepSeek 官方按量 API",
                      "adapterId": "deepseek-payg-api",
                      "protocols": ["OPENAI_COMPATIBLE", "ANTHROPIC_MESSAGES"],
                      "baseUrlTemplate": "https://api.deepseek.com",
                      "credentialKind": "API_KEY",
                      "subscriptionKinds": ["PAYG"],
                      "modelCatalogMode": "OFFICIAL_API",
                      "status": "DOCUMENTED"
                    }
                  ]
                }
                """;
    }
}
