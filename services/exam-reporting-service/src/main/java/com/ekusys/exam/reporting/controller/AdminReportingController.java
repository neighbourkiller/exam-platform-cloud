package com.ekusys.exam.reporting.controller;

import com.ekusys.exam.admin.dto.AdminExamMonitorStudentStatusView;
import com.ekusys.exam.admin.dto.AdminExamMonitorSummaryView;
import com.ekusys.exam.admin.dto.OperationAuditLogView;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.reporting.service.AdminReportingService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportingController {
    private final AdminReportingService service;

    public AdminReportingController(AdminReportingService service) {
        this.service = service;
    }

    @GetMapping("/exam-monitor/summary")
    public ApiResponse<AdminExamMonitorSummaryView> summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.ok(service.summary(startTime, endTime));
    }

    @GetMapping("/exam-monitor/exams/{examId}/students")
    public ApiResponse<List<AdminExamMonitorStudentStatusView>> students(@PathVariable Long examId) {
        return ApiResponse.ok(service.students(examId));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<OperationAuditLogView>> audit(
        @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize,
        @RequestParam(required = false) String operatorKeyword, @RequestParam(required = false) String action,
        @RequestParam(required = false) String targetType, @RequestParam(required = false) String targetId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.ok(service.audit(pageNum, pageSize, operatorKeyword, action, targetType, targetId,
            status, startTime, endTime));
    }
}
