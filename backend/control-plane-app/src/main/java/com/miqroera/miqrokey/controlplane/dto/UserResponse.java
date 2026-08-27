package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;

public record UserResponse(String id, String username, String displayName, String role, String status,
        boolean mustChangePassword, Instant lastLoginAt, Instant sessionExpiresAt) {
}
