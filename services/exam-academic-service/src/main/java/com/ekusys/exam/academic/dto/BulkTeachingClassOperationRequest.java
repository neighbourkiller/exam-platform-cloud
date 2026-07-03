package com.ekusys.exam.academic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BulkTeachingClassOperationRequest(@NotEmpty List<Long> classIds, @NotBlank String status) {
}
