package com.miqroera.miqrokey.adapters.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the catalog verification public key from a PEM file
 * (SubjectPublicKeyInfo, e.g. as produced by
 * {@code openssl pkey -pubout -outform PEM}). The private signing key never
 * ships with the product; the release process holds it.
 */
public final class CatalogKeyLoader {

    /** Classpath location of the built-in catalog public key. */
    public static final String DEFAULT_CLASSPATH_KEY = "catalog/keys/catalog-public.pem";

    private CatalogKeyLoader() {
    }

    /** Loads the default key bundled on the classpath. */
    public static PublicKey loadDefault() {
        try (var in = CatalogKeyLoader.class.getClassLoader().getResourceAsStream(DEFAULT_CLASSPATH_KEY)) {
            if (in == null) {
                throw new IllegalStateException("Catalog public key not found on classpath: " + DEFAULT_CLASSPATH_KEY);
            }
            return parsePem(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read catalog public key from classpath", e);
        }
    }

    /** Loads a key from a PEM file on disk. */
    public static PublicKey loadFile(Path pemFile) {
        try {
            String pem = Files.readString(pemFile, StandardCharsets.US_ASCII);
            return parsePem(pem);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read catalog public key from " + pemFile, e);
        }
    }

    private static PublicKey parsePem(String pem) {
        String body = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "").replaceAll("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        if (body.isEmpty() || body.length() < 40) {
            throw new IllegalArgumentException("PEM body is empty or malformed");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM body is not valid base64", e);
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not supported by this JVM", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("PEM file is not an Ed25519 public key", e);
        }
    }
}
