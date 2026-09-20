package com.ekusys.exam.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkImportRowErrorView {

    private Integer rowNumber;
    private String field;
    private String message;
    private String rawValue;
}
