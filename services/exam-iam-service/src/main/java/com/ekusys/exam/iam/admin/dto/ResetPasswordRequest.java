package com.ekusys.exam.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank String password) {
}
