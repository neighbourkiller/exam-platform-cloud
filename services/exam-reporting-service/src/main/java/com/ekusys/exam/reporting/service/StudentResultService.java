package com.ekusys.exam.reporting.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.StudentExamResultView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StudentResultService {
    private final JdbcTemplate jdbc;

    public StudentResultService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StudentExamResultView> listCurrentStudentResults() {
        Long studentId = SecurityUtils.getCurrentUserId();
        if (studentId == null) throw new BusinessException("未登录");
        return jdbc.query(
            """
                select e.exam_id,e.name,e.subject_name,e.start_time,e.end_time,e.duration_minutes,e.status exam_status,
                       s.submission_id,s.status submission_status,s.objective_score,s.subjective_score,s.total_score,
                       s.pass_flag,s.submitted_at
                  from rpt_exam_candidate c
                  join rpt_exam e on e.exam_id=c.exam_id
                  left join rpt_student_score s on s.exam_id=c.exam_id and s.student_id=c.student_id
                 where c.student_id=?
                 order by e.start_time desc,e.exam_id desc
                """,
            (rs, rowNum) -> StudentExamResultView.builder()
                .examId(rs.getLong("exam_id"))
                .name(rs.getString("name"))
                .subjectName(rs.getString("subject_name"))
                .startTime(rs.getObject("start_time", LocalDateTime.class))
                .endTime(rs.getObject("end_time", LocalDateTime.class))
                .durationMinutes(rs.getInt("duration_minutes"))
                .examStatus(rs.getString("exam_status"))
                .submissionId(nullableLong(rs, "submission_id"))
                .submissionStatus(rs.getString("submission_status"))
                .objectiveScore(nullableInt(rs, "objective_score"))
                .subjectiveScore(nullableInt(rs, "subjective_score"))
                .totalScore(nullableInt(rs, "total_score"))
                .passFlag(nullableBoolean(rs, "pass_flag"))
                .submittedAt(rs.getObject("submitted_at", LocalDateTime.class))
                .submitted(rs.getObject("submission_id") != null)
                .build(), studentId
        );
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getInt(column);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }

    private Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getBoolean(column);
    }
}
