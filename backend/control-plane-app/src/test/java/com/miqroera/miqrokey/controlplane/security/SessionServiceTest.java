package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserSession;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService")
class SessionServiceTest {

    @Mock
    UserSessionRepository sessionRepository;
    AuthProperties authProperties;
    SessionService service;

    static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER_ID = UUID.randomUUID();
    static final UUID SESSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setSessionCookieName("MIQROKEY_SESSION");
        authProperties.setCsrfCookieName("MIQROKEY_CSRF");
        authProperties.setSessionAbsoluteTimeout(Duration.ofHours(12));
        authProperties.setSessionIdleTimeout(Duration.ofMinutes(30));
        service = new SessionService(sessionRepository, authProperties);
    }

    @Nested
    @DisplayName("Session creation")
    class CreateSession {
        @Test
        @DisplayName("should create session with 32-byte tokens")
        void shouldCreateSession() {
            User user = buildUser();
            when(sessionRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            SessionToken tokens = service.createSession(user);

            assertThat(tokens.sessionToken()).isNotEmpty();
            assertThat(tokens.csrfToken()).isNotEmpty();
            // Hex encoding of 32 bytes = 64 chars
            assertThat(tokens.sessionToken()).hasSize(64);
            assertThat(tokens.csrfToken()).hasSize(64);
            assertThat(tokens.sessionToken()).isNotEqualTo(tokens.csrfToken());
        }

        @Test
        @DisplayName("should store token digests, not raw tokens")
        void shouldStoreDigests() {
            User user = buildUser();
            ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
            when(sessionRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

            SessionToken tokens = service.createSession(user);
            verify(sessionRepository).insert(captor.capture());

            UserSession saved = captor.getValue();
            byte[] expectedDigest = SessionService.sha256(SessionService.hexToBytes(tokens.sessionToken()));
            assertThat(MessageDigest.isEqual(saved.tokenDigest(), expectedDigest)).isTrue();
            // Raw token should NOT match the digest
            assertThat(MessageDigest.isEqual(tokens.sessionToken().getBytes(), saved.tokenDigest())).isFalse();
        }
    }

    @Nested
    @DisplayName("Session lookup")
    class Lookup {
        @Test
        @DisplayName("should find session by valid token")
        void shouldFindByToken() {
            byte[] digest = SessionService.sha256(
                    SessionService.hexToBytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            UserSession session = new UserSession(SESSION_ID, TENANT_ID, USER_ID, digest, new byte[32], Instant.now(),
                    Instant.now(), Instant.now().plus(Duration.ofHours(1)), null);
            when(sessionRepository.findByTokenDigest(any())).thenReturn(Optional.of(session));

            var found = service.findByToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            assertThat(found).isPresent();
        }

        @Test
        @DisplayName("should return empty for null token")
        void shouldReturnEmptyForNull() {
            assertThat(service.findByToken(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty for invalid hex")
        void shouldReturnEmptyForInvalidHex() {
            assertThat(service.findByToken("not-hex")).isEmpty();
        }

        @Test
        @DisplayName("should return empty for wrong-length token")
        void shouldReturnEmptyForWrongLength() {
            assertThat(service.findByToken("aabb")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cookie attributes")
    class Cookies {
        @Test
        @DisplayName("should set HttpOnly session cookie")
        void shouldSetHttpOnlySessionCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            SessionToken tokens = new SessionToken("sess-token-hex", "csrf-token-hex");

            service.setCookies(response, tokens, Instant.now().plus(Duration.ofHours(12)));

            Cookie sessionCookie = response.getCookie("MIQROKEY_SESSION");
            assertThat(sessionCookie).isNotNull();
            assertThat(sessionCookie.isHttpOnly()).isTrue();
            assertThat(sessionCookie.getSecure()).isFalse(); // default dev mode
            assertThat(sessionCookie.getPath()).isEqualTo("/");
            assertThat(sessionCookie.getMaxAge()).isPositive();
        }

        @Test
        @DisplayName("should set non-HttpOnly CSRF cookie")
        void shouldSetNonHttpOnlyCsrfCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            SessionToken tokens = new SessionToken("sess-token-hex", "csrf-token-hex");

            service.setCookies(response, tokens, Instant.now().plus(Duration.ofHours(12)));

            Cookie csrfCookie = response.getCookie("MIQROKEY_CSRF");
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.isHttpOnly()).isFalse();
        }

        @Test
        @DisplayName("should clear cookies on logout with SameSite")
        void shouldClearCookies() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            service.clearCookies(response);

            Cookie sessionCookie = response.getCookie("MIQROKEY_SESSION");
            assertThat(sessionCookie).isNotNull();
            assertThat(sessionCookie.getMaxAge()).isZero();
            assertThat(sessionCookie.getValue()).isEmpty();
            assertThat(sessionCookie.isHttpOnly()).isTrue();

            Cookie csrfCookie = response.getCookie("MIQROKEY_CSRF");
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.getMaxAge()).isZero();
        }

        @Test
        @DisplayName("should set Secure cookies when production mode")
        void shouldSetSecureCookiesInProduction() {
            authProperties.setCookieSecure(true);
            MockHttpServletResponse response = new MockHttpServletResponse();
            SessionToken tokens = new SessionToken("sess-token-hex", "csrf-token-hex");

            service.setCookies(response, tokens, Instant.now().plus(Duration.ofHours(12)));

            Cookie sessionCookie = response.getCookie("MIQROKEY_SESSION");
            assertThat(sessionCookie).isNotNull();
            assertThat(sessionCookie.getSecure()).isTrue();

            Cookie csrfCookie = response.getCookie("MIQROKEY_CSRF");
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.getSecure()).isTrue();
        }
    }

    @Nested
    @DisplayName("CSRF verification")
    class CsrfVerification {
        @Test
        @DisplayName("should match correct CSRF token")
        void shouldMatchCorrectCsrf() {
            String csrfToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            byte[] csrfDigest = SessionService.sha256(SessionService.hexToBytes(csrfToken));

            assertThat(service.verifyCsrf(csrfToken, csrfDigest)).isTrue();
        }

        @Test
        @DisplayName("should reject incorrect CSRF token")
        void shouldRejectIncorrectCsrf() {
            String csrfToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            byte[] csrfDigest = SessionService.sha256(
                    SessionService.hexToBytes("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

            assertThat(service.verifyCsrf(csrfToken, csrfDigest)).isFalse();
        }

        @Test
        @DisplayName("should reject null header token")
        void shouldRejectNullToken() {
            assertThat(service.verifyCsrf(null, new byte[32])).isFalse();
        }
    }

    @Nested
    @DisplayName("Session extraction from request")
    class ExtractToken {
        @Test
        @DisplayName("should extract session token from cookie")
        void shouldExtractToken() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("MIQROKEY_SESSION", "test-token"));

            assertThat(service.extractSessionToken(request)).isEqualTo("test-token");
        }

        @Test
        @DisplayName("should return null when no cookies")
        void shouldReturnNullNoCookies() {
            assertThat(service.extractSessionToken(new MockHttpServletRequest())).isNull();
        }
    }

    private User buildUser() {
        return new User(USER_ID, TENANT_ID, "admin", "Admin", new byte[32], UserRole.SYSTEM_ADMIN, UserStatus.ACTIVE,
                false, 0, null, null, 0, Instant.now(), Instant.now());
    }
}
