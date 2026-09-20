package com.miqroera.miqrokey.controlplane.service.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LineError;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Parsed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict JSONL parser for the canonical bill format (F19 contract draft v0).
 * Each non-empty line is one {@link BillLine}; malformed or incomplete lines
 * are collected with a stable error code and parsing continues, so a single bad
 * row never fails the whole file. Provider-specific export formats are
 * converted to this canonical shape by real parsers (WAITING_FOR_SAMPLE).
 */
public final class CanonicalBillParser {

    private static final String REQUIRED = "FIELD_REQUIRED";
    private static final String NOT_JSON = "LINE_NOT_JSON";
    private static final String BAD_TYPE = "FIELD_TYPE";
    private static final String BAD_TIME = "FIELD_TIME";
    private static final String EMPTY = "EMPTY_LINE";

    private final ObjectMapper objectMapper;

    public CanonicalBillParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Parsed parse(String content) {
        List<BillLine> lines = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return new Parsed(lines, errors);
        }
        String[] rawLines = content.split("\\R");
        for (int i = 0; i < rawLines.length; i++) {
            String raw = rawLines[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            int lineNumber = i + 1;
            try {
                JsonNode node = objectMapper.readTree(raw);
                if (node == null || !node.isObject()) {
                    errors.add(new LineError(lineNumber, NOT_JSON, "行不是 JSON 对象"));
                    continue;
                }
                lines.add(toBillLine(node, lineNumber, errors));
            } catch (Exception e) {
                errors.add(new LineError(lineNumber, NOT_JSON, "JSON 解析失败"));
            }
        }
        return new Parsed(lines, errors);
    }

    private BillLine toBillLine(JsonNode node, int lineNumber, List<LineError> errors) {
        String requestId = text(node, "provider_request_id");
        Instant occurredAt = time(node, "occurred_at", lineNumber, errors);
        String modelId = text(node, "model_id");
        String productCode = text(node, "provider_product_code");
        Long input = longValue(node, "input_tokens", lineNumber, errors);
        Long output = longValue(node, "output_tokens", lineNumber, errors);
        Long cacheRead = longValue(node, "cache_read_tokens", lineNumber, errors);
        String amount = decimal(node, "amount", lineNumber, errors);
        String currency = text(node, "currency");
        String status = text(node, "status");
        String rowRef = text(node, "provider_row_ref");

        if (absent(node, "amount")) {
            errors.add(new LineError(lineNumber, REQUIRED, "amount 必填"));
        }
        if (currency == null) {
            errors.add(new LineError(lineNumber, REQUIRED, "currency 必填"));
        }
        if (occurredAt == null) {
            errors.add(new LineError(lineNumber, BAD_TIME, "occurred_at 必填且为 UTC RFC3339"));
        }
        if (requestId == null && modelId == null && input == null) {
            errors.add(
                    new LineError(lineNumber, REQUIRED, "provider_request_id / model_id / input_tokens 至少一项(无任何匹配锚点)"));
        }
        return new BillLine(requestId, occurredAt, modelId, productCode, input, output, cacheRead, amount, currency,
                status, rowRef);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean absent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
    }

    /**
     * The contract declares {@code amount} as a decimal string
     * (docs/bill-reconciliation-contract.md) and the engine turns anything it
     * cannot parse into {@link BigDecimal#ZERO} while still counting the row as
     * {@code UNMATCHED_PROVIDER}. A value that is not a decimal is therefore a line
     * error here - it must never be silently dropped out of the amount gap. JSON
     * numbers keep their value (no precision is invented by stringifying them);
     * strings must be readable as a decimal; every other type is an error.
     */
    private static String decimal(JsonNode node, String field, int lineNumber, List<LineError> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isTextual()) {
            try {
                new BigDecimal(value.asText());
                // kept verbatim: the operator's literal is what the report echoes
                return value.asText();
            } catch (NumberFormatException ignored) {
                // reported below - not a decimal literal
            }
        }
        errors.add(new LineError(lineNumber, BAD_TYPE, field + " 不是 decimal string: " + rawValue(value)));
        return null;
    }

    private static String rawValue(JsonNode value) {
        String raw = value.toString();
        return raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
    }

    private static Instant time(JsonNode node, String field, int lineNumber, List<LineError> errors) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            errors.add(new LineError(lineNumber, BAD_TYPE, field + " 不是 RFC3339 时间"));
            return null;
        }
    }

    /**
     * The contract declares the token counts as {@code int}. A value of another
     * type used to become a silent {@code null}, which quietly removed a match
     * anchor from the row (and made the third matching level unreachable for it)
     * without a single line error - it is reported instead.
     */
    private static Long longValue(JsonNode node, String field, int lineNumber, List<LineError> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.asLong();
        }
        errors.add(new LineError(lineNumber, BAD_TYPE, field + " 不是整数: " + rawValue(value)));
        return null;
    }
}
