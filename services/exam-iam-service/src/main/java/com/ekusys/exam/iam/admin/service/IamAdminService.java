package com.ekusys.exam.iam.admin.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.academic.api.AcademicUserProfileCommand;
import com.ekusys.exam.academic.api.AcademicUserSummary;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.iam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.iam.admin.dto.BulkUserOperationRequest;
import com.ekusys.exam.iam.admin.dto.RoleView;
import com.ekusys.exam.iam.admin.dto.TeachingClassView;
import com.ekusys.exam.iam.admin.dto.UserCreateRequest;
import com.ekusys.exam.iam.admin.dto.UserQueryRequest;
import com.ekusys.exam.iam.admin.dto.UserUpdateRequest;
import com.ekusys.exam.iam.admin.dto.UserView;
import com.ekusys.exam.iam.client.AcademicProfileClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamAdminService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AcademicProfileClient academic;

    @Value("${app.security.default-password:}")
    private String defaultPassword;

    public IamAdminService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AcademicProfileClient academic) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.academic = academic;
    }

    public PageResponse<UserView> queryUsers(UserQueryRequest request) {
        long pageNum = Math.max(1, request.getPageNum());
        long pageSize = Math.max(1, Math.min(100, request.getPageSize()));
        StringBuilder where = new StringBuilder("""
             where not exists (
               select 1 from sys_user_role aur join sys_role ar on ar.id=aur.role_id
                where aur.user_id=u.id and ar.code='ADMIN'
             )
            """);
        List<Object> parameters = new ArrayList<>();
        String roleCode = normalizeRole(request.getRoleCode());
        if (roleCode != null) {
            where.append(" and exists (select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=u.id and r.code=?)");
            parameters.add(roleCode);
        }
        String keyword = normalize(request.getKeyword());
        if (keyword != null) {
            where.append(" and (u.username like ? or u.real_name like ?)");
            parameters.add("%" + keyword + "%");
            parameters.add("%" + keyword + "%");
        }
        Long total = jdbc.queryForObject("select count(*) from sys_user u" + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageSize);
        pageParameters.add((pageNum - 1) * pageSize);
        List<UserRow> rows = jdbc.query(
            "select u.id,u.username,u.real_name,u.enabled from sys_user u" + where + " order by u.id desc limit ? offset ?",
            (rs, rowNum) -> new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("real_name"), rs.getBoolean("enabled")),
            pageParameters.toArray()
        );
        List<Long> ids = rows.stream().map(UserRow::id).toList();
        Map<Long, AcademicUserSummary> academicMap = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            List<AcademicUserSummary> summaries = academic.summaries(ids).getData();
            if (summaries != null) summaries.forEach(item -> academicMap.put(item.userId(), item));
        }
        List<UserView> records = rows.stream().map(row -> toView(row, academicMap.get(row.id()))).toList();
        return PageResponse.<UserView>builder().pageNum(pageNum).pageSize(pageSize)
            .total(total == null ? 0 : total).records(records).build();
    }

    @Transactional
    public Long createUser(UserCreateRequest request) {
        ensureUsernameAvailable(request.getUsername());
        List<String> roleCodes = validateRoles(request.getRoleIds());
        long userId = IdWorker.getId();
        jdbc.update(
            "insert into sys_user(id,username,password,real_name,enabled,token_version,create_time,update_time) values(?,?,?,?,1,0,current_timestamp(3),current_timestamp(3))",
            userId, request.getUsername().trim(), passwordEncoder.encode(resolvePassword(request.getPassword())), request.getRealName().trim()
        );
        replaceRoles(userId, request.getRoleIds());
        academic.synchronize(userId, new AcademicUserProfileCommand(roleCodes, request.getStudentNo(),
            request.getEnrollmentYear(), request.getTeachingClassIds(), request.getTeacherNo(), request.getTitle()));
        return userId;
    }

    public void validateCreateUser(UserCreateRequest request) {
        ensureUsernameAvailable(request.getUsername());
        resolvePassword(request.getPassword());
        List<String> roleCodes = validateRoles(request.getRoleIds());
        academic.validate(new AcademicUserProfileCommand(roleCodes, request.getStudentNo(),
            request.getEnrollmentYear(), request.getTeachingClassIds(), request.getTeacherNo(), request.getTitle()));
    }

    @Transactional
    public void updateUser(Long userId, UserUpdateRequest request) {
        ensureUser(userId);
        int enabled = request.getEnabled() == null || request.getEnabled() ? 1 : 0;
        jdbc.update("update sys_user set real_name=?,enabled=?,token_version=token_version+1,update_time=current_timestamp(3) where id=?",
            request.getRealName().trim(), enabled, userId);
        academic.synchronize(userId, new AcademicUserProfileCommand(roleCodes(userId), request.getStudentNo(),
            request.getEnrollmentYear(), request.getTeachingClassIds(), null, null));
    }

    @Transactional
    public void deleteUser(Long userId) {
        ensureUser(userId);
        academic.delete(userId);
        jdbc.update("delete from sys_user_role where user_id=?", userId);
        jdbc.update("delete from sys_user where id=?", userId);
    }

    @Transactional
    public void resetPassword(Long userId, String password) {
        ensureUser(userId);
        jdbc.update("update sys_user set password=?,token_version=token_version+1,update_time=current_timestamp(3) where id=?",
            passwordEncoder.encode(password), userId);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        ensureUser(userId);
        List<String> roleCodes = validateRoles(roleIds);
        replaceRoles(userId, roleIds);
        jdbc.update("update sys_user set token_version=token_version+1,update_time=current_timestamp(3) where id=?", userId);
        academic.synchronize(userId, new AcademicUserProfileCommand(roleCodes, null, null, null, null, null));
    }

    public List<RoleView> listRoles() {
        return jdbc.query("select id,code,name from sys_role order by id",
            (rs, rowNum) -> new RoleView(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
    }

    public Long createRole(RoleCreateRequest request) {
        Integer count = jdbc.queryForObject("select count(*) from sys_role where code=?", Integer.class, request.code().trim());
        if (count != null && count > 0) throw new BusinessException("角色编码已存在");
        long id = IdWorker.getId();
        jdbc.update("insert into sys_role(id,code,name,create_time,update_time) values(?,?,?,current_timestamp(3),current_timestamp(3))",
            id, request.code().trim().toUpperCase(), request.name().trim());
        return id;
    }

    @Transactional
    public void operateUsers(BulkUserOperationRequest request) {
        List<Long> ids = request.userIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        String action = request.action().trim().toUpperCase();
        for (Long id : ids) {
            switch (action) {
                case "ENABLE" -> setEnabled(id, true);
                case "DISABLE" -> setEnabled(id, false);
                case "RESET_PASSWORD" -> {
                    if (request.password() == null || request.password().isBlank()) throw new BusinessException("密码不能为空");
                    resetPassword(id, request.password());
                }
                case "ASSIGN_ROLES" -> {
                    assignRoles(id, request.roleIds());
                    if (request.teachingClassIds() != null) {
                        academic.synchronize(id, new AcademicUserProfileCommand(
                            roleCodes(id), null, null, request.teachingClassIds(), null, null));
                    }
                }
                case "ASSIGN_CLASSES" -> academic.synchronize(id,
                    new AcademicUserProfileCommand(roleCodes(id), null, null, request.teachingClassIds(), null, null));
                default -> throw new BusinessException("不支持的批量用户操作: " + request.action());
            }
        }
    }

    private void setEnabled(Long userId, boolean enabled) {
        ensureUser(userId);
        jdbc.update("update sys_user set enabled=?,token_version=token_version+1,update_time=current_timestamp(3) where id=?",
            enabled ? 1 : 0, userId);
    }

    private UserView toView(UserRow row, AcademicUserSummary academicSummary) {
        List<TeachingClassView> classes = academicSummary == null ? List.of()
            : academicSummary.teachingClasses().stream().map(this::toClassView).toList();
        return new UserView(row.id(), row.username(), row.realName(), row.enabled(),
            academicSummary == null ? null : academicSummary.studentNo(),
            academicSummary == null ? null : academicSummary.enrollmentYear(), classes, roles(row.id()));
    }

    private TeachingClassView toClassView(TeachingClassSummary item) {
        return new TeachingClassView(item.id(), item.name(), item.subjectId(), item.subjectName(), item.teacherId(),
            null, item.term(), item.status(), item.capacity());
    }

    private List<RoleView> roles(Long userId) {
        return jdbc.query("select r.id,r.code,r.name from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=? order by r.id",
            (rs, rowNum) -> new RoleView(rs.getLong("id"), rs.getString("code"), rs.getString("name")), userId);
    }

    private List<String> roleCodes(Long userId) {
        return jdbc.queryForList("select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?", String.class, userId);
    }

    private List<String> validateRoles(List<Long> roleIds) {
        List<Long> ids = roleIds == null ? List.of() : roleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) throw new BusinessException("至少选择一个角色");
        List<String> codes = ids.stream().map(id -> {
            List<String> values = jdbc.queryForList("select code from sys_role where id=?", String.class, id);
            if (values.isEmpty()) throw new BusinessException("角色不存在: " + id);
            return values.getFirst();
        }).toList();
        if (codes.contains("ADMIN")) throw new BusinessException("不能通过该接口分配管理员角色");
        return codes;
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        List<Long> ids = roleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        jdbc.update("delete from sys_user_role where user_id=?", userId);
        for (Long roleId : ids) {
            jdbc.update("insert into sys_user_role(id,user_id,role_id,create_time,update_time) values(?,?,?,current_timestamp(3),current_timestamp(3))",
                IdWorker.getId(), userId, roleId);
        }
    }

    private void ensureUsernameAvailable(String username) {
        Integer count = jdbc.queryForObject("select count(*) from sys_user where username=?", Integer.class, username.trim());
        if (count != null && count > 0) throw new BusinessException("用户名已存在");
    }

    private void ensureUser(Long userId) {
        Integer count = jdbc.queryForObject("select count(*) from sys_user where id=?", Integer.class, userId);
        if (count == null || count == 0) throw new BusinessException("用户不存在");
    }

    private String resolvePassword(String password) {
        String resolved = normalize(password);
        if (resolved == null) resolved = normalize(defaultPassword);
        if (resolved == null) throw new BusinessException("请填写密码或配置 APP_DEFAULT_PASSWORD");
        return resolved;
    }

    private String normalizeRole(String roleCode) {
        String normalized = normalize(roleCode);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase();
        if (!List.of("STUDENT", "TEACHER").contains(normalized)) throw new BusinessException("不支持的用户角色筛选");
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record UserRow(Long id, String username, String realName, boolean enabled) {
    }
}
