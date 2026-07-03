package com.ekusys.exam.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RoleCreateRequest(@NotBlank String code, @NotBlank String name) {
}
