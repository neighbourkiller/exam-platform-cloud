package com.ekusys.exam.runtime.entry;

import org.springframework.http.HttpStatus;

public class ExamEntryException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long retryAfterMs;

    public ExamEntryException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ExamEntryException(HttpStatus status, String code, String message, Long retryAfterMs) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterMs() {
        return retryAfterMs;
    }
}
