package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

    @Test
    @DisplayName("every control character in a message keeps the envelope valid JSON (#866)")
    void allControlCharactersAreEscaped() throws Exception {
        // RFC 8259 §7: U+0000–U+001F MUST be escaped inside a JSON string. The
        // model name is client-supplied, and a JSON unicode escape such as the
        // one for backspace decodes to a raw control character that reaches
        // envelope — escaping only \n \r \t leaves the rest unparseable.
        for (int codePoint = 0x00; codePoint <= 0x1F; codePoint++) {
            String message = "Model 'denied" + (char) codePoint + "model' is not allowed";
            String body = ErrorEnvelopes.body(
                    new AuthFailureException(HttpStatus.FORBIDDEN, "model_not_allowed", message),
                    "/v1/chat/completions");

            JsonNode parsed = objectMapper.readTree(body);
            assertThat(parsed.path("error").path("message").asText()).as("message round-trip for U+%04X", codePoint)
                    .isEqualTo(message);
        }
    }
}
