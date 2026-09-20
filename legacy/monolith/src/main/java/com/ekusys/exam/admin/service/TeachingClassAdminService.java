package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingClassAdminService {

    private static final String ROLE_TEACHER = "TEACHER";

    private final TeachingClassMapper teachingClassMapper;
    private final SubjectMapper subjectMapper;
    private final UserMapper userMapper;

    public TeachingClassAdminService(TeachingClassMapper teachingClassMapper,
                                     SubjectMapper subjectMapper,
                                     UserMapper userMapper) {
        this.teachingClassMapper = teachingClassMapper;
        this.subjectMapper = subjectMapper;
        this.userMapper = userMapper;
    }

    public List<TeachingClassView> listTeachingClasses() {
        List<TeachingClass> classes = teachingClassMapper.selectList(
            new LambdaQueryWrapper<TeachingClass>().orderByDesc(TeachingClass::getCreateTime)
        );
        return toTeachingClassViews(classes);
    }

    @Transactional
    public Long createTeachingClass(TeachingClassCreateRequest request) {
        String status = normalizeText(request.getStatus());
        ensureTeachingClassRelation(request.getSubjectId(), request.getTeacherId());

        if (request.getId() != null && teachingClassMapper.selectById(request.getId()) != null) {
            throw new BusinessException("教学班ID已存在");
        }

        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(request.getId());
        teachingClass.setName(normalizeText(request.getName()));
        teachingClass.setSubjectId(request.getSubjectId());
        teachingClass.setTeacherId(request.getTeacherId());
        teachingClass.setTerm(normalizeText(request.getTerm()));
        teachingClass.setStatus(status == null ? "ONGOING" : status);
        teachingClass.setCapacity(request.getCapacity());
        teachingClassMapper.insert(teachingClass);
        return teachingClass.getId();
    }

    @Transactional
    public void updateTeachingClass(Long id, TeachingClassUpdateRequest request) {
        TeachingClass teachingClass = teachingClassMapper.selectById(id);
        if (teachingClass == null) {
            throw new BusinessException("教学班不存在");
        }
        ensureTeachingClassRelation(request.getSubjectId(), request.getTeacherId());

        String status = normalizeText(request.getStatus());
        teachingClass.setName(normalizeText(request.getName()));
        teachingClass.setSubjectId(request.getSubjectId());
        teachingClass.setTeacherId(request.getTeacherId());
        teachingClass.setTerm(normalizeText(request.getTerm()));
        teachingClass.setStatus(status == null ? "ONGOING" : status);
        teachingClass.setCapacity(request.getCapacity());
        teachingClassMapper.updateById(teachingClass);
    }

    public List<TeachingClassView> toTeachingClassViews(List<TeachingClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return List.of();
        }
        Set<Long> subjectIds = classes.stream()
            .map(TeachingClass::getSubjectId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty()
            ? Collections.emptyMap()
            : subjectMapper.selectBatchIds(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId, item -> item, (a, b) -> a));

        Set<Long> teacherIds = classes.stream()
            .map(TeachingClass::getTeacherId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> teacherMap = teacherIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(teacherIds).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (a, b) -> a));

        return classes.stream()
            .sorted((a, b) -> {
                int subjectCompare = compareNullableLong(a.getSubjectId(), b.getSubjectId());
                if (subjectCompare != 0) {
                    return subjectCompare;
                }
                int termCompare = String.valueOf(a.getTerm()).compareTo(String.valueOf(b.getTerm()));
                if (termCompare != 0) {
                    return termCompare;
                }
                return compareNullableLong(a.getId(), b.getId());
            })
            .map(tc -> {
                Subject subject = subjectMap.get(tc.getSubjectId());
                User teacher = teacherMap.get(tc.getTeacherId());
                return TeachingClassView.builder()
                    .id(tc.getId())
                    .name(tc.getName())
                    .subjectId(tc.getSubjectId())
                    .subjectName(subject == null ? null : subject.getName())
                    .teacherId(tc.getTeacherId())
                    .teacherName(teacher == null ? null : teacher.getRealName())
                    .term(tc.getTerm())
                    .status(tc.getStatus())
                    .capacity(tc.getCapacity())
                    .build();
            }).toList();
    }

    public void ensureTeachingClassRelation(Long subjectId, Long teacherId) {
        if (subjectId == null || subjectMapper.selectById(subjectId) == null) {
            throw new BusinessException("课程不存在");
        }
        User teacher = userMapper.selectById(teacherId);
        if (teacher == null) {
            throw new BusinessException("教师用户不存在");
        }
        if (!userMapper.selectRoleCodes(teacherId).contains(ROLE_TEACHER)) {
            throw new BusinessException("指定用户不是教师角色");
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int compareNullableLong(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return Long.compare(a, b);
    }
}
