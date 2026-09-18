package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV escaping of the usage export (#816): RFC 4180 quoting plus the #430
 * spreadsheet formula-injection guard, mirroring {@code AuditExportQuoteTest}
 * on the audit export — this writer ships provider-controlled text
 * ({@code providerRequestId}) into a file that gets opened in spreadsheets.
 */
@DisplayName("usage export CSV escaping")
class UsageExportCsvEscapeTest {

    @Test
    @DisplayName("formula-leading cells get an apostrophe prefix")
    void formulaLeadingCellsAreNeutralized() {
        assertThat(ExportTaskService.quote("=cmd|' /C calc'!A0")).startsWith("'=");
        assertThat(ExportTaskService.quote("+1+1")).startsWith("'+");
        assertThat(ExportTaskService.quote("@SUM(A1)")).startsWith("'@");
        assertThat(ExportTaskService.quote("-2+3")).startsWith("'-");
    }

    @Test
    @DisplayName("separator, quote and line break are RFC 4180 quoted")
    void structuralCharactersAreQuoted() {
        assertThat(ExportTaskService.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(ExportTaskService.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(ExportTaskService.quote("a\nb")).isEqualTo("\"a\nb\"");
    }

    @Test
    @DisplayName("numbers stay numbers and blanks stay empty")
    void numbersAndNulls() {
        assertThat(ExportTaskService.quote(-123L)).isEqualTo("-123");
        assertThat(ExportTaskService.quote(null)).isEmpty();
        assertThat(ExportTaskService.quote("deepseek-flash")).isEqualTo("deepseek-flash");
    }
}
