package com.ekusys.exam.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BulkUserOperationRequest(@NotEmpty List<Long> userIds, @NotBlank String action,
                                       List<Long> roleIds, List<Long> teachingClassIds, String password) {
}
