package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.ExamAnswerLimits;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMoreThanFiveHundredAnswers() {
        SnapshotRequest request = validSnapshot();
        List<AnswerPayload> answers = new ArrayList<>();
        for (long questionId = 1; questionId <= ExamAnswerLimits.MAX_ANSWER_COUNT + 1L; questionId++) {
            answers.add(answer(questionId, ""));
        }
        request.setAnswers(answers);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsOversizedAnswerAndNegativeQuestionId() {
        SnapshotRequest request = validSnapshot();
        request.setAnswers(List.of(answer(-1L, "A".repeat(ExamAnswerLimits.MAX_ANSWER_TEXT_LENGTH + 1))));

        assertThat(validator.validate(request)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void requiresClientLeaseForSnapshotAndSubmit() {
        SnapshotRequest snapshot = validSnapshot();
        snapshot.setClientId(" ");
        snapshot.setLeaseToken(null);
        SubmitExamRequest submit = new SubmitExamRequest();
        submit.setAnswers(List.of(answer(1L, "")));

        assertThat(validator.validate(snapshot)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(validator.validate(submit)).hasSizeGreaterThanOrEqualTo(2);
    }

    private SnapshotRequest validSnapshot() {
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(List.of(answer(1L, "")));
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        request.setClientTimestamp(1L);
        request.setSnapshotVersion(1L);
        return request;
    }

    private AnswerPayload answer(Long questionId, String text) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(questionId);
        answer.setAnswerText(text);
        return answer;
    }
}
