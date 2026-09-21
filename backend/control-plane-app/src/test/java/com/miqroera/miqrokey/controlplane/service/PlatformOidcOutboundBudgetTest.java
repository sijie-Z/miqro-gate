package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PH57 wall-clock bound sweep, platform OIDC leg (#1294): the code-flow
 * callback makes two outbound calls (token exchange, then userinfo) on the
 * Tomcat request thread of {@code GET /api/auth/oauth2/callback}. Both now run
 * under {@code MIQROKEY_PLATFORM_OIDC_HTTP_TIMEOUT} as a whole-operation
 * budget.
 *
 * <p>
 * Every test drives the real service at a local peer that misbehaves in one of
 * the ways a slow identity provider (or a tarpitting middlebox on the path to
 * one) can, and measures the wall clock. The peer runs on 127.0.0.1 only.
 * </p>
 *
 * <p>
 * Only {@code complete()} is on the clock. Building the service is reported but
 * not charged: it is mostly Mockito's inline-mock-maker attaching its agent
 * (measured at ~3.8 s on first use in a JVM here), which production pays once
 * at startup, never inside a request. Charging it to the callback made the
 * first test in the JVM report 5943 ms against a 1500 ms budget while the
 * budget was in fact holding — the number described the harness, not the
 * product. The {@link #warmUpOneTimeCosts() warm-up} moves that cost before the
 * first measurement so every case reports the callback's own wall clock.
 * </p>
 *
 * <p>
 * The budget under test is deliberately far below the transport's per-read idle
 * deadline (observed at ~10 s with reactor-netty), and the deadline each test
 * allows is below that idle value too: a call that only ended because the
 * transport eventually went idle would still fail here.
 * </p>
 */
@DisplayName("PH57 platform OIDC outbound call budgets")
class PlatformOidcOutboundBudgetTest {

    /** The budget configured on the service under test. */
    private static final Duration BUDGET = Duration.ofMillis(1500);

    /**
     * Hard deadline for a bounded call. Above {@link #BUDGET} to leave room for
     * scheduling, below the ~10 s read-idle deadline so that a call which only
     * ended by going idle cannot pass.
     */
    private static final long CALL_DEADLINE_MS = 6_000;

    /** How long a handler holds its response open when nothing releases it. */
    private static final Duration STALL_HOLD = Duration.ofMinutes(10);

    /** Gap between drip bytes; far below any per-read idle window. */
    private static final Duration DRIP_INTERVAL = Duration.ofSeconds(3);

    private volatile boolean peerReleased;
    private long dripDeadline;
    private HttpServer peer;
    private String peerBaseUrl;

    /**
     * Pays the one-time costs — Mockito's agent attach and reactor-netty's global
     * resource init — before the first measurement, so that a cold JVM cannot be
     * read as a call outrunning its budget.
     */
    @BeforeAll
    static void warmUpOneTimeCosts() throws Exception {
        HttpServer warm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        warm.createContext("/token-ok", exchange -> respond(exchange, "{\"access_token\":\"warm\"}"));
        warm.createContext("/userinfo-no-sub", exchange -> respond(exchange, "{}"));
        warm.start();
        try {
            service("http://127.0.0.1:" + warm.getAddress().getPort(), "/token-ok", "/userinfo-no-sub")
                    .complete(request(), new MockHttpServletResponse(), "auth-code", "state-value");
        } catch (RuntimeException expected) {
            // The empty userinfo body is not what is being warmed up here.
        } finally {
            warm.stop(0);
        }
    }

    @BeforeEach
    void startPeer() throws Exception {
        peerReleased = false;
        dripDeadline = System.nanoTime() + STALL_HOLD.toNanos();
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/token-ok", exchange -> respond(exchange, "{\"access_token\":\"ph57-token\"}"));
        // 200 + a declared length, then a fraction of it and a hold.
        peer.createContext("/stall-body", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write("0123456789".getBytes(StandardCharsets.US_ASCII));
                body.flush();
                holdResponseOpen();
            }
        });
        // Accepts the connection and never writes a single header.
        peer.createContext("/no-headers", exchange -> holdResponseOpen());
        // 200 + a declared length, then one byte every few seconds: every read
        // completes well inside any per-read idle window, so only a budget on the
        // whole operation can end this call.
        peer.createContext("/drip", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            try (OutputStream body = exchange.getResponseBody()) {
                while (!peerReleased && System.nanoTime() < dripDeadline) {
                    body.write('x');
                    body.flush();
                    Thread.sleep(DRIP_INTERVAL.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // Answers promptly, but with a userinfo body carrying no sub claim: the
        // ordinary failure path, which must not be turned into a timeout.
        peer.createContext("/userinfo-no-sub", exchange -> respond(exchange, "{}"));
        peer.start();
        peerBaseUrl = "http://127.0.0.1:" + peer.getAddress().getPort();
    }

    @AfterEach
    void stopPeer() {
        peerReleased = true; // release the handler first: stop() joins the dispatcher thread
        if (peer != null) {
            peer.stop(0);
        }
    }

    /** Holds the exchange open until cleanup releases it. */
    private void holdResponseOpen() {
        long deadline = System.nanoTime() + STALL_HOLD.toNanos();
        while (!peerReleased && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // -----------------------------------------------------------------
    // Token exchange: POST {token-uri} with the authorization code
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the token exchange is given up on when the peer drips bytes forever")
    void tokenExchangeIsBoundedAgainstSlowDrip() throws Exception {
        Outcome outcome = measure("exchangeCode (drip)", () -> service(peerBaseUrl, "/drip", "/drip"));

        assertTimedOut(outcome, "AUTH_ERROR");
    }

    @Test
    @DisplayName("the token exchange is given up on when no response headers ever arrive")
    void tokenExchangeIsBoundedWhenNoHeadersArrive() throws Exception {
        Outcome outcome = measure("exchangeCode (no headers)",
                () -> service(peerBaseUrl, "/no-headers", "/no-headers"));

        assertTimedOut(outcome, "AUTH_ERROR");
    }

    @Test
    @DisplayName("the token exchange is given up on when the peer stalls mid-body")
    void tokenExchangeIsBoundedAgainstStalledBody() throws Exception {
        Outcome outcome = measure("exchangeCode (stalled body)",
                () -> service(peerBaseUrl, "/stall-body", "/stall-body"));

        assertTimedOut(outcome, "AUTH_ERROR");
    }

    // -----------------------------------------------------------------
    // userinfo: GET {userinfo-uri} with the access token
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the userinfo call is given up on when the peer drips bytes forever")
    void userinfoIsBoundedAgainstSlowDrip() throws Exception {
        Outcome outcome = measure("fetchUserinfo (drip)", () -> service(peerBaseUrl, "/token-ok", "/drip"));

        assertTimedOut(outcome, "USERINFO_INVALID");
    }

    @Test
    @DisplayName("the userinfo call is given up on when no response headers ever arrive")
    void userinfoIsBoundedWhenNoHeadersArrive() throws Exception {
        Outcome outcome = measure("fetchUserinfo (no headers)", () -> service(peerBaseUrl, "/token-ok", "/no-headers"));

        assertTimedOut(outcome, "USERINFO_INVALID");
    }

    // -----------------------------------------------------------------
    // The budget must not touch calls that answer
    // -----------------------------------------------------------------

    /**
     * The other half of the contract: a peer that answers inside the budget is
     * still parsed and still fails for its own reason. Without this, a "fix" that
     * timed everything out would pass the tests above.
     */
    @Test
    @DisplayName("a peer that answers promptly keeps its own failure code, not a timeout")
    void promptPeerIsUnaffected() throws Exception {
        Outcome outcome = measure("fetchUserinfo (prompt, no sub)",
                () -> service(peerBaseUrl, "/token-ok", "/userinfo-no-sub"));

        assertThat(outcome.failure()).as("a prompt answer must fail on its own terms").isNotNull();
        assertThat(outcome.errorCode()).as("failure code for a sub-less userinfo body").isEqualTo("USERINFO_INVALID");
        assertThat(outcome.elapsedMs()).as("returned after %d ms: a prompt answer must not be charged the %d ms budget",
                outcome.elapsedMs(), BUDGET.toMillis()).isLessThan(BUDGET.toMillis());
    }

    // -----------------------------------------------------------------

    /**
     * The service as production builds it. The collaborator mocks are never
     * reached: every call under test fails (or is given up on) before the flow gets
     * past the two outbound calls.
     */
    private static PlatformOidcAuthService service(String baseUrl, String tokenPath, String userinfoPath) {
        AuthProperties properties = new AuthProperties();
        properties.setPlatformOidcEnabled(true);
        properties.setPlatformOidcIdpCode("ph57");
        properties.setPlatformOidcClientId("ph57-client");
        properties.setPlatformOidcClientSecret("ph57-secret");
        properties.setPlatformOidcAuthorizeUri(baseUrl + "/authorize");
        properties.setPlatformOidcTokenUri(baseUrl + tokenPath);
        properties.setPlatformOidcUserinfoUri(baseUrl + userinfoPath);
        properties.setPlatformOidcRedirectUri("http://127.0.0.1:18690/api/auth/oauth2/callback");
        properties.setPlatformOidcHttpTimeout(BUDGET);
        return new PlatformOidcAuthService(properties,
                mock(com.miqroera.miqrokey.domain.repository.UserRepository.class),
                mock(com.miqroera.miqrokey.domain.service.PasswordHasher.class),
                mock(com.miqroera.miqrokey.controlplane.security.SessionService.class),
                mock(com.miqroera.miqrokey.domain.service.AuditService.class),
                mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class));
    }

    /**
     * A callback request carrying the state cookie that {@code complete} checks.
     */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MIQROKEY_OAUTH_STATE", "state-value"));
        return request;
    }

    /** What one measured call did, in wall clock and in failure code. */
    private record Outcome(long elapsedMs, Throwable failure) {

        String errorCode() {
            return failure instanceof PlatformOidcAuthService.OAuthFlowException e ? e.code() : null;
        }
    }

    /**
     * Builds the service, then measures the callback alone. The build is printed
     * next to the measurement and deliberately kept out of it: it carries the
     * one-time Mockito agent attach, which is a property of this JVM rather than of
     * the call under test.
     */
    private Outcome measure(String label, Supplier<PlatformOidcAuthService> factory) throws Exception {
        long buildStart = System.nanoTime();
        PlatformOidcAuthService service = factory.get();
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        return measure(label, buildMs, () -> service.complete(request, response, "auth-code", "state-value"));
    }

    /**
     * Runs {@code call} on its own thread and reports the wall clock. A call still
     * running when the deadline passes is reported as a negative value so
     * {@link #assertTimedOut} can fail with the honest lower bound.
     */
    private static Outcome measure(String label, long buildMs, Runnable call) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Thread> runner = new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ph57-oidc-measure");
            thread.setDaemon(true);
            runner.set(thread);
            return thread;
        });
        long start = System.nanoTime();
        Future<?> future = executor.submit(call);
        try {
            future.get(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[ph57] %-40s build %5d ms | returned after %6d ms%n", label, buildMs, elapsed);
            return new Outcome(elapsed, null);
        } catch (ExecutionException e) {
            // Ending by failing is still ending; name the failure so the number
            // cannot be mistaken for a clean completion.
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[ph57] %-40s build %5d ms | returned after %6d ms, failing with %s%n", label, buildMs,
                    elapsed, e.getCause().getClass().getSimpleName());
            return new Outcome(elapsed, e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[ph57] %-40s build %5d ms | STILL BLOCKED after %6d ms — call never returned%n", label,
                    buildMs, elapsed);
            return new Outcome(-elapsed, e);
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void assertTimedOut(Outcome outcome, String expectedCode) {
        // A call still running at the deadline is reported as a negative number,
        // which is the defect itself — assert it before anything that a negative
        // value could satisfy by accident.
        assertThat(outcome.elapsedMs())
                .as("%s: the call has to end inside %d ms — nothing bounds it as a whole operation otherwise",
                        expectedCode, CALL_DEADLINE_MS)
                .isPositive().isLessThan(CALL_DEADLINE_MS);
        assertThat(outcome.errorCode())
                .as("%s: an overrunning call is reported as a flow failure so the login page can redirect",
                        expectedCode)
                .isEqualTo(expectedCode);
    }
}
