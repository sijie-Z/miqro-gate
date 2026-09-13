package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error-envelope JSON validity with hostile model names (#447): control
 * characters must be escaped, not passed through raw.
 */
@DisplayName("ErrorEnvelopes")
class ErrorEnvelopesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("control characters in a message keep the envelope valid JSON (#447)")
    void controlCharactersAreEscaped() throws Exception {
        String message = "Model 'a\nb\tc\rd' is not allowed";
        String body = ErrorEnvelopes.body(new AuthFailureException(HttpStatus.FORBIDDEN, "model_not_allowed", message),
                "/v1/messages");

        JsonNode parsed = objectMapper.readTree(body);
        assertThat(parsed.path("error").path("message").asText()).isEqualTo(message);
        assertThat(parsed.path("error").path("type").asText()).isEqualTo("model_not_allowed");
    }
}
