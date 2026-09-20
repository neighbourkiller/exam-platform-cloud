package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProctoringDispositionRequest {

    @NotBlank
    private String status;

    @Size(max = 500)
    private String remark;
}
