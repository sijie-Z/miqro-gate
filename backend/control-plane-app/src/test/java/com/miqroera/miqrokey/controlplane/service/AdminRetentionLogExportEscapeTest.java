package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminRetentionLogView;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Escaping contract of the retention-log CSV export (#430 family). The
 * {@code content} column carries the retained request/response payload and the
 * {@code user_name} column carries a display name — both are arbitrary text, so
 * this writer needs the same RFC 4180 quoting plus formula-injection guard as
 * the audit, reconciliation and usage exports.
 */
@DisplayName("Retention log CSV cell escaping")
class AdminRetentionLogExportEscapeTest {

    private static final UUID TENANT = UUID.randomUUID();

    /** Invertible stand-in: ciphertext carries the UTF-8 plaintext bytes. */
    private static class FakeCrypto implements KeyEncryptionProvider {
        @Override
        public EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId) {
            return new EncryptedSecret(plaintext.clone(), new byte[]{1}, "v1");
        }

        @Override
        public byte[] decrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return secret.ciphertext();
        }

        @Override
        public String activeKeyVersion() {
            return "v1";
        }

        @Override
        public EncryptedSecret reEncrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return secret;
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KeyEncryptionProvider> provider(KeyEncryptionProvider value) {
        ObjectProvider<KeyEncryptionProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static AdminRetentionLogService.RawRow rawRow(byte[] plain, String displayName) {
        return new AdminRetentionLogService.RawRow(UUID.randomUUID(), UUID.randomUUID(), "user-1", displayName,
                UUID.randomUUID(), "OPENAI_CHAT", "OUTPUT", "req-1", Instant.now(), "v1", plain, new byte[]{1},
                plain.length, false);
    }

    private static NamedParameterJdbcTemplate jdbcReturning(List<AdminRetentionLogService.RawRow> rows) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AdminRetentionLogService.RawRow>>any())).thenReturn(rows);
        return jdbc;
    }

    private static String exportOf(byte[] plain, String displayName) {
        AdminRetentionLogService service = new AdminRetentionLogService(
                jdbcReturning(List.of(rawRow(plain, displayName))), provider(new FakeCrypto()));
        return service.exportCsv(TENANT, null, null, null, null, null).csv();
    }

    @Test
    @DisplayName("formula-leading cells get an apostrophe prefix")
    void formulaLeadingCellsAreNeutralized() {
        assertThat(AdminRetentionLogService.csvCell("=cmd|' /C calc'!A0")).isEqualTo("'=cmd|' /C calc'!A0");
        assertThat(AdminRetentionLogService.csvCell("+1")).isEqualTo("'+1");
        assertThat(AdminRetentionLogService.csvCell("-x")).isEqualTo("'-x");
        assertThat(AdminRetentionLogService.csvCell("@cmd")).isEqualTo("'@cmd");
        assertThat(AdminRetentionLogService.csvCell("\tcmd")).isEqualTo("'\tcmd");
        assertThat(AdminRetentionLogService.csvCell("=HYPERLINK(\"http://evil\",\"click\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"");
    }

    @Test
    @DisplayName("ordinary cells keep their structural quoting")
    void ordinaryCellsUnchanged() {
        assertThat(AdminRetentionLogService.csvCell("plain")).isEqualTo("plain");
        assertThat(AdminRetentionLogService.csvCell("has, comma")).isEqualTo("\"has, comma\"");
        assertThat(AdminRetentionLogService.csvCell("has \"quote\"")).isEqualTo("\"has \"\"quote\"\"\"");
        assertThat(AdminRetentionLogService.csvCell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(AdminRetentionLogService.csvCell(null)).isEmpty();
    }

    @Test
    @DisplayName("the exported document starts with a UTF-8 BOM for spreadsheet consumers")
    void exportedDocumentCarriesBom() {
        String csv = exportOf("plain reply".getBytes(StandardCharsets.UTF_8), null);
        assertThat(csv).startsWith("\uFEFF");
    }

    @Test
    @DisplayName("a retained payload that looks like a formula reaches the CSV neutralized")
    void retainedPayloadIsNeutralizedInExport() {
        // Exactly what a gateway caller (or a malicious upstream model response)
        // can put into the retained body.
        String csv = exportOf("=cmd|' /C calc'!A0,A1".getBytes(StandardCharsets.UTF_8), null);
        assertThat(csv).contains("'=cmd|' /C calc'!A0,A1");
    }

    @Test
    @DisplayName("a display name that looks like a formula reaches the CSV neutralized")
    void displayNameIsNeutralizedInExport() {
        String csv = exportOf("plain".getBytes(StandardCharsets.UTF_8), "=1+1");
        assertThat(csv).contains("'=1+1");
    }

    @Test
    @DisplayName("view text round-trips through the same view object the export renders")
    void viewCarriesDecryptedText() {
        AdminRetentionLogService service = new AdminRetentionLogService(
                jdbcReturning(List.of(rawRow("=1+1".getBytes(StandardCharsets.UTF_8), null))),
                provider(new FakeCrypto()));
        List<AdminRetentionLogView> views = service.query(TENANT, null, null, null, null, null, 1, 20);
        assertThat(views.get(0).text()).isEqualTo("=1+1");
    }
}
