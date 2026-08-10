package com.ekusys.exam.gateway;

import com.ekusys.exam.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ExamEntryRateLimitFilter implements GlobalFilter, Ordered {
    private static final Pattern ENTRY_PATH = Pattern.compile(
        "^/api/v1/exams/([0-9]+)/(entry/prepare|entry/activate|paper-delivery)$"
    );
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
        local nowParts = redis.call('TIME')
        local nowMs = tonumber(nowParts[1]) * 1000 + math.floor(tonumber(nowParts[2]) / 1000)
        local rate = tonumber(ARGV[1])
        local burst = tonumber(ARGV[2])
        local values = redis.call('HMGET', KEYS[1], 'tokens', 'lastMs')
        local tokens = tonumber(values[1])
        local lastMs = tonumber(values[2])
        if not tokens or not lastMs then
            tokens = burst
            lastMs = nowMs
        end
        if nowMs > lastMs then
            tokens = math.min(burst, tokens + ((nowMs - lastMs) * rate / 1000))
        end
        local allowed = 0
        local retryAfterMs = 0
        if tokens >= 1 then
            tokens = tokens - 1
            allowed = 1
        else
            retryAfterMs = math.ceil((1 - tokens) * 1000 / rate)
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'lastMs', tostring(nowMs))
        redis.call('PEXPIRE', KEYS[1], math.ceil((burst / rate) * 2000) + 60000)
        return {allowed, retryAfterMs}
        """, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final ExamEntryRateLimitProperties properties;
    private final ObjectMapper mapper;
    private final Counter rejected;

    public ExamEntryRateLimitFilter(ReactiveStringRedisTemplate redis,
                                    ExamEntryRateLimitProperties properties,
                                    ObjectMapper mapper,
                                    MeterRegistry registry) {
        this.redis = redis;
        this.properties = properties;
        this.mapper = mapper;
        this.rejected = registry.counter("exam.gateway.entry.rejected");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        EntryPath entry = classify(exchange);
        if (!properties.isEnabled() || entry == null) {
            return chain.filter(exchange);
        }
        ExamEntryRateLimitProperties.Limits limits = properties.forEndpoint(entry.endpoint());
        String key = "exam:gateway-entry-rate:{" + entry.examId() + "}:" + entry.endpoint();
        return redis.execute(
                TOKEN_BUCKET,
                List.of(key),
                List.of(
                    String.valueOf(limits.safeReplenishRate()),
                    String.valueOf(limits.safeBurstCapacity())
                )
            )
            .next()
            .map(result -> {
                if (result.size() >= 2 && number(result.getFirst()) == 1L) {
                    return LimitDecision.allow();
                }
                long retryAfterMs = result.size() < 2 ? 1_000L : Math.max(1L, number(result.get(1)));
                return LimitDecision.rejected(retryAfterMs);
            })
            .switchIfEmpty(Mono.just(LimitDecision.unavailableDecision()))
            .onErrorReturn(LimitDecision.unavailableDecision())
            .flatMap(decision -> {
                if (decision.allowed()) {
                    return chain.filter(exchange);
                }
                if (decision.unavailable()) {
                    return writeError(
                        exchange, HttpStatus.SERVICE_UNAVAILABLE, "EXAM_ENTRY_UNAVAILABLE",
                        "考试流量保护服务暂时不可用，请稍后重试", decision.retryAfterMs()
                    );
                }
                rejected.increment();
                return writeError(
                    exchange, HttpStatus.TOO_MANY_REQUESTS, "EXAM_ENTRY_BUSY",
                    "当前进入考试人数较多，请稍后重试", decision.retryAfterMs()
                );
            });
    }

    static EntryPath classify(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return null;
        }
        Matcher matcher = ENTRY_PATH.matcher(exchange.getRequest().getURI().getPath());
        if (!matcher.matches()) {
            return null;
        }
        String endpoint = switch (matcher.group(2)) {
            case "entry/prepare" -> "prepare";
            case "entry/activate" -> "activate";
            case "paper-delivery" -> "paper-delivery";
            default -> null;
        };
        return endpoint == null ? null : new EntryPath(matcher.group(1), endpoint);
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status,
                                  String code, String message, long retryAfterMs) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
            HttpHeaders.RETRY_AFTER,
            String.valueOf(Math.max(1L, (retryAfterMs + 999L) / 1_000L))
        );
        ApiResponse<Map<String, Long>> body = ApiResponse.<Map<String, Long>>builder()
            .success(false)
            .code(code)
            .message(message)
            .data(Map.of("retryAfterMs", retryAfterMs))
            .build();
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            bytes = ("{\"success\":false,\"code\":\"" + code + "\",\"message\":\"请求失败\"}")
                .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return Long.parseLong(String.valueOf(value));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    record EntryPath(String examId, String endpoint) {
    }

    private record LimitDecision(boolean allowed, boolean unavailable, long retryAfterMs) {
        private static LimitDecision allow() {
            return new LimitDecision(true, false, 0L);
        }

        private static LimitDecision rejected(long retryAfterMs) {
            return new LimitDecision(false, false, retryAfterMs);
        }

        private static LimitDecision unavailableDecision() {
            return new LimitDecision(false, true, 500L);
        }
    }
}
