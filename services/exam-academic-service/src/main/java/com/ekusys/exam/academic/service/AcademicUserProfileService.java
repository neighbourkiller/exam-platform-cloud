package com.ekusys.exam.academic.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.academic.api.AcademicUserProfileCommand;
import com.ekusys.exam.academic.api.AcademicUserSummary;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicUserProfileService {
    private final JdbcTemplate jdbc;

    public AcademicUserProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void synchronize(Long userId, AcademicUserProfileCommand command) {
        validate(userId, command);
        List<String> roles = command.roleCodes() == null ? List.of() : command.roleCodes();
        if (roles.contains("STUDENT")) {
            jdbc.update(
                """
                    insert into student_profile(id,user_id,student_no,enrollment_year,status,create_time,update_time)
                    values(?,?,?,?,'ACTIVE',current_timestamp(3),current_timestamp(3))
                    on duplicate key update student_no=coalesce(values(student_no),student_no),
                        enrollment_year=coalesce(values(enrollment_year),enrollment_year),
                        status='ACTIVE',update_time=current_timestamp(3)
                    """,
                IdWorker.getId(), userId, blankToNull(command.studentNo()), blankToNull(command.enrollmentYear())
            );
            if (command.teachingClassIds() != null) synchronizeStudentClasses(userId, command.teachingClassIds());
        } else {
            jdbc.update("delete from student_teaching_class where student_id=?", userId);
            jdbc.update("delete from student_profile where user_id=?", userId);
        }
        if (roles.contains("TEACHER")) {
            jdbc.update(
                """
                    insert into teacher_profile(id,user_id,teacher_no,title,status,create_time,update_time)
                    values(?,?,?,?,'ACTIVE',current_timestamp(3),current_timestamp(3))
                    on duplicate key update teacher_no=coalesce(values(teacher_no),teacher_no),
                        title=coalesce(values(title),title),status='ACTIVE',update_time=current_timestamp(3)
                    """,
                IdWorker.getId(), userId, blankToNull(command.teacherNo()), blankToNull(command.title())
            );
        } else {
            jdbc.update("delete from teacher_profile where user_id=?", userId);
        }
    }

    public void validate(Long userId, AcademicUserProfileCommand command) {
        List<String> roles = command.roleCodes() == null ? List.of() : command.roleCodes();
        if (roles.contains("STUDENT")) {
            String studentNo = blankToNull(command.studentNo());
            if (studentNo != null) {
                Integer count = userId == null
                    ? jdbc.queryForObject("select count(*) from student_profile where student_no=?", Integer.class, studentNo)
                    : jdbc.queryForObject(
                        "select count(*) from student_profile where student_no=? and user_id<>?",
                        Integer.class, studentNo, userId
                    );
                if (count != null && count > 0) {
                    throw new BusinessException("学号已存在");
                }
            }
            validateStudentClasses(command.teachingClassIds());
        } else if (command.teachingClassIds() != null && !command.teachingClassIds().isEmpty()) {
            throw new BusinessException("仅学生角色可分配教学班");
        }
    }

    private void validateStudentClasses(List<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return;
        }
        List<Long> ids = classIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException("存在无效教学班ID");
        }
        if (ids.size() != classIds.stream().filter(java.util.Objects::nonNull).count()) {
            throw new BusinessException("教学班ID不能重复");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Long> subjectIds = jdbc.queryForList(
            "select subject_id from teaching_class where id in (" + placeholders + ")",
            Long.class,
            ids.toArray()
        );
        if (subjectIds.size() != ids.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        if (new HashSet<>(subjectIds).size() != subjectIds.size()) {
            throw new BusinessException("同一课程仅可分配一个教学班");
        }
    }

    @Transactional
    public void delete(Long userId) {
        jdbc.update("delete from student_teaching_class where student_id=?", userId);
        jdbc.update("delete from student_profile where user_id=?", userId);
        jdbc.update("delete from teacher_profile where user_id=?", userId);
    }

    public List<AcademicUserSummary> summaries(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        Map<Long, ProfileRow> profiles = new LinkedHashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        jdbc.query("select user_id,student_no,enrollment_year from student_profile where user_id in (" + placeholders + ")",
            (RowCallbackHandler) rs -> profiles.put(rs.getLong("user_id"), new ProfileRow(rs.getString("student_no"), rs.getString("enrollment_year"))),
            userIds.toArray());
        Map<Long, List<TeachingClassSummary>> classes = new LinkedHashMap<>();
        String classSql =
            """
                select x.user_id,c.id,c.name,c.subject_id,s.name subject_name,c.teacher_id,c.term,c.status,c.capacity
                  from (
                    select student_id user_id,teaching_class_id from student_teaching_class where enroll_status='ACTIVE'
                    union
                    select teacher_id user_id,id teaching_class_id from teaching_class
                  ) x
                  join teaching_class c on c.id=x.teaching_class_id
                  left join subject s on s.id=c.subject_id
                 where x.user_id in (
                """ + placeholders + ") order by c.id";
        jdbc.query(classSql,
            (RowCallbackHandler) rs -> classes.computeIfAbsent(rs.getLong("user_id"), ignored -> new ArrayList<>()).add(
                new TeachingClassSummary(rs.getLong("id"), rs.getString("name"), rs.getLong("subject_id"),
                    rs.getString("subject_name"), rs.getLong("teacher_id"), rs.getString("term"),
                    rs.getString("status"), (Integer) rs.getObject("capacity"))),
            userIds.toArray()
        );
        return userIds.stream().distinct().map(userId -> {
            ProfileRow profile = profiles.get(userId);
            return new AcademicUserSummary(userId, profile == null ? null : profile.studentNo(),
                profile == null ? null : profile.enrollmentYear(), classes.getOrDefault(userId, List.of()));
        }).toList();
    }

    private void synchronizeStudentClasses(Long userId, List<Long> classIds) {
        List<Long> distinctIds = classIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long classId : distinctIds) {
            Integer count = jdbc.queryForObject("select count(*) from teaching_class where id=?", Integer.class, classId);
            if (count == null || count == 0) throw new BusinessException("教学班不存在: " + classId);
        }
        jdbc.update("delete from student_teaching_class where student_id=?", userId);
        for (Long classId : distinctIds) {
            Long subjectId = jdbc.queryForObject("select subject_id from teaching_class where id=?", Long.class, classId);
            jdbc.update(
                "insert into student_teaching_class(id,student_id,subject_id,teaching_class_id,enroll_status,enrolled_at,create_time,update_time) values(?,?,?,?,'ACTIVE',?,current_timestamp(3),current_timestamp(3))",
                IdWorker.getId(), userId, subjectId, classId, LocalDateTime.now()
            );
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ProfileRow(String studentNo, String enrollmentYear) {
    }
}
