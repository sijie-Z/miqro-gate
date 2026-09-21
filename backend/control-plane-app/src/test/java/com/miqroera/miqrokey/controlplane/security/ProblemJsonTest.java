package com.miqroera.miqrokey.controlplane.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single envelope writer (#1011).
 *
 * <p>
 * These assertions are the reason the package no longer carries six escape
 * rules: the writer is small enough to pin exhaustively here, including the two
 * things every hand-rolled copy got wrong at least once — the C0 range, and
 * whether {@code detail} is present.
 * </p>
 */
@DisplayName("ProblemJson envelope (#445, #1011)")
class ProblemJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The JSON specials plus the C0 characters a header can carry through to
     * {@code getHeader}. Serializing must round-trip all of them.
     */
    private static final String HOSTILE = "a\tb\nc\rd\"e\\f g";

    @Test
    @DisplayName("a client-controlled value with C0 characters still parses and round-trips")
    void controlCharactersRoundTrip() throws Exception {
        String json = ProblemJson.of(403, "Forbidden", "IP_NOT_ALLOWED", null, HOSTILE);

        assertThat(json).as("a raw control character would make this unparseable").doesNotContain("\t");
        JsonNode parsed = MAPPER.readTree(json);
        assertThat(parsed.get("requestId").asText()).isEqualTo(HOSTILE);
        assertThat(parsed.get("code").asText()).isEqualTo("IP_NOT_ALLOWED");
    }

    @Test
    @DisplayName("detail is omitted when null — absence is not the same as JSON null")
    void omitsDetailWhenNull() throws Exception {
        JsonNode without = MAPPER.readTree(ProblemJson.of(403, "Forbidden", "IP_NOT_ALLOWED", null, "r-1"));
        assertThat(without.has("detail")).isFalse();

        JsonNode with = MAPPER
                .readTree(ProblemJson.of(401, "Unauthorized", "ADMIN_API_KEY_INVALID", "管理密钥缺失或无效", "r-2"));
        assertThat(with.get("detail").asText()).isEqualTo("管理密钥缺失或无效");
    }

    @Test
    @DisplayName("field order is the historical one, so text readers keep working")
    void fieldOrderIsStable() {
        assertThat(ProblemJson.of(403, "Forbidden", "IP_NOT_ALLOWED", null, "r-3"))
                .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                        + "\"code\":\"IP_NOT_ALLOWED\",\"requestId\":\"r-3\"}");
    }
}
