package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-side content-filter shadow configuration (#740, ADR-0027).
 *
 * <p>
 * The shadow tier is <strong>observation only</strong>: a local vocabulary is
 * matched against the buffered request and reply bytes inside the gateway
 * process, and the outcome is a set of counters plus a content-free log line.
 * Nothing is blocked, rewritten, truncated or injected; the content never
 * leaves the gateway, is never persisted and never enters the retention
 * pipeline (ADR-0027 §2 D1–D5).
 * </p>
 *
 * <p>
 * <strong>Default off.</strong> When {@code enabled} is false — the
 * {@code application.yml} default — the shadow does not run at all: no
 * evaluation, no metric registered, no log line. Behavior is exactly the code
 * before #740.
 * </p>
 *
 * <p>
 * The vocabulary is local configuration (no control plane, no API), changed
 * only by operators. Grammar: {@code category=word1|word2;category2=word3} —
 * the category is a bounded free string used as a metric tag, the word is a
 * case-insensitive substring. Malformed entries are skipped (a single
 * content-free startup warn counts them, when the shadow is enabled); a
 * repeated category replaces its earlier words (last entry wins).
 * </p>
 *
 * @param enabled
 *            whether the shadow runs at all; {@code null} means false
 * @param vocabulary
 *            the rule vocabulary; {@code null} means empty
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.content-filter")
public record ContentFilterProperties(Boolean enabled, String vocabulary) {

    public ContentFilterProperties {
        enabled = enabled != null && enabled;
        vocabulary = vocabulary == null ? "" : vocabulary;
    }
}
