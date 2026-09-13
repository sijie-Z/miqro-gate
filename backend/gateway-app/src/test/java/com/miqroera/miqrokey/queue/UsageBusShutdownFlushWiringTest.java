package com.miqroera.miqrokey.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * #451: the usage bus must flush on graceful shutdown — up to a full flush
 * interval of accepted events used to be lost on every restart/deploy because
 * the bean carried no destroy method.
 */
@DisplayName("Usage bus shutdown flush wiring")
class UsageBusShutdownFlushWiringTest {

    @Test
    @DisplayName("the usageEventBus bean declares destroyMethod=flush (#451)")
    void usageBusFlushesOnDestroy() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("miqrokey.gateway.persistence.enabled", "true")));
            ctx.registerBean(DataSource.class, () -> mock(DataSource.class));
            ctx.registerBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));
            ctx.registerBean(Clock.class, Clock::systemUTC);
            ctx.register(QueueConfig.class);
            ctx.refresh();

            BeanDefinition definition = ctx.getBeanDefinition("usageEventBus");
            assertThat(definition.getDestroyMethodName()).isEqualTo("flush");
        }
    }
}
