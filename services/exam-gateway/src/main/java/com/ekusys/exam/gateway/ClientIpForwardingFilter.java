package com.ekusys.exam.gateway;

import com.ekusys.exam.common.web.ClientIpUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ClientIpForwardingFilter implements GlobalFilter, Ordered {
    private static final String FORWARDED = "Forwarded";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private final TrustedClientIpResolver resolver;

    public ClientIpForwardingFilter(TrustedClientIpResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var forwardedValues = exchange.getRequest().getHeaders().getOrEmpty(X_FORWARDED_FOR);
        String forwardedFor = forwardedValues.isEmpty() ? null : String.join(",", forwardedValues);
        String clientIp = resolver.resolve(
            exchange.getRequest().getRemoteAddress(),
            forwardedFor
        );
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(FORWARDED);
            headers.remove(X_FORWARDED_FOR);
            headers.remove(X_REAL_IP);
            headers.remove(ClientIpUtils.CLIENT_IP_HEADER);
            headers.set(ClientIpUtils.CLIENT_IP_HEADER, clientIp);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
