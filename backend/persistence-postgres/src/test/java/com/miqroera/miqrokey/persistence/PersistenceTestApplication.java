package com.miqroera.miqrokey.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;

/**
 * Minimal Spring Boot test application for persistence integration tests. Uses
 * only JDBC and Flyway auto-configuration; no web, no JPA, no R2DBC.
 */
@SpringBootApplication(exclude = {ServletWebServerFactoryAutoConfiguration.class,
        ReactiveWebServerFactoryAutoConfiguration.class})
public class PersistenceTestApplication {
}
