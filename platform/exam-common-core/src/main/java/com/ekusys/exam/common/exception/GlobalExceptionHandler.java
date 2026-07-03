package com.ekusys.exam.common.exception;

import com.ekusys.exam.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException exception) {
        log.warn("Business exception: code={}, message={}", exception.getCode(), exception.getMessage());
        return ApiResponse.fail(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> handleBadRequest(Exception exception) {
        log.warn("Bad request: {}", exception.getMessage());
        return ApiResponse.fail("请求参数不正确");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknown(Exception exception) {
        log.error("Unhandled exception", exception);
        return ApiResponse.fail("服务器内部错误");
    }
}
