package com.ekusys.exam.grading.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class SubjectiveScoreRequest {

    @Valid
    @NotEmpty
    private List<SubjectiveScoreItem> scores;
}
