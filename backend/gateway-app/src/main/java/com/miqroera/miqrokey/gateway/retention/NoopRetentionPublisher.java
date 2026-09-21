package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default publisher when no transport is configured: retention stays fully off
 * even if the config switch is flipped (fail-closed posture until the Kafka
 * producer lands behind configuration).
 */
public final class NoopRetentionPublisher implements RetentionPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopRetentionPublisher.class);

    @Override
    public void publish(RetentionEnvelope envelope) {
        log.warn("retention envelope {} dropped: no retention publisher configured", envelope.eventId());
    }
}
