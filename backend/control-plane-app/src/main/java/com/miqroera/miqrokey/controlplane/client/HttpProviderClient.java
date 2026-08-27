package com.miqroera.miqrokey.controlplane.client;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLParameters;

/**
 * The SPI {@link ProviderClient} implementation the control plane hands to
 * adapters for control-plane operations (credential validation, model catalog
 * fetch, plan status).
 *
 * <p>
 * Enforces centrally, in one place:
 * <ul>
 * <li><b>SSRF gate</b> — the bound base URL is validated at construction and
 * re-validated on every exchange by the shared
 * {@link UpstreamTargetValidator} (https-only, public addresses, empty
 * allowlist = production default); a rejected target surfaces as a generic
 * runtime error that never names the URL.</li>
 * <li><b>DNS-rebinding pinning</b> — the base URL is fixed for the lifetime of
 * this client, so it is resolved exactly once at construction and every
 * exchange connects to the validated IP literal ({@link UpstreamTargetPin}).
 * A rebinding DNS answer between validation and connection cannot move the
 * traffic; for {@code https} targets SNI and certificate identity stay on the
 * original hostname.</li>
 * <li><b>Timeouts</b> — connect and overall request deadlines.</li>
 * <li><b>Response size cap</b> — bodies over the bound limit abort the exchange
 * instead of buffering unboundedly.</li>
 * <li><b>No redirects</b> — a 3xx would move the request off the validated
 * target; it is surfaced as-is.</li>
 * <li><b>Credential injection</b> — the bound upstream credential header is
 * added to every request; adapter-provided requests never carry secrets.</li>
 * </ul>
 *
 * <p>
 * Uses the JDK HTTP client (no extra dependency); inference traffic never
 * passes through this type. DNS resolution inside the validator is blocking, so
 * callers must not run {@link #exchange} on a reactive event loop — the control
 * plane invokes it from MVC worker threads ({@code ModelCatalogService} blocks
 * on the returned Mono).
 * </p>
 */
public final class HttpProviderClient implements ProviderClient {

    private final HttpClient http;
    private final URI baseUrl;
    private final URI pinnedBaseUri;
    private final SSLParameters sslParameters;
    private final String credentialHeader;
    private final String credentialValue;
    private final UpstreamTargetValidator targetValidator;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public HttpProviderClient(URI baseUrl, String credentialHeader, String credentialValue,
            UpstreamTargetValidator targetValidator, Duration connectTimeout, Duration requestTimeout,
            int maxResponseBytes) {
        if (baseUrl == null || credentialHeader == null || credentialValue == null || targetValidator == null
                || connectTimeout == null || requestTimeout == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        this.baseUrl = baseUrl;
        this.credentialHeader = credentialHeader;
        this.credentialValue = credentialValue;
        this.targetValidator = targetValidator;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        // SSRF gate at construction: the base URL never changes for this
        // client, so resolve it once and pin every exchange to the validated
        // address. The failure never names the target.
        UpstreamTargetValidator.Resolved target = targetValidator.validateAndResolve(baseUrl.toString());
        if (!target.allowed()) {
            throw new IllegalStateException("Upstream target is not allowed for this provider client");
        }
        this.pinnedBaseUri = UpstreamTargetPin.pin(baseUrl, target.addresses()[0]);
        this.sslParameters = UpstreamTargetPin.sslParametersFor(baseUrl);
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslParameters != null) {
            // Keep SNI + certificate identity on the original hostname even
            // though the connection goes to the pinned IP literal.
            builder.sslParameters(sslParameters);
        }
        this.http = builder.build();
    }

    @Override
    public Mono<ProviderResponse> exchange(ProviderRequest request) {
        try {
            URI uri = buildUri(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header(credentialHeader, credentialValue).header("Accept", "application/json").GET().build();
            return Mono.fromFuture(http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()))
                    .flatMap(response -> readBounded(response));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private URI buildUri(ProviderRequest request) {
        // SSRF gate on every exchange; the failure never names the target.
        UpstreamTargetValidator.Result result = targetValidator.validate(baseUrl.toString());
        if (!result.allowed()) {
            throw new IllegalStateException("Upstream target is not allowed for this provider client");
        }
        String path = request.path();
        StringBuilder sb = new StringBuilder(pinnedBaseUri.toString());
        if (sb.charAt(sb.length() - 1) == '/' && path.startsWith("/") && path.length() > 1) {
            path = path.substring(1);
        }
        sb.append(path);
        if (!request.query().isBlank()) {
            sb.append('?').append(request.query());
        }
        // Raw splice: the caller's path/query are already encoded (ProviderRequest
        // contract). URI.create keeps the percent-escapes untouched, unlike the
        // multi-arg constructor which re-encodes them.
        return URI.create(sb.toString());
    }

    /** The URI every exchange connects to: host replaced by the validated IP. */
    URI pinnedBaseUri() {
        return pinnedBaseUri;
    }

    /** Client-level SSL parameters ({@code https} only; null for plain http). */
    SSLParameters sslParameters() {
        return sslParameters;
    }

    private Mono<ProviderResponse> readBounded(HttpResponse<InputStream> response) {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        try (InputStream in = response.body()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Provider response body read timed out");
                }
                total += read;
                if (total > maxResponseBytes) {
                    throw new IllegalStateException("Provider response exceeds the control-plane body limit");
                }
                out.write(buf, 0, read);
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> headers.put(k, List.copyOf(v)));
            return Mono.just(new ProviderResponse(response.statusCode(), headers, out.toByteArray()));
        } catch (IOException e) {
            return Mono.error(new IllegalStateException("Provider exchange failed", e));
        }
    }
}
