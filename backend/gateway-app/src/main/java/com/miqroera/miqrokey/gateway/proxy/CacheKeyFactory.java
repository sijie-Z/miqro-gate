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
 * {@code tenantId|projectId|virtualKeyId|productId|model|purpose|normalizedBody}.
 * Normalization strips client-only fields that do not influence the model
 * output, sorts object keys recursively, and strips whitespace — so
 * semantically identical requests produce identical keys.
 * </p>
 *
 * <p>
 * The gateway NEVER re-emits the normalized JSON upstream: the raw request
 * bytes are forwarded untouched. Normalization exists only for key derivation.
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
     * Computes the cache key. Non-JSON bodies fall back to a raw digest of the body
     * bytes (still bounded and deterministic).
     */
    public CacheKey compute(AuthContext ctx, String modelName, byte[] body) {
        String normalized = normalize(body);
        String canonical = ctx.tenantId() + "|" + ctx.projectId() + "|" + ctx.key().keyId() + "|" + ctx.productId()
                + "|" + (modelName == null ? "" : modelName) + "|"
                + (ctx.key().purpose() == null ? "" : ctx.key().purpose()) + "|" + normalized;
        return CacheKey.from(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
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
