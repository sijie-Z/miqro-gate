package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Custom CSRF cookie name tests")
class CustomCsrfCookieNameTest {

    static final String CUSTOM_CSRF_NAME = "MY_CUSTOM_CSRF";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        String secretPath = AuthIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString();
        registry.add("miqrokey.bootstrap-secret-file", () -> secretPath);
        registry.add("miqrokey.csrf-cookie-name", () -> CUSTOM_CSRF_NAME);
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
    @DisplayName("GET /csrf returns token from custom cookie name")
    void csrfReturnsTokenUsingCustomCookieName() throws Exception {
        String username = "customcsrf_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        MvcResult bootR = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username, "Test"))))
                .andReturn();

        Cookie csrfCookie = null;
        Cookie sessionCookie = null;
        if (bootR.getResponse().getCookies() != null) {
            for (Cookie c : bootR.getResponse().getCookies()) {
                if (CUSTOM_CSRF_NAME.equals(c.getName())) {
                    csrfCookie = c;
                } else if ("MIQROKEY_SESSION".equals(c.getName())) {
                    sessionCookie = c;
                }
            }
        }
        assertThat(csrfCookie).as("CSRF cookie with custom name must be present").isNotNull();
        assertThat(sessionCookie).as("Session cookie must be present").isNotNull();

        if (bootR.getResponse().getCookies() != null) {
            for (Cookie c : bootR.getResponse().getCookies()) {
                assertThat(c.getName()).as("default CSRF cookie name should not appear").isNotEqualTo("MIQROKEY_CSRF");
            }
        }

        // Send both session + CSRF cookies so AuthController can read the CSRF cookie
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf").cookie(sessionCookie, csrfCookie)).andReturn();
        Map<?, ?> csrfBody = objectMapper.readValue(csrfResult.getResponse().getContentAsString(), Map.class);
        String csrfToken = (String) csrfBody.get("token");
        assertThat(csrfToken).isNotEmpty();
        assertThat(csrfToken).isEqualTo(csrfCookie.getValue());
    }
}
