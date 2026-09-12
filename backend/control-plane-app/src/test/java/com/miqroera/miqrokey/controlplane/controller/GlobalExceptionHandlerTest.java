package com.miqroera.miqrokey.controlplane.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantics of the global fallback handlers (#412): framework-level client
 * errors and unmapped database conflicts become typed 4xx problem responses —
 * never a bare 500 — and response details never leak SQL or internals.
 */
@DisplayName("Global exception handler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    @DisplayName("unmapped integrity conflicts are 409 without leaking SQL")
    void integrityConflict() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uq_secret_name\"");
        ResponseEntity<Map<String, Object>> response = handler.handleIntegrity(e, request);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> body = body(response);
        assertThat(body.get("code")).isEqualTo("RESOURCE_CONFLICT");
        assertThat(String.valueOf(body.get("detail"))).doesNotContain("uq_secret_name").doesNotContain("ERROR:");
    }

    @Test
    @DisplayName("deadlocks and lock failures are 409 CONCURRENT_MODIFICATION")
    void concurrencyConflict() {
        ResponseEntity<Map<String, Object>> response = handler
                .handleConcurrency(new ConcurrencyFailureException("deadlock detected"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).get("code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    @DisplayName("framework client errors map to 400 / 405 / 415 / 404")
    void frameworkClientErrors() {
        ResponseEntity<Map<String, Object>> missing = handler
                .handleMissingParam(new MissingServletRequestParameterException("status", "String"), request);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(body(missing).get("code")).isEqualTo("PARAM_INVALID");

        ResponseEntity<Map<String, Object>> method = handler
                .handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"), request);
        assertThat(method.getStatusCode().value()).isEqualTo(405);
        assertThat(body(method).get("code")).isEqualTo("METHOD_NOT_ALLOWED");

        ResponseEntity<Map<String, Object>> media = handler
                .handleMediaType(new HttpMediaTypeNotSupportedException("text/plain"), request);
        assertThat(media.getStatusCode().value()).isEqualTo(415);
        assertThat(body(media).get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        ResponseEntity<Map<String, Object>> noResource = handler
                .handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/api/v1/nope"), request);
        assertThat(noResource.getStatusCode().value()).isEqualTo(404);
        assertThat(body(noResource).get("code")).isEqualTo("NOT_FOUND");
    }
}
