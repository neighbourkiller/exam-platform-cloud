package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.TeacherProfile;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.TeacherProfileMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileSyncService {

    private static final String ROLE_TEACHER = "TEACHER";
    private static final String ROLE_STUDENT = "STUDENT";

    private final TeachingClassMapper teachingClassMapper;
    private final StudentTeachingClassMapper studentTeachingClassMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final TeacherProfileMapper teacherProfileMapper;
    private final TeachingClassAdminService teachingClassAdminService;

    public UserProfileSyncService(TeachingClassMapper teachingClassMapper,
                                  StudentTeachingClassMapper studentTeachingClassMapper,
                                  StudentProfileMapper studentProfileMapper,
                                  TeacherProfileMapper teacherProfileMapper,
                                  TeachingClassAdminService teachingClassAdminService) {
        this.teachingClassMapper = teachingClassMapper;
        this.studentTeachingClassMapper = studentTeachingClassMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.teacherProfileMapper = teacherProfileMapper;
        this.teachingClassAdminService = teachingClassAdminService;
    }

    public Map<Long, List<TeachingClassView>> buildUserTeachingClassMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<StudentTeachingClass> bindings = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .in(StudentTeachingClass::getStudentId, userIds)
                .orderByAsc(StudentTeachingClass::getSubjectId, StudentTeachingClass::getTeachingClassId)
        );
        if (bindings.isEmpty()) {
            return Map.of();
        }

        Set<Long> classIds = bindings.stream()
            .map(StudentTeachingClass::getTeachingClassId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, TeachingClassView> classViewMap = teachingClassAdminService.toTeachingClassViews(
            teachingClassMapper.selectBatchIds(classIds)
        ).stream().collect(Collectors.toMap(TeachingClassView::getId, item -> item, (a, b) -> a));

        Map<Long, List<TeachingClassView>> result = new java.util.HashMap<>();
        for (StudentTeachingClass binding : bindings) {
            TeachingClassView view = classViewMap.get(binding.getTeachingClassId());
            if (view == null) {
                continue;
            }
            result.computeIfAbsent(binding.getStudentId(), key -> new ArrayList<>()).add(view);
        }
        return result;
    }

    public Map<Long, List<TeachingClassView>> buildTeacherTeachingClassMap(List<Long> teacherIds) {
        if (teacherIds == null || teacherIds.isEmpty()) {
            return Map.of();
        }
        List<TeachingClass> classes = teachingClassMapper.selectList(
            new LambdaQueryWrapper<TeachingClass>()
                .in(TeachingClass::getTeacherId, teacherIds)
                .orderByAsc(TeachingClass::getSubjectId, TeachingClass::getId)
        );
        if (classes.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<TeachingClassView>> result = new java.util.HashMap<>();
        for (TeachingClassView view : teachingClassAdminService.toTeachingClassViews(classes)) {
            if (view.getTeacherId() == null) {
                continue;
            }
            result.computeIfAbsent(view.getTeacherId(), key -> new ArrayList<>()).add(view);
        }
        return result;
    }

    public Map<Long, StudentProfile> buildStudentProfileMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return studentProfileMapper.selectList(
            new LambdaQueryWrapper<StudentProfile>().in(StudentProfile::getUserId, userIds)
        ).stream().collect(Collectors.toMap(
            StudentProfile::getUserId,
            item -> item,
            (a, b) -> a
        ));
    }

    public void validateStudentNoAvailable(Long currentUserId, String studentNo) {
        String normalizedStudentNo = normalizeText(studentNo);
        if (normalizedStudentNo == null) {
            return;
        }
        List<StudentProfile> profiles = studentProfileMapper.selectList(
            new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getStudentNo, normalizedStudentNo)
        );
        boolean occupied = profiles.stream()
            .anyMatch(profile -> !Objects.equals(profile.getUserId(), currentUserId));
        if (occupied) {
            throw new BusinessException("学号已存在");
        }
    }

    @Transactional
    public void syncProfilesByRoleCodes(Long userId, List<String> roleCodes) {
        if (roleCodes.contains(ROLE_STUDENT)) {
            StudentProfile profile = studentProfileMapper.selectOne(
                new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, userId).last("limit 1")
            );
            if (profile == null) {
                profile = new StudentProfile();
                profile.setUserId(userId);
                profile.setStatus("ACTIVE");
                studentProfileMapper.insert(profile);
            }
        } else {
            studentProfileMapper.delete(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, userId));
            studentTeachingClassMapper.delete(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getStudentId, userId));
        }

        if (roleCodes.contains(ROLE_TEACHER)) {
            TeacherProfile profile = teacherProfileMapper.selectOne(
                new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getUserId, userId).last("limit 1")
            );
            if (profile == null) {
                profile = new TeacherProfile();
                profile.setUserId(userId);
                profile.setStatus("ACTIVE");
                teacherProfileMapper.insert(profile);
            }
        } else {
            teacherProfileMapper.delete(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getUserId, userId));
        }
    }

    @Transactional
    public void syncStudentProfile(Long userId, String studentNo, String enrollmentYear) {
        String normalizedStudentNo = normalizeText(studentNo);
        String normalizedEnrollmentYear = normalizeText(enrollmentYear);
        validateStudentNoAvailable(userId, normalizedStudentNo);
        StudentProfile profile = studentProfileMapper.selectOne(
            new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, userId).last("limit 1")
        );
        if (profile == null) {
            profile = new StudentProfile();
            profile.setUserId(userId);
            profile.setStatus("ACTIVE");
            profile.setStudentNo(normalizedStudentNo);
            profile.setEnrollmentYear(normalizedEnrollmentYear);
            studentProfileMapper.insert(profile);
            return;
        }
        profile.setStudentNo(normalizedStudentNo);
        profile.setEnrollmentYear(normalizedEnrollmentYear);
        studentProfileMapper.updateById(profile);
    }

    @Transactional
    public void syncStudentNo(Long userId, String studentNo) {
        String normalizedStudentNo = normalizeText(studentNo);
        validateStudentNoAvailable(userId, normalizedStudentNo);
        StudentProfile profile = studentProfileMapper.selectOne(
            new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, userId).last("limit 1")
        );
        if (profile == null) {
            profile = new StudentProfile();
            profile.setUserId(userId);
            profile.setStatus("ACTIVE");
            profile.setStudentNo(normalizedStudentNo);
            studentProfileMapper.insert(profile);
            return;
        }
        profile.setStudentNo(normalizedStudentNo);
        studentProfileMapper.updateById(profile);
    }

    @Transactional
    public void syncTeacherProfile(Long userId, String teacherNo, String title) {
        TeacherProfile profile = teacherProfileMapper.selectOne(
            new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getUserId, userId).last("limit 1")
        );
        if (profile == null) {
            profile = new TeacherProfile();
            profile.setUserId(userId);
            profile.setStatus("ACTIVE");
            profile.setTeacherNo(normalizeText(teacherNo));
            profile.setTitle(normalizeText(title));
            teacherProfileMapper.insert(profile);
            return;
        }
        profile.setTeacherNo(normalizeText(teacherNo));
        profile.setTitle(normalizeText(title));
        teacherProfileMapper.updateById(profile);
    }

    @Transactional
    public void updateStudentTeachingClasses(Long userId, List<Long> teachingClassIds, List<String> roleCodes) {
        List<String> safeRoleCodes = roleCodes == null ? List.of() : roleCodes;
        List<Long> targetIds = validateStudentTeachingClasses(teachingClassIds, roleCodes);

        if (!safeRoleCodes.contains(ROLE_STUDENT)) {
            studentTeachingClassMapper.delete(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getStudentId, userId));
            return;
        }

        studentTeachingClassMapper.delete(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getStudentId, userId));
        if (targetIds.isEmpty()) {
            return;
        }

        List<TeachingClass> classes = teachingClassMapper.selectBatchIds(targetIds);
        if (classes.size() != targetIds.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        Map<Long, TeachingClass> classMap = classes.stream()
            .collect(Collectors.toMap(TeachingClass::getId, item -> item, (a, b) -> a));

        Set<Long> subjectSet = new LinkedHashSet<>();
        for (Long classId : targetIds) {
            TeachingClass teachingClass = classMap.get(classId);
            if (teachingClass == null) {
                throw new BusinessException("存在无效教学班ID");
            }
            if (!subjectSet.add(teachingClass.getSubjectId())) {
                throw new BusinessException("同一课程仅可分配一个教学班");
            }
        }

        for (Long classId : targetIds) {
            TeachingClass teachingClass = classMap.get(classId);
            StudentTeachingClass relation = new StudentTeachingClass();
            relation.setStudentId(userId);
            relation.setSubjectId(teachingClass.getSubjectId());
            relation.setTeachingClassId(classId);
            relation.setEnrollStatus("ACTIVE");
            relation.setEnrolledAt(LocalDateTime.now());
            studentTeachingClassMapper.insert(relation);
        }
    }

    public List<Long> validateStudentTeachingClasses(List<Long> teachingClassIds, List<String> roleCodes) {
        List<String> safeRoleCodes = roleCodes == null ? List.of() : roleCodes;
        boolean isStudent = safeRoleCodes.contains(ROLE_STUDENT);
        List<Long> targetIds = teachingClassIds == null ? List.of() : teachingClassIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .toList();

        if (!isStudent) {
            if (!targetIds.isEmpty()) {
                throw new BusinessException("仅学生角色可分配教学班");
            }
            return targetIds;
        }

        if (targetIds.isEmpty()) {
            return targetIds;
        }

        List<TeachingClass> classes = teachingClassMapper.selectBatchIds(targetIds);
        if (classes.size() != targetIds.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        Map<Long, TeachingClass> classMap = classes.stream()
            .collect(Collectors.toMap(TeachingClass::getId, item -> item, (a, b) -> a));

        Set<Long> subjectSet = new LinkedHashSet<>();
        for (Long classId : targetIds) {
            TeachingClass teachingClass = classMap.get(classId);
            if (teachingClass == null) {
                throw new BusinessException("存在无效教学班ID");
            }
            if (!subjectSet.add(teachingClass.getSubjectId())) {
                throw new BusinessException("同一课程仅可分配一个教学班");
            }
        }
        return targetIds;
    }

    @Transactional
    public void deleteProfilesAndTeachingClasses(Long userId) {
        studentTeachingClassMapper.delete(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getStudentId, userId));
        studentProfileMapper.delete(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, userId));
        teacherProfileMapper.delete(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getUserId, userId));
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
