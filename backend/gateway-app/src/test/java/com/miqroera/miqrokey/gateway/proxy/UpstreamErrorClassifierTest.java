package com.miqroera.miqrokey.gateway.proxy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the upstream-error observation tier (ADR-0024 option B,
 * #770): the pattern match, the truncation guard, the prefix cap, and the
 * counter's bounded class label. Nothing here retries or rewrites anything —
 * the tier is measurement, and these tests pin exactly what it may look at.
 */
@DisplayName("UpstreamErrorClassifier")
class UpstreamErrorClassifierTest {

    private static final String SIGNATURE = """
            {"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0.type: Invalid `signature` in `thinking` block"}}""";
    private static final String MISMATCH = """
            {"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0: Expected `thinking` or `redacted_thinking`, but found `tool_use`."}}""";
    private static final String BUDGET = """
            {"type":"error","error":{"type":"invalid_request_error","message":"thinking.budget_tokens: Input should be greater than or equal to 1024"}}""";
    private static final String MISSING = """
            {"type":"error","error":{"type":"invalid_request_error","message":"messages.2.content.1.thinking.signature: Field required"}}""";

    private static UpstreamErrorClassifier.ErrorClass classify(String body) {
        return UpstreamErrorClassifier.classify(body.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    @DisplayName("classifies the documented rejection shapes")
    void classifiesShapes() {
        assertThat(classify(SIGNATURE)).isEqualTo(UpstreamErrorClassifier.ErrorClass.SIGNATURE_INVALID);
        assertThat(classify(MISMATCH)).isEqualTo(UpstreamErrorClassifier.ErrorClass.THINKING_BLOCK_MISMATCH);
        assertThat(classify(BUDGET)).isEqualTo(UpstreamErrorClassifier.ErrorClass.BUDGET_INVALID);
        assertThat(classify(MISSING)).isEqualTo(UpstreamErrorClassifier.ErrorClass.MISSING_SIGNATURE);
    }

    @Test
    @DisplayName("anything else is UNCLASSIFIED, so the long tail stays countable")
    void unmatchedIsUnclassified() {
        assertThat(classify("{\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}"))
                .isEqualTo(UpstreamErrorClassifier.ErrorClass.UNCLASSIFIED);
        assertThat(classify("")).isEqualTo(UpstreamErrorClassifier.ErrorClass.UNCLASSIFIED);
        assertThat(UpstreamErrorClassifier.classify(null, false))
                .isEqualTo(UpstreamErrorClassifier.ErrorClass.UNCLASSIFIED);
    }

    @Test
    @DisplayName("a truncated buffer is never classified from a fragment")
    void truncatedIsUnclassified() {
        assertThat(UpstreamErrorClassifier.classify(SIGNATURE.getBytes(StandardCharsets.UTF_8), true))
                .isEqualTo(UpstreamErrorClassifier.ErrorClass.UNCLASSIFIED);
        assertThat(classify(SIGNATURE)).isEqualTo(UpstreamErrorClassifier.ErrorClass.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("inspects a bounded prefix only — a pattern past the cap is not matched")
    void capsTheInspectedPrefix() {
        String padded = "x".repeat(UpstreamErrorClassifier.MAX_CLASSIFY_BYTES) + "invalid `signature`";
        assertThat(classify(padded)).isEqualTo(UpstreamErrorClassifier.ErrorClass.UNCLASSIFIED);
    }

    @Test
    @DisplayName("counts one sample per observation under a bounded class label")
    void countsPerClass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UpstreamErrorClassifier classifier = new UpstreamErrorClassifier(registry);

        classifier.observe(400, SIGNATURE.getBytes(StandardCharsets.UTF_8), false);
        classifier.observe(400, SIGNATURE.getBytes(StandardCharsets.UTF_8), false);
        classifier.observe(500, "boom".getBytes(StandardCharsets.UTF_8), false);

        assertThat(counter(registry, "SIGNATURE_INVALID")).isEqualTo(2.0);
        assertThat(counter(registry, "UNCLASSIFIED")).isEqualTo(1.0);
        // Two classes seen → two meters: the HTTP status is a log field, never a
        // tag (an upstream may answer any integer code; tags must stay bounded).
        assertThat(registry.getMeters()).hasSize(2);
    }

    private static double counter(SimpleMeterRegistry registry, String errorClass) {
        return registry.get("miqrokey_gateway_upstream_error_class_total").tag("class", errorClass).counter().count();
    }
}
