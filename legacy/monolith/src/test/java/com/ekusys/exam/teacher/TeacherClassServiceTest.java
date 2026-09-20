package com.ekusys.exam.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.entity.UserRole;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import com.ekusys.exam.repository.mapper.UserRoleMapper;
import com.ekusys.exam.teacher.service.TeacherClassService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class TeacherClassServiceTest {

    @Mock
    private TeachingClassMapper teachingClassMapper;
    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private StudentProfileMapper studentProfileMapper;
    @Mock
    private SubjectMapper subjectMapper;

    private TeacherClassService teacherClassService;

    @BeforeEach
    void setUp() {
        teacherClassService = new TeacherClassService(
            teachingClassMapper,
            studentTeachingClassMapper,
            userMapper,
            userRoleMapper,
            roleMapper,
            studentProfileMapper,
            subjectMapper
        );

        LoginUser teacher = LoginUser.builder()
            .userId(1002L)
            .username("teacher1")
            .password("x")
            .enabled(true)
            .roles(List.of("TEACHER"))
            .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(teacher, null, teacher.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void addStudentsShouldRejectWhenUserNotStudentRole() {
        TeachingClass teachingClass = buildClass(3301L, 1002L, 5001L);
        when(teachingClassMapper.selectById(3301L)).thenReturn(teachingClass);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildStudentRole());
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of()); // no student users
        when(userMapper.selectById(2001L)).thenReturn(buildUser(2001L, "u1", "U1"));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> teacherClassService.addStudents(3301L, List.of(2001L)));
        assertEquals("用户不是学生: 2001", ex.getMessage());
    }

    @Test
    void addStudentsShouldRejectWhenOccupiedBySameSubjectOtherClass() {
        TeachingClass teachingClass = buildClass(3301L, 1002L, 5001L);
        when(teachingClassMapper.selectById(3301L)).thenReturn(teachingClass);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildStudentRole());
        UserRole userRole = new UserRole();
        userRole.setUserId(1003L);
        userRole.setRoleId(3L);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(userRole));
        when(userMapper.selectById(1003L)).thenReturn(buildUser(1003L, "student1", "学生A"));

        when(studentTeachingClassMapper.selectCount(any(LambdaQueryWrapper.class)))
            .thenReturn(0L) // current class
            .thenReturn(1L); // same subject other class

        BusinessException ex = assertThrows(BusinessException.class,
            () -> teacherClassService.addStudents(3301L, List.of(1003L)));
        assertEquals("学生已在同课程其他教学班: 1003", ex.getMessage());
    }

    @Test
    void addStudentsShouldInsertWhenValid() {
        TeachingClass teachingClass = buildClass(3301L, 1002L, 5001L);
        when(teachingClassMapper.selectById(3301L)).thenReturn(teachingClass);
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildStudentRole());
        UserRole userRole = new UserRole();
        userRole.setUserId(1003L);
        userRole.setRoleId(3L);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(userRole));
        when(userMapper.selectById(1003L)).thenReturn(buildUser(1003L, "student1", "学生A"));
        when(studentTeachingClassMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L).thenReturn(0L);

        teacherClassService.addStudents(3301L, List.of(1003L));

        verify(studentTeachingClassMapper, times(1)).insert(any(StudentTeachingClass.class));
    }

    @Test
    void removeStudentShouldRejectWhenNotOwnClass() {
        TeachingClass teachingClass = buildClass(3301L, 9999L, 5001L);
        when(teachingClassMapper.selectById(3301L)).thenReturn(teachingClass);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> teacherClassService.removeStudent(3301L, 1003L));
        assertEquals("无权限管理该教学班", ex.getMessage());
    }

    private TeachingClass buildClass(Long id, Long teacherId, Long subjectId) {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(id);
        teachingClass.setTeacherId(teacherId);
        teachingClass.setSubjectId(subjectId);
        teachingClass.setName("班级A");
        return teachingClass;
    }

    private Role buildStudentRole() {
        Role role = new Role();
        role.setId(3L);
        role.setCode("STUDENT");
        role.setName("学生");
        return role;
    }

    private User buildUser(Long id, String username, String realName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        return user;
    }
}

