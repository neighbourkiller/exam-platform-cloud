package com.ekusys.exam.grading.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class QuestionBatchScoreRequest {

    @NotNull
    private Long examId;

    @NotEmpty
    private List<Long> submissionAnswerIds;

    @NotNull
    @Min(0)
    private Integer score;

    @Size(max = 255)
    private String comment;
}
