package com.ekusys.exam.academic.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.academic.client.IamUserClient;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.TeachingClassOptionView;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamTeachingClassController {
    private final TeachingClassMapper classMapper;
    private final SubjectMapper subjectMapper;
    private final IamUserClient iam;

    public ExamTeachingClassController(TeachingClassMapper classMapper, SubjectMapper subjectMapper, IamUserClient iam) {
        this.classMapper = classMapper;
        this.subjectMapper = subjectMapper;
        this.iam = iam;
    }

    @GetMapping("/teaching-classes")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<TeachingClassOptionView>> teachingClasses() {
        LambdaQueryWrapper<TeachingClass> query = new LambdaQueryWrapper<TeachingClass>()
            .orderByAsc(TeachingClass::getSubjectId, TeachingClass::getTerm, TeachingClass::getName, TeachingClass::getId);
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")) {
            query.eq(TeachingClass::getTeacherId, SecurityUtils.getCurrentUserId());
        }
        List<TeachingClass> classes = classMapper.selectList(query);
        Set<Long> subjectIds = classes.stream().map(TeachingClass::getSubjectId).collect(Collectors.toSet());
        Map<Long, Subject> subjects = subjectIds.isEmpty() ? Map.of()
            : subjectMapper.selectBatchIds(subjectIds).stream().collect(Collectors.toMap(Subject::getId, Function.identity()));
        List<Long> teacherIds = classes.stream().map(TeachingClass::getTeacherId).distinct().toList();
        List<UserSummary> teacherValues = teacherIds.isEmpty() ? List.of() : iam.batch(new UserBatchRequest(teacherIds)).getData();
        Map<Long, UserSummary> teachers = teacherValues == null ? Map.of()
            : teacherValues.stream().collect(Collectors.toMap(UserSummary::id, Function.identity(), (left, right) -> left));
        return ApiResponse.ok(classes.stream().map(item -> TeachingClassOptionView.builder()
            .id(item.getId()).name(item.getName()).subjectId(item.getSubjectId())
            .subjectName(subjects.get(item.getSubjectId()) == null ? null : subjects.get(item.getSubjectId()).getName())
            .teacherId(item.getTeacherId())
            .teacherName(teachers.get(item.getTeacherId()) == null ? null : teachers.get(item.getTeacherId()).realName())
            .term(item.getTerm()).status(item.getStatus()).build()).toList());
    }
}
