package com.ekusys.exam.importing;

import java.util.List;

public record BulkImportResultView(Integer total,
                                   Integer successCount,
                                   Integer failureCount,
                                   Boolean dryRun,
                                   List<BulkImportRowErrorView> errors) {
    public static BulkImportResultView of(int total, int success, boolean dryRun,
                                          List<BulkImportRowErrorView> errors) {
        return new BulkImportResultView(total, success, errors.size(), dryRun, List.copyOf(errors));
    }
}
