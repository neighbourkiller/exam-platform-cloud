package com.ekusys.exam.admin.controller;

import com.ekusys.exam.admin.dto.AssignRolesRequest;
import com.ekusys.exam.admin.dto.AdminExamMonitorStudentStatusView;
import com.ekusys.exam.admin.dto.AdminExamMonitorSummaryView;
import com.ekusys.exam.admin.dto.BulkExamOperationRequest;
import com.ekusys.exam.admin.dto.BulkImportResultView;
import com.ekusys.exam.admin.dto.BulkTeachingClassOperationRequest;
import com.ekusys.exam.admin.dto.BulkUserOperationRequest;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.CourseView;
import com.ekusys.exam.admin.dto.OperationAuditLogView;
import com.ekusys.exam.admin.dto.ResetPasswordRequest;
import com.ekusys.exam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.admin.dto.RoleView;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.admin.dto.UserQueryRequest;
import com.ekusys.exam.admin.dto.UserUpdateRequest;
import com.ekusys.exam.admin.dto.UserView;
import com.ekusys.exam.admin.service.AdminBulkService;
import com.ekusys.exam.admin.service.AdminExamMonitorService;
import com.ekusys.exam.admin.service.AdminService;
import com.ekusys.exam.admin.service.OperationAuditLogQueryService;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.api.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AdminBulkService adminBulkService;
    private final AdminExamMonitorService adminExamMonitorService;
    private final OperationAuditLogQueryService operationAuditLogQueryService;

    public AdminController(AdminService adminService,
                           AdminBulkService adminBulkService,
                           AdminExamMonitorService adminExamMonitorService,
                           OperationAuditLogQueryService operationAuditLogQueryService) {
        this.adminService = adminService;
        this.adminBulkService = adminBulkService;
        this.adminExamMonitorService = adminExamMonitorService;
        this.operationAuditLogQueryService = operationAuditLogQueryService;
    }

    @PostMapping("/users/query")
    public ApiResponse<PageResponse<UserView>> queryUsers(@RequestBody UserQueryRequest request) {
        return ApiResponse.ok(adminService.queryUsers(request));
    }

    @PostMapping("/users")
    @AuditOperation(action = "USER_CREATE", targetType = "USER", targetId = "#result.data", detail = "#request.username")
    public ApiResponse<Long> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.ok("创建成功", adminService.createUser(request));
    }

    @PutMapping("/users/{userId}")
    @AuditOperation(action = "USER_UPDATE", targetType = "USER", targetId = "#userId", detail = "#request.realName")
    public ApiResponse<Void> updateUser(@PathVariable Long userId, @Valid @RequestBody UserUpdateRequest request) {
        adminService.updateUser(userId, request);
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/users/{userId}")
    @AuditOperation(action = "USER_DELETE", targetType = "USER", targetId = "#userId")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        adminService.deleteUser(userId);
        return ApiResponse.ok("删除成功", null);
    }

    @PostMapping("/users/{userId}/reset-password")
    @AuditOperation(action = "USER_RESET_PASSWORD", targetType = "USER", targetId = "#userId", detail = "'reset-password'")
    public ApiResponse<Void> resetPassword(@PathVariable Long userId, @Valid @RequestBody ResetPasswordRequest request) {
        adminService.resetPassword(userId, request.getPassword());
        return ApiResponse.ok("重置成功", null);
    }

    @PutMapping("/users/{userId}/roles")
    @AuditOperation(action = "USER_ASSIGN_ROLES", targetType = "USER", targetId = "#userId", detail = "#request.roleIds")
    public ApiResponse<Void> assignRoles(@PathVariable Long userId, @Valid @RequestBody AssignRolesRequest request) {
        adminService.assignRoles(userId, request.getRoleIds());
        return ApiResponse.ok("分配成功", null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> listRoles() {
        return ApiResponse.ok(adminService.listRoles());
    }

    @PostMapping("/roles")
    @AuditOperation(action = "ROLE_CREATE", targetType = "ROLE", targetId = "#result.data", detail = "#request.code")
    public ApiResponse<Long> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok("创建成功", adminService.createRole(request));
    }

    @GetMapping("/courses")
    public ApiResponse<List<CourseView>> listCourses() {
        return ApiResponse.ok(adminService.listCourses());
    }

    @PostMapping("/courses")
    @AuditOperation(action = "COURSE_CREATE", targetType = "COURSE", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> createCourse(@Valid @RequestBody CourseCreateRequest request) {
        return ApiResponse.ok("创建成功", adminService.createCourse(request));
    }

    @PostMapping(value = "/courses/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_COURSE_IMPORT", targetType = "COURSE", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun")
    public ApiResponse<BulkImportResultView> importCourses(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", adminBulkService.importCourses(file, dryRun));
    }

    @PutMapping("/courses/{courseId}")
    @AuditOperation(action = "COURSE_UPDATE", targetType = "COURSE", targetId = "#courseId", detail = "#request.name")
    public ApiResponse<Void> updateCourse(@PathVariable Long courseId, @Valid @RequestBody CourseUpdateRequest request) {
        adminService.updateCourse(courseId, request);
        return ApiResponse.ok("更新成功", null);
    }

    @GetMapping("/teaching-classes")
    public ApiResponse<List<TeachingClassView>> listTeachingClasses() {
        return ApiResponse.ok(adminService.listTeachingClasses());
    }

    @PostMapping("/teaching-classes")
    @AuditOperation(action = "TEACHING_CLASS_CREATE", targetType = "TEACHING_CLASS", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> createTeachingClass(@Valid @RequestBody TeachingClassCreateRequest request) {
        return ApiResponse.ok("创建成功", adminService.createTeachingClass(request));
    }

    @PutMapping("/teaching-classes/{id}")
    @AuditOperation(action = "TEACHING_CLASS_UPDATE", targetType = "TEACHING_CLASS", targetId = "#id", detail = "#request.name")
    public ApiResponse<Void> updateTeachingClass(@PathVariable Long id,
                                                 @Valid @RequestBody TeachingClassUpdateRequest request) {
        adminService.updateTeachingClass(id, request);
        return ApiResponse.ok("更新成功", null);
    }

    @PostMapping(value = "/users/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_USER_IMPORT", targetType = "USER", targetId = "'bulk'", detail = "#role + ',dryRun=' + #dryRun")
    public ApiResponse<BulkImportResultView> importUsers(@RequestParam("file") MultipartFile file,
                                                         @RequestParam("role") String role,
                                                         @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", adminBulkService.importUsers(file, role, dryRun));
    }

    @PostMapping(value = "/teaching-classes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_TEACHING_CLASS_IMPORT", targetType = "TEACHING_CLASS", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun")
    public ApiResponse<BulkImportResultView> importTeachingClasses(@RequestParam("file") MultipartFile file,
                                                                   @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", adminBulkService.importTeachingClasses(file, dryRun));
    }

    @PostMapping(value = "/exam-schedules/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_EXAM_SCHEDULE_IMPORT", targetType = "EXAM", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun")
    public ApiResponse<BulkImportResultView> importExamSchedules(@RequestParam("file") MultipartFile file,
                                                                 @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", adminBulkService.importExamSchedules(file, dryRun));
    }

    @PostMapping("/users/batch")
    @AuditOperation(action = "BULK_USER_OPERATION", targetType = "USER", targetId = "'bulk'", detail = "#request.action")
    public ApiResponse<Void> operateUsers(@Valid @RequestBody BulkUserOperationRequest request) {
        adminBulkService.operateUsers(request);
        return ApiResponse.ok("批量操作成功", null);
    }

    @PostMapping("/teaching-classes/batch")
    @AuditOperation(action = "BULK_TEACHING_CLASS_OPERATION", targetType = "TEACHING_CLASS", targetId = "'bulk'",
        detail = "#request.status")
    public ApiResponse<Void> operateTeachingClasses(@Valid @RequestBody BulkTeachingClassOperationRequest request) {
        adminBulkService.operateTeachingClasses(request);
        return ApiResponse.ok("批量操作成功", null);
    }

    @PostMapping("/exams/batch")
    @AuditOperation(action = "BULK_EXAM_OPERATION", targetType = "EXAM", targetId = "'bulk'", detail = "#request.action")
    public ApiResponse<Void> operateExams(@Valid @RequestBody BulkExamOperationRequest request) {
        adminBulkService.operateExams(request);
        return ApiResponse.ok("批量操作成功", null);
    }

    @GetMapping("/exam-monitor/summary")
    public ApiResponse<AdminExamMonitorSummaryView> examMonitorSummary(
        @RequestParam(value = "startTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(value = "endTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.ok(adminExamMonitorService.summary(startTime, endTime));
    }

    @GetMapping("/exam-monitor/exams/{examId}/students")
    public ApiResponse<List<AdminExamMonitorStudentStatusView>> examMonitorStudents(@PathVariable Long examId) {
        return ApiResponse.ok(adminExamMonitorService.students(examId));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<OperationAuditLogView>> auditLogs(
        @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
        @RequestParam(value = "pageSize", defaultValue = "20") long pageSize,
        @RequestParam(value = "operatorKeyword", required = false) String operatorKeyword,
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "targetType", required = false) String targetType,
        @RequestParam(value = "targetId", required = false) String targetId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "startTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(value = "endTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.ok(operationAuditLogQueryService.query(
            pageNum, pageSize, operatorKeyword, action, targetType, targetId, status, startTime, endTime
        ));
    }
}
