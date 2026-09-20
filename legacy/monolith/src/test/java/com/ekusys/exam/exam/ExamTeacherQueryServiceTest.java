package com.ekusys.exam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.exam.service.ExamAccessService;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.exam.service.ExamProctoringPolicyService;
import com.ekusys.exam.exam.service.ExamStatusService;
import com.ekusys.exam.exam.service.ExamTeacherQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExamTeacherQueryServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private TeachingClassMapper teachingClassMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ExamTargetClassMapper examTargetClassMapper;

    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;

    private ExamTeacherQueryService examTeacherQueryService;

    @BeforeEach
    void setUp() {
        ExamAccessService examAccessService = new ExamAccessService(
            examMapper,
            examTargetClassMapper,
            studentTeachingClassMapper,
            userMapper
        );
        ExamPermissionService examPermissionService = new ExamPermissionService(
            userMapper,
            examTargetClassMapper,
            teachingClassMapper
        );
        examTeacherQueryService = new ExamTeacherQueryService(
            examMapper,
            teachingClassMapper,
            subjectMapper,
            userMapper,
            examAccessService,
            new ExamStatusService(examMapper),
            examPermissionService,
            new ExamProctoringPolicyService(new ObjectMapper())
        );
    }

    @Test
    void listTeacherExamsShouldOnlyReturnManageableExams() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            exam(1L, 200L),
            exam(2L, 999L),
            exam(3L, 999L)
        ));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            targetClass(2L, 11L),
            targetClass(3L, 12L)
        ));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(
            teachingClass(11L, 200L),
            teachingClass(12L, 300L)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            List<TeacherExamView> exams = examTeacherQueryService.listTeacherExams();

            assertEquals(2, exams.size());
            assertEquals(List.of(1L, 2L), exams.stream().map(TeacherExamView::getExamId).toList());
        }
    }

    private Exam exam(Long id, Long publisherId) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setName("exam-" + id);
        exam.setPublisherId(publisherId);
        exam.setStatus("PUBLISHED");
        exam.setStartTime(LocalDateTime.now().plusMinutes(10));
        exam.setEndTime(LocalDateTime.now().plusMinutes(40));
        return exam;
    }

    private ExamTargetClass targetClass(Long examId, Long classId) {
        ExamTargetClass item = new ExamTargetClass();
        item.setExamId(examId);
        item.setClassId(classId);
        return item;
    }

    private TeachingClass teachingClass(Long id, Long teacherId) {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(id);
        teachingClass.setTeacherId(teacherId);
        return teachingClass;
    }
}
