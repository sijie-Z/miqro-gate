package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the normalized cache key for a request.
 *
 * <p>
 * Key = SHA-256 of
 * {@code tenantId|projectId|virtualKeyId|productId|model|purpose|scope} where
 * {@code scope} is the <em>semantic scope</em> of the conversation: the system
 * prompt plus the <b>last user message</b> (aligned with Tencent's "latest user
 * message" and Higress's GJSON content extraction — see
 * docs/ai-gateway-comparison.md). Earlier conversation turns do not change the
 * key, so a repeated question inside different histories still hits the cache.
 * </p>
 *
 * <p>
 * When the body is not a recognized chat shape (no extractable user message),
 * the scope falls back to the full normalized body — the previous behavior — so
 * non-chat payloads stay safe.
 * </p>
 *
 * <p>
 * The gateway NEVER re-emits the normalized JSON upstream: the raw request
 * bytes are forwarded untouched. Extraction exists only for key derivation.
 * </p>
 */
@Component
public final class CacheKeyFactory {

    /**
     * Request fields that do not affect the provider's output and are stripped
     * before key derivation.
     */
    private static final Set<String> STRIP_FIELDS = Set.of("stream", "stream_options", "metadata", "user");

    private final ObjectMapper objectMapper;

    public CacheKeyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Computes the cache key. Chat-shaped bodies use the semantic scope (system +
     * last user message); anything else falls back to the full normalized body.
     */
    public CacheKey compute(AuthContext ctx, String modelName, byte[] body) {
        String scope = semanticScope(body);
        String normalized = scope.isEmpty() ? normalize(body) : scope;
        String canonical = ctx.tenantId() + "|" + ctx.projectId() + "|" + ctx.key().keyId() + "|" + ctx.productId()
                + "|" + (modelName == null ? "" : modelName) + "|"
                + (ctx.key().purpose() == null ? "" : ctx.key().purpose()) + "|" + normalized;
        return CacheKey.from(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Extracts the semantic scope of a chat request: {@code "<system>|<last
     * user message>"}. Handles OpenAI chat, Anthropic messages, and OpenAI
     * Responses ({@code input}) shapes, including array-form content parts. Returns
     * the empty string when no user message is extractable, which makes the caller
     * fall back to the full-body key.
     */
    public String semanticScope(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return "";
            }
            JsonNode messages = root.has("messages") ? root.get("messages") : root.get("input");
            if (messages == null || !messages.isArray()) {
                return "";
            }
            String system = "";
            String lastUser = "";
            for (JsonNode msg : messages) {
                // OpenAI Responses allows plain strings in the input array;
                // treat them as user turns.
                if (msg.isTextual()) {
                    String text = msg.asText();
                    if (!text.isEmpty()) {
                        lastUser = text;
                    }
                    continue;
                }
                String role = msg.path("role").asText("");
                String content = textContent(msg.get("content"));
                if ("system".equals(role) && system.isEmpty() && !content.isEmpty()) {
                    system = content;
                } else if ("user".equals(role) && !content.isEmpty()) {
                    lastUser = content;
                }
            }
            if (lastUser.isEmpty()) {
                return "";
            }
            return system + "|" + lastUser;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts plain text from a message content node: a string, or an array of
     * content parts ({@code {"type":"text","text":...}}), or plain strings.
     */
    private static String textContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if ("text".equals(part.path("type").asText(""))) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Normalizes the request body for key derivation: strips {@link #STRIP_FIELDS},
     * sorts object keys recursively, removes insignificant whitespace. Returns the
     * empty string when the body is not valid JSON.
     */
    public String normalize(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || root.isNull()) {
                return "";
            }
            JsonNode stripped = root.deepCopy();
            if (stripped.isObject()) {
                ObjectNode clean = objectMapper.createObjectNode();
                stripped.fields().forEachRemaining(entry -> {
                    if (!STRIP_FIELDS.contains(entry.getKey())) {
                        clean.set(entry.getKey(), entry.getValue());
                    }
                });
                stripped = clean;
            }
            // ORDER_MAP_ENTRIES_BY_KEYS does not apply to JsonNode trees, so
            // the sort must be done explicitly; arrays keep their order.
            return objectMapper.writeValueAsString(sortKeysRecursively(stripped));
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonNode sortKeysRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> field : fields) {
                sorted.set(field.getKey(), sortKeysRecursively(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> sorted.add(sortKeysRecursively(item)));
            return sorted;
        }
        // Value nodes are immutable; sharing them inside the new containers is
        // safe.
        return node;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
