package com.miqroera.miqrokey.domain.model;

import java.util.UUID;

/**
 * A single allowed model within a project provider grant. Model IDs are exact
 * and case-sensitive.
 */
public record ProjectProviderGrantModel(UUID grantId, String modelId) {
}
