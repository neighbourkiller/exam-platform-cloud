package com.ekusys.exam.exam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SubmitExamRequest {

    @Valid
    @NotEmpty
    @Size(max = ExamAnswerLimits.MAX_ANSWER_COUNT)
    private List<AnswerPayload> answers;

    @NotBlank
    @Size(max = ExamAnswerLimits.MAX_CLIENT_ID_LENGTH)
    private String clientId;

    @NotBlank
    @Size(max = ExamAnswerLimits.MAX_LEASE_TOKEN_LENGTH)
    private String leaseToken;
}
