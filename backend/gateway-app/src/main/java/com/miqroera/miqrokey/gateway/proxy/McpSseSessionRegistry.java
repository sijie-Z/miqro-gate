package com.miqroera.miqrokey.gateway.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-node in-memory registry of inbound MCP SSE sessions (issue #356, I11).
 * A session is a hot sink carrying pre-encoded SSE frames: the GET {@code /sse}
 * handler subscribes the response to it, and {@code POST /message} dispatches
 * the JSON-RPC call whose outcome lands on the same stream. Sessions are bound
 * to one consumer and one MCP service; idle sessions are reaped (stream
 * completed) by a scheduled sweep. Distributed/multi-node sessions remain the
 * separate raw-doc-09 item — this registry deliberately does not try to be one.
 */
@Component
public class McpSseSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpSseSessionRegistry.class);

    /**
     * Idle sessions are closed (stream completed) after this long without a
     * message.
     */
    static final Duration IDLE_TTL = Duration.ofMinutes(5);
    /**
     * Hard cap on concurrent inbound SSE sessions (single-node private deployment).
     */
    static final int MAX_SESSIONS = 256;

    private final Map<UUID, SseSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public McpSseSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    /** One inbound SSE session; the sink carries already-encoded SSE frames. */
    public static final class SseSession {

        private final UUID id;
        private final UUID consumerId;
        private final UUID serviceId;
        private final String serviceName;
        private final Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        private volatile Instant lastActivity;

        private SseSession(UUID id, UUID consumerId, UUID serviceId, String serviceName, Instant now) {
            this.id = id;
            this.consumerId = consumerId;
            this.serviceId = serviceId;
            this.serviceName = serviceName;
            this.lastActivity = now;
        }

        public UUID id() {
            return id;
        }

        public UUID consumerId() {
            return consumerId;
        }

        public UUID serviceId() {
            return serviceId;
        }

        public String serviceName() {
            return serviceName;
        }

        /** The frame stream the SSE response subscribes to. */
        public Sinks.Many<byte[]> frames() {
            return sink;
        }

        void touch(Instant now) {
            lastActivity = now;
        }

        void emit(byte[] frame) {
            Sinks.EmitResult result = sink.tryEmitNext(frame);
            if (result.isFailure()) {
                // Subscriber gone or saturated: the session is dead, drop the frame.
                log.debug("aigw.mcp.sse.emit_failed session={} result={}", id, result);
            }
        }

        void close() {
            sink.tryEmitComplete();
        }
    }

    /**
     * Opens a session for the consumer+service pair; empty when the registry is at
     * capacity (the caller answers {@code 503 session_capacity_exceeded}).
     */
    public Optional<SseSession> tryOpen(UUID consumerId, UUID serviceId, String serviceName) {
        if (sessions.size() >= MAX_SESSIONS) {
            log.warn("aigw.mcp.sse.capacity sessions={} rejected service={}", sessions.size(), serviceName);
            return Optional.empty();
        }
        Instant now = clock.instant();
        SseSession session = new SseSession(UUID.randomUUID(), consumerId, serviceId, serviceName, now);
        sessions.put(session.id(), session);
        log.info("aigw.mcp.sse.open session={} service={}", session.id(), serviceName);
        return Optional.of(session);
    }

    public Optional<SseSession> find(UUID sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    /** Marks activity; a message resets the idle TTL. */
    public void touch(SseSession session) {
        session.touch(clock.instant());
    }

    /** Removes and completes a session (stream termination or explicit close). */
    public void close(UUID sessionId) {
        SseSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("aigw.mcp.sse.close session={}", sessionId);
        }
    }

    /**
     * Idle sweep: sessions without traffic for {@link #IDLE_TTL} end their stream.
     */
    @Scheduled(fixedDelayString = "${miqrokey.mcp.sse.reap-cycle-ms:30000}")
    public void reapIdle() {
        Instant cutoff = clock.instant().minus(IDLE_TTL);
        List<UUID> idle = sessions.values().stream().filter(session -> session.lastActivity.isBefore(cutoff))
                .map(SseSession::id).toList();
        for (UUID sessionId : idle) {
            log.info("aigw.mcp.sse.idle_timeout session={}", sessionId);
            close(sessionId);
        }
    }

    /** Live session count (metrics/tests). */
    public int size() {
        return sessions.size();
    }
}
