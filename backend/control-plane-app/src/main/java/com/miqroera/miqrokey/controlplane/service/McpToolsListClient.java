package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the upstream {@code tools/list} JSON-RPC method for the MCP tool sync
 * (issue #344, raw doc 03): a direct POST without a prior initialize handshake,
 * matching the vendor quickstart. Response bodies are capped before parsing and
 * every failure surfaces as a sanitized {@code TOOLS_SYNC_UPSTREAM_FAILED} —
 * the message never contains the URL, request headers or response content.
 */
@Component
public class McpToolsListClient {

    /** Fixed JSON-RPC request; the sync never forwards caller input upstream. */
    static final String REQUEST_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final int MAX_TOOLS = 1000;
    /** Mirrors the mcp_tools.description column width; longer text is truncated. */
    static final int MAX_DESCRIPTION_CHARS = 2000;

    /**
     * One upstream entry; {@code name} is untrusted and validated by the caller.
     */
    public record UpstreamTool(String name, String description) {
    }

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    @Autowired
    public McpToolsListClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(30));
    }

    McpToolsListClient(ObjectMapper objectMapper, HttpClient http, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    /** Fetches and validates the upstream tool list; {@code bearer} is optional. */
    public List<UpstreamTool> fetchTools(String endpoint, String bearer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(endpoint)).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(REQUEST_BODY, StandardCharsets.UTF_8));
            if (bearer != null) {
                builder.header("Authorization", bearer);
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstream("上游返回 HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_BODY_BYTES) {
                throw upstream("上游响应超过 2MB 上限。");
            }
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                throw upstream("上游响应不是合法 JSON。");
            }
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw upstream("上游返回错误：" + truncate(error.path("message").asText("unknown"), 200));
            }
            JsonNode tools = root.path("result").path("tools");
            if (!tools.isArray()) {
                throw upstream("上游响应缺少 result.tools 数组。");
            }
            if (tools.size() > MAX_TOOLS) {
                throw upstream("上游工具数超过 " + MAX_TOOLS + " 上限。");
            }
            List<UpstreamTool> result = new ArrayList<>(tools.size());
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                String description = tool.path("description").asText("");
                result.add(new UpstreamTool(name.trim(),
                        description.isBlank() ? null : truncate(description, MAX_DESCRIPTION_CHARS)));
            }
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw upstream("上游 tools/list 调用失败：" + truncate(String.valueOf(e.getMessage()), 200));
        }
    }

    private static ApiException upstream(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "TOOLS_SYNC_UPSTREAM_FAILED", message);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
