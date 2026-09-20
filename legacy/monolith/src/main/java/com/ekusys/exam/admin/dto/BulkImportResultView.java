package com.ekusys.exam.admin.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkImportResultView {

    private Integer total;
    private Integer successCount;
    private Integer failureCount;
    private Boolean dryRun;
    private List<BulkImportRowErrorView> errors;
}
