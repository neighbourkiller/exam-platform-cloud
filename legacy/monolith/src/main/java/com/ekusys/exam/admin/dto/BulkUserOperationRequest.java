package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class BulkUserOperationRequest {

    @NotEmpty
    private List<Long> userIds;

    @NotBlank
    private String action;

    private List<Long> roleIds;
    private List<Long> teachingClassIds;
    private String password;
}
