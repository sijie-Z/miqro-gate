package com.miqroera.miqrokey.gateway.proxy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the content-filter shadow (ADR-0027, #740): the match
 * semantics (case-insensitive substring, one counted hit per category per
 * evaluation), the overflow skip, the default-off kill switch, malformed
 * vocabulary tolerance, the bounded prefix and the swallow contract. Nothing
 * here blocks or rewrites anything — the tier is measurement, and these tests
 * pin exactly what it may look at and count.
 */
@DisplayName("ContentFilterShadow")
class ContentFilterShadowTest {

    private static final String EVALUATIONS = "miqrokey_gateway_content_filter_evaluations_total";
    private static final String HITS = "miqrokey_gateway_content_filter_hits_total";
    private static final String MATCH_SECONDS = "miqrokey_gateway_content_filter_match_seconds";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ContentFilterShadow shadow(String vocabulary) {
        return new ContentFilterShadow(new ContentFilterProperties(true, vocabulary), registry);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private double evaluations(String direction) {
        var counter = registry.find(EVALUATIONS).tag("direction", direction).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double hits(String direction, String category) {
        var counter = registry.find(HITS).tags("direction", direction, "category", category).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("counts one hit per category per evaluation — repeated words cannot amplify it")
    void countsOneHitPerCategoryPerEvaluation() {
        shadow("cat=alpha|beta").observeInput(bytes("alpha beta ALPHA alpha"));

        assertThat(evaluations("input")).isEqualTo(1.0);
        assertThat(hits("input", "cat")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("matches case-insensitively in both directions")
    void matchesCaseInsensitively() {
        ContentFilterShadow shadow = shadow("mixed=SeCrEt;upper=PLAIN");

        shadow.observeInput(bytes("the SECRET is plain"));

        assertThat(hits("input", "mixed")).isEqualTo(1.0);
        assertThat(hits("input", "upper")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("counts every matching category independently in one evaluation")
    void countsMultipleCategoriesIndependently() {
        shadow("one=alpha;two=beta;three=gamma").observeOutput(bytes("alpha and beta"), false);

        assertThat(evaluations("output")).isEqualTo(1.0);
        assertThat(hits("output", "one")).isEqualTo(1.0);
        assertThat(hits("output", "two")).isEqualTo(1.0);
        assertThat(hits("output", "three")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an evaluation without hits still moves the evaluations counter")
    void countsEvaluationsWithoutHits() {
        shadow("cat=alpha").observeInput(bytes("nothing to see here"));

        assertThat(evaluations("input")).isEqualTo(1.0);
        assertThat(hits("input", "cat")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an empty or absent body is a counted evaluation")
    void countsEmptyBodies() {
        ContentFilterShadow shadow = shadow("cat=alpha");

        shadow.observeInput(new byte[0]);
        shadow.observeInput(null);

        assertThat(evaluations("input")).isEqualTo(2.0);
        assertThat(hits("input", "cat")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an overflowed output buffer skips matching but still counts the evaluation")
    void overflowSkipsMatchingButCountsEvaluation() {
        shadow("cat=alpha").observeOutput(bytes("alpha"), true);

        assertThat(evaluations("output")).isEqualTo(1.0);
        assertThat(hits("output", "cat")).isEqualTo(0.0);
        // Matching never ran, so there is no duration sample either.
        assertThat(registry.find(MATCH_SECONDS).tag("direction", "output").timer()).isNull();
    }

    @Test
    @DisplayName("records one match-duration sample per matched evaluation")
    void recordsMatchDuration() {
        ContentFilterShadow shadow = shadow("cat=alpha");

        shadow.observeInput(bytes("alpha"));
        shadow.observeInput(bytes("nothing"));

        var timer = registry.find(MATCH_SECONDS).tag("direction", "input").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("disabled or flag absent: no meter is ever registered")
    void disabledTouchesNothing() {
        ContentFilterShadow disabled = new ContentFilterShadow(new ContentFilterProperties(false, "cat=alpha"),
                registry);
        disabled.observeInput(bytes("alpha"));
        disabled.observeOutput(bytes("alpha"), false);

        ContentFilterShadow absentFlag = new ContentFilterShadow(new ContentFilterProperties(null, "cat=alpha"),
                registry);
        absentFlag.observeInput(bytes("alpha"));

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("empty, null and malformed vocabularies never fail; valid entries keep matching")
    void malformedVocabularyDoesNotFail() {
        String[] vocabularies = {null, "", "   ", ";;;", "no-equals-sign", "=no-category", "cat=", "cat=||",
                "cat=a|b;broken;other=c"};

        for (String vocabulary : vocabularies) {
            SimpleMeterRegistry fresh = new SimpleMeterRegistry();
            ContentFilterShadow shadow = new ContentFilterShadow(new ContentFilterProperties(true, vocabulary), fresh);
            shadow.observeInput(bytes("a b c"));
            shadow.observeOutput(bytes("a b c"), false);
            assertThat(fresh.find(EVALUATIONS).tag("direction", "input").counter()).isNotNull();
        }

        ContentFilterShadow shadow = shadow("cat=alpha;broken;other=beta");
        shadow.observeInput(bytes("alpha beta"));
        assertThat(hits("input", "cat")).isEqualTo(1.0);
        assertThat(hits("input", "other")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a repeated category replaces its earlier words (last entry wins)")
    void repeatedCategoryLastEntryWins() {
        ContentFilterShadow shadow = shadow("dup=first;dup=second");

        shadow.observeInput(bytes("first"));
        shadow.observeInput(bytes("second"));

        assertThat(evaluations("input")).isEqualTo(2.0);
        assertThat(hits("input", "dup")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("inspects a bounded prefix only — a word past the cap is not matched")
    void matchesOnlyTheBoundedPrefix() {
        ContentFilterShadow shadow = shadow("cat=alpha");
        String padded = "x".repeat(ContentFilterShadow.MAX_MATCH_BYTES) + " alpha";

        shadow.observeInput(bytes(padded));

        assertThat(evaluations("input")).isEqualTo(1.0);
        assertThat(hits("input", "cat")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a throwing matcher is swallowed — nothing reaches the caller")
    void matchFailureIsSwallowed() {
        ContentFilterShadow shadow = new ThrowingShadow(new ContentFilterProperties(true, "cat=alpha"), registry);

        shadow.observeInput(bytes("alpha")); // must not throw

        assertThat(evaluations("input")).isEqualTo(1.0); // the channel stays countable
        assertThat(hits("input", "cat")).isEqualTo(0.0);
    }

    /**
     * Failure-injection seam: the matcher always throws and the swallow must eat
     * it.
     */
    private static final class ThrowingShadow extends ContentFilterShadow {

        ThrowingShadow(ContentFilterProperties properties, MeterRegistry registry) {
            super(properties, registry);
        }

        @Override
        Map<String, Integer> match(String text) {
            throw new IllegalStateException("deliberate test failure");
        }
    }
}
