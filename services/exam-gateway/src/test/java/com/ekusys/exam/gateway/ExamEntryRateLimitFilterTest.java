package com.ekusys.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ExamEntryRateLimitFilterTest {
    @Test
    void classifiesOnlyThreePostEntryEndpoints() {
        var activate = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/exams/11/entry/activate").build()
        );
        var delivery = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/exams/11/paper-delivery").build()
        );
        var getPrepare = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/exams/11/entry/prepare").build()
        );

        assertThat(ExamEntryRateLimitFilter.classify(activate).endpoint()).isEqualTo("activate");
        assertThat(ExamEntryRateLimitFilter.classify(delivery).endpoint()).isEqualTo("paper-delivery");
        assertThat(ExamEntryRateLimitFilter.classify(getPrepare)).isNull();
    }

    @Test
    void defaultsMatchAdmissionCapacityPlan() {
        ExamEntryRateLimitProperties properties = new ExamEntryRateLimitProperties();

        assertThat(properties.getPrepare().safeReplenishRate()).isEqualTo(500);
        assertThat(properties.getPrepare().safeBurstCapacity()).isEqualTo(1_000);
        assertThat(properties.getActivate().safeReplenishRate()).isEqualTo(1_200);
        assertThat(properties.getActivate().safeBurstCapacity()).isEqualTo(2_400);
        assertThat(properties.getPaperDelivery().safeReplenishRate()).isEqualTo(1_200);
        assertThat(properties.getPaperDelivery().safeBurstCapacity()).isEqualTo(2_400);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void allowedRequestIsForwardedWithoutPrematurelyCommittingUnavailableResponse() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList()))
            .thenReturn((Flux) Flux.just(List.of("1", "0")));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        ExamEntryRateLimitFilter filter = new ExamEntryRateLimitFilter(
            redis, new ExamEntryRateLimitProperties(), new ObjectMapper(), new SimpleMeterRegistry()
        );
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/exams/11/entry/activate").build()
        );

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectedRequestReturnsStructuredTooManyRequestsHeaders() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList()))
            .thenReturn((Flux) Flux.just(List.of("0", "250")));
        ExamEntryRateLimitFilter filter = new ExamEntryRateLimitFilter(
            redis, new ExamEntryRateLimitProperties(), new ObjectMapper(), new SimpleMeterRegistry()
        );
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/exams/11/entry/activate").build()
        );

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
