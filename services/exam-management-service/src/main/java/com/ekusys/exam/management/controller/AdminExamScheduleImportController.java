package com.ekusys.exam.management.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.management.service.ExamScheduleImportService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/exam-schedules")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExamScheduleImportController {
    private final ExamScheduleImportService importService;

    public AdminExamScheduleImportController(ExamScheduleImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_EXAM_SCHEDULE_IMPORT", targetType = "EXAM", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun + ',success=' + #result.data.successCount + ',failure=' + #result.data.failureCount")
    public ApiResponse<BulkImportResultView> importSchedules(@RequestParam("file") MultipartFile file,
                                                              @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", importService.importSchedules(file, dryRun));
    }
}
