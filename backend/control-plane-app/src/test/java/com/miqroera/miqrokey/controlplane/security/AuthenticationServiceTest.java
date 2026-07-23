package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService")
class AuthenticationServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    PasswordHasher passwordHasher;
    @Mock
    SessionService sessionService;
    @Mock
    AuditService auditService;
    @Mock
    Environment environment;
    AuthProperties authProperties;

    AuthenticationService service;

    static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER_ID = UUID.randomUUID();
    static final byte[] PASSWORD_HASH = "hashed".getBytes();

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setLoginMaxFailures(5);
        authProperties.setLoginLockBase(Duration.ofMinutes(1));
        authProperties.setSessionAbsoluteTimeout(Duration.ofHours(12));
        service = new AuthenticationService(userRepository, passwordHasher, sessionService, auditService,
                authProperties, environment);

        // verifyAgainstDummy always returns false; this is lenient so unknown-user
        // tests don't need to explicitly stub it.
        lenient().when(passwordHasher.verifyAgainstDummy(anyString())).thenReturn(false);
    }

    @Nested
    @DisplayName("Login - success")
    class LoginSuccess {
        @Test
        @DisplayName("should authenticate with valid credentials")
        void shouldAuthenticateValidCredentials() {
            User user = buildActiveUser();
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verify("correct", PASSWORD_HASH)).thenReturn(true);
            when(sessionService.createSession(any())).thenReturn(new SessionToken("sess", "csrf"));

            var result = service.login("admin", "correct", "req-1");

            assertThat(result).isNotNull();
            assertThat(result.user().username()).isEqualTo("admin");
            verify(userRepository).update(any());
        }

        @Test
        @DisplayName("should reset failed login count on success")
        void shouldResetFailedLoginCount() {
            User user = buildUser(3, null, UserStatus.ACTIVE);
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verify("correct", PASSWORD_HASH)).thenReturn(true);
            when(sessionService.createSession(any())).thenReturn(new SessionToken("sess", "csrf"));

            service.login("admin", "correct", "req-1");

            verify(userRepository).update(any());
        }
    }

    @Nested
    @DisplayName("Login - failure indistinguishability")
    class LoginFailure {
        @Test
        @DisplayName("unknown user returns generic message")
        void unknownUserGeneric() {
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "nobody")).thenReturn(Optional.empty());
            when(passwordHasher.verifyAgainstDummy("password")).thenReturn(false);
            assertThatThrownBy(() -> service.login("nobody", "password", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessage(AuthenticationService.LOGIN_FAILED);
        }

        @Test
        @DisplayName("wrong password returns same generic message as unknown user")
        void wrongPasswordGeneric() {
            User user = buildActiveUser();
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verify("wrong", PASSWORD_HASH)).thenReturn(false);
            // recordFailedLogin uses findByIdForUpdate
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.login("admin", "wrong", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessage(AuthenticationService.LOGIN_FAILED);
        }

        @Test
        @DisplayName("disabled user returns same generic message")
        void disabledUserGeneric() {
            User user = buildUser(0, null, UserStatus.DISABLED);
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verifyAgainstDummy("password")).thenReturn(false);
            // recordFailedLogin uses findByIdForUpdate
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.login("admin", "password", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessage(AuthenticationService.LOGIN_FAILED);
        }

        @Test
        @DisplayName("locked account returns same generic message")
        void lockedAccountGeneric() {
            User user = buildUser(5, Instant.now().plus(Duration.ofHours(1)), UserStatus.LOCKED);
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verifyAgainstDummy("password")).thenReturn(false);
            // recordFailedLogin uses findByIdForUpdate
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.login("admin", "password", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessage(AuthenticationService.LOGIN_FAILED);
        }
    }

    @Nested
    @DisplayName("Lockout behavior")
    class Lockout {
        @Test
        @DisplayName("should lock after max failures")
        void shouldLockAfterMaxFailures() {
            User user = buildUser(4, null, UserStatus.ACTIVE);
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verify("wrong", PASSWORD_HASH)).thenReturn(false);
            when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.login("admin", "wrong", "req-1"));
            verify(userRepository, atLeastOnce()).update(any());
        }

        @Test
        @DisplayName("should auto-unlock after lock period and allow login")
        void shouldAutoUnlockAfterExpiry() {
            User user = buildUser(5, Instant.now().minus(Duration.ofMinutes(1)), UserStatus.LOCKED);
            when(userRepository.findByTenantIdAndUsername(TENANT_ID, "admin")).thenReturn(Optional.of(user));
            when(passwordHasher.verify("correct", PASSWORD_HASH)).thenReturn(true);
            when(sessionService.createSession(any())).thenReturn(new SessionToken("sess", "csrf"));

            var result = service.login("admin", "correct", "req-1");
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Password change")
    class PasswordChange {
        @Test
        @DisplayName("should change password and revoke other sessions")
        void shouldChangePassword() {
            User user = buildActiveUser();
            UUID sessionId = UUID.randomUUID();
            when(passwordHasher.verify("old", PASSWORD_HASH)).thenReturn(true);
            when(passwordHasher.hash("NewPass1!")).thenReturn("new-hash".getBytes());

            service.changePassword(user, sessionId, "old", "NewPass1!", "req-1");

            verify(userRepository).update(any());
            verify(sessionService).revokeOtherSessions(USER_ID, sessionId);
        }

        @Test
        @DisplayName("should reject incorrect current password")
        void shouldRejectWrongCurrentPassword() {
            User user = buildActiveUser();
            when(passwordHasher.verify("wrong", PASSWORD_HASH)).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword(user, UUID.randomUUID(), "wrong", "NewPass1!", "req-1"))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("should reject too-short password")
        void shouldRejectShortPassword() {
            User user = buildActiveUser();
            when(passwordHasher.verify("old", PASSWORD_HASH)).thenReturn(true);

            assertThatThrownBy(() -> service.changePassword(user, UUID.randomUUID(), "old", "short", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessageContaining("at least 8");
        }

        @Test
        @DisplayName("should reject common password")
        void shouldRejectCommonPassword() {
            User user = buildActiveUser();
            when(passwordHasher.verify("old", PASSWORD_HASH)).thenReturn(true);

            // "Password1" passes uppercase+lowercase+digit policy but is a common password
            assertThatThrownBy(() -> service.changePassword(user, UUID.randomUUID(), "old", "Password1", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessageContaining("too common");
        }
    }

    @Nested
    @DisplayName("Bootstrap")
    class BootstrapTest {

        Path tempSecretFile;
        static final String BOOTSTRAP_SECRET = "test-bootstrap-secret-with-min-length-16";

        @BeforeEach
        void setUpBootstrap() throws IOException {
            tempSecretFile = Files.createTempFile("bootstrap-secret", ".txt");
            Files.writeString(tempSecretFile, BOOTSTRAP_SECRET);
            authProperties.setBootstrapSecretFile(tempSecretFile.toAbsolutePath().toString());
        }

        @Test
        @DisplayName("should create admin when no users exist and secret matches")
        void shouldBootstrapWhenEmpty() {
            when(userRepository.countByTenantId(TENANT_ID)).thenReturn(0);
            byte[] hash = "temp-hash".getBytes();
            when(passwordHasher.hash(anyString())).thenReturn(hash);
            SessionToken tokens = new SessionToken("sess", "csrf");
            when(sessionService.createSession(any())).thenReturn(tokens);

            var result = service.bootstrap(BOOTSTRAP_SECRET, "admin", "Administrator", "req-1");

            assertThat(result).isNotNull();
            assertThat(result.user().username()).isEqualTo("admin");
            assertThat(result.temporaryPassword()).isNotEmpty();
            assertThat(result.tokens()).isEqualTo(tokens);
            verify(userRepository).lockTenantForBootstrap(TENANT_ID);
            verify(userRepository).insert(any());
        }

        @Test
        @DisplayName("should reject bootstrap when users already exist")
        void shouldRejectWhenUsersExist() {
            // After lock, count > 0 → reject
            when(userRepository.countByTenantId(TENANT_ID)).thenReturn(1);

            assertThatThrownBy(() -> service.bootstrap(BOOTSTRAP_SECRET, "admin", "Administrator", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessageContaining("Bootstrap unavailable");
        }

        @Test
        @DisplayName("should reject bootstrap with wrong secret")
        void shouldRejectWithWrongSecret() {
            when(userRepository.countByTenantId(TENANT_ID)).thenReturn(0);

            assertThatThrownBy(() -> service.bootstrap("not-the-correct-secret-abc", "admin", "Administrator", "req-1"))
                    .isInstanceOf(AuthenticationException.class).hasMessage(AuthenticationService.LOGIN_FAILED);
        }

        @Test
        @DisplayName("should create user with SYSTEM_ADMIN role")
        void shouldCreateSystemAdmin() {
            when(userRepository.countByTenantId(TENANT_ID)).thenReturn(0);
            byte[] hash = "temp-hash".getBytes();
            when(passwordHasher.hash(anyString())).thenReturn(hash);
            when(sessionService.createSession(any())).thenReturn(new SessionToken("sess", "csrf"));

            var result = service.bootstrap(BOOTSTRAP_SECRET, "admin", "Administrator", "req-1");

            assertThat(result.user().role()).isEqualTo(UserRole.SYSTEM_ADMIN);
            assertThat(result.user().mustChangePassword()).isTrue();
            assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("Logout")
    class LogoutTest {
        @Test
        @DisplayName("should revoke session on logout")
        void shouldRevokeSession() {
            User user = buildActiveUser();
            UUID sessionId = UUID.randomUUID();
            service.logout(user, sessionId, "req-1");
            verify(sessionService).revokeSession(sessionId);
        }
    }

    private User buildActiveUser() {
        return buildUser(0, null, UserStatus.ACTIVE);
    }

    private User buildUser(int failedCount, Instant lockedUntil, UserStatus status) {
        return new User(USER_ID, TENANT_ID, "admin", "Admin User", PASSWORD_HASH, UserRole.SYSTEM_ADMIN, status, true,
                failedCount, lockedUntil, null, 0, Instant.now(), Instant.now());
    }
}
