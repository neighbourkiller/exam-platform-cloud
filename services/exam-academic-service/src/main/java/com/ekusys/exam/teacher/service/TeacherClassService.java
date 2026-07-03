package com.ekusys.exam.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.academic.client.IamUserClient;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.teacher.dto.TeacherClassStudentView;
import com.ekusys.exam.teacher.dto.TeacherClassView;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherClassService {
    private static final String ACTIVE = "ACTIVE";
    private final TeachingClassMapper teachingClassMapper;
    private final StudentTeachingClassMapper relationMapper;
    private final StudentProfileMapper profileMapper;
    private final SubjectMapper subjectMapper;
    private final IamUserClient iamUserClient;

    public TeacherClassService(TeachingClassMapper teachingClassMapper, StudentTeachingClassMapper relationMapper,
                               StudentProfileMapper profileMapper, SubjectMapper subjectMapper,
                               IamUserClient iamUserClient) {
        this.teachingClassMapper = teachingClassMapper;
        this.relationMapper = relationMapper;
        this.profileMapper = profileMapper;
        this.subjectMapper = subjectMapper;
        this.iamUserClient = iamUserClient;
    }

    public List<TeacherClassView> listMyClasses() {
        LambdaQueryWrapper<TeachingClass> query = new LambdaQueryWrapper<TeachingClass>()
            .orderByAsc(TeachingClass::getSubjectId, TeachingClass::getTerm, TeachingClass::getName);
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")) {
            query.eq(TeachingClass::getTeacherId, SecurityUtils.getCurrentUserId());
        }
        List<TeachingClass> classes = teachingClassMapper.selectList(query);
        Map<Long, Subject> subjects = subjectMapper.selectBatchIds(classes.stream().map(TeachingClass::getSubjectId).distinct().toList())
            .stream().collect(Collectors.toMap(Subject::getId, Function.identity()));
        return classes.stream().map(item -> TeacherClassView.builder().id(item.getId()).name(item.getName())
            .subjectId(item.getSubjectId()).subjectName(subjects.get(item.getSubjectId()).getName()).term(item.getTerm())
            .status(item.getStatus()).capacity(item.getCapacity()).studentCount(relationMapper.selectCount(
                new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getTeachingClassId, item.getId())
                    .eq(StudentTeachingClass::getEnrollStatus, ACTIVE))).build()).toList();
    }

    public List<TeacherClassStudentView> listClassStudents(Long classId) {
        ensureOwnClass(classId);
        List<Long> ids = relationMapper.selectList(new LambdaQueryWrapper<StudentTeachingClass>()
            .eq(StudentTeachingClass::getTeachingClassId, classId).eq(StudentTeachingClass::getEnrollStatus, ACTIVE))
            .stream().map(StudentTeachingClass::getStudentId).toList();
        return toViews(ids);
    }

    public PageResponse<TeacherClassStudentView> queryStudentCandidates(Long classId, long pageNum, long pageSize, String keyword) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        Set<Long> occupied = relationMapper.selectList(new LambdaQueryWrapper<StudentTeachingClass>()
            .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId()).eq(StudentTeachingClass::getEnrollStatus, ACTIVE))
            .stream().map(StudentTeachingClass::getStudentId).collect(Collectors.toSet());
        List<UserSummary> candidates = iamUserClient.byRole("STUDENT").getData().stream()
            .filter(user -> !occupied.contains(user.id()))
            .filter(user -> keyword == null || keyword.isBlank() || user.username().contains(keyword) || user.realName().contains(keyword))
            .toList();
        long current = Math.max(pageNum, 1); long size = Math.max(pageSize, 1);
        int from = (int) Math.min((current - 1) * size, candidates.size());
        int to = (int) Math.min(from + size, candidates.size());
        List<TeacherClassStudentView> records = candidates.subList(from, to).stream().map(this::toView).toList();
        return PageResponse.<TeacherClassStudentView>builder().pageNum(current).pageSize(size).total(candidates.size()).records(records).build();
    }

    @Transactional
    public void addStudents(Long classId, List<Long> studentIds) {
        TeachingClass teachingClass = ensureOwnClass(classId);
        Map<Long, UserSummary> users = iamUserClient.batch(new UserBatchRequest(studentIds)).getData().stream()
            .collect(Collectors.toMap(UserSummary::id, Function.identity()));
        for (Long studentId : studentIds.stream().filter(Objects::nonNull).distinct().toList()) {
            UserSummary user = users.get(studentId);
            if (user == null || !user.roles().contains("STUDENT")) throw new BusinessException("用户不是学生: " + studentId);
            if (relationMapper.selectCount(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getStudentId, studentId)
                .eq(StudentTeachingClass::getSubjectId, teachingClass.getSubjectId()).eq(StudentTeachingClass::getEnrollStatus, ACTIVE)) > 0) continue;
            StudentTeachingClass relation = new StudentTeachingClass(); relation.setStudentId(studentId);
            relation.setSubjectId(teachingClass.getSubjectId()); relation.setTeachingClassId(classId);
            relation.setEnrollStatus(ACTIVE); relation.setEnrolledAt(LocalDateTime.now()); relationMapper.insert(relation);
        }
    }

    @Transactional
    public void removeStudent(Long classId, Long studentId) {
        ensureOwnClass(classId);
        if (relationMapper.delete(new LambdaQueryWrapper<StudentTeachingClass>().eq(StudentTeachingClass::getTeachingClassId, classId)
            .eq(StudentTeachingClass::getStudentId, studentId)) == 0) throw new BusinessException("学生不在当前教学班");
    }

    private TeachingClass ensureOwnClass(Long id) {
        TeachingClass value = teachingClassMapper.selectById(id);
        if (value == null) throw new BusinessException("教学班不存在");
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN") && !Objects.equals(value.getTeacherId(), SecurityUtils.getCurrentUserId()))
            throw new BusinessException("无权限管理该教学班");
        return value;
    }

    private List<TeacherClassStudentView> toViews(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, StudentProfile> profiles = profileMapper.selectList(new LambdaQueryWrapper<StudentProfile>().in(StudentProfile::getUserId, ids))
            .stream().collect(Collectors.toMap(StudentProfile::getUserId, Function.identity()));
        return iamUserClient.batch(new UserBatchRequest(ids)).getData().stream().map(user -> {
            StudentProfile profile = profiles.get(user.id());
            return TeacherClassStudentView.builder().id(user.id()).username(user.username()).realName(user.realName())
                .studentNo(profile == null ? null : profile.getStudentNo()).build();
        }).toList();
    }

    private TeacherClassStudentView toView(UserSummary user) {
        StudentProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, user.id()));
        return TeacherClassStudentView.builder().id(user.id()).username(user.username()).realName(user.realName())
            .studentNo(profile == null ? null : profile.getStudentNo()).build();
    }
}
