package com.miqroera.miqrokey.gateway.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The Kafka publisher must appear only when a real address is configured.
 *
 * <p>
 * The case that matters is the empty-but-present value:
 * {@code compose.prod.yaml} passes exactly that when the operator has not set
 * up Kafka ({@code ${MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS:-}}). Under
 * {@code @ConditionalOnProperty} it counted as "configured", the publisher's
 * constructor rejected the blank value, and the gateway could not start at all.
 */
class KafkaRetentionConfiguredTest {

    private static final String PROPERTY = "miqrokey.retention.kafka.bootstrap-servers";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaRetentionConfig.class);

    @Test
    void absentProperty_registersNoPublisher() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RetentionPublisher.class));
    }

    @Test
    void emptyProperty_registersNoPublisher() {
        // What the default production compose passes.
        runner.withPropertyValues(PROPERTY + "=")
                .run(context -> assertThat(context).doesNotHaveBean(RetentionPublisher.class));
    }

    @Test
    void whitespaceOnlyProperty_registersNoPublisher() {
        runner.withPropertyValues(PROPERTY + "=   ")
                .run(context -> assertThat(context).doesNotHaveBean(RetentionPublisher.class));
    }

    @Test
    void configuredAddress_registersTheKafkaPublisher() {
        runner.withPropertyValues(PROPERTY + "=localhost:9092").run(context -> {
            assertThat(context).hasSingleBean(RetentionPublisher.class);
            assertThat(context.getBean(RetentionPublisher.class)).isInstanceOf(KafkaRetentionPublisher.class);
        });
    }
}
