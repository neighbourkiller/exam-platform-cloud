package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.exam.dto.TeachingClassOptionView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExamTeacherQueryService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final ExamMapper examMapper;
    private final TeachingClassMapper teachingClassMapper;
    private final SubjectMapper subjectMapper;
    private final UserMapper userMapper;
    private final ExamAccessService examAccessService;
    private final ExamStatusService examStatusService;
    private final ExamPermissionService examPermissionService;
    private final ExamProctoringPolicyService proctoringPolicyService;

    public ExamTeacherQueryService(ExamMapper examMapper,
                                   TeachingClassMapper teachingClassMapper,
                                   SubjectMapper subjectMapper,
                                   UserMapper userMapper,
                                   ExamAccessService examAccessService,
                                   ExamStatusService examStatusService,
                                   ExamPermissionService examPermissionService,
                                   ExamProctoringPolicyService proctoringPolicyService) {
        this.examMapper = examMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.subjectMapper = subjectMapper;
        this.userMapper = userMapper;
        this.examAccessService = examAccessService;
        this.examStatusService = examStatusService;
        this.examPermissionService = examPermissionService;
        this.proctoringPolicyService = proctoringPolicyService;
    }

    public List<TeacherExamView> listTeacherExams() {
        List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>().orderByDesc(Exam::getCreateTime));
        exams = examPermissionService.filterManageableExams(exams);
        LocalDateTime now = LocalDateTime.now();
        exams.forEach(item -> examStatusService.refreshExamStatusByTime(item, now));
        return exams.stream().map(exam -> {
            var proctoringPolicy = proctoringPolicyService.resolve(exam.getProctoringLevel(), exam.getProctoringConfigJson());
            return TeacherExamView.builder()
                .examId(exam.getId())
                .name(exam.getName())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .durationMinutes(exam.getDurationMinutes())
                .passScore(exam.getPassScore())
                .status(exam.getStatus())
                .proctoringLevel(proctoringPolicy.getLevel())
                .proctoringPolicy(proctoringPolicy)
                .build();
        }).toList();
    }

    public List<TeachingClassOptionView> listTeachingClasses() {
        Long userId = examAccessService.getCurrentUserId();
        Set<String> roleCodes = new HashSet<>(userMapper.selectRoleCodes(userId));

        LambdaQueryWrapper<TeachingClass> wrapper = new LambdaQueryWrapper<TeachingClass>()
            .orderByAsc(TeachingClass::getSubjectId, TeachingClass::getTerm, TeachingClass::getName, TeachingClass::getId);
        if (!roleCodes.contains(ROLE_ADMIN)) {
            wrapper.eq(TeachingClass::getTeacherId, userId);
        }
        List<TeachingClass> classes = teachingClassMapper.selectList(wrapper);
        if (classes.isEmpty()) {
            return List.of();
        }

        Set<Long> subjectIds = classes.stream()
            .map(TeachingClass::getSubjectId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty()
            ? Map.of()
            : subjectMapper.selectBatchIds(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId, item -> item, (a, b) -> a));

        Set<Long> teacherIds = classes.stream()
            .map(TeachingClass::getTeacherId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> teacherMap = teacherIds.isEmpty()
            ? Map.of()
            : userMapper.selectBatchIds(teacherIds).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (a, b) -> a));

        return classes.stream().map(item -> {
            Subject subject = subjectMap.get(item.getSubjectId());
            User teacher = teacherMap.get(item.getTeacherId());
            return TeachingClassOptionView.builder()
                .id(item.getId())
                .name(item.getName())
                .subjectId(item.getSubjectId())
                .subjectName(subject == null ? null : subject.getName())
                .teacherId(item.getTeacherId())
                .teacherName(teacher == null ? null : teacher.getRealName())
                .term(item.getTerm())
                .status(item.getStatus())
                .build();
        }).toList();
    }
}
