package com.ekusys.exam.paper.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ManualPaperQuestion {

    @NotNull
    private Long questionId;

    @NotNull
    @Min(1)
    private Integer score;

    @NotNull
    @Min(1)
    private Integer sortOrder;
}
