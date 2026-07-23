package com.miqroera.testconfig;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.config.ProductionStartupValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Test configuration for ProductionStartupValidator context tests.
 *
 * <p>
 * This class lives in {@code com.miqroera.testconfig} — outside the
 * {@code com.miqroera.miqrokey} component scan base package — so it is never
 * picked up by {@code @SpringBootTest} context loading. It is only used when
 * explicitly referenced via
 * {@code ApplicationContextRunner.withUserConfiguration}.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class ProductionValidatorTestConfig {

    @Bean
    ProductionStartupValidator productionStartupValidator(AuthProperties props, Environment env) {
        return new ProductionStartupValidator(props, env);
    }
}
