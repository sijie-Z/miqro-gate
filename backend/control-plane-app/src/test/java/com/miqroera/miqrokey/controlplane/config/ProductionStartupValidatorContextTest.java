package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.testconfig.ProductionValidatorTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-startup tests proving the Spring container refuses to start when
 * production-mode security constraints are violated.
 *
 * <p>
 * The {@link ProductionStartupValidator} is called via {@code @PostConstruct},
 * so an invalid configuration causes the {@code ApplicationContext} to fail
 * before any bean is available. These tests use
 * {@link ApplicationContextRunner} to assert context creation outcome.
 * </p>
 */
@DisplayName("Production startup validator context tests")
class ProductionStartupValidatorContextTest {

    // -------------------------------------------------------------------
    // Valid configuration
    // -------------------------------------------------------------------

    @Test
    @DisplayName("valid production config (https non-localhost) starts successfully")
    void validConfigStarts() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://admin.example.com:8443").run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    // -------------------------------------------------------------------
    // cookieSecure
    // -------------------------------------------------------------------

    @Test
    @DisplayName("cookieSecure=false in production rejects context")
    void cookieSecureFalseRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=false", "miqrokey.origin-allowlist[0]=https://example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("cookieSecure=true");
                });
    }

    // -------------------------------------------------------------------
    // Null/empty allowlist (harder to set via properties, uses direct beans)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("empty allowlist in production rejects context")
    void emptyAllowlistRejectsContext() {
        AuthProperties emptyAllowlist = new AuthProperties();
        emptyAllowlist.setProduction(true);
        emptyAllowlist.setCookieSecure(true);
        emptyAllowlist.setOriginAllowlist(List.of());

        new ApplicationContextRunner().withBean(AuthProperties.class, () -> emptyAllowlist)
                .withBean(ProductionStartupValidator.class,
                        () -> new ProductionStartupValidator(emptyAllowlist, new MockEnvironment()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("non-empty");
                });
    }

    @Test
    @DisplayName("null allowlist in production rejects context")
    void nullAllowlistRejectsContext() {
        AuthProperties nullAllowlist = new AuthProperties();
        nullAllowlist.setProduction(true);
        nullAllowlist.setCookieSecure(true);
        nullAllowlist.setOriginAllowlist(null);

        new ApplicationContextRunner().withBean(AuthProperties.class, () -> nullAllowlist)
                .withBean(ProductionStartupValidator.class,
                        () -> new ProductionStartupValidator(nullAllowlist, new MockEnvironment()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("non-empty");
                });
    }

    // -------------------------------------------------------------------
    // Localhost-only
    // -------------------------------------------------------------------

    @Test
    @DisplayName("localhost-only allowlist in production rejects context")
    void localhostOnlyRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=http://localhost:5173",
                "miqrokey.origin-allowlist[1]=http://127.0.0.1:8080").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("non-localhost");
                });
    }

    // -------------------------------------------------------------------
    // HTTP non-localhost
    // -------------------------------------------------------------------

    @Test
    @DisplayName("HTTP non-localhost origin in production rejects context")
    void httpNonLocalhostRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=http://insecure.example.com").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("HTTP non-localhost");
                });
    }

    // -------------------------------------------------------------------
    // Path / trailing slash
    // -------------------------------------------------------------------

    @Test
    @DisplayName("origin with path in production rejects context")
    void originWithPathRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://example.com/admin/login").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("path");
                });
    }

    @Test
    @DisplayName("origin with trailing slash in production rejects context")
    void originWithTrailingSlashRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://example.com/").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("path");
                });
    }

    // -------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------

    @Test
    @DisplayName("origin with query in production rejects context")
    void originWithQueryRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://example.com?foo=bar").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("query");
                });
    }

    // -------------------------------------------------------------------
    // Fragment
    // -------------------------------------------------------------------

    @Test
    @DisplayName("origin with fragment in production rejects context")
    void originWithFragmentRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://example.com#section").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("fragment");
                });
    }

    // -------------------------------------------------------------------
    // Userinfo
    // -------------------------------------------------------------------

    @Test
    @DisplayName("origin with userinfo in production rejects context")
    void originWithUserinfoRejectsContext() {
        runner().withPropertyValues("miqrokey.cookie-secure=true", "miqrokey.origin-allowlist[0]=https://example.com",
                "miqrokey.origin-allowlist[1]=https://user:pass@example.com").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("userinfo");
                });
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Shared runner with production mode enabled and the validator wired via
     * property binding. Uses {@link ProductionValidatorTestConfig} which lives
     * outside the {@code com.miqroera.miqrokey} component scan base package.
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ProductionValidatorTestConfig.class)
                .withPropertyValues("miqrokey.production=true");
    }

    /**
     * Traverse the cause chain to find the root cause message for assertion.
     */
    private static String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
