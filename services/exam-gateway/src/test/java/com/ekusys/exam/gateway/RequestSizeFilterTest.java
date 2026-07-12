package com.ekusys.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;

class RequestSizeFilterTest {

    @Test
    void rejectsRequestLargerThanFiveMegabytes() {
        RequestSizeGatewayFilterFactory factory = new RequestSizeGatewayFilterFactory();
        var config = new RequestSizeGatewayFilterFactory.RequestSizeConfig()
            .setMaxSize(DataSize.ofMegabytes(5));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        var request = MockServerHttpRequest.post("/api/v1/exams/1/snapshot")
            .header("Content-Length", String.valueOf(DataSize.ofMegabytes(5).toBytes() + 1))
            .build();
        var exchange = MockServerWebExchange.from(request);

        factory.apply(config).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(chain);
    }
}
