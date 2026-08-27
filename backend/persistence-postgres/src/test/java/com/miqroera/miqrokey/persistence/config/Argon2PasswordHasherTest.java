package com.miqroera.miqrokey.persistence.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Argon2PasswordHasher")
class Argon2PasswordHasherTest {

    Argon2PasswordHasher hasher = new Argon2PasswordHasher();

    @Nested
    @DisplayName("Hash and verify")
    class HashAndVerify {
        @Test
        @DisplayName("should hash and verify a password")
        void shouldHashAndVerify() {
            byte[] hash = hasher.hash("MySecurePass1!");
            assertThat(hash).isNotNull().isNotEmpty();
            assertThat(hasher.verify("MySecurePass1!", hash)).isTrue();
        }

        @Test
        @DisplayName("should reject incorrect password")
        void shouldRejectIncorrectPassword() {
            byte[] hash = hasher.hash("MySecurePass1!");
            assertThat(hasher.verify("WrongPassword1!", hash)).isFalse();
        }

        @Test
        @DisplayName("should produce different hashes for same password")
        void shouldProduceDifferentHashes() {
            byte[] hash1 = hasher.hash("MySecurePass1!");
            byte[] hash2 = hasher.hash("MySecurePass1!");
            assertThat(hash1).isNotEqualTo(hash2);
            assertThat(hasher.verify("MySecurePass1!", hash1)).isTrue();
            assertThat(hasher.verify("MySecurePass1!", hash2)).isTrue();
        }
    }

    @Nested
    @DisplayName("Dummy verification (timing indistinguishability)")
    class DummyVerification {
        @Test
        @DisplayName("verifyAgainstDummy should always return false")
        void shouldAlwaysReturnFalse() {
            assertThat(hasher.verifyAgainstDummy("any-password")).isFalse();
            assertThat(hasher.verifyAgainstDummy("another-password")).isFalse();
        }

        @Test
        @DisplayName("verifyAgainstDummy should be callable with same params as verify")
        void shouldAcceptSameParams() {
            assertThat(hasher.verifyAgainstDummy("password")).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {
        @Test
        @DisplayName("should reject null password")
        void shouldRejectNullPassword() {
            assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject blank password")
        void shouldRejectBlankPassword() {
            assertThatThrownBy(() -> hasher.hash("   ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should return false for null hash")
        void shouldReturnFalseForNullHash() {
            assertThat(hasher.verify("password", null)).isFalse();
        }

        @Test
        @DisplayName("should return false for empty hash")
        void shouldReturnFalseForEmptyHash() {
            assertThat(hasher.verify("password", new byte[0])).isFalse();
        }

        @Test
        @DisplayName("should accept unicode passwords")
        void shouldAcceptUnicodePasswords() {
            String password = "Passwo1!";
            byte[] hash = hasher.hash(password);
            assertThat(hasher.verify(password, hash)).isTrue();
        }
    }
}
