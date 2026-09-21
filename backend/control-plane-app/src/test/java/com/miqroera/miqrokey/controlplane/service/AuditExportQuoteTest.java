package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formula-injection guard of the audit CSV quoting (#430). The audit columns
 * are JSON-blob-first or server-controlled, so their CSV shape is structurally
 * immune — the guard is defense-in-depth for future summary shapes; structural
 * quoting (comma/quote/newline) is unchanged.
 */
@DisplayName("Audit CSV cell quoting")
class AuditExportQuoteTest {

    @Test
    @DisplayName("formula-leading cells get an apostrophe prefix")
    void formulaLeadingCellsAreNeutralized() {
        assertThat(AuditEventReadService.quote("=cmd|' /C calc'!A0, x")).isEqualTo("\"'=cmd|' /C calc'!A0, x\"");
        assertThat(AuditEventReadService.quote("+1")).isEqualTo("'+1");
        assertThat(AuditEventReadService.quote("-x")).isEqualTo("'-x");
        assertThat(AuditEventReadService.quote("@cmd")).isEqualTo("'@cmd");
        assertThat(AuditEventReadService.quote("\tcmd")).isEqualTo("'\tcmd");
    }

    @Test
    @DisplayName("ordinary cells keep their structural quoting")
    void ordinaryCellsUnchanged() {
        assertThat(AuditEventReadService.quote("plain")).isEqualTo("plain");
        assertThat(AuditEventReadService.quote("has, comma")).isEqualTo("\"has, comma\"");
        assertThat(AuditEventReadService.quote("{\"name\":\"alice\"}")).isEqualTo("\"{\"\"name\"\":\"\"alice\"\"}\"");
        assertThat(AuditEventReadService.quote(null)).isEmpty();
    }
}
