package com.miqroera.miqrokey.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Model-approval workflow settings (api-contract §4.6, configuration-reference
 * §5): models that skip the review cycle. A request for a whitelisted model is
 * auto-approved on submission (the approval row still records the grant).
 *
 * <p>
 * Whitelisted models are exact, case-sensitive IDs (same rule as grant models).
 * </p>
 */
@ConfigurationProperties(prefix = "miqrokey.approval")
public class ApprovalProperties {

    /** Model IDs that are usable without an administrator review. */
    private List<String> whitelistModels = List.of();

    public List<String> getWhitelistModels() {
        return whitelistModels;
    }

    public void setWhitelistModels(List<String> whitelistModels) {
        this.whitelistModels = whitelistModels == null ? List.of() : whitelistModels;
    }
}
