package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook endpoint management (G4.5, {@code webhook_endpoints} V12): the URL is
 * SSRF-validated at create time (public https only unless the control-plane
 * allowlist extends it), and the signing secret is stored AES-GCM-encrypted
 * (AAD tenant + endpoint) — never in plaintext. Deliveries are signed with
 * HMAC-SHA256 over the payload ({@code X-MiQroKey-Signature:
 * sha256=<hex>}).
 */
@Service
public class WebhookEndpointService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final NamedParameterJdbcTemplate jdbc;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final UpstreamTargetValidator targetValidator;

    public WebhookEndpointService(NamedParameterJdbcTemplate jdbc, KeyEncryptionProvider keyEncryptionProvider,
            UpstreamTargetValidator controlPlaneTargetValidator) {
        this.jdbc = jdbc;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.targetValidator = controlPlaneTargetValidator;
    }

    public WebhookEndpointView create(UUID tenantId, String name, String url, String secret, int timeoutMs) {
        validateUrl(url);
        UUID id = UUID.randomUUID();
        // AAD binds the ciphertext to (tenant, endpoint) — the same ids used
        // for decryption at delivery time.
        EncryptedSecret encrypted = keyEncryptionProvider.encrypt(secret.getBytes(StandardCharsets.UTF_8), tenantId,
                id);
        jdbc.update("""
                INSERT INTO webhook_endpoints
                    (id, tenant_id, name, url, secret_encrypted, secret_nonce, secret_key_version, enabled,
                     timeout_ms, version)
                VALUES (:id, :tenantId, :name, :url, :encrypted, :nonce, :keyVersion, TRUE, :timeoutMs, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("name", name)
                .addValue("url", url).addValue("encrypted", encrypted.ciphertext()).addValue("nonce", encrypted.nonce())
                .addValue("keyVersion", encrypted.keyVersion()).addValue("timeoutMs", timeoutMs));
        return view(get(tenantId, id));
    }

    public List<WebhookEndpointView> list(UUID tenantId) {
        return jdbc.query("SELECT * FROM webhook_endpoints WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER).stream().map(this::view).toList();
    }

    public WebhookEndpoint get(UUID tenantId, UUID endpointId) {
        List<WebhookEndpoint> found = jdbc.query(
                "SELECT * FROM webhook_endpoints WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", endpointId).addValue("tenantId", tenantId), ROW_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook endpoint not found");
        }
        return found.get(0);
    }

    /** Safe view: the signing secret (ciphertext included) is never returned. */
    public WebhookEndpointView view(WebhookEndpoint endpoint) {
        return new WebhookEndpointView(endpoint.id(), endpoint.tenantId(), endpoint.name(), endpoint.url(),
                endpoint.enabled(), endpoint.timeoutMs(), endpoint.version(), endpoint.createdAt(),
                endpoint.updatedAt());
    }

    @Transactional
    public WebhookEndpoint update(UUID tenantId, UUID endpointId, String name, Boolean enabled, Integer timeoutMs) {
        WebhookEndpoint existing = get(tenantId, endpointId);
        jdbc.update("""
                UPDATE webhook_endpoints
                SET name = :name, enabled = :enabled, timeout_ms = :timeoutMs, version = version + 1,
                    updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId
                """,
                new MapSqlParameterSource("name", name != null ? name : existing.name())
                        .addValue("enabled", enabled != null ? enabled : existing.enabled())
                        .addValue("timeoutMs", timeoutMs != null ? timeoutMs : existing.timeoutMs())
                        .addValue("id", endpointId).addValue("tenantId", tenantId));
        return get(tenantId, endpointId);
    }

    /** Update returning the safe view. */
    @Transactional
    public WebhookEndpointView updateView(UUID tenantId, UUID endpointId, String name, Boolean enabled,
            Integer timeoutMs) {
        return view(update(tenantId, endpointId, name, enabled, timeoutMs));
    }

    @Transactional
    public void delete(UUID tenantId, UUID endpointId) {
        get(tenantId, endpointId);
        jdbc.update("DELETE FROM webhook_endpoints WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", endpointId).addValue("tenantId", tenantId));
    }

    /** Sends a signed test payload and reports the upstream HTTP status. */
    public TestResult test(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        byte[] payload = ("{\"type\":\"TEST\",\"endpointId\":\"" + endpointId + "\",\"sentAt\":\"" + Instant.now()
                + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            int status = postSigned(endpoint, payload);
            return new TestResult(status, null);
        } catch (Exception e) {
            return new TestResult(null, truncate(e.getMessage()));
        }
    }

    /** Delivery history for one endpoint. */
    public List<DeliveryAttempt> deliveries(UUID tenantId, UUID endpointId, int limit) {
        return jdbc.query("""
                SELECT * FROM webhook_delivery_attempts
                WHERE tenant_id = :tenantId AND endpoint_id = :endpointId
                ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("endpointId", endpointId)
                .addValue("limit", Math.min(limit, 100)), DELIVERY_ROW_MAPPER);
    }

    // -------------------------------------------------------------------

    /** Decrypted signing secret for delivery; caller clears the array. */
    byte[] secretFor(WebhookEndpoint endpoint) {
        byte[] secret = keyEncryptionProvider.decrypt(
                new EncryptedSecret(endpoint.secretEncrypted(), endpoint.secretNonce(), endpoint.secretKeyVersion()),
                endpoint.tenantId(), endpoint.id());
        return secret;
    }

    /** HMAC-SHA256 over the payload; hex-encoded for the signature header. */
    static String signature(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    int postSigned(WebhookEndpoint endpoint, byte[] payload) throws Exception {
        byte[] secret = secretFor(endpoint);
        try {
            String signature = signature(secret, payload);
            var request = java.net.http.HttpRequest.newBuilder(URI.create(endpoint.url()))
                    .header("Content-Type", "application/json").header("X-MiQroKey-Signature", "sha256=" + signature)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload))
                    .timeout(java.time.Duration.ofMillis(endpoint.timeoutMs())).build();
            var response = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    private void validateUrl(String url) {
        UpstreamTargetValidator.Result result = targetValidator.validate(url);
        if (!result.allowed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_URL_REJECTED",
                    "The webhook URL is rejected by the SSRF gate (public https only)");
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** Public view of an endpoint; carries no secret material. */
    public record WebhookEndpointView(UUID id, UUID tenantId, String name, String url, boolean enabled, int timeoutMs,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record WebhookEndpoint(UUID id, UUID tenantId, String name, String url, byte[] secretEncrypted,
            byte[] secretNonce, String secretKeyVersion, boolean enabled, int timeoutMs, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record TestResult(Integer httpStatus, String errorMessage) {
    }

    public record DeliveryAttempt(UUID id, UUID tenantId, UUID eventId, UUID endpointId, int attempt,
            Integer httpStatus, Instant nextRetryAt, String errorMessage, Instant createdAt) {
    }

    private static final RowMapper<WebhookEndpoint> ROW_MAPPER = (rs, rowNum) -> new WebhookEndpoint(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("url"),
            rs.getBytes("secret_encrypted"), rs.getBytes("secret_nonce"), rs.getString("secret_key_version"),
            rs.getBoolean("enabled"), rs.getInt("timeout_ms"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);

    private static final RowMapper<DeliveryAttempt> DELIVERY_ROW_MAPPER = (rs, rowNum) -> new DeliveryAttempt(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("event_id"),
            (UUID) rs.getObject("endpoint_id"), rs.getInt("attempt"), rs.getObject("http_status", Integer.class),
            rs.getTimestamp("next_retry_at") != null ? rs.getTimestamp("next_retry_at").toInstant() : null,
            rs.getString("error_message"), rs.getTimestamp("created_at").toInstant());
}
