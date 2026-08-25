package com.miqroera.miqrokey.gateway.proxy;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyTargetProperties.class)
public class ProxyConfig {

    @Bean
    public WebClient proxyWebClient(ProxyTargetProperties properties) {
        // G0.2 PoC: use non-pooled connections to ensure immediate connection
        // cleanup on cancellation. Connection pooling will be added in G2.x.
        ConnectionProvider provider = ConnectionProvider.newConnection();

        // responseTimeout is reactor-netty's "first response" deadline: it
        // covers waiting for the response headers after the request is sent,
        // not the streaming body. G2.5 maps it to the first-byte timeout.
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.firstByteTimeout());

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

    @Bean
    public Clock gatewayClock() {
        return Clock.systemUTC();
    }
}
