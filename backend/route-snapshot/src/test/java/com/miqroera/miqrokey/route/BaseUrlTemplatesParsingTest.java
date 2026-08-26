package com.miqroera.miqrokey.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-protocol base URL template parsing (G3.x relay wiring): entries may carry
 * a {@code protocols} array or act as the single fallback.
 */
@DisplayName("base_url_templates protocol parsing")
class BaseUrlTemplatesParsingTest {

    private final JdbcRouteSnapshotLoader loader = new JdbcRouteSnapshotLoader(null,
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    @DisplayName("protocol-tagged entries map to per-protocol bases with a fallback")
    void parsesProtocolEntries() throws Exception {
        String json = "[{\"url\":\"https://api.example.com/v4\",\"protocols\":[\"OPENAI_COMPATIBLE\"]},"
                + "{\"url\":\"https://api.example.com/anthropic\",\"protocols\":[\"ANTHROPIC_MESSAGES\"]},"
                + "{\"url\":\"https://api.example.com/fallback\"}]";

        Object parsed = invoke("parseBaseUrls", json);
        String single = (String) invokeOn(parsed, "single");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> byProtocol = (java.util.Map<String, String>) invokeOn(parsed, "byProtocol");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> uris = (java.util.Map<String, Object>) invokeOn(parsed, "byProtocolUris");

        assertThat(single).isEqualTo("https://api.example.com/fallback");
        assertThat(byProtocol).containsEntry("OPENAI_COMPATIBLE", "https://api.example.com/v4")
                .containsEntry("ANTHROPIC_MESSAGES", "https://api.example.com/anthropic");
        assertThat(uris.get("OPENAI_COMPATIBLE").toString()).isEqualTo("https://api.example.com/v4");
    }

    @Test
    @DisplayName("plain text entries and unknown structures degrade to empty or single")
    void toleratesPlainAndUnknownStructures() throws Exception {
        Object empty = invoke("parseBaseUrls", "[{\"url\":\"\"}]");
        assertThat(invokeOn(empty, "single")).isNull();
        assertThat((java.util.Map<?, ?>) invokeOn(empty, "byProtocol")).isEmpty();

        Object single = invoke("parseBaseUrls", "\"https://api.example.com/v1\"");
        assertThat(invokeOn(single, "single")).isEqualTo("https://api.example.com/v1");

        Object malformed = invoke("parseBaseUrls", "not-json");
        assertThat(invokeOn(malformed, "single")).isNull();
    }

    private Object invoke(String method, String arg) throws Exception {
        Method m = JdbcRouteSnapshotLoader.class.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return m.invoke(loader, arg);
    }

    private Object invokeOn(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
