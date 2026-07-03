package com.ekusys.exam.iam.config;

import com.ekusys.exam.common.api.ApiResponse;
import feign.FeignException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IamFeignExceptionHandler {
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeign(FeignException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.fail("DOWNSTREAM_UNAVAILABLE", "Academic 服务暂时不可用"));
    }
}
