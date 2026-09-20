package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.admin.dto.UserUpdateRequest;
import com.ekusys.exam.admin.dto.UserQueryRequest;
import com.ekusys.exam.admin.dto.UserView;
import com.ekusys.exam.admin.service.RoleAdminService;
import com.ekusys.exam.admin.service.UserAdminService;
import com.ekusys.exam.admin.service.UserProfileSyncService;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RoleAdminService roleAdminService;
    @Mock
    private UserProfileSyncService userProfileSyncService;

    private UserAdminService userAdminService;

    @BeforeEach
    void setUp() {
        userAdminService = new UserAdminService(userMapper, passwordEncoder, roleAdminService, userProfileSyncService);
        ReflectionTestUtils.setField(userAdminService, "defaultPassword", "123456");
    }

    @Test
    void createUserShouldRejectDuplicateUsername() {
        User existing = new User();
        existing.setId(1L);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");

        BusinessException ex = assertThrows(BusinessException.class, () -> userAdminService.createUser(request));
        assertEquals("用户名已存在", ex.getMessage());
    }

    @Test
    void createUserShouldUseDefaultPasswordAndSyncStudentProfile() {
        when(passwordEncoder.encode("123456")).thenReturn("encoded");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(roleAdminService.listRoleCodesByUserId(1001L)).thenReturn(List.of("STUDENT"));
        org.mockito.Mockito.doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1001L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");
        request.setRealName("Alice");
        request.setRoleIds(List.of(3L));
        request.setTeachingClassIds(List.of(3301L));
        request.setStudentNo("S001");
        request.setEnrollmentYear("2026");

        Long userId = userAdminService.createUser(request);

        assertEquals(1001L, userId);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("encoded", captor.getValue().getPassword());
        verify(roleAdminService).assignRoles(1001L, List.of(3L), List.of(3301L));
        verify(userProfileSyncService).syncStudentProfile(1001L, "S001", "2026");
    }

    @Test
    void updateUserShouldSyncStudentNoAndTeachingClasses() {
        User user = new User();
        user.setId(1001L);
        user.setRealName("Old");
        user.setEnabled(true);
        when(userMapper.selectById(1001L)).thenReturn(user);
        when(roleAdminService.listRoleCodesByUserId(1001L)).thenReturn(List.of("STUDENT"));

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRealName("New");
        request.setEnabled(false);
        request.setStudentNo("S002");
        request.setEnrollmentYear("2027");
        request.setTeachingClassIds(List.of(3302L));

        userAdminService.updateUser(1001L, request);

        verify(userMapper).updateById(user);
        assertEquals("New", user.getRealName());
        assertEquals(false, user.getEnabled());
        verify(userProfileSyncService).syncStudentProfile(1001L, "S002", "2027");
        verify(userProfileSyncService).updateStudentTeachingClasses(1001L, List.of(3302L), List.of("STUDENT"));
    }

    @Test
    void queryUsersShouldExcludeAdminsAndExposeEnrollmentYear() {
        User user = new User();
        user.setId(1001L);
        user.setUsername("alice");
        user.setRealName("Alice");
        user.setEnabled(true);
        Page<User> page = new Page<>(1, 20);
        page.setRecords(List.of(user));
        page.setTotal(1);
        when(userMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(roleAdminService.buildUserRoleViewMap(List.of(1001L))).thenReturn(Map.of());
        when(userProfileSyncService.buildUserTeachingClassMap(List.of(1001L))).thenReturn(Map.of());
        StudentProfile profile = new StudentProfile();
        profile.setUserId(1001L);
        profile.setStudentNo("S001");
        profile.setEnrollmentYear("2026");
        when(userProfileSyncService.buildStudentProfileMap(List.of(1001L))).thenReturn(Map.of(1001L, profile));

        UserQueryRequest request = new UserQueryRequest();
        request.setPageNum(1);
        request.setPageSize(20);
        request.setKeyword(" Alice ");
        request.setRoleCode("STUDENT");
        PageResponse<UserView> result = userAdminService.queryUsers(request);

        assertEquals(1, result.getTotal());
        assertEquals("S001", result.getRecords().get(0).getStudentNo());
        assertEquals("2026", result.getRecords().get(0).getEnrollmentYear());
        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("sys_role"));
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("ADMIN"));
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("STUDENT"));
    }

    @Test
    void queryUsersShouldExposeTeacherTeachingClassesForTeacherFilter() {
        User user = new User();
        user.setId(2001L);
        user.setUsername("teacher01");
        user.setRealName("Teacher");
        user.setEnabled(true);
        Page<User> page = new Page<>(1, 20);
        page.setRecords(List.of(user));
        page.setTotal(1);
        when(userMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(roleAdminService.buildUserRoleViewMap(List.of(2001L))).thenReturn(Map.of());
        TeachingClassView teachingClass = TeachingClassView.builder()
            .id(3301L)
            .name("数学一班")
            .subjectName("数学")
            .teacherId(2001L)
            .build();
        when(userProfileSyncService.buildTeacherTeachingClassMap(List.of(2001L))).thenReturn(Map.of(2001L, List.of(teachingClass)));
        when(userProfileSyncService.buildStudentProfileMap(List.of(2001L))).thenReturn(Map.of());

        UserQueryRequest request = new UserQueryRequest();
        request.setPageNum(1);
        request.setPageSize(20);
        request.setRoleCode("TEACHER");
        PageResponse<UserView> result = userAdminService.queryUsers(request);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().get(0).getTeachingClasses().size());
        assertEquals(3301L, result.getRecords().get(0).getTeachingClasses().get(0).getId());
        verify(userProfileSyncService).buildTeacherTeachingClassMap(List.of(2001L));
        verify(userProfileSyncService, never()).buildUserTeachingClassMap(any());
    }

    @Test
    void queryUsersShouldRejectUnsupportedRoleFilter() {
        UserQueryRequest request = new UserQueryRequest();
        request.setRoleCode("ADMIN");

        BusinessException ex = assertThrows(BusinessException.class, () -> userAdminService.queryUsers(request));

        assertEquals("不支持的用户角色筛选", ex.getMessage());
    }

    @Test
    void createUserShouldRequirePasswordWhenDefaultPasswordNotConfigured() {
        ReflectionTestUtils.setField(userAdminService, "defaultPassword", "");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("bob");
        request.setRealName("Bob");
        request.setRoleIds(List.of(1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> userAdminService.createUser(request));
        assertEquals("请填写密码或配置 APP_DEFAULT_PASSWORD", ex.getMessage());
    }

    @Test
    void validateCreateUserShouldRejectDuplicateStudentNoForStudentRole() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(roleAdminService.validateRoleAssignment(List.of(3L), List.of(3301L))).thenReturn(List.of("STUDENT"));
        org.mockito.Mockito.doThrow(new BusinessException("学号已存在"))
            .when(userProfileSyncService).validateStudentNoAvailable(null, "S001");

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("S001");
        request.setRealName("Alice");
        request.setRoleIds(List.of(3L));
        request.setTeachingClassIds(List.of(3301L));
        request.setStudentNo("S001");

        BusinessException ex = assertThrows(BusinessException.class, () -> userAdminService.validateCreateUser(request));

        assertEquals("学号已存在", ex.getMessage());
    }

    @Test
    void validateCreateUserShouldNotCheckStudentNoForNonStudentRole() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(roleAdminService.validateRoleAssignment(List.of(2L), null)).thenReturn(List.of("TEACHER"));

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("teacher01");
        request.setRealName("Teacher");
        request.setRoleIds(List.of(2L));
        request.setStudentNo("S001");

        userAdminService.validateCreateUser(request);

        verify(userProfileSyncService, never()).validateStudentNoAvailable(any(), any());
    }
}
