package com.miqroera.miqrokey.controlplane.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserSession;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A 401 that also has to change state — disabling or locking an account revokes
 * the live session so it cannot be replayed — is the one path where a failure of
 * the state change matters as much as the rejection itself.
 */
@DisplayName("SessionFilter (control-plane portal)")
class SessionFilterTest {

    private static final String PATH = "/api/v1/agents";
    private static final String RAW_TOKEN = "ph45-raw-token";

    private final SessionService sessionService = mock(SessionService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserContext userContext = new UserContext();
    private final AuthProperties authProperties = new AuthProperties();
    private final FilterChain chain = mock(FilterChain.class);
    private final SessionFilter filter = new SessionFilter(sessionService, userRepository, userContext, authProperties);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appender.start();
        ((Logger) LoggerFactory.getLogger(SessionFilter.class)).addAppender(appender);
        when(sessionService.extractSessionToken(any())).thenReturn(RAW_TOKEN);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SessionFilter.class)).detachAppender(appender);
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(PATH);
        return request;
    }

    private UserSession liveSession() {
        Instant now = Instant.now();
        return new UserSession(sessionId, tenantId, userId, new byte[32], new byte[32], now.minusSeconds(60),
                now.minusSeconds(5), now.plusSeconds(3600), null);
    }

    private User user(UserStatus status) {
        Instant now = Instant.now();
        return new User(userId, tenantId, "ph45-user", "PH45 User", new byte[32], UserRole.USER, status, false, 0, null,
                now, 1L, now, now);
    }

    @Test
    @DisplayName("PH45: a disabled account's session that cannot be revoked is reported server-side")
    void failedRevocationOfADisabledAccountIsReported() throws Exception {
        when(sessionService.findByToken(RAW_TOKEN)).thenReturn(Optional.of(liveSession()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(UserStatus.DISABLED)));
        doThrow(new IllegalStateException("revoke failed: connection reset")).when(sessionService)
                .revokeSession(sessionId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        // The rejection itself is unchanged: 401, and the chain is never reached.
        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        verify(sessionService).revokeSession(sessionId);

        // ... but the failed revocation must leave a trace: the session is still
        // usable even though the account is disabled.
        assertThat(messages(Level.ERROR)).anySatisfy(line -> assertThat(line)
                .contains(sessionId.toString(), userId.toString(), "DISABLED"));
    }

    @Test
    @DisplayName("PH45: a locked account's session that cannot be revoked is reported server-side")
    void failedRevocationOfALockedAccountIsReported() throws Exception {
        when(sessionService.findByToken(RAW_TOKEN)).thenReturn(Optional.of(liveSession()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(UserStatus.LOCKED)));
        doThrow(new IllegalStateException("revoke failed: connection reset")).when(sessionService)
                .revokeSession(sessionId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(messages(Level.ERROR)).anySatisfy(line -> assertThat(line)
                .contains(sessionId.toString(), userId.toString(), "LOCKED"));
    }

    @Test
    @DisplayName("PH45: a succeeded revocation stays silent (no false alarm on the happy path)")
    void successfulRevocationIsNotReported() throws Exception {
        when(sessionService.findByToken(RAW_TOKEN)).thenReturn(Optional.of(liveSession()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(UserStatus.DISABLED)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(sessionService).revokeSession(sessionId);
        assertThat(messages(Level.ERROR)).isEmpty();
    }
}
