package com.ekusys.exam.runtime.client;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.management.api.RuntimeExamAdmission;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.management.api.RuntimeStudentExamSummary;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "exam-management-service", contextId = "runtimeManagementClient")
public interface ManagementRuntimeClient {
    @GetMapping("/internal/v1/exams/{id}/admission")
    ApiResponse<RuntimeExamAdmission> admission(@PathVariable("id") Long id,
                                                @RequestParam("studentId") Long studentId);

    @GetMapping("/internal/v1/exams/{id}/metadata")
    ApiResponse<RuntimeExamMetadata> metadata(@PathVariable("id") Long id);

    @GetMapping("/internal/v1/exams/{id}/proctoring-context")
    ApiResponse<RuntimeExamProctoringContext> proctoringContext(@PathVariable("id") Long id);

    @GetMapping("/internal/v1/exams/students/{studentId}/summaries")
    ApiResponse<List<RuntimeStudentExamSummary>> summaries(@PathVariable("studentId") Long studentId);
}
