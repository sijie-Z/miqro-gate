package com.miqroera.miqrokey.domain.model;

/**
 * What a quota rule does when its period watermark reaches the limit (#684,
 * ADR-0019 option B).
 *
 * <ul>
 * <li>{@code ALERT} — the shipped behaviour and the default: the rule only
 * drives watermarks and alerts, traffic is never touched.</li>
 * <li>{@code REJECT} — soft landing: the control-plane evaluator publishes the
 * scope as blocked ({@code quota_enforcement}) and the gateway answers 429 until
 * the period rolls over, the limit is raised back above usage, or the rule is
 * disabled or deleted. The credential itself is never deleted, disabled or
 * rotated — only the over-quota request is refused.</li>
 * </ul>
 *
 * Opt-in per rule, so enforcement is never switched on by accident.
 */
public enum QuotaRuleEnforcement {
    ALERT, REJECT
}
