package com.ekusys.exam.management.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.management.dto.BulkExamOperationRequest;
import com.ekusys.exam.management.service.ExamManagementService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/exams")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExamBatchController {
    private final ExamManagementService service;

    public AdminExamBatchController(ExamManagementService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    public ApiResponse<Void> operate(@Valid @RequestBody BulkExamOperationRequest request) {
        String action = request.action().trim().toUpperCase();
        for (Long id : request.examIds().stream().distinct().toList()) {
            switch (action) {
                case "PUBLISH" -> service.publish(id);
                case "TERMINATE" -> service.terminate(id);
                default -> throw new BusinessException("不支持的批量考试操作: " + request.action());
            }
        }
        return ApiResponse.ok("批量操作成功", null);
    }
}
