package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.runtime.controller.RuntimeExamController;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RuntimeExamController.class)
public class ExamEntryExceptionHandler {
    @ExceptionHandler(ExamEntryException.class)
    public ResponseEntity<ApiResponse<Map<String, Long>>> handle(ExamEntryException exception) {
        ApiResponse<Map<String, Long>> body = ApiResponse.<Map<String, Long>>builder()
            .success(false)
            .code(exception.code())
            .message(exception.getMessage())
            .data(exception.retryAfterMs() == null
                ? null : Map.of("retryAfterMs", exception.retryAfterMs()))
            .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.retryAfterMs() != null) {
            long seconds = Math.max(1L, (exception.retryAfterMs() + 999L) / 1_000L);
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        return response.body(body);
    }
}
