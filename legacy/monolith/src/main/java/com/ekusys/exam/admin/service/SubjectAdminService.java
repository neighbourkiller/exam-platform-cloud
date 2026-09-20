package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.CourseView;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectAdminService {

    private final SubjectMapper subjectMapper;

    public SubjectAdminService(SubjectMapper subjectMapper) {
        this.subjectMapper = subjectMapper;
    }

    public List<CourseView> listCourses() {
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>().orderByDesc(Subject::getCreateTime)).stream()
            .map(subject -> CourseView.builder()
                .id(subject.getId())
                .name(subject.getName())
                .description(subject.getDescription())
                .build())
            .toList();
    }

    @Transactional
    public Long createCourse(CourseCreateRequest request) {
        validateCreateCourse(request);

        Subject subject = new Subject();
        subject.setId(request.getId());
        subject.setName(normalizeCourseName(request.getName()));
        subject.setDescription(request.getDescription());
        subjectMapper.insert(subject);
        return subject.getId();
    }

    @Transactional
    public void updateCourse(Long courseId, CourseUpdateRequest request) {
        validateUpdateCourse(courseId, request);

        Subject subject = subjectMapper.selectById(courseId);
        subject.setName(normalizeCourseName(request.getName()));
        subject.setDescription(request.getDescription());
        subjectMapper.updateById(subject);
    }

    public void validateCreateCourse(CourseCreateRequest request) {
        String courseName = request.getName() == null ? "" : request.getName().trim();
        Subject existingByName = subjectMapper.selectOne(new LambdaQueryWrapper<Subject>()
            .eq(Subject::getName, courseName)
            .last("limit 1"));
        if (existingByName != null) {
            throw new BusinessException("课程已存在");
        }

        if (request.getId() != null) {
            Subject existingById = subjectMapper.selectById(request.getId());
            if (existingById != null) {
                throw new BusinessException("课程ID已存在");
            }
        }
    }

    public void validateUpdateCourse(Long courseId, CourseUpdateRequest request) {
        Subject subject = subjectMapper.selectById(courseId);
        if (subject == null) {
            throw new BusinessException("课程不存在");
        }

        String courseName = request.getName() == null ? "" : request.getName().trim();
        Subject existingByName = subjectMapper.selectOne(new LambdaQueryWrapper<Subject>()
            .eq(Subject::getName, courseName)
            .ne(Subject::getId, courseId)
            .last("limit 1"));
        if (existingByName != null) {
            throw new BusinessException("课程名称已存在");
        }
    }

    private String normalizeCourseName(String name) {
        return name == null ? "" : name.trim();
    }
}
