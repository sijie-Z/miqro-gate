package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
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
 * {@code tenantId|projectId|virtualKeyId|productId|model|purpose|format|gen|scope}
 * where {@code format} is {@code stream=1/0} (a streamed SSE response must
 * never replay into a buffered JSON request, or vice versa — #444), {@code gen}
 * is a fingerprint of the output-shaping generation parameters present in the
 * body (temperature / top_p / top_k / max_tokens / penalties / reasoning effort
 * / thinking budget / … — chat bodies keep only the conversation scope below,
 * so these must be an explicit key dimension or two requests that differ only
 * in sampling configuration would share one entry), and {@code scope} is the
 * <em>semantic scope</em> of the conversation: the system prompt plus the
 * <b>last user message</b> (aligned with Tencent's "latest user message" and
 * Higress's GJSON content extraction — see docs/ai-gateway-comparison.md). The
 * system part covers chat {@code system} messages, the Anthropic top-level
 * {@code system} field, and the OpenAI Responses {@code instructions} field;
 * earlier conversation turns do not change the key, so a repeated question
 * inside different histories still hits the cache.
 * </p>
 *
 * <p>
 * When the body is not a recognized chat shape (no extractable user message),
 * the scope falls back to the full normalized body — the previous behavior — so
 * non-chat payloads stay safe. The same fallback covers multimodal content: a
 * message part that is not text (an image, a document, an audio clip, …) cannot
 * be represented in the scope, and a key built from the surrounding text alone
 * would be identical for two requests whose non-text payloads differ.
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
     * Request fields stripped from the <em>normalized body</em> before key
     * derivation. {@code stream} is stripped here but captured separately as the
     * explicit response-format dimension ({@link #streamFlag}) — removing it from
     * this set is not equivalent and would leave the semantic-scope path without a
     * format dimension (#444).
     */
    private static final Set<String> STRIP_FIELDS = Set.of("stream", "stream_options", "metadata", "user");

    /**
     * Body fields that shape the generated output without changing the
     * conversation. Captured as the {@code gen} key dimension so sampling
     * differences (temperature, token budgets, thinking budget, …) can never replay
     * each other's responses.
     */
    private static final List<String> GENERATION_FIELDS = List.of("temperature", "top_p", "top_k", "max_tokens",
            "max_output_tokens", "n", "seed", "stop", "frequency_penalty", "presence_penalty", "logit_bias",
            "response_format", "reasoning_effort", "thinking", "verbosity");

    private final ObjectMapper objectMapper;

    public CacheKeyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Computes the cache key. Chat-shaped bodies use the semantic scope (system +
     * last user message); anything else falls back to the full normalized body.
     */
    public CacheKey compute(AuthContext ctx, String modelName, byte[] body) {
        // Hot path: the body is already buffered by the caller and is never
        // mutated here, so it is parsed exactly once and the tree is shared by
        // every key dimension. Parsing per dimension made this method the most
        // expensive step of a cacheable request.
        JsonNode root = parse(body);
        String scope = semanticScope(root);
        String normalized = scope.isEmpty() ? normalize(root) : scope;
        String canonical = ctx.tenantId() + "|" + ctx.projectId() + "|" + ctx.key().keyId() + "|" + ctx.productId()
                + "|" + (modelName == null ? "" : modelName) + "|"
                + (ctx.key().purpose() == null ? "" : ctx.key().purpose()) + "|"
                + (streamFlag(root) ? "stream=1" : "stream=0") + "|" + generationFingerprint(root) + "|" + normalized;
        return CacheKey.from(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Parses the buffered body once. Returns {@code null} for an empty body or
     * anything that is not valid JSON; every caller treats a null tree as "this
     * dimension is absent", which is exactly what a failed parse used to yield.
     */
    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fingerprint of the output-shaping generation parameters present in the body,
     * in a fixed field order so the serialization is deterministic. Returns the
     * empty string when the body carries none (or is not JSON), keeping the
     * pre-existing key shape for such requests.
     */
    private String generationFingerprint(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "";
        }
        try {
            ObjectNode picked = objectMapper.createObjectNode();
            for (String field : GENERATION_FIELDS) {
                JsonNode value = root.get(field);
                if (value != null && !value.isNull()) {
                    picked.set(field, sortKeysRecursively(value));
                }
            }
            return picked.isEmpty() ? "" : "gen=" + objectMapper.writeValueAsString(picked);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The response-format dimension of the key: {@code stream:true} requests get a
     * different key than {@code stream:false} ones for the same conversation
     * (#444).
     */
    private boolean streamFlag(JsonNode root) {
        return root != null && root.path("stream").asBoolean(false);
    }

    /**
     * Extracts the semantic scope of a chat request: {@code "<system>|<last
     * user message>"}. Handles OpenAI chat, Anthropic messages, and OpenAI
     * Responses ({@code input}) shapes, including array-form content parts. The
     * system part is taken from a {@code system} message when present, else from
     * the Anthropic top-level {@code system} field and the OpenAI Responses
     * {@code instructions} field — ignoring those would let two requests with
     * different system prompts but the same last user message share a key. Returns
     * the empty string when no user message is extractable, which makes the caller
     * fall back to the full-body key.
     */
    public String semanticScope(byte[] body) {
        return semanticScope(parse(body));
    }

    /** Node-taking overload: the caller already parsed the body once. */
    private String semanticScope(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "";
        }
        try {
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
                if (content == null) {
                    // A part of this message is not text (image, document, …):
                    // the text alone would not identify the request.
                    return "";
                }
                if ("system".equals(role) && system.isEmpty() && !content.isEmpty()) {
                    system = content;
                } else if ("user".equals(role) && !content.isEmpty()) {
                    lastUser = content;
                }
            }
            if (system.isEmpty()) {
                // Anthropic carries the system prompt as a top-level field;
                // OpenAI Responses uses "instructions". Both accept a plain
                // string or an array of content parts.
                system = textContent(root.get("system"));
                if (system == null) {
                    return "";
                }
                if (system.isEmpty()) {
                    system = textContent(root.get("instructions"));
                    if (system == null) {
                        return "";
                    }
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
     *
     * @return the flattened text, or {@code null} when the node carries a part that
     *         is not text (an image, a document, an audio clip, …). Such a part
     *         changes the answer but has no textual representation, so the caller
     *         must fall back to the full-body key instead of keying on the
     *         surrounding text alone. Extra fields on a text part (for example
     *         Anthropic's {@code cache_control}) do not make it non-text.
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
                } else {
                    return null;
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
        return normalize(parse(body));
    }

    /** Node-taking overload: the caller already parsed the body once. */
    private String normalize(JsonNode root) {
        if (root == null || root.isNull()) {
            return "";
        }
        try {
            // deepCopy() so the shared tree handed in by compute() is never
            // touched by the strip/sort below.
            JsonNode stripped = root.deepCopy();
            if (stripped.isObject()) {
                ObjectNode clean = objectMapper.createObjectNode();
                stripped.properties().forEach(entry -> {
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
            fields.addAll(node.properties());
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
