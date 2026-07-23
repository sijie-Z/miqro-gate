package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;

public record LoginResponse(String id, String username, String displayName, String role, boolean mustChangePassword,
        Instant sessionExpiresAt) {
}
