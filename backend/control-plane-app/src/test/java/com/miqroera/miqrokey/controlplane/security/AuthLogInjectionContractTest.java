package com.miqroera.miqrokey.controlplane.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PH66 contract: a username is caller-supplied free text and reaches
 * {@code AuthenticationService} log lines verbatim. A JSON {@code "\n"} is a
 * legal escape that decodes to a real U+000A, so one SLF4J event renders as
 * several physical lines — the extra ones imitate the gateway's own format.
 *
 * <p>
 * Both endpoints under test are reachable without any credential:
 * {@code /api/v1/auth/register} is CSRF-exempt and open by default, and
 * {@code /api/v1/auth/login} is public by nature. The forged line therefore
 * needs no account at all.
 * </p>
 *
 * <p>
 * No {@code miqrokey.registration-enabled} override on purpose: the point is
 * that the default configuration is exposed.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("AuthenticationService log lines cannot be forged by a crafted username")
class AuthLogInjectionContractTest {

    private static final Path SECRET_FILE;
    private static final String SECRET = "test-bootstrap-secret-min-16chars";
    static {
        try {
            SECRET_FILE = Files.createTempFile("bootstrap-secret", ".txt");
            Files.writeString(SECRET_FILE, SECRET);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> SECRET_FILE.toAbsolutePath().toString());
    }

    /** The forged tail: it must never become a physical log line of its own. */
    private static final String FORGED = "2026-09-21T00:00:00.000Z ERROR 1 --- [main] aigw.audit : FORGED-BY-PH66";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        resetDb();
        serviceLogger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(appender);
        resetDb();
    }

    @Test
    @DisplayName("a username with a line break cannot forge a log line via self-registration")
    void registerWithLineBreakCannotForgeALogLine() throws Exception {
        String payload = "newbie\n" + FORGED;

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":" + jsonString(payload) + ",\"password\":\"StrongPass2026!\"}"))
                .andExpect(status().isCreated());

        ILoggingEvent event = singleEventContaining("self-registered");
        report(event);
        assertSinglePhysicalLine(event);
    }

    @Test
    @DisplayName("a username with a line break cannot forge a log line via login")
    void loginWithLineBreakUsernameCannotForgeALogLine() throws Exception {
        String payload = "returning\n" + FORGED;
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":" + jsonString(payload) + ",\"password\":\"StrongPass2026!\"}"))
                .andExpect(status().isCreated());
        appender.list.clear();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":" + jsonString(payload) + ",\"password\":\"StrongPass2026!\"}"))
                .andExpect(status().isOk());

        ILoggingEvent event = singleEventContaining("logged in successfully");
        report(event);
        assertSinglePhysicalLine(event);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ILoggingEvent singleEventContaining(String needle) {
        List<ILoggingEvent> hits = appender.list.stream().filter(e -> e.getFormattedMessage().contains(needle))
                .toList();
        assertThat(hits).as("exactly one '%s' event", needle).hasSize(1);
        return hits.get(0);
    }

    /** Prints the raw event text the appender would render (PH66 evidence). */
    private static void report(ILoggingEvent event) {
        String raw = event.getFormattedMessage();
        System.out.println(">>>BEGIN");
        System.out.println(raw);
        System.out.println("<<<END");
        System.out.println(
                "---- physical lines rendered from that single event: " + raw.split("\n", -1).length + " ----");
        for (String line : raw.split("\n", -1)) {
            System.out.println("LINE| " + line);
        }
    }

    private static void assertSinglePhysicalLine(ILoggingEvent event) {
        String raw = event.getFormattedMessage();
        assertThat(raw).as("the caller-supplied username must not reach the log line verbatim").doesNotContain("\n")
                .doesNotContain("\r");
    }

    /** Escapes a raw Java string into a JSON string literal. */
    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private void resetDb() {
        for (String table : List.of("user_sessions", "admin_audit_events", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // first run: tables may not exist yet
            }
        }
    }
}
