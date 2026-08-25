package com.miqroera.miqrokey.spi;

import java.net.URI;
import java.util.Set;

/**
 * A provider product instance as declared by the signed catalog
 * ({@code docs/provider-adapter-contract.md §3}). Products are the stable unit
 * of provider modelling: a vendor exposing several plans is modelled as several
 * definitions, never as one definition branching on a {@code vendor} string.
 *
 * <p>
 * Definitions are immutable data; the compact constructor validates the shape
 * so malformed catalog entries fail fast at load time.
 *
 * @param id
 *            stable product id, e.g. {@code deepseek-payg}
 * @param vendor
 *            vendor id, e.g. {@code deepseek}
 * @param displayName
 *            internal user-facing name
 * @param adapterId
 *            compile-time registered adapter id serving this product
 * @param protocols
 *            protocol families the product can serve
 * @param baseUrlTemplate
 *            upstream origin or path template; must be HTTPS. Ordinary users
 *            never control this value.
 * @param credentialKind
 *            kind of credential the product consumes
 * @param subscriptionKinds
 *            billing shapes the product supports
 * @param modelCatalogMode
 *            how the model catalog is obtained
 * @param status
 *            verification lifecycle status
 */
public record ProviderProductDefinition(String id, String vendor, String displayName, String adapterId,
        Set<ProtocolFamily> protocols, URI baseUrlTemplate, CredentialKind credentialKind,
        Set<SubscriptionKind> subscriptionKinds, ModelCatalogMode modelCatalogMode, AdapterStatus status) {

    public ProviderProductDefinition {
        requireNonBlank(id, "id");
        requireNonBlank(vendor, "vendor");
        requireNonBlank(displayName, "displayName");
        requireNonBlank(adapterId, "adapterId");
        if (protocols == null || protocols.isEmpty()) {
            throw new IllegalArgumentException("protocols must not be empty");
        }
        if (baseUrlTemplate == null) {
            throw new IllegalArgumentException("baseUrlTemplate must not be null");
        }
        if (!"https".equalsIgnoreCase(baseUrlTemplate.getScheme())) {
            throw new IllegalArgumentException("baseUrlTemplate must use https, got " + baseUrlTemplate);
        }
        if (baseUrlTemplate.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrlTemplate must not contain userinfo: " + baseUrlTemplate);
        }
        if (credentialKind == null) {
            throw new IllegalArgumentException("credentialKind must not be null");
        }
        if (subscriptionKinds == null || subscriptionKinds.isEmpty()) {
            throw new IllegalArgumentException("subscriptionKinds must not be empty");
        }
        if (modelCatalogMode == null) {
            throw new IllegalArgumentException("modelCatalogMode must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        protocols = Set.copyOf(protocols);
        subscriptionKinds = Set.copyOf(subscriptionKinds);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
