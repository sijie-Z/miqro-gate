package com.miqroera.miqrokey.gateway.proxy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Observation-only content-filter shadow (ADR-0027, issue #740).
 *
 * <p>
 * A local, operator-configured vocabulary of case-insensitive substrings is
 * matched against the request and reply bytes the gateway already buffered —
 * inside this process, single pass over a bounded prefix, no IO, no external
 * service, no regex. The outcome is <strong>measurement only</strong>: two
 * counters, one match-duration timer per direction, and one content-free log
 * line per hit category. Nothing is blocked, rejected, rewritten, truncated or
 * injected, and no request/response byte differs because this ran (ADR-0027 §2
 * D1–D4). Words and bodies never enter a log, a metric tag or an event (D5):
 * the metric vocabulary is the configured category, never a word or any
 * content.
 * </p>
 *
 * <p>
 * Metrics (both directions evaluated; {@code direction} is {@code input} or
 * {@code output}):
 * </p>
 * <ul>
 * <li>{@code miqrokey_gateway_content_filter_evaluations_total{direction}} —
 * incremented for <em>every</em> evaluation, hit or not, so "no hits" stays
 * distinguishable from "the channel is not running" (ADR-0027 §6).</li>
 * <li>{@code miqrokey_gateway_content_filter_hits_total{direction,category}} —
 * incremented <em>once per evaluation</em> for a category with at least one
 * matching word; a repeated word cannot amplify it.</li>
 * <li>{@code miqrokey_gateway_content_filter_match_seconds{direction}} —
 * duration of the matching work of one evaluation (decode + scan; not recorded
 * for an overflow skip, where none ran).</li>
 * </ul>
 *
 * <p>
 * Bounded input, classifier precedent ({@link UpstreamErrorClassifier}): an
 * output buffer the collector had to truncate ({@code overflow}) is skipped
 * entirely — a fragment could mislabel a reply — while the evaluation counter
 * still moves. Matching itself reads at most {@link #MAX_MATCH_BYTES} decoded
 * bytes so the per-evaluation working set stays fixed on the hot path.
 * </p>
 *
 * <p>
 * <strong>Default off.</strong> {@link #observeInput}/{@link #observeOutput}
 * are no-ops unless {@code miqrokey.gateway.content-filter.enabled} is true
 * (see {@link ContentFilterProperties}): no registry access, no logging, no
 * behavior change. Any matching failure is swallowed in place with a
 * content-free debug line — observation can never break the pipeline.
 * </p>
 */
@Component
public class ContentFilterShadow {

    /** Words past this prefix are not matched (bounded hot-path working set). */
    static final int MAX_MATCH_BYTES = 8 * 1024;

    static final String DIRECTION_INPUT = "input";
    static final String DIRECTION_OUTPUT = "output";

    private static final Logger LOG = LoggerFactory.getLogger(ContentFilterShadow.class);

    private final MeterRegistry registry;
    private final boolean enabled;
    /** Category to pre-lowercased words; empty when disabled. */
    private final Map<String, List<String>> vocabulary;

    public ContentFilterShadow(ContentFilterProperties properties, MeterRegistry registry) {
        this.registry = registry;
        this.enabled = properties.enabled();
        this.vocabulary = this.enabled ? parseVocabulary(properties.vocabulary()) : Map.of();
        if (this.enabled && this.vocabulary.isEmpty()) {
            LOG.warn("content filter shadow: enabled with an empty vocabulary - no word can match");
        }
    }

    /**
     * Evaluates the buffered request body. The body reaches this point complete (an
     * oversized context was rejected with 413 before it, so there is no
     * truncated-input case on this side). Never throws; never affects the request.
     */
    public void observeInput(byte[] body) {
        observe(DIRECTION_INPUT, body, false);
    }

    /**
     * Evaluates the reply bytes after they were fully written to the client.
     * {@code overflow} flags a buffer the collector truncated; the match is skipped
     * for it while the evaluation counter still moves. Never throws; never affects
     * the response.
     */
    public void observeOutput(byte[] body, boolean overflow) {
        observe(DIRECTION_OUTPUT, body, overflow);
    }

    private void observe(String direction, byte[] body, boolean overflow) {
        if (!enabled) {
            return;
        }
        try {
            Counter.builder("miqrokey_gateway_content_filter_evaluations_total").tag("direction", direction)
                    .description("Content-filter shadow evaluations per direction (ADR-0027; observation only)")
                    .register(registry).increment();
            if (overflow) {
                // Bounded input: a truncated buffer is never matched (classifier
                // precedent). The evaluation counter above stays the liveness
                // signal for "the shadow is running but saw fewer bodies".
                return;
            }
            long startNanos = System.nanoTime();
            Map<String, Integer> matched = match(matchableText(body));
            Timer.builder("miqrokey_gateway_content_filter_match_seconds").tag("direction", direction)
                    .description("Time spent matching the local content-filter vocabulary (ADR-0027; observation only)")
                    .register(registry).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            for (Map.Entry<String, Integer> hit : matched.entrySet()) {
                Counter.builder("miqrokey_gateway_content_filter_hits_total")
                        .tags("direction", direction, "category", hit.getKey())
                        .description("Content-filter shadow category hits (one per category per evaluation)")
                        .register(registry).increment();
                // Categories and counts only - never a word, never any body bytes.
                LOG.info("content filter shadow: direction={} category={} hits={}", direction, hit.getKey(),
                        hit.getValue());
            }
        } catch (Exception e) {
            // Observation must never affect the pipeline: swallow in place. Only
            // the exception type is logged - a message could echo the body.
            LOG.debug("content filter shadow evaluation failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Categories with at least one matching word, mapped to the number of distinct
     * words of that category that matched (the counter still counts the category
     * once — this count is what the log line reports). Visible for the
     * failure-injection tests: an implementation that throws is swallowed by
     * {@link #observe} and never reaches the caller.
     */
    Map<String, Integer> match(String text) {
        Map<String, Integer> matched = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> category : vocabulary.entrySet()) {
            int hits = 0;
            for (String word : category.getValue()) {
                if (text.contains(word)) {
                    hits++;
                }
            }
            if (hits > 0) {
                matched.put(category.getKey(), hits);
            }
        }
        return matched;
    }

    /**
     * The matched text: the body decoded as UTF-8 up to {@link #MAX_MATCH_BYTES}
     * and lowercased once (classifier precedent). The bound keeps the
     * per-evaluation allocation fixed regardless of body size; words are
     * pre-lowercased, so the comparison is case-insensitive on both sides.
     */
    private static String matchableText(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, 0, Math.min(body.length, MAX_MATCH_BYTES), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Parses {@code category=word1|word2;category2=word3}. Empty segments are
     * padding; a segment without a non-empty category and at least one non-empty
     * word is malformed and skipped (counted in one content-free warn — never the
     * entry text). Words are trimmed and lowercased once here, deduplicated within
     * their category; a repeated category replaces its earlier words (last entry
     * wins).
     */
    static Map<String, List<String>> parseVocabulary(String raw) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (raw != null && !raw.isBlank()) {
            for (String segment : raw.split(";")) {
                String entry = segment.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                int separator = entry.indexOf('=');
                if (separator <= 0 || separator == entry.length() - 1) {
                    skipped++;
                    continue;
                }
                String category = entry.substring(0, separator).trim();
                List<String> words = new ArrayList<>();
                for (String word : entry.substring(separator + 1).split("\\|")) {
                    String lowered = word.trim().toLowerCase(Locale.ROOT);
                    if (!lowered.isEmpty() && !words.contains(lowered)) {
                        words.add(lowered);
                    }
                }
                if (category.isEmpty() || words.isEmpty()) {
                    skipped++;
                    continue;
                }
                parsed.put(category, words);
            }
        }
        if (skipped > 0) {
            LOG.warn("content filter shadow vocabulary: {} malformed entries skipped, {} categories active", skipped,
                    parsed.size());
        }
        return parsed;
    }
}
