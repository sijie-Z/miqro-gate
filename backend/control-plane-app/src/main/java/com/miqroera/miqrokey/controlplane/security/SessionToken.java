package com.miqroera.miqrokey.controlplane.security;

/**
 * The pair of tokens generated when a session is created. The session token is
 * HttpOnly; the CSRF token is readable by JavaScript.
 */
public record SessionToken(String sessionToken, String csrfToken) {

    @Override
    public String toString() {
        return "SessionToken[sessionToken=****, csrfToken=****]";
    }
}
