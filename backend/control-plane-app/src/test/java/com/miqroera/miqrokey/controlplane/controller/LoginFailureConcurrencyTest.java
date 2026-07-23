package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Login failure concurrency tests")
class LoginFailureConcurrencyTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        String secretPath = AuthIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString();
        registry.add("miqrokey.bootstrap-secret-file", () -> secretPath);
        registry.add("miqrokey.login-max-failures", () -> "3");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        try {
            jdbc.update("DELETE FROM user_sessions", new MapSqlParameterSource());
            jdbc.update("DELETE FROM users", new MapSqlParameterSource());
        } catch (Exception ignored) {
        }
    }

    @Test
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    @DisplayName("concurrent failed logins produce deterministic counter without lost updates")
    void concurrentFailuresDeterministic() throws Exception {
        String username = "lockuser_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username, "Test"))))
                .andExpect(status().isCreated());

        int concurrentAttempts = 4;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrentAttempts; i++) {
            tasks.add(() -> {
                latch.await();
                MvcResult r = mockMvc
                        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new LoginRequest(username, "wrong-password"))))
                        .andReturn();
                return r.getResponse().getStatus();
            });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(executor.submit(task));
        }

        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        for (Future<Integer> f : futures) {
            assertThat(f.get(1, TimeUnit.SECONDS)).isEqualTo(401);
        }

        Integer failCount = jdbc.queryForObject("SELECT failed_login_count FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), Integer.class);
        assertThat(failCount).as("no lost updates: counter must equal concurrent attempts")
                .isEqualTo(concurrentAttempts);

        String status = jdbc.queryForObject("SELECT status FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), String.class);
        assertThat(status).isEqualTo("LOCKED");
    }

    @Test
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    @DisplayName("sequential failed logins produce exact durable count in DB")
    void sequentialFailuresExactCount() throws Exception {
        String username = "sequser_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username, "Test"))))
                .andExpect(status().isCreated());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(username, "wrong"))))
                    .andExpect(status().isUnauthorized());
        }

        Integer failCount = jdbc.queryForObject("SELECT failed_login_count FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), Integer.class);
        assertThat(failCount).isEqualTo(3);
    }
}
