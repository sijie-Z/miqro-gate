package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * #1300 accumulation guard: an abandoned OIDC call must hand its connection
 * back, so that N abandoned logins leave the peer holding the same number of
 * sockets as it did before them.
 *
 * <p>
 * The PH57 sweep {@code PlatformOidcOutboundBudgetTest} proves that the budget
 * ends <em>one</em> call and that the peer sees the client hang up. What the
 * issue left unmeasured is the accumulation half: whether repeated abandons
 * return their connections or pile up until the process is restarted. This
 * class measures it directly, with a peer that counts sockets.
 * </p>
 *
 * <p>
 * Only the peer's socket accounting is asserted. Thread counts are printed —
 * and a {@code jcmd} dump is written to {@code target/} — as context for
 * whoever reads the log; they are too noisy to assert on, and a thread that
 * lingers proves nothing about whether a connection came back.
 * </p>
 *
 * <p>
 * The peer is a raw {@link ServerSocket} rather than a {@code HttpServer}: the
 * only thing that can answer "was the connection handed back" is the number of
 * TCP connections the peer still has open. A handler-level library hides that.
 * Every accepted socket is counted, and the peer learns that the client let go
 * the same way a real IdP would — a drip write into the closed connection
 * fails. Sockets in {@link #openSockets} after the grace period are connections
 * the client never returned.
 * </p>
 *
 * <p>
 * The peer runs on 127.0.0.1 only and no credential leaves the machine.
 * </p>
 */
@DisplayName("#1300 abandoned OIDC calls return every connection they take")
class PlatformOidcAbandonedConnectionAccumulationTest {

    /**
     * Same budget shape as the PH57 sweep: far below any transport idle deadline.
     */
    private static final Duration BUDGET = Duration.ofMillis(1500);

    /** How many calls are abandoned before the accounting is read. */
    private static final int ABANDONS = 8;

    /**
     * How long the peer is given to notice a release after the last budget expired.
     * The client either closes at the budget or not at all, so a longer wait cannot
     * turn a leak into a release.
     */
    private static final long GRACE_MS = 5_000;

    /** Gap between drip bytes from the peer. */
    private static final long DRIP_INTERVAL_MS = 100;

    private static final String CLIENT_SECRET = "ph70 s+cr&et=%/";

    /** Accepted connections still open on the peer, i.e. not handed back. */
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    /** Connections accepted since the peer started: one per call under test. */
    private final AtomicInteger accepted = new AtomicInteger();

    /** Connections whose drip write failed, i.e. the client was no longer there. */
    private final AtomicInteger releasedByClient = new AtomicInteger();

    private final List<Long> abandonWallClockMs = new ArrayList<>();

    private volatile boolean stopping;
    private ServerSocket server;
    private String baseUrl;

    @BeforeEach
    void startPeer() throws IOException {
        stopping = false;
        abandonWallClockMs.clear();
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        baseUrl = "http://127.0.0.1:" + server.getLocalPort();
        Thread.ofVirtual().name("ph70-peer-accept").start(this::acceptLoop);
    }

    @AfterEach
    void stopPeer() throws IOException {
        stopping = true;
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void abandonedCallsReturnEveryConnectionTheyTook() throws Exception {
        // Pay the one-time costs (Mockito's agent attach, first connection setup)
        // before the counting window, so the numbers describe the abandons alone.
        // The peer's canned body carries no "sub", so both outbound calls succeed
        // at the transport and the flow still ends as USERINFO_INVALID — expected
        // here, and not what this class measures.
        try {
            callService("/ok").complete(request(), new MockHttpServletResponse(), "warm-code", "state-value");
        } catch (PlatformOidcAuthService.OAuthFlowException expected) {
            // Warm-up only.
        }

        Snapshot before = snapshot("before");
        for (int i = 1; i <= ABANDONS; i++) {
            PlatformOidcAuthService service = callService("/stall");
            long start = System.nanoTime();
            try {
                service.complete(request(), new MockHttpServletResponse(), "auth-code", "state-value");
                fail("abandon %d/%d: the peer never answers, so the budget must end this call", i, ABANDONS);
            } catch (PlatformOidcAuthService.OAuthFlowException expected) {
                // The budget ended the call; what happens to its connection is the question.
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            abandonWallClockMs.add(elapsed);
            System.out.printf("[ph70] abandon %d/%d returned after %5d ms | peer accepted=%d open=%d released=%d%n", i,
                    ABANDONS, elapsed, accepted.get(), openSockets.size(), releasedByClient.get());
        }

        // Wait for the connections to come back, then read the accounting.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MS);
        while (!openSockets.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Snapshot after = snapshot("after");

        System.out.println("[ph70] --- accounting -------------------------------------------");
        System.out.println("[ph70] " + before);
        System.out.println("[ph70] " + after);
        System.out.printf(
                "[ph70] abandons=%d, connections accepted=%d, still open on the peer=%d, released by the client=%d%n",
                ABANDONS, accepted.get(), after.openConnections(), releasedByClient.get());
        System.out.printf("[ph70] budget was %d ms; wall clock per abandon: %s%n", BUDGET.toMillis(),
                abandonWallClockMs);

        assertThat(after.openConnections())
                .as("connections the peer still holds %d ms after the last budget expired: %s", GRACE_MS, openSockets)
                .isZero();
        assertThat(releasedByClient.get())
                .as("connections the client actually tore down (the peer saw its drip write fail)").isEqualTo(ABANDONS);
    }

    // ------------------------------------------------------------------
    // Peer: counts connections, and reports its own observation of teardown.
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (!stopping) {
            try {
                Socket socket = server.accept();
                accepted.incrementAndGet();
                openSockets.add(socket);
                Thread.ofVirtual().name("ph70-peer-conn").start(() -> serve(socket));
            } catch (IOException e) {
                if (!stopping) {
                    System.out.println("[ph70] peer accept failed: " + e);
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String head = readHead(in);
            if (head.contains(" /ok ")) {
                byte[] body = "{\"access_token\":\"ph70-warm\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                return;
            }
            // A tarpit: real IdP headers, then one byte at a time forever. A
            // per-read idle deadline never trips, so only a whole-operation
            // bound can end this call.
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            drip(out);
        } catch (IOException e) {
            // The client was gone before the tarpit started: a read of its
            // request or a write of its response lost a race with the close.
            // That is not an observation of a handed-back connection, so it is
            // deliberately not counted as one: releasedByClient moves only
            // inside drip().
            System.out.println("[ph70] peer lost the client before dripping: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            openSockets.remove(socket);
        }
    }

    /**
     * Writes one byte every {@link #DRIP_INTERVAL_MS} until the client lets go. A
     * failed write is the only way the peer can see that the connection was handed
     * back, which makes this the one place {@link #releasedByClient} moves.
     */
    private void drip(OutputStream out) throws InterruptedException {
        while (!stopping) {
            try {
                out.write('x');
                out.flush();
            } catch (IOException e) {
                releasedByClient.incrementAndGet();
                return;
            }
            Thread.sleep(DRIP_INTERVAL_MS);
        }
    }

    /** Reads request headers up to the blank line that ends them. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (head.size() >= 4 && head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                break;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Teardown of the peer's own socket.
        }
    }

    // ------------------------------------------------------------------
    // Accounting
    // ------------------------------------------------------------------

    /**
     * What the process looks like at one instant: connections the peer still holds,
     * and the threads that would be pinned by an abandoned call. The thread dump is
     * written to {@code target/} because virtual threads — the ones a blocked
     * reader would occupy — are not listed by {@link Thread#getAllStackTraces()}.
     */
    private Snapshot snapshot(String label) throws Exception {
        int platformThreads = Thread.getAllStackTraces().size();
        int blockedOnSocketRead = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement frame : entry.getValue()) {
                if (frame.getClassName().contains("sun.nio.ch") || frame.getMethodName().contains("read0")) {
                    blockedOnSocketRead++;
                    break;
                }
            }
        }
        String dump = threadDump(label);
        System.out.printf(
                "[ph70] snapshot %-6s open=%d accepted=%d platformThreads=%d platformThreadsInSocketRead=%d jcmd=%s%n",
                label, openSockets.size(), accepted.get(), platformThreads, blockedOnSocketRead, dump);
        return new Snapshot(label, openSockets.size(), accepted.get(), platformThreads, blockedOnSocketRead, dump);
    }

    /**
     * Asks the running JVM for a full thread dump, virtual threads included. The
     * dump is evidence rather than an assertion: a missing {@code jcmd} must not
     * fail the measurement it documents.
     */
    private String threadDump(String label) {
        String path = System.getProperty("user.dir") + "/target/ph70-thread-dump-" + label + ".json";
        try {
            Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/jcmd",
                    String.valueOf(ProcessHandle.current().pid()), "Thread.dump_to_file", "-format=json", "-overwrite",
                    path).redirectErrorStream(true).start();
            // Wait first, read after: jcmd answers in one short line, far below
            // the pipe buffer, so this cannot deadlock on a full pipe — while
            // reading first would block forever on a jcmd that never exits and
            // make the timeout below unreachable.
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return path + " (jcmd did not finish)";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return path + (output.isEmpty() ? "" : " (" + output.replace('\n', ' ') + ")");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unavailable: " + e;
        }
    }

    private record Snapshot(String label, int openConnections, int accepted, int platformThreads,
            int platformThreadsInSocketRead, String threadDumpPath) {

        @Override
        public String toString() {
            return "snapshot " + label + ": openConnections=" + openConnections + " accepted=" + accepted
                    + " platformThreads=" + platformThreads + " platformThreadsInSocketRead="
                    + platformThreadsInSocketRead;
        }
    }

    // ------------------------------------------------------------------
    // The service under test
    // ------------------------------------------------------------------

    /**
     * A callback request carrying the state cookie that {@code complete} checks.
     */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MIQROKEY_OAUTH_STATE", "state-value"));
        return request;
    }

    private PlatformOidcAuthService callService(String path) {
        AuthProperties properties = new AuthProperties();
        properties.setPlatformOidcEnabled(true);
        properties.setPlatformOidcIdpCode("ph70");
        properties.setPlatformOidcClientId("ph70-client");
        properties.setPlatformOidcClientSecret(CLIENT_SECRET);
        properties.setPlatformOidcAuthorizeUri(baseUrl + "/authorize");
        properties.setPlatformOidcTokenUri(baseUrl + path);
        properties.setPlatformOidcUserinfoUri(baseUrl + path);
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
}
