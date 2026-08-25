package com.miqroera.miqrokey.adapters.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.AdapterStatus;
import com.miqroera.miqrokey.spi.CredentialKind;
import com.miqroera.miqrokey.spi.ModelCatalogMode;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderProductDefinition;
import com.miqroera.miqrokey.spi.SubscriptionKind;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict schema validation of a catalog manifest
 * ({@code docs/provider-adapter-contract.md §3}).
 *
 * <p>
 * The validator is deliberately strict and allow-list based: unknown top-level
 * fields, unknown product fields, unknown enum values and structurally wrong
 * values are all rejected. This is the load-time boundary that makes a catalog
 * pure data — a manifest can never smuggle executable fields into the system,
 * and adapter resolution never consults anything beyond the {@code adapterId}
 * string against the compile-time registry.
 *
 * <p>
 * Supported shape:
 *
 * <pre>
 * { "version": 1, "products": [ { ...ProviderProductDefinition fields... } ] }
 * </pre>
 */
public final class CatalogManifestValidator {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("version", "products");
    private static final Set<String> PRODUCT_FIELDS = Set.of("id", "vendor", "displayName", "adapterId", "protocols",
            "baseUrlTemplate", "credentialKind", "subscriptionKinds", "modelCatalogMode", "status");

    private final ObjectMapper objectMapper;

    public CatalogManifestValidator() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Validates a catalog manifest and returns the product definitions.
     *
     * @throws CatalogManifestException
     *             with every violation collected, when the manifest is malformed
     *             JSON or invalid
     */
    public List<ProviderProductDefinition> validate(byte[] json) throws CatalogManifestException {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new CatalogManifestException("Catalog is not valid JSON: " + e.getMessage(), e);
        }
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new CatalogManifestException("Catalog root must be a JSON object");
        }

        rejectUnknownFields(root, TOP_LEVEL_FIELDS, "catalog", errors);
        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.asInt() != 1) {
            errors.add("catalog.version: must be the integer 1");
        }
        JsonNode products = root.get("products");
        if (products == null || !products.isArray()) {
            errors.add("catalog.products: must be a non-empty array");
        } else if (products.isEmpty()) {
            errors.add("catalog.products: must not be empty");
        }

        List<ProviderProductDefinition> definitions = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        if (products != null && products.isArray()) {
            int index = 0;
            for (JsonNode product : products) {
                validateProduct(product, index, errors, seenIds, definitions);
                index++;
            }
        }
        if (!errors.isEmpty()) {
            throw new CatalogManifestException("Catalog manifest invalid:\n  - " + String.join("\n  - ", errors));
        }
        return List.copyOf(definitions);
    }

    private void validateProduct(JsonNode product, int index, List<String> errors, Set<String> seenIds,
            List<ProviderProductDefinition> definitions) {
        String loc = "products[" + index + "]";
        if (product == null || !product.isObject()) {
            errors.add(loc + ": must be a JSON object");
            return;
        }
        rejectUnknownFields(product, PRODUCT_FIELDS, loc, errors);

        String id = requireSlug(product, "id", loc, errors);
        String vendor = requireSlug(product, "vendor", loc, errors);
        String displayName = requireString(product, "displayName", loc, errors);
        String adapterId = requireString(product, "adapterId", loc, errors);
        if (id != null && !seenIds.add(id)) {
            errors.add(loc + ".id: duplicate product id '" + id + "'");
        }

        Set<ProtocolFamily> protocols = requireEnums(product.get("protocols"), ProtocolFamily.class, "protocols", loc,
                errors);
        Set<SubscriptionKind> subscriptionKinds = requireEnums(product.get("subscriptionKinds"), SubscriptionKind.class,
                "subscriptionKinds", loc, errors);
        CredentialKind credentialKind = requireEnum(product, "credentialKind", CredentialKind.class, loc, errors);
        ModelCatalogMode modelCatalogMode = requireEnum(product, "modelCatalogMode", ModelCatalogMode.class, loc,
                errors);
        AdapterStatus status = requireEnum(product, "status", AdapterStatus.class, loc, errors);
        URI baseUrlTemplate = requireHttpsUri(product, "baseUrlTemplate", loc, errors);

        // The record's compact constructor is the final gate; it re-checks the
        // invariants so validation stays in one authoritative place. Its
        // IllegalArgumentException is reported through the manifest error
        // channel instead of escaping.
        if (id != null && vendor != null && displayName != null && adapterId != null && protocols != null
                && baseUrlTemplate != null && credentialKind != null && subscriptionKinds != null
                && modelCatalogMode != null && status != null) {
            try {
                definitions.add(new ProviderProductDefinition(id, vendor, displayName, adapterId, protocols,
                        baseUrlTemplate, credentialKind, subscriptionKinds, modelCatalogMode, status));
            } catch (IllegalArgumentException e) {
                errors.add(loc + ": " + e.getMessage());
            }
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String loc, List<String> errors) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                errors.add(loc + ": unknown field '" + name + "' (catalog is data-only; unknown fields are rejected)");
            }
        }
    }

    private static String requireSlug(JsonNode product, String field, String loc, List<String> errors) {
        JsonNode value = product.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(loc + "." + field + ": required");
            return null;
        }
        String text = value.asText();
        if (!SLUG.matcher(text).matches()) {
            errors.add(loc + "." + field + ": must match [a-z0-9][a-z0-9-]*, got '" + text + "'");
            return null;
        }
        return text;
    }

    private static String requireString(JsonNode product, String field, String loc, List<String> errors) {
        JsonNode value = product.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(loc + "." + field + ": required");
            return null;
        }
        return value.asText();
    }

    private static URI requireHttpsUri(JsonNode product, String field, String loc, List<String> errors) {
        JsonNode value = product.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(loc + "." + field + ": required");
            return null;
        }
        String text = value.asText();
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            errors.add(loc + "." + field + ": not a valid URI: '" + text + "'");
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            errors.add(loc + "." + field + ": must use https, got '" + uri.getScheme() + "'");
        }
        if (uri.getRawUserInfo() != null) {
            errors.add(loc + "." + field + ": must not contain userinfo");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            errors.add(loc + "." + field + ": must not contain query or fragment");
        }
        return uri;
    }

    private static <E extends Enum<E>> E requireEnum(JsonNode product, String field, Class<E> type, String loc,
            List<String> errors) {
        JsonNode value = product.get(field);
        if (value == null || !value.isTextual()) {
            errors.add(loc + "." + field + ": required");
            return null;
        }
        try {
            return Enum.valueOf(type, value.asText());
        } catch (IllegalArgumentException e) {
            errors.add(loc + "." + field + ": unknown value '" + value.asText() + "', expected one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
            return null;
        }
    }

    private static <E extends Enum<E>> Set<E> requireEnums(JsonNode node, Class<E> type, String field, String loc,
            List<String> errors) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            errors.add(loc + "." + field + ": must be a non-empty array of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
            return null;
        }
        Set<E> result = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                errors.add(loc + "." + field + ": entries must be strings");
                continue;
            }
            try {
                result.add(Enum.valueOf(type, item.asText()));
            } catch (IllegalArgumentException e) {
                errors.add(loc + "." + field + ": unknown value '" + item.asText() + "', expected one of "
                        + java.util.Arrays.toString(type.getEnumConstants()));
            }
        }
        return result;
    }
}
