package com.ekusys.exam.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BulkExamOperationRequest(@NotEmpty List<Long> examIds, @NotBlank String action) {
}
