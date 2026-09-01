package com.miqroera.miqrokey.controlplane.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the JDK-native RS256 JWT verifier (ADR-0011): signature
 * verification, claim checks against an injected clock, algorithm strictness
 * (alg=none / non-RS256 rejected), padding-less Base64url handling and PEM
 * parsing.
 */
@DisplayName("ConsumerJwtVerifier")
class ConsumerJwtVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final ConsumerJwtVerifier VERIFIER = new ConsumerJwtVerifier(Clock.fixed(NOW, ZoneOffset.UTC));

    // ---------------------------------------------------------------- happy

    @Test
    @DisplayName("a valid RS256 token with matching sub and future exp verifies")
    void validTokenPasses() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), null));

        assertThat(VERIFIER.verify(token, pem(keys.getPublic()), "platform")).isTrue();
    }

    @Test
    @DisplayName("nbf within the clock-skew tolerance is accepted, beyond it rejected")
    void nbfTolerance() throws Exception {
        KeyPair keys = rsaKeyPair();
        String withinSkew = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), NOW.plusSeconds(30)));
        String beyondSkew = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), NOW.plusSeconds(120)));

        assertThat(VERIFIER.verify(withinSkew, pem(keys.getPublic()), "platform")).isTrue();
        assertThat(VERIFIER.verify(beyondSkew, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("padding-less Base64url segments (real JWT shape) are decoded")
    void paddingLessBase64urlWorks() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), null));

        // Standard JWT encoding omits Base64 padding; decodeUrl must restore it.
        String[] parts = token.split("\\.");
        assertThat(parts[0].length() % 4).isNotEqualTo(1);
        assertThat(VERIFIER.verify(token, pem(keys.getPublic()), "platform")).isTrue();
    }

    // -------------------------------------------------------------- rejects

    @Test
    @DisplayName("an expired token is rejected")
    void expiredRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("platform", NOW.minusSeconds(10), null));

        assertThat(VERIFIER.verify(token, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("a missing exp claim is rejected")
    void missingExpRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), Map.of("sub", "platform"));

        assertThat(VERIFIER.verify(token, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("a token whose sub does not match the consumer is rejected")
    void wrongSubjectRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("other", NOW.plusSeconds(600), null));

        assertThat(VERIFIER.verify(token, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("a tampered payload fails signature verification")
    void tamperedPayloadRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), null));
        String[] parts = token.split("\\.");
        String forgedPayload = b64url(
                "{\"sub\":\"platform\",\"exp\":%d}".formatted(NOW.plusSeconds(60000).getEpochSecond()));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(VERIFIER.verify(tampered, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("a token signed by another key is rejected")
    void wrongSignatureRejected() throws Exception {
        KeyPair platform = rsaKeyPair();
        KeyPair attacker = rsaKeyPair();
        String token = sign(attacker.getPrivate(), claims("platform", NOW.plusSeconds(600), null));

        assertThat(VERIFIER.verify(token, pem(platform.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("alg=none or any non-RS256 alg is rejected")
    void nonRs256Rejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String header = b64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"sub\":\"platform\",\"exp\":%d}".formatted(NOW.plusSeconds(600).getEpochSecond()));
        String noneToken = header + "." + payload + ".";
        String hs256 = signWith(keys.getPrivate(), "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                Map.of("sub", "platform", "exp", NOW.plusSeconds(600).getEpochSecond()));

        assertThat(VERIFIER.verify(noneToken, pem(keys.getPublic()), "platform")).isFalse();
        assertThat(VERIFIER.verify(hs256, pem(keys.getPublic()), "platform")).isFalse();
    }

    @Test
    @DisplayName("malformed tokens (wrong segment count, bad base64) are rejected")
    void malformedRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String pem = pem(keys.getPublic());

        assertThat(VERIFIER.verify("only-two.parts", pem, "platform")).isFalse();
        assertThat(VERIFIER.verify("a.b.c.d", pem, "platform")).isFalse();
        assertThat(VERIFIER.verify("a.b.c", pem, "platform")).isFalse();
        assertThat(VERIFIER.verify(null, pem, "platform")).isFalse();
        assertThat(VERIFIER.verify("a.b.c", null, "platform")).isFalse();
    }

    @Test
    @DisplayName("oversized tokens and payloads are rejected")
    void oversizedRejected() throws Exception {
        KeyPair keys = rsaKeyPair();
        String huge = "x".repeat(ConsumerJwtVerifier.MAX_TOKEN_BYTES + 1);
        assertThat(VERIFIER.verify(huge, pem(keys.getPublic()), "platform")).isFalse();
    }

    // ------------------------------------------------------- subject extraction

    @Test
    @DisplayName("extractSubject reads sub without verifying; malformed returns null")
    void extractSubject() throws Exception {
        KeyPair keys = rsaKeyPair();
        String token = sign(keys.getPrivate(), claims("platform", NOW.plusSeconds(600), null));

        assertThat(ConsumerJwtVerifier.extractSubject(token)).isEqualTo("platform");
        assertThat(ConsumerJwtVerifier.extractSubject("not-a-jwt")).isNull();
        assertThat(ConsumerJwtVerifier.extractSubject("a.b.")).isNull();
        assertThat(ConsumerJwtVerifier.extractSubject(null)).isNull();
    }

    // --------------------------------------------------------------- key tools

    @Test
    @DisplayName("PEM parsing accepts RSA keys and rejects garbage and EC keys")
    void pemParsing() throws Exception {
        KeyPair rsa = rsaKeyPair();
        assertThat(ConsumerJwtVerifier.parsePublicKey(pem(rsa.getPublic())).getAlgorithm()).isEqualTo("RSA");

        // Garbage decodes to invalid DER -> fails key construction.
        assertThatThrownBy(() -> ConsumerJwtVerifier.parsePublicKey("not a pem"))
                .isInstanceOf(java.security.spec.InvalidKeySpecException.class);

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(256);
        KeyPair ec = ecGen.generateKeyPair();
        assertThatThrownBy(() -> ConsumerJwtVerifier.parsePublicKey(pem(ec.getPublic())))
                .isInstanceOf(java.security.spec.InvalidKeySpecException.class);
    }

    @Test
    @DisplayName("fingerprint is 16 hex chars and stable for the same key")
    void fingerprint() throws Exception {
        KeyPair keys = rsaKeyPair();
        String first = ConsumerJwtVerifier.fingerprint(keys.getPublic());
        String second = ConsumerJwtVerifier.fingerprint(keys.getPublic());

        assertThat(first).hasSize(16).isEqualTo(second);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> claims(String sub, Instant exp, Instant nbf) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("exp", exp.getEpochSecond());
        if (nbf != null) {
            claims.put("nbf", nbf.getEpochSecond());
        }
        return claims;
    }

    private static String sign(PrivateKey key, Map<String, Object> claims) throws Exception {
        return signWith(key, "{\"alg\":\"RS256\",\"typ\":\"JWT\"}", claims);
    }

    private static String signWith(PrivateKey key, String headerJson, Object claims) throws Exception {
        String header = b64url(headerJson);
        String payload = b64url(new ObjectMapper().writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + b64url(signature.sign());
    }

    private static String b64url(String json) {
        return b64url(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
