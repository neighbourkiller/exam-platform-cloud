package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.ExamAnswerLimits;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamAnswerInputValidatorTest {
    private final ExamAnswerInputValidator validator = new ExamAnswerInputValidator();

    @Test
    void acceptsValidAnswersAndOlderOfflineVersion() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        SnapshotRequest request = snapshot(answer(1L, "A"));
        request.setSnapshotVersion(Timestamp.valueOf(receivedAt.minusHours(1)).getTime());

        assertThatCode(() -> {
            validator.validateAnswers(request.getAnswers());
            validator.validateSnapshotVersion(request, receivedAt);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateQuestionIds() {
        List<AnswerPayload> answers = List.of(answer(1L, "A"), answer(1L, "B"));

        assertBusinessCode(
            () -> validator.validateAnswers(answers),
            ExamAnswerInputValidator.INVALID_ANSWERS_CODE
        );
    }

    @Test
    void rejectsAggregateAnswerLengthOverLimit() {
        List<AnswerPayload> answers = new ArrayList<>();
        for (long questionId = 1; questionId <= 63; questionId++) {
            answers.add(answer(questionId, "A".repeat(ExamAnswerLimits.MAX_ANSWER_TEXT_LENGTH)));
        }

        assertBusinessCode(
            () -> validator.validateAnswers(answers),
            ExamAnswerInputValidator.INVALID_ANSWERS_CODE
        );
    }

    @Test
    void rejectsVersionMoreThanFiveMinutesAhead() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        SnapshotRequest request = snapshot(answer(1L, "A"));
        request.setSnapshotVersion(
            Timestamp.valueOf(receivedAt).getTime() + ExamAnswerLimits.MAX_FUTURE_SKEW_MILLIS + 1
        );

        assertBusinessCode(
            () -> validator.validateSnapshotVersion(request, receivedAt),
            ExamAnswerInputValidator.INVALID_VERSION_CODE
        );
    }

    @Test
    void validatesLeaseFieldsForNormalSubmission() {
        SubmitExamRequest request = new SubmitExamRequest();
        request.setAnswers(List.of(answer(1L, "A")));
        request.setClientId(" ");
        request.setLeaseToken("lease-1");

        assertThatThrownBy(() -> validator.validateSubmitRequest(request))
            .isInstanceOf(BusinessException.class);
    }

    private void assertBusinessCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(code));
    }

    private SnapshotRequest snapshot(AnswerPayload... answers) {
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(List.of(answers));
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        return request;
    }

    private AnswerPayload answer(Long questionId, String text) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(questionId);
        answer.setAnswerText(text);
        return answer;
    }
}
