package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExamAccessService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_TEACHER = "TEACHER";
    private static final String ENROLL_STATUS_ACTIVE = "ACTIVE";

    private final ExamMapper examMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final StudentTeachingClassMapper studentTeachingClassMapper;
    private final UserMapper userMapper;

    public ExamAccessService(ExamMapper examMapper,
                             ExamTargetClassMapper examTargetClassMapper,
                             StudentTeachingClassMapper studentTeachingClassMapper,
                             UserMapper userMapper) {
        this.examMapper = examMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.studentTeachingClassMapper = studentTeachingClassMapper;
        this.userMapper = userMapper;
    }

    public Long getCurrentUserId() {
        return SecurityUtils.getCurrentUserId();
    }

    public Set<String> getCurrentRoleCodes() {
        return new HashSet<>(userMapper.selectRoleCodes(getCurrentUserId()));
    }

    public Exam ensureExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在");
        }
        return exam;
    }

    public void ensureExamManagePermission(Exam exam) {
        Long currentUserId = getCurrentUserId();
        Set<String> roleCodes = new HashSet<>(userMapper.selectRoleCodes(currentUserId));
        if (roleCodes.contains(ROLE_ADMIN)) {
            return;
        }
        if (roleCodes.contains(ROLE_TEACHER) && Objects.equals(exam.getPublisherId(), currentUserId)) {
            return;
        }
        throw new BusinessException("无权限操作该考试");
    }

    public void checkStudentAccess(Long examId, Long studentId) {
        List<Long> classIds = listActiveTeachingClassIdsByStudent(studentId);
        if (classIds.isEmpty()) {
            throw new BusinessException("未关联教学班");
        }
        long count = examTargetClassMapper.selectCount(new LambdaQueryWrapper<ExamTargetClass>()
            .eq(ExamTargetClass::getExamId, examId)
            .in(ExamTargetClass::getClassId, classIds));
        if (count == 0) {
            throw new BusinessException("无考试访问权限");
        }
    }

    public List<Long> listActiveTeachingClassIdsByStudent(Long studentId) {
        return studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .eq(StudentTeachingClass::getStudentId, studentId)
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
        ).stream().map(StudentTeachingClass::getTeachingClassId).toList();
    }

    public void validateTeachingClassTeacher(TeachingClass teachingClass) {
        Long teacherId = teachingClass.getTeacherId();
        if (teacherId == null) {
            throw new BusinessException("教学班未配置任课老师");
        }
        User teacher = userMapper.selectById(teacherId);
        if (teacher == null) {
            throw new BusinessException("教学班任课老师不存在");
        }
        Set<String> roleCodes = new HashSet<>(userMapper.selectRoleCodes(teacherId));
        if (!roleCodes.contains(ROLE_TEACHER)) {
            throw new BusinessException("教学班任课老师角色非法");
        }
    }
}
