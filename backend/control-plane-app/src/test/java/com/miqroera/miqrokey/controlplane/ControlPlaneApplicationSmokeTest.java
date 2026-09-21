package com.miqroera.miqrokey.controlplane;

import com.miqroera.miqrokey.controlplane.config.TestCryptoConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal smoke test to verify the Control Plane application context loads.
 *
 * <p>
 * Uses {@code SpringBootTest.WebEnvironment.NONE} to avoid starting an embedded
 * server, since database connectivity is not required for G0.1.
 * </p>
 *
 * <p>
 * #421: this context runs on the {@code test} profile (H2, Flyway off — an
 * EMPTY schema). Every {@code @Scheduled} task fires its FIRST run immediately
 * on startup (fixedDelay without initialDelay), so the DB-touching schedulers
 * spammed {@code BadSqlGrammarException} ERRORs against the empty schema — and
 * Spring's context cache kept that failing scheduler alive for the rest of the
 * suite. A never-firing {@link TaskScheduler} bean (named
 * {@code taskScheduler}, so Boot's auto-configuration backs off) absorbs every
 * schedule registration; the context is additionally evicted after the class.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({TestCryptoConfig.class, ControlPlaneApplicationSmokeTest.NoopScheduling.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Control Plane application context")
class ControlPlaneApplicationSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("should load application context successfully")
    void shouldLoadApplicationContext() {
        // If the context fails to load, this test will throw an exception.
        // The assertion is implicit in the @SpringBootTest lifecycle.
    }

    @Test
    @DisplayName("should use the documented control plane port by default")
    void shouldUseDocumentedControlPlanePortByDefault() {
        assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8080);
    }

    /**
     * Never-firing scheduler: the smoke context must not execute database schedules
     * against the empty H2 schema (#421). The bean name matches Spring Boot's
     * auto-configured scheduler so it takes its place (the auto-config is
     * {@code @ConditionalOnMissingBean}).
     */
    @TestConfiguration
    static class NoopScheduling {

        @Bean
        @Primary
        TaskScheduler taskScheduler() {
            return new TaskScheduler() {
                @Override
                public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
                    return NOOP_FUTURE;
                }

                @Override
                public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
                    return NOOP_FUTURE;
                }

                @Override
                public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
                    return NOOP_FUTURE;
                }

                @Override
                public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
                    return NOOP_FUTURE;
                }

                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
                    return NOOP_FUTURE;
                }

                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                    return NOOP_FUTURE;
                }
            };
        }

        private static final ScheduledFuture<?> NOOP_FUTURE = new ScheduledFuture<Object>() {
            @Override
            public long getDelay(TimeUnit unit) {
                return Long.MAX_VALUE;
            }

            @Override
            public int compareTo(Delayed other) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
        };
    }
}
