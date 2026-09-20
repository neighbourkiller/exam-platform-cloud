package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.admin.dto.RoleView;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.entity.UserRole;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import com.ekusys.exam.repository.mapper.UserRoleMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleAdminService {

    private static final String ROLE_STUDENT = "STUDENT";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserProfileSyncService userProfileSyncService;

    public RoleAdminService(UserMapper userMapper,
                            RoleMapper roleMapper,
                            UserRoleMapper userRoleMapper,
                            UserProfileSyncService userProfileSyncService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.userProfileSyncService = userProfileSyncService;
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        assignRoles(userId, roleIds, null);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds, List<Long> teachingClassIds) {
        ensureUser(userId);
        List<Long> safeRoleIds = normalizeRoleIds(roleIds);
        List<String> roleCodes = validateRoleAssignment(roleIds, teachingClassIds);

        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        for (Long roleId : safeRoleIds) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }

        userProfileSyncService.syncProfilesByRoleCodes(userId, roleCodes);
        if (teachingClassIds != null || !roleCodes.contains(ROLE_STUDENT)) {
            userProfileSyncService.updateStudentTeachingClasses(userId, teachingClassIds, roleCodes);
        }
    }

    public List<String> validateRoleAssignment(List<Long> roleIds, List<Long> teachingClassIds) {
        List<Long> safeRoleIds = normalizeRoleIds(roleIds);
        List<String> roleCodes = safeRoleIds.stream()
            .map(roleId -> {
                Role role = roleMapper.selectById(roleId);
                if (role == null) {
                    throw new BusinessException("角色不存在: " + roleId);
                }
                return role.getCode();
            })
            .toList();
        userProfileSyncService.validateStudentTeachingClasses(teachingClassIds, roleCodes);
        return roleCodes;
    }

    @Transactional
    public void deleteUserRoles(Long userId) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
    }

    public List<RoleView> listRoles() {
        return roleMapper.selectList(null).stream()
            .map(role -> RoleView.builder().id(role.getId()).code(role.getCode()).name(role.getName()).build())
            .toList();
    }

    public Long createRole(RoleCreateRequest request) {
        Role role = new Role();
        role.setCode(request.getCode());
        role.setName(request.getName());
        roleMapper.insert(role);
        return role.getId();
    }

    public List<String> listRoleCodesByUserId(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
            .stream()
            .map(UserRole::getRoleId)
            .map(roleMapper::selectById)
            .filter(Objects::nonNull)
            .map(Role::getCode)
            .toList();
    }

    public Map<Long, List<RoleView>> buildUserRoleViewMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Role> roles = roleMapper.selectList(null);
        if (roles.isEmpty()) {
            return Map.of();
        }
        Map<Long, Role> roleMap = roles.stream().collect(Collectors.toMap(Role::getId, item -> item, (a, b) -> a));
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds))
            .stream()
            .collect(Collectors.groupingBy(
                UserRole::getUserId,
                Collectors.mapping(UserRole::getRoleId, Collectors.toList())
            ))
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().stream()
                    .map(roleMap::get)
                    .filter(Objects::nonNull)
                    .map(role -> RoleView.builder().id(role.getId()).code(role.getCode()).name(role.getName()).build())
                    .toList(),
                (a, b) -> a
            ));
    }

    private User ensureUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private List<Long> normalizeRoleIds(List<Long> roleIds) {
        return roleIds == null ? List.of() : roleIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }
}
