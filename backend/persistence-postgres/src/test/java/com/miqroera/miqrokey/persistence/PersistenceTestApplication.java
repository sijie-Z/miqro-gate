package com.miqroera.miqrokey.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot test application for persistence integration tests. Uses
 * only JDBC and Flyway auto-configuration; no web, no JPA, no R2DBC.
 *
 * <p>
 * ObjectMapper is declared explicitly because this module has no spring-web
 * dependency, so
 * {@code JacksonAutoConfiguration.JacksonObjectMapperConfiguration} cannot back
 * the {@code UsageStatsRepositoryImpl} constructor injection.
 * </p>
 */
@SpringBootApplication(exclude = {ServletWebServerFactoryAutoConfiguration.class,
        ReactiveWebServerFactoryAutoConfiguration.class})
public class PersistenceTestApplication {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
