package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AnswerPayload {

    @NotNull
    @Positive
    private Long questionId;

    @NotNull
    @Size(max = ExamAnswerLimits.MAX_ANSWER_TEXT_LENGTH)
    private String answerText;
}
