package com.ekusys.exam.question.dto;

import com.ekusys.exam.common.enums.Difficulty;
import com.ekusys.exam.common.enums.QuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class QuestionUpdateRequest {

    @NotNull
    private Long subjectId;

    @NotNull
    private QuestionType type;

    @NotNull
    private Difficulty difficulty;

    @NotBlank
    private String content;

    private String optionsJson;

    @NotBlank
    private String answer;

    private String analysis;

    @NotNull
    private Integer defaultScore;

    private List<Long> assetIds;
}
