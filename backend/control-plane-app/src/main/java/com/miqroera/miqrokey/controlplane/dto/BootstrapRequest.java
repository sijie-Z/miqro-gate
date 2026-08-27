package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BootstrapRequest(@NotBlank @Size(min = 16, max = 1024) String bootstrapSecret,
        @NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 256) String displayName) {

    @Override
    public String toString() {
        return "BootstrapRequest[username=" + username + ", bootstrapSecret=****]";
    }
}
