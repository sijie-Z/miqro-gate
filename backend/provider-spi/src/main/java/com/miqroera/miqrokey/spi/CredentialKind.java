package com.miqroera.miqrokey.spi;

/**
 * What kind of secret a provider product consumes. A product definition with
 * {@code NONE} means the upstream accepts unauthenticated requests (rare and
 * must be explicitly allowed by the signed catalog).
 */
public enum CredentialKind {

    /** Static API key supplied in a header. */
    API_KEY,

    /** OAuth 2.0 client-credentials exchange, typically client id + secret. */
    OAUTH2_CLIENT_CREDENTIALS,

    /** No credential required. */
    NONE,
}
