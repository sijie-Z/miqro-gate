package com.miqroera.miqrokey.gateway.retention;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when a non-blank Kafka bootstrap-servers value is configured.
 *
 * <p>
 * Deliberately not {@code @ConditionalOnProperty}. That annotation matches when
 * the property is <em>present</em> and not {@code "false"} — so an <b>empty</b>
 * value counts as configured, and an empty value is exactly what
 * {@code deploy/compose.prod.yaml} passes when the operator has not set up
 * Kafka ({@code ${MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS:-}}). The
 * publisher's constructor then rejects the blank value and the gateway fails to
 * start, so the documented default deployment — no {@code kafka} profile — left
 * the gateway in a restart loop while every other service reported healthy.
 *
 * <p>
 * "The variable is set but empty" is a normal shape for a deployment to arrive
 * in, so it has to mean "not configured" here.
 */
final class KafkaRetentionConfigured implements Condition {

    static final String PROPERTY = "miqrokey.retention.kafka.bootstrap-servers";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(PROPERTY);
        return value != null && !value.isBlank();
    }
}
