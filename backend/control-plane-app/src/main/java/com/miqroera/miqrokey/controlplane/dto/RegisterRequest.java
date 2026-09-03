package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Open self-registration request (F-REG): a username, optional display name and
 * the account password. Password policy (length/character classes/common
 * passwords) is enforced by the authentication service so failures carry a
 * stable {@code PASSWORD_INVALID} code.
 */
public record RegisterRequest(@NotBlank @Size(max = 128) String username, @Size(max = 128) String displayName,
        @NotBlank @Size(max = 128) String password) {
}
