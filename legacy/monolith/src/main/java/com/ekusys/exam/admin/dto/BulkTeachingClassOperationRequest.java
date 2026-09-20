package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class BulkTeachingClassOperationRequest {

    @NotEmpty
    private List<Long> classIds;

    @NotBlank
    private String status;
}
