package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminRetentionLogView;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin retention reader contract (ADR-0014 §8): decrypt + MD5 for the
 * authorized view, graceful null on undecryptable rows, filter validation and
 * RFC-4180 CSV escaping.
 */
@DisplayName("Admin retention log service")
class AdminRetentionLogServiceTest {

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

    @Test
    @DisplayName("query decrypts rows, prefers display name and computes the data MD5")
    void queryDecryptsAndHashes() throws Exception {
        byte[] plain = "the reply text".getBytes(StandardCharsets.UTF_8);
        AdminRetentionLogService service = new AdminRetentionLogService(jdbcReturning(List.of(rawRow(plain, "演示用户"))),
                provider(new FakeCrypto()));

        List<AdminRetentionLogView> views = service.query(TENANT, null, null, null, null, null, 1, 20);

        assertThat(views).hasSize(1);
        AdminRetentionLogView view = views.get(0);
        assertThat(view.text()).isEqualTo("the reply text");
        assertThat(view.userName()).isEqualTo("演示用户");
        assertThat(view.direction()).isEqualTo("OUTPUT");
        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(plain));
        assertThat(view.dataMd5()).isEqualTo(expectedMd5);
    }

    @Test
    @DisplayName("an undecryptable row surfaces with null text instead of failing the page")
    void undecryptableRowYieldsNullText() {
        KeyEncryptionProvider throwing = new FakeCrypto() {
            @Override
            public byte[] decrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
                throw new IllegalStateException("retired key version");
            }
        };
        AdminRetentionLogService service = new AdminRetentionLogService(
                jdbcReturning(List.of(rawRow("x".getBytes(StandardCharsets.UTF_8), null))), provider(throwing));

        List<AdminRetentionLogView> views = service.query(TENANT, null, null, null, null, null, 1, 20);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).text()).isNull();
        assertThat(views.get(0).dataMd5()).isNull();
        assertThat(views.get(0).userName()).isEqualTo("user-1"); // falls back to username
    }

    @Test
    @DisplayName("page is 1-based like every other paginated list endpoint: page=1 binds offset 0")
    void pageIsOneBasedLikeSiblingEndpoints() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AdminRetentionLogService.RawRow>>any())).thenReturn(List.of());
        AdminRetentionLogService service = new AdminRetentionLogService(jdbc, provider(new FakeCrypto()));

        // Contract (docs/api-contract.md:266, :567) fixes `page` as 默认 1, ≥1; the
        // sibling
        // usage-records endpoints pin the same meaning
        // (UsageStatsServiceTest.recordsScalesOffsetWithPage:
        // page=3/size=25 -> offset 50, and page=0 -> 400 PAGE_INVALID).
        service.query(TENANT, null, null, null, null, null, 1, 20);

        org.mockito.ArgumentCaptor<SqlParameterSource> params = org.mockito.ArgumentCaptor
                .forClass(SqlParameterSource.class);
        org.mockito.Mockito.verify(jdbc).query(anyString(), params.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AdminRetentionLogService.RawRow>>any());
        assertThat(params.getValue().getValue("offset"))
                .as("page=1 must be the FIRST page (offset 0), not the second page").isEqualTo(0L);
    }

    @Test
    @DisplayName("page below 1 is rejected with PAGE_INVALID instead of silently clamping to the first page")
    void pageBelowOneIsRejected() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AdminRetentionLogService service = new AdminRetentionLogService(jdbc, provider(new FakeCrypto()));

        assertThatThrownBy(() -> service.query(TENANT, null, null, null, null, null, 0, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PAGE_INVALID"));
    }

    @Test
    @DisplayName("an invalid direction filter is rejected as 400 PARAM_INVALID before touching the database")
    void invalidDirectionRejected() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AdminRetentionLogService service = new AdminRetentionLogService(jdbc, provider(new FakeCrypto()));

        // The sibling filter on the same endpoint (`from=not-a-timestamp`) answers
        // 400 PARAM_INVALID; a misspelled direction has to answer the same way.
        // A bare ResponseStatusException would be swallowed by the advice's
        // catch-all and leak out as 500 INTERNAL_ERROR.
        assertThatThrownBy(() -> service.query(TENANT, null, "SIDEWAYS", null, null, null, 1, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("PARAM_INVALID");
                });
    }

    @Test
    @DisplayName("CSV cells escape commas, quotes and newlines per RFC 4180")
    void csvCellEscaping() {
        assertThat(AdminRetentionLogService.csvCell("plain")).isEqualTo("plain");
        assertThat(AdminRetentionLogService.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AdminRetentionLogService.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AdminRetentionLogService.csvCell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(AdminRetentionLogService.csvCell(null)).isEmpty();
    }

    @Test
    @DisplayName("export renders a header plus escaped content rows")
    void exportRendersCsv() {
        byte[] plain = "报告，含\"引号\"".getBytes(StandardCharsets.UTF_8);
        AdminRetentionLogService service = new AdminRetentionLogService(jdbcReturning(List.of(rawRow(plain, null))),
                provider(new FakeCrypto()));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        AdminRetentionLogService.ExportSummary result;
        try {
            result = service.streamCsv(TENANT, null, null, null, null, null, out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        assertThat(result.rows()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        String[] lines = out.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).startsWith("\uFEFFevent_id,occurred_at,user_id,user_name")
                .as("UTF-8 BOM precedes the header for spreadsheet consumers");
        assertThat(lines[0]).endsWith("data_md5,content");
        assertThat(lines[1]).contains("\"报告，含\"\"引号\"\"\"");
    }

    @Test
    @DisplayName("#1023: the export reads bounded pages instead of materialising the table")
    void exportReadsInBoundedChunks() {
        // What this pins: the export used to ask for EXPORT_LIMIT + 1 rows in a single
        // query and build the whole document in memory before writing a byte. Memory is
        // bounded by the page size now — the stub answers one full page and then an empty
        // page, and the recorded LIMIT proves no read ever asks for more than a chunk.
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<AdminRetentionLogService.RawRow> fullPage = new ArrayList<>();
        for (int i = 0; i < AdminRetentionLogService.EXPORT_CHUNK; i++) {
            fullPage.add(rawRow("row".getBytes(StandardCharsets.UTF_8), null));
        }
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AdminRetentionLogService.RawRow>>any()))
                .thenReturn(fullPage, List.of());
        AdminRetentionLogService service = new AdminRetentionLogService(jdbc, provider(new FakeCrypto()));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        AdminRetentionLogService.ExportSummary summary;
        try {
            summary = service.streamCsv(TENANT, null, null, null, null, null, out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        assertThat(summary.rows()).isEqualTo(AdminRetentionLogService.EXPORT_CHUNK);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).query(anyString(), params.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AdminRetentionLogService.RawRow>>any());
        for (SqlParameterSource captured : params.getAllValues()) {
            assertThat(((MapSqlParameterSource) captured).getValue("limit"))
                    .as("every read is one page, never the whole export")
                    .isEqualTo(AdminRetentionLogService.EXPORT_CHUNK);
        }
    }
}
