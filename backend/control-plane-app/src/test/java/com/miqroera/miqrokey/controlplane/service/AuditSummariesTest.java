package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit summary JSON escaping (#447). */
@DisplayName("AuditSummaries")
class AuditSummariesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("control characters and quotes survive a round-trip (#447)")
    void controlCharactersAreEscaped() throws Exception {
        String hostile = "a\nb\tc\"d";
        JsonNode parsed = objectMapper.readTree(AuditSummaries.summary("name", hostile));
        assertThat(parsed.get("name").asText()).isEqualTo(hostile);
    }

    @Test
    @DisplayName("crafted text cannot forge sibling members (#447)")
    void craftedTextCannotForgeMembers() throws Exception {
        String crafted = "x\",\"role\":\"SYSTEM_ADMIN";
        JsonNode parsed = objectMapper.readTree(AuditSummaries.summary("username", crafted));
        assertThat(parsed.get("username").asText()).isEqualTo(crafted);
        assertThat(parsed.get("role")).isNull();
    }
}
