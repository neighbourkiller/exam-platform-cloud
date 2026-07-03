package com.ekusys.exam.paper.dto;

import com.ekusys.exam.common.enums.Difficulty;
import com.ekusys.exam.common.enums.QuestionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AutoGenerateRule {

    @NotNull
    private QuestionType type;

    private Difficulty difficulty;

    @NotNull
    @Min(1)
    private Integer count;

    @NotNull
    @Min(1)
    private Integer score;
}
