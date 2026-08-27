package com.miqroera.miqrokey.controlplane.service.credential;

import com.miqroera.miqrokey.domain.credential.CredentialSecretValidator;
import org.springframework.stereotype.Component;

/**
 * Default local format validator: non-blank, 8..512 characters, no control
 * characters. Provider-specific validation adapters (G3.x) plug into the same
 * SPI to add a real upstream round-trip; this bean is the safe default until
 * then.
 */
@Component
public class FormatCredentialValidator implements CredentialSecretValidator {

    @Override
    public ValidationResult validate(String secret) {
        if (secret == null) {
            return ValidationResult.invalid("Credential secret must not be null");
        }
        if (secret.isBlank()) {
            return ValidationResult.invalid("Credential secret must not be blank");
        }
        if (secret.length() < 8) {
            return ValidationResult.invalid("Credential secret must be at least 8 characters");
        }
        if (secret.length() > 512) {
            return ValidationResult.invalid("Credential secret must be at most 512 characters");
        }
        for (int i = 0; i < secret.length(); i++) {
            if (Character.isISOControl(secret.charAt(i))) {
                return ValidationResult.invalid("Credential secret must not contain control characters");
            }
        }
        return ValidationResult.ok();
    }
}
