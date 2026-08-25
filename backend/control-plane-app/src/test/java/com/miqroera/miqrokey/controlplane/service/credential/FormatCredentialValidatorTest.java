package com.miqroera.miqrokey.controlplane.service.credential;

import com.miqroera.miqrokey.domain.credential.CredentialSecretValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules of the default local credential secret validator. */
class FormatCredentialValidatorTest {

    private final CredentialSecretValidator validator = new FormatCredentialValidator();

    @Test
    void acceptsNormalSecrets() {
        assertThat(validator.validate("sk-ant-abcdef1234567890").valid()).isTrue();
        assertThat(validator.validate("x".repeat(8)).valid()).isTrue();
        assertThat(validator.validate("x".repeat(512)).valid()).isTrue();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(validator.validate(null).valid()).isFalse();
        assertThat(validator.validate("").valid()).isFalse();
        assertThat(validator.validate("   ").valid()).isFalse();
    }

    @Test
    void rejectsTooShortAndTooLong() {
        assertThat(validator.validate("x".repeat(7)).valid()).isFalse();
        assertThat(validator.validate("x".repeat(513)).valid()).isFalse();
    }

    @Test
    void rejectsControlCharacters() {
        assertThat(validator.validate("sk-key-abc\n123").valid()).isFalse();
        assertThat(validator.validate("sk-key-abc\t123").valid()).isFalse();
    }
}
