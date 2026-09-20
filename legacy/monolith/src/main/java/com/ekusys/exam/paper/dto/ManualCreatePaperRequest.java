package com.ekusys.exam.paper.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class ManualCreatePaperRequest {

    @NotBlank
    private String name;

    @NotNull
    private Long subjectId;

    private String description;

    @Valid
    @NotEmpty
    private List<ManualPaperQuestion> questions;
}
