package com.miqroera.miqrokey.controlplane.client;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SPI {@link ProviderClient} implementation the control plane hands to
 * adapters for control-plane operations (credential validation, model catalog
 * fetch, plan status).
 *
 * <p>
 * Enforces centrally, in one place:
 * <ul>
 * <li><b>SSRF gate</b> — the bound base URL is re-validated on every exchange
 * by the shared {@link UpstreamTargetValidator} (https-only, public addresses,
 * empty allowlist = production default); a rejected target surfaces as a
 * generic runtime error that never names the URL.</li>
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
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
        if (path.startsWith("/") && baseUrl.getPath().endsWith("/") && path.length() > 1) {
            path = path.substring(1);
        }
        try {
            return new URI(baseUrl.getScheme(), baseUrl.getRawAuthority(), baseUrl.getRawPath() + path,
                    request.query().isBlank() ? null : request.query(), null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid provider request path", e);
        }
    }

    private Mono<ProviderResponse> readBounded(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
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
