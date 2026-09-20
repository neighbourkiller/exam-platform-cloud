package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeachingClassAdminServiceTest {

    @Mock
    private TeachingClassMapper teachingClassMapper;
    @Mock
    private SubjectMapper subjectMapper;
    @Mock
    private UserMapper userMapper;

    private TeachingClassAdminService teachingClassAdminService;

    @BeforeEach
    void setUp() {
        teachingClassAdminService = new TeachingClassAdminService(teachingClassMapper, subjectMapper, userMapper);
    }

    @Test
    void createTeachingClassShouldRejectNonTeacherUser() {
        Subject subject = new Subject();
        subject.setId(5001L);
        User user = new User();
        user.setId(1002L);
        when(subjectMapper.selectById(5001L)).thenReturn(subject);
        when(userMapper.selectById(1002L)).thenReturn(user);
        when(userMapper.selectRoleCodes(1002L)).thenReturn(List.of("ADMIN"));

        TeachingClassCreateRequest request = new TeachingClassCreateRequest();
        request.setSubjectId(5001L);
        request.setTeacherId(1002L);
        request.setName("Java-1班");
        request.setTerm("2026-春");

        BusinessException ex = assertThrows(BusinessException.class, () -> teachingClassAdminService.createTeachingClass(request));
        assertEquals("指定用户不是教师角色", ex.getMessage());
    }

    @Test
    void listTeachingClassesShouldAssembleSubjectAndTeacherName() {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(3301L);
        teachingClass.setName("Java-1班");
        teachingClass.setSubjectId(5001L);
        teachingClass.setTeacherId(1002L);
        teachingClass.setTerm("2026-春");
        teachingClass.setStatus("ONGOING");
        when(teachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(teachingClass));

        Subject subject = new Subject();
        subject.setId(5001L);
        subject.setName("Java程序设计");
        when(subjectMapper.selectBatchIds(any())).thenReturn(List.of(subject));

        User teacher = new User();
        teacher.setId(1002L);
        teacher.setRealName("教师A");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(teacher));

        List<TeachingClassView> views = teachingClassAdminService.listTeachingClasses();

        assertEquals(1, views.size());
        assertEquals("Java程序设计", views.get(0).getSubjectName());
        assertEquals("教师A", views.get(0).getTeacherName());
    }
}
