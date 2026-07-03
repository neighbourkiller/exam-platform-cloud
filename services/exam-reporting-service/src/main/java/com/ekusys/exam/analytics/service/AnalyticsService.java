package com.ekusys.exam.analytics.service;

import com.ekusys.exam.analytics.dto.ClassTrendItem;
import com.ekusys.exam.analytics.dto.ExamOverviewItem;
import com.ekusys.exam.analytics.dto.ScoreDistributionItem;
import com.ekusys.exam.analytics.dto.StudentScoreItem;
import com.ekusys.exam.analytics.dto.WrongTopicItem;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private final JdbcTemplate jdbc;

    public AnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ScoreDistributionItem> scoreDistribution(Long examId) {
        ensureCanRead(examId);
        int[] buckets = new int[5];
        for (Integer score : jdbc.queryForList(
            "select coalesce(total_score,0) from rpt_student_score where exam_id=? and total_score is not null",
            Integer.class, examId)) {
            if (score < 60) buckets[0]++;
            else if (score < 70) buckets[1]++;
            else if (score < 80) buckets[2]++;
            else if (score < 90) buckets[3]++;
            else buckets[4]++;
        }
        return List.of(
            ScoreDistributionItem.builder().range("0-59").count(buckets[0]).build(),
            ScoreDistributionItem.builder().range("60-69").count(buckets[1]).build(),
            ScoreDistributionItem.builder().range("70-79").count(buckets[2]).build(),
            ScoreDistributionItem.builder().range("80-89").count(buckets[3]).build(),
            ScoreDistributionItem.builder().range("90-100").count(buckets[4]).build()
        );
    }

    public ExamOverviewItem overview(Long examId) {
        ensureCanRead(examId);
        return jdbc.queryForObject(
            """
                select count(*) total_students,
                       coalesce(sum(case when pass_flag=1 then 1 else 0 end),0) pass_count,
                       coalesce(avg(total_score),0) avg_score,
                       max(total_score) max_score,min(total_score) min_score
                  from rpt_student_score
                 where exam_id=? and total_score is not null
                """,
            (rs, rowNum) -> {
                int total = rs.getInt("total_students");
                int pass = rs.getInt("pass_count");
                return ExamOverviewItem.builder()
                    .totalStudents(total)
                    .passCount(pass)
                    .passRate(total == 0 ? 0D : round2(pass * 100D / total))
                    .avgScore(round2(rs.getDouble("avg_score")))
                    .maxScore(nullableInt(rs, "max_score"))
                    .minScore(nullableInt(rs, "min_score"))
                    .build();
            }, examId
        );
    }

    public List<ClassTrendItem> classTrend(Long examId) {
        ensureCanRead(examId);
        return jdbc.query(
            """
                select c.class_id,max(c.class_name) class_name,round(avg(s.total_score),2) avg_score
                  from rpt_exam_candidate c
                  join rpt_student_score s on s.exam_id=c.exam_id and s.student_id=c.student_id
                 where c.exam_id=? and s.total_score is not null
                 group by c.class_id
                 order by c.class_id
                """,
            (rs, rowNum) -> ClassTrendItem.builder()
                .classId(rs.getLong("class_id"))
                .className(rs.getString("class_name"))
                .avgScore(rs.getDouble("avg_score"))
                .build(), examId
        );
    }

    public List<WrongTopicItem> wrongTopics(Long examId, Integer topN) {
        ensureCanRead(examId);
        int limit = Math.max(1, Math.min(topN == null ? 10 : topN, 50));
        return jdbc.query(
            """
                select question_id,max(question_content) question_content,
                       sum(case when correct_flag=0 then 1 else 0 end) wrong_count,count(*) total_count,
                       round(sum(case when correct_flag=0 then 1 else 0 end)*100.0/count(*),2) wrong_rate
                  from rpt_objective_answer
                 where exam_id=?
                 group by question_id
                 order by wrong_rate desc,question_id
                 limit ?
                """,
            (rs, rowNum) -> WrongTopicItem.builder()
                .questionId(rs.getLong("question_id"))
                .questionContent(rs.getString("question_content"))
                .wrongRate(rs.getDouble("wrong_rate"))
                .wrongCount(rs.getInt("wrong_count"))
                .totalCount(rs.getInt("total_count"))
                .build(), examId, limit
        );
    }

    public List<StudentScoreItem> studentScores(Long examId) {
        ensureCanRead(examId);
        return jdbc.query(
            """
                select c.student_id,c.student_no,c.username,c.student_name,c.class_name,
                       s.submission_id,s.status submission_status,s.objective_score,s.subjective_score,
                       s.total_score,s.pass_flag,s.submitted_at
                  from rpt_exam_candidate c
                  left join rpt_student_score s on s.exam_id=c.exam_id and s.student_id=c.student_id
                 where c.exam_id=?
                 order by c.class_name,c.student_no,c.student_id
                """,
            (rs, rowNum) -> StudentScoreItem.builder()
                .studentId(rs.getLong("student_id"))
                .studentNo(rs.getString("student_no"))
                .username(rs.getString("username"))
                .studentName(studentName(rs))
                .classNames(rs.getString("class_name") == null ? List.of() : List.of(rs.getString("class_name")))
                .submissionId(nullableLong(rs, "submission_id"))
                .submissionStatus(rs.getString("submission_status"))
                .objectiveScore(nullableInt(rs, "objective_score"))
                .subjectiveScore(nullableInt(rs, "subjective_score"))
                .totalScore(nullableInt(rs, "total_score"))
                .passFlag(nullableBoolean(rs, "pass_flag"))
                .submittedAt(rs.getObject("submitted_at", LocalDateTime.class))
                .submitted(rs.getObject("submission_id") != null)
                .build(), examId
        );
    }

    private void ensureCanRead(Long examId) {
        List<Long> publishers = jdbc.queryForList("select publisher_id from rpt_exam where exam_id=?", Long.class, examId);
        if (publishers.isEmpty()) throw new BusinessException("考试不存在或报表尚未生成");
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")
            && !java.util.Objects.equals(publishers.getFirst(), SecurityUtils.getCurrentUserId())) {
            throw new BusinessException("无权限查看该考试分析数据");
        }
    }

    private String studentName(ResultSet rs) throws SQLException {
        String name = rs.getString("student_name");
        return name == null || name.isBlank() ? "学生" + rs.getLong("student_id") : name;
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

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
