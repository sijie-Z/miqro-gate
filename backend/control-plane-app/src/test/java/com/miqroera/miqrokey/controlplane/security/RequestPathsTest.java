package com.miqroera.miqrokey.controlplane.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the security-decision path normalization (issue #723): the
 * gate must see the same path the handler matcher routed on.
 */
@DisplayName("RequestPaths lookup-path normalization (#723)")
class RequestPathsTest {

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("keeps a plain path intact")
    void plainPathUnchanged() {
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin/users"))).isEqualTo("/api/v1/admin/users");
    }

    @Test
    @DisplayName("removes semicolon path parameters the way the handler matcher does")
    void stripsSemicolonContent() {
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin;x/users"))).isEqualTo("/api/v1/admin/users");
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin;a=b/users;v=1"))).isEqualTo("/api/v1/admin/users");
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin;x"))).isEqualTo("/api/v1/admin");
    }

    @Test
    @DisplayName("removes a percent-encoded semicolon that only appears after decoding")
    void stripsEncodedSemicolonAfterDecoding() {
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin%3Bx/users"))).isEqualTo("/api/v1/admin/users");
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin%3bx/users"))).isEqualTo("/api/v1/admin/users");
        assertThat(RequestPaths.lookupPath(request("/api/v1/admin%3Bx"))).isEqualTo("/api/v1/admin");
    }

    @Test
    @DisplayName("decodes percent-encoding before the prefix decision")
    void decodesPercentEncoding() {
        assertThat(RequestPaths.lookupPath(request("/api/v1/%61dmin/users"))).isEqualTo("/api/v1/admin/users");
    }

    @Test
    @DisplayName("collapses duplicate slashes, which the container normalizes before mapping")
    void collapsesDuplicateSlashes() {
        assertThat(RequestPaths.lookupPath(request("//api/v1/admin/users"))).isEqualTo("/api/v1/admin/users");
        assertThat(RequestPaths.lookupPath(request("/api/v1//admin//users"))).isEqualTo("/api/v1/admin/users");
    }

    @Test
    @DisplayName("falls back to / for an empty path")
    void emptyPathFallsBackToRoot() {
        assertThat(RequestPaths.lookupPath(request(""))).isEqualTo("/");
    }
}
