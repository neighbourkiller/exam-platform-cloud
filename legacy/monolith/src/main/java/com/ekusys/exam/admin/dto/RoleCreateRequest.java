package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoleCreateRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;
}
