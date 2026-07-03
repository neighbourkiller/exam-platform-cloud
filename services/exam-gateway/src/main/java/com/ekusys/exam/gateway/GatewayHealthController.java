package com.ekusys.exam.gateway;

import com.ekusys.exam.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/health")
public class GatewayHealthController {
    @GetMapping("/ping")
    public Mono<ApiResponse<Map<String, String>>> ping() {
        return Mono.just(ApiResponse.ok(Map.of("status", "UP", "service", "exam-gateway")));
    }
}
