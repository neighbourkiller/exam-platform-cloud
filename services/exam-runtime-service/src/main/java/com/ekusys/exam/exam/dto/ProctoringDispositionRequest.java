package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProctoringDispositionRequest(@NotBlank String status, @Size(max = 500) String remark) {
}
