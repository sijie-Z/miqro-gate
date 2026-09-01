package com.miqroera.miqrokey.controlplane.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * JDK-native RS256 JWT verification for consumer authentication (ADR-0011).
 * Accepts only the {@code RS256} algorithm, parses the three JWT segments as
 * padding-less Base64url, validates {@code exp}/{@code nbf} against an
 * injectable clock and verifies the signature with the consumer's RSA public
 * key (PEM SubjectPublicKeyInfo). No third-party JWT library: the surface is
 * deliberately tiny, so every failure mode (wrong alg, tampered payload,
 * expired token, oversized claims) is an explicit false.
 */
public final class ConsumerJwtVerifier {

    /** Upper bound for a presented token (oversized-claim DoS guard). */
    public static final int MAX_TOKEN_BYTES = 16 * 1024;
    /** Upper bound for the decoded payload. */
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024;
    /** Standard clock-skew tolerance for {@code nbf}. */
    public static final Duration NBF_TOLERANCE = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;

    public ConsumerJwtVerifier() {
        this(Clock.systemUTC());
    }

    public ConsumerJwtVerifier(Clock clock) {
        this.clock = clock;
    }

    /**
     * Verifies {@code token} against the consumer's PEM public key: RS256 signature
     * over {@code header.payload}, {@code sub} equal to {@code expectedSubject},
     * {@code exp} required and in the future, optional {@code nbf} not beyond the
     * skew tolerance. Returns false on any anomaly — never throws.
     */
    public boolean verify(String token, String pem, String expectedSubject) {
        if (token == null || pem == null || expectedSubject == null) {
            return false;
        }
        try {
            byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            if (tokenBytes.length > MAX_TOKEN_BYTES) {
                return false;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            byte[] header = decodeUrl(parts[0]);
            byte[] payload = decodeUrl(parts[1]);
            byte[] signature = decodeUrl(parts[2]);
            if (header == null || payload == null || signature == null || payload.length > MAX_PAYLOAD_BYTES) {
                return false;
            }

            JsonNode headerNode = JSON.readTree(header);
            if (headerNode == null || !"RS256".equals(headerNode.path("alg").asText())) {
                return false;
            }

            JsonNode claims = JSON.readTree(payload);
            if (claims == null) {
                return false;
            }
            if (!expectedSubject.equals(claims.path("sub").asText())) {
                return false;
            }
            long exp = claims.path("exp").asLong(-1);
            if (exp < 0 || !Instant.ofEpochSecond(exp).isAfter(clock.instant())) {
                return false;
            }
            if (claims.hasNonNull("nbf") && Instant.ofEpochSecond(claims.path("nbf").asLong())
                    .isAfter(clock.instant().plus(NBF_TOLERANCE))) {
                return false;
            }

            byte[] data = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(parsePublicKey(pem));
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the {@code sub} claim without verifying the signature. Used by the
     * auth filter only to locate the consumer whose public key must verify the
     * token — authentication still requires a full {@link #verify} pass. Returns
     * null for any malformed token.
     */
    public static String extractSubject(String token) {
        if (token == null) {
            return null;
        }
        try {
            if (token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) {
                return null;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] payload = decodeUrl(parts[1]);
            if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
                return null;
            }
            JsonNode claims = JSON.readTree(payload);
            if (claims == null) {
                return null;
            }
            String sub = claims.path("sub").asText(null);
            return sub == null || sub.isBlank() ? null : sub;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a PEM SubjectPublicKeyInfo into an RSA public key. Throws when the PEM
     * is malformed or not an RSA key (callers surface a validation error).
     */
    public static PublicKey parsePublicKey(String pem)
            throws java.security.NoSuchAlgorithmException, java.security.spec.InvalidKeySpecException {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** SHA-256 of the DER public key, first 8 bytes as hex (display only). */
    public static String fingerprint(PublicKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * JWT segments are padding-less Base64url; the JDK decoder requires padding, so
     * it is restored here. Lengths of {@code 4n+1} are invalid.
     */
    private static byte[] decodeUrl(String part) {
        try {
            int rem = part.length() % 4;
            if (rem == 1) {
                return null;
            }
            String padded = rem == 0 ? part : part + "=".repeat(4 - rem);
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
