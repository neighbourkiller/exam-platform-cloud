package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AnswerPayload {

    @NotNull
    private Long questionId;

    @NotNull
    private String answerText;
}
