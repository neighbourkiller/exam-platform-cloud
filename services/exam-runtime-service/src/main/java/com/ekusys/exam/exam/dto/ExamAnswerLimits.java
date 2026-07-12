package com.ekusys.exam.exam.dto;

public final class ExamAnswerLimits {
    public static final int MAX_ANSWER_COUNT = 500;
    public static final int MAX_ANSWER_TEXT_LENGTH = 16_000;
    public static final int MAX_TOTAL_ANSWER_LENGTH = 1_000_000;
    public static final int MAX_CLIENT_ID_LENGTH = 128;
    public static final int MAX_LEASE_TOKEN_LENGTH = 128;
    public static final long MAX_FUTURE_SKEW_MILLIS = 5 * 60 * 1000L;

    private ExamAnswerLimits() {
    }
}
