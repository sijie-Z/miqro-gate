package com.miqroera.miqrokey.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProviderProductDefinition invariants")
class ProviderProductDefinitionTest {

    private static ProviderProductDefinition valid() {
        return new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek 官方按量 API", "deepseek-payg-api",
                Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES),
                URI.create("https://api.deepseek.com"), CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG),
                ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED);
    }

    @Test
    @DisplayName("valid definition constructs and normalizes collections to immutable copies")
    void validDefinition() {
        var protocols = new java.util.HashSet<ProtocolFamily>();
        protocols.add(ProtocolFamily.OPENAI_COMPATIBLE);
        var definition = new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek", "deepseek-payg-api",
                protocols, URI.create("https://api.deepseek.com"), CredentialKind.API_KEY,
                Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED);
        assertThat(definition.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
        assertThatThrownBy(() -> definition.protocols().add(ProtocolFamily.VENDOR_NATIVE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("blank id/vendor/displayName/adapterId are rejected")
    void blankFieldsRejected() {
        assertThatThrownBy(() -> new ProviderProductDefinition("", "deepseek", "DeepSeek", "deepseek-payg-api",
                Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API,
                AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", " ", "DeepSeek",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API,
                AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vendor");
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "  ",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API,
                AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek", "",
                Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API,
                AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterId");
    }

    @Test
    @DisplayName("empty protocols or subscriptionKinds are rejected")
    void emptyCollectionsRejected() {
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek",
                "deepseek-payg-api", Set.of(), URI.create("https://api.deepseek.com"), CredentialKind.API_KEY,
                Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protocols");
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(), ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subscriptionKinds");
    }

    @Test
    @DisplayName("non-https or userinfo base URLs are rejected")
    void insecureBaseUrlRejected() {
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("http://api.deepseek.com"),
                CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API,
                AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE),
                URI.create("https://evil@api.deepseek.com"), CredentialKind.API_KEY, Set.of(SubscriptionKind.PAYG),
                ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("null enums are rejected")
    void nullEnumsRejected() {
        assertThatThrownBy(() -> new ProviderProductDefinition("deepseek-payg-api", "deepseek", "DeepSeek",
                "deepseek-payg-api", Set.of(ProtocolFamily.OPENAI_COMPATIBLE), URI.create("https://api.deepseek.com"),
                null, Set.of(SubscriptionKind.PAYG), ModelCatalogMode.OFFICIAL_API, AdapterStatus.DOCUMENTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credentialKind");
    }
}
