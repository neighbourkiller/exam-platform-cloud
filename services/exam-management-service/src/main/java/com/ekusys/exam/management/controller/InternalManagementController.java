package com.ekusys.exam.management.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.management.api.RuntimeExamAdmission;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.management.api.RuntimeExamSnapshot;
import com.ekusys.exam.management.api.RuntimeStudentExamSummary;
import com.ekusys.exam.management.service.ExamManagementService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/exams")
public class InternalManagementController {
    private final ExamManagementService service;

    public InternalManagementController(ExamManagementService service) {
        this.service = service;
    }

    @GetMapping("/papers/{paperId}/used")
    public ApiResponse<Boolean> used(@PathVariable Long paperId) {
        return ApiResponse.ok(service.isPaperUsed(paperId));
    }

    @GetMapping("/{id}/admission")
    public ApiResponse<RuntimeExamAdmission> admission(@PathVariable Long id,
                                                       @RequestParam Long studentId) {
        return ApiResponse.ok(service.admission(id, studentId));
    }

    @GetMapping("/{id}/metadata")
    public ApiResponse<RuntimeExamMetadata> metadata(@PathVariable Long id) {
        return ApiResponse.ok(service.runtimeMetadata(id));
    }

    @GetMapping("/{id}/proctoring-context")
    public ApiResponse<RuntimeExamProctoringContext> proctoringContext(@PathVariable Long id) {
        return ApiResponse.ok(service.proctoringContext(id));
    }

    @GetMapping("/students/{studentId}/summaries")
    public ApiResponse<List<RuntimeStudentExamSummary>> summaries(@PathVariable Long studentId) {
        return ApiResponse.ok(service.studentSummaries(studentId));
    }

    @Deprecated
    @GetMapping("/{id}/runtime-snapshot")
    public ApiResponse<RuntimeExamSnapshot> snapshot(@PathVariable Long id) {
        return ApiResponse.ok(service.runtimeSnapshot(id));
    }

    @Deprecated
    @GetMapping("/students/{studentId}")
    public ApiResponse<List<RuntimeExamSnapshot>> student(@PathVariable Long studentId) {
        return ApiResponse.ok(service.studentSnapshots(studentId));
    }
}
