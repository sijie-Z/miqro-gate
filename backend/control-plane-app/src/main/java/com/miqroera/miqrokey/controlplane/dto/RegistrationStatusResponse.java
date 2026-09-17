package com.miqroera.miqrokey.controlplane.dto;

/**
 * Public, read-only self-registration switch state.
 *
 * <p>
 * Deliberately a single boolean: the login page needs to know whether to offer
 * the self-service registration entry before the user fills in the form, and
 * nothing else about the deployment may leak through an anonymous endpoint.
 * </p>
 */
public record RegistrationStatusResponse(boolean enabled) {
}
