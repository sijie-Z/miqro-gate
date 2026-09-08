package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tolerant OpenAPI→tool-spec parser for the F17 batch import: walks
 * {@code paths.*.<method>} operations, derives the snake_case tool name from
 * {@code operationId} (falling back to the path when absent or invalid), takes
 * {@code summary}/{@code description} as the description and keeps the raw
 * path/method. Unknown vendor extensions and unsupported HTTP methods are
 * ignored with a skip note instead of failing the whole import.
 */
public final class ToolOpenApiParser {

    private static final Set<String> SUPPORTED_METHODS = Set.of("get", "post", "put", "delete", "patch");
    private static final int MAX_ITEMS = 100;
    private static final int MAX_DESCRIPTION = 2000;
    private static final int MAX_TOOL_NAME = 128;

    private ToolOpenApiParser() {
    }

    /** One tool derived from an OpenAPI operation, ready for validation. */
    public record ToolSpec(String toolName, String description, String method, String path) {
    }

    /** A tool that could not be derived; imports continue past it. */
    public record SkipNote(String toolName, String reason) {
    }

    public record Parsed(List<ToolSpec> tools, List<SkipNote> skipped) {
    }

    public static Parsed parse(JsonNode spec) {
        if (spec == null || !spec.isObject() || !spec.has("paths") || !spec.get("paths").isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SPEC_INVALID", "需提供含 paths 的 OpenAPI JSON 对象。");
        }
        List<ToolSpec> tools = new ArrayList<>();
        List<SkipNote> skipped = new ArrayList<>();
        JsonNode paths = spec.get("paths");
        paths.fieldNames().forEachRemaining(path -> {
            JsonNode pathNode = paths.get(path);
            if (!pathNode.isObject()) {
                return;
            }
            pathNode.fieldNames().forEachRemaining(method -> {
                String lower = method.toLowerCase(Locale.ROOT);
                JsonNode op = pathNode.get(method);
                if (!op.isObject()) {
                    return;
                }
                if (!SUPPORTED_METHODS.contains(lower)) {
                    skipped.add(new SkipNote("", "HTTP 方法不支持：" + method.toUpperCase(Locale.ROOT)));
                    return;
                }
                String operationId = text(op.get("operationId"));
                String derived = toSnakeName(operationId);
                if (derived == null) {
                    derived = toSnakeName(path);
                }
                if (derived == null || derived.length() > MAX_TOOL_NAME) {
                    skipped.add(new SkipNote(operationId == null ? "" : operationId, "无法从 operationId/路径派生合法工具名"));
                    return;
                }
                String summary = text(op.get("summary"));
                String description = text(op.get("description"));
                String desc = summary != null ? summary : description;
                if (desc != null && desc.length() > MAX_DESCRIPTION) {
                    desc = desc.substring(0, MAX_DESCRIPTION);
                }
                tools.add(new ToolSpec(derived, desc, lower.toUpperCase(Locale.ROOT), path));
                if (tools.size() > MAX_ITEMS) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_TOOLS", "单次最多导入 " + MAX_ITEMS + " 个工具。");
                }
            });
        });
        return new Parsed(tools, skipped);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** camelCase / dash / slash / spaces → snake_case; null when unusable. */
    private static String toSnakeName(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(0) == '_') {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        String name = sb.toString();
        if (name.isEmpty() || !name.matches("[a-z][a-z0-9_]*")) {
            return null;
        }
        return name;
    }
}
