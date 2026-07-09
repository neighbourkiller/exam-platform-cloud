package com.ekusys.exam.iam.admin.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.iam.admin.dto.AssignRolesRequest;
import com.ekusys.exam.iam.admin.dto.BulkUserOperationRequest;
import com.ekusys.exam.iam.admin.dto.ResetPasswordRequest;
import com.ekusys.exam.iam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.iam.admin.dto.RoleView;
import com.ekusys.exam.iam.admin.dto.UserCreateRequest;
import com.ekusys.exam.iam.admin.dto.UserQueryRequest;
import com.ekusys.exam.iam.admin.dto.UserUpdateRequest;
import com.ekusys.exam.iam.admin.dto.UserView;
import com.ekusys.exam.iam.admin.service.IamAdminService;
import com.ekusys.exam.iam.admin.service.IamUserImportService;
import com.ekusys.exam.importing.BulkImportResultView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class IamAdminController {
    private final IamAdminService service;
    private final IamUserImportService importService;

    public IamAdminController(IamAdminService service, IamUserImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @PostMapping("/users/query")
    public ApiResponse<PageResponse<UserView>> query(@RequestBody UserQueryRequest request) {
        return ApiResponse.ok(service.queryUsers(request));
    }

    @PostMapping("/users")
    @AuditOperation(action = "USER_CREATE", targetType = "USER", targetId = "#result.data", detail = "#request.username")
    public ApiResponse<Long> create(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.ok("创建成功", service.createUser(request));
    }

    @PutMapping("/users/{id}")
    @AuditOperation(action = "USER_UPDATE", targetType = "USER", targetId = "#id", detail = "#request.realName")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        service.updateUser(id, request); return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/users/{id}")
    @AuditOperation(action = "USER_DELETE", targetType = "USER", targetId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteUser(id); return ApiResponse.ok("删除成功", null);
    }

    @PostMapping("/users/{id}/reset-password")
    @AuditOperation(action = "USER_RESET_PASSWORD", targetType = "USER", targetId = "#id", detail = "'reset-password'")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(id, request.password()); return ApiResponse.ok("重置成功", null);
    }

    @PutMapping("/users/{id}/roles")
    @AuditOperation(action = "USER_ASSIGN_ROLES", targetType = "USER", targetId = "#id", detail = "#request.roleIds")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        service.assignRoles(id, request.roleIds()); return ApiResponse.ok("分配成功", null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles() {
        return ApiResponse.ok(service.listRoles());
    }

    @PostMapping("/roles")
    @AuditOperation(action = "ROLE_CREATE", targetType = "ROLE", targetId = "#result.data", detail = "#request.code")
    public ApiResponse<Long> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok("创建成功", service.createRole(request));
    }

    @PostMapping("/users/batch")
    @AuditOperation(action = "BULK_USER_OPERATION", targetType = "USER", targetId = "'bulk'", detail = "#request.action")
    public ApiResponse<Void> operateUsers(@Valid @RequestBody BulkUserOperationRequest request) {
        service.operateUsers(request); return ApiResponse.ok("批量操作成功", null);
    }

    @PostMapping(value = "/users/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_USER_IMPORT", targetType = "USER", targetId = "'bulk'",
        detail = "#role + ',dryRun=' + #dryRun + ',success=' + #result.data.successCount + ',failure=' + #result.data.failureCount")
    public ApiResponse<BulkImportResultView> importUsers(@RequestParam("file") MultipartFile file,
                                                          @RequestParam("role") String role,
                                                          @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", importService.importUsers(file, role, dryRun));
    }
}
