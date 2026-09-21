package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP error envelope must not let a client-supplied tool name forge members
 * inside the gateway's own JSON (#447).
 */
@DisplayName("MCP problem JSON")
class McpProblemJsonTest {

    @Test
    @DisplayName("a hostile tool name cannot inject envelope members (#447)")
    void hostileToolNameCannotForgeMembers() throws Exception {
        String hostile = "x\",\"mcp_access_denied\":true,\"x\":\"";
        String body = new String(
                McpProxyController.problemJson("mcp_tool_unavailable", "Tool is unknown or disabled: " + hostile),
                StandardCharsets.UTF_8);

        JsonNode parsed = new ObjectMapper().readTree(body);
        assertThat(parsed.path("error").path("mcp_access_denied").isMissingNode()).isTrue();
        assertThat(parsed.path("error").path("message").asText()).isEqualTo("Tool is unknown or disabled: " + hostile);
    }

    @Test
    @DisplayName("a control character in a tool name keeps the envelope valid JSON (#866)")
    void controlCharacterInToolNameKeepsEnvelopeValidJson() throws Exception {
        // The JSON-RPC body carries the tool name, and a JSON unicode escape
        // decodes to a raw control character that is echoed verbatim.
        for (int codePoint = 0x00; codePoint <= 0x1F; codePoint++) {
            String message = "Tool is unknown or disabled: tool" + (char) codePoint + "name";
            String body = new String(McpProxyController.problemJson("mcp_tool_unavailable", message),
                    StandardCharsets.UTF_8);

            JsonNode parsed = new ObjectMapper().readTree(body);
            assertThat(parsed.path("error").path("message").asText()).as("message round-trip for U+%04X", codePoint)
                    .isEqualTo(message);
        }
    }
}
