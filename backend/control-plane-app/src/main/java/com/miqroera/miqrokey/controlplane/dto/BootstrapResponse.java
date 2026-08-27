package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;

public record BootstrapResponse(String userId, String username, String temporaryPassword, boolean shownOnce,
        Instant sessionExpiresAt) {

    @Override
    public String toString() {
        return "BootstrapResponse[userId=" + userId + ", username=" + username + ", temporaryPassword=****, shownOnce="
                + shownOnce + "]";
    }
}
