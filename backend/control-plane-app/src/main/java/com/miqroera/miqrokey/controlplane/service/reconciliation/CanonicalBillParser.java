package com.miqroera.miqrokey.controlplane.service.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LineError;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Parsed;

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
        Long input = longValue(node, "input_tokens");
        Long output = longValue(node, "output_tokens");
        Long cacheRead = longValue(node, "cache_read_tokens");
        String amount = text(node, "amount");
        String currency = text(node, "currency");
        String status = text(node, "status");
        String rowRef = text(node, "provider_row_ref");

        if (amount == null) {
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

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isIntegralNumber() ? value.asLong() : null;
    }
}
