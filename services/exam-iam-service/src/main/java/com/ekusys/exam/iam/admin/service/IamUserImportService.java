package com.ekusys.exam.iam.admin.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.iam.admin.dto.UserCreateRequest;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.BulkImportRowErrorView;
import com.ekusys.exam.importing.CsvImportParser;
import com.ekusys.exam.importing.CsvImportValues;
import com.ekusys.exam.importing.RowImportException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IamUserImportService {
    private final CsvImportParser parser;
    private final IamAdminService adminService;
    private final JdbcTemplate jdbc;

    public IamUserImportService(CsvImportParser parser, IamAdminService adminService, JdbcTemplate jdbc) {
        this.parser = parser;
        this.adminService = adminService;
        this.jdbc = jdbc;
    }

    public BulkImportResultView importUsers(MultipartFile file, String role, boolean dryRun) {
        String roleCode = normalizeRole(role);
        Long roleId = resolveRoleId(roleCode);
        CsvImportParser.ParsedCsv csv = parser.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        LinkedHashSet<String> seenUsernames = new LinkedHashSet<>();
        int success = 0;
        for (CsvImportParser.CsvRow row : csv.rows()) {
            try {
                String usernameField = "STUDENT".equals(roleCode) ? "studentNo" : "username";
                CsvImportValues.require(row, usernameField, "realName");
                String username = row.value(usernameField).trim();
                if (!seenUsernames.add(username)) {
                    throw new RowImportException(usernameField,
                        "STUDENT".equals(roleCode) ? "学号在导入文件中重复" : "用户名在导入文件中重复",
                        username);
                }
                UserCreateRequest request = buildRequest(row, roleCode, roleId, username);
                if (dryRun) {
                    adminService.validateCreateUser(request);
                } else {
                    adminService.createUser(request);
                }
                success++;
            } catch (RowImportException exception) {
                errors.add(new BulkImportRowErrorView(row.rowNumber(), exception.getField(),
                    exception.getMessage(), exception.getRawValue()));
            } catch (Exception exception) {
                errors.add(new BulkImportRowErrorView(row.rowNumber(), null,
                    exception.getMessage() == null ? "处理失败" : exception.getMessage(), null));
            }
        }
        return BulkImportResultView.of(csv.rows().size(), success, dryRun, errors);
    }

    private UserCreateRequest buildRequest(CsvImportParser.CsvRow row, String roleCode,
                                           Long roleId, String username) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setRealName(row.value("realName"));
        request.setPassword(CsvImportValues.nullable(row.value("password")));
        request.setRoleIds(List.of(roleId));
        if ("STUDENT".equals(roleCode)) {
            request.setStudentNo(CsvImportValues.nullable(row.value("studentNo")));
            request.setEnrollmentYear(CsvImportValues.nullable(row.value("enrollmentYear")));
            request.setTeachingClassIds(CsvImportValues.longList(row.value("teachingClassIds"), "teachingClassIds"));
        } else {
            request.setTeacherNo(CsvImportValues.nullable(row.value("teacherNo")));
            request.setTitle(CsvImportValues.nullable(row.value("title")));
        }
        return request;
    }

    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!List.of("STUDENT", "TEACHER").contains(value)) {
            throw new BusinessException("导入角色仅支持 STUDENT 或 TEACHER");
        }
        return value;
    }

    private Long resolveRoleId(String roleCode) {
        List<Long> ids = jdbc.queryForList("select id from sys_role where code=?", Long.class, roleCode);
        if (ids.isEmpty()) {
            throw new BusinessException("角色不存在: " + roleCode);
        }
        return ids.getFirst();
    }
}
