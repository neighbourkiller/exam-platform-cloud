package com.ekusys.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.common.web.ClientIpUtils;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class ClientIpForwardingFilterTest {
    private static final String FORWARDED = "Forwarded";

    @Test
    void overwritesExternalClientIpHeaders() throws Exception {
        ClientIpProperties properties = new ClientIpProperties();
        ClientIpForwardingFilter filter = new ClientIpForwardingFilter(new TrustedClientIpResolver(properties));
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
            .remoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 443))
            .header(FORWARDED, "for=198.51.100.5")
            .header("X-Forwarded-For", "198.51.100.5")
            .header("X-Real-IP", "198.51.100.6")
            .header(ClientIpUtils.CLIENT_IP_HEADER, "198.51.100.7")
            .build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst(ClientIpUtils.CLIENT_IP_HEADER)).isEqualTo("203.0.113.9");
        assertThat(headers.containsHeader(FORWARDED)).isFalse();
        assertThat(headers.containsHeader("X-Forwarded-For")).isFalse();
        assertThat(headers.containsHeader("X-Real-IP")).isFalse();
    }

    @Test
    void combinesRepeatedForwardedForHeadersBeforeResolving() throws Exception {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        ClientIpForwardingFilter filter = new ClientIpForwardingFilter(new TrustedClientIpResolver(properties));
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
            .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.3"), 443))
            .header("X-Forwarded-For", "198.51.100.7", "10.0.0.2")
            .build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(ClientIpUtils.CLIENT_IP_HEADER))
            .isEqualTo("198.51.100.7");
    }
}
