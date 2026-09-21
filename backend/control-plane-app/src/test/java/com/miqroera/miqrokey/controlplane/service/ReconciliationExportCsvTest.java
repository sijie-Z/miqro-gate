package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reconciliation CSV export shape: the #430 formula-injection guard and RFC
 * 4180 quoting for cells, plus the single declared column list (#754) the
 * header and every data row are built from — a duplicated or missing entry is
 * exactly what would silently shift cells under the right names.
 */
@DisplayName("Reconciliation CSV export")
class ReconciliationExportCsvTest {

    @Test
    @DisplayName("formula-leading cells get an apostrophe prefix; bill text is provider-controlled")
    void formulaLeadingCellsAreNeutralized() {
        assertThat(ReconciliationService.csvCell("=SUM(A1:A2)")).isEqualTo("'=SUM(A1:A2)");
        assertThat(ReconciliationService.csvCell("@ref")).isEqualTo("'@ref");
        assertThat(ReconciliationService.csvCell("\tcmd")).isEqualTo("'\tcmd");
        // Signed payloads that only *start* like a number are still formulas.
        assertThat(ReconciliationService.csvCell("-1+1")).isEqualTo("'-1+1");
        assertThat(ReconciliationService.csvCell("-cmd|' /C calc'!A0")).isEqualTo("'-cmd|' /C calc'!A0");
        assertThat(ReconciliationService.csvCell("+cmd|' /C calc'!A0")).isEqualTo("'+cmd|' /C calc'!A0");
        // CR is a line break, so RFC 4180 quoting wraps the already-guarded cell.
        // The apostrophe still leads the field content, so the formula stays inert.
        assertThat(ReconciliationService.csvCell("\r=cmd")).isEqualTo("\"'\r=cmd\"");
    }

    @Test
    @DisplayName("a signed decimal literal keeps its value — the amount column is data, not a formula")
    void signedDecimalsAreNotGuarded() {
        // The page renders the raw detail value, so guarding these would make the
        // export disagree with the console and turn the amount column into text.
        assertThat(ReconciliationService.csvCell("-1.50")).isEqualTo("-1.50");
        assertThat(ReconciliationService.csvCell("+1")).isEqualTo("+1");
        assertThat(ReconciliationService.csvCell("-12.34")).isEqualTo("-12.34");
        assertThat(ReconciliationService.csvCell("3")).isEqualTo("3");
        assertThat(ReconciliationService.csvCell("1.5e-3")).isEqualTo("1.5e-3");
        // Near-misses stay guarded: the literal must be the whole cell.
        assertThat(ReconciliationService.csvCell("-1.50 ")).isEqualTo("'-1.50 ");
        assertThat(ReconciliationService.csvCell("-1,50")).isEqualTo("\"'-1,50\"");
        assertThat(ReconciliationService.csvCell("=1")).isEqualTo("'=1");
    }

    @Test
    @DisplayName("structural quoting follows RFC 4180 with the guard applied first")
    void structuralQuoting() {
        assertThat(ReconciliationService.csvCell("plain")).isEqualTo("plain");
        assertThat(ReconciliationService.csvCell("")).isEmpty();
        assertThat(ReconciliationService.csvCell(null)).isEmpty();
        assertThat(ReconciliationService.csvCell("has, comma")).isEqualTo("\"has, comma\"");
        assertThat(ReconciliationService.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ReconciliationService.csvCell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(ReconciliationService.csvCell("=a,b")).isEqualTo("\"'=a,b\"");
    }

    @Test
    @DisplayName("declared columns are unique, identity-first, detail-flattened last")
    void declaredColumnOrder() {
        List<String> columns = ReconciliationService.EXPORT_COLUMNS;
        assertThat(new HashSet<>(columns)).hasSameSizeAs(columns);
        assertThat(columns.subList(0, 7)).containsExactly("report_id", "provider_code", "row_no", "verdict",
                "matched_by", "provider_row_ref", "local_ref");
        assertThat(columns.subList(7, columns.size())).allMatch(column -> column.startsWith("detail_"));
        assertThat(columns).contains("detail_bucket_key", "detail_provider_count", "detail_local_count");
        // Every declared detail column must name the JSON key it reads: the
        // snake_case header is the export's, the camelCase key the stored one.
        assertThat(ReconciliationService.DETAIL_KEYS.keySet())
                .containsExactlyInAnyOrderElementsOf(columns.subList(7, columns.size()));
    }

    @Test
    @DisplayName("detail cells read the declared camelCase key; absent keys stay empty, undeclared columns fail")
    void detailCellsReadDeclaredKeys() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("detail", new ObjectMapper()
                .readTree("{\"modelId\":\"m-1\",\"amount\":\"1.00\",\"occurredAt\":\"2026-09-10T00:00:00Z\"}"));
        Map<String, Object> report = Map.of();

        assertThat(ReconciliationService.exportCell(row, report, "detail_model_id")).isEqualTo("m-1");
        assertThat(ReconciliationService.exportCell(row, report, "detail_amount")).isEqualTo("1.00");
        assertThat(ReconciliationService.exportCell(row, report, "detail_occurred_at"))
                .isEqualTo("2026-09-10T00:00:00Z");
        // A verdict that does not carry the key yields an empty cell, never a shifted
        // one.
        assertThat(ReconciliationService.exportCell(row, report, "detail_bucket_key")).isEmpty();
        assertThat(ReconciliationService.exportCell(row, report, "detail_provider_count")).isEmpty();
        assertThat(ReconciliationService.exportCell(row, report, "detail_local_count")).isEmpty();
        assertThatThrownBy(() -> ReconciliationService.exportCell(row, report, "detail_nope"))
                .isInstanceOf(IllegalStateException.class);
    }
}
