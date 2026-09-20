package com.ekusys.exam.common.controller;

import com.ekusys.exam.common.api.ApiResponse;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, LocalDateTime>> ping() {
        return ApiResponse.ok(Map.of("serverTime", LocalDateTime.now()));
    }
}
