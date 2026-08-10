package com.ekusys.exam.runtime.entry;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeExamDefinitionRepository {
    private final JdbcTemplate jdbc;

    public RuntimeExamDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RuntimeExamDefinition find(Long examId) {
        List<RuntimeExamDefinition> rows = jdbc.query(
            """
                select exam_id,name,start_time,end_time,duration_minutes,pass_score,
                       paper_snapshot_id,paper_snapshot_version,publisher_id,
                       proctoring_level,proctoring_config_json,exam_status,
                       provisioning_status,candidate_count,prepared_count
                  from runtime_exam_definition
                 where exam_id=?
                """,
            (rs, rowNum) -> new RuntimeExamDefinition(
                rs.getLong("exam_id"), rs.getString("name"),
                rs.getObject("start_time", java.time.LocalDateTime.class),
                rs.getObject("end_time", java.time.LocalDateTime.class),
                rs.getInt("duration_minutes"), rs.getInt("pass_score"),
                rs.getObject("paper_snapshot_id", Long.class),
                rs.getLong("paper_snapshot_version"),
                rs.getObject("publisher_id", Long.class), rs.getString("proctoring_level"),
                rs.getString("proctoring_config_json"), rs.getString("exam_status"),
                rs.getString("provisioning_status"), rs.getInt("candidate_count"),
                rs.getInt("prepared_count")
            ),
            examId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RuntimeEntrySession findSession(Long examId, Long studentId) {
        List<RuntimeEntrySession> rows = jdbc.query(
            """
                select s.id,s.exam_id,s.student_id,s.status,s.start_time,s.deadline_time,
                       s.active_client_id,s.active_client_token,s.active_client_lease_until,
                       sub.id submission_id,coalesce(sub.draft_version,0) draft_version
                  from exam_session s
                  left join submission sub
                    on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                 where s.exam_id=? and s.student_id=?
                 limit 1
                """,
            (rs, rowNum) -> new RuntimeEntrySession(
                rs.getLong("id"), rs.getLong("exam_id"), rs.getLong("student_id"),
                rs.getString("status"),
                rs.getObject("start_time", java.time.LocalDateTime.class),
                rs.getObject("deadline_time", java.time.LocalDateTime.class),
                rs.getString("active_client_id"), rs.getString("active_client_token"),
                rs.getObject("active_client_lease_until", java.time.LocalDateTime.class),
                rs.getObject("submission_id", Long.class), rs.getLong("draft_version")
            ),
            examId, studentId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Long> readySnapshotsStartingWithinMinutes(int minutes) {
        return jdbc.queryForList(
            """
                select distinct paper_snapshot_id
                  from runtime_exam_definition
                 where provisioning_status='READY'
                   and end_time>current_timestamp(3)
                   and start_time<=timestampadd(minute,?,current_timestamp(3))
                   and paper_snapshot_id is not null
                 order by paper_snapshot_id
                """,
            Long.class,
            Math.max(1, minutes)
        );
    }
}
