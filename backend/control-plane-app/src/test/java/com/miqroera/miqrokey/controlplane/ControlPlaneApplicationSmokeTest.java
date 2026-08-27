package com.miqroera.miqrokey.controlplane;

import com.miqroera.miqrokey.controlplane.config.TestCryptoConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal smoke test to verify the Control Plane application context loads.
 *
 * <p>
 * Uses {@code SpringBootTest.WebEnvironment.NONE} to avoid starting an embedded
 * server, since database connectivity is not required for G0.1.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestCryptoConfig.class)
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
}
