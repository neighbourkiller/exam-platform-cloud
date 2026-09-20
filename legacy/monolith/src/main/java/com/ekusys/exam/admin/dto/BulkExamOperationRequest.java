package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class BulkExamOperationRequest {

    @NotEmpty
    private List<Long> examIds;

    @NotBlank
    private String action;
}
