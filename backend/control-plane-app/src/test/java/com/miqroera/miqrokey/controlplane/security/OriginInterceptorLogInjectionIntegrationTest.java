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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Origin rejection lines are the one place where the control plane logs a
 * caller-controlled <i>path</i>: {@link RequestPaths#lookupPath} is the decoded
 * lookup path, so {@code %0A} in the URI arrives at the log sink as a real
 * newline and {@code %20}/{@code %5B} as ordinary characters. A client that can
 * reach the interceptor (any authenticated session) can therefore shape what a
 * log collector sees as a whole line — CWE-117, the same class as the gateway's
 * {@code aigw.mcp.*} sinks.
 *
 * <p>
 * The in-repo precedent for the required behaviour is
 * {@code ApiKeyAuthFilter.forLog}: "Control characters are flattened so a
 * crafted value cannot forge extra log lines". This test drives a real Tomcat
 * over a real socket — MockMvc would not exercise URI decoding, which is the
 * whole point — and reads the line back from Logback.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Origin rejection logs flatten the decoded path instead of letting it forge a line")
class OriginInterceptorLogInjectionIntegrationTest extends AbstractControlPlaneIntegrationTest {

    static final Path SECRET_FILE;
    static final String SECRET = "test-bootstrap-secret-min-16chars";
    static {
        try {
            SECRET_FILE = Files.createTempFile("bootstrap-secret", ".txt");
            Files.writeString(SECRET_FILE, SECRET);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final Logger INTERCEPTOR_LOGGER = (Logger) LoggerFactory.getLogger(OriginInterceptor.class);

    /** Text planted after an encoded newline; it must stay on the same log line. */
    private static final String MARKER = "FORGED-SECOND-LINE";

    /**
     * A mapped POST that ends in a path variable, so handler resolution does not
     * depend on a catch-all resource handler; {@code %0A} decodes inside the
     * {@code {agentId}} segment and the mapping still matches.
     */
    private static final String CRAFTED_PATH = "/api/v1/admin/agents/AAAA%0A" + MARKER + "%20%5BINFO%5D/disable";

    @LocalServerPort
    int port;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @DynamicPropertySource
    public static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> SECRET_FILE.toAbsolutePath().toString());
    }

    @BeforeEach
    void setUp() throws Exception {
        clean();
        appender.start();
        INTERCEPTOR_LOGGER.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        INTERCEPTOR_LOGGER.detachAppender(appender);
        appender.stop();
        clean();
    }

    @Test
    @DisplayName("an encoded newline in the path cannot split the Origin rejection log line")
    void encodedNewlineInPathCannotForgeALogLine() throws Exception {
        String cookie = bootstrapSession();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(CRAFTED_PATH)).header("Origin", "http://evil.example")
                        .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        List<ILoggingEvent> lines = List.copyOf(appender.list);
        System.out.println("---- raw OriginInterceptor log lines for " + CRAFTED_PATH + " ----");
        for (ILoggingEvent line : lines) {
            System.out.println("[flush  ] " + line.getFormattedMessage());
            System.out.println("[escaped] " + line.getFormattedMessage().replace("\r", "\\r").replace("\n", "\\n"));
        }
        System.out.println("[status ] " + response.statusCode() + " " + response.body());
        System.out.println("--------------------------------------------------------------");

        // The request really took the Origin rejection branch (interceptor runs
        // before CSRF, so no CSRF token is needed to reach it).
        assertThat(response.statusCode()).as("body was: %s", response.body()).isEqualTo(403);
        assertThat(response.body()).contains("ORIGIN_REJECTED");

        ILoggingEvent rejection = lines.stream()
                .filter(line -> line.getFormattedMessage().contains("Origin not in allowlist")).findFirst()
                .orElseThrow(() -> new AssertionError("Origin rejection was not logged: " + lines));

        assertThat(rejection.getFormattedMessage()).as("a control character from the URI must not reach the log")
                .doesNotContain("\r").doesNotContain("\n");

        // Still usable for debugging: the path is logged, just flattened.
        assertThat(rejection.getFormattedMessage()).contains(MARKER).contains("POST");
    }

    /**
     * Bootstrap + password change over real HTTP, returning the session cookie
     * header.
     */
    private String bootstrapSession() throws Exception {
        HttpResponse<String> boot = http.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/bootstrap")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"bootstrapSecret\":\"" + SECRET
                                + "\",\"username\":\"root\",\"displayName\":\"Admin\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(boot.statusCode()).as("bootstrap responded: %s", boot.body()).isEqualTo(201);

        String session = cookieValue(boot, "MIQROKEY_SESSION");
        String csrf = cookieValue(boot, "MIQROKEY_CSRF");
        String temporaryPassword = jsonField(boot.body(), "temporaryPassword");
        assertThat(session).isNotBlank();

        // SessionFilter's mustChangePassword gate 401s every other path until the
        // bootstrap password is rotated, so the session must be made usable first.
        HttpResponse<String> changed = http.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/password")).header("Content-Type", "application/json")
                        .header("Cookie", "MIQROKEY_SESSION=" + session + "; MIQROKEY_CSRF=" + csrf)
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"" + temporaryPassword
                                + "\",\"newPassword\":\"DrillPass2026!\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).as("password change responded: %s", changed.body()).isEqualTo(200);
        return "MIQROKEY_SESSION=" + session;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * Single flat JSON field; the bootstrap body holds no nesting worth a parser.
     */
    private static String jsonField(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s missing from %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        Optional<String> cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst();
        return cookie.map(value -> value.split(";", 2)[0].substring(name.length() + 1)).orElse("");
    }

    private void clean() {
        for (String table : new String[]{"agents", "usage_event", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "unattributed_policy", "upstream_credential_versions",
                "upstream_credentials", "quota_snapshots", "upstream_subscriptions", "project_repositories", "projects",
                "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }
}
