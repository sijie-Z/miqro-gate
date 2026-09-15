package com.miqroera.miqrokey.controlplane.client;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SPI {@link ProviderClient} implementation the control plane hands to
 * adapters for control-plane operations (credential validation, model catalog
 * fetch, plan status).
 *
 * <p>
 * Enforces centrally, in one place:
 * <ul>
 * <li><b>SSRF gate</b> — the bound base URL is validated at construction and
 * re-validated on every exchange by the shared {@link UpstreamTargetValidator}
 * (https-only, public addresses, empty allowlist = production default); a
 * rejected target surfaces as a generic runtime error that never names the
 * URL.</li>
 * <li><b>DNS-rebinding pinning</b> — the base URL is fixed for the lifetime of
 * this client, so it is resolved exactly once at construction and every
 * exchange reaches that validated address: a {@link AddressResolverGroup} hands
 * the pinned IP to the socket layer, so a rebinding DNS answer cannot move the
 * traffic. The URI itself keeps the original hostname, so the TLS SNI, the
 * certificate identity check and the {@code Host} header all stay on the
 * validated hostname (the JDK HTTP client could not do this — it derives
 * {@code Host} from the URI and forbids overriding it, which forced the
 * IP-literal rewrite that upstream WAFs reject with HTTP 418, #507).</li>
 * <li><b>Transport</b> — the Reactor Netty HTTP client over HTTP/1.1, the same
 * client stack the gateway inference path uses.</li>
 * <li><b>Timeouts</b> — connect and response deadlines.</li>
 * <li><b>Response size cap</b> — bodies over the bound limit abort the exchange
 * instead of buffering unboundedly.</li>
 * <li><b>No redirects</b> — a 3xx would move the request off the validated
 * target; it is surfaced as-is.</li>
 * <li><b>Credential injection</b> — the bound upstream credential header is
 * added to every request; adapter-provided requests never carry secrets.</li>
 * </ul>
 *
 * <p>
 * DNS resolution inside the validator is blocking, so callers must not run
 * {@link #exchange} on a reactive event loop — the control plane invokes it
 * from MVC worker threads ({@code ModelCatalogService} blocks on the returned
 * Mono).
 * </p>
 */
public final class HttpProviderClient implements ProviderClient {

    private final HttpClient http;
    private final URI baseUrl;
    private final InetAddress pinnedAddress;
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
        this.pinnedAddress = target.addresses()[0];
        this.http = HttpClient.create().protocol(HttpProtocol.HTTP11).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(requestTimeout).resolver(new PinnedAddressResolverGroup(pinnedAddress))
                .doOnConnected(connection -> {
                    SslHandler ssl = connection.channel().pipeline().get(SslHandler.class);
                    if (ssl != null) {
                        // Keep certificate identity checks on the hostname (the
                        // pre-#507 JDK client ran with the HTTPS endpoint
                        // identification algorithm). Idempotent when the
                        // reactor-netty default already enables it.
                        SSLParameters parameters = ssl.engine().getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        ssl.engine().setSSLParameters(parameters);
                    }
                });
    }

    @Override
    public Mono<ProviderResponse> exchange(ProviderRequest request) {
        try {
            URI uri = buildUri(request);
            return http.headers(headers -> {
                headers.set(credentialHeader, credentialValue);
                headers.set("Accept", "application/json");
            }).get().uri(uri.toString()).response((response, body) -> readBounded(response, body)).single();
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
        StringBuilder sb = new StringBuilder(baseUrl.toString());
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

    /** The address every exchange connects to: the validated resolution. */
    InetAddress pinnedAddress() {
        return pinnedAddress;
    }

    private Mono<ProviderResponse> readBounded(HttpClientResponse response, ByteBufFlux body) {
        HttpHeaders nettyHeaders = response.responseHeaders();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : nettyHeaders.names()) {
            headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(nettyHeaders.getAll(name)));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return body.map(buffer -> {
            // ByteBuf lifecycle stays with reactor-netty (it releases the
            // buffer after this callback); only copy the readable bytes out.
            byte[] chunk = new byte[buffer.readableBytes()];
            buffer.readBytes(chunk);
            return chunk;
        }).reduce(out, (acc, chunk) -> {
            if (acc.size() + chunk.length > maxResponseBytes) {
                throw new IllegalStateException("Provider response exceeds the control-plane body limit");
            }
            acc.write(chunk, 0, chunk.length);
            return acc;
        }).map(acc -> new ProviderResponse(response.status().code(), headers, acc.toByteArray()));
    }

    /**
     * Hands the pinned address to every connection attempt, so DNS is never
     * consulted at exchange time (the DNS-rebinding TOCTOU defense). The hostname
     * in the URI still drives the {@code Host} header, SNI and the certificate
     * identity check.
     */
    private static final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

        private final InetAddress pinned;

        private PinnedAddressResolverGroup(InetAddress pinned) {
            this.pinned = pinned;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new PinnedAddressResolver(executor, pinned);
        }
    }

    private static final class PinnedAddressResolver extends AbstractAddressResolver<InetSocketAddress> {

        private final InetAddress pinned;

        private PinnedAddressResolver(EventExecutor executor, InetAddress pinned) {
            super(executor);
            this.pinned = pinned;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return address instanceof InetSocketAddress;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            // Unresolved (hostname) addresses must go through doResolve below,
            // which returns the pinned address instead of consulting DNS.
            return !address.isUnresolved();
        }

        @Override
        protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
            promise.setSuccess(new InetSocketAddress(pinned, unresolved.getPort()));
        }

        @Override
        protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
            promise.setSuccess(List.of(new InetSocketAddress(pinned, unresolved.getPort())));
        }
    }
}
