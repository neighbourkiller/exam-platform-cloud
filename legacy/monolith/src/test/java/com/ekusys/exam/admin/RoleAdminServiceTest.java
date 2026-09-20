package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.admin.service.RoleAdminService;
import com.ekusys.exam.admin.service.UserProfileSyncService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.entity.UserRole;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import com.ekusys.exam.repository.mapper.UserRoleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleAdminServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private UserProfileSyncService userProfileSyncService;

    private RoleAdminService roleAdminService;

    @BeforeEach
    void setUp() {
        roleAdminService = new RoleAdminService(userMapper, roleMapper, userRoleMapper, userProfileSyncService);
    }

    @Test
    void assignRolesShouldRejectMissingRole() {
        User user = new User();
        user.setId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(user);
        when(roleMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> roleAdminService.assignRoles(1001L, List.of(99L)));
        assertEquals("角色不存在: 99", ex.getMessage());
    }

    @Test
    void assignRolesShouldSyncProfilesAndClearTeachingClassForNonStudent() {
        User user = new User();
        user.setId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(user);
        Role teacherRole = new Role();
        teacherRole.setId(2L);
        teacherRole.setCode("TEACHER");
        when(roleMapper.selectById(2L)).thenReturn(teacherRole);

        roleAdminService.assignRoles(1001L, List.of(2L));

        verify(userRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(userRoleMapper).insert(any(UserRole.class));
        verify(userProfileSyncService).syncProfilesByRoleCodes(1001L, List.of("TEACHER"));
        verify(userProfileSyncService).updateStudentTeachingClasses(1001L, null, List.of("TEACHER"));
    }

    @Test
    void createRoleShouldPersistRole() {
        RoleCreateRequest request = new RoleCreateRequest();
        request.setCode("MONITOR");
        request.setName("监考员");
        org.mockito.Mockito.doAnswer(invocation -> {
            Role role = invocation.getArgument(0);
            role.setId(10L);
            return 1;
        }).when(roleMapper).insert(any(Role.class));

        Long id = roleAdminService.createRole(request);

        assertEquals(10L, id);
    }
}
