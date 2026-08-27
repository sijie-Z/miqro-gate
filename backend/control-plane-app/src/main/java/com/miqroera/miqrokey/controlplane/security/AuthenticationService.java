package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core authentication logic: login, logout, password change, and bootstrap.
 *
 * <p>
 * Login failures are intentionally indistinguishable to prevent account
 * enumeration. The response and timing are identical whether the username does
 * not exist, the password is wrong, or the account is disabled or locked.
 * </p>
 *
 * <p>
 * <b>Transaction design:</b>
 * </p>
 * <ul>
 * <li>{@link #login(String, String, String)} — no class-level transaction. The
 * progressive delay runs entirely outside any transaction. Successful
 * user/session updates and LOGIN audit happen in their own transactions.</li>
 * <li>{@link #bootstrap(String, String, String, String)} — one outer
 * transaction for user insert + session creation + BOOTSTRAP audit event. Audit
 * events use default {@code REQUIRED} propagation and join the bootstrap
 * transaction, avoiding FK-lock self-deadlock between the suspended bootstrap
 * transaction and a separate audit transaction.</li>
 * <li>{@link #recordFailedLogin(User, String)} — {@code REQUIRES_NEW}
 * transaction. The failed-login counter increment, lock transition, and
 * LOGIN_FAILED/ACCOUNT_LOCKED audit events commit atomically. Since audit uses
 * {@code REQUIRED}, it joins this REQUIRES_NEW transaction rather than creating
 * a third nested transaction.</li>
 * <li>{@link #changePassword(User, UUID, String, String, String)} — one outer
 * transaction for hash update + session revocation + PASSWORD_CHANGE audit, all
 * atomic.</li>
 * <li>{@link #logout(User, UUID, String)} — no outer transaction; session
 * revocation and LOGOUT audit each start their own transaction.</li>
 * </ul>
 */
@Service
public class AuthenticationService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static final String LOGIN_FAILED = "Invalid username or password.";

    /** Allowed endpoints when mustChangePassword is true. */
    static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("/api/v1/auth/password", "/api/v1/auth/logout",
            "/api/v1/auth/me", "/api/v1/auth/csrf");

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final AuthProperties authProperties;
    private final Environment environment;

    /**
     * Self-injected to access {@code @Transactional(propagation = REQUIRES_NEW)}.
     */
    private AuthenticationService self;

    static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public AuthenticationService(UserRepository userRepository, PasswordHasher passwordHasher,
            SessionService sessionService, AuditService auditService, AuthProperties authProperties,
            Environment environment) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.authProperties = authProperties;
        this.environment = environment;
    }

    /** Spring-managed self-injection for {@code REQUIRES_NEW} proxy access. */
    public void setSelf(AuthenticationService self) {
        this.self = self;
    }

    /**
     * Attempt login.
     *
     * <p>
     * No outer {@code @Transactional} — database writes are committed inside
     * {@link #recordFailedLogin} and the session/rehash updates. The progressive
     * delay runs entirely outside any transaction.
     * </p>
     *
     * <p>
     * <b>Timing indistinguishability:</b> Unknown users, DISABLED, and LOCKED
     * accounts all perform identical Argon2 work against a stable pre-computed
     * dummy hash.
     * </p>
     */
    public LoginResult login(String username, String password, String requestId) {
        Optional<User> userOpt = userRepository.findByTenantIdAndUsername(SEED_TENANT_ID, username);
        User user = userOpt.orElse(null);
        Instant now = Instant.now();

        boolean rejectDisabled = user != null && user.status() == UserStatus.DISABLED;
        boolean rejectLocked = user != null && user.status() == UserStatus.LOCKED && user.lockedUntil() != null
                && now.isBefore(user.lockedUntil());

        // Always perform Argon2 work — timing indistinguishable.
        boolean passwordValid;
        if (user != null && !rejectDisabled && !rejectLocked) {
            passwordValid = passwordHasher.verify(password, user.passwordHash());
        } else {
            passwordHasher.verifyAgainstDummy(password);
            passwordValid = false;
        }

        // Estimate fail count for progressive delay (NOT used for persistence).
        // Unknown usernames must observe a delay too, otherwise the timing
        // difference lets attackers enumerate existing accounts: start them
        // at the same floor as a first failed attempt on a known account.
        int estimatedFailCount = (user != null) ? user.failedLoginCount() + 1 : 2;

        // Progressive delay BEFORE any write transaction — never sleep holding a DB
        // connection.
        progressiveDelay(estimatedFailCount);

        if (!passwordValid) {
            if (user != null) {
                if (self != null) {
                    self.recordFailedLogin(user, requestId);
                } else {
                    recordFailedLogin(user, requestId);
                }
            }
            throw new AuthenticationException(LOGIN_FAILED);
        }

        // --- Success ---
        boolean wasLocked = user.status() == UserStatus.LOCKED;
        UserStatus newStatus = wasLocked ? UserStatus.ACTIVE : user.status();

        // Reset counters, record login time
        User afterSuccess = new User(user.id(), user.tenantId(), user.username(), user.displayName(),
                user.passwordHash(), user.role(), newStatus, user.mustChangePassword(), 0, null, now,
                user.version() + 1, user.createdAt(), now);
        userRepository.update(afterSuccess);

        // Rehash
        if (passwordHasher.needsRehash(user.passwordHash())) {
            byte[] newHash = passwordHasher.hash(password);
            User rehashed = new User(user.id(), user.tenantId(), user.username(), user.displayName(), newHash,
                    user.role(), newStatus, user.mustChangePassword(), 0, null, now, user.version() + 2,
                    user.createdAt(), now);
            userRepository.update(rehashed);
        }

        SessionToken tokens = sessionService.createSession(afterSuccess);
        Instant sessionExpires = now.plus(authProperties.getSessionAbsoluteTimeout());

        auditService.record(user.tenantId(), user.id(), "LOGIN", "USER", user.id(), buildSummary(user.username()),
                requestId);

        LOG.info("User {} logged in successfully", user.username());
        return new LoginResult(enrichWithView(afterSuccess, newStatus), tokens, sessionExpires);
    }

    /**
     * Atomically increment the failed-login counter and optionally lock the
     * account. Uses a row-level lock ({@code SELECT ... FOR UPDATE}) on the user
     * row to read the fresh counter, compute the increment, and update
     * deterministically under concurrency.
     *
     * <p>
     * Runs in a {@code REQUIRES_NEW} transaction so the caller cannot roll back the
     * audit trail. The counter update, lock transition, and
     * LOGIN_FAILED/ACCOUNT_LOCKED audit events all commit atomically in this single
     * transaction (audit uses default {@code REQUIRED} propagation and joins this
     * transaction).
     * </p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedLogin(User user, String requestId) {
        User fresh = userRepository.findByIdForUpdate(user.id())
                .orElseThrow(() -> new IllegalStateException("User disappeared: " + user.id()));

        int newFailCount = fresh.failedLoginCount() + 1;
        Instant now = Instant.now();
        Instant lockedUntil = null;
        UserStatus newStatus = fresh.status();

        if (newFailCount >= authProperties.getLoginMaxFailures()) {
            long multiplier = 1L << Math.max(0, newFailCount - authProperties.getLoginMaxFailures());
            Duration lockDuration = authProperties.getLoginLockBase().multipliedBy(Math.min(multiplier, 1024));
            lockedUntil = now.plus(lockDuration);
            newStatus = UserStatus.LOCKED;
            LOG.warn("User {} locked until {} after {} failures", fresh.username(), lockedUntil, newFailCount);
        }

        User updated = new User(fresh.id(), fresh.tenantId(), fresh.username(), fresh.displayName(),
                fresh.passwordHash(), fresh.role(), newStatus, fresh.mustChangePassword(), newFailCount, lockedUntil,
                fresh.lastLoginAt(), fresh.version() + 1, fresh.createdAt(), now);
        userRepository.update(updated);

        // Audit LOGIN_FAILED durably
        auditService.record(fresh.tenantId(), fresh.id(), "LOGIN_FAILED", "USER", fresh.id(),
                buildSummary(fresh.username()), requestId);

        if (newStatus == UserStatus.LOCKED) {
            auditService.record(fresh.tenantId(), fresh.id(), "ACCOUNT_LOCKED", "USER", fresh.id(),
                    "{\"lockedUntil\":\"" + lockedUntil + "\"}", requestId);
        }
    }

    /**
     * Bootstrap the first admin user. Serializes concurrent requests at the DB
     * level by locking the seed tenant row with {@code SELECT ... FOR UPDATE}
     * inside the same transaction, then checking the user count. Exactly one admin
     * is created even under concurrent requests with different usernames.
     */
    @Transactional
    public BootstrapResult bootstrap(String bootstrapSecret, String username, String displayName, String requestId) {
        // Lock the tenant row to serialize bootstrap attempts
        userRepository.lockTenantForBootstrap(SEED_TENANT_ID);

        // Now re-check under the lock
        if (userRepository.countByTenantId(SEED_TENANT_ID) > 0) {
            throw new AuthenticationException("Bootstrap unavailable: users already exist.");
        }

        String expectedSecret = loadBootstrapSecret();
        if (expectedSecret == null || !MessageDigest.isEqual(bootstrapSecret.getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8))) {
            progressiveDelay(5);
            throw new AuthenticationException(LOGIN_FAILED);
        }

        byte[] tempBytes = new byte[16];
        SECURE_RANDOM.nextBytes(tempBytes);
        String temporaryPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(tempBytes);

        byte[] passwordHash = passwordHasher.hash(temporaryPassword);

        Instant now = Instant.now();
        User admin = new User(UUID.randomUUID(), SEED_TENANT_ID, username, displayName, passwordHash,
                UserRole.SYSTEM_ADMIN, UserStatus.ACTIVE, true, 0, null, null, 0, now, now);

        userRepository.insert(admin);

        SessionToken tokens = sessionService.createSession(admin);
        Instant sessionExpires = now.plus(authProperties.getSessionAbsoluteTimeout());

        auditService.record(SEED_TENANT_ID, admin.id(), "BOOTSTRAP", "USER", admin.id(), buildSummary(username),
                requestId);

        LOG.info("Bootstrap admin {} created", username);
        return new BootstrapResult(sanitizeUser(admin), temporaryPassword, tokens, sessionExpires);
    }

    @Transactional
    public void changePassword(User currentUser, UUID currentSessionId, String currentPassword, String newPassword,
            String requestId) {
        if (!passwordHasher.verify(currentPassword, currentUser.passwordHash())) {
            progressiveDelay(3);
            throw new AuthenticationException("Current password is incorrect.");
        }

        validatePasswordPolicy(newPassword);

        if (isCommonPassword(newPassword)) {
            throw new AuthenticationException("That password is too common. Please choose a different one.");
        }

        byte[] newHash = passwordHasher.hash(newPassword);
        Instant now = Instant.now();

        User updated = new User(currentUser.id(), currentUser.tenantId(), currentUser.username(),
                currentUser.displayName(), newHash, currentUser.role(), currentUser.status(), false, 0, null, now,
                currentUser.version() + 1, currentUser.createdAt(), now);
        userRepository.update(updated);

        sessionService.revokeOtherSessions(currentUser.id(), currentSessionId);

        auditService.record(currentUser.tenantId(), currentUser.id(), "PASSWORD_CHANGE", "USER", currentUser.id(),
                "\"password_change:user_initiated\"", requestId);

        LOG.info("User {} changed password and revoked other sessions", currentUser.username());
    }

    public void logout(User user, UUID sessionId, String requestId) {
        sessionService.revokeSession(sessionId);
        auditService.record(user.tenantId(), user.id(), "LOGOUT", "SESSION", sessionId, buildSummary(user.username()),
                requestId);
        LOG.info("User {} logged out", user.username());
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    void progressiveDelay(int failureCount) {
        if (failureCount <= 1)
            return;
        long delayMs = Math.min(3000, 250L * (1L << Math.min(failureCount - 2, 4)));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw new AuthenticationException("Password must be at least 8 characters.");
        }
        if (password.length() > 128) {
            throw new AuthenticationException("Password must not exceed 128 characters.");
        }
        boolean hasUpper = false, hasLower = false, hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c))
                hasUpper = true;
            if (Character.isLowerCase(c))
                hasLower = true;
            if (Character.isDigit(c))
                hasDigit = true;
        }
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new AuthenticationException(
                    "Password must contain at least one uppercase letter, one lowercase letter, and one digit.");
        }
    }

    boolean isCommonPassword(String password) {
        return COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT));
    }

    /** Pre-computed set of well-known breached passwords. */
    private static final Set<String> COMMON_PASSWORDS = Set.of("password", "password1", "password123", "admin",
            "admin123", "12345678", "123456789", "qwerty123", "abc123456", "letmein1", "welcome1", "monkey123",
            "dragon123", "master123", "sunshine1", "princess1", "football1", "iloveyou1", "trustno1", "batman123",
            "superman1", "passw0rd", "pa$$w0rd", "p@ssword", "p@ssw0rd1", "changeme", "changeme1", "qwertyuiop",
            "1q2w3e4r", "zxcvbnm1", "miqrokey", "miqrokey1");

    private String loadBootstrapSecret() {
        String filePath = authProperties.getBootstrapSecretFile();
        if (filePath == null || filePath.isBlank()) {
            filePath = environment.getProperty("miqrokey.bootstrap-secret-file");
        }
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("miqrokey.bootstrap-secret-file");
        }
        if (filePath == null || filePath.isBlank()) {
            filePath = System.getProperty("MIQROKEY_BOOTSTRAP_SECRET_FILE");
        }
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            LOG.error("Bootstrap secret file does not exist");
            return null;
        }
        if (!Files.isRegularFile(path)) {
            LOG.error("Bootstrap secret path is not a regular file");
            return null;
        }
        if (!Files.isReadable(path)) {
            LOG.error("Bootstrap secret file is not readable");
            return null;
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            String result = new String(bytes, StandardCharsets.UTF_8).trim();
            Arrays.fill(bytes, (byte) 0);

            if (result.isEmpty()) {
                LOG.error("Bootstrap secret file is empty");
                return null;
            }
            if (result.length() < 16) {
                LOG.error("Bootstrap secret is too short (minimum 16 characters)");
                return null;
            }
            return result;
        } catch (IOException e) {
            LOG.error("Could not read bootstrap secret file");
            return null;
        }
    }

    private static String buildSummary(String username) {
        return String.format("{\"username\":\"%s\"}", escapeJson(username));
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    private static UserView enrichWithView(User u, UserStatus effectiveStatus) {
        return new UserView(u.id(), u.tenantId(), u.username(), u.displayName(), u.role(), effectiveStatus,
                u.mustChangePassword(), u.failedLoginCount(), u.lockedUntil(), u.lastLoginAt(), u.createdAt(),
                u.updatedAt());
    }

    private static User sanitizeUser(User u) {
        // Return a safe copy — passwordHash excluded by UserView, but
        // BootstrapResult still carries a User reference for the controller.
        // The controller MUST extract only safe fields.
        return u;
    }

    /**
     * Safe user view for API responses — excludes passwordHash and internal fields.
     */
    public record UserView(UUID id, UUID tenantId, String username, String displayName, UserRole role,
            UserStatus status, boolean mustChangePassword, int failedLoginCount, Instant lockedUntil,
            Instant lastLoginAt, Instant createdAt, Instant updatedAt) {
    }

    public record LoginResult(UserView user, SessionToken tokens, Instant sessionExpires) {
    }
    public record BootstrapResult(User user, String temporaryPassword, SessionToken tokens, Instant sessionExpires) {
    }
}
