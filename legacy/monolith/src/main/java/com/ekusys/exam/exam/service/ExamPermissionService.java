package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExamPermissionService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_TEACHER = "TEACHER";

    private final UserMapper userMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final TeachingClassMapper teachingClassMapper;

    public ExamPermissionService(UserMapper userMapper,
                                 ExamTargetClassMapper examTargetClassMapper,
                                 TeachingClassMapper teachingClassMapper) {
        this.userMapper = userMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.teachingClassMapper = teachingClassMapper;
    }

    public void ensureCanManageExam(Exam exam, String denyMessage) {
        if (exam == null) {
            throw new BusinessException("考试不存在");
        }
        if (!canManageExam(exam)) {
            throw new BusinessException(denyMessage);
        }
    }

    public boolean canManageExam(Exam exam) {
        if (exam == null) {
            return false;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Set<String> roleCodes = currentRoleCodes(currentUserId);
        if (roleCodes.contains(ROLE_ADMIN)) {
            return true;
        }
        if (!roleCodes.contains(ROLE_TEACHER)) {
            return false;
        }
        if (Objects.equals(exam.getPublisherId(), currentUserId)) {
            return true;
        }
        return listTargetClasses(exam.getId()).stream()
            .filter(Objects::nonNull)
            .anyMatch(item -> Objects.equals(item.getTeacherId(), currentUserId));
    }

    public List<Exam> filterManageableExams(Collection<Exam> exams) {
        if (exams == null || exams.isEmpty()) {
            return List.of();
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Set<String> roleCodes = currentRoleCodes(currentUserId);
        if (roleCodes.contains(ROLE_ADMIN)) {
            return exams.stream().filter(Objects::nonNull).toList();
        }
        if (!roleCodes.contains(ROLE_TEACHER)) {
            return List.of();
        }

        List<Exam> candidates = exams.stream()
            .filter(Objects::nonNull)
            .toList();
        Set<Long> publishedByCurrentUser = candidates.stream()
            .filter(item -> Objects.equals(item.getPublisherId(), currentUserId))
            .map(Exam::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> examIds = candidates.stream()
            .map(Exam::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<TeachingClass>> targetClassesByExamId = listTargetClassesByExamIds(examIds);

        return candidates.stream()
            .filter(item -> publishedByCurrentUser.contains(item.getId())
                || targetClassesByExamId.getOrDefault(item.getId(), List.of()).stream()
                    .anyMatch(clazz -> Objects.equals(clazz.getTeacherId(), currentUserId)))
            .toList();
    }

    public List<TeachingClass> listTargetClasses(Long examId) {
        if (examId == null) {
            return List.of();
        }
        return listTargetClassesByExamIds(Set.of(examId)).getOrDefault(examId, List.of());
    }

    private Set<String> currentRoleCodes(Long currentUserId) {
        if (currentUserId == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(userMapper.selectRoleCodes(currentUserId));
    }

    private Map<Long, List<TeachingClass>> listTargetClassesByExamIds(Set<Long> examIds) {
        if (examIds == null || examIds.isEmpty()) {
            return Map.of();
        }
        List<ExamTargetClass> links = examTargetClassMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().in(ExamTargetClass::getExamId, examIds)
        );
        if (links.isEmpty()) {
            return Map.of();
        }

        Set<Long> classIds = links.stream()
            .map(ExamTargetClass::getClassId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TeachingClass> classMap = classIds.isEmpty()
            ? Map.of()
            : teachingClassMapper.selectBatchIds(classIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(TeachingClass::getId, item -> item, (a, b) -> a));

        Map<Long, List<TeachingClass>> result = new HashMap<>();
        for (ExamTargetClass link : links) {
            TeachingClass teachingClass = classMap.get(link.getClassId());
            if (teachingClass == null) {
                continue;
            }
            result.computeIfAbsent(link.getExamId(), key -> new java.util.ArrayList<>()).add(teachingClass);
        }
        return result;
    }
}
