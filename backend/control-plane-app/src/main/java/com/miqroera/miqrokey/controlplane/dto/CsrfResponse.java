package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;

public record CsrfResponse(String token, Instant expiresAt) {
}
