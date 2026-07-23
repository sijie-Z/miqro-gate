package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=****]";
    }
}
