package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import reactor.core.scheduler.Scheduler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Connect-time SSRF gate for the proxy's reactor-netty {@code HttpClient} — the
 * gateway half of the DNS-rebinding TOCTOU defense (G2.6).
 *
 * <p>
 * The pre-connect {@link UpstreamTargetValidator} check in
 * {@code ProxyController} resolves the upstream hostname and discards the
 * result; without this group the HTTP client would resolve it again when
 * connecting, so a rebinding DNS answer could move the traffic to a non-public
 * address after the check passed. This group instead validates the hostname at
 * connect time and returns the validated address — the connection is pinned to
 * it, and only that address is ever dialed.
 * </p>
 *
 * <p>
 * The blocking DNS lookup and validation run on the bounded scheduler (never on
 * the Reactor event loop); the netty promise is completed from there, and netty
 * schedules the actual connect back onto the event loop. A hostname whose
 * addresses fail the gate fails the connection, which surfaces as the same
 * generic {@code upstream_unavailable} 502 — the reason never names the target.
 * SNI and the {@code Host} header keep the original hostname (the resolver only
 * changes the dialed address).
 * </p>
 */
public final class ValidatingAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final UpstreamTargetValidator targetValidator;
    private final Scheduler blockingScheduler;

    public ValidatingAddressResolverGroup(UpstreamTargetValidator targetValidator, Scheduler blockingScheduler) {
        this.targetValidator = targetValidator;
        this.blockingScheduler = blockingScheduler;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new ValidatingResolver(executor, targetValidator, blockingScheduler);
    }

    private static final class ValidatingResolver extends AbstractAddressResolver<InetSocketAddress> {

        private final UpstreamTargetValidator targetValidator;
        private final Scheduler blockingScheduler;

        ValidatingResolver(EventExecutor executor, UpstreamTargetValidator targetValidator,
                Scheduler blockingScheduler) {
            super(executor);
            this.targetValidator = targetValidator;
            this.blockingScheduler = blockingScheduler;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            return !address.isUnresolved();
        }

        @Override
        protected void doResolve(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise) {
            blockingScheduler.schedule(() -> {
                try {
                    InetAddress[] validated = targetValidator.resolveValidated(unresolvedAddress.getHostString());
                    promise.setSuccess(new InetSocketAddress(validated[0], unresolvedAddress.getPort()));
                } catch (Throwable failure) {
                    promise.setFailure(failure);
                }
            });
        }

        @Override
        protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise) {
            blockingScheduler.schedule(() -> {
                try {
                    InetAddress[] validated = targetValidator.resolveValidated(unresolvedAddress.getHostString());
                    promise.setSuccess(java.util.Arrays.stream(validated)
                            .map(address -> new InetSocketAddress(address, unresolvedAddress.getPort())).toList());
                } catch (Throwable failure) {
                    promise.setFailure(failure);
                }
            });
        }
    }
}
