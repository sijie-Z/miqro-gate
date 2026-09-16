package com.miqroera.miqrokey.controlplane.retention;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Optional built-in consumer of the {@code content-retention} topic feeding the
 * admin console's retention log viewer (ADR-0014 §8). OFF by default —
 * deployments enable it with {@code miqrokey.retention.consumer.enabled=true}
 * plus bootstrap servers (self-hosted broker profile or a CKafka address).
 *
 * <p>
 * At-least-once with manual sync commits: offsets commit only after each batch
 * was handed to {@link RetentionLogIngestService} (idempotent on event_id, so
 * replays are no-ops). The loop runs on a dedicated daemon thread; broker
 * outages surface as throttled WARNs, never as request-path impact.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "miqrokey.retention.consumer", name = "enabled", havingValue = "true")
public class RetentionLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(RetentionLogConsumer.class);

    private final RetentionLogIngestService ingestService;
    private final String bootstrapServers;
    private final String topic;
    private final String groupId;
    private final long pollMillis;

    private volatile boolean running;
    private Thread thread;

    public RetentionLogConsumer(RetentionLogIngestService ingestService,
            @Value("${miqrokey.retention.consumer.bootstrap-servers:}") String bootstrapServers,
            @Value("${miqrokey.retention.consumer.topic:content-retention}") String topic,
            @Value("${miqrokey.retention.consumer.group-id:miqrokey-retention-console}") String groupId,
            @Value("${miqrokey.retention.consumer.poll-millis:500}") long pollMillis) {
        this.ingestService = ingestService;
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.groupId = groupId;
        this.pollMillis = pollMillis;
    }

    @PostConstruct
    void start() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            log.error("retention consumer enabled but miqrokey.retention.consumer.bootstrap-servers is empty; "
                    + "consumer not started");
            return;
        }
        running = true;
        thread = new Thread(this::run, "retention-log-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("retention log consumer started (topic={}, group={}, brokers={})", topic, groupId, bootstrapServers);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "200");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (running) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollMillis));
                    if (records.isEmpty()) {
                        continue;
                    }
                    int inserted = 0;
                    for (ConsumerRecord<String, String> record : records) {
                        if (ingestService.ingest(record.value())) {
                            inserted++;
                        }
                    }
                    // Commit only after the batch was ingested; replays are
                    // idempotent (event_id), so an ingest crash re-reads safely.
                    consumer.commitSync();
                    if (inserted > 0) {
                        log.debug("retention consumer ingested {} of {} records", inserted, records.count());
                    }
                } catch (org.apache.kafka.common.errors.WakeupException e) {
                    break;
                } catch (Exception e) {
                    log.warn("retention consumer poll failed; retrying: {}", e.getMessage());
                    sleepQuietly();
                }
            }
        } catch (Exception e) {
            if (running) {
                log.error("retention log consumer stopped unexpectedly", e);
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }
}
