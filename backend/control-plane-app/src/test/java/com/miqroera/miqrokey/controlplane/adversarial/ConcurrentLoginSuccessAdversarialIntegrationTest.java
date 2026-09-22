package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1334: two logins that overlap in the Argon2 window used to be mutually
 * exclusive the hard way. The success path read an unlocked snapshot of the
 * user row, hashed the password (0.65–0.88&nbsp;s), then wrote the snapshot
 * back with a version predicate — so the slower request's
 * {@code UPDATE ... WHERE version = ?} matched zero rows and a <em>correct</em>
 * password came back as HTTP 409 {@code CONCURRENT_MODIFICATION}. Measured
 * before the fix: N=2 failed exactly one request in five out of five rounds,
 * N=3 exactly two in three out of three.
 *
 * <p>
 * The positive half is the one that matters here: multi-session per user is by
 * design ({@code /auth/logout-others} exists, and logins from two devices are
 * ordinary), so a burst of concurrent logins with the right password must hand
 * out one working session per request — never reject a legitimate credential.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: concurrent logins with correct credentials all succeed (PostgreSQL)")
class ConcurrentLoginSuccessAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    private static final String PASSWORD = "NewSecurePass1!";

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("N concurrent logins with the correct password all return 200 with their own usable session")
    void concurrentLoginsAllSucceed() throws Exception {
        String username = "race_" + UUID.randomUUID().toString().substring(0, 8);
        bootstrapAndSetPassword(username);
        long versionBefore = versionOf(username);

        int concurrentAttempts = 4;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentAttempts; i++) {
            tasks.add(() -> {
                latch.await();
                return mockMvc
                        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                        .andReturn();
            });
        }

        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(executor.submit(task));
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get(1, TimeUnit.SECONDS));
        }

        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus())
                    .as("a correct password must never be answered with 409 CONCURRENT_MODIFICATION: %s",
                            r.getResponse().getContentAsString())
                    .isEqualTo(200);
        }

        Set<String> tokens = new HashSet<>();
        for (MvcResult r : results) {
            Cookie session = cookie(r, "MIQROKEY_SESSION");
            assertThat(session).as("each successful login hands out a session cookie").isNotNull();
            tokens.add(session.getValue());
        }
        assertThat(tokens).as("four concurrent logins = four distinct sessions").hasSize(concurrentAttempts);

        // Each issued session must actually work — a token that cannot pass its
        // own follow-up request is not a login.
        for (String token : tokens) {
            mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("MIQROKEY_SESSION", token)))
                    .andExpect(status().isOk());
        }

        assertThat(statusOf(username)).isEqualTo("ACTIVE");
        assertThat(failedLoginCountOf(username)).as("correct passwords must not be counted as failures").isZero();
        assertThat(versionOf(username) - versionBefore)
                .as("one bookkeeping write per successful login — no lost update").isEqualTo(concurrentAttempts);
    }

    // The companion gate — a DISABLE or a fresh lockout landing *inside* the
    // hashing window — cannot be triggered deterministically through HTTP (it
    // needs a hook between the snapshot read and the bookkeeping transaction), so
    // it lives in AuthenticationServiceTest.SuccessfulLoginConcurrency, where the
    // locked re-read can be stubbed directly.

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void bootstrapAndSetPassword(String username) throws Exception {
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdversarialTestSupport.secret(), username, "Race User"))))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = cookie(boot, "MIQROKEY_SESSION");
        Cookie csrf = cookie(boot, "MIQROKEY_CSRF");
        assertThat(session).as("bootstrap hands out a session").isNotNull();
        String tempPassword = map(boot).get("temporaryPassword").toString();

        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(session, csrf)
                .header("X-CSRF-Token", csrf != null ? csrf.getValue() : "")
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, PASSWORD))))
                .andExpect(status().isOk());
    }

    private long versionOf(String username) {
        Long v = jdbc.queryForObject("SELECT version FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), Long.class);
        return v == null ? -1 : v;
    }

    private String statusOf(String username) {
        return jdbc.queryForObject("SELECT status FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), String.class);
    }

    private int failedLoginCountOf(String username) {
        Integer c = jdbc.queryForObject("SELECT failed_login_count FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), Integer.class);
        return c == null ? -1 : c;
    }
}
