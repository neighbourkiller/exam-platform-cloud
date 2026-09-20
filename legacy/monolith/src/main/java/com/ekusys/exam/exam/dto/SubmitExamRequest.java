package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class SubmitExamRequest {

    @NotNull
    private List<AnswerPayload> answers;
}
