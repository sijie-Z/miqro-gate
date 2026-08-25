package com.miqroera.miqrokey.adapters.catalog;

import com.miqroera.miqrokey.spi.AdapterStatus;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CatalogManifestValidator strict schema")
class CatalogManifestValidatorTest {

    private final CatalogManifestValidator validator = new CatalogManifestValidator();

    @Test
    @DisplayName("valid manifest parses to definitions")
    void validManifest() throws Exception {
        var definitions = validator.validate(TestCatalogSigner.validManifest().getBytes(StandardCharsets.UTF_8));
        assertThat(definitions).hasSize(1);
        var product = definitions.get(0);
        assertThat(product.id()).isEqualTo("deepseek-payg-api");
        assertThat(product.vendor()).isEqualTo("deepseek");
        assertThat(product.protocols()).contains(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(product.status()).isEqualTo(AdapterStatus.DOCUMENTED);
        assertThat(product.baseUrlTemplate().toString()).isEqualTo("https://api.deepseek.com");
    }

    @Test
    @DisplayName("unknown top-level fields are rejected")
    void unknownTopLevelFieldRejected() {
        String manifest = TestCatalogSigner.validManifest().replace("\"version\": 1",
                "\"version\": 1,\n  \"_comment\": \"x\"");
        assertThatThrownBy(() -> validator.validate(manifest.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("unknown field '_comment'");
    }

    @Test
    @DisplayName("unknown product fields — including code-bearing fields — are rejected")
    void unknownProductFieldRejected() {
        // The catalog is pure data: any field that could carry executable content
        // (class names, code, plugin references) is rejected at schema time, so a
        // remote catalog can never load code into the gateway.
        String manifest = TestCatalogSigner.validManifest().replace("\"status\": \"DOCUMENTED\"",
                "\"status\": \"DOCUMENTED\",\n      \"class\": \"com.evil.Exploit\",\n      \"code\": \"System.exit(1)\"");
        assertThatThrownBy(() -> validator.validate(manifest.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("unknown field 'class'")
                .hasMessageContaining("unknown field 'code'");
    }

    @Test
    @DisplayName("missing required fields are rejected")
    void missingRequiredFieldRejected() {
        String manifest = TestCatalogSigner.validManifest().replace("\"adapterId\": \"deepseek-payg-api\",\n", "");
        assertThatThrownBy(() -> validator.validate(manifest.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("adapterId");
    }

    @Test
    @DisplayName("unknown enum values are rejected")
    void unknownEnumValueRejected() {
        String manifest = TestCatalogSigner.validManifest().replace("\"status\": \"DOCUMENTED\"",
                "\"status\": \"HACKED\"");
        assertThatThrownBy(() -> validator.validate(manifest.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("status")
                .hasMessageContaining("HACKED");
    }

    @Test
    @DisplayName("non-https or userinfo base URLs are rejected")
    void insecureBaseUrlRejected() {
        String http = TestCatalogSigner.validManifest().replace("https://api.deepseek.com", "http://api.deepseek.com");
        assertThatThrownBy(() -> validator.validate(http.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("https");

        String userinfo = TestCatalogSigner.validManifest().replace("https://api.deepseek.com",
                "https://evil@api.deepseek.com");
        assertThatThrownBy(() -> validator.validate(userinfo.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("duplicate product ids are rejected")
    void duplicateProductIdRejected() {
        String product = """
                {
                  "id": "deepseek-payg-api",
                  "vendor": "deepseek",
                  "displayName": "DeepSeek 官方按量 API",
                  "adapterId": "deepseek-payg-api",
                  "protocols": ["OPENAI_COMPATIBLE", "ANTHROPIC_MESSAGES"],
                  "baseUrlTemplate": "https://api.deepseek.com",
                  "credentialKind": "API_KEY",
                  "subscriptionKinds": ["PAYG"],
                  "modelCatalogMode": "OFFICIAL_API",
                  "status": "DOCUMENTED"
                }""";
        String duplicated = """
                {
                  "version": 1,
                  "products": [
                %s,
                %s
                  ]
                }
                """.formatted(product, product);
        assertThatThrownBy(() -> validator.validate(duplicated.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("duplicate product id");
    }

    @Test
    @DisplayName("malformed JSON is rejected")
    void malformedJsonRejected() {
        assertThatThrownBy(() -> validator.validate("{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("all violations are collected in one message")
    void allErrorsCollected() {
        String manifest = """
                {
                  "version": 99,
                  "products": [
                    {
                      "id": "Bad_Id!",
                      "vendor": "deepseek",
                      "displayName": "",
                      "adapterId": "deepseek-payg-api",
                      "protocols": ["NOT_A_PROTOCOL"],
                      "baseUrlTemplate": "ftp://api.deepseek.com",
                      "credentialKind": "API_KEY",
                      "subscriptionKinds": ["PAYG"],
                      "modelCatalogMode": "OFFICIAL_API",
                      "status": "DOCUMENTED"
                    }
                  ]
                }
                """;
        assertThatThrownBy(() -> validator.validate(manifest.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CatalogManifestException.class).hasMessageContaining("version")
                .hasMessageContaining("Bad_Id!").hasMessageContaining("displayName")
                .hasMessageContaining("NOT_A_PROTOCOL").hasMessageContaining("ftp");
    }
}
