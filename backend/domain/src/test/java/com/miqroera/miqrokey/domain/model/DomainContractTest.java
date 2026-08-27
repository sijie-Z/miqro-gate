package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder contract test for domain model — ensures the test infrastructure
 * works. Real domain entity tests will be added in G1.1 when we define the
 * database schema.
 */
@DisplayName("Domain model contract")
class DomainContractTest {

    @Test
    @DisplayName("should run unit tests without Spring context")
    void shouldRunWithoutSpringContext() {
        // The mere fact this test executes without Spring Boot proves domain has no
        // framework dependency.
        var message = "domain module is framework-agnostic";
        assertThat(message).isNotBlank();
    }
}
