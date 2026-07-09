package com.ekusys.exam.management.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.management.service.ExamManagementService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exams")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ManagementExamController {
    private final ExamManagementService service;

    public ManagementExamController(ExamManagementService service) {
        this.service = service;
    }

    @PostMapping
    @AuditOperation(action = "EXAM_CREATE", targetType = "EXAM", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> create(@Valid @RequestBody ExamCreateRequest request) {
        return ApiResponse.ok("创建成功", service.create(request));
    }

    @PostMapping("/{id}/publish")
    @AuditOperation(action = "EXAM_PUBLISH", targetType = "EXAM", targetId = "#id")
    public ApiResponse<Void> publish(@PathVariable Long id) {
        service.publish(id);
        return ApiResponse.ok("发布成功", null);
    }

    @PostMapping("/{id}/terminate")
    @AuditOperation(action = "EXAM_TERMINATE", targetType = "EXAM", targetId = "#id")
    public ApiResponse<Void> terminate(@PathVariable Long id) {
        service.terminate(id);
        return ApiResponse.ok("终止成功", null);
    }

    @GetMapping("/teacher")
    public ApiResponse<List<TeacherExamView>> teacher() {
        return ApiResponse.ok(service.listTeacher());
    }
}
