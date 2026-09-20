package com.ekusys.exam.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.entity.UserRole;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import com.ekusys.exam.repository.mapper.UserRoleMapper;
import com.ekusys.exam.teacher.dto.TeacherClassStudentView;
import com.ekusys.exam.teacher.dto.TeacherClassView;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherClassService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_STUDENT = "STUDENT";
    private static final String ENROLL_STATUS_ACTIVE = "ACTIVE";

    private final TeachingClassMapper teachingClassMapper;
    private final StudentTeachingClassMapper studentTeachingClassMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final SubjectMapper subjectMapper;

    public TeacherClassService(TeachingClassMapper teachingClassMapper,
                               StudentTeachingClassMapper studentTeachingClassMapper,
                               UserMapper userMapper,
                               UserRoleMapper userRoleMapper,
                               RoleMapper roleMapper,
                               StudentProfileMapper studentProfileMapper,
                               SubjectMapper subjectMapper) {
        this.teachingClassMapper = teachingClassMapper;
        this.studentTeachingClassMapper = studentTeachingClassMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.subjectMapper = subjectMapper;
    }

    public List<TeacherClassView> listMyClasses() {
        Long teacherId = SecurityUtils.getCurrentUserId();
        LambdaQueryWrapper<TeachingClass> wrapper = new LambdaQueryWrapper<TeachingClass>()
            .orderByAsc(TeachingClass::getSubjectId, TeachingClass::getTerm, TeachingClass::getName, TeachingClass::getId);
        if (!isAdminCurrentUser()) {
            wrapper.eq(TeachingClass::getTeacherId, teacherId);
        }
        List<TeachingClass> classes = teachingClassMapper.selectList(wrapper);
        if (classes.isEmpty()) {
            return List.of();
        }

        Set<Long> subjectIds = classes.stream().map(TeachingClass::getSubjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty()
            ? Collections.emptyMap()
            : subjectMapper.selectBatchIds(subjectIds).stream().collect(Collectors.toMap(Subject::getId, e -> e, (a, b) -> a));

        Set<Long> classIds = classes.stream().map(TeachingClass::getId).collect(Collectors.toSet());
        Map<Long, Long> classStudentCount = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .in(StudentTeachingClass::getTeachingClassId, classIds)
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
        ).stream().collect(Collectors.groupingBy(
            StudentTeachingClass::getTeachingClassId,
            Collectors.counting()
        ));

        return classes.stream().map(tc -> {
            Subject subject = subjectMap.get(tc.getSubjectId());
            return TeacherClassView.builder()
                .id(tc.getId())
                .name(tc.getName())
                .subjectId(tc.getSubjectId())
                .subjectName(subject == null ? null : subject.getName())
                .term(tc.getTerm())
                .status(tc.getStatus())
                .capacity(tc.getCapacity())
                .studentCount(classStudentCount.getOrDefault(tc.getId(), 0L))
                .build();
        }).toList();
    }

    public List<TeacherClassStudentView> listClassStudents(Long classId) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        List<StudentTeachingClass> relations = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .eq(StudentTeachingClass::getTeachingClassId, classId)
                .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId())
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
                .orderByAsc(StudentTeachingClass::getStudentId)
        );
        return toStudentViews(relations);
    }

    public PageResponse<TeacherClassStudentView> queryStudentCandidates(Long classId, long pageNum, long pageSize, String keyword) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        long current = pageNum <= 0 ? 1 : pageNum;
        long size = pageSize <= 0 ? 10 : pageSize;

        Set<Long> studentRoleUserIds = findStudentRoleUserIds();
        if (studentRoleUserIds.isEmpty()) {
            return PageResponse.<TeacherClassStudentView>builder()
                .pageNum(current)
                .pageSize(size)
                .total(0)
                .records(List.of())
                .build();
        }

        Set<Long> inCurrentClass = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .eq(StudentTeachingClass::getTeachingClassId, classId)
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
        ).stream().map(StudentTeachingClass::getStudentId).collect(Collectors.toSet());
        Set<Long> occupiedSameSubjectOtherClass = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId())
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
                .ne(StudentTeachingClass::getTeachingClassId, classId)
        ).stream().map(StudentTeachingClass::getStudentId).collect(Collectors.toSet());

        Set<Long> availableIds = new LinkedHashSet<>(studentRoleUserIds);
        availableIds.removeAll(inCurrentClass);
        availableIds.removeAll(occupiedSameSubjectOtherClass);
        if (availableIds.isEmpty()) {
            return PageResponse.<TeacherClassStudentView>builder()
                .pageNum(current)
                .pageSize(size)
                .total(0)
                .records(List.of())
                .build();
        }

        String queryKeyword = keyword == null ? null : keyword.trim();
        Set<Long> studentNoMatchedIds = findStudentNoMatchedUserIds(queryKeyword);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
            .in(User::getId, availableIds)
            .orderByAsc(User::getUsername, User::getId);
        if (queryKeyword != null && !queryKeyword.isBlank()) {
            wrapper.and(q -> {
                q.like(User::getUsername, queryKeyword)
                    .or()
                    .like(User::getRealName, queryKeyword);
                if (!studentNoMatchedIds.isEmpty()) {
                    q.or().in(User::getId, studentNoMatchedIds);
                }
            });
        }

        Page<User> page = userMapper.selectPage(new Page<>(current, size), wrapper);
        Map<Long, StudentProfile> profileMap = findStudentProfileMap(page.getRecords().stream().map(User::getId).toList());

        List<TeacherClassStudentView> records = page.getRecords().stream().map(user ->
            TeacherClassStudentView.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .studentNo(profileMap.containsKey(user.getId()) ? profileMap.get(user.getId()).getStudentNo() : null)
                .build()
        ).toList();

        return PageResponse.<TeacherClassStudentView>builder()
            .pageNum(page.getCurrent())
            .pageSize(page.getSize())
            .total(page.getTotal())
            .records(records)
            .build();
    }

    @Transactional
    public void addStudents(Long classId, List<Long> studentIds) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        List<Long> targets = studentIds == null ? List.of() : studentIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (targets.isEmpty()) {
            throw new BusinessException("请选择学生");
        }

        Set<Long> studentRoleUserIds = findStudentRoleUserIds();
        for (Long studentId : targets) {
            User user = userMapper.selectById(studentId);
            if (user == null) {
                throw new BusinessException("学生不存在: " + studentId);
            }
            if (!studentRoleUserIds.contains(studentId)) {
                throw new BusinessException("用户不是学生: " + studentId);
            }

            long existingCurrentClass = studentTeachingClassMapper.selectCount(
                new LambdaQueryWrapper<StudentTeachingClass>()
                    .eq(StudentTeachingClass::getStudentId, studentId)
                    .eq(StudentTeachingClass::getTeachingClassId, classId)
                    .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
            );
            if (existingCurrentClass > 0) {
                continue;
            }

            long occupiedSameSubject = studentTeachingClassMapper.selectCount(
                new LambdaQueryWrapper<StudentTeachingClass>()
                    .eq(StudentTeachingClass::getStudentId, studentId)
                    .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId())
                    .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
                    .ne(StudentTeachingClass::getTeachingClassId, classId)
            );
            if (occupiedSameSubject > 0) {
                throw new BusinessException("学生已在同课程其他教学班: " + studentId);
            }

            StudentTeachingClass relation = new StudentTeachingClass();
            relation.setStudentId(studentId);
            relation.setSubjectId(teachingClass.getSubjectId());
            relation.setTeachingClassId(classId);
            relation.setEnrollStatus(ENROLL_STATUS_ACTIVE);
            relation.setEnrolledAt(LocalDateTime.now());
            studentTeachingClassMapper.insert(relation);
        }
    }

    @Transactional
    public void removeStudent(Long classId, Long studentId) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        int deleted = studentTeachingClassMapper.delete(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .eq(StudentTeachingClass::getTeachingClassId, classId)
                .eq(StudentTeachingClass::getStudentId, studentId)
                .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId())
        );
        if (deleted <= 0) {
            throw new BusinessException("学生不在当前教学班");
        }
    }

    private TeachingClass ensureOwnClass(Long classId) {
        TeachingClass teachingClass = teachingClassMapper.selectById(classId);
        if (teachingClass == null) {
            throw new BusinessException("教学班不存在");
        }
        if (isAdminCurrentUser()) {
            return teachingClass;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!Objects.equals(currentUserId, teachingClass.getTeacherId())) {
            throw new BusinessException("无权限管理该教学班");
        }
        return teachingClass;
    }

    private boolean isAdminCurrentUser() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return userMapper.selectRoleCodes(currentUserId).contains(ROLE_ADMIN);
    }

    private List<TeacherClassStudentView> toStudentViews(List<StudentTeachingClass> relations) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds = relations.stream().map(StudentTeachingClass::getStudentId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(studentIds).stream()
            .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        Map<Long, StudentProfile> profileMap = findStudentProfileMap(studentIds);

        return studentIds.stream().map(studentId -> {
            User user = userMap.get(studentId);
            StudentProfile profile = profileMap.get(studentId);
            return TeacherClassStudentView.builder()
                .id(studentId)
                .username(user == null ? null : user.getUsername())
                .realName(user == null ? null : user.getRealName())
                .studentNo(profile == null ? null : profile.getStudentNo())
                .build();
        }).toList();
    }

    private Set<Long> findStudentRoleUserIds() {
        Role studentRole = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getCode, ROLE_STUDENT).last("limit 1"));
        if (studentRole == null) {
            return Set.of();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, studentRole.getId()))
            .stream()
            .map(UserRole::getUserId)
            .collect(Collectors.toSet());
    }

    private Map<Long, StudentProfile> findStudentProfileMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return studentProfileMapper.selectList(
            new LambdaQueryWrapper<StudentProfile>().in(StudentProfile::getUserId, userIds)
        ).stream().collect(Collectors.toMap(StudentProfile::getUserId, profile -> profile, (a, b) -> a, HashMap::new));
    }

    private Set<Long> findStudentNoMatchedUserIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Set.of();
        }
        return studentProfileMapper.selectList(
            new LambdaQueryWrapper<StudentProfile>().like(StudentProfile::getStudentNo, keyword)
        ).stream().map(StudentProfile::getUserId).collect(Collectors.toSet());
    }
}
