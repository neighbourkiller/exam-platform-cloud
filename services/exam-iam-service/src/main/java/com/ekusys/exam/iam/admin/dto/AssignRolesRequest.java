package com.ekusys.exam.iam.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AssignRolesRequest(@NotEmpty List<Long> roleIds) {
}
