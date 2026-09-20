package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.BulkExamOperationRequest;
import com.ekusys.exam.admin.dto.BulkImportResultView;
import com.ekusys.exam.admin.dto.BulkImportRowErrorView;
import com.ekusys.exam.admin.dto.BulkTeachingClassOperationRequest;
import com.ekusys.exam.admin.dto.BulkUserOperationRequest;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.service.ExamService;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminBulkService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminCsvImportService csvImportService;
    private final UserAdminService userAdminService;
    private final RoleAdminService roleAdminService;
    private final UserProfileSyncService userProfileSyncService;
    private final TeachingClassAdminService teachingClassAdminService;
    private final SubjectAdminService subjectAdminService;
    private final ExamService examService;
    private final UserMapper userMapper;
    private final PaperMapper paperMapper;
    private final RoleMapper roleMapper;
    private final SubjectMapper subjectMapper;
    private final TeachingClassMapper teachingClassMapper;

    public AdminBulkService(AdminCsvImportService csvImportService,
                            UserAdminService userAdminService,
                            RoleAdminService roleAdminService,
                            UserProfileSyncService userProfileSyncService,
                            TeachingClassAdminService teachingClassAdminService,
                            SubjectAdminService subjectAdminService,
                            ExamService examService,
                            UserMapper userMapper,
                            PaperMapper paperMapper,
                            RoleMapper roleMapper,
                            SubjectMapper subjectMapper,
                            TeachingClassMapper teachingClassMapper) {
        this.csvImportService = csvImportService;
        this.userAdminService = userAdminService;
        this.roleAdminService = roleAdminService;
        this.userProfileSyncService = userProfileSyncService;
        this.teachingClassAdminService = teachingClassAdminService;
        this.subjectAdminService = subjectAdminService;
        this.examService = examService;
        this.userMapper = userMapper;
        this.paperMapper = paperMapper;
        this.roleMapper = roleMapper;
        this.subjectMapper = subjectMapper;
        this.teachingClassMapper = teachingClassMapper;
    }

    public BulkImportResultView importUsers(MultipartFile file, String role, boolean dryRun) {
        String roleCode = normalizeRole(role);
        Role targetRole = ensureRole(roleCode);
        AdminCsvImportService.ParsedCsv csv = csvImportService.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        LinkedHashSet<String> seenUsernames = new LinkedHashSet<>();
        int success = 0;
        for (AdminCsvImportService.CsvRow row : csv.rows()) {
            try {
                String usernameField = "STUDENT".equals(roleCode) ? "studentNo" : "username";
                validateRequired(row, usernameField, "realName");
                String username = value(row, usernameField);
                if (!seenUsernames.add(username)) {
                    throw new RowException(usernameField, duplicateUsernameMessage(roleCode), username);
                }
                UserCreateRequest request = buildUserCreateRequest(row, roleCode, targetRole.getId(), username);
                if (!dryRun) {
                    Long userId = userAdminService.createUser(request);
                    if ("TEACHER".equals(roleCode)) {
                        userProfileSyncService.syncTeacherProfile(userId, value(row, "teacherNo"), value(row, "title"));
                    }
                } else {
                    userAdminService.validateCreateUser(request);
                }
                success++;
            } catch (RowException ex) {
                errors.add(toError(row.rowNumber(), ex.field, ex.getMessage(), ex.rawValue));
            } catch (Exception ex) {
                errors.add(toError(row.rowNumber(), null, ex.getMessage(), null));
            }
        }
        return importResult(csv.rows().size(), success, dryRun, errors);
    }

    public BulkImportResultView importCourses(MultipartFile file, boolean dryRun) {
        AdminCsvImportService.ParsedCsv csv = csvImportService.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        LinkedHashSet<Long> seenIds = new LinkedHashSet<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        int success = 0;
        for (AdminCsvImportService.CsvRow row : csv.rows()) {
            try {
                validateRequired(row, "name");
                Long id = parseLong(value(row, "id"), "id");
                if (id != null && !seenIds.add(id)) {
                    throw new RowException("id", "课程ID在导入文件中重复", value(row, "id"));
                }
                String name = value(row, "name").trim();
                if (!seenNames.add(name)) {
                    throw new RowException("name", "课程名称在导入文件中重复", name);
                }

                Subject existing = id == null ? null : subjectMapper.selectById(id);
                if (existing == null) {
                    CourseCreateRequest request = new CourseCreateRequest();
                    request.setId(id);
                    request.setName(name);
                    request.setDescription(emptyToNull(value(row, "description")));
                    if (dryRun) {
                        subjectAdminService.validateCreateCourse(request);
                    } else {
                        subjectAdminService.createCourse(request);
                    }
                } else {
                    CourseUpdateRequest request = new CourseUpdateRequest();
                    request.setName(name);
                    request.setDescription(emptyToNull(value(row, "description")));
                    if (dryRun) {
                        subjectAdminService.validateUpdateCourse(id, request);
                    } else {
                        subjectAdminService.updateCourse(id, request);
                    }
                }
                success++;
            } catch (RowException ex) {
                errors.add(toError(row.rowNumber(), ex.field, ex.getMessage(), ex.rawValue));
            } catch (Exception ex) {
                errors.add(toError(row.rowNumber(), null, ex.getMessage(), null));
            }
        }
        return importResult(csv.rows().size(), success, dryRun, errors);
    }

    public BulkImportResultView importTeachingClasses(MultipartFile file, boolean dryRun) {
        AdminCsvImportService.ParsedCsv csv = csvImportService.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        int success = 0;
        for (AdminCsvImportService.CsvRow row : csv.rows()) {
            try {
                validateRequired(row, "name", "subjectId", "term");
                Long id = parseLong(value(row, "id"), "id");
                Long teacherId = resolveTeacherId(row);
                if (teacherId == null) {
                    throw new RowException("teacherId", "teacherId 或 teacherUsername 必填", null);
                }
                if (!dryRun) {
                    TeachingClass existing = id == null ? null : teachingClassMapper.selectById(id);
                    if (existing == null) {
                        TeachingClassCreateRequest request = new TeachingClassCreateRequest();
                        request.setId(id);
                        request.setName(value(row, "name"));
                        request.setSubjectId(parseLong(value(row, "subjectId"), "subjectId"));
                        request.setTeacherId(teacherId);
                        request.setTerm(value(row, "term"));
                        request.setStatus(emptyToNull(value(row, "status")));
                        request.setCapacity(parseInteger(value(row, "capacity"), "capacity"));
                        teachingClassAdminService.createTeachingClass(request);
                    } else {
                        TeachingClassUpdateRequest request = new TeachingClassUpdateRequest();
                        request.setName(value(row, "name"));
                        request.setSubjectId(parseLong(value(row, "subjectId"), "subjectId"));
                        request.setTeacherId(teacherId);
                        request.setTerm(value(row, "term"));
                        request.setStatus(emptyToNull(value(row, "status")));
                        request.setCapacity(parseInteger(value(row, "capacity"), "capacity"));
                        teachingClassAdminService.updateTeachingClass(id, request);
                    }
                } else {
                    teachingClassAdminService.ensureTeachingClassRelation(parseLong(value(row, "subjectId"), "subjectId"), teacherId);
                }
                success++;
            } catch (RowException ex) {
                errors.add(toError(row.rowNumber(), ex.field, ex.getMessage(), ex.rawValue));
            } catch (Exception ex) {
                errors.add(toError(row.rowNumber(), null, ex.getMessage(), null));
            }
        }
        return importResult(csv.rows().size(), success, dryRun, errors);
    }

    public BulkImportResultView importExamSchedules(MultipartFile file, boolean dryRun) {
        AdminCsvImportService.ParsedCsv csv = csvImportService.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        int success = 0;
        for (AdminCsvImportService.CsvRow row : csv.rows()) {
            try {
                validateRequired(row, "name", "paperId", "startTime", "endTime", "durationMinutes", "passScore", "targetClassIds");
                ExamCreateRequest request = new ExamCreateRequest();
                request.setName(value(row, "name"));
                request.setPaperId(parseLong(value(row, "paperId"), "paperId"));
                request.setStartTime(parseDateTime(value(row, "startTime"), "startTime"));
                request.setEndTime(parseDateTime(value(row, "endTime"), "endTime"));
                request.setDurationMinutes(parseInteger(value(row, "durationMinutes"), "durationMinutes"));
                request.setPassScore(parseInteger(value(row, "passScore"), "passScore"));
                request.setTargetClassIds(parseLongList(value(row, "targetClassIds")));
                request.setProctoringLevel(emptyToNull(value(row, "proctoringLevel")));
                if (!dryRun) {
                    Long examId = examService.createExam(request);
                    if (parseBoolean(value(row, "autoPublish"))) {
                        examService.publishExam(examId);
                    }
                } else {
                    validateExamSchedule(request);
                }
                success++;
            } catch (RowException ex) {
                errors.add(toError(row.rowNumber(), ex.field, ex.getMessage(), ex.rawValue));
            } catch (Exception ex) {
                errors.add(toError(row.rowNumber(), null, ex.getMessage(), null));
            }
        }
        return importResult(csv.rows().size(), success, dryRun, errors);
    }

    @Transactional
    public void operateUsers(BulkUserOperationRequest request) {
        List<Long> userIds = normalizeIds(request.getUserIds());
        String action = request.getAction().trim().toUpperCase(Locale.ROOT);
        for (Long userId : userIds) {
            switch (action) {
                case "ENABLE" -> updateUserEnabled(userId, true);
                case "DISABLE" -> updateUserEnabled(userId, false);
                case "RESET_PASSWORD" -> {
                    if (emptyToNull(request.getPassword()) == null) {
                        throw new BusinessException("密码不能为空");
                    }
                    userAdminService.resetPassword(userId, request.getPassword());
                }
                case "ASSIGN_ROLES" -> roleAdminService.assignRoles(userId, request.getRoleIds(), request.getTeachingClassIds());
                case "ASSIGN_CLASSES" -> userProfileSyncService.updateStudentTeachingClasses(
                    userId,
                    request.getTeachingClassIds(),
                    roleAdminService.listRoleCodesByUserId(userId)
                );
                default -> throw new BusinessException("不支持的批量用户操作: " + request.getAction());
            }
        }
    }

    @Transactional
    public void operateTeachingClasses(BulkTeachingClassOperationRequest request) {
        String status = emptyToNull(request.getStatus());
        if (status == null) {
            throw new BusinessException("教学班状态不能为空");
        }
        for (Long classId : normalizeIds(request.getClassIds())) {
            TeachingClass teachingClass = teachingClassMapper.selectById(classId);
            if (teachingClass == null) {
                throw new BusinessException("教学班不存在: " + classId);
            }
            teachingClass.setStatus(status);
            teachingClassMapper.updateById(teachingClass);
        }
    }

    public void operateExams(BulkExamOperationRequest request) {
        String action = request.getAction().trim().toUpperCase(Locale.ROOT);
        for (Long examId : normalizeIds(request.getExamIds())) {
            switch (action) {
                case "PUBLISH" -> examService.publishExam(examId);
                case "TERMINATE" -> examService.terminateExam(examId);
                default -> throw new BusinessException("不支持的批量考试操作: " + request.getAction());
            }
        }
    }

    private void updateUserEnabled(Long userId, boolean enabled) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在: " + userId);
        }
        user.setEnabled(enabled);
        userMapper.updateById(user);
    }

    private Role ensureRole(String roleCode) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new BusinessException("角色不存在: " + roleCode);
        }
        return role;
    }

    private void validateExamSchedule(ExamCreateRequest request) {
        Paper paper = paperMapper.selectById(request.getPaperId());
        if (paper == null) {
            throw new BusinessException("试卷不存在");
        }
        Long paperSubjectId = paper.getSubjectId();
        if (paperSubjectId == null) {
            throw new BusinessException("试卷未绑定课程，无法发布考试");
        }
        List<Long> classIds = request.getTargetClassIds() == null ? List.of() : request.getTargetClassIds();
        if (classIds.isEmpty()) {
            throw new BusinessException("目标教学班不能为空");
        }
        List<TeachingClass> teachingClasses = teachingClassMapper.selectBatchIds(classIds);
        if (teachingClasses.size() != classIds.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        for (TeachingClass teachingClass : teachingClasses) {
            if (!Objects.equals(paperSubjectId, teachingClass.getSubjectId())) {
                throw new BusinessException("目标教学班课程与试卷课程不一致");
            }
        }
    }

    private UserCreateRequest buildUserCreateRequest(AdminCsvImportService.CsvRow row,
                                                     String roleCode,
                                                     Long roleId,
                                                     String username) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setRealName(value(row, "realName"));
        request.setPassword(emptyToNull(value(row, "password")));
        request.setRoleIds(List.of(roleId));
        if ("STUDENT".equals(roleCode)) {
            request.setStudentNo(emptyToNull(value(row, "studentNo")));
            request.setEnrollmentYear(emptyToNull(value(row, "enrollmentYear")));
            request.setTeachingClassIds(parseLongList(value(row, "teachingClassIds")));
        }
        return request;
    }

    private String duplicateUsernameMessage(String roleCode) {
        return "STUDENT".equals(roleCode) ? "学号在导入文件中重复" : "用户名在导入文件中重复";
    }

    private String normalizeRole(String role) {
        String roleCode = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!"STUDENT".equals(roleCode) && !"TEACHER".equals(roleCode)) {
            throw new BusinessException("导入角色仅支持 STUDENT 或 TEACHER");
        }
        return roleCode;
    }

    private void validateRequired(AdminCsvImportService.CsvRow row, String... fields) {
        for (String field : fields) {
            String text = value(row, field);
            if (text == null || text.isBlank()) {
                throw new RowException(field, "字段不能为空", text);
            }
        }
    }

    private Long resolveTeacherId(AdminCsvImportService.CsvRow row) {
        Long teacherId = parseLong(value(row, "teacherId"), "teacherId");
        if (teacherId != null) {
            return teacherId;
        }
        String username = value(row, "teacherUsername");
        if (username == null || username.isBlank()) {
            return null;
        }
        User teacher = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return teacher == null ? null : teacher.getId();
    }

    private String value(AdminCsvImportService.CsvRow row, String field) {
        return row.data().getOrDefault(field, "");
    }

    private Long parseLong(String text, String field) {
        String value = emptyToNull(text);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new RowException(field, "必须是整数", text);
        }
    }

    private Integer parseInteger(String text, String field) {
        String value = emptyToNull(text);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new RowException(field, "必须是整数", text);
        }
    }

    private LocalDateTime parseDateTime(String text, String field) {
        String value = emptyToNull(text);
        if (value == null) {
            throw new RowException(field, "时间不能为空", text);
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                throw new RowException(field, "时间格式应为 yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd HH:mm:ss", text);
            }
        }
    }

    private List<Long> parseLongList(String text) {
        String value = emptyToNull(text);
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split("[,;，；]"))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .map(item -> parseLong(item, "ids"))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .toList();
    }

    private List<Long> normalizeIds(List<Long> ids) {
        List<Long> values = ids == null ? List.of() : ids.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (values.isEmpty()) {
            throw new BusinessException("请选择批量操作对象");
        }
        return values;
    }

    private boolean parseBoolean(String text) {
        String value = emptyToNull(text);
        return value != null && SetOfTrue.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private String emptyToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BulkImportResultView importResult(int total, int success, boolean dryRun, List<BulkImportRowErrorView> errors) {
        return BulkImportResultView.builder()
            .total(total)
            .successCount(success)
            .failureCount(errors.size())
            .dryRun(dryRun)
            .errors(errors)
            .build();
    }

    private BulkImportRowErrorView toError(int rowNumber, String field, String message, String rawValue) {
        return BulkImportRowErrorView.builder()
            .rowNumber(rowNumber)
            .field(field)
            .message(message == null ? "处理失败" : message)
            .rawValue(rawValue)
            .build();
    }

    private static class SetOfTrue {
        private static boolean contains(String value) {
            return "true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value);
        }
    }

    private static class RowException extends RuntimeException {
        private final String field;
        private final String rawValue;

        private RowException(String field, String message, String rawValue) {
            super(message);
            this.field = field;
            this.rawValue = rawValue;
        }
    }
}
