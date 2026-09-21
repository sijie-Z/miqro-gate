package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.domain.model.RetentionEnvelope;

/**
 * Outbound side of the retention channel (ADR-0014). Implementations must never
 * block the caller: the sidecar publishes from its own bounded queue flush. R2
 * default is a no-op; the Kafka producer plugs in as a second implementation
 * behind configuration.
 */
public interface RetentionPublisher {

    void publish(RetentionEnvelope envelope);
}
