package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A membership link between a team and a user.
 */
public record TeamMembership(UUID teamId, UUID userId, UUID createdBy, Instant createdAt) {
}
