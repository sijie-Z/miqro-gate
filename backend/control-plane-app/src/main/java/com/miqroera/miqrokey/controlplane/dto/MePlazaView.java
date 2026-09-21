package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Model plaza (腾讯「AI 能力市场」对位, #1201): what the caller can call right now across
 * their own virtual keys, plus the catalog models they could still ask for.
 *
 * <p>
 * {@code models} is the union over the caller's ACTIVE keys with an ACTIVE
 * grant of {@code key models ∩ grant models ∩ ACTIVE model_catalog} — the same
 * inputs the gateway's {@code /v1/models} intersects, minus the signed
 * provider-catalog check, which lives on the gateway side only.
 * {@code requestable} lists ACTIVE catalog models that are missing from a key:
 * the candidates the model-approval flow exists for (approving one extends the
 * grant and the key).
 * </p>
 *
 * <p>
 * Wire-shape note: all fields are safe metadata — model ids, display names,
 * prices and key labels; never key material or request content.
 * </p>
 */
public record MePlazaView(List<PlazaModel> models, List<RequestableModel> requestable) {

    /**
     * One model usable through at least one of the caller's keys.
     *
     * @param price
     *            latest unit prices per one million tokens; <b>null</b> when the
     *            catalog has no snapshot for this (product, model) at all.
     */
    public record PlazaModel(String modelId, String displayName, Integer contextWindow, Integer maxOutputTokens,
            UUID providerProductId, String providerProductCode, String providerProductName, PlazaPrice price,
            List<PlazaKeyRef> keys) {
    }

    /**
     * Unit prices per one million tokens, mirroring the admin price catalog;
     * individual fields are null when that token type has no snapshot.
     */
    public record PlazaPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion, BigDecimal cacheReadPerMillion,
            BigDecimal cacheCreationPerMillion, String currency) {
    }

    /** A key the model is usable through (label only — never the secret). */
    public record PlazaKeyRef(UUID id, String name, String display) {
    }

    /**
     * A catalog model not on the given key; approvable via the model-approval flow
     * (§8.2). One row per (model, key) pair so the requester knows which key the
     * request would extend.
     */
    public record RequestableModel(String modelId, String displayName, Integer contextWindow, Integer maxOutputTokens,
            UUID providerProductId, String providerProductCode, String providerProductName, UUID keyId,
            String keyName) {
    }
}
