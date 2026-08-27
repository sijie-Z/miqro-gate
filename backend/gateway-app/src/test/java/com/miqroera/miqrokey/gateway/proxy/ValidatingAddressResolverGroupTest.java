package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Connect-time SSRF gate for the proxy WebClient (DNS-rebinding TOCTOU
 * defense): resolutions go through the validator and the connection is pinned
 * to the validated address with the original port. Unresolved address fixtures
 * keep the tests off the network (IP literals parse locally; {@code localhost}
 * resolves through the hosts file).
 */
@DisplayName("ValidatingAddressResolverGroup")
class ValidatingAddressResolverGroupTest {

    @Test
    @DisplayName("an allowlisted host resolves to the validated address with the original port")
    void resolvesAllowlistedHost() throws Exception {
        ValidatingAddressResolverGroup group = new ValidatingAddressResolverGroup(
                new UpstreamTargetValidator(List.of("127.0.0.0/8")), Schedulers.immediate());
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);

        Future<InetSocketAddress> future = resolver.resolve(InetSocketAddress.createUnresolved("127.0.0.1", 8080));

        InetSocketAddress resolved = future.get(5, TimeUnit.SECONDS);
        assertThat(resolved.isUnresolved()).isFalse();
        assertThat(resolved.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(resolved.getPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("a non-allow-listed host fails the resolution without leaking the hostname")
    void rejectsNonAllowlistedHost() {
        ValidatingAddressResolverGroup group = new ValidatingAddressResolverGroup(
                new UpstreamTargetValidator(List.of()), Schedulers.immediate());
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);

        Future<InetSocketAddress> future = resolver.resolve(InetSocketAddress.createUnresolved("127.0.0.1", 80));

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    @DisplayName("resolveAll returns every validated address with the original port")
    void resolvesAllAddresses() throws Exception {
        ValidatingAddressResolverGroup group = new ValidatingAddressResolverGroup(
                new UpstreamTargetValidator(List.of("127.0.0.0/8", "::1/128")), Schedulers.immediate());
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);

        Future<List<InetSocketAddress>> future = resolver
                .resolveAll(InetSocketAddress.createUnresolved("localhost", 9090));

        List<InetSocketAddress> resolved = future.get(5, TimeUnit.SECONDS);
        assertThat(resolved).isNotEmpty();
        assertThat(resolved).allSatisfy(address -> {
            assertThat(address.isUnresolved()).isFalse();
            assertThat(address.getAddress().isLoopbackAddress()).isTrue();
            assertThat(address.getPort()).isEqualTo(9090);
        });
    }

    @Test
    @DisplayName("already-resolved addresses pass through untouched")
    void passesThroughResolvedAddresses() throws Exception {
        ValidatingAddressResolverGroup group = new ValidatingAddressResolverGroup(
                new UpstreamTargetValidator(List.of()), Schedulers.immediate());
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);

        InetSocketAddress literal = new InetSocketAddress("127.0.0.1", 8080);
        Future<InetSocketAddress> future = resolver.resolve(literal);

        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(literal);
    }
}
