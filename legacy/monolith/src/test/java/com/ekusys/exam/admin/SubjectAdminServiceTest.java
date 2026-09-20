package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubjectAdminServiceTest {

    @Mock
    private SubjectMapper subjectMapper;

    private SubjectAdminService subjectAdminService;

    @BeforeEach
    void setUp() {
        subjectAdminService = new SubjectAdminService(subjectMapper);
    }

    @Test
    void createCourseShouldRejectDuplicateName() {
        Subject existing = new Subject();
        existing.setId(1L);
        when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        CourseCreateRequest request = new CourseCreateRequest();
        request.setName("Java");

        BusinessException ex = assertThrows(BusinessException.class, () -> subjectAdminService.createCourse(request));
        assertEquals("课程已存在", ex.getMessage());
    }

    @Test
    void createCourseShouldRejectDuplicateId() {
        when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        Subject existing = new Subject();
        existing.setId(5001L);
        when(subjectMapper.selectById(5001L)).thenReturn(existing);

        CourseCreateRequest request = new CourseCreateRequest();
        request.setId(5001L);
        request.setName("Java");

        BusinessException ex = assertThrows(BusinessException.class, () -> subjectAdminService.createCourse(request));
        assertEquals("课程ID已存在", ex.getMessage());
    }

    @Test
    void updateCourseShouldRejectDuplicateName() {
        Subject current = new Subject();
        current.setId(5001L);
        when(subjectMapper.selectById(5001L)).thenReturn(current);
        Subject duplicate = new Subject();
        duplicate.setId(5002L);
        when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(duplicate);

        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setName("Spring");

        BusinessException ex = assertThrows(BusinessException.class, () -> subjectAdminService.updateCourse(5001L, request));
        assertEquals("课程名称已存在", ex.getMessage());
    }
}
