package com.miqroera.miqrokey.controlplane.dto;

/**
 * Credential detail: the masked {@link CredentialView} plus the full version
 * history (newest first) for auditability of rotation and disable events.
 */
public record CredentialDetailView(CredentialView credential, java.util.List<CredentialVersionView> versions) {
}
