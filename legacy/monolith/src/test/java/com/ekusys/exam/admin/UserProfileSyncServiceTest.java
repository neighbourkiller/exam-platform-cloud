package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.admin.service.UserProfileSyncService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.TeacherProfileMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserProfileSyncServiceTest {

    @Mock
    private TeachingClassMapper teachingClassMapper;
    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;
    @Mock
    private StudentProfileMapper studentProfileMapper;
    @Mock
    private TeacherProfileMapper teacherProfileMapper;
    @Mock
    private TeachingClassAdminService teachingClassAdminService;

    private UserProfileSyncService userProfileSyncService;

    @BeforeEach
    void setUp() {
        userProfileSyncService = new UserProfileSyncService(
            teachingClassMapper,
            studentTeachingClassMapper,
            studentProfileMapper,
            teacherProfileMapper,
            teachingClassAdminService
        );
    }

    @Test
    void buildTeacherTeachingClassMapShouldGroupClassesByTeacherId() {
        TeachingClass first = new TeachingClass();
        first.setId(3301L);
        first.setTeacherId(2001L);
        TeachingClass second = new TeachingClass();
        second.setId(3302L);
        second.setTeacherId(2001L);
        List<TeachingClass> classes = List.of(first, second);
        when(teachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(classes);
        TeachingClassView firstView = TeachingClassView.builder()
            .id(3301L)
            .teacherId(2001L)
            .name("数学一班")
            .build();
        TeachingClassView secondView = TeachingClassView.builder()
            .id(3302L)
            .teacherId(2001L)
            .name("数学二班")
            .build();
        when(teachingClassAdminService.toTeachingClassViews(classes)).thenReturn(List.of(firstView, secondView));

        Map<Long, List<TeachingClassView>> result = userProfileSyncService.buildTeacherTeachingClassMap(List.of(2001L));

        assertEquals(1, result.size());
        assertEquals(List.of(firstView, secondView), result.get(2001L));
    }

    @Test
    void syncProfilesByRoleCodesShouldCreateStudentAndTeacherProfiles() {
        when(studentProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(teacherProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        userProfileSyncService.syncProfilesByRoleCodes(1001L, List.of("STUDENT", "TEACHER"));

        verify(studentProfileMapper).insert(org.mockito.ArgumentMatchers.<com.ekusys.exam.repository.entity.StudentProfile>any());
        verify(teacherProfileMapper).insert(org.mockito.ArgumentMatchers.<com.ekusys.exam.repository.entity.TeacherProfile>any());
    }

    @Test
    void updateStudentTeachingClassesShouldRejectDuplicateSubjectAssignment() {
        TeachingClass first = new TeachingClass();
        first.setId(3301L);
        first.setSubjectId(5001L);
        TeachingClass second = new TeachingClass();
        second.setId(3302L);
        second.setSubjectId(5001L);
        when(teachingClassMapper.selectBatchIds(List.of(3301L, 3302L))).thenReturn(List.of(first, second));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userProfileSyncService.updateStudentTeachingClasses(1001L, List.of(3301L, 3302L), List.of("STUDENT")));
        assertEquals("同一课程仅可分配一个教学班", ex.getMessage());
    }

    @Test
    void updateStudentTeachingClassesShouldRejectNonStudentAssignment() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> userProfileSyncService.updateStudentTeachingClasses(1001L, List.of(3301L), List.of("TEACHER")));
        assertEquals("仅学生角色可分配教学班", ex.getMessage());
    }

    @Test
    void syncStudentNoShouldUpdateExistingProfile() {
        StudentProfile profile = new StudentProfile();
        profile.setId(1L);
        profile.setUserId(1001L);
        when(studentProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        userProfileSyncService.syncStudentNo(1001L, "S002");

        assertEquals("S002", profile.getStudentNo());
        verify(studentProfileMapper).updateById(profile);
    }

    @Test
    void syncStudentProfileShouldRejectDuplicateStudentNoOnCreate() {
        StudentProfile existing = new StudentProfile();
        existing.setId(1L);
        existing.setUserId(2002L);
        existing.setStudentNo("S001");
        when(studentProfileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userProfileSyncService.syncStudentProfile(1001L, "S001", "2026"));

        assertEquals("学号已存在", ex.getMessage());
        verify(studentProfileMapper, never()).insert(any(StudentProfile.class));
    }

    @Test
    void syncStudentProfileShouldRejectDuplicateStudentNoOnUpdate() {
        StudentProfile existing = new StudentProfile();
        existing.setId(1L);
        existing.setUserId(2002L);
        existing.setStudentNo("S001");
        when(studentProfileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userProfileSyncService.syncStudentProfile(1001L, "S001", "2026"));

        assertEquals("学号已存在", ex.getMessage());
        verify(studentProfileMapper, never()).updateById(any(StudentProfile.class));
    }

    @Test
    void syncStudentProfileShouldAllowCurrentUserStudentNo() {
        StudentProfile profile = new StudentProfile();
        profile.setId(1L);
        profile.setUserId(1001L);
        profile.setStudentNo("S001");
        when(studentProfileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(profile));
        when(studentProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        userProfileSyncService.syncStudentProfile(1001L, "S001", "2026");

        assertEquals("S001", profile.getStudentNo());
        verify(studentProfileMapper).updateById(profile);
    }

    @Test
    void syncStudentProfileShouldUpdateStudentNoAndEnrollmentYear() {
        StudentProfile profile = new StudentProfile();
        profile.setId(1L);
        profile.setUserId(1001L);
        when(studentProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        userProfileSyncService.syncStudentProfile(1001L, " S003 ", " 2026 ");

        assertEquals("S003", profile.getStudentNo());
        assertEquals("2026", profile.getEnrollmentYear());
        verify(studentProfileMapper).updateById(profile);
    }

    @Test
    void syncStudentProfileShouldNormalizeBlankValues() {
        StudentProfile profile = new StudentProfile();
        profile.setId(1L);
        profile.setUserId(1001L);
        profile.setStudentNo("S001");
        profile.setEnrollmentYear("2026");
        when(studentProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        userProfileSyncService.syncStudentProfile(1001L, " ", "");

        assertNull(profile.getStudentNo());
        assertNull(profile.getEnrollmentYear());
        verify(studentProfileMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(studentProfileMapper).updateById(profile);
    }
}
