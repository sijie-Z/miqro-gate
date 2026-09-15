package com.miqroera.miqrokey.controlplane.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ingest contract of the built-in retention consumer (ADR-0014 §8): envelope
 * JSON maps to row parameters with direction defaulting to INPUT, base64
 * ciphertext is decoded, duplicates are no-ops, malformed input is counted but
 * never fatal.
 */
@DisplayName("Retention log ingest (envelope JSON -> retention_log)")
class RetentionLogIngestServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();

    private static String envelope(String directionFragment) {
        String ciphertext = Base64.getEncoder().encodeToString("cipher".getBytes(StandardCharsets.UTF_8));
        String nonce = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        return "{\"eventId\":\"" + EVENT_ID + "\",\"tenantId\":\"" + TENANT_ID + "\",\"userId\":\"" + USER_ID
                + "\",\"virtualKeyId\":\"" + KEY_ID + "\",\"wireProtocol\":\"OPENAI_CHAT\"," + directionFragment
                + "\"gatewayRequestId\":\"req-1\",\"occurredAt\":\"" + Instant.now()
                + "\",\"keyVersion\":\"v1\",\"textCharCount\":5,\"truncated\":false," + "\"ciphertext\":\"" + ciphertext
                + "\",\"nonce\":\"" + nonce + "\"}";
    }

    @Test
    @DisplayName("a valid OUTPUT envelope inserts decoded parameters")
    void ingestsOutputEnvelope() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        RetentionLogIngestService service = new RetentionLogIngestService(jdbc, new ObjectMapper());

        boolean inserted = service.ingest(envelope("\"direction\":\"OUTPUT\","));

        assertThat(inserted).isTrue();
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        org.mockito.Mockito.verify(jdbc).update(anyString(), captor.capture());
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertThat(params.getValue("direction")).isEqualTo("OUTPUT");
        assertThat(params.getValue("eventId")).isEqualTo(EVENT_ID);
        assertThat((byte[]) params.getValue("ciphertext")).isEqualTo("cipher".getBytes(StandardCharsets.UTF_8));
        assertThat(params.getValue("textCharCount")).isEqualTo(5);
        assertThat(service.malformedCount()).isZero();
    }

    @Test
    @DisplayName("an envelope without direction (older producer) defaults to INPUT")
    void directionDefaultsToInput() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        RetentionLogIngestService service = new RetentionLogIngestService(jdbc, new ObjectMapper());

        assertThat(service.ingest(envelope(""))).isTrue();
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        org.mockito.Mockito.verify(jdbc).update(anyString(), captor.capture());
        assertThat(((MapSqlParameterSource) captor.getValue()).getValue("direction")).isEqualTo("INPUT");
    }

    @Test
    @DisplayName("a duplicate (ON CONFLICT no-op) is not an error")
    void duplicateIsNoop() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        RetentionLogIngestService service = new RetentionLogIngestService(jdbc, new ObjectMapper());

        assertThat(service.ingest(envelope("\"direction\":\"INPUT\","))).isFalse();
        assertThat(service.malformedCount()).isZero();
    }

    @Test
    @DisplayName("missing fields, bad base64 and bad JSON are counted and skipped")
    void malformedEnvelopesAreCounted() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetentionLogIngestService service = new RetentionLogIngestService(jdbc, new ObjectMapper());

        assertThat(service.ingest("{\"eventId\":\"" + EVENT_ID + "\"}")).isFalse(); // missing fields
        assertThat(service.ingest("{\"eventId\":\"not-a-uuid\",\"tenantId\":\"" + TENANT_ID + "\",\"userId\":\""
                + USER_ID + "\",\"virtualKeyId\":\"" + KEY_ID
                + "\",\"wireProtocol\":\"OPENAI_CHAT\",\"gatewayRequestId\":\"r\",\"occurredAt\":\"" + Instant.now()
                + "\",\"keyVersion\":\"v1\",\"ciphertext\":\"x\",\"nonce\":\"y\"}")).isFalse(); // bad uuid
        assertThat(service.ingest(envelope("").replace("Y2lwaGVy", "!!!"))).isFalse(); // bad base64
        assertThat(service.ingest("not json at all")).isFalse();
        assertThat(service.malformedCount()).isEqualTo(4);
    }
}
